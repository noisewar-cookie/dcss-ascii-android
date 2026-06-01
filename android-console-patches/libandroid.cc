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
#define COLS 80

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
static jmethodID NativeWrapper_printTerminalChar;
static jmethodID NativeWrapper_invalidateTerminal;
static jmethodID NativeWrapper_preStormHint;

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
TerminalChar terminalWindow[LINES][COLS];
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
		if (y >= LINES)
		{
			y = LINES - 1;
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
	NativeWrapper_printTerminalChar = e->GetMethodID(NativeWrapperClass,
		"printTerminalChar", "(IICII)V");
	NativeWrapper_invalidateTerminal = e->GetMethodID(NativeWrapperClass,
		"invalidateTerminal", "()V");
	NativeWrapper_preStormHint = e->GetMethodID(NativeWrapperClass,
		"preStormHint", "(Z)V");

	return NativeWrapper_fatal
		&& NativeWrapper_getch
		&& NativeWrapper_printTerminalChar
		&& NativeWrapper_invalidateTerminal
		&& NativeWrapper_preStormHint;
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
	void Java_com_crawlmb_NativeWrapper_refreshTerminal( JNIEnv* env, jobject object);
	void Java_com_crawlmb_NativeWrapper_nativeSaveGame( JNIEnv* env, jclass clz);
};

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
	int argc = 11;
	char *argv[] = {(char*)"", (char*)"-dir", (char*)dataDir,
                      (char*)"-macro", (char*)settingsDir,
                      (char*)"-morgue", (char*)morgueDir,
                      (char*)"-rcdir", (char*)settingsDir,
                      (char*)"-extra-opt-first", (char*)"char_set=ascii"};
	main(argc, argv);
}

void Java_com_crawlmb_NativeWrapper_refreshTerminal( JNIEnv* env, jobject object)
{
	// This needs to use the passed-in JNIEnv and jobject, since this is run from the UI thread
	for (int i = 0; i < LINES; ++i)
	{
		for (int j = 0; j < COLS; ++j)
		{
			TerminalChar * terminalChar = &terminalWindow[i][j];
			env->CallVoidMethod(object, NativeWrapper_printTerminalChar, terminalChar->y, terminalChar->x, terminalChar->character, terminalChar->foregroundColour, terminalChar->backgroundColour);
		}
	}
	env->CallVoidMethod(object, NativeWrapper_invalidateTerminal);
}

void set_mouse_enabled(bool enabled)
{
	return;
}

// Scan terminalWindow for any HUD label at col 37 in rows 2..8. Mirrors
// RegionRouter.detectMode (Java side) but operates on the C-side grid
// before the storm flushes it. This lets Java preemptively skip
// forwarding cells to the gameplay split panels when the new frame is a
// menu — without the hint, drawPoint would tear mapView/hudView/msgView
// with menu chars at terminal coordinates while splitContainer is still
// VISIBLE (applyMode's hide runs later on the UI thread). Keep the
// label list and column in sync with HUD_LABELS / HUD_ANCHOR_COL in
// RegionRouter.java.
static bool _is_gameplay_frame()
{
	static const char *labels[] = {
		"Health:", "HP:", "Magic:", "MP:", "AC:", "EV:", "SH:", "XL:"
	};
	static const int n_labels = sizeof(labels) / sizeof(labels[0]);
	const int anchor_col = 37;
	for (int r = 2; r <= 8; r++)
	{
		for (int li = 0; li < n_labels; li++)
		{
			const char *lbl = labels[li];
			int llen = (int)strlen(lbl);
			if (anchor_col + llen > COLS)
				continue;
			bool match = true;
			for (int i = 0; i < llen; i++)
			{
				if (terminalWindow[r][anchor_col + i].character
						!= (jchar)lbl[i])
				{
					match = false;
					break;
				}
			}
			if (match)
				return true;
		}
	}
	return false;
}

void sendTerminalToScreen()
{
	if (dirtyTerminalChars.empty())
	{
		return;
	}
	JAVA_CALL(NativeWrapper_preStormHint,
		_is_gameplay_frame() ? JNI_TRUE : JNI_FALSE);
	std::set<TerminalChar *>::iterator it;
	for (it = dirtyTerminalChars.begin(); it != dirtyTerminalChars.end(); it++)
	{
		JAVA_CALL(NativeWrapper_printTerminalChar, (*it)->y, (*it)->x, (*it)->character, (*it)->foregroundColour, (*it)->backgroundColour);
	}
	JAVA_CALL(NativeWrapper_invalidateTerminal);
	dirtyTerminalChars.clear();
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
	for (int i = 0; i < LINES; ++i)
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
	if (y >= LINES)
	{
		y = LINES - 1;
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
    return (LINES);
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
    for (int i = 0; i < LINES; ++i)
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
