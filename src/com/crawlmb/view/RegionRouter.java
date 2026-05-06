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
		DEFAULT, PREGAME, MAINMENU, ITEMS, SPELLS, OVERVIEW, SKILLS, RELIGION, HISCORES
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
	private static final int TERMINAL_COLS = 80;

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
	// portraitFullFontScale instead of portraitItemsFontScale. Non-paged
	// inventory still uses the literal "Inventory:" title.
	private static final String[] ITEMS_ROW0_PREFIXES = {
		"Inventory:",
		"Gear:",
		"Potions:",
		"Scrolls:",
		"Evocable Items:",
		"Drop what?",
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
		"Welcome to ",
		"Items known"
	};

	private static final String[] SPELLS_ROW0_PREFIXES = {
		"Your spells",
		"Ability - do what",
		"Ability - describe what",
		"Innate Abilities, Weirdness"
	};

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

	private final List<RegionTermView> splitRegions = new ArrayList<>();
	private RegionTermView fullView;
	private RegionTermView skillsView;
	private LinearLayout splitContainer;
	private final Context context;

	private final char[][] terminalShadow = new char[TERMINAL_ROWS][TERMINAL_COLS];
	private volatile LayoutMode currentMode = LayoutMode.PREGAME;
	private volatile MenuType currentMenuType = MenuType.DEFAULT;
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
		boolean fullVisible = !splitVisible && !skillsVisible;

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
		boolean vscrollable = false;
		switch (type)
		{
		case PREGAME:
			scale = fontConfig.portraitPregameFontScale;
			scrollable = fontConfig.portraitPregameScrollable;
			break;
		case MAINMENU:
			scale = fontConfig.portraitMainmenuFontScale;
			scrollable = fontConfig.portraitMainmenuScrollable;
			break;
		case ITEMS:
			scale = fontConfig.portraitItemsFontScale;
			scrollable = fontConfig.portraitItemsScrollable;
			vscrollable = fontConfig.portraitItemsVScrollable;
			break;
		case SPELLS:
			scale = fontConfig.portraitSpellsFontScale;
			scrollable = fontConfig.portraitSpellsScrollable;
			break;
		case OVERVIEW:
			scale = fontConfig.portraitOverviewFontScale;
			scrollable = fontConfig.portraitOverviewScrollable;
			break;
		case RELIGION:
			scale = fontConfig.portraitReligionFontScale;
			scrollable = fontConfig.portraitReligionScrollable;
			break;
		case HISCORES:
			scale = fontConfig.portraitHiscoresFontScale;
			scrollable = fontConfig.portraitHiscoresScrollable;
			break;
		case SKILLS:
			// Should never happen — SKILLS uses skillsView. Fall through
			// to default to be defensive.
		case DEFAULT:
		default:
			scale = fontConfig.portraitFullFontScale;
			scrollable = fontConfig.portraitFullScrollable;
			break;
		}
		float prevScale = fullView.getFontScaleMultiplier();
		fullView.setFontScaleMultiplier(scale);
		fullView.setHorizontalScrollEnabled(scrollable);
		// Only ITEMS opts into vertical scroll today; every other MenuType
		// leaves vscrollable=false above. Drag-scroll only takes effect when
		// the rendered bitmap is taller than the screen — at smaller font
		// scales this is a no-op.
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
		// Row 0 prefix matches: cheapest, covers all set_title-style menus.
		for (String p : ITEMS_ROW0_PREFIXES)
		{
			if (matchesAt(0, 0, p))
				return MenuType.ITEMS;
		}
		for (String p : SPELLS_ROW0_PREFIXES)
		{
			if (matchesAt(0, 0, p))
				return MenuType.SPELLS;
		}

		// Custom-rendered screens — scan distinctive markers across rows.
		// Hiscores: title widget renders "<game name>: High Scores" near top.
		for (int r = 0; r <= 3; r++)
		{
			if (rowContains(r, "High Scores"))
				return MenuType.HISCORES;
		}
		// Overview (%): _status_mut_rune_list emits "@:" at the start of a
		// line — distinctive vs. the gameplay HUD which has no "@:" label.
		// Allow a small left-margin for any popup framing.
		for (int r = 1; r <= 18; r++)
		{
			for (int c = 0; c <= 4; c++)
			{
				if (matchesAt(r, c, "@:"))
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
	// not there, the screen has been overwritten by DCSS → MAINMENU.
	private MenuType detectPregameType()
	{
		return matchesAt(0, 0, LOADING_ANCHOR)
				? MenuType.PREGAME : MenuType.MAINMENU;
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
