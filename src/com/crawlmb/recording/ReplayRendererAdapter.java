package com.crawlmb.recording;

import com.crawlmb.NativeWrapper;
import com.crawlmb.view.TerminalRenderer;

import java.util.Arrays;
import java.util.Objects;

/**
 * Feeds replayed ScreenStates into the live renderer (RegionRouter) through the
 * same TerminalRenderer calls NativeWrapper makes, so screen classification and
 * region routing behave exactly as in a live game. Side channels are only
 * re-sent when they change. The views themselves are built from the recorded
 * layout (ReplayLayout).
 */
public final class ReplayRendererAdapter implements ReplayController.RenderTarget
{
	public interface PositionListener
	{
		void onPositionChanged(int pos, boolean playing);
	}

	private final TerminalRenderer renderer;
	private final PositionListener listener;
	private ScreenState last;

	public ReplayRendererAdapter(TerminalRenderer renderer, PositionListener listener)
	{
		this.renderer = renderer;
		this.listener = listener;
	}

	@Override
	public void render(ScreenState s, int pos)
	{
		synchronized (NativeWrapper.display_lock)
		{
			ScreenState p = last;
			if (p == null || p.msgHistoryMode != s.msgHistoryMode)
				renderer.setMessageHistoryMode(s.msgHistoryMode);
			if (s.msgHistChars != null && (p == null || p.msgHistChars != s.msgHistChars))
				renderer.setMessageHistory(s.msgHistChars, s.msgHistFg, s.msgHistBg);
			if (p == null || p.charLogMode != s.charLogMode)
				renderer.setCharacterLogMode(s.charLogMode);
			if (p == null || !Objects.equals(p.hudTitle, s.hudTitle)
					|| !Arrays.equals(p.hudTitleColours, s.hudTitleColours))
				renderer.updateHudTitle(nz(s.hudTitle), nz(s.hudTitleColours));
			if (p == null || !Objects.equals(p.hudVitals, s.hudVitals)
					|| !Arrays.equals(p.hudVitalsColours, s.hudVitalsColours))
				renderer.updateHudVitals(nz(s.hudVitals), nz(s.hudVitalsColours));
			if (p == null || !Arrays.equals(p.hudHp, s.hudHp) || !Arrays.equals(p.hudMp, s.hudMp))
				renderer.updateHudBars(nz(s.hudHp), nz(s.hudMp));
			if (p == null || !Objects.equals(p.statusLights, s.statusLights)
					|| !Arrays.equals(p.statusColours, s.statusColours))
				renderer.updateStatusLights(nz(s.statusLights), nz(s.statusColours));
			if (s.mapAnchorCol >= 0 && (p == null || p.mapAnchorCol != s.mapAnchorCol
					|| p.mapAnchorRow != s.mapAnchorRow))
				renderer.setMapAnchor(s.mapAnchorCol, s.mapAnchorRow);
			// Renderer diffs against its own shadow; hand it copies so cached
			// decoded frames are never mutated.
			renderer.onFrame(s.chars.clone(), s.fg.clone(), s.bg.clone());
			last = s;
		}
	}

	/** Re-send every side channel on the next render (fresh views/bitmaps). */
	public void reset()
	{
		synchronized (NativeWrapper.display_lock)
		{
			last = null;
		}
	}

	@Override
	public void onPositionChanged(int pos, boolean playing)
	{
		if (listener != null)
			listener.onPositionChanged(pos, playing);
	}

	private static String nz(String s) { return s == null ? "" : s; }
	private static int[] nz(int[] a) { return a == null ? new int[0] : a; }
}
