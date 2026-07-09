/**
 * @file
 * @brief Functions for android support
**/

/*
 * This is essentially the input/output adapter for the android code,
 * interfacing via the Java Native Interface.
 * Originally based off of libunix.cc

   Aug 2012 Michael Barlow <michaelbarlow7@gmail.com>
   Updated for 0.34.1 compatibility                                    */

#include "AppHdr.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdarg.h>
#include <ctype.h>
#include "libunix.h"
#include "defines.h"

#include "cio.h"
#include "delay.h"
#include "enum.h"
#include "externs.h"
#include "player.h"
#include "libutil.h"
#include "options.h"
#include "files.h"
#include "state.h"
#include "unicode.h"
#include "view.h"
#include "viewgeom.h"

// main.h was removed in 0.34.1; forward-declare main() directly
extern int main(int argc, char *argv[]);

#include <wchar.h>
#include <locale.h>
#include <termios.h>

#include <time.h>
#include <jni.h>
#include <android/log.h>
// Log template is as follows:
// __android_log_write(ANDROID_LOG_ERROR, "Tag", "Error here");//Or ANDROID_LOG_INFO, ...
#include <setjmp.h>


#define LINES 24
#define MENU_LINES 48
#define COLS 80

// Rows of the gameplay dungeon view (terminal rows 0..16); the message
// window starts right below. Must match the Java split layout
// (RegionRouter.MSG_START_ROW).
#define GAMEPLAY_VIEW_LINES 17

// Word wrap (Android preference). Set by NativeWrapper.setWordwrap before
// initGame. android_layout_lines is read by the patched
// crawl_view_geometry::init_geometry (viewgeom.cc.patch) to clamp the
// gameplay layout height: 24 stock, 17 + msg rows when word wrap extends
// the message window. The wrap width / msg rows are handed to crawl as
// -extra-opt-first options in initGame.
// Non-static: message.cc.patch reads android_msg_wrap_cols to anchor the
// more-prompt below the newest message instead of the window's last row;
// ui.cc.patch reads android_prose_wrap_cols to cap the wrap width of prose
// Text widgets (describe/god/hints screens).
int android_layout_lines = LINES;
int android_msg_wrap_cols = 0;
int android_prose_wrap_cols = 0;
static int android_msg_rows = 0;

// Probably a redundant conversion, since it gets converted later on,
// but it's a bit of leftover code from the curses stuff
#define KEY_HOME	0406		/* home key */
#define KEY_END		0550		/* end key */
#define KEY_DOWN	0402		/* down-arrow key */
#define KEY_UP		0403		/* up-arrow key */
#define KEY_LEFT	0404		/* left-arrow key */
#define KEY_RIGHT	0405		/* right-arrow key */
#define KEY_NPAGE	0522		/* next-page key */
#define KEY_PPAGE	0523		/* previous-page key */
#define KEY_A1		0534		/* upper left of keypad */
#define KEY_A3		0535		/* upper right of keypad */
#define KEY_B2		0536		/* center of keypad */
#define KEY_C1		0537		/* lower left of keypad */
#define KEY_C3		0540		/* lower right of keypad */
#define KEY_SB2		0700		/* shifted center of keypad (long-press = rest) */
#define KEY_SHOME	0607		/* shifted home key */
#define KEY_SEND	0602		/* shifted end key */
#define KEY_SLEFT	0611		/* shifted left-arrow key */
#define KEY_SRIGHT	0622		/* shifted right-arrow key */
#define KEY_BTAB	0541		/* back-tab key */
#define KEY_BACKSPACE	0407   /* backspace key */
#define KEY_DC		0512		/* delete-character key */

#define JAVA_CALL(...) (env->CallVoidMethod(NativeWrapperObj, __VA_ARGS__))
#define JAVA_CALL_INT(...) (env->CallIntMethod(NativeWrapperObj, __VA_ARGS__))
#define JAVA_METHOD(m,s) (env->GetMethodID(NativeWrapperClass, m, s))

void (*crawl_quit_hook)(void) = NULL;

static jmp_buf jbuf;

// JNI globals split into two phases:
//
//   Phase 1 — cached once in JNI_OnLoad (write-once, read-only after):
//     g_jvm, NativeWrapperClass (global ref), NativeWrapper_* method IDs.
//     The JVM serializes JNI_OnLoad via NativeWrapper's static initializer
//     lock, so subsequent reads from any thread see them initialized.
//
//   Phase 2 — captured per game session in init_java_methods:
//     env (game-thread JNIEnv) and NativeWrapperObj (instance jobject).
//     Used by same-thread crawl callbacks (getchk, sendTerminalToScreen,
//     crawl_quit). Refresh from the UI thread does NOT use these — it
//     uses the env/object passed into the JNI entry point.
static JavaVM *g_jvm;
static JNIEnv *env;

static jclass NativeWrapperClass;
static jobject NativeWrapperObj;

/* Java Methods (cached in JNI_OnLoad) */
static jmethodID NativeWrapper_fatal;
static jmethodID NativeWrapper_getch;
static jmethodID NativeWrapper_frameUpdate;
static jmethodID NativeWrapper_updateStatusLights;
static jmethodID NativeWrapper_setMessageHistoryMode;
static jmethodID NativeWrapper_setCharacterLogMode;
static jmethodID NativeWrapper_showCharacterFile;
static jmethodID NativeWrapper_notifyGameSaved;
static jmethodID NativeWrapper_setMapAnchor;

// Terminal stuff
class TerminalChar //I guess this could be a struct.
{
public:
	jint x; // If this were java
	jint y; // these two fields would be final
	jint foregroundColour;
	jint backgroundColour;
	jchar character;
	TerminalChar(){};
	TerminalChar(int py, int px)
	{
		x = px;
		y = py;
	}
};

std::map<COLOURS, int> colourMap;
TerminalChar terminalWindow[MENU_LINES][COLS];
std::set<TerminalChar *> dirtyTerminalChars;
int x = 0;
int y = 0;
jint backgroundColour; //RGB values
jint foregroundColour;
unsigned brand;

void advance()
{
		++x;
		if (x >= COLS)
		{
			++y;
			x = 0;
		}
		if (y >= MENU_LINES)
		{
			y = MENU_LINES - 1;
		}
}

TerminalChar * getTerminalCharAt(int py, int px)
{
	return &terminalWindow[py][px];
}

TerminalChar * getCurrentTerminalChar()
{
	return getTerminalCharAt(y, x);
}

// Cache the NativeWrapper class and its method IDs. Called once from
// JNI_OnLoad. Returns false on any lookup failure so the loader can
// fail fast with a clear log line rather than crashing later on a NULL
// jmethodID. To bridge a new Java method, declare it above and add one
// line here — single point of maintenance.
static bool _cache_native_wrapper_methods(JNIEnv* e)
{
	jclass local = e->FindClass("com/crawlmb/NativeWrapper");
	if (!local)
		return false;
	NativeWrapperClass = (jclass)e->NewGlobalRef(local);
	e->DeleteLocalRef(local);
	if (!NativeWrapperClass)
		return false;

	NativeWrapper_fatal = e->GetMethodID(NativeWrapperClass,
		"fatal", "(Ljava/lang/String;)V");
	NativeWrapper_getch = e->GetMethodID(NativeWrapperClass,
		"getch", "(I)I");
	NativeWrapper_frameUpdate = e->GetMethodID(NativeWrapperClass,
		"frameUpdate", "([C[I[I)V");
	NativeWrapper_updateStatusLights = e->GetMethodID(NativeWrapperClass,
		"updateStatusLights", "(Ljava/lang/String;[I)V");
	NativeWrapper_setMessageHistoryMode = e->GetMethodID(NativeWrapperClass,
		"setMessageHistoryMode", "(Z)V");
	NativeWrapper_setCharacterLogMode = e->GetMethodID(NativeWrapperClass,
		"setCharacterLogMode", "(Z)V");
	NativeWrapper_showCharacterFile = e->GetMethodID(NativeWrapperClass,
		"showCharacterFile", "(Ljava/lang/String;)V");
	NativeWrapper_notifyGameSaved = e->GetStaticMethodID(NativeWrapperClass,
		"notifyGameSaved", "(Ljava/lang/String;)V");
	NativeWrapper_setMapAnchor = e->GetMethodID(NativeWrapperClass,
		"setMapAnchor", "(II)V");

	return NativeWrapper_fatal
		&& NativeWrapper_getch
		&& NativeWrapper_frameUpdate
		&& NativeWrapper_updateStatusLights
		&& NativeWrapper_setMessageHistoryMode
		&& NativeWrapper_setCharacterLogMode
		&& NativeWrapper_showCharacterFile
		&& NativeWrapper_notifyGameSaved
		&& NativeWrapper_setMapAnchor;
}

// Called from the patched _replay_messages_core (message.cc) at entry and
// exit of the Ctrl+P / startup message history popup. Forwards to the Java
// side so RegionRouter can classify the popup as MenuType.MESSAGES and
// give fullView the full 48-row terminal region (default fullView caps at
// row 28, which clips the latest messages because FS_START_AT_END anchors
// them to the bottom of the scroller viewport).
extern "C" void android_message_history_mode(bool active)
{
	if (env == NULL || NativeWrapperObj == NULL)
		return;
	JAVA_CALL(NativeWrapper_setMessageHistoryMode, (jboolean)(active ? JNI_TRUE : JNI_FALSE));
}

// Called from patched _show_morgue (hiscores.cc) at entry and exit of the
// character-log viewer opened from the High Scores menu. Forwards to the
// Java side so RegionRouter can classify the popup as MenuType.MORGUE and
// hold that classification across scrolling. Without this, the morgue text
// (which contains "Turns:", "Skill", "Granted powers:", etc.) trips other
// content anchors as the user scrolls and the font scale ping-pongs.
extern "C" void android_character_log_mode(bool active)
{
	if (env == NULL || NativeWrapperObj == NULL)
		return;
	JAVA_CALL(NativeWrapper_setCharacterLogMode, (jboolean)(active ? JNI_TRUE : JNI_FALSE));
}

// Called from patched _show_morgue (hiscores.cc) instead of the in-terminal
// formatted_scroller: hands the morgue file path to Java, which opens it in
// the native scrolling viewer (CharFileViewer). The 48-row terminal can't
// hold a whole morgue file, and its bitmap doesn't fit the screen above the
// keyboard; the native viewer renders the full file with real vscroll.
// Game-thread only (crawl menu callback), so the cached env is valid.
extern "C" void android_show_character_file(const char *path)
{
	if (env == NULL || NativeWrapperObj == NULL || path == NULL || !*path)
		return;
	jstring jpath = env->NewStringUTF(path);
	if (jpath == NULL)
	{
		env->ExceptionClear();
		return;
	}
	JAVA_CALL(NativeWrapper_showCharacterFile, jpath);
	env->DeleteLocalRef(jpath);
}

// Called from viewmap.cc goto_level() with the 1-indexed virtual-terminal
// (col, row) where the player glyph is drawn in the level-map view. Java
// consumes this to scroll the physical LEVELMAP viewport so the player
// appears centered — required because the C++ side only controls placement
// within the 80x48 virtual grid, while the visible portion depends on the
// LEVELMAP font scale (currently 2.25x) which C++ has no view into.
extern "C" void android_map_anchor(int col, int row)
{
	if (env == NULL || NativeWrapperObj == NULL
		|| NativeWrapper_setMapAnchor == NULL)
	{
		return;
	}
	JAVA_CALL(NativeWrapper_setMapAnchor, (jint)col, (jint)row);
}

// Called from patched save_game (files.cc) after each save commit with
// the .cs path. Autosaves can run on the UI thread, where the cached
// game-thread env/NativeWrapperObj are invalid — resolve this thread's
// env and call a static method through the global class ref.
extern "C" void android_notify_game_saved(const char *path)
{
	if (g_jvm == NULL || NativeWrapperClass == NULL
		|| NativeWrapper_notifyGameSaved == NULL || path == NULL || !*path)
	{
		return;
	}
	JNIEnv *e = NULL;
	// save_game only runs on Java-attached threads (game or UI), so no
	// AttachCurrentThread fallback is needed.
	if (g_jvm->GetEnv((void**)&e, JNI_VERSION_1_6) != JNI_OK || e == NULL)
		return;
	jstring jpath = e->NewStringUTF(path);
	if (jpath == NULL)
	{
		e->ExceptionClear();
		return;
	}
	e->CallStaticVoidMethod(NativeWrapperClass,
		NativeWrapper_notifyGameSaved, jpath);
	if (e->ExceptionCheck())
		e->ExceptionClear();
	e->DeleteLocalRef(jpath);
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/)
{
	g_jvm = vm;
	JNIEnv* e = NULL;
	if (vm->GetEnv((void**)&e, JNI_VERSION_1_6) != JNI_OK)
	{
		__android_log_write(ANDROID_LOG_ERROR, "Crawl",
			"JNI_OnLoad: GetEnv failed");
		return JNI_ERR;
	}
	if (!_cache_native_wrapper_methods(e))
	{
		__android_log_write(ANDROID_LOG_ERROR, "Crawl",
			"JNI_OnLoad: failed to cache NativeWrapper methods");
		return JNI_ERR;
	}
	return JNI_VERSION_1_6;
}

// Capture the per-game-session env and instance for same-thread crawl
// callbacks. Class and method IDs are already cached in JNI_OnLoad.
void init_java_methods( JNIEnv* env1, jobject object )
{
	env = env1;
	NativeWrapperObj = object;
}

extern "C"
{
	void Java_com_crawlmb_NativeWrapper_initGame( JNIEnv* env, jobject object , jstring jDataDir, jstring jSettingsDir, jstring jMorgueDir);
	void Java_com_crawlmb_NativeWrapper_setWordwrap( JNIEnv* env, jobject object, jint msgWrapCols, jint msgRows, jint proseWrapCols);
	void Java_com_crawlmb_NativeWrapper_refreshTerminal( JNIEnv* env, jobject object);
	void Java_com_crawlmb_NativeWrapper_nativeSaveGame( JNIEnv* env, jclass clz);
};

// Called on the game thread from NativeWrapper.gameStart, before initGame
// runs main(). msgWrapCols <= 0 disables word wrap (stock 24-line layout).
void Java_com_crawlmb_NativeWrapper_setWordwrap( JNIEnv* env, jobject object, jint msgWrapCols, jint msgRows, jint proseWrapCols)
{
	if (msgWrapCols > 0 && msgRows > 0)
	{
		android_msg_wrap_cols = msgWrapCols;
		android_msg_rows = msgRows;
		if (android_msg_rows > MENU_LINES - GAMEPLAY_VIEW_LINES)
			android_msg_rows = MENU_LINES - GAMEPLAY_VIEW_LINES;
		android_layout_lines = GAMEPLAY_VIEW_LINES + android_msg_rows;
	}
	else
	{
		android_msg_wrap_cols = 0;
		android_msg_rows = 0;
		android_layout_lines = LINES;
	}
	android_prose_wrap_cols = proseWrapCols > 0 ? proseWrapCols : 0;
}

void Java_com_crawlmb_NativeWrapper_nativeSaveGame( JNIEnv* env, jclass clz)
{
	if (you.save)
		save_game(false);
}

void Java_com_crawlmb_NativeWrapper_initGame( JNIEnv* env, jobject object , jstring jDataDir, jstring jSettingsDir, jstring jMorgueDir)
{
	init_java_methods(env, object);
	const char *dataDir = env->GetStringUTFChars(jDataDir, NULL);
	const char *settingsDir = env->GetStringUTFChars(jSettingsDir, NULL);
	const char *morgueDir = env->GetStringUTFChars(jMorgueDir, NULL);

	// -rcdir puts settingsDir at the front of find_crawlrc's search list,
	// so init.txt is read from external user-visible storage (where the
	// in-app editor writes) instead of falling back to the bundled
	// ANDROID_ASSETS/settings/init.txt.
	std::vector<char*> args = {(char*)"", (char*)"-dir", (char*)dataDir,
                      (char*)"-macro", (char*)settingsDir,
                      (char*)"-morgue", (char*)morgueDir,
                      (char*)"-rcdir", (char*)settingsDir,
                      (char*)"-extra-opt-first", (char*)"char_set=ascii"};
	// Word wrap: crawl wraps messages at msg_max_width natively; the msg
	// window gets android_msg_rows rows (extra history for the bottom-
	// pinned Java panel). view_max_height pins the dungeon view at 17 rows
	// — init_geometry grows the view BEFORE the msg window, so without it
	// the extra layout lines would shift the msg window below row 17 and
	// break the Java split-panel rows. -extra-opt-first keeps init.txt
	// able to override.
	char opt_msg_width[32], opt_msg_height[32];
	if (android_msg_wrap_cols > 0)
	{
		snprintf(opt_msg_width, sizeof(opt_msg_width),
			"msg_max_width=%d", android_msg_wrap_cols);
		snprintf(opt_msg_height, sizeof(opt_msg_height),
			"msg_max_height=%d", android_msg_rows);
		args.push_back((char*)"-extra-opt-first");
		args.push_back(opt_msg_width);
		args.push_back((char*)"-extra-opt-first");
		args.push_back(opt_msg_height);
		args.push_back((char*)"-extra-opt-first");
		args.push_back((char*)"view_max_height=17");
	}
	main((int)args.size(), args.data());
}

// Deliver the whole terminal grid to Java in ONE JNI call (frameUpdate).
// Java diffs the frame against its shadow, classifies the screen BEFORE
// routing any cell, then paints — the atomic handoff is what prevents
// mid-storm tearing and routing-under-stale-state flashes, and it replaces
// ~3840 per-cell printTerminalChar round trips per full refresh.
// Row staging buffers are stack-local so the game-thread caller
// (sendTerminalToScreen) and the UI-thread caller (refreshTerminal) can't
// race over shared storage.
static void _send_frame(JNIEnv* e, jobject obj)
{
	const int cells = MENU_LINES * COLS;
	jcharArray jchars = e->NewCharArray(cells);
	jintArray jfg = e->NewIntArray(cells);
	jintArray jbg = e->NewIntArray(cells);
	if (!jchars || !jfg || !jbg)
	{
		e->ExceptionClear();
		if (jchars) e->DeleteLocalRef(jchars);
		if (jfg) e->DeleteLocalRef(jfg);
		if (jbg) e->DeleteLocalRef(jbg);
		return;
	}
	jchar rowCh[COLS];
	jint rowFg[COLS];
	jint rowBg[COLS];
	for (int i = 0; i < MENU_LINES; ++i)
	{
		for (int j = 0; j < COLS; ++j)
		{
			const TerminalChar &tc = terminalWindow[i][j];
			rowCh[j] = tc.character;
			rowFg[j] = tc.foregroundColour;
			rowBg[j] = tc.backgroundColour;
		}
		e->SetCharArrayRegion(jchars, i * COLS, COLS, rowCh);
		e->SetIntArrayRegion(jfg, i * COLS, COLS, rowFg);
		e->SetIntArrayRegion(jbg, i * COLS, COLS, rowBg);
	}
	e->CallVoidMethod(obj, NativeWrapper_frameUpdate, jchars, jfg, jbg);
	if (e->ExceptionCheck())
		e->ExceptionClear();
	e->DeleteLocalRef(jchars);
	e->DeleteLocalRef(jfg);
	e->DeleteLocalRef(jbg);
}

void Java_com_crawlmb_NativeWrapper_refreshTerminal( JNIEnv* env, jobject object)
{
	// This needs to use the passed-in JNIEnv and jobject, since this is run
	// from the UI thread. May run before initGame: terminalWindow is
	// zero-initialized then, which Java renders as blanks — don't deref
	// crawl globals here.
	_send_frame(env, object);
}

void set_mouse_enabled(bool enabled)
{
	return;
}

void sendTerminalToScreen()
{
	// dirtyTerminalChars is now only a "did anything change" gate; the
	// frame call always carries the full grid and Java diffs it. Gameplay
	// vs menu classification happens Java-side on the delivered frame, so
	// the old C-side _is_gameplay_frame / preStormHint mirror is gone.
	if (dirtyTerminalChars.empty())
	{
		return;
	}
	dirtyTerminalChars.clear();
	_send_frame(env, NativeWrapperObj);
}

void android_send_status_lights(const char** texts, const int* colours,
	int count)
{
	if (count <= 0 || !texts || !colours)
	{
		jstring empty = env->NewStringUTF("");
		jintArray arr = env->NewIntArray(0);
		if (!empty || !arr)
		{
			env->ExceptionClear();
			if (empty) env->DeleteLocalRef(empty);
			if (arr) env->DeleteLocalRef(arr);
			return;
		}
		JAVA_CALL(NativeWrapper_updateStatusLights, empty, arr);
		env->DeleteLocalRef(empty);
		env->DeleteLocalRef(arr);
		return;
	}
	std::string joined;
	jint* argb = new jint[count];
	for (int i = 0; i < count; i++)
	{
		if (i > 0)
			joined += '\t';
		if (texts[i])
			joined += texts[i];
		argb[i] = colourMap[(COLOURS)(colours[i] & 0x0f)];
	}
	jstring jtext = env->NewStringUTF(joined.c_str());
	jintArray jcolours = env->NewIntArray(count);
	if (!jtext || !jcolours)
	{
		env->ExceptionClear();
		delete[] argb;
		if (jtext) env->DeleteLocalRef(jtext);
		if (jcolours) env->DeleteLocalRef(jcolours);
		return;
	}
	env->SetIntArrayRegion(jcolours, 0, count, argb);
	delete[] argb;
	JAVA_CALL(NativeWrapper_updateStatusLights, jtext, jcolours);
	if (env->ExceptionCheck())
		env->ExceptionClear();
	env->DeleteLocalRef(jtext);
	env->DeleteLocalRef(jcolours);
}

int getchk()
{
	sendTerminalToScreen();
    int c = JAVA_CALL_INT(NativeWrapper_getch, 1);
    return c;
}

int m_getch()
{
    int c;
    do
    {
        c = getchk();

    } while ((c == CK_MOUSE_MOVE || c == CK_MOUSE_CLICK)
             && !crawl_state.mouse_enabled);

    return (c);
}

int getch_ck()
{
    int c = m_getch();
    switch (c)
    {
    // Android returns 159 for backspace and 156 for enter
    case 159:
    case KEY_BACKSPACE: return CK_BKSP;
    case 156: return CK_ENTER;
    case KEY_DC:    return CK_DELETE;
    case KEY_HOME:  return CK_HOME;
    case KEY_PPAGE: return CK_PGUP;
    case KEY_END:   return CK_END;
    case KEY_NPAGE: return CK_PGDN;
    case KEY_UP:    return CK_UP;
    case KEY_DOWN:  return CK_DOWN;
    case KEY_LEFT:  return CK_LEFT;
    case KEY_RIGHT: return CK_RIGHT;
    case KEY_A1:    return CK_HOME;
    case KEY_A3:    return CK_PGUP;
    case KEY_B2:    return CK_CLEAR;
    case KEY_C1:    return CK_END;
    case KEY_C3:    return CK_PGDN;
    case KEY_SB2:   return CK_SHIFT_CLEAR;

    default:         return c;
    }
}

static void unix_handle_terminal_resize()
{
    console_shutdown();
    console_startup();
}

int start_colour()
{
	colourMap[BLACK] = 0xFF000000; // This really should be global, or loaded in the onload, or whatever
	colourMap[BLUE] = 0xFF0040FF;
	colourMap[GREEN] = 0xFF008040;
	colourMap[CYAN] = 0xFF00A0A0;
	colourMap[RED] = 0xFFFF4040;
	colourMap[MAGENTA] = 0xFF9020FF;
	colourMap[BROWN] = 0xFFA64800;
	colourMap[LIGHTGRAY] = 0xFFC0C0C0;
	colourMap[DARKGRAY] = 0xFF606060;
	colourMap[LIGHTBLUE] = 0xFF00FFFF;
	colourMap[LIGHTGREEN] = 0xFF00FF00;
	colourMap[LIGHTCYAN] = 0xFF20FFDC;
	colourMap[LIGHTRED] = 0xFFFF5050;
	colourMap[LIGHTMAGENTA] = 0xFFFA4FFD;
	colourMap[YELLOW] = 0xFFFFFF00;
	colourMap[WHITE] = 0xFFFFFFFF;
	colourMap[NUM_TERM_COLOURS] = 0xFF008040;

	foregroundColour = colourMap[WHITE];
	backgroundColour = colourMap[BLACK];

	return 0;
}
void setUpTerminalCharacters()
{
	// Set up terminal window here
	for (int i = 0; i < MENU_LINES; ++i)
	{
		for (int j = 0; j < COLS; ++j)
		{//TODO: We'd ideally initialize all this in a constructor
			terminalWindow[i][j].x = j;
			terminalWindow[i][j].y = i;
			terminalWindow[i][j].character = ' ';
			terminalWindow[i][j].foregroundColour = colourMap[WHITE];
			terminalWindow[i][j].backgroundColour = colourMap[BLACK];
		}
	}
}
void console_startup(void)
{
    start_colour();

    setUpTerminalCharacters();

    crawl_view.init_geometry();
}

void console_shutdown()
{
    // I don't think we need to do anything here for android
}


void crawl_quit(const char* msg)
{
	if (msg)
	{
		JAVA_CALL(NativeWrapper_fatal, env->NewStringUTF(msg));
	}

	if (crawl_quit_hook)
	{
		(*crawl_quit_hook)();
	}

	longjmp(jbuf,1);
}

void advanceLine()
{
	y++;
	if (y >= MENU_LINES)
	{
		y = MENU_LINES - 1;
	}
	x = 0;
}

void clear_to_end_of_line();
void addChar(wchar_t c)
{
 	if (c == '\n')
 	{
 		// On a newline character, clear to the end of the line and
 		// advance a row
 		clear_to_end_of_line();
         do {
             advance();
         } while (x > 0);
 		return;
 	}

 	// Need to determine colours depending on brand
 	int fg = foregroundColour;
 	int bg = backgroundColour;
 	if (brand != CHATTR_NORMAL)
 	{
 		if ((brand & CHATTR_ATTRMASK) == CHATTR_HILITE)
 		{
 			COLOURS bgcolour = (COLOURS) macro_colour((brand & CHATTR_COLMASK) >> 8);
 			bg = colourMap[bgcolour];
 		}

 		if ((brand & CHATTR_ATTRMASK) == CHATTR_REVERSE)
 		{
 			int temp = fg;
 			fg = bg;
 			bg = temp;
 		}

 		if (fg == bg)
 		{
 			fg = colourMap[BLACK];
 		}
 	}

 	// Apply changes to terminalChar, if they apply
 	bool isDirty = false;
 	TerminalChar * terminalChar = getCurrentTerminalChar();
 	if (terminalChar->foregroundColour != fg)
 	{
 		terminalChar->foregroundColour = fg;
 		isDirty = true;
 	}
 	if (terminalChar->backgroundColour != bg)
 	{
 		terminalChar->backgroundColour = bg;
 		isDirty = true;
 	}
 	if (terminalChar->character != c)
 	{
 		terminalChar->character = c;
 		isDirty = true;
 	}

 	if (isDirty)
 	{
 		dirtyTerminalChars.insert(terminalChar);
 	}
 	advance();

}

int addnstr(int n, const char *s)
{
	for(int i = 0; i < n; ++i)
	{
		addChar(*s);
		++s;
	}
	return 0;
}

void cprintf(const char *format, ...)
{
    char buffer[2048];          // One full screen if no control seq...

    va_list argp;

    va_start(argp, format);
    vsnprintf(buffer, sizeof(buffer), format, argp);
    va_end(argp);

    char32_t c;
    char *bp = buffer;
    while (int s = utf8towc(&c, bp))
    {
        addChar((wchar_t)c);
        bp += s;
    }
}


void putwch(char32_t chr)
{
    wchar_t c = chr;
    if (!c)
    {
		c = ' ';
	}
    addChar(c);
}

void puttext(int x1, int y1, const crawl_view_buffer &vbuf)
{
    const screen_cell_t *cell = vbuf;
    const coord_def size = vbuf.size();
    for (int y = 0; y < size.y; ++y)
    {
        cgotoxy(x1, y1 + y);
        for (int x = 0; x < size.x; ++x)
        {
            put_colour_ch(cell->colour, cell->glyph);
            cell++;
        }
    }
    update_screen();
}

// These next four are front functions so that we can reduce
// the amount of curses special code that occurs outside this
// this file.  This is good, since there are some issues with
// name space collisions between curses macros and the standard
// C++ string class.  -- bwr
void update_screen(void)
{
    //ANDROID: We don't really need this I don't think
}

void clear_to_end_of_line(void)
{
    textcolour(LIGHTGREY);
    textbackground(BLACK);

    int curX = x;
    int curY = y;
    do
    {
		addChar(' ');
	} while (x > 0);

    // Probably should use a temporary cursor rather than doing it this way?
    // Reset cursor back to where it was
    x = curX;
    y = curY;
}

int get_number_of_lines(void)
{
    return (MENU_LINES);
}

int get_number_of_cols(void)
{
    return (COLS);
}

void clrscr_sys()
{
    textcolour(LIGHTGREY);
    textbackground(BLACK);
	int origX = x;
	int origY = y;
    x = 0;
    y = 0;
    for (int i = 0; i < MENU_LINES; ++i)
    {
		for (int j = 0; j < COLS; ++j)
		{
			addChar(' ');
		}
	}
	x = origX;
	y = origY;
}

void set_cursor_enabled(bool enabled)
{
}

bool is_cursor_enabled()
{
    return true;
}

inline unsigned get_brand(int col)
{
    return (col & COLFLAG_FRIENDLY_MONSTER) ? Options.friend_highlight :
           (col & COLFLAG_NEUTRAL_MONSTER)  ? Options.neutral_highlight :
           (col & COLFLAG_ITEM_HEAP)        ? Options.heap_highlight :
           (col & COLFLAG_WILLSTAB)         ? Options.stab_highlight :
           (col & COLFLAG_MAYSTAB)          ? Options.may_stab_highlight :
           (col & COLFLAG_FEATURE_ITEM)     ? Options.feature_item_highlight :
           (col & COLFLAG_TRAP_ITEM)        ? Options.trap_item_highlight :
           (col & COLFLAG_REVERSE)          ? CHATTR_REVERSE
                                            : CHATTR_NORMAL;
}

void textcolour(int col)
{
	COLOURS fgcolour = (COLOURS) macro_colour(col & 0x00ff);
	brand = get_brand(col);
	foregroundColour = colourMap[fgcolour];
}

void textbackground(int col)
{
	COLOURS bgcolour = (COLOURS) macro_colour(col & 0x00ff);
	brand = get_brand(col);
	backgroundColour = colourMap[bgcolour];
}


void gotoxy_sys(int px, int py)
{
	x = px - 1;
	y = py - 1;
}

inline int character_at(int py, int px)
{
	gotoxy_sys(px, py);
	return getCurrentTerminalChar()->character;
}

inline void write_char_at(int py, int px, int ch)
{
	gotoxy_sys(px, py);

	char c = ch;
	addnstr(1,&c);
}

void fakecursorxy(int px, int py)
{
	TerminalChar * flippingChar = getTerminalCharAt(py - 1, px - 1);
	int tempcolour = flippingChar->foregroundColour;
	flippingChar->foregroundColour = flippingChar->backgroundColour;
	flippingChar->backgroundColour = tempcolour;
	dirtyTerminalChars.insert(flippingChar);
}

int wherex()
{
	return x + 1;
}


int wherey()
{
	return y + 1;
}

void delay(unsigned int time)
{
    if (crawl_state.disables[DIS_DELAY])
        return;

	sendTerminalToScreen();
    if (time)
        usleep(time * 1000);
}

bool kbhit()
{
	// I don't think we need this in android, buffering is handled by java code
	return false;
}

int num_to_lines(int num)
{
    return num;
}

// Mostly taken from libunix.cc
lib_display_info::lib_display_info()
    : type("Console"),
    term("N/A"),
    fg_colors(Options.bold_brightens_foreground != false ? 16 : 8),
    bg_colors(Options.blink_brightens_background ? 16 : 8)
{
}

// Also taken from libunix.cc
COLOURS default_hover_colour()
{
    return Options.blink_brightens_background ? DARKGREY : BLUE;
}

// Headless mode stubs — new in 0.34.1, declared in libunix.h
static bool _headless_mode = false;

bool in_headless_mode()
{
    return _headless_mode;
}

void enter_headless_mode()
{
    _headless_mode = true;
}
