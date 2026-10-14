package com.crawlmb.recording;

import static org.junit.Assert.*;

import org.junit.Test;

public class ExamineAndReadOnlyTest
{
	/** One recorded turn with a goblin, a dagger, and known floor/wall cells. */
	private static ReplayController controller(final ScreenState[] lastRendered)
	{
		RecorderReplayTest.Harness h = new RecorderReplayTest.Harness();
		h.frame(0, 0);
		String view = "\"pos\":{\"x\":10,\"y\":10},"
				+ "\"view\":{\"cx\":10,\"cy\":10,\"hx\":16,\"hy\":8,\"vx\":1,\"vy\":1,\"tx\":1,\"ty\":1,\"sx\":33,\"sy\":17},"
				+ "\"mons\":[{\"x\":11,\"y\":10,\"n\":\"a goblin\",\"vis\":1,\"d\":\"" + TestSupport.hash(900) + "\"}],"
				+ "\"map\":{\"level\":\"D:1\",\"key\":1,\"w\":80,\"h\":70,\"cells\":["
				+ "[10,10,64,7,2,\"" + TestSupport.hash(901) + "\",0,0],"
				+ "[11,10,103,7,2,\"" + TestSupport.hash(901) + "\",0,0],"
				+ "[12,10,41,7,2,\"" + TestSupport.hash(901) + "\",\"" + TestSupport.hash(902)
				+ "\",0,[[\"a dagger\",\"" + TestSupport.hash(903) + "\"]]],"
				+ "[10,11,35,7,2,\"" + TestSupport.hash(904) + "\",0,0]],"
				+ TestSupport.los(2, 2, 17, new int[] { 10, 10 }, new int[] { 11, 10 },
						new int[] { 12, 10 }) + "}";
		h.rec.onSnapshot(TestSupport.payload(TestSupport.doc("CP", 0, 0, "D:1", 20, 20, 1, view),
				new String[][] {
						{ TestSupport.hash(900), "3", "A goblin.\nA small ugly creature." },
						{ TestSupport.hash(901), "5", "floor" },
						{ TestSupport.hash(902), "4", "The floor. Nothing special." },
						{ TestSupport.hash(903), "2", "A dagger.\nA short blade." },
						{ TestSupport.hash(904), "5", "rock wall" } }));
		h.frame(0, 0);
		h.rec.close();
		h.w.drain();
		return new ReplayController(h.w.store, false, 2, new ReplayController.RenderTarget()
		{
			@Override public void render(ScreenState s, int pos) { lastRendered[0] = s; }
			@Override public void onPositionChanged(int pos, boolean playing) {}
		});
	}

	@Test
	public void cursorStartsOnPlayerMovesAndDescribes()
	{
		ScreenState[] shown = new ScreenState[1];
		ReplayController rc = controller(shown);
		rc.seek(rc.timeline().size() - 1);
		ExamineCursor cur = new ExamineCursor(rc.knowledge(), rc.resolver());
		assertEquals(10, cur.x());
		assertTrue(cur.summary().startsWith("You."));

		assertTrue(cur.move(1, 0));
		assertEquals("A goblin. Floor", cur.summary());
		assertEquals("A goblin.\nA small ugly creature.", cur.describe());
		// Picker lists the monster and the floor under it.
		assertEquals(2, cur.targets().size());
		assertEquals("A goblin", cur.targets().get(0)[0]);
		assertEquals("Floor", cur.targets().get(1)[0]);
		assertEquals("Floor.", cur.targets().get(1)[1]); // no recorded terrain desc

		assertTrue(cur.move(1, 0));
		assertTrue(cur.summary().startsWith("A dagger."));
		assertTrue(cur.describe().startsWith("A dagger.\nA short blade."));
		assertTrue(cur.describe().endsWith("The floor. Nothing special."));

		cur.reset();
		assertTrue(cur.move(0, 1));
		assertEquals("Rock wall (remembered)", cur.summary());

		cur.reset();
		assertTrue(cur.move(-5, -5));
		assertEquals("You have no knowledge of this place.", cur.summary());

		// Can't leave the recorded view (hx = 16).
		cur.reset();
		for (int i = 0; i < 16; i++)
			assertTrue(cur.move(1, 0));
		assertFalse(cur.move(1, 0));
	}

	@Test
	public void overlayHighlightsCursorCellOnScreen()
	{
		ScreenState[] shown = new ScreenState[1];
		ReplayController rc = controller(shown);
		rc.seek(rc.timeline().size() - 1);
		ScreenState plain = shown[0];
		final ExamineCursor cur = new ExamineCursor(rc.knowledge(), rc.resolver());
		rc.setOverlay(cur::overlay);
		int idx = cur.screenIndex(plain.rows, plain.cols);
		// grid (10,10), centre (10,10), half (16,8), viewp (1,1) -> screen (17,9) 1-based
		assertEquals((9 - 1) * plain.cols + (17 - 1), idx);
		assertEquals(0xFFBBBBBB, shown[0].bg[idx]);
		assertFalse("recorded frame must not be mutated", plain.bg[idx] == 0xFFBBBBBB);
		cur.move(1, 0);
		rc.refresh();
		assertEquals(0xFFBBBBBB, shown[0].bg[idx + 1]);
		rc.setOverlay(null);
		assertTrue(shown[0].gridEquals(plain));
	}

	@Test
	public void readOnlyRules()
	{
		assertTrue(ReplayReadOnly.isEditKey(ReplayReadOnly.Screen.ITEM, 'W'));
		assertTrue(ReplayReadOnly.isEditKey(ReplayReadOnly.Screen.ITEM, 'q'));
		assertFalse(ReplayReadOnly.isEditKey(ReplayReadOnly.Screen.ITEM, 'v'));
		assertFalse(ReplayReadOnly.isEditKey(ReplayReadOnly.Screen.INVENTORY, 'a'));
		assertTrue(ReplayReadOnly.isEditKey(ReplayReadOnly.Screen.TRAINING, 'b'));
		assertTrue(ReplayReadOnly.isEditKey(ReplayReadOnly.Screen.SPELL, 'F'));
		assertTrue(ReplayReadOnly.isEditKey(ReplayReadOnly.Screen.ABILITY, 'a'));
		assertFalse(ReplayReadOnly.isEditKey(ReplayReadOnly.Screen.CHARACTER, 'x'));
		assertTrue(ReplayReadOnly.screensEnabled(false));
		assertFalse(ReplayReadOnly.screensEnabled(true));
		assertEquals("Recordings can't be edited.", ReplayReadOnly.MESSAGE);
	}
}
