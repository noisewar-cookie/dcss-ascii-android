package com.crawlmb.view;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

public class RegionRouter implements TerminalRenderer
{
	public enum LayoutMode { PREGAME, GAMEPLAY, MENU }

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

	private final List<RegionTermView> splitRegions = new ArrayList<>();
	private RegionTermView fullView;
	private LinearLayout splitContainer;
	private final Context context;

	private final char[][] terminalShadow = new char[TERMINAL_ROWS][TERMINAL_COLS];
	private volatile LayoutMode currentMode = LayoutMode.PREGAME;
	private boolean gameplayEverDetected = false;

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

	private void applyMode(LayoutMode mode)
	{
		boolean splitVisible = (mode == LayoutMode.GAMEPLAY);
		// INVISIBLE (never GONE) on both sides keeps layout dimensions stable
		// across transitions, which prevents the bleedthrough seen previously.
		if (fullView != null)
			fullView.setVisibility(splitVisible ? View.INVISIBLE : View.VISIBLE);
		if (splitContainer != null)
			splitContainer.setVisibility(splitVisible ? View.VISIBLE : View.INVISIBLE);
	}

	@Override
	public boolean onGameStart()
	{
		currentMode = LayoutMode.PREGAME;
		gameplayEverDetected = false;
		for (int i = 0; i < TERMINAL_ROWS; i++)
			for (int j = 0; j < TERMINAL_COLS; j++)
				terminalShadow[i][j] = 0;

		if (fullView != null)
			fullView.post(() -> applyMode(LayoutMode.PREGAME));

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

	@Override
	public void postInvalidate()
	{
		LayoutMode detected = detectMode();
		if (detected != currentMode)
		{
			currentMode = detected;
			if (detected == LayoutMode.GAMEPLAY)
				gameplayEverDetected = true;
			final LayoutMode target = detected;
			if (fullView != null)
				fullView.post(() -> applyMode(target));
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
