package com.crawlmb.recording;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class ReplayLayoutTest
{
	// RecFormat.LAYOUT_* order: wrap cols, msg rows, prose, skills, compact, mark, mp argb.
	static final int[] STOCK = { 0, 0, 0, 0, 0, 1, 0xFF305FE8 };
	static final int[] WRAP = { 40, 15, 38, 30, 0, 1, 0xFF305FE8 };
	static final int[] COMPACT = { 40, 15, 38, 30, 1, 0, 0xFF305FE8 };

	static final class Counter implements Runnable
	{
		int n;
		@Override public void run() { n++; }
	}

	@Test
	public void unknownLayouts()
	{
		assertFalse(ReplayLayout.known(null));
		assertFalse(ReplayLayout.known(new int[3]));
		assertFalse(ReplayLayout.known(new int[RecFormat.LAYOUT_SIZE]));
		assertTrue(ReplayLayout.known(STOCK));
		assertNull(new ReplayLayout(new int[RecFormat.LAYOUT_SIZE], new Counter()).applied());
	}

	@Test
	public void fieldsDecode()
	{
		assertFalse(ReplayLayout.wordwrap(STOCK));
		assertEquals(0, ReplayLayout.msgRows(STOCK));
		assertFalse(ReplayLayout.compactHud(STOCK));
		assertTrue(ReplayLayout.newturnMark(STOCK));
		assertTrue(ReplayLayout.wordwrap(COMPACT));
		assertEquals(15, ReplayLayout.msgRows(COMPACT));
		assertTrue(ReplayLayout.compactHud(COMPACT));
		assertFalse(ReplayLayout.newturnMark(COMPACT));
	}

	@Test
	public void sameViewsDrawWithoutRebuild()
	{
		Counter c = new Counter();
		ReplayLayout l = new ReplayLayout(WRAP, c);
		assertTrue(l.accept(WRAP));
		// A fold changes only the wrap width: same views.
		int[] narrower = WRAP.clone();
		narrower[RecFormat.LAYOUT_MSG_WRAP_COLS] = 25;
		assertTrue(l.accept(narrower));
		// Old frames / unknown layouts keep whatever is applied.
		assertTrue(l.accept(new int[RecFormat.LAYOUT_SIZE]));
		assertEquals(0, c.n);
	}

	@Test
	public void changeRebuildsOnceAndHoldsFramesUntilRebuilt()
	{
		Counter c = new Counter();
		ReplayLayout l = new ReplayLayout(WRAP, c);
		assertFalse(l.accept(COMPACT));
		assertEquals(1, c.n);
		assertArrayEquals(COMPACT, l.applied());
		// Playback keeps ticking while the rebuild is posted: nothing drawn,
		// no second request, whatever layout the frames carry.
		assertFalse(l.accept(COMPACT));
		assertFalse(l.accept(WRAP));
		assertEquals(1, c.n);
		l.onRebuilt();
		assertTrue(l.accept(COMPACT));
		// Scrubbing back across the change switches back.
		assertFalse(l.accept(WRAP));
		assertEquals(2, c.n);
		assertArrayEquals(WRAP, l.applied());
	}

	@Test
	public void heldChangeWaitsForRelease()
	{
		Counter c = new Counter();
		ReplayLayout l = new ReplayLayout(WRAP, c);
		l.hold(true);
		assertFalse(l.accept(COMPACT));
		assertTrue(l.accept(WRAP));
		assertEquals(0, c.n);
		assertArrayEquals(WRAP, l.applied());
		l.hold(false);
		assertFalse(l.accept(COMPACT));
		assertEquals(1, c.n);
	}

	@Test
	public void eachFieldTriggersRebuild()
	{
		int[] fields = { RecFormat.LAYOUT_MSG_WRAP_COLS, RecFormat.LAYOUT_MSG_ROWS,
				RecFormat.LAYOUT_COMPACT_HUD, RecFormat.LAYOUT_NEWTURN_MARK };
		for (int f : fields)
		{
			Counter c = new Counter();
			ReplayLayout l = new ReplayLayout(WRAP, c);
			int[] changed = WRAP.clone();
			changed[f] = f == RecFormat.LAYOUT_MSG_ROWS ? 10 : (WRAP[f] == 0 ? 1 : 0);
			assertFalse("field " + f, l.accept(changed));
			assertEquals("field " + f, 1, c.n);
		}
		// Not part of the views: prose/skills wrap, MP bar colour.
		Counter c = new Counter();
		ReplayLayout l = new ReplayLayout(WRAP, c);
		int[] cosmetic = WRAP.clone();
		cosmetic[RecFormat.LAYOUT_PROSE_COLS] = 60;
		cosmetic[RecFormat.LAYOUT_SKILLS_COLS] = 50;
		cosmetic[RecFormat.LAYOUT_MP_BAR_ARGB] = 0xFF00FF00;
		assertTrue(l.accept(cosmetic));
		assertEquals(0, c.n);
	}

	@Test
	public void unknownStartAdoptsFirstKnownLayout()
	{
		Counter c = new Counter();
		ReplayLayout l = new ReplayLayout(null, c);
		assertTrue(l.accept(new int[RecFormat.LAYOUT_SIZE]));
		assertFalse(l.accept(STOCK));
		assertEquals(1, c.n);
	}

	/** Recorder -> store -> controller: layout at start, after a mid-game toggle, across chunks. */
	@Test
	public void recordedLayoutReplaysAtEveryPosition()
	{
		RecorderReplayTest.Harness h = new RecorderReplayTest.Harness();
		h.rec.onLayout(WRAP);
		for (int t = 0; t < 200; t++)
			h.frame(t, t * 10);
		h.rec.onLayout(COMPACT); // live compact-HUD toggle
		for (int t = 200; t < 700; t++)
			h.frame(t, t * 10);
		h.rec.close();
		h.w.drain();
		assertTrue("spans several chunks", h.w.store.frames.size() > RecFormat.CHUNK_MAX_FRAMES * 2);

		final List<int[]> drawn = new ArrayList<>();
		final ReplayLayout[] layout = new ReplayLayout[1];
		final Counter rebuilds = new Counter();
		ReplayController rc = new ReplayController(h.w.store, false, 2,
				new ReplayController.RenderTarget()
				{
					@Override public void render(ScreenState s, int pos)
					{
						if (layout[0].accept(s.layout))
							drawn.add(s.layout);
					}
					@Override public void onPositionChanged(int pos, boolean playing) {}
				});
		ReplayTimeline tl = rc.timeline();
		assertArrayEquals(WRAP, rc.layoutAt(0));
		assertArrayEquals(COMPACT, rc.layoutAt(tl.size() - 1));
		assertArrayEquals(WRAP, rc.layoutAt(tl.posForTurn(199)));
		assertArrayEquals(COMPACT, rc.layoutAt(tl.posForTurn(200)));
		// Keyframes of later chunks carry the layout too.
		assertArrayEquals(COMPACT, rc.layoutAt(tl.posForTurn(650)));

		layout[0] = new ReplayLayout(rc.layoutAt(0), rebuilds);
		for (int p = 0; p < tl.size(); p++)
		{
			rc.seek(p);
			if (drawn.size() < p + 1)
			{
				// Host rebuilt the views: redraw the held frame.
				layout[0].onRebuilt();
				rc.refresh();
			}
		}
		assertEquals(1, rebuilds.n);
		assertEquals(tl.size(), drawn.size());
		for (int i = 0; i < drawn.size(); i++)
			assertTrue(ReplayLayout.sameViews(drawn.get(i), layout[0].applied())
					== (i >= tl.posForTurn(200)));
	}
}
