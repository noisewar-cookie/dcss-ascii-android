package com.crawlmb.recording;

import java.util.Arrays;

/**
 * Everything the renderer needs to paint one moment: the terminal grid plus
 * the side channels that are not in the grid (compact HUD, status lights,
 * message-history popup, char-log mode, map anchor) and the layout the game
 * was rendered with. Mirrors the NativeWrapper -> TerminalRenderer calls.
 */
public final class ScreenState
{
	public final int rows;
	public final int cols;
	public final char[] chars;
	public final int[] fg; // ARGB
	public final int[] bg; // ARGB

	public String hudTitle;
	public int[] hudTitleColours;
	public String hudVitals;
	public int[] hudVitalsColours;
	public int[] hudHp;
	public int[] hudMp;
	public String statusLights;
	public int[] statusColours;
	public boolean msgHistoryMode;
	public char[] msgHistChars;
	public int[] msgHistFg;
	public int[] msgHistBg;
	public boolean charLogMode;
	public int mapAnchorCol = -1;
	public int mapAnchorRow = -1;
	public int[] layout = new int[RecFormat.LAYOUT_SIZE];

	public ScreenState(int rows, int cols)
	{
		this.rows = rows;
		this.cols = cols;
		chars = new char[rows * cols];
		fg = new int[rows * cols];
		bg = new int[rows * cols];
	}

	public ScreenState copy()
	{
		ScreenState s = new ScreenState(rows, cols);
		System.arraycopy(chars, 0, s.chars, 0, chars.length);
		System.arraycopy(fg, 0, s.fg, 0, fg.length);
		System.arraycopy(bg, 0, s.bg, 0, bg.length);
		s.copySideChannelsFrom(this);
		return s;
	}

	public void copySideChannelsFrom(ScreenState o)
	{
		hudTitle = o.hudTitle;
		hudTitleColours = clone(o.hudTitleColours);
		hudVitals = o.hudVitals;
		hudVitalsColours = clone(o.hudVitalsColours);
		hudHp = clone(o.hudHp);
		hudMp = clone(o.hudMp);
		statusLights = o.statusLights;
		statusColours = clone(o.statusColours);
		msgHistoryMode = o.msgHistoryMode;
		// Large and immutable once delivered: share, don't clone.
		msgHistChars = o.msgHistChars;
		msgHistFg = o.msgHistFg;
		msgHistBg = o.msgHistBg;
		charLogMode = o.charLogMode;
		mapAnchorCol = o.mapAnchorCol;
		mapAnchorRow = o.mapAnchorRow;
		layout = clone(o.layout);
	}

	public boolean gridEquals(ScreenState o)
	{
		return Arrays.equals(chars, o.chars) && Arrays.equals(fg, o.fg)
				&& Arrays.equals(bg, o.bg);
	}

	public boolean sideChannelsEqual(ScreenState o)
	{
		return eq(hudTitle, o.hudTitle) && Arrays.equals(hudTitleColours, o.hudTitleColours)
				&& eq(hudVitals, o.hudVitals) && Arrays.equals(hudVitalsColours, o.hudVitalsColours)
				&& Arrays.equals(hudHp, o.hudHp) && Arrays.equals(hudMp, o.hudMp)
				&& eq(statusLights, o.statusLights) && Arrays.equals(statusColours, o.statusColours)
				&& msgHistoryMode == o.msgHistoryMode
				&& Arrays.equals(msgHistChars, o.msgHistChars)
				&& Arrays.equals(msgHistFg, o.msgHistFg) && Arrays.equals(msgHistBg, o.msgHistBg)
				&& charLogMode == o.charLogMode && mapAnchorCol == o.mapAnchorCol
				&& mapAnchorRow == o.mapAnchorRow && Arrays.equals(layout, o.layout);
	}

	private static boolean eq(Object a, Object b) { return a == null ? b == null : a.equals(b); }
	private static int[] clone(int[] a) { return a == null ? null : a.clone(); }
}
