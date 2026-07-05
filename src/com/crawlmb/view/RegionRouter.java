package com.crawlmb.view;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.crawlmb.FontConfig;
import com.crawlmb.Preferences;
import com.crawlmb.R;
import com.crawlmb.keyboard.CrawlKeyboardView;

import java.util.ArrayList;
import java.util.List;

public class RegionRouter implements TerminalRenderer
{
	public enum LayoutMode { PREGAME, GAMEPLAY, MENU }

	// Menu sub-classification. Detected per-frame from terminalShadow
	// contents; controls which font_config.txt scale and scrollable setting
	// fullView/skillsView gets. DEFAULT covers any menu not matched by a
	// more specific group. PREGAME and MAINMENU are sub-types of
	// LayoutMode.PREGAME (loading screen vs. DCSS welcome/character creation).
	public enum MenuType
	{
		DEFAULT, PREGAME, MAINMENU, ITEMS, SPELLS, OVERVIEW, SKILLS,
		RELIGION, HISCORES, TRAVEL, LEVELMAP, DESCRIBE, HELP,
		// Message history (Ctrl+P). The formatted_scroller is sized to the
		// full 48-row terminal with FS_START_AT_END, so the newest messages
		// land at terminal rows past 28 — fullView's default 0..28 region
		// clips them off. This MenuType widens fullView to TERMINAL_ROWS so
		// the full scroller is drawn into the bitmap and the user can
		// scroll to the bottom (latest messages). Detected via a JNI flag
		// set by the patched _replay_messages_core in message.cc, not by
		// terminal content, because the popup has no title row to anchor
		// on and the per-row "_" turn-end prefix also appears in normal
		// gameplay messages.
		MESSAGES,
		// Character log popup (activate a hiscore entry). The
		// formatted_scroller has no title and its content — the morgue
		// file text — is arbitrary: "Turns:", "     Skill", "Granted
		// powers:" and other strings that would trip OVERVIEW / SKILLS /
		// RELIGION content anchors as the user scrolls. Detected via a
		// JNI flag set by the patched _show_morgue in hiscores.cc, not by
		// content scanning. fullView is widened to the full 48-row
		// terminal so the entire scroller viewport is painted.
		MORGUE,
		// Newgame sub-states. Each picks a different vertically-stacked
		// panel container (one panel per category) and per-panel scales
		// from font_config.txt. Detected via row-0 "Welcome" + the
		// "species." / "background." disambiguator from newgame.cc.
		NEWGAME_SPECIES, NEWGAME_BACKGROUND, NEWGAME_WEAPON, NEWGAME_NAME
	}

	public interface ScrollStateListener
	{
		// Called on UI thread when fullView's effective scrollable state
		// changes (menu type changed, fullView visibility changed, etc.).
		void onMenuScrollableChanged(boolean scrollable);
	}

	// 0-based region boundaries for the 80x24 inline layout
	public static final int HUD_START_ROW = 0;
	// Stats only: rows 0-8 (stats/wp/qv). Status-light terminal rows
	// (9-10) are suppressed — the native StatusBarView replaces them.
	public static final int HUD_END_ROW = 9;
	public static final int MLIST_START_ROW = 11;
	public static final int MLIST_END_ROW = 15;
	public static final int HUD_START_COL = 37;
	public static final int HUD_END_COL = 80;

	public static final int MAP_START_ROW = 0;
	public static final int MAP_END_ROW = 17;
	public static final int MAP_START_COL = 0;
	public static final int MAP_END_COL = 37;

	public static final int MSG_START_ROW = 17;
	public static final int MSG_END_ROW = 24;
	public static final int MSG_START_COL = 0;
	public static final int MSG_END_COL = 80;

	private static final int TERMINAL_ROWS = TerminalRenderer.FRAME_ROWS;
	public static final int TERMINAL_COLS = TerminalRenderer.FRAME_COLS;

	// Gameplay anchor: any HUD caption present at terminal col 37 in the HUD
	// rows. We OR over multiple labels because draw_border() (which prints
	// "AC:"/"EV:"/"SH:") is not re-emitted on every frame, while the per-stat
	// printers re-emit Health:/HP:, Magic:/MP:, and XL: whenever their dirty
	// flag is set. force_more_message prompts (e.g. "You hear the hiss of
	// flowing sand. --more--") wipe the AC/EV/SH labels but leave the others,
	// so a single-label anchor would misclassify those frames as MENU.
	private static final int HUD_ANCHOR_COL = 37;
	private static final int HUD_ANCHOR_ROW_MIN = 2;
	private static final int HUD_ANCHOR_ROW_MAX = 8;
	private static final String[] HUD_LABELS = {
		"Health:", "HP:", "Magic:", "MP:", "AC:", "EV:", "SH:", "XL:"
	};

	// Menu-type anchors. Row 0 prefixes for menus that use Menu::set_title.
	// Custom-rendered screens (overview, skills, religion, hiscores) need
	// looser scans on different rows.
	// Row 0 prefixes for ITEMS-category screens. The four "Gear:" / "Potions:"
	// / "Scrolls:" / "Evocable Items:" prefixes are the per-page titles of
	// the paged inventory ('i') menu — InvMenu::set_title in
	// crawl-ref/source/invent.cc emits one of these when MF_PAGED_INVENTORY
	// is set, which is the default in 0.34 (Options.show_paged_inventory).
	// Without these, paged inventory was misclassified as DEFAULT and got
	// portraitDefaultFontScale instead of portraitItemsFontScale. Non-paged
	// inventory still uses the literal "Inventory:" title.
	//
	// Pickup ('g') and the recognised-items menu ('\') were added after an
	// earlier "Items known" anchor was found to never match: known-items.cc
	// emits "Items not yet recognised:", "Recognised items.", or "You
	// recognise all items." depending on state — none of which start with
	// "Items known". Pickup's title is built in items.cc as either
	// "Pick up what? ... (_ for help)" or, for a single-stack pile, "Select
	// pick up quantity by entering a number, then select the item".
	// Inscribe ('{') uses "Inscribe which item?" via prompt_invent_item.
	//
	// Scroll-driven item-pick menus from item-use.cc: scroll of identify
	// ("Identify which item?"), enchant weapon ("Enchant which weapon?"),
	// enchant armour ("Enchant which item?"), brand weapon ("Brand which
	// weapon?"). The brand prefix also covers Okawaru's Finesse-equivalent
	// brand ability in god-abil.cc.
	// Adjust slots ('=') uses two prompts back to back: "Adjust which item?"
	// then "Adjust to which letter?" — both share the "Adjust " prefix.
	// "Zap which item?" is evoke.cc's wand prompt; "Uncurse and destroy
	// which item?" is Ashenzari's curse ability.
	// AcquireMenu emits "Choose an item to acquire." (regular acquirement,
	// Okawaru/Trog gifts) or "Choose a gizmo to assemble." (Coglin gizmo).
	private static final String[] ITEMS_ROW0_PREFIXES = {
		"Inventory:",
		"Gear:",
		"Potions:",
		"Scrolls:",
		"Evocable Items:",
		"Drop what?",
		"Pick up what?",
		"Select pick up quantity",
		"Wield ",
		"Unequip ",
		"Equip ",
		"Equip or unequip",
		"Wear ",
		"Wear or take off",
		"Take off ",
		"Put on ",
		"Put on or remove",
		"Remove ",
		"Drink which",
		"Read which",
		"Evoke which",
		"Zap which item",
		"Inscribe which",
		"Adjust ",
		"Identify which item",
		"Enchant which weapon",
		"Enchant which item",
		"Brand which weapon",
		"Uncurse and destroy which item",
		"Choose an item to acquire",
		"Choose a gizmo to assemble",
		"Welcome to ",
		"Items not yet recognised",
		"Recognised items",
		"You recognise all items",
		"Innate Abilities, Weirdness",
		"Visible ",
	};

	// Catches:
	//   "Your spells (cast)" / "Your spells (describe)" — spell list (I), cast (z)
	//     via list_spells() in spl-cast.cc
	//   "Spells (Memorise)" / "Spells (Cast)" / "Spells (Imbue)" /
	//   "Spells (Describe)" / "Spells (Hide)" / "Spells (Show)" — memorise (M),
	//     Vehumet's Divine Exegesis, Spellspark Servitor imbue
	//     via SpellLibraryMenu::calc_title in spl-book.cc
	//   "Ability - do what" / "Ability - describe what" — abilities (a)
	private static final String[] SPELLS_ROW0_PREFIXES = {
		"Your spells",
		"Spells (",
		"Ability - do what",
		"Ability - describe what",
	};

	// Help screen (?). The formatted_scroller popup has no title widget;
	// content starts at row 0. The two two-column pages are the help menu
	// ("Dungeon Crawl Help") and the key help ("Movement:"). Both use
	// column_composer(2, 42) — left column at positions 0-41, right at 42-79.
	// Single-column text-file pages (Manual, Aptitudes, etc.) are navigated
	// to from within the same popup but fall through to DEFAULT.
	private static final String[] HELP_ROW0_PREFIXES = {
		"Dungeon Crawl Help",
		"Movement:",
	};

	// Fixed column split for help screens. column_composer(2, 42) pads the
	// left column to width margin-1 = 41, so right-column text starts at
	// terminal position 41.
	private static final int HELP_COL_SPLIT = 41;

	// Quiver action menu (Shift+Q). ActionSelectMenu in quiver.cc sets an
	// empty MEL_TITLE, so row-0 prefix matching can't catch it. Its more
	// widget always contains "focus mode:" (built in get_keyhelp:
	// "[Tab/!] focus mode: off|on") and that literal occurs in no other
	// DCSS menu, so a full-terminal scan for it is unambiguous.
	private static final String QUIVER_MORE_MARKER = "focus mode:";

	// Level map title row: _draw_title in viewmap.cc renders the level name
	// (left), feature list (centre), and "(Press ? for help)" (right) when
	// columns >= help width. Accessed via Shift+X (CMD_DISPLAY_MAP).
	private static final String LEVELMAP_HELP_MARKER = "(Press ? for help)";

	// Stash search results title row from StashSearchMenu::calc_title in
	// stash.cc — "5 matches: travel [toggle: !], by dist [/], hide useless &
	// duplicates [=]" (or "view  " in examine mode). The "[toggle:" substring
	// is unique to this title row format. Accessed via Ctrl+F when matches
	// are found.
	private static final String TRAVEL_TOGGLE_MARKER = "[toggle:";

	// Skills menu (m) is rendered into a dedicated wider/taller view so its
	// two-column layout can be folded into one column. The remap moves the
	// second column's skill rows underneath the first column's, and shifts
	// the help/button block down by SKILL_FOLD_ROWS. The value matches
	// (SK_ARR_LN - 1), i.e. the number of m_skills rows per column below
	// the title row — 17 for ndisplayed_skills=38 in 0.34.x. See
	// crawl-ref/source/skills.h (skill_display_order) and
	// crawl-ref/source/skill-menu.h (SK_ARR_LN).
	public static final int SKILL_FOLD_ROWS = 17;
	// Header anchor: scan rows 0..3 for the literal "Skill" header text
	// emitted by SkillMenuEntry::set_title (with five leading spaces).
	private static final String SKILL_HEADER = "     Skill";
	// Loading screen anchor: the first item of loading_message_array starts
	// with this exact string. Lets us distinguish the static load screen
	// (PREGAME) from DCSS's "Hello, welcome..." main menu (MAINMENU).
	// Keep in sync with res/values/string-array.xml's first <item>.
	private static final String LOADING_ANCHOR = "PLEASE WAIT...";

	// Newgame screen anchors. newgame.cc emits "Welcome, %s. Please select
	// your species." / "...your background." into row 0 (formatted_string,
	// no leading indent, so col-0 match works). The "Welcome" prefix alone
	// is not enough — DCSS's main menu also opens with "Hello, welcome to
	// Dungeon Crawl" — so we additionally require the trailing
	// "species."/"background." token in the same row to disambiguate.
	// _welcome() in newgame.cc emits "Welcome." (no comma) when nothing has
	// been chosen yet (species/job/name all empty), and "Welcome, foo." once
	// any of those is set. Matching just "Welcome" covers both forms; the
	// species./background. token below disambiguates from the main menu's
	// "Hello, welcome to Dungeon Crawl" greeting (lowercase 'w', and not at
	// col 0).
	private static final String NEWGAME_WELCOME_PREFIX = "Welcome";
	private static final String NEWGAME_SPECIES_TOKEN = "species.";
	private static final String NEWGAME_BACKGROUND_TOKEN = "background.";
	private static final String NEWGAME_WEAPON_ANCHOR = "choice of weapons";

	// Character naming screen anchor. _choose_name in newgame.cc renders
	// "What is your name today? " via formatted_string into a popup; the
	// exact terminal row depends on the popup's vertical centering.
	private static final String NEWGAME_NAME_ANCHOR = "What is your name today?";

	// DCSS main menu greeting. menu.cc renders "Hello, welcome to Dungeon
	// Crawl Stone Soup <version>." at row 0 col 0 of the main menu shown at
	// app start and after Ctrl+S returns from gameplay. Distinct from the
	// newgame "Welcome..." (no "Hello," prefix), so this anchor is unique.
	private static final String MAINMENU_ANCHOR = "Hello, welcome to Dungeon Crawl";

	// Newgame panel rectangles. Hardcoded to upstream 0.34 layout —
	// m_main_items is OuterMenu(true, 3, 20) with set_margin_for_crt(1, 0),
	// so the welcome row sits at row 0, content starts at row 2 after the
	// 1-row top margin, and the 3-col grid splits 80 cols into ~27/27/26.
	// Group titles are at the y-offset given by their static coord_def.
	// If upstream nudges these, the symptom is a panel showing wrong/empty
	// content; verify alongside the existing "regression-test menu types
	// after submodule bump" check in RenderingImplementation.md.
	public static final int NEWGAME_WELCOME_ROW0 = 0;
	public static final int NEWGAME_WELCOME_ROW1 = 2;   // rows 0..1
	public static final int NEWGAME_SUB_ROW0     = 13;
	public static final int NEWGAME_SUB_ROW1     = 19;  // rows 13..18
	public static final int NEWGAME_COL_LEFT     = 0;
	public static final int NEWGAME_COL_LEFT_END = 27;  // cols 0..26
	public static final int NEWGAME_COL_MID      = 27;
	public static final int NEWGAME_COL_MID_END  = 54;  // cols 27..53
	public static final int NEWGAME_COL_RIGHT    = 54;
	public static final int NEWGAME_COL_RIGHT_END= 80;  // cols 54..79
	public static final int NEWGAME_COL_FULL_END = 80;  // wide panels

	// Species: 3 categories at y=0, 1 title + 9 items per group → rows 2..10.
	public static final int NEWGAME_SPECIES_ROW0 = 2;
	public static final int NEWGAME_SPECIES_ROW1 = 12;  // rows 2..11

	// Background welcome panel includes the CRT margin row as a visible spacer.
	public static final int NEWGAME_BG_WELCOME_ROW1 = 3;
	// Background categories start one row later than species (extra prompt row).
	public static final int NEWGAME_BG_WARRIOR_ROW0    = 3,  NEWGAME_BG_WARRIOR_ROW1    = 9;
	public static final int NEWGAME_BG_ZEALOT_ROW0     = 9,  NEWGAME_BG_ZEALOT_ROW1     = 13;
	public static final int NEWGAME_BG_ADVENTURER_ROW0 = 3,  NEWGAME_BG_ADVENTURER_ROW1 = 8;
	public static final int NEWGAME_BG_WARMAGE_ROW0    = 8,  NEWGAME_BG_WARMAGE_ROW1    = 13;
	public static final int NEWGAME_BG_MAGE_ROW0       = 3,  NEWGAME_BG_MAGE_ROW1       = 14;

	private final List<RegionTermView> splitRegions = new ArrayList<>();
	private RegionTermView fullView;
	private RegionTermView skillsView;
	private RegionTermView itemsView;
	private CrawlKeyboardView keyboardView;
	// App-only static panel shown only while the DCSS main menu is active.
	// Sits in a LinearLayout sibling-pair with fullView so its slot expands
	// to fill the gap between fullView's bottom and the virtual keyboard.
	// Visibility is toggled here from applyMode; content is preloaded by
	// GameActivity from assets/quick_controls.txt.
	private View quickControlsView;
	private ViewGroup splitContainer;
	private StatusBarView statusBarView;
	// Newgame panel containers. Each holds a vertical stack of
	// RegionTermViews — those panels are also added to splitRegions so
	// drawPoint forwards into their fixed terminal rectangles. The
	// container visibility gates whether the user sees them.
	private View newgameSpeciesContainer;
	private View newgameBackgroundContainer;
	// Per-category panel refs so we can re-aim each one at the actual
	// terminal column slice once the upstream Grid widget has settled. The
	// welcome and sub panels are full-width and don't need adjustment.
	private RegionTermView ngsSimpleView;
	private RegionTermView ngsIntermediateView;
	private RegionTermView ngsAdvancedView;
	private RegionTermView ngbWarriorView;
	private RegionTermView ngbZealotView;
	private RegionTermView ngbAdventurerView;
	private RegionTermView ngbWarMageView;
	private RegionTermView ngbMageView;
	// Sub-options block: split upstream's "description Switcher above 2-col
	// sub-items grid" into three panels per screen so the whole block reads
	// as a single vertical column:
	//   desc     — full-width, samples the description rows above sub-items
	//   subLeft  — only the 4 sub-item rows × col-0 ("+/#/%/?")
	//   subRight — only the 4 sub-item rows × col-1, with per-row shifts
	//              applied to strip upstream's varying leading whitespace
	//              ("    *", "    !", "Space", "  Tab") so each row aligns
	//              flush at panel col 0 under the col-0 column.
	// Row split between desc and sub items is found at activation by scanning
	// for the "+ - Recommended" row.
	private RegionTermView ngsDescView;
	private RegionTermView ngsSubLeftView;
	private RegionTermView ngsSubRightView;
	private RegionTermView ngbDescView;
	private RegionTermView ngbSubLeftView;
	private RegionTermView ngbSubRightView;
	private View newgameWeaponContainer;
	private RegionTermView ngwContentView;
	private RegionTermView ngwSubLeftView;
	private RegionTermView ngwSubRightView;
	// Word-wrap mode (crawl.wordwrap pref): msgView reference and its
	// terminal row count, set by GameActivity when the pref is on. Queried
	// by NativeWrapper.gameStart to compute the msg_max_width option before
	// DCSS boots. null msgWordwrapView = word wrap off.
	private RegionTermView msgWordwrapView;
	private int msgWordwrapRows = 7;
	private final Context context;

	private final char[][] terminalShadow = new char[TERMINAL_ROWS][TERMINAL_COLS];
	private final int[][] terminalFg = new int[TERMINAL_ROWS][TERMINAL_COLS];
	private final int[][] terminalBg = new int[TERMINAL_ROWS][TERMINAL_COLS];
	// Per-frame dirty map built by onFrame's diff against terminalShadow.
	// Reused across frames; only valid within a single onFrame call (frames
	// arrive serialized under display_lock).
	private final boolean[][] frameChanged = new boolean[TERMINAL_ROWS][TERMINAL_COLS];
	private volatile LayoutMode currentMode = LayoutMode.PREGAME;
	private volatile MenuType currentMenuType = MenuType.DEFAULT;

	// Set true by libandroid.cc → NativeWrapper.setMessageHistoryMode() while
	// the Ctrl+P / startup message history popup is open. Read on the game
	// thread by detectMenuType() to short-circuit content scanning and
	// return MenuType.MESSAGES regardless of what the terminal grid contains
	// at that moment. Volatile so the C++-side write is visible to the
	// detection read without explicit synchronisation; the flag is single-
	// writer (game thread inside _replay_messages_core) so torn reads aren't
	// a concern.
	//
	// Static so the popup-active signal survives across RegionRouter
	// recreations. rebuildViews() (Activity.onStart) constructs a new
	// RegionRouter on every sleep/wake cycle; a fresh instance would start
	// with this flag false even though the C++ game thread is still blocked
	// inside _replay_messages_core. detectMenuType would then return DEFAULT
	// instead of MESSAGES, applyMenuType would set endRow back to 28, and
	// the bottom 20 rows of the popup (the latest messages) would be clipped
	// — exactly the pre-fix symptom, just triggered by background/foreground
	// instead of by opening Ctrl+P. Same rationale as gameplayEverDetected
	// above: the flag represents C++ runtime state (are we inside
	// _replay_messages_core), which is invariant across Java-side recreations
	// for as long as the process lives. Process death resets it to false on
	// next class load, which matches reality (the popup is gone too).
	private static volatile boolean messageHistoryMode = false;

	@Override
	public void setMessageHistoryMode(boolean active)
	{
		messageHistoryMode = active;
	}

	// Set true by libandroid.cc → NativeWrapper.setCharacterLogMode() while
	// the morgue-viewer popup opened from the High Scores menu is on-screen.
	// Same static/volatile rationale as messageHistoryMode above: represents
	// C++ runtime state (are we inside _show_morgue → morgue_file.show()),
	// invariant across RegionRouter recreations from sleep/wake cycles.
	private static volatile boolean characterLogMode = false;

	@Override
	public void setCharacterLogMode(boolean active)
	{
		characterLogMode = active;
	}

	public void setMsgWordwrap(RegionTermView msgView, int msgRows)
	{
		this.msgWordwrapView = msgView;
		this.msgWordwrapRows = msgRows;
	}

	// msg_max_width for DCSS: visible columns at the msg font size, minus
	// the '_' turn-marker column (message text starts at terminal col 1).
	// 0 disables word wrap — either the pref is off (no view registered) or
	// the panel hasn't been measured, in which case wrapping silently stays
	// off for the session rather than wrapping at a wrong width.
	@Override
	public int getMsgWrapCols()
	{
		if (msgWordwrapView == null)
			return 0;
		int visible = msgWordwrapView.computeVisibleCols();
		if (visible <= 1)
			return 0;
		return Math.min(visible - 1, TERMINAL_COLS - 1);
	}

	@Override
	public int getMsgRows()
	{
		return msgWordwrapView != null ? msgWordwrapRows : 7;
	}

	// Wrap cap for prose popup Texts (describe/god/hints — the wrap_text
	// widgets capped by ui.cc.patch): visible columns at the largest prose-
	// popup font scale, so wrapped lines fit every prose screen. Mirrors
	// RegionTermView.autoSizeFontByWidth/setFontSize so the computed char
	// width matches what the panel will render with. 0 when word wrap is
	// off or fullView isn't measured yet (wrap then stays off for the
	// session rather than wrapping at a wrong width).
	@Override
	public int getProseWrapCols()
	{
		if (msgWordwrapView == null || fullView == null || fontConfig == null)
			return 0;
		int width = fullView.getMeasuredWidth();
		if (width <= 0)
			return 0;
		Typeface face = fullView.getTypeface(Preferences.getFontFace());
		if (face == null)
			return 0;
		float scale = Math.max(fontConfig.portraitDescribeFontScale,
				fontConfig.portraitReligionFontScale);
		int refSize = GameFontShaper.widthFitTextSize(context,
				TERMINAL_COLS, width,
				RegionTermView.MIN_FONT_SIZE, RegionTermView.MAX_FONT_SIZE);
		float matched = GameFontShaper.matchReferenceLineHeight(
				context, face, refSize);
		int scaledSize = Math.round(Math.round(matched) * scale);
		scaledSize = Math.max(RegionTermView.MIN_FONT_SIZE,
				Math.min(scaledSize, RegionTermView.MAX_FONT_SIZE));
		Paint probe = new Paint();
		probe.setTypeface(face);
		probe.setTextSize(scaledSize);
		int charWidth = (int) probe.measureText("X");
		if (charWidth <= 0)
			return 0;
		// -1 safety margin against char-width rounding across font faces.
		int cols = width / charWidth - 1;
		return Math.max(0, Math.min(cols, TERMINAL_COLS - 1));
	}

	public LayoutMode getCurrentMode()
	{
		return currentMode;
	}

	public MenuType getCurrentMenuType()
	{
		return currentMenuType;
	}
	// Static so the "have we ever seen gameplay" signal survives across
	// RegionRouter recreations. rebuildViews() constructs a new RegionRouter
	// on every Activity onStart (including sleep/wake), and a fresh instance
	// starts with this flag false. detectMode() falls back to PREGAME when
	// no HUD is on screen AND this flag is false — which misclassifies any
	// menu open at sleep time (LEVELMAP, inventory, spells, etc.) as PREGAME
	// on resume, costing the menu its configured font scale until the user
	// returns to gameplay and the flag flips again. Static = process-lifetime,
	// which matches reality: if the process is still alive, gameplay state
	// the C++ side reaches across resumes is unchanged. Process death (full
	// kill) resets the class state to false on next class load, which is
	// correct for a true cold start.
	private static boolean gameplayEverDetected = false;

	// Set by onFrame when the frame transitions GAMEPLAY -> a non-gameplay
	// state. Read by routeCell to skip forwarding to splitRegions for that
	// frame — without this, menu chars at terminal coordinates would land
	// in mapView/hudView/msgView while splitContainer is still VISIBLE
	// (applyMode's hide runs later on the UI thread), painting them "on top
	// of the game map". Volatile because replayAllFromShadow can route from
	// the UI thread while the game thread owns the flag.
	private volatile boolean skipSplitRegionsThisStorm = false;

	// Anchor for the single-column fold of the skills menu. Recomputed on
	// each transition INTO MenuType.SKILLS by scanning terminalShadow for
	// the two occurrences of "     Skill" on the same row. skillsLeftCol
	// is the start of col 0's title; skillsRightCol is the start of col 1's
	// title. The cut threshold (which side of the fold a char belongs to)
	// is skillsRightCol; the shift applied to col 1 chars to align them
	// under col 0 is (skillsRightCol - skillsLeftCol), which equals
	// MIN_COLS/2 = 39 in practice. -1 = anchor unknown → drawPoint
	// forwards to skillsView 1:1 as a fallback.
	private volatile int skillsHeaderRow = -1;
	private volatile int skillsLeftCol = -1;
	private volatile int skillsRightCol = -1;
	// Compact row mappings: destination row for each source skill row in
	// the left/right columns. -1 = blank row, skip. Built by
	// recomputeSkillsAnchor() to eliminate blank-line dividers within
	// each column, keeping only one divider between the two column
	// sections and one before the help/button block.
	private volatile int[] skillsLeftDest;
	private volatile int[] skillsRightDest;
	private volatile int skillsBottomStart = -1;
	// True when the SkillMenu is in SKMF_SIMPLE (tutorial/hints) mode.
	// Detected by absence of the "Apt " column header -- SKMF_APTITUDE is
	// only set when !game_is_hints_tutorial(), so an "Apt"-less header
	// means SIMPLE. In SIMPLE mode the C++ side does m_pos.y -= 1 before
	// placing the help block (a row-stealing trick to fit longer tutorial
	// help + buttons in 24 terminal rows), which makes the first help line
	// land on what would otherwise be the last skill row of the grid.
	// We respond by:
	//   1) shrinking the fold range by 1 so source row (headerRow + 17)
	//      is treated as bottom content, not as a fold-grid row -- this
	//      stops the help text getting interleaved with col-1 skills;
	//   2) shifting button rows down by 1 destRow to insert a blank gap
	//      between the help text and the [?]/[=] buttons, matching how
	//      the main game's help bounds naturally leave a trailing blank.
	// Threshold (skillsSimpleButtonRow) is the source terminal row where
	// the first switch row begins -- terminal row 22 with help_height=5
	// and m_pos.y -= 1 applied.
	private volatile boolean skillsSimpleMode = false;
	private static final int SKILL_SIMPLE_BUTTON_ROW = 22;

	// Anchor for the single-column fold of item menus. Recomputed each
	// frame while in MenuType.ITEMS. itemsColSplit is the column where
	// the right column starts; itemsFirstContentRow / itemsFoldRows
	// define the content region. itemsLeftDest[r] / itemsRightDest[r]
	// give the destination row in itemsView for left- and right-column
	// content on terminal row r. Right items are inserted after each
	// category section's left items so the fold order matches DCSS's
	// navigation order. -1 = skip. itemsRightRowCount is the total
	// number of right-column rows (used as footer offset).
	private volatile int itemsColSplit = -1;
	private volatile int itemsFirstContentRow = -1;
	private volatile int itemsFoldRows = -1;
	private volatile int[] itemsLeftDest;
	private volatile int[] itemsRightDest;
	private volatile int itemsRightRowCount;
	// Footer compression: footerStartRow is the first non-blank row after
	// a blank gap past the item content. footerDestRow is the destination
	// row in itemsView where the footer should start (right after content).
	private volatile int itemsFooterStartRow = -1;
	private volatile int itemsFooterDestRow = -1;

	// Generic footer compression for fullView menus (SPELLS, etc.).
	// When the DCSS "more" widget renders keyhelp past row 23 (fullView's
	// endRow), these remap footer rows to just after the last content row
	// so they're visible without scrolling.
	private volatile int fullViewFooterStart = -1;
	private volatile int fullViewFooterDest = -1;
	private volatile int fullViewLastOverflow = -1;

	private FontConfig fontConfig;
	private ScrollStateListener scrollStateListener;
	private Runnable redrawRequester;
	// One-shot, posted to the UI thread the first time DCSS paints a real
	// post-boot screen (main menu or gameplay). GameActivity uses it to
	// dismiss the "Reloading..." overlay shown across a save-restore process
	// restart, exactly when the game becomes visible. Null when no overlay
	// is pending, so normal launches pay nothing.
	private Runnable reloadCompleteListener;
	// Set by applyMode when it reaches the reload destination AND has just
	// scheduled a repaint into a blank surface (scale change recreated
	// fullView's bitmap, or gameplay cleared splitRegions). The next
	// onFrame is that repaint — it refills the bitmap before signalling,
	// so dismissing the overlay there avoids exposing the blank surface
	// (the flash that firing on the bare detection transition caused).
	// Written on the UI thread (applyMode), read on the game thread
	// (onFrame); volatile for cross-thread visibility.
	private volatile boolean reloadAwaitingRepaint = false;
	// True only when SplashActivity ran InstallProgramTask on this launch
	// (first install or dat-hash mismatch). On cached launches DCSS init is
	// fast enough that the static "PLEASE WAIT..." overlay is unnecessary
	// noise; skipping it shaves a frame and avoids briefly showing stale text
	// before the main menu paints.
	private boolean showLoadingMessage = false;

	public RegionRouter(Context context)
	{
		this.context = context;
	}

	public void addRegion(RegionTermView region)
	{
		splitRegions.add(region);
	}

	public void setFullView(RegionTermView view)
	{
		this.fullView = view;
	}

	public void setSkillsView(RegionTermView view)
	{
		this.skillsView = view;
	}

	public void setItemsView(RegionTermView view)
	{
		this.itemsView = view;
	}

	public void setKeyboardView(CrawlKeyboardView view)
	{
		this.keyboardView = view;
	}

	public void setQuickControlsView(View view)
	{
		this.quickControlsView = view;
	}

	public void setStatusBarView(StatusBarView view)
	{
		this.statusBarView = view;
	}

	public void setSplitContainer(ViewGroup container)
	{
		this.splitContainer = container;
	}

	public void setNewgameSpeciesContainer(View container)
	{
		this.newgameSpeciesContainer = container;
	}

	public void setNewgameBackgroundContainer(View container)
	{
		this.newgameBackgroundContainer = container;
	}

	public void setNewgameSpeciesPanels(RegionTermView simple,
			RegionTermView intermediate, RegionTermView advanced)
	{
		this.ngsSimpleView = simple;
		this.ngsIntermediateView = intermediate;
		this.ngsAdvancedView = advanced;
	}

	public void setNewgameBackgroundPanels(RegionTermView warrior,
			RegionTermView zealot, RegionTermView adventurer,
			RegionTermView warMage, RegionTermView mage)
	{
		this.ngbWarriorView = warrior;
		this.ngbZealotView = zealot;
		this.ngbAdventurerView = adventurer;
		this.ngbWarMageView = warMage;
		this.ngbMageView = mage;
	}

	public void setNewgameSubPanels(RegionTermView speciesDesc,
			RegionTermView speciesLeft, RegionTermView speciesRight,
			RegionTermView backgroundDesc, RegionTermView backgroundLeft,
			RegionTermView backgroundRight)
	{
		this.ngsDescView = speciesDesc;
		this.ngsSubLeftView = speciesLeft;
		this.ngsSubRightView = speciesRight;
		this.ngbDescView = backgroundDesc;
		this.ngbSubLeftView = backgroundLeft;
		this.ngbSubRightView = backgroundRight;
	}

	public void setNewgameWeaponContainer(View container)
	{
		this.newgameWeaponContainer = container;
	}

	public void setNewgameWeaponPanels(RegionTermView content,
			RegionTermView subLeft, RegionTermView subRight)
	{
		this.ngwContentView = content;
		this.ngwSubLeftView = subLeft;
		this.ngwSubRightView = subRight;
	}

	// True iff every cell on the row is whitespace or unset.
	private boolean rowIsBlank(int row)
	{
		if (row < 0 || row >= TERMINAL_ROWS)
			return true;
		for (int c = 0; c < TERMINAL_COLS; c++)
		{
			char ch = terminalShadow[row][c];
			if (ch != 0 && ch != ' ')
				return false;
		}
		return true;
	}

	// Find the column where `s` first occurs on `row` of terminalShadow.
	// Returns -1 if not present. Used to anchor newgame panels to the
	// actual rendered grid columns (their absolute terminal positions
	// depend on the upstream Grid's stretch_h distribution and can't be
	// pinned statically).
	private int findColInRow(int row, String s)
	{
		if (row < 0 || row >= TERMINAL_ROWS)
			return -1;
		int len = s.length();
		if (len == 0 || len > TERMINAL_COLS)
			return -1;
		for (int c = 0; c <= TERMINAL_COLS - len; c++)
		{
			boolean match = true;
			for (int i = 0; i < len; i++)
			{
				if (terminalShadow[row][c + i] != s.charAt(i))
				{
					match = false;
					break;
				}
			}
			if (match)
				return c;
		}
		return -1;
	}

	// Scan terminalShadow row 2 for the species group titles
	// (Simple/Intermediate/Advanced) and re-aim each per-category panel at
	// the slice spanning [thisTitleCol, nextTitleCol). No-op if any title
	// is missing — the next frame will retry.
	private void applyNewgameSpeciesBounds()
	{
		if (ngsSimpleView == null || ngsIntermediateView == null
				|| ngsAdvancedView == null)
			return;
		int cSimple = findColInRow(2, "Simple");
		int cInter  = findColInRow(2, "Intermediate");
		int cAdv    = findColInRow(2, "Advanced");
		if (cSimple < 0 || cInter < 0 || cAdv < 0)
			return;
		ngsSimpleView.setRegionCols(cSimple, cInter);
		ngsIntermediateView.setRegionCols(cInter, cAdv);
		ngsAdvancedView.setRegionCols(cAdv, TERMINAL_COLS);
	}

	// Stack upstream's "description Switcher above 2-col sub-items grid"
	// into three vertically-aligned panels:
	//
	//   desc     full-width, samples rows [NEWGAME_SUB_ROW0, subItemRow)
	//   subLeft  rows [subItemRow, subItemRow+SUB_GRID_ROWS), col-0 cells
	//   subRight same row range, col-1 cells with per-row left shifts that
	//            strip varying upstream indent ("    *", "    !", "Space",
	//            "  Tab") so every row renders flush at panel col 0.
	//
	// Anchors:
	//   "+ - Recommended"  → row R+0 col-0 cell (defines subItemRow)
	//   "Space "           → row R+2 col-1 cell (no leading whitespace —
	//                        the cleanest col-1 anchor since rows 0/1/3
	//                        have variable space padding)
	// If subItemRow can't be found, falls back to a single-column desc panel
	// covering the whole sub block and hides the two grid panels via 0-width
	// regions; the next frame will retry detection.
	private static final int SUB_GRID_ROWS = 4;
	private static final int WEAPON_SUB_GRID_ROWS = 3;

	private void applyNewgameSubBounds(boolean isSpecies)
	{
		RegionTermView desc  = isSpecies ? ngsDescView     : ngbDescView;
		RegionTermView left  = isSpecies ? ngsSubLeftView  : ngbSubLeftView;
		RegionTermView right = isSpecies ? ngsSubRightView : ngbSubRightView;
		if (desc == null || left == null || right == null)
			return;

		// Find sub_items grid top row by scanning a wide range. The actual
		// row depends on description Switcher's content height (0 if no
		// description selected, otherwise 2-3 lines), and on whether the
		// m_main_items grid has 10 rows (species: 9 items + title) or 11
		// rows (background: 10 Mage items + title).
		int subItemRow = -1;
		int cLeft = -1;
		for (int r = NEWGAME_SUB_ROW0; r < TERMINAL_ROWS; r++)
		{
			int c = findColInRow(r, "+ - Recommended");
			if (c >= 0)
			{
				subItemRow = r;
				cLeft = c;
				break;
			}
		}

		// Find col-1 cell anchor (Space row has no leading whitespace).
		int cMid = -1;
		if (subItemRow >= 0)
		{
			for (int r = subItemRow; r < subItemRow + SUB_GRID_ROWS
					&& r < TERMINAL_ROWS; r++)
			{
				int c = findColInRow(r, "Space ");
				if (c >= 0)
				{
					cMid = c;
					break;
				}
			}
		}

		if (subItemRow < 0 || cLeft < 0 || cMid <= cLeft)
		{
			// Detection incomplete — render whole sub block as full-width
			// description, retry next frame. Hide the grid panels by
			// collapsing them to zero width (won't draw any chars).
			desc.setRegionRows(NEWGAME_SUB_ROW0, NEWGAME_SUB_ROW1);
			desc.setRegionCols(0, TERMINAL_COLS);
			desc.setRowColShift(null);
			left.setRegionRows(NEWGAME_SUB_ROW1, NEWGAME_SUB_ROW1);
			left.setRegionCols(0, 0);
			left.setRowColShift(null);
			right.setRegionRows(NEWGAME_SUB_ROW1, NEWGAME_SUB_ROW1);
			right.setRegionCols(0, 0);
			right.setRowColShift(null);
			return;
		}

		int gridEnd = Math.min(subItemRow + SUB_GRID_ROWS, TERMINAL_ROWS);

		// Description panel: rows above the sub-items grid, full width.
		// Strip leading blank rows down to exactly one, so the desc panel
		// always opens with a single blank-row spacer regardless of how
		// many empty rows upstream stacked above the description text.
		// Species: m_main_items is 10 rows (9 items + title), its bottom
		// margin is the only leading blank → 0 stripped, 1 kept. Background:
		// m_main_items is 11 rows (10 Mage items + title), so both its
		// bottom margin and descriptions' top margin are blank → 1 stripped,
		// 1 kept. Either way the user sees one empty row between the last
		// category item and the description.
		int descStart = isSpecies ? NEWGAME_SUB_ROW0 : NEWGAME_SUB_ROW0 + 1;
		while (descStart + 1 < subItemRow
				&& rowIsBlank(descStart) && rowIsBlank(descStart + 1))
			descStart++;
		desc.setRegionRows(descStart, subItemRow);
		desc.setRegionCols(0, TERMINAL_COLS);
		desc.setRowColShift(null);

		// Sub-items col-0: 4 rows × [cLeft, cMid).
		left.setRegionRows(subItemRow, gridEnd);
		left.setRegionCols(cLeft, cMid);
		left.setRowColShift(null);

		// Sub-items col-1: same rows × [cMid, 80). Compute per-row leading-
		// whitespace shifts so every row's first non-space char aligns at
		// panel col 0 (matching col-0's "+/#/%/?" alignment in subLeft).
		right.setRegionRows(subItemRow, gridEnd);
		right.setRegionCols(cMid, TERMINAL_COLS);
		int rows = gridEnd - subItemRow;
		int[] shifts = new int[rows];
		for (int i = 0; i < rows; i++)
		{
			int row = subItemRow + i;
			int shift = 0;
			while (cMid + shift < TERMINAL_COLS
					&& terminalShadow[row][cMid + shift] == ' ')
				shift++;
			shifts[i] = shift;
		}
		right.setRowColShift(shifts);
	}

	private void applyNewgameWeaponSubBounds()
	{
		if (ngwContentView == null || ngwSubLeftView == null
				|| ngwSubRightView == null)
			return;

		int subItemRow = -1;
		int cLeft = -1;
		for (int r = 0; r < TERMINAL_ROWS; r++)
		{
			int c = findColInRow(r, "+ - Recommended");
			if (c >= 0)
			{
				subItemRow = r;
				cLeft = c;
				break;
			}
		}

		int cMid = -1;
		if (subItemRow >= 0)
		{
			for (int r = subItemRow; r < subItemRow + WEAPON_SUB_GRID_ROWS
					&& r < TERMINAL_ROWS; r++)
			{
				int c = findColInRow(r, "* - Random");
				if (c >= 0)
				{
					cMid = c;
					break;
				}
			}
		}

		if (subItemRow < 0 || cLeft < 0 || cMid <= cLeft)
		{
			ngwContentView.setRegionRows(0, TERMINAL_ROWS);
			ngwContentView.setRegionCols(0, TERMINAL_COLS);
			ngwSubLeftView.setRegionRows(TERMINAL_ROWS, TERMINAL_ROWS);
			ngwSubLeftView.setRegionCols(0, 0);
			ngwSubRightView.setRegionRows(TERMINAL_ROWS, TERMINAL_ROWS);
			ngwSubRightView.setRegionCols(0, 0);
			return;
		}

		int gridEnd = Math.min(subItemRow + WEAPON_SUB_GRID_ROWS, TERMINAL_ROWS);

		ngwContentView.setRegionRows(0, subItemRow);
		ngwContentView.setRegionCols(0, TERMINAL_COLS);

		ngwSubLeftView.setRegionRows(subItemRow, gridEnd);
		ngwSubLeftView.setRegionCols(cLeft, cMid);
		ngwSubLeftView.setRowColShift(null);

		ngwSubRightView.setRegionRows(subItemRow, gridEnd);
		ngwSubRightView.setRegionCols(cMid, TERMINAL_COLS);
		int rows = gridEnd - subItemRow;
		int[] shifts = new int[rows];
		for (int i = 0; i < rows; i++)
		{
			int row = subItemRow + i;
			int shift = 0;
			while (cMid + shift < TERMINAL_COLS
					&& terminalShadow[row][cMid + shift] == ' ')
				shift++;
			shifts[i] = shift;
		}
		ngwSubRightView.setRowColShift(shifts);
	}

	// Background row 2 has only the col-0 group titles — Warrior, Adventurer,
	// Mage. Zealot (col 0) and Warrior-mage (col 1) sit further down their
	// columns and share the col-0/col-1 x ranges respectively.
	private void applyNewgameBackgroundBounds()
	{
		if (ngbWarriorView == null || ngbZealotView == null
				|| ngbAdventurerView == null || ngbWarMageView == null
				|| ngbMageView == null)
			return;
		int cWarrior = findColInRow(NEWGAME_BG_WARRIOR_ROW0, "Warrior");
		int cAdv     = findColInRow(NEWGAME_BG_WARRIOR_ROW0, "Adventurer");
		int cMage    = findColInRow(NEWGAME_BG_WARRIOR_ROW0, "Mage");
		if (cWarrior < 0 || cAdv < 0 || cMage < 0)
			return;
		ngbWarriorView.setRegionCols(cWarrior, cAdv);
		ngbZealotView.setRegionCols(cWarrior, cAdv);
		ngbAdventurerView.setRegionCols(cAdv, cMage);
		ngbWarMageView.setRegionCols(cAdv, cMage);
		ngbMageView.setRegionCols(cMage, TERMINAL_COLS);
	}

	public void setFontConfig(FontConfig config)
	{
		this.fontConfig = config;
	}

	public void setScrollStateListener(ScrollStateListener l)
	{
		this.scrollStateListener = l;
	}

	// Hook to ask DCSS to repaint the current screen. Called after a font
	// scale change recreates fullView's bitmap (now blank): without this,
	// the new bitmap stays empty until the next user input triggers a
	// natural redraw.
	public void setRedrawRequester(Runnable r)
	{
		this.redrawRequester = r;
	}

	public void setShowLoadingMessage(boolean show)
	{
		this.showLoadingMessage = show;
	}

	// Called from buildPortraitLayout after this RegionRouter and all of its
	// views have been wired up, before onResume's redrawScreen runs. If a
	// Ctrl+P / startup message history popup is open on the C++ side
	// (static messageHistoryMode), pre-applies the MESSAGES layout to
	// fullView so its region is already 48 rows when the first refreshTerminal
	// arrives. Without this, the new fullView is constructed at the default
	// 28 rows; the refresh frame's cells for rows 28..47 are dropped by the
	// view's range check, only rows 0..27 land in the mirror, and the
	// subsequent detection-driven applyMode that widens the region then
	// reallocates the mirror as an empty 48-row array — erasing the rows
	// 0..27 we just drew and leaving the panel blank until the user taps to
	// wake DCSS into a fresh storm. Setting currentMenuType to MESSAGES here
	// matches the layout so the first frame after refreshTerminal
	// sees no transition and doesn't re-run resetAllScroll — the
	// pendingScrollToBottom armed by applyFullConfig persists through the
	// empty-mirror onMeasure pass and is consumed by the post-refresh onDraw
	// once maxContentRow reflects the populated mirror.
	public void prepareForResume()
	{
		if (fullView == null)
			return;
		// Same rationale as messageHistoryMode: pre-apply the widened
		// region so the first refreshTerminal fills the full 48-row
		// bitmap. Checked first so that if both flags are somehow set,
		// MESSAGES wins (Ctrl+P can't be opened from the morgue viewer
		// but the check is defensive).
		if (messageHistoryMode)
		{
			applyFullConfig(MenuType.MESSAGES);
			currentMode = LayoutMode.MENU;
			currentMenuType = MenuType.MESSAGES;
			return;
		}
		if (characterLogMode)
		{
			applyFullConfig(MenuType.MORGUE);
			currentMode = LayoutMode.MENU;
			currentMenuType = MenuType.MORGUE;
		}
	}

	public void setReloadCompleteListener(Runnable r)
	{
		this.reloadCompleteListener = r;
	}

	// Hand the one-shot reload-complete signal to the UI thread and clear it
	// so it can never fire twice. No-op once consumed.
	private void fireReloadComplete()
	{
		final Runnable done = reloadCompleteListener;
		if (done == null)
			return;
		reloadCompleteListener = null;
		if (fullView != null)
			fullView.post(done);
	}

	// Zero drag-scroll offsets on every panel that can scroll. applyMode is
	// the single choke point for screen transitions, so doing this here means
	// no panel re-opens at its prior offset (msg log staying scrolled after
	// visiting inventory, inventory keeping its prior position on re-entry,
	// etc).
	private void resetAllScroll()
	{
		for (RegionTermView region : splitRegions)
			region.resetScroll();
		if (fullView != null)
			fullView.resetScroll();
		if (skillsView != null)
			skillsView.resetScroll();
		if (itemsView != null)
			itemsView.resetScroll();
		if (quickControlsView instanceof RegionTermView)
			((RegionTermView) quickControlsView).resetScroll();
		RegionTermView[] ng = {
				ngsSimpleView, ngsIntermediateView, ngsAdvancedView,
				ngbWarriorView, ngbZealotView, ngbAdventurerView,
				ngbWarMageView, ngbMageView,
				ngsDescView, ngsSubLeftView, ngsSubRightView,
				ngbDescView, ngbSubLeftView, ngbSubRightView,
				ngwContentView, ngwSubLeftView, ngwSubRightView };
		for (RegionTermView v : ng)
			if (v != null)
				v.resetScroll();
	}

	private void applyMode(LayoutMode mode, MenuType menuType)
	{
		resetAllScroll();
		boolean splitVisible = (mode == LayoutMode.GAMEPLAY);
		boolean skillsVisible = !splitVisible
				&& menuType == MenuType.SKILLS
				&& skillsView != null;
		boolean itemsVisible = !splitVisible
				&& (menuType == MenuType.ITEMS || menuType == MenuType.HELP)
				&& itemsView != null;
		boolean newgameSpeciesVisible = !splitVisible
				&& menuType == MenuType.NEWGAME_SPECIES
				&& newgameSpeciesContainer != null;
		boolean newgameBackgroundVisible = !splitVisible
				&& menuType == MenuType.NEWGAME_BACKGROUND
				&& newgameBackgroundContainer != null;
		boolean newgameWeaponVisible = !splitVisible
				&& menuType == MenuType.NEWGAME_WEAPON
				&& newgameWeaponContainer != null;
		boolean fullVisible = !splitVisible && !skillsVisible
				&& !itemsVisible
				&& !newgameSpeciesVisible && !newgameBackgroundVisible
				&& !newgameWeaponVisible;

		// Apply scale/scroll config BEFORE visibility changes. When
		// fullView transitions INVISIBLE→VISIBLE, its bitmap may hold
		// content drawn at a stale font scale (gameplay-era storms wrote
		// into it even while invisible). Applying the scale first lets
		// setFontScaleMultiplier → requestLayout → onMeasure recreate
		// the bitmap at the correct scale in the same layout pass that
		// makes the view visible, avoiding a one-frame flash of
		// misaligned ghost chars from the old scale. If the scale
		// changes, clearBitmap hides the stale pixels immediately so
		// even a mid-pass draw sees black rather than wrong-scale text.
		boolean scaleChanged = false;
		RegionTermView activeMenu = null;
		if (fontConfig != null)
		{
			if (!splitVisible && menuType == MenuType.SKILLS
					&& skillsView != null)
			{
				activeMenu = skillsView;
				scaleChanged = applySkillsConfig();
			}
			else if (!splitVisible
					&& (menuType == MenuType.ITEMS
						|| menuType == MenuType.HELP)
					&& itemsView != null)
			{
				activeMenu = itemsView;
				scaleChanged = menuType == MenuType.HELP
						? applyHelpConfig() : applyItemsConfig();
			}
			else if (fullVisible && fullView != null)
			{
				activeMenu = fullView;
				scaleChanged = applyFullConfig(menuType);
				// Wipe fullView on every menuType change, matching how
				// skillsView / itemsView clear below. Two independent staleness
				// sources need this:
				//   1. INVISIBLE→VISIBLE: drawPoint forwards to fullView
				//      unconditionally, so gameplay map/HUD/msg chars
				//      accumulate in the mirror while fullView is INVISIBLE
				//      (popup natural width < 80 doesn't overwrite them).
				//   2. VISIBLE→VISIBLE menu hops with matching scale (e.g.
				//      HISCORES→MAINMENU, both 1.6): no bitmap recreate fires,
				//      so repaintAllFromMirrorLocked never runs and pixels the
				//      new menu didn't paint over survive. Symptom: scattered
				//      leaderboard/gameplay glyphs persisting into the main
				//      menu after death.
				// Force scaleChanged so scheduleRedrawAfterLayout runs
				// replayAllFromShadow to refill from terminalShadow (which
				// clrscr already wiped to spaces where the old menu had
				// content).
				fullView.clear();
				scaleChanged = true;
			}
		}

		// INVISIBLE (never GONE) keeps layout dimensions stable across
		// transitions, which prevents the bleedthrough seen previously.
		if (fullView != null)
			fullView.setVisibility(fullVisible ? View.VISIBLE : View.INVISIBLE);
		if (skillsView != null)
		{
			skillsView.setVisibility(skillsVisible ? View.VISIBLE : View.INVISIBLE);
			// Wipe stale content from a previous skills session — some
			// destination cells of the fold remap aren't covered by any
			// source cell, so without a clear they can show old data.
			if (skillsVisible)
				skillsView.clear();
		}
		if (itemsView != null)
		{
			itemsView.setVisibility(itemsVisible ? View.VISIBLE : View.INVISIBLE);
			if (itemsVisible)
				itemsView.clear();
		}
		if (splitContainer != null)
			splitContainer.setVisibility(splitVisible ? View.VISIBLE : View.INVISIBLE);
		if (newgameSpeciesContainer != null)
			newgameSpeciesContainer.setVisibility(
					newgameSpeciesVisible ? View.VISIBLE : View.INVISIBLE);
		if (newgameBackgroundContainer != null)
			newgameBackgroundContainer.setVisibility(
					newgameBackgroundVisible ? View.VISIBLE : View.INVISIBLE);
		if (newgameWeaponContainer != null)
			newgameWeaponContainer.setVisibility(
					newgameWeaponVisible ? View.VISIBLE : View.INVISIBLE);
		// Quick Controls panel: only visible while the DCSS main menu is on
		// screen. fullVisible alone is not enough — every other in-game menu
		// (inventory, overview, etc.) is also "full" and the QC panel must
		// stay hidden during those. INVISIBLE preserves the LinearLayout slot
		// so fullView's measured position doesn't shift when toggling state.
		if (quickControlsView != null)
		{
			boolean qcVisible = fullVisible && menuType == MenuType.MAINMENU;
			quickControlsView.setVisibility(qcVisible ? View.VISIBLE : View.INVISIBLE);
			// INVISIBLE↔VISIBLE only calls invalidate(); the view's existing
			// bitmap (rendered during the last measure pass) is intact, so
			// invalidate() is enough to surface it.
			if (qcVisible)
				quickControlsView.invalidate();
		}

		// Re-aim newgame panels at the actual terminal columns rendered by
		// the upstream Grid widget (stretch_h means we can't pin them
		// statically). Done before the panels become visible so the first
		// drawn frame already has correct slicing.
		if (newgameSpeciesVisible)
		{
			applyNewgameSpeciesBounds();
			applyNewgameSubBounds(true);
		}
		if (newgameBackgroundVisible)
		{
			applyNewgameBackgroundBounds();
			applyNewgameSubBounds(false);
		}
		if (newgameWeaponVisible)
		{
			applyNewgameWeaponSubBounds();
		}

		if (scrollStateListener != null)
		{
			boolean menuScrollable = activeMenu != null
					&& activeMenu.isScrollEnabled();
			scrollStateListener.onMenuScrollableChanged(menuScrollable);
		}

		// Decide whether the now-active surface needs a post-layout repaint.
		// onFrame's transition replay already painted it once on the game
		// thread, but applyMenuConfig's setFontScaleMultiplier can trigger
		// a re-measure that recreates the bitmap blank AFTER that replay —
		// the mirror repaint in onMeasure covers most of it, and this
		// scheduled replay-from-shadow is the backstop that also re-runs
		// fold remaps. applyMode is only called on state transitions, so
		// this scheduler runs at most once per transition.
		View redrawTarget = null;
		if (splitVisible && splitContainer != null)
			redrawTarget = splitContainer;
		else if (newgameSpeciesVisible && newgameSpeciesContainer != null)
			redrawTarget = newgameSpeciesContainer;
		else if (newgameBackgroundVisible && newgameBackgroundContainer != null)
			redrawTarget = newgameBackgroundContainer;
		else if (newgameWeaponVisible && newgameWeaponContainer != null)
			redrawTarget = newgameWeaponContainer;
		else if (activeMenu != null
				&& (scaleChanged || activeMenu == skillsView
						|| activeMenu == itemsView))
			redrawTarget = activeMenu;

		if (redrawRequester != null && redrawTarget != null)
			scheduleRedrawAfterLayout(redrawTarget);

		// Reload overlay dismissal. GameActivity registers
		// reloadCompleteListener so the "Reloading..." overlay is torn down
		// only once DCSS has actually painted its first post-boot screen — we
		// are now laying that screen out. If a repaint was just scheduled, the
		// destination surface is momentarily blank; removing the overlay now
		// would flash it, so defer to the repaint's onFrame (see
		// reloadAwaitingRepaint). If nothing was scheduled, the surface already
		// holds the painted screen, so dismiss right away.
		if (reloadCompleteListener != null
				&& (mode == LayoutMode.GAMEPLAY
					|| menuType == MenuType.MAINMENU))
		{
			if (redrawRequester != null && redrawTarget != null)
				reloadAwaitingRepaint = true;
			else
				fireReloadComplete();
		}
	}

	// One-shot OnGlobalLayoutListener that draws the loading message into
	// fullView after the post-applyMode layout pass settles. drawing earlier
	// (e.g. directly in NativeWrapper.onGameStart pre-fix) landed chars in a
	// bitmap that applyMode was about to recreate at the pregame scale,
	// leaving a blank surface until DCSS rendered its main menu.
	private void scheduleLoadingMessageDraw()
	{
		if (fullView == null)
			return;
		final ViewTreeObserver vto = fullView.getViewTreeObserver();
		vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener()
		{
			@Override
			public void onGlobalLayout()
			{
				ViewTreeObserver o = fullView.getViewTreeObserver();
				if (o.isAlive())
					o.removeOnGlobalLayoutListener(this);
				drawLoadingMessage();
			}
		});
		// Force a layout pass so the listener is guaranteed to fire even when
		// applyMode's setFontScaleMultiplier was a no-op (scale already at the
		// pregame value from a previous game session).
		fullView.requestLayout();
	}

	private void drawLoadingMessage()
	{
		String[] lines = context.getResources()
				.getStringArray(R.array.loading_message_array);
		int rowCount = Math.min(lines.length, TERMINAL_ROWS);
		for (int r = 0; r < rowCount; r++)
		{
			String line = lines[r];
			int colCount = Math.min(line.length(), TERMINAL_COLS);
			for (int c = 0; c < colCount; c++)
				drawPoint(r, c, line.charAt(c), Color.WHITE, Color.BLACK, false);
		}
		// Same finalize a native frame gets: classify (a no-op transition —
		// the loading anchor keeps PREGAME/PREGAME) and surface the pixels.
		classifyFrame();
		invalidateAllViews();
	}

	private void scheduleRedrawAfterLayout(final View target)
	{
		final ViewTreeObserver vto = target.getViewTreeObserver();
		vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener()
		{
			@Override
			public void onGlobalLayout()
			{
				ViewTreeObserver o = target.getViewTreeObserver();
				if (o.isAlive())
					o.removeOnGlobalLayoutListener(this);
				// Prefer the Java-side replay from terminalShadow: it hits
				// the same views via drawPoint but skips ~3840 JNI round
				// trips per transition (C++ refreshTerminal → per-cell
				// printTerminalChar). Falls back to the C++ redraw path
				// only when the router hasn't seen a storm yet (shadow all
				// zeros), so the initial resume-repaint still works.
				if (hasShadowContent())
					replayAllFromShadow();
				else if (redrawRequester != null)
					redrawRequester.run();
				// Re-measure: onMeasure just ran with maxContentRow=-1
				// (cleared by applyMode), so fullView reported its full
				// canvas height and squeezed QuickControls. The refill
				// above populated maxContentRow; ask for a fresh pass.
				if (target == fullView
						&& currentMenuType == MenuType.MAINMENU)
					target.requestLayout();
			}
		});
		// Force a layout pass so the listener is guaranteed to fire. A
		// view-swap (INVISIBLE→VISIBLE) only calls invalidate() on the
		// parent, not requestLayout(), so without this the listener would
		// never run when the target's font scale didn't change — leaving
		// the freshly-cleared skillsView bitmap blank because DCSS hadn't
		// been asked to repaint into it. requestLayout coalesces with any
		// pending pass triggered by setFontScaleMultiplier, so it's safe
		// to call unconditionally.
		target.requestLayout();
	}

	// Returns true if the font scale multiplier on fullView changed (i.e.
	// the bitmap will be recreated and needs a repaint).
	private boolean applyFullConfig(MenuType type)
	{
		float scale;
		boolean scrollable;
		boolean vscrollable;
		switch (type)
		{
		case PREGAME:
			scale = fontConfig.portraitPregameFontScale;
			scrollable = fontConfig.portraitPregameScrollable;
			vscrollable = false;
			break;
		case MAINMENU:
			scale = fontConfig.portraitMainmenuFontScale;
			scrollable = fontConfig.portraitMainmenuScrollable;
			vscrollable = false;
			break;
		case ITEMS:
			// Should never happen — ITEMS uses itemsView. Fall through
			// to default to be defensive.
		case SPELLS:
			scale = fontConfig.portraitSpellsFontScale;
			scrollable = fontConfig.portraitSpellsScrollable;
			vscrollable = fontConfig.portraitSpellsVScrollable;
			break;
		case OVERVIEW:
			scale = fontConfig.portraitOverviewFontScale;
			scrollable = fontConfig.portraitOverviewScrollable;
			vscrollable = false;
			break;
		case RELIGION:
			scale = fontConfig.portraitReligionFontScale;
			scrollable = fontConfig.portraitReligionScrollable;
			// Word wrap makes the god description taller; the region is
			// widened to TERMINAL_ROWS below, so vscroll is needed to pan.
			vscrollable = msgWordwrapView != null;
			break;
		case HISCORES:
			// fullView is widened to TERMINAL_ROWS below so the full 48-row
			// scroller viewport is painted; vscroll lets the user pan when
			// the rendered bitmap is taller than the physical screen. The
			// DCSS scroll_button_into_view auto-follow inside OuterMenu
			// can't help here because it computes deltas against DCSS's
			// 48-row canvas, which is entirely visible from its own POV.
			scale = fontConfig.portraitHiscoresFontScale;
			scrollable = fontConfig.portraitHiscoresScrollable;
			vscrollable = fontConfig.portraitHiscoresVScrollable;
			break;
		case MORGUE:
			// Character log popup. fullView is widened to TERMINAL_ROWS
			// below so the full 48-row scroller viewport is painted;
			// vscroll lets the user pan when the rendered bitmap is
			// taller than the physical screen.
			scale = fontConfig.portraitMorgueFontScale;
			scrollable = fontConfig.portraitMorgueScrollable;
			vscrollable = fontConfig.portraitMorgueVScrollable;
			break;
		case TRAVEL:
			scale = fontConfig.portraitTravelFontScale;
			scrollable = fontConfig.portraitTravelScrollable;
			vscrollable = fontConfig.portraitTravelVScrollable;
			break;
		case LEVELMAP:
			scale = fontConfig.portraitLevelmapFontScale;
			scrollable = fontConfig.portraitLevelmapScrollable;
			vscrollable = fontConfig.portraitLevelmapVScrollable;
			break;
		case DESCRIBE:
			scale = fontConfig.portraitDescribeFontScale;
			scrollable = fontConfig.portraitDescribeScrollable;
			vscrollable = fontConfig.portraitDescribeVScrollable;
			break;
		case MESSAGES:
			// Ctrl+P / startup message history. fullView is widened to
			// TERMINAL_ROWS below so the full 48-row scroller is drawn
			// into the bitmap; vscroll lets the user pan to the latest
			// messages at the bottom.
			scale = fontConfig.portraitMessagesFontScale;
			scrollable = fontConfig.portraitMessagesScrollable;
			vscrollable = fontConfig.portraitMessagesVScrollable;
			break;
		case NEWGAME_NAME:
			scale = fontConfig.portraitNewgameNameFontScale;
			scrollable = fontConfig.portraitNewgameNameScrollable;
			vscrollable = fontConfig.portraitNewgameNameVScrollable;
			break;
		case SKILLS:
			// Should never happen — SKILLS uses skillsView. Fall through
			// to default to be defensive.
		case DEFAULT:
		default:
			scale = fontConfig.portraitDefaultFontScale;
			scrollable = fontConfig.portraitDefaultScrollable;
			vscrollable = fontConfig.portraitDefaultVScrollable;
			break;
		}
		int endRow = (type == MenuType.SPELLS || type == MenuType.LEVELMAP
				|| type == MenuType.MESSAGES || type == MenuType.MORGUE
				|| type == MenuType.HISCORES)
				? TERMINAL_ROWS : 28;
		// Word wrap: prose popups grow taller (same text, fewer columns) and
		// DCSS's scroller won't scroll internally until content exceeds its
		// 48-row viewport — rows 28..47 would be silently clipped by the
		// default region. Widen to the full terminal; vscroll (on for these
		// types when wrap is on) pans to the extra rows.
		if (msgWordwrapView != null
				&& (type == MenuType.DESCRIBE || type == MenuType.RELIGION
					|| type == MenuType.DEFAULT))
			endRow = TERMINAL_ROWS;
		// A regionRows change reallocates fullView's mirror in the next layout
		// pass (ensureMirrorSizedLocked), dropping every drawPoint the ongoing
		// storm has already delivered. Treat it as a bitmap-recreate event so
		// applyMode schedules a repaint request — otherwise a transition
		// between two menus that share a font scale (e.g. spells 1.75 <->
		// describe 1.75) leaves the surface blank/corrupt because scaleChanged
		// alone stays false.
		int prevEndRow = fullView.getEndRow();
		fullView.setRegionRows(0, endRow);
		fullView.setAnchorToContent(type == MenuType.MAINMENU);
		float prevScale = fullView.getFontScaleMultiplier();
		fullView.setFontScaleMultiplier(scale);
		fullView.setHorizontalScrollEnabled(scrollable);
		fullView.setVerticalScrollEnabled(vscrollable);
		// Ctrl+P opens at the latest messages (bottom of content). The
		// scroller fills the 48-row bitmap with FS_START_AT_END, but
		// resetAllScroll has just zeroed scrollOffsetY to the top, so without
		// this snap the user would land at the start of the log and have to
		// scroll down. Consumed by RegionTermView.onDraw once content and
		// viewport size are both available.
		if (type == MenuType.MESSAGES)
			fullView.requestScrollToBottom();
		return prevScale != scale || prevEndRow != endRow;
	}

	// Returns true if the font scale multiplier on skillsView changed.
	private boolean applySkillsConfig()
	{
		float scale = fontConfig.portraitSkillsFontScale;
		float prevScale = skillsView.getFontScaleMultiplier();
		skillsView.setFontScaleMultiplier(scale);
		skillsView.setHorizontalScrollEnabled(fontConfig.portraitSkillsScrollable);
		skillsView.setVerticalScrollEnabled(fontConfig.portraitSkillsVScrollable);
		return prevScale != scale;
	}

	private boolean applyItemsConfig()
	{
		float scale = fontConfig.portraitItemsFontScale;
		float prevScale = itemsView.getFontScaleMultiplier();
		itemsView.setFontScaleMultiplier(scale);
		itemsView.setHorizontalScrollEnabled(fontConfig.portraitItemsScrollable);
		itemsView.setVerticalScrollEnabled(fontConfig.portraitItemsVScrollable);
		return prevScale != scale;
	}

	private boolean applyHelpConfig()
	{
		float scale = fontConfig.portraitHelpFontScale;
		float prevScale = itemsView.getFontScaleMultiplier();
		itemsView.setFontScaleMultiplier(scale);
		itemsView.setHorizontalScrollEnabled(fontConfig.portraitHelpScrollable);
		itemsView.setVerticalScrollEnabled(fontConfig.portraitHelpVScrollable);
		return prevScale != scale;
	}

	@Override
	public boolean onGameStart()
	{
		currentMode = LayoutMode.PREGAME;
		currentMenuType = MenuType.PREGAME;
		// Intentionally NOT resetting gameplayEverDetected here — it's a
		// process-lifetime static (see field declaration). Resetting it on
		// every nativew.resize() (which routes through here) would re-break
		// menu detection after sleep/wake.
		skillsHeaderRow = -1;
		skillsLeftCol = -1;
		skillsRightCol = -1;
		skillsSimpleMode = false;
		itemsColSplit = -1;
		itemsFirstContentRow = -1;
		itemsFoldRows = -1;
		itemsLeftDest = null;
		itemsRightDest = null;
		itemsRightRowCount = 0;
		itemsFooterStartRow = -1;
		itemsFooterDestRow = -1;
		fullViewFooterStart = -1;
		fullViewFooterDest = -1;
		fullViewLastOverflow = -1;
		for (int i = 0; i < TERMINAL_ROWS; i++)
			for (int j = 0; j < TERMINAL_COLS; j++)
			{
				terminalShadow[i][j] = 0;
				terminalFg[i][j] = 0;
				terminalBg[i][j] = 0;
			}

		if (fullView != null)
		{
			fullView.post(() ->
			{
				applyMode(LayoutMode.PREGAME, MenuType.PREGAME);
				// applyMode's setFontScaleMultiplier(pregameScale) triggers a
				// layout pass that recreates fullView's bitmap blank. Schedule
				// the loading-message draw to run after that pass settles, so
				// the chars land in the final-size bitmap instead of the
				// initial 1.0x-scale bitmap that's about to be discarded.
				// Skipped on cached launches (assets already extracted) where
				// DCSS init is fast and the overlay is unnecessary.
				if (showLoadingMessage)
					scheduleLoadingMessageDraw();
			});
		}

		boolean allOk = true;
		if (fullView != null && !fullView.onGameStart())
			allOk = false;
		if (skillsView != null && !skillsView.onGameStart())
			allOk = false;
		if (itemsView != null && !itemsView.onGameStart())
			allOk = false;
		for (RegionTermView region : splitRegions)
		{
			if (!region.onGameStart())
				allOk = false;
		}
		return allOk;
	}

	// Internal single-cell entry: writes the shadow, then routes. Used by
	// drawLoadingMessage and replayAllFromShadow. Frames from DCSS arrive
	// via onFrame (which diffs into the shadow itself and routes after
	// classification) — not through here.
	public void drawPoint(int r, int c, char ch, int fcolor, int bcolor, boolean extendedErase)
	{
		if (r >= 0 && r < TERMINAL_ROWS && c >= 0 && c < TERMINAL_COLS)
		{
			terminalShadow[r][c] = ch;
			terminalFg[r][c] = fcolor;
			terminalBg[r][c] = bcolor;
		}

		routeCell(r, c, ch, fcolor, bcolor, extendedErase);
	}

	// Forward one cell to every view interested in it under the CURRENT
	// mode/menu-type/fold-anchor state. onFrame guarantees that state was
	// recomputed from this frame's grid before any cell is routed.
	private void routeCell(int r, int c, char ch, int fcolor, int bcolor,
			boolean extendedErase)
	{
		if (fullView != null)
			forwardToFullView(r, c, ch, fcolor, bcolor, extendedErase);
		// Skip splitRegions during a gameplay->menu transition frame: the
		// frame's chars belong to a menu, and forwarding them at terminal
		// coordinates into mapView/hudView/msgView would tear the next
		// visible draw before applyMode hides splitContainer.
		if (!skipSplitRegionsThisStorm)
		{
			for (RegionTermView region : splitRegions)
				region.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);
		}

		if (skillsView != null && currentMenuType == MenuType.SKILLS)
			forwardToSkillsView(r, c, ch, fcolor, bcolor, extendedErase);

		if (itemsView != null
				&& (currentMenuType == MenuType.ITEMS
					|| currentMenuType == MenuType.HELP))
			forwardToItemsView(r, c, ch, fcolor, bcolor, extendedErase);
	}

	@Override
	public void updateStatusLights(String texts, int[] colours)
	{
		if (statusBarView != null)
			statusBarView.post(() -> statusBarView.updateLights(texts, colours));
	}

	// Remap the 24x80 two-column skills layout into a compact
	// single-column view. Uses the cached anchor and the compact row
	// mappings built by recomputeSkillsAnchor(). Blank-line dividers
	// within each column are skipped; one divider row is inserted
	// between the two column sections and one before the help/button
	// block.
	private void forwardToSkillsView(int r, int c, char ch, int fcolor,
			int bcolor, boolean extendedErase)
	{
		int headerRow = skillsHeaderRow;
		int leftCol = skillsLeftCol;
		int rightCol = skillsRightCol;
		int[] ld = skillsLeftDest;
		int[] rd = skillsRightDest;
		int bottom = skillsBottomStart;
		if (headerRow < 0 || leftCol < 0 || rightCol < 0
				|| ld == null || rd == null || bottom < 0)
		{
			skillsView.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);
			return;
		}
		int fold = skillsSimpleMode ? SKILL_FOLD_ROWS - 1 : SKILL_FOLD_ROWS;
		int colShift = rightCol - leftCol;
		if (r < headerRow)
		{
			skillsView.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);
		}
		else if (r == headerRow)
		{
			if (c < rightCol)
				skillsView.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);
		}
		else if (r <= headerRow + fold)
		{
			int idx = r - headerRow - 1;
			if (c < rightCol)
			{
				int dest = ld[idx];
				if (dest >= 0)
					skillsView.drawPoint(dest, c, ch, fcolor, bcolor,
							extendedErase);
			}
			else
			{
				int dest = rd[idx];
				if (dest >= 0)
					skillsView.drawPoint(dest, c - colShift, ch, fcolor,
							bcolor, extendedErase);
			}
		}
		else
		{
			int offset = r - (headerRow + fold + 1);
			// SIMPLE: shift the button rows down by one destRow so the
			// fold output gets a blank gap between the (now contiguous)
			// help text and the first switch row, matching the trailing
			// blank that the main game's 3-row help bounds produce for
			// its 2-line description.
			if (skillsSimpleMode && r >= SKILL_SIMPLE_BUTTON_ROW)
				offset += 1;
			skillsView.drawPoint(bottom + offset, c, ch, fcolor,
					bcolor, extendedErase);
		}
	}

	private boolean isSkillHalfBlank(int row, int colStart, int colEnd)
	{
		if (row < 0 || row >= TERMINAL_ROWS)
			return true;
		for (int c = colStart; c < colEnd && c < TERMINAL_COLS; c++)
		{
			char ch = terminalShadow[row][c];
			if (ch != 0 && ch != ' ')
				return false;
		}
		return true;
	}

	// Scan rows 0..3 for the literal SKILL_HEADER text. If two matches
	// land on the same row, store both column positions and build compact
	// row mappings that eliminate blank-line dividers within each column.
	private void recomputeSkillsAnchor()
	{
		skillsHeaderRow = -1;
		skillsLeftCol = -1;
		skillsRightCol = -1;
		skillsLeftDest = null;
		skillsRightDest = null;
		skillsBottomStart = -1;
		skillsSimpleMode = false;
		int patLen = SKILL_HEADER.length();
		for (int r = 0; r <= 3; r++)
		{
			int firstCol = -1;
			for (int c = 0; c + patLen <= TERMINAL_COLS; c++)
			{
				boolean ok = true;
				for (int i = 0; i < patLen; i++)
				{
					if (terminalShadow[r][c + i] != SKILL_HEADER.charAt(i))
					{
						ok = false;
						break;
					}
				}
				if (!ok)
					continue;
				if (firstCol < 0)
					firstCol = c;
				else
				{
					skillsHeaderRow = r;
					skillsLeftCol = firstCol;
					skillsRightCol = c;
					boolean simple = true;
					for (int rr = 0; rr <= 3; rr++)
					{
						if (rowContains(rr, "Apt "))
						{
							simple = false;
							break;
						}
					}
					skillsSimpleMode = simple;
					buildSkillsCompactMap();
					return;
				}
			}
		}
	}

	// Build compact destination-row arrays that skip blank rows within
	// each column, inserting one divider between the two column sections
	// and one before the help/button block.
	private void buildSkillsCompactMap()
	{
		// SIMPLE (tutorial) shortens the fold range by 1 -- see the
		// skillsSimpleMode comment for why. ld/rd are still allocated at
		// SKILL_FOLD_ROWS so forwardToSkillsView's idx lookup is safe even
		// when iteration stops one short; the unused trailing slot stays
		// at its default -1 (skip).
		int fold = skillsSimpleMode ? SKILL_FOLD_ROWS - 1 : SKILL_FOLD_ROWS;
		int hdr = skillsHeaderRow;
		int rCol = skillsRightCol;

		int[] leftDest = new int[SKILL_FOLD_ROWS];
		int[] rightDest = new int[SKILL_FOLD_ROWS];
		for (int i = 0; i < SKILL_FOLD_ROWS; i++)
		{
			leftDest[i] = -1;
			rightDest[i] = -1;
		}
		int destRow = hdr + 1;

		for (int i = 0; i < fold; i++)
		{
			int srcRow = hdr + 1 + i;
			if (isSkillHalfBlank(srcRow, 0, rCol))
				leftDest[i] = -1;
			else
				leftDest[i] = destRow++;
		}

		destRow++;

		for (int i = 0; i < fold; i++)
		{
			int srcRow = hdr + 1 + i;
			if (isSkillHalfBlank(srcRow, rCol, TERMINAL_COLS))
				rightDest[i] = -1;
			else
				rightDest[i] = destRow++;
		}

		destRow++;

		skillsLeftDest = leftDest;
		skillsRightDest = rightDest;
		skillsBottomStart = destRow;
	}

	// Remap the 24×80 two-column item menu layout into a 48×80 single-column
	// view. When DCSS renders the inventory with MF_USE_TWO_COLUMNS, items
	// are split across left (cols 0..colSplit-1) and right (cols colSplit..79)
	// halves. This method stacks the right column underneath the left.
	// Rules:
	//   r < firstContentRow                       → pass through (title)
	//   firstContentRow <= r <= firstContentRow + foldRows - 1
	//     c < colSplit                            → pass through (left column)
	//     c >= colSplit                           → emit at (r + foldRows, c - colSplit)
	//   r > firstContentRow + foldRows - 1        → emit at (r + foldRows, c) (footer)
	// If anchor not set, forward 1:1.
	private void forwardToItemsView(int r, int c, char ch, int fcolor,
			int bcolor, boolean extendedErase)
	{
		int colSplit = itemsColSplit;
		int firstRow = itemsFirstContentRow;
		int fold = itemsFoldRows;
		int[] ld = itemsLeftDest;
		int[] rd = itemsRightDest;
		int rightCount = itemsRightRowCount;
		if (colSplit < 0 || firstRow < 0 || fold <= 0 || ld == null)
		{
			int footerStart = itemsFooterStartRow;
			int footerDest = itemsFooterDestRow;
			if (footerStart >= 0 && footerDest >= 0 && r >= footerStart)
				itemsView.drawPoint(footerDest + (r - footerStart), c,
						ch, fcolor, bcolor, extendedErase);
			else
				itemsView.drawPoint(r, c, ch, fcolor, bcolor,
						extendedErase);
			return;
		}
		if (r < firstRow)
		{
			itemsView.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);
		}
		else if (r < firstRow + fold)
		{
			int rDest = (rd != null) ? rd[r] : -1;
			if (rDest < 0 || c < colSplit)
			{
				// No right item on this row (heading / left-only):
				// all columns go to leftDest. For rows with a right
				// item, only cols < colSplit go to leftDest.
				int dest = ld[r];
				if (dest >= 0)
					itemsView.drawPoint(dest, c, ch, fcolor, bcolor,
							extendedErase);
			}
			else
			{
				itemsView.drawPoint(rDest, c - colSplit, ch, fcolor,
						bcolor, extendedErase);
			}
		}
		else
		{
			int footerStart = itemsFooterStartRow;
			int footerDest = itemsFooterDestRow;
			if (footerStart >= 0 && footerDest >= 0 && r >= footerStart)
			{
				itemsView.drawPoint(footerDest + (r - footerStart), c,
						ch, fcolor, bcolor, extendedErase);
			}
			else
			{
				itemsView.drawPoint(r + rightCount, c, ch, fcolor,
						bcolor, extendedErase);
			}
		}
	}

	// Scan terminalShadow to detect the two-column item menu layout and
	// compute fold parameters. Looks for non-space content past column 35
	// in rows 1..22 to find the column split and count content rows.
	private void recomputeItemsAnchor()
	{
		int prevSplit = itemsColSplit;
		int prevFirst = itemsFirstContentRow;
		int prevFold = itemsFoldRows;
		int[] prevLd = itemsLeftDest;
		int[] prevRd = itemsRightDest;
		int prevFooterStart = itemsFooterStartRow;
		int prevFooterDest = itemsFooterDestRow;

		itemsColSplit = -1;
		itemsFirstContentRow = -1;
		itemsFoldRows = -1;

		// Find the first row with content and the column split by scanning
		// for the earliest non-space character past the midpoint. DCSS
		// places the right column at m_nat_column_width (≤ 40).
		int detectedSplit = -1;
		int firstContent = -1;
		int lastContent = -1;
		for (int r = 1; r < TERMINAL_ROWS - 1; r++)
		{
			boolean leftHasContent = false;
			for (int c = 0; c < 35; c++)
			{
				if (terminalShadow[r][c] != ' ' && terminalShadow[r][c] != 0)
				{
					leftHasContent = true;
					break;
				}
			}
			if (!leftHasContent)
			{
				if (firstContent >= 0)
					break;
				continue;
			}
			if (firstContent < 0)
				firstContent = r;
			lastContent = r;

			// Look for a right-column item entry on this row.
			// Real two-column items start with " <letter> - "
			// (indent + slot letter + separator).  Plain overflow
			// text from long single-column names won't match.
			if (detectedSplit < 0)
			{
				for (int c = 35; c < 49; c++)
				{
					char sc = terminalShadow[r][c];
					if ((sc >= 'a' && sc <= 'z') || (sc >= 'A' && sc <= 'Z'))
					{
						char sep = terminalShadow[r][c + 2];
							if (c + 3 < TERMINAL_COLS
								&& terminalShadow[r][c + 1] == ' '
								&& (sep == '-' || sep == '+')
								&& terminalShadow[r][c + 3] == ' ')
						{
							// Include the leading indent space
							detectedSplit = (c > 0
									&& terminalShadow[r][c - 1] == ' ')
									? c - 1 : c;
							break;
						}
					}
				}
			}
		}

		// Detect footer rows: scan upward from the bottom of the terminal
		// to find non-blank rows separated from item content by a gap.
		int footerStart = -1;
		for (int fr = TERMINAL_ROWS - 1; fr > lastContent; fr--)
		{
			boolean hasContent = false;
			for (int fc = 0; fc < TERMINAL_COLS; fc++)
			{
				if (terminalShadow[fr][fc] != ' '
						&& terminalShadow[fr][fc] != 0)
				{
					hasContent = true;
					break;
				}
			}
			if (hasContent)
				footerStart = fr;
		}

		if (detectedSplit < 0 || firstContent < 0)
		{
			itemsLeftDest = null;
			itemsRightDest = null;
			itemsRightRowCount = 0;
			// Even without a fold, compress the footer gap.
			if (footerStart >= 0 && lastContent >= 0
					&& footerStart > lastContent + 1)
			{
				itemsFooterStartRow = footerStart;
				itemsFooterDestRow = lastContent + 2;
			}
			else
			{
				itemsFooterStartRow = -1;
				itemsFooterDestRow = -1;
			}
			if (prevFold > 0
					|| itemsFooterStartRow != prevFooterStart
					|| itemsFooterDestRow != prevFooterDest)
				replayItemsFromShadow();
			return;
		}

		// Build per-section fold maps. For each section (group of item
		// rows between headings), right-column items are inserted
		// immediately after that section's left-column items. This makes
		// the fold's visual order match DCSS's navigation order.
		int[] ld = new int[TERMINAL_ROWS];
		int[] rd = new int[TERMINAL_ROWS];
		java.util.Arrays.fill(ld, -1);
		java.util.Arrays.fill(rd, -1);

		int dest = firstContent;
		int rightCount = 0;
		int r = firstContent;
		while (r <= lastContent)
		{
			if (isItemRow(r))
			{
				// Collect this section's rows (until heading or end)
				java.util.List<Integer> secRightRows =
						new java.util.ArrayList<>();
				while (r <= lastContent && isItemRow(r))
				{
					ld[r] = dest++;
					if (hasRightItemAt(r, detectedSplit))
						secRightRows.add(r);
					r++;
				}
				for (int rr : secRightRows)
				{
					rd[rr] = dest++;
					rightCount++;
				}
			}
			else
			{
				// Heading or non-item row — full width passthrough
				ld[r] = dest++;
				r++;
			}
		}

		itemsColSplit = detectedSplit;
		itemsFirstContentRow = firstContent;
		itemsFoldRows = lastContent - firstContent + 1;
		itemsLeftDest = ld;
		itemsRightDest = rd;
		itemsRightRowCount = rightCount;
		if (footerStart >= 0 && footerStart > lastContent + 1)
		{
			itemsFooterStartRow = footerStart;
			itemsFooterDestRow = dest + 1;
		}
		else
		{
			itemsFooterStartRow = -1;
			itemsFooterDestRow = -1;
		}

		if (itemsColSplit != prevSplit
				|| itemsFirstContentRow != prevFirst
				|| itemsFoldRows != prevFold
				|| !java.util.Arrays.equals(ld, prevLd)
				|| !java.util.Arrays.equals(rd, prevRd)
				|| itemsFooterStartRow != prevFooterStart
				|| itemsFooterDestRow != prevFooterDest)
		{
			replayItemsFromShadow();
		}
	}

	// Item rows start with " <letter> - " (space at col 0, letter at 1,
	// " - " at 2-4). Headings start with a capital letter at col 0.
	private boolean isItemRow(int r)
	{
		if (r < 0 || r >= TERMINAL_ROWS)
			return false;
		char sep = terminalShadow[r][3];
		return terminalShadow[r][0] == ' '
				&& (sep == '-' || sep == '+')
				&& terminalShadow[r][2] == ' '
				&& terminalShadow[r][4] == ' ';
	}

	private boolean hasRightItemAt(int r, int colSplit)
	{
		for (int c = colSplit; c <= colSplit + 2
				&& c + 3 < TERMINAL_COLS; c++)
		{
			char ch = terminalShadow[r][c];
			if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z'))
			{
				char sep2 = terminalShadow[r][c + 2];
					if (terminalShadow[r][c + 1] == ' '
						&& (sep2 == '-' || sep2 == '+')
						&& terminalShadow[r][c + 3] == ' ')
					return true;
			}
		}
		return false;
	}

	private void replayItemsFromShadow()
	{
		itemsView.clear();
		for (int r = 0; r < TERMINAL_ROWS; r++)
			for (int c = 0; c < TERMINAL_COLS; c++)
				forwardToItemsView(r, c, terminalShadow[r][c],
						terminalFg[r][c], terminalBg[r][c], false);
	}

	// Compute fold parameters for two-column help screens. Populates the
	// same items* fields used by forwardToItemsView(). The column split is
	// fixed at HELP_COL_SPLIT (42), matching column_composer(2, 42) in
	// command.cc. Left-column content stays in place; right-column content
	// is stacked sequentially below the last left-column row.
	private void recomputeHelpAnchor()
	{
		int prevSplit = itemsColSplit;
		int prevFirst = itemsFirstContentRow;
		int prevFold = itemsFoldRows;
		int[] prevLd = itemsLeftDest;
		int[] prevRd = itemsRightDest;

		itemsColSplit = -1;
		itemsFirstContentRow = -1;
		itemsFoldRows = -1;
		itemsFooterStartRow = -1;
		itemsFooterDestRow = -1;

		// Scan for content rows and detect whether there's a right column.
		int firstContent = -1;
		int lastContent = -1;
		boolean hasRightCol = false;
		for (int r = 0; r < TERMINAL_ROWS; r++)
		{
			boolean rowHasLeft = false;
			boolean rowHasRight = false;
			for (int c = 0; c < HELP_COL_SPLIT; c++)
			{
				if (terminalShadow[r][c] != ' ' && terminalShadow[r][c] != 0)
				{
					rowHasLeft = true;
					break;
				}
			}
			for (int c = HELP_COL_SPLIT; c < TERMINAL_COLS; c++)
			{
				if (terminalShadow[r][c] != ' ' && terminalShadow[r][c] != 0)
				{
					rowHasRight = true;
					break;
				}
			}
			if (rowHasLeft || rowHasRight)
			{
				if (firstContent < 0)
					firstContent = r;
				lastContent = r;
				if (rowHasRight)
					hasRightCol = true;
			}
		}

		if (firstContent < 0 || !hasRightCol)
		{
			itemsLeftDest = null;
			itemsRightDest = null;
			itemsRightRowCount = 0;
			if (prevFold > 0)
				replayItemsFromShadow();
			return;
		}

		// Build fold maps: left-column rows first, then right-column rows.
		int[] ld = new int[TERMINAL_ROWS];
		int[] rd = new int[TERMINAL_ROWS];
		java.util.Arrays.fill(ld, -1);
		java.util.Arrays.fill(rd, -1);

		int dest = firstContent;
		int rightCount = 0;

		// Pass 1: assign left-column destinations (all content rows).
		for (int r = firstContent; r <= lastContent; r++)
			ld[r] = dest++;

		// Pass 2: assign right-column destinations below the left block.
		// Insert a blank spacer row before each section heading. A heading
		// is detected as a right-column row that follows a terminal row
		// whose right column was blank (DCSS puts blank lines between
		// sections in the column_composer output).
		boolean prevRightBlank = true;
		for (int r = firstContent; r <= lastContent; r++)
		{
			boolean rowHasRight = false;
			for (int c = HELP_COL_SPLIT; c < TERMINAL_COLS; c++)
			{
				if (terminalShadow[r][c] != ' ' && terminalShadow[r][c] != 0)
				{
					rowHasRight = true;
					break;
				}
			}
			if (rowHasRight)
			{
				if (prevRightBlank)
					dest++;
				rd[r] = dest++;
				rightCount++;
			}
			prevRightBlank = !rowHasRight;
		}

		itemsColSplit = HELP_COL_SPLIT;
		itemsFirstContentRow = firstContent;
		itemsFoldRows = lastContent - firstContent + 1;
		itemsLeftDest = ld;
		itemsRightDest = rd;
		itemsRightRowCount = rightCount;

		if (itemsColSplit != prevSplit
				|| itemsFirstContentRow != prevFirst
				|| itemsFoldRows != prevFold
				|| !java.util.Arrays.equals(ld, prevLd)
				|| !java.util.Arrays.equals(rd, prevRd))
		{
			replayItemsFromShadow();
		}
	}

	// Scan terminalShadow for keyhelp/footer rows that fall past row 23.
	// When the DCSS "more" widget renders past that boundary, compress
	// the footer to right after the last content row. applyMode handles
	// the endRow expansion on the UI thread; this method only sets the
	// remap coordinates used by forwardToFullView.
	private void recomputeFullViewFooter()
	{
		int prevStart = fullViewFooterStart;
		int prevDest = fullViewFooterDest;
		int prevOverflow = fullViewLastOverflow;
		fullViewFooterStart = -1;
		fullViewFooterDest = -1;
		fullViewLastOverflow = -1;

		int lastContent = -1;
		for (int r = 0; r < 24; r++)
		{
			for (int c = 0; c < TERMINAL_COLS; c++)
			{
				if (terminalShadow[r][c] != ' ' && terminalShadow[r][c] != 0)
				{
					lastContent = r;
					break;
				}
			}
		}

		// Find the footer block at the bottom of the terminal. Scan
		// upward from row 47, tolerating small gaps (<=3 blank rows)
		// so that spacer lines within set_more() text don't split
		// the footer. The gap between overflow content and the footer
		// is always much larger (>10 rows).
		int footerStart = -1;
		int footerEnd = -1;
		int blankRun = 0;
		for (int r = TERMINAL_ROWS - 1; r >= 24; r--)
		{
			boolean hasContent = false;
			for (int c = 0; c < TERMINAL_COLS; c++)
			{
				if (terminalShadow[r][c] != ' ' && terminalShadow[r][c] != 0)
				{
					hasContent = true;
					break;
				}
			}
			if (hasContent)
			{
				if (footerEnd < 0)
					footerEnd = r;
				footerStart = r;
				blankRun = 0;
			}
			else if (footerEnd >= 0)
			{
				blankRun++;
				if (blankRun > 3)
					break;
			}
		}

		if (footerStart >= 0 && lastContent >= 0)
		{
			// Find the last overflow content row between row 24 and the
			// footer. These are spell-list items that spill past the
			// default 24-row boundary.
			int lastOverflow = -1;
			for (int r = footerStart - 1; r >= 24; r--)
			{
				for (int c = 0; c < TERMINAL_COLS; c++)
				{
					if (terminalShadow[r][c] != ' ' && terminalShadow[r][c] != 0)
					{
						lastOverflow = r;
						break;
					}
				}
				if (lastOverflow >= 0)
					break;
			}

			fullViewFooterStart = footerStart;
			fullViewLastOverflow = lastOverflow;
			// +2 (not +1) leaves a 1-row spacer between content and the
			// remapped footer, matching itemsView's footer compression
			// (recomputeItemsAnchor uses lastContent+2). Without this the
			// "more" prompt sits flush against the last list row.
			if (lastOverflow >= 0)
				fullViewFooterDest = lastOverflow + 2;
			else
				fullViewFooterDest = lastContent + 2;
		}

		if (fullViewFooterStart != prevStart
				|| fullViewFooterDest != prevDest
				|| fullViewLastOverflow != prevOverflow)
		{
			replayFullViewFromShadow();
		}
	}

	private void replayFullViewFromShadow()
	{
		if (fullView == null)
			return;
		fullView.clear();
		for (int r = 0; r < TERMINAL_ROWS; r++)
			for (int c = 0; c < TERMINAL_COLS; c++)
				forwardToFullView(r, c, terminalShadow[r][c],
						terminalFg[r][c], terminalBg[r][c], false);
	}

	// True once at least one storm has populated terminalShadow. Cheap scan:
	// bails on the first non-zero, non-space cell.
	private boolean hasShadowContent()
	{
		for (int r = 0; r < TERMINAL_ROWS; r++)
			for (int c = 0; c < TERMINAL_COLS; c++)
			{
				char ch = terminalShadow[r][c];
				if (ch != 0 && ch != ' ')
					return true;
			}
		return false;
	}

	// Replay the full 48x80 terminal state to every active view via drawPoint
	// (same routing DCSS's refreshTerminal would trigger, minus ~3840 JNI
	// round trips). Runs on the UI thread after a layout pass that recreated
	// bitmaps; the game thread may be updating terminalShadow concurrently
	// but drawPoint's per-view renderLock serialises the writes and a stale
	// cell just gets overwritten by the next storm.
	private void replayAllFromShadow()
	{
		for (int r = 0; r < TERMINAL_ROWS; r++)
			for (int c = 0; c < TERMINAL_COLS; c++)
			{
				char ch = terminalShadow[r][c];
				if (ch == 0)
					ch = ' ';
				drawPoint(r, c, ch, terminalFg[r][c],
						terminalBg[r][c], false);
			}
		if (fullView != null)
			fullView.postInvalidate();
		if (skillsView != null)
			skillsView.postInvalidate();
		if (itemsView != null)
			itemsView.postInvalidate();
		for (RegionTermView region : splitRegions)
			region.postInvalidate();
	}

	private void forwardToFullView(int r, int c, char ch, int fcolor,
			int bcolor, boolean extendedErase)
	{
		int fStart = fullViewFooterStart;
		int fDest = fullViewFooterDest;
		int lastOv = fullViewLastOverflow;
		if (fStart >= 0 && fDest >= 0 && r >= fStart)
			fullView.drawPoint(fDest + (r - fStart), c,
					ch, fcolor, bcolor, extendedErase);
		else if (fStart >= 0 && r >= 24 && r <= lastOv)
			fullView.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);
		else if (fStart >= 0 && r >= 24)
			return;
		else
			fullView.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);
	}

	private boolean matchesAt(int row, int col, String pattern)
	{
		if (row < 0 || row >= TERMINAL_ROWS)
			return false;
		if (col + pattern.length() > TERMINAL_COLS)
			return false;
		for (int i = 0; i < pattern.length(); i++)
			if (terminalShadow[row][col + i] != pattern.charAt(i))
				return false;
		return true;
	}

	// True if `pattern` appears at the start of `row` after any leading
	// spaces. Use this for title-prefix detection instead of matchesAt(r,0,p):
	// Menu::set_title(..., indent=true) (menu.cc:1479) prepends a single space
	// to the title text via Menu::update_title (menu.cc:2878-2883), shifting
	// the title to col 1. Strict col-0 matching silently misclassifies every
	// such menu as DEFAULT — historically this hit spell list (I), cast (z),
	// memorise (M), and abilities (a), all of which pass indent=true. Drift
	// in upstream's indent flag is a recurring source of "menu lost its scale"
	// bugs; tolerating leading whitespace makes detection robust to that flag.
	private boolean rowStartsWith(int row, String pattern)
	{
		if (row < 0 || row >= TERMINAL_ROWS)
			return false;
		int start = 0;
		while (start < TERMINAL_COLS && terminalShadow[row][start] == ' ')
			start++;
		if (start + pattern.length() > TERMINAL_COLS)
			return false;
		for (int i = 0; i < pattern.length(); i++)
			if (terminalShadow[row][start + i] != pattern.charAt(i))
				return false;
		return true;
	}

	private boolean rowContains(int row, String pattern)
	{
		if (row < 0 || row >= TERMINAL_ROWS)
			return false;
		int last = TERMINAL_COLS - pattern.length();
		for (int c = 0; c <= last; c++)
		{
			boolean ok = true;
			for (int i = 0; i < pattern.length(); i++)
			{
				if (terminalShadow[row][c + i] != pattern.charAt(i))
				{
					ok = false;
					break;
				}
			}
			if (ok)
				return true;
		}
		return false;
	}

	private LayoutMode detectMode()
	{
		for (int r = HUD_ANCHOR_ROW_MIN; r <= HUD_ANCHOR_ROW_MAX; r++)
		{
			for (String label : HUD_LABELS)
			{
				if (matchesAt(r, HUD_ANCHOR_COL, label))
					return LayoutMode.GAMEPLAY;
			}
		}
		return gameplayEverDetected ? LayoutMode.MENU : LayoutMode.PREGAME;
	}

	private MenuType detectMenuType()
	{
		// Message history popup (Ctrl+P, startup error log). Flag is set by
		// the patched _replay_messages_core in message.cc via JNI before
		// hist.show() runs and cleared after it returns, so this branch is
		// only taken while the popup is actually open. Checked first so it
		// wins over any content-anchor match (e.g. a "_Welcome back" line
		// quoted in the history would otherwise look pregame-ish).
		if (messageHistoryMode)
			return MenuType.MESSAGES;

		// Character log popup opened from the High Scores menu. Same
		// pattern as messageHistoryMode: the underlying formatted_scroller
		// has no title anchor, and its content — the morgue file — hits
		// several other menu anchors as the user scrolls (Turns:/Skill/
		// Granted powers:). The JNI flag is single-writer (game thread
		// inside _show_morgue) so torn reads aren't a concern.
		if (characterLogMode)
			return MenuType.MORGUE;

		// Pregame-style screens reachable from MENU mode after the first
		// gameplay frame: once gameplayEverDetected sticks at true,
		// detectMode() returns MENU (not PREGAME) for every non-gameplay
		// frame, so Ctrl+S → main menu and Ctrl+S → New Game both arrive
		// here instead of detectPregameType. Share the anchor helper so any
		// new pregame screen added in the future works from both flows
		// without needing to be duplicated across detect functions.
		MenuType pregame = detectPregameAnchor();
		if (pregame != null)
			return pregame;

		// Row 0 prefix matches: cheapest, covers all set_title-style menus.
		// Use rowStartsWith (not matchesAt(0,0,...)) so the leading space
		// added by Menu::set_title(..., indent=true) doesn't shift the title
		// past our anchor.
		for (String p : ITEMS_ROW0_PREFIXES)
		{
			if (rowStartsWith(0, p))
				return MenuType.ITEMS;
		}
		// Quiver action menu (Shift+Q): title row is blank, so identify
		// via the unique "focus mode:" string in the menu's more widget,
		// which lives near the bottom of the terminal. Maps to ITEMS so
		// it inherits the same scale/scroll config as inventory.
		for (int r = 0; r < TERMINAL_ROWS; r++)
		{
			if (rowContains(r, QUIVER_MORE_MARKER))
				return MenuType.ITEMS;
		}
		for (String p : SPELLS_ROW0_PREFIXES)
		{
			if (rowStartsWith(0, p))
				return MenuType.SPELLS;
		}
		// Stash search results (Ctrl+F). Row 0 contains "[toggle:" as part
		// of the StashSearchMenu calc_title format.
		if (rowContains(0, TRAVEL_TOGGLE_MARKER))
			return MenuType.ITEMS;

		// Help screen (?). The formatted_scroller popup renders two-column
		// keybinding content starting at row 0 (no title widget). Detected
		// before custom-rendered screens so "Dungeon Crawl Help" doesn't
		// accidentally match a looser scan below.
		for (String p : HELP_ROW0_PREFIXES)
		{
			if (rowStartsWith(0, p))
				return MenuType.HELP;
		}

		// Level map (Shift+X). _draw_title in viewmap.cc renders
		// "(Press ? for help)" at the right of row 0.
		if (rowContains(0, LEVELMAP_HELP_MARKER))
			return MenuType.LEVELMAP;

		// Custom-rendered screens — scan distinctive markers across rows.
		// Hiscores: title widget renders "<game name>: High Scores" near
		// top of the High Scores menu (rows 0-8). The death/quit screen
		// (end.cc) renders "Best Crawlers - <game type>" as the header
		// above its embedded leaderboard, but the goodbye_msg block
		// (death summary, stats) above it can push that header past
		// row 8 — scan the full visible window for it. Both phrases are
		// unique strings, no false-positive risk elsewhere.
		for (int r = 0; r <= 8; r++)
		{
			if (rowContains(r, "High Scores"))
				return MenuType.HISCORES;
		}
		for (int r = 0; r <= 23; r++)
		{
			if (rowContains(r, "Best Crawlers"))
				return MenuType.HISCORES;
		}
		// Overview (%): _get_overview_progress emits "Turns:" at the start
		// of a line — distinctive vs. the gameplay HUD (which prints
		// "Time:" / "Turn:" but never "Turns:"). Anchored near the top of
		// the popup content (row ~5, after title + 3-row stat block + a
		// blank row), so it stays visible regardless of popup viewport
		// size or scroll position. The earlier "@:" anchor from
		// _status_mut_rune_list lived at the BOTTOM of the overview; the
		// 0.34 reorg (043235e) inserted a 5-row progress block above it
		// and pushed "@:" past the rows 1-18 search window, so the menu
		// fell through to DEFAULT and lost portrait_overview_font_scale.
		// Allow a small left-margin for any popup framing.
		for (int r = 1; r <= 18; r++)
		{
			for (int c = 0; c <= 4; c++)
			{
				if (matchesAt(r, c, "Turns:"))
					return MenuType.OVERVIEW;
			}
		}
		// Religion (^): describe-god always renders "Granted powers:" header.
		for (int r = 0; r < TERMINAL_ROWS; r++)
		{
			if (rowContains(r, "Granted powers:"))
				return MenuType.RELIGION;
		}
		// Skill training (m): SkillMenu column title. SKILL_HEADER
		// ("     Skill", 5 leading spaces + "Skill") is emitted by
		// SkillMenuEntry::set_title for every column-title row in both
		// regular and tutorial/hints (SKMF_SIMPLE) mode -- unlike "Apt ",
		// which is only emitted when SKMF_APTITUDE is set (regular mode
		// only). Using SKILL_HEADER keeps detection aligned with the
		// fold anchor in recomputeSkillsAnchor() so the tutorial gets
		// the same single-column layout as the main game.
		for (int r = 0; r <= 3; r++)
		{
			if (rowContains(r, SKILL_HEADER))
				return MenuType.SKILLS;
		}

		// Description popups (item/spell/monster/feature/ability examine).
		// All use show_description() or equivalent with a title widget at
		// the top and set_margin_for_crt(0, 0, 1, 0), producing: row 0 =
		// title, row 1 = blank margin, row 2+ = description body.
		if (!rowIsBlank(0) && rowIsBlank(1) && !rowIsBlank(2))
			return MenuType.DESCRIBE;

		return MenuType.DEFAULT;
	}

	// Atomic frame entry point (TerminalRenderer). libandroid.cc delivers
	// the whole 48x80 grid in one JNI call per storm, with display_lock held
	// throughout. Ordering is the point: the frame is diffed into
	// terminalShadow and CLASSIFIED (mode, menu type, fold anchors) before
	// any cell is routed to a view — under the old per-cell protocol, cells
	// were routed under the PREVIOUS frame's state and corrected at storm
	// end, which flashed unfolded/mis-scaled content for a frame and could
	// tear mid-storm on a vsync.
	@Override
	public void onFrame(char[] chars, int[] fg, int[] bg)
	{
		// 1. Diff into the shadow; frameChanged marks cells needing repaint.
		for (int r = 0; r < TERMINAL_ROWS; r++)
		{
			int base = r * TERMINAL_COLS;
			for (int c = 0; c < TERMINAL_COLS; c++)
			{
				char ch = chars[base + c];
				int f = fg[base + c];
				int b = bg[base + c];
				boolean changed = terminalShadow[r][c] != ch
						|| terminalFg[r][c] != f
						|| terminalBg[r][c] != b;
				frameChanged[r][c] = changed;
				if (changed)
				{
					terminalShadow[r][c] = ch;
					terminalFg[r][c] = f;
					terminalBg[r][c] = b;
				}
			}
		}

		// 2. Classify the new frame and handle any state transition (anchor
		// recomputes, applyMode post) BEFORE routing.
		boolean wasGameplay = currentMode == LayoutMode.GAMEPLAY;
		boolean transition = classifyFrame();
		// Tear guard (previously the C++ preStormHint mechanism): on
		// gameplay -> non-gameplay, this frame's cells must not land in the
		// still-VISIBLE split panels; applyMode hides them later on the UI
		// thread. They keep stale gameplay pixels until the next transition
		// INTO gameplay clears and repaints them.
		skipSplitRegionsThisStorm = wasGameplay
				&& currentMode != LayoutMode.GAMEPLAY;

		// 3. Route under the NEW state. On a transition, replay the whole
		// grid so views that just became active are fully painted before
		// applyMode makes them visible (they were skipped or stale while
		// inactive); steady-state frames route only changed cells.
		for (int r = 0; r < TERMINAL_ROWS; r++)
			for (int c = 0; c < TERMINAL_COLS; c++)
			{
				if (!transition && !frameChanged[r][c])
					continue;
				char ch = terminalShadow[r][c];
				routeCell(r, c, ch == 0 ? ' ' : ch, terminalFg[r][c],
						terminalBg[r][c], false);
			}
		skipSplitRegionsThisStorm = false;

		invalidateAllViews();

		// This frame is the post-applyMode repaint of the reload destination:
		// its cells refilled the bitmap above, so the overlay can now be
		// removed without exposing the blank surface applyMode left.
		if (reloadAwaitingRepaint)
		{
			reloadAwaitingRepaint = false;
			fireReloadComplete();
		}
	}

	// Detect mode/menu-type from terminalShadow and apply state transitions:
	// fold-anchor recomputes run here on the game thread (the routing pass
	// that follows needs them immediately), applyMode is posted to the UI
	// thread (visibility/scale changes). Also runs the per-frame recomputes
	// (newgame sub bounds, items/help anchors, fullView footer) so routing
	// uses this frame's geometry. Returns true when a transition occurred.
	private boolean classifyFrame()
	{
		LayoutMode detected = detectMode();
		MenuType detectedType;
		if (detected == LayoutMode.MENU)
			detectedType = detectMenuType();
		else if (detected == LayoutMode.PREGAME)
			detectedType = detectPregameType();
		else
			detectedType = MenuType.DEFAULT;

		boolean transition = detected != currentMode
				|| detectedType != currentMenuType;
		if (transition)
		{
			// menu -> gameplay: splitRegions still hold stale menu-era
			// content. Clear now, on the game thread — onFrame's routing
			// pass repaints the full gameplay frame into them right after
			// this, and applyMode's scheduleRedrawAfterLayout covers any
			// bitmap recreate from a scale change. The reverse direction
			// (gameplay -> menu) is handled by skipSplitRegionsThisStorm
			// in onFrame.
			if (currentMode != LayoutMode.GAMEPLAY
					&& detected == LayoutMode.GAMEPLAY)
			{
				for (RegionTermView region : splitRegions)
					region.clear();
			}

			currentMode = detected;
			currentMenuType = detectedType;
			if (detected == LayoutMode.GAMEPLAY)
				gameplayEverDetected = true;

			// Recompute the fold anchor as we enter the skills menu so this
			// frame's routing pass can remap. Done here (not inside the
			// applyMode post) because applyMode runs on the UI thread later;
			// the cells routed right after classification need the anchor
			// immediately.
			if (detectedType == MenuType.SKILLS)
				recomputeSkillsAnchor();
			else
			{
				skillsHeaderRow = -1;
				skillsLeftCol = -1;
				skillsRightCol = -1;
			}
			if (detectedType == MenuType.ITEMS)
				recomputeItemsAnchor();
			else if (detectedType == MenuType.HELP)
				recomputeHelpAnchor();
			else
			{
				itemsColSplit = -1;
				itemsFirstContentRow = -1;
				itemsFoldRows = -1;
				itemsLeftDest = null;
				itemsRightDest = null;
				itemsRightRowCount = 0;
				itemsFooterStartRow = -1;
				itemsFooterDestRow = -1;
			}
			fullViewFooterStart = -1;
			fullViewFooterDest = -1;
			fullViewLastOverflow = -1;
			final LayoutMode targetMode = detected;
			final MenuType targetType = detectedType;
			if (fullView != null)
				fullView.post(() -> applyMode(targetMode, targetType));
			if (keyboardView != null)
				keyboardView.setArrowsVisible(
						targetMode == LayoutMode.GAMEPLAY);
		}

		// While in newgame, re-detect sub-items grid bounds every frame.
		// The description Switcher's height changes when the user navigates
		// between species/background items (no description ↔ multi-line
		// description), which shifts the sub-items grid down or up. applyMode
		// only fires on state transitions, so without this re-check the new
		// content would land outside the previously-set panel rows. The
		// setRegionRows / setRegionCols / setRowColShift calls early-return
		// when bounds are unchanged, so this is a no-op on most frames.
		if (currentMenuType == MenuType.NEWGAME_SPECIES && fullView != null)
			fullView.post(() -> applyNewgameSubBounds(true));
		else if (currentMenuType == MenuType.NEWGAME_BACKGROUND && fullView != null)
			fullView.post(() -> applyNewgameSubBounds(false));
		else if (currentMenuType == MenuType.NEWGAME_WEAPON && fullView != null)
			fullView.post(() -> applyNewgameWeaponSubBounds());

		// Recompute items/help fold anchor every frame while active.
		// Content changes when the user scrolls, switches pages,
		// or the two-column layout toggles on/off depending on item count.
		if (currentMenuType == MenuType.ITEMS && itemsView != null)
			recomputeItemsAnchor();
		else if (currentMenuType == MenuType.HELP && itemsView != null)
			recomputeHelpAnchor();

		// Compress fullView footer for menus whose keyhelp renders past
		// row 23. ITEMS/HELP/SKILLS use their own views; GAMEPLAY and
		// PREGAME don't have keyhelp footers. HISCORES content extends
		// past row 23 as continuous entries with no keyhelp gap, so
		// compression would create a phantom spacer at row 24.
		// LEVELMAP (Shift+X) renders dungeon map content continuously
		// across all terminal rows — no keyhelp footer to compress.
		if (detected == LayoutMode.MENU
				&& detectedType != MenuType.ITEMS
				&& detectedType != MenuType.HELP
				&& detectedType != MenuType.SKILLS
				&& detectedType != MenuType.HISCORES
				&& detectedType != MenuType.MORGUE
				&& detectedType != MenuType.LEVELMAP
				&& fullView != null)
			recomputeFullViewFooter();

		return transition;
	}

	private void invalidateAllViews()
	{
		if (fullView != null)
		{
			fullView.postInvalidate();
			if (currentMenuType == MenuType.MAINMENU)
				fullView.post(() -> fullView.requestLayout());
		}
		if (skillsView != null)
			skillsView.postInvalidate();
		if (itemsView != null)
			itemsView.postInvalidate();
		for (RegionTermView region : splitRegions)
			region.postInvalidate();
	}

	// Sub-classify LayoutMode.PREGAME. Before DCSS runs, SplashActivity
	// hands off to GameActivity which prints the loading-message string
	// (see res/values/string-array.xml; first entry begins "Launching
	// game."). DCSS then clears the screen and prints "Hello, welcome to
	// Dungeon Crawl ...". We match the loading anchor at row 0; if it's
	// not there, the screen has been overwritten by DCSS.
	//
	// After "New game" is selected, DCSS shows the species/background
	// picker with row 0 = "Welcome, %s. Please select your species." or
	// "...your background.". Both prefixed "Welcome, " so we additionally
	// require the "species."/"background." token in the same row to
	// disambiguate from the main menu's "Hello, welcome to Dungeon Crawl"
	// and from each other.
	private MenuType detectPregameType()
	{
		if (matchesAt(0, 0, LOADING_ANCHOR))
			return MenuType.PREGAME;
		MenuType anchor = detectPregameAnchor();
		if (anchor != null)
			return anchor;
		// Help screen reachable from the main menu (? before starting a
		// game). Shares the same row-0 prefixes as the in-game path.
		for (String p : HELP_ROW0_PREFIXES)
		{
			if (rowStartsWith(0, p))
				return MenuType.HELP;
		}
		// Pregame fallthrough: an unrecognized non-loading screen.
		// DEFAULT is the safe catch-all: gives the screen
		// portraitDefaultFontScale and keeps the QC panel (gated on
		// MAINMENU specifically) hidden.
		return MenuType.DEFAULT;
	}

	// Specific pregame-style screen anchors (excluding the loading screen,
	// which is only ever shown pre-gameplay and so doesn't need to be
	// detectable from MENU mode). Returns null when no anchor matches so
	// callers can apply their own fallback — detectPregameType falls
	// through to MAINMENU, detectMenuType falls through to its own
	// in-game-menu anchors. Centralizing these anchors here means any
	// future pregame screen added to this helper is automatically detected
	// from both pregame-mode and post-gameplay menu-mode flows.
	private MenuType detectPregameAnchor()
	{
		if (matchesAt(0, 0, MAINMENU_ANCHOR))
			return MenuType.MAINMENU;
		if (matchesAt(0, 0, NEWGAME_WELCOME_PREFIX))
		{
			if (rowContains(0, NEWGAME_SPECIES_TOKEN))
				return MenuType.NEWGAME_SPECIES;
			if (rowContains(0, NEWGAME_BACKGROUND_TOKEN)
					|| rowContains(1, NEWGAME_BACKGROUND_TOKEN))
				return MenuType.NEWGAME_BACKGROUND;
			for (int r = 1; r < 5; r++)
			{
				if (rowContains(r, NEWGAME_WEAPON_ANCHOR))
					return MenuType.NEWGAME_WEAPON;
			}
		}
		// Character naming popup (_choose_name). The popup is vertically
		// centered, so the exact row varies; scan all rows.
		for (int r = 0; r < TERMINAL_ROWS; r++)
		{
			if (rowContains(r, NEWGAME_NAME_ANCHOR))
				return MenuType.NEWGAME_NAME;
		}
		return null;
	}

	@Override
	public void increaseFontSize()
	{
		if (fullView != null)
			fullView.increaseFontSize();
		if (skillsView != null)
			skillsView.increaseFontSize();
		if (itemsView != null)
			itemsView.increaseFontSize();
		for (RegionTermView region : splitRegions)
			region.increaseFontSize();
	}

	@Override
	public void decreaseFontSize()
	{
		if (fullView != null)
			fullView.decreaseFontSize();
		if (skillsView != null)
			skillsView.decreaseFontSize();
		if (itemsView != null)
			itemsView.decreaseFontSize();
		for (RegionTermView region : splitRegions)
			region.decreaseFontSize();
	}

	@Override
	public Context getContext()
	{
		return context;
	}

	@Override
	public Resources getResources()
	{
		return context.getResources();
	}
}
