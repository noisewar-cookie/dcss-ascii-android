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

	// Menu sub-classification used only when in MENU mode. Detected per-frame
	// from terminalShadow contents; controls which font_config.txt scale and
	// scrollable setting fullView gets. DEFAULT covers pregame and any menu
	// not matched by the more specific groups.
	public enum MenuType
	{
		DEFAULT, ITEMS, SPELLS, OVERVIEW, SKILLS, RELIGION, HISCORES
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
	private static final String[] ITEMS_ROW0_PREFIXES = {
		"Inventory:",
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

	private final List<RegionTermView> splitRegions = new ArrayList<>();
	private RegionTermView fullView;
	private LinearLayout splitContainer;
	private final Context context;

	private final char[][] terminalShadow = new char[TERMINAL_ROWS][TERMINAL_COLS];
	private volatile LayoutMode currentMode = LayoutMode.PREGAME;
	private volatile MenuType currentMenuType = MenuType.DEFAULT;
	private boolean gameplayEverDetected = false;

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
		// INVISIBLE (never GONE) on both sides keeps layout dimensions stable
		// across transitions, which prevents the bleedthrough seen previously.
		if (fullView != null)
			fullView.setVisibility(splitVisible ? View.INVISIBLE : View.VISIBLE);
		if (splitContainer != null)
			splitContainer.setVisibility(splitVisible ? View.VISIBLE : View.INVISIBLE);

		boolean scaleChanged = false;
		if (fullView != null && fontConfig != null && !splitVisible)
			scaleChanged = applyMenuConfig(menuType);

		if (scrollStateListener != null)
		{
			boolean menuScrollable = !splitVisible
					&& fullView != null
					&& fullView.isHorizontalScrollEnabled();
			scrollStateListener.onMenuScrollableChanged(menuScrollable);
		}

		// applyMenuConfig's setFontScaleMultiplier triggers a re-measure
		// that recreates the bitmap blank; DCSS won't redraw mid-menu
		// without input. Fire the redraw requester after the next layout
		// pass so it draws into the freshly-sized bitmap.
		if (scaleChanged && redrawRequester != null && fullView != null)
			scheduleRedrawAfterLayout();
	}

	private void scheduleRedrawAfterLayout()
	{
		final ViewTreeObserver vto = fullView.getViewTreeObserver();
		vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener()
		{
			@Override
			public void onGlobalLayout()
			{
				ViewTreeObserver o = fullView.getViewTreeObserver();
				if (o.isAlive())
					o.removeOnGlobalLayoutListener(this);
				if (redrawRequester != null)
					redrawRequester.run();
			}
		});
	}

	// Returns true if the font scale multiplier actually changed (i.e. the
	// bitmap will be recreated and needs a repaint).
	private boolean applyMenuConfig(MenuType type)
	{
		float scale;
		boolean scrollable;
		switch (type)
		{
		case ITEMS:
			scale = fontConfig.portraitItemsFontScale;
			scrollable = fontConfig.portraitItemsScrollable;
			break;
		case SPELLS:
			scale = fontConfig.portraitSpellsFontScale;
			scrollable = fontConfig.portraitSpellsScrollable;
			break;
		case OVERVIEW:
			scale = fontConfig.portraitOverviewFontScale;
			scrollable = fontConfig.portraitOverviewScrollable;
			break;
		case SKILLS:
			scale = fontConfig.portraitSkillsFontScale;
			scrollable = fontConfig.portraitSkillsScrollable;
			break;
		case RELIGION:
			scale = fontConfig.portraitReligionFontScale;
			scrollable = fontConfig.portraitReligionScrollable;
			break;
		case HISCORES:
			scale = fontConfig.portraitHiscoresFontScale;
			scrollable = fontConfig.portraitHiscoresScrollable;
			break;
		case DEFAULT:
		default:
			scale = fontConfig.portraitFullFontScale;
			scrollable = fontConfig.portraitFullScrollable;
			break;
		}
		float prevScale = fullView.getFontScaleMultiplier();
		fullView.setFontScaleMultiplier(scale);
		fullView.setHorizontalScrollEnabled(scrollable);
		return prevScale != scale;
	}

	@Override
	public boolean onGameStart()
	{
		currentMode = LayoutMode.PREGAME;
		currentMenuType = MenuType.DEFAULT;
		gameplayEverDetected = false;
		for (int i = 0; i < TERMINAL_ROWS; i++)
			for (int j = 0; j < TERMINAL_COLS; j++)
				terminalShadow[i][j] = 0;

		if (fullView != null)
			fullView.post(() -> applyMode(LayoutMode.PREGAME, MenuType.DEFAULT));

		boolean allOk = true;
		if (fullView != null && !fullView.onGameStart())
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
		for (RegionTermView region : splitRegions)
			region.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);
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
		MenuType detectedType = (detected == LayoutMode.MENU)
				? detectMenuType() : MenuType.DEFAULT;

		if (detected != currentMode || detectedType != currentMenuType)
		{
			currentMode = detected;
			currentMenuType = detectedType;
			if (detected == LayoutMode.GAMEPLAY)
				gameplayEverDetected = true;
			final LayoutMode targetMode = detected;
			final MenuType targetType = detectedType;
			if (fullView != null)
				fullView.post(() -> applyMode(targetMode, targetType));
		}

		if (fullView != null)
			fullView.postInvalidate();
		for (RegionTermView region : splitRegions)
			region.postInvalidate();
	}

	@Override
	public void increaseFontSize()
	{
		if (fullView != null)
			fullView.increaseFontSize();
		for (RegionTermView region : splitRegions)
			region.increaseFontSize();
	}

	@Override
	public void decreaseFontSize()
	{
		if (fullView != null)
			fullView.decreaseFontSize();
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
