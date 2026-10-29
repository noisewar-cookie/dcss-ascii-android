package com.crawlmb.recording;

/**
 * The recorded layout replay builds its panels from (msg window rows, word
 * wrap, compact HUD, turn mark), so the grid routes the way it did for the
 * recorder. Font, theme, panel placement and keyboard stay the viewer's.
 * A frame whose layout differs from the applied one isn't drawn; the views are
 * rebuilt for it and the frame is re-rendered.
 */
public final class ReplayLayout
{
	private final Runnable rebuild;
	private int[] applied;
	private boolean pending;
	private boolean held;

	public ReplayLayout(int[] initial, Runnable rebuild)
	{
		this.applied = known(initial) ? initial.clone() : null;
		this.rebuild = rebuild;
	}

	/** Layout the views should be built from; null = the viewer's prefs. */
	public int[] applied()
	{
		return applied;
	}

	/**
	 * While held (a scrub drag), a layout change only holds frames back: the
	 * rebuild replaces the controls, which cancels the drag.
	 */
	public void hold(boolean on)
	{
		held = on;
	}

	/** True if a frame with this layout can be drawn on the current views. */
	public boolean accept(int[] frame)
	{
		if (pending)
			return false;
		if (!known(frame) || (applied != null && sameViews(applied, frame)))
			return true;
		if (held)
			return false;
		applied = frame.clone();
		pending = true;
		rebuild.run();
		return false;
	}

	/** Views were rebuilt from applied(). */
	public void onRebuilt()
	{
		pending = false;
	}

	/** Files from before layout was recorded hold all zeros. */
	public static boolean known(int[] l)
	{
		if (l == null || l.length < RecFormat.LAYOUT_SIZE)
			return false;
		for (int v : l)
			if (v != 0)
				return true;
		return false;
	}

	public static boolean wordwrap(int[] l)
	{
		return l[RecFormat.LAYOUT_MSG_WRAP_COLS] > 0;
	}

	/** Native msg window rows; 0 = stock 7. */
	public static int msgRows(int[] l)
	{
		return l[RecFormat.LAYOUT_MSG_ROWS];
	}

	public static boolean compactHud(int[] l)
	{
		return l[RecFormat.LAYOUT_COMPACT_HUD] != 0;
	}

	public static boolean newturnMark(int[] l)
	{
		return l[RecFormat.LAYOUT_NEWTURN_MARK] != 0;
	}

	// Wrap width alone (a fold, a font change) doesn't change the views.
	static boolean sameViews(int[] a, int[] b)
	{
		return wordwrap(a) == wordwrap(b) && msgRows(a) == msgRows(b)
				&& compactHud(a) == compactHud(b) && newturnMark(a) == newturnMark(b);
	}
}
