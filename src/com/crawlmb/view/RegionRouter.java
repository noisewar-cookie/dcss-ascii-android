package com.crawlmb.view;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import com.crawlmb.FontConfig;

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
		RELIGION, HISCORES, TRAVEL, LEVELMAP, VFEATURES,
		// Newgame sub-states. Each picks a different vertically-stacked
		// panel container (one panel per category) and per-panel scales
		// from font_config.txt. Detected via row-0 "Welcome" + the
		// "species." / "background." disambiguator from newgame.cc.
		NEWGAME_SPECIES, NEWGAME_BACKGROUND
	}

	public interface ScrollStateListener
	{
		// Called on UI thread when fullView's effective scrollable state
		// changes (menu type changed, fullView visibility changed, etc.).
		void onMenuScrollableChanged(boolean scrollable);
	}

	// 0-based region boundaries for the 80x24 inline layout
	public static final int HUD_START_ROW = 0;
	public static final int HUD_END_ROW = 17;
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

	private static final int TERMINAL_ROWS = 24;
	public static final int TERMINAL_COLS = 80;

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
		"Inscribe which",
		"Welcome to ",
		"Items not yet recognised",
		"Recognised items",
		"You recognise all items"
	};

	// Catches:
	//   "Your spells (cast)" / "Your spells (describe)" — spell list (I), cast (z)
	//     via list_spells() in spl-cast.cc
	//   "Spells (Memorise)" / "Spells (Cast)" / "Spells (Imbue)" /
	//   "Spells (Describe)" / "Spells (Hide)" / "Spells (Show)" — memorise (M),
	//     Vehumet's Divine Exegesis, Spellspark Servitor imbue
	//     via SpellLibraryMenu::calc_title in spl-book.cc
	//   "Ability - do what" / "Ability - describe what" — abilities (a)
	//   "Innate Abilities, Weirdness" — mutations (A)
	private static final String[] SPELLS_ROW0_PREFIXES = {
		"Your spells",
		"Spells (",
		"Ability - do what",
		"Ability - describe what",
		"Innate Abilities, Weirdness"
	};

	// Visible Monsters/Items/Features title from _full_describe_menu in
	// directn.cc — accessed via Ctrl+X (full_describe_view).
	private static final String VFEATURES_PREFIX = "Visible ";

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
	private static final String LOADING_ANCHOR = "Launching game";

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

	// Background: Warrior 5 items → rows 2..7. Zealot 3 items at y=6 → rows 8..11.
	// Adventurer 4 items → rows 2..6. Warrior-mage 4 items at y=5 → rows 7..11.
	// Mage 10 items → rows 2..12.
	public static final int NEWGAME_BG_WARRIOR_ROW0    = 2,  NEWGAME_BG_WARRIOR_ROW1    = 8;
	public static final int NEWGAME_BG_ZEALOT_ROW0     = 8,  NEWGAME_BG_ZEALOT_ROW1     = 12;
	public static final int NEWGAME_BG_ADVENTURER_ROW0 = 2,  NEWGAME_BG_ADVENTURER_ROW1 = 7;
	public static final int NEWGAME_BG_WARMAGE_ROW0    = 7,  NEWGAME_BG_WARMAGE_ROW1    = 12;
	public static final int NEWGAME_BG_MAGE_ROW0       = 2,  NEWGAME_BG_MAGE_ROW1       = 13;

	private final List<RegionTermView> splitRegions = new ArrayList<>();
	private RegionTermView fullView;
	private RegionTermView skillsView;
	private LinearLayout splitContainer;
	// Newgame panel containers. Each holds a vertical stack of
	// RegionTermViews — those panels are also added to splitRegions so
	// drawPoint forwards into their fixed terminal rectangles. The
	// container visibility gates whether the user sees them.
	private LinearLayout newgameSpeciesContainer;
	private LinearLayout newgameBackgroundContainer;
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
	private final Context context;

	private final char[][] terminalShadow = new char[TERMINAL_ROWS][TERMINAL_COLS];
	private volatile LayoutMode currentMode = LayoutMode.PREGAME;
	private volatile MenuType currentMenuType = MenuType.DEFAULT;

	public MenuType getCurrentMenuType()
	{
		return currentMenuType;
	}
	private boolean gameplayEverDetected = false;

	// Set by preStormHint when the next storm transitions GAMEPLAY -> a
	// non-gameplay frame. Read by drawPoint to skip forwarding to
	// splitRegions for the duration of the storm — without this, the storm
	// writes menu chars at terminal coordinates into mapView/hudView/msgView
	// and a UI-thread vsync mid-storm or post-storm-pre-applyMode draws
	// those chars in the wrong panel ("on top of the game map"). Cleared
	// in postInvalidate after the storm so the next frame starts clean.
	private volatile boolean skipSplitRegionsThisStorm = false;

	// Anchor for the single-column fold of the skills menu. Recomputed on
	// each transition INTO MenuType.SKILLS by scanning terminalShadow for
	// the two occurrences of "     Skill" on the same row. skillsLeftCol
	// is the start of col 0's title; skillsRightCol is the start of col 1's
	// title. The cut threshold (which side of the fold a char belongs to)
	// is skillsRightCol; the shift applied to col 1 chars to align them
	// under col 0 is (skillsRightCol - skillsLeftCol), which equals
	// MIN_COLS/2 = 39 in practice. The two values must come from the same
	// detection so the col-shift stays consistent with the cut. -1 = anchor
	// unknown (pre-detection or detection failed) → drawPoint forwards to
	// skillsView 1:1 as a fallback.
	private volatile int skillsHeaderRow = -1;
	private volatile int skillsLeftCol = -1;
	private volatile int skillsRightCol = -1;

	private FontConfig fontConfig;
	private ScrollStateListener scrollStateListener;
	private Runnable redrawRequester;

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

	public RegionTermView getSkillsView()
	{
		return skillsView;
	}

	public void setSplitContainer(LinearLayout container)
	{
		this.splitContainer = container;
	}

	public void setNewgameSpeciesContainer(LinearLayout container)
	{
		this.newgameSpeciesContainer = container;
	}

	public void setNewgameBackgroundContainer(LinearLayout container)
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
		int descStart = NEWGAME_SUB_ROW0;
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

	// Background row 2 has only the col-0 group titles — Warrior, Adventurer,
	// Mage. Zealot (col 0) and Warrior-mage (col 1) sit further down their
	// columns and share the col-0/col-1 x ranges respectively.
	private void applyNewgameBackgroundBounds()
	{
		if (ngbWarriorView == null || ngbZealotView == null
				|| ngbAdventurerView == null || ngbWarMageView == null
				|| ngbMageView == null)
			return;
		int cWarrior = findColInRow(2, "Warrior");
		int cAdv     = findColInRow(2, "Adventurer");
		int cMage    = findColInRow(2, "Mage");
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

	private void applyMode(LayoutMode mode, MenuType menuType)
	{
		boolean splitVisible = (mode == LayoutMode.GAMEPLAY);
		boolean skillsVisible = !splitVisible
				&& menuType == MenuType.SKILLS
				&& skillsView != null;
		boolean newgameSpeciesVisible = !splitVisible
				&& menuType == MenuType.NEWGAME_SPECIES
				&& newgameSpeciesContainer != null;
		boolean newgameBackgroundVisible = !splitVisible
				&& menuType == MenuType.NEWGAME_BACKGROUND
				&& newgameBackgroundContainer != null;
		boolean fullVisible = !splitVisible && !skillsVisible
				&& !newgameSpeciesVisible && !newgameBackgroundVisible;

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
		if (splitContainer != null)
			splitContainer.setVisibility(splitVisible ? View.VISIBLE : View.INVISIBLE);
		if (newgameSpeciesContainer != null)
			newgameSpeciesContainer.setVisibility(
					newgameSpeciesVisible ? View.VISIBLE : View.INVISIBLE);
		if (newgameBackgroundContainer != null)
			newgameBackgroundContainer.setVisibility(
					newgameBackgroundVisible ? View.VISIBLE : View.INVISIBLE);

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

		// Apply scale/scroll config to whichever menu view is now active.
		// fullView and skillsView are mutually exclusive in non-gameplay
		// modes; the skills menu owns skillsView, everything else owns
		// fullView.
		boolean scaleChanged = false;
		RegionTermView activeMenu = null;
		if (fontConfig != null)
		{
			if (skillsVisible)
			{
				activeMenu = skillsView;
				scaleChanged = applySkillsConfig();
			}
			else if (fullVisible && fullView != null)
			{
				activeMenu = fullView;
				scaleChanged = applyFullConfig(menuType);
			}
		}

		if (scrollStateListener != null)
		{
			boolean menuScrollable = activeMenu != null
					&& activeMenu.isScrollEnabled();
			scrollStateListener.onMenuScrollableChanged(menuScrollable);
		}

		// Decide whether DCSS needs to repaint into the now-active surface.
		//   - splitContainer (gameplay): postInvalidate just cleared
		//     splitRegions on this menu->gameplay transition, so DCSS must
		//     repaint into the cleared bitmaps before the user sees them.
		//   - menu views: applyMenuConfig's setFontScaleMultiplier
		//     triggers a re-measure that recreates the bitmap blank, and a
		//     view-swap into skillsView always lands on a stale bitmap
		//     (drawPoint only forwards to skillsView while currentMenuType
		//     == SKILLS, so the previous storm skipped it).
		// applyMode is only called on state transitions, so this scheduler
		// runs at most once per transition.
		View redrawTarget = null;
		if (splitVisible && splitContainer != null)
			redrawTarget = splitContainer;
		else if (newgameSpeciesVisible && newgameSpeciesContainer != null)
			redrawTarget = newgameSpeciesContainer;
		else if (newgameBackgroundVisible && newgameBackgroundContainer != null)
			redrawTarget = newgameBackgroundContainer;
		else if (activeMenu != null
				&& (scaleChanged || activeMenu == skillsView))
			redrawTarget = activeMenu;

		if (redrawRequester != null && redrawTarget != null)
			scheduleRedrawAfterLayout(redrawTarget);
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
				if (redrawRequester != null)
					redrawRequester.run();
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
			scale = fontConfig.portraitItemsFontScale;
			scrollable = fontConfig.portraitItemsScrollable;
			vscrollable = fontConfig.portraitItemsVScrollable;
			break;
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
			vscrollable = false;
			break;
		case HISCORES:
			scale = fontConfig.portraitHiscoresFontScale;
			scrollable = fontConfig.portraitHiscoresScrollable;
			vscrollable = false;
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
		case VFEATURES:
			scale = fontConfig.portraitVfeaturesFontScale;
			scrollable = fontConfig.portraitVfeaturesScrollable;
			vscrollable = fontConfig.portraitVfeaturesVScrollable;
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
		float prevScale = fullView.getFontScaleMultiplier();
		fullView.setFontScaleMultiplier(scale);
		fullView.setHorizontalScrollEnabled(scrollable);
		// Drag-scroll only takes effect when the rendered bitmap exceeds
		// the screen in that axis — at smaller font scales this is a no-op.
		fullView.setVerticalScrollEnabled(vscrollable);
		return prevScale != scale;
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

	@Override
	public boolean onGameStart()
	{
		currentMode = LayoutMode.PREGAME;
		currentMenuType = MenuType.DEFAULT;
		gameplayEverDetected = false;
		skillsHeaderRow = -1;
		skillsLeftCol = -1;
		skillsRightCol = -1;
		for (int i = 0; i < TERMINAL_ROWS; i++)
			for (int j = 0; j < TERMINAL_COLS; j++)
				terminalShadow[i][j] = 0;

		if (fullView != null)
			fullView.post(() -> applyMode(LayoutMode.PREGAME, MenuType.PREGAME));

		boolean allOk = true;
		if (fullView != null && !fullView.onGameStart())
			allOk = false;
		if (skillsView != null && !skillsView.onGameStart())
			allOk = false;
		for (RegionTermView region : splitRegions)
		{
			if (!region.onGameStart())
				allOk = false;
		}
		return allOk;
	}

	@Override
	public void drawPoint(int r, int c, char ch, int fcolor, int bcolor, boolean extendedErase)
	{
		if (r >= 0 && r < TERMINAL_ROWS && c >= 0 && c < TERMINAL_COLS)
			terminalShadow[r][c] = ch;

		if (fullView != null)
			fullView.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);
		// Skip splitRegions during a gameplay->menu transition storm: the
		// new frame's chars belong to a menu, and forwarding them at
		// terminal coordinates into mapView/hudView/msgView would tear the
		// next visible draw before applyMode hides splitContainer.
		if (!skipSplitRegionsThisStorm)
		{
			for (RegionTermView region : splitRegions)
				region.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);
		}

		if (skillsView != null && currentMenuType == MenuType.SKILLS)
			forwardToSkillsView(r, c, ch, fcolor, bcolor, extendedErase);
	}

	// Set the skip flag when this storm transitions GAMEPLAY -> non-gameplay.
	// That's the direction where the storm writes wrong-panel content into
	// CURRENTLY-VISIBLE splitRegions, which mid-storm or post-storm-pre-
	// applyMode draws expose. The reverse direction (menu -> gameplay) has
	// stale content too, but splitContainer is still INVISIBLE during the
	// storm, so we let it write and clear it in postInvalidate. Runs on
	// the game thread under display_lock (NativeWrapper.preStormHint),
	// strictly before the storm's drawPoint calls.
	@Override
	public void preStormHint(boolean isGameplay)
	{
		skipSplitRegionsThisStorm =
				(currentMode == LayoutMode.GAMEPLAY) && !isGameplay;
	}

	// Remap the 24x80 two-column skills layout into a 41x80 single-column
	// view. Uses the cached anchor (skillsHeaderRow, skillsLeftCol,
	// skillsRightCol). The cut threshold for which column a char belongs to
	// is skillsRightCol; the col-shift applied to col-1 chars is
	// (skillsRightCol - skillsLeftCol). These differ — the right column's
	// title is at col 41 but its content is offset 39 from the left column's
	// content, because col_split=MIN_COLS/2=39 and both columns share the
	// same x++ indent before placement.
	// Rules:
	//   r < headerRow                                  → pass through
	//   r == headerRow, c <  rightCol                  → pass through (left header)
	//   r == headerRow, c >= rightCol                  → drop (redundant right header)
	//   headerRow < r <= headerRow + SKILL_FOLD_ROWS
	//     c <  rightCol                                → pass through (col 0 skill)
	//     c >= rightCol                                → emit at (r+fold, c-(rightCol-leftCol))
	//   r > headerRow + SKILL_FOLD_ROWS                → emit at (r+fold, c)
	//                                                    (help/button rows span full width)
	// If the anchor isn't set yet, forward 1:1 — the destination view will
	// be repainted once the anchor resolves on the next frame.
	private void forwardToSkillsView(int r, int c, char ch, int fcolor,
			int bcolor, boolean extendedErase)
	{
		int headerRow = skillsHeaderRow;
		int leftCol = skillsLeftCol;
		int rightCol = skillsRightCol;
		if (headerRow < 0 || leftCol < 0 || rightCol < 0)
		{
			skillsView.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);
			return;
		}
		int fold = SKILL_FOLD_ROWS;
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
			if (c < rightCol)
				skillsView.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);
			else
				skillsView.drawPoint(r + fold, c - colShift, ch, fcolor,
						bcolor, extendedErase);
		}
		else
		{
			skillsView.drawPoint(r + fold, c, ch, fcolor, bcolor, extendedErase);
		}
	}

	// Scan rows 0..3 for the literal SKILL_HEADER text. If two matches
	// land on the same row, store both column positions: skillsLeftCol is
	// the start of the first occurrence (col-0 title), skillsRightCol is
	// the start of the second (col-1 title). The col-shift for the fold
	// is the difference between them. Otherwise leave the anchor unset
	// (-1, -1, -1) and fall back to 1:1 forwarding.
	private void recomputeSkillsAnchor()
	{
		skillsHeaderRow = -1;
		skillsLeftCol = -1;
		skillsRightCol = -1;
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
				{
					firstCol = c;
				}
				else
				{
					skillsHeaderRow = r;
					skillsLeftCol = firstCol;
					skillsRightCol = c;
					return;
				}
			}
		}
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
		// Newgame species/background picker. After the first gameplay frame,
		// gameplayEverDetected sticks at true, so a return to the main menu
		// (e.g. Ctrl+S) classifies as MENU instead of PREGAME — meaning the
		// newgame screen reached from that main menu lands here, not in
		// detectPregameType. Check the same anchor so the screen still routes
		// to NEWGAME_SPECIES/NEWGAME_BACKGROUND on the second-and-later trips
		// through New Game.
		if (matchesAt(0, 0, NEWGAME_WELCOME_PREFIX))
		{
			if (rowContains(0, NEWGAME_SPECIES_TOKEN))
				return MenuType.NEWGAME_SPECIES;
			if (rowContains(0, NEWGAME_BACKGROUND_TOKEN))
				return MenuType.NEWGAME_BACKGROUND;
		}

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
		// Visible Monsters/Items/Features (Ctrl+X). Title row 0 begins with
		// "Visible " (modulo possible indent space) — must be checked before
		// the generic row scans below to avoid being shadowed.
		if (rowStartsWith(0, VFEATURES_PREFIX))
			return MenuType.VFEATURES;

		// Stash search results (Ctrl+F). Row 0 contains "[toggle:" as part of
		// the StashSearchMenu calc_title format. Cheaper than the rowContains
		// scans below and unique to that menu.
		if (rowContains(0, TRAVEL_TOGGLE_MARKER))
			return MenuType.TRAVEL;

		// Level map (Shift+X). _draw_title in viewmap.cc renders
		// "(Press ? for help)" at the right of row 0.
		if (rowContains(0, LEVELMAP_HELP_MARKER))
			return MenuType.LEVELMAP;

		// Custom-rendered screens — scan distinctive markers across rows.
		// Hiscores: title widget renders "<game name>: High Scores" near top.
		for (int r = 0; r <= 3; r++)
		{
			if (rowContains(r, "High Scores"))
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
		// Skill training (m): SkillMenu column header text. "Apt " (with
		// trailing space) matches the literal aptitude column header and
		// is very unlikely to appear elsewhere with that exact byte
		// sequence. Constrained to top rows where the header lives.
		for (int r = 0; r <= 3; r++)
		{
			if (rowContains(r, "Apt "))
				return MenuType.SKILLS;
		}
		return MenuType.DEFAULT;
	}

	@Override
	public void postInvalidate()
	{
		LayoutMode detected = detectMode();
		MenuType detectedType;
		if (detected == LayoutMode.MENU)
			detectedType = detectMenuType();
		else if (detected == LayoutMode.PREGAME)
			detectedType = detectPregameType();
		else
			detectedType = MenuType.DEFAULT;

		if (detected != currentMode || detectedType != currentMenuType)
		{
			// menu -> gameplay: splitRegions still hold whatever the menu
			// storms wrote into them (drawPoint forwards unconditionally
			// when the skip flag isn't set, and during menus splitContainer
			// is INVISIBLE so we let those writes happen). Now that
			// splitContainer is about to become visible, clear the stale
			// menu content synchronously on the game thread; applyMode's
			// scheduleRedrawAfterLayout will trigger DCSS to repaint
			// gameplay into the cleared bitmaps. The reverse direction
			// (gameplay -> menu) is handled by skipSplitRegionsThisStorm,
			// set via preStormHint before this storm even started.
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
			// Recompute the fold anchor as we enter the skills menu so the
			// next frame's drawPoints can remap. Done here (not inside the
			// applyMode post) because applyMode runs on the UI thread later;
			// drawPoints arriving in between need the anchor immediately.
			if (detectedType == MenuType.SKILLS)
				recomputeSkillsAnchor();
			else
			{
				skillsHeaderRow = -1;
				skillsLeftCol = -1;
				skillsRightCol = -1;
			}
			final LayoutMode targetMode = detected;
			final MenuType targetType = detectedType;
			if (fullView != null)
				fullView.post(() -> applyMode(targetMode, targetType));
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

		if (fullView != null)
			fullView.postInvalidate();
		if (skillsView != null)
			skillsView.postInvalidate();
		for (RegionTermView region : splitRegions)
			region.postInvalidate();

		// End of storm: clear the per-storm flag so the next storm starts
		// from a known state. preStormHint will set it again at the start
		// of the next storm if needed.
		skipSplitRegionsThisStorm = false;
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
		if (matchesAt(0, 0, NEWGAME_WELCOME_PREFIX))
		{
			if (rowContains(0, NEWGAME_SPECIES_TOKEN))
				return MenuType.NEWGAME_SPECIES;
			if (rowContains(0, NEWGAME_BACKGROUND_TOKEN))
				return MenuType.NEWGAME_BACKGROUND;
		}
		return MenuType.MAINMENU;
	}

	@Override
	public void increaseFontSize()
	{
		if (fullView != null)
			fullView.increaseFontSize();
		if (skillsView != null)
			skillsView.increaseFontSize();
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
