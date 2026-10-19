package com.crawlmb.recording;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** End-to-end: GameRecorder -> MemoryRecordingStore -> ReplayController. */
public class RecorderReplayTest
{
	static final class FakeNative implements GameRecorder.NativeBridge
	{
		String name = "Hero";
		long birth = 1700000000L;
		int resets;

		@Override
		public byte[] gameIdentity()
		{
			return ("{\"name\":\"" + name + "\",\"birth\":" + birth + ",\"seed\":\"1\","
					+ "\"dcss_version\":\"0.34.1\",\"species\":\"Minotaur\",\"job\":\"Berserker\","
					+ "\"game_type\":\"\",\"in_game\":1}").getBytes(StandardCharsets.UTF_8);
		}

		@Override
		public void resetSession() { resets++; }
	}

	static final class FakeClock implements GameRecorder.Clock
	{
		long t = 0;
		@Override public long nowMs() { return t; }
	}

	static final class Harness
	{
		final TestSupport.SyncWriter w = new TestSupport.SyncWriter();
		final FakeNative nat = new FakeNative();
		final FakeClock clock = new FakeClock();
		final GameRecorder rec = new GameRecorder(w.writer, nat, clock, "test", TestSupport.ROWS,
				TestSupport.COLS);
		final TestSupport.FakeScreen scr = new TestSupport.FakeScreen(9);
		final List<ScreenState> truthByTurnFrame = new ArrayList<>();

		Harness() { rec.setEnabled(true); }

		void frame(int turn, int aut)
		{
			clock.t += 50;
			scr.mutate(20);
			rec.onFrame(scr.c.clone(), scr.fg.clone(), scr.bg.clone(), turn, aut, false);
		}

		void snapshot(String kind, int turn, int aut, String level, int hp, String extra)
		{
			rec.onSnapshot(TestSupport.payload(TestSupport.doc(kind, turn, aut, level, hp, 20, 1, extra),
					new String[][] { { TestSupport.hash(turn + 1000), "3", "desc " + turn } }));
		}

		/** One player turn: a few frames, input wait, key, snapshot. */
		void turn(int turn, String level, int hp, String mapJson)
		{
			frame(turn, turn * 10);
			frame(turn, turn * 10);
			rec.onInputWait();
			snapshot("CP", turn, turn * 10, level, hp, mapJson);
			rec.onInput('o');
		}
	}

	static String mapKey(String level)
	{
		return "\"map\":{\"level\":\"" + level + "\",\"key\":1,\"w\":80,\"h\":70,\"cells\":["
				+ "[1,1,46,7,1,\"" + TestSupport.hash(5) + "\",0,0],"
				+ "[2,1,35,7,1,\"" + TestSupport.hash(6) + "\",0,0]]}";
	}

	static String mapDelta(String level, int x)
	{
		return "\"map\":{\"level\":\"" + level + "\",\"key\":0,\"w\":80,\"h\":70,\"cells\":["
				+ "[" + x + ",2,46,7,1,\"" + TestSupport.hash(5)
				+ "\",0,0,[[\"a dagger\",\"" + TestSupport.hash(7) + "\"]]]]}";
	}

	@Test
	public void newGameStartsAtFirstInGameFrameAndReplays()
	{
		Harness h = new Harness();
		for (int i = 0; i < 5; i++)
			h.frame(-1, -1); // char creation
		h.frame(0, 0);       // first in-game frame opens the recording
		h.snapshot("CP", 0, 0, "D:1", 20, mapDelta("D:1", 9)); // dropped: awaiting keyframe
		h.snapshot("CP", 0, 0, "D:1", 20, mapKey("D:1"));
		for (int t = 1; t <= 40; t++)
			h.turn(t, t < 20 ? "D:1" : "D:2", 20 - (t % 7),
					t == 20 ? mapKey("D:2") : mapDelta(t < 20 ? "D:1" : "D:2", 3 + t % 5));
		h.rec.close();
		h.w.drain();

		MemoryRecordingStore s = h.w.store;
		assertEquals("new@0", s.sessions.get(0));
		assertEquals(0, s.frames.get(0).frame);
		assertEquals(0, s.frames.get(0).turn); // char creation not recorded
		assertTrue(s.closed);
		assertTrue(h.nat.resets >= 1);
		assertFalse(s.blobBases.isEmpty()); // descs chain by kind
		for (String hash : s.blobs.keySet())
		{
			int depth = 0;
			for (String b = s.blobBases.get(hash); b != null; b = s.blobBases.get(b))
				depth++;
			assertTrue(depth < GameRecorder.BLOB_CHAIN_MAX);
		}

		final List<Integer> rendered = new ArrayList<>();
		ReplayController rc = new ReplayController(s, false, 3, new ReplayController.RenderTarget()
		{
			@Override public void render(ScreenState st, int pos) { rendered.add(pos); }
			@Override public void onPositionChanged(int pos, boolean playing) {}
		});
		ReplayTimeline tl = rc.timeline();
		assertEquals(s.frames.size(), tl.size());

		// Step by turn from the start: lands on the first frame of each turn.
		rc.seek(0);
		assertEquals(0, tl.turn(rc.position()));
		rc.step(ReplayTimeline.Unit.TURN, 1);
		assertEquals(1, tl.turn(rc.position()));
		rc.step(ReplayTimeline.Unit.TURN, 1);
		assertEquals(2, tl.turn(rc.position()));

		// Examine at turn 25: level D:2, map rebuilt from keyframe + deltas.
		rc.seekTurn(25);
		rc.step(ReplayTimeline.Unit.INPUT, 1);
		KnowledgeResolver.Knowledge k = rc.knowledge();
		assertNotNull(k);
		assertEquals("D:2", k.row.level);
		assertNotNull(k.map.get(1, 1));             // from keyframe
		assertNotNull(k.map.get(3 + 25 % 5, 2));   // from a delta
		assertEquals("a dagger", k.map.get(3 + 25 % 5, 2).items.get(0).name);
		assertEquals("desc " + 25, rc.resolver().blob(TestSupport.hash(25 + 1000)));

		// Other level still browsable as of this turn.
		int row = rc.resolver().rowAt(tl.frame(rc.position()));
		assertNotNull(rc.resolver().levelMap("D:1", row));
		assertEquals(java.util.Arrays.asList("D:1", "D:2"), rc.resolver().levelsVisited(row));

		// Play forward to the end, then rewind to the start.
		rc.setSpeed(64);
		rc.play();
		for (int i = 0; i < 1000 && rc.isPlaying(); i++)
			rc.tick(100);
		assertEquals(tl.size() - 1, rc.position());
		rc.setRewinding(true);
		for (int i = 0; i < 1000 && rc.isPlaying(); i++)
			rc.tick(100);
		assertEquals(0, rc.position());

		// Events: level change was derived.
		assertTrue(rc.skipEvent(1, Collections.singleton(EventDeriver.LEVEL)));
		assertEquals("D:2", rc.knowledge().row.level);
	}

	@Test
	public void resumeFromOlderSaveAbandonsBranch()
	{
		Harness h = new Harness();
		h.frame(0, 0);
		h.snapshot("CP", 0, 0, "D:1", 20, mapKey("D:1"));
		for (int t = 1; t <= 10; t++)
			h.turn(t, "D:1", 20, mapDelta("D:1", 4));
		h.rec.onGameSaved();          // save at turn 10
		for (int t = 11; t <= 15; t++)
			h.turn(t, "D:1", 20, mapDelta("D:1", 5));
		h.frame(-1, -1);              // process dies / back to menu: recording closes
		h.w.drain();
		int abandonedFirst = -1;
		for (MemoryRecordingStore.Frame f : h.w.store.frames)
			if (f.turn == 11 && abandonedFirst < 0)
				abandonedFirst = f.frame;

		// Restore the turn-10 save.
		h.frame(10, 100);
		h.snapshot("CP", 10, 100, "D:1", 20, mapKey("D:1"));
		for (int t = 11; t <= 13; t++)
			h.turn(t, "D:1", 20, mapDelta("D:1", 6));
		h.rec.close();
		h.w.drain();

		MemoryRecordingStore s = h.w.store;
		assertTrue(s.sessions.get(1).startsWith("resume@"));
		assertTrue(s.isAbandoned(abandonedFirst));
		ReplayTimeline visible = new ReplayTimeline(s.frameIndex(), s.events(), false);
		ReplayTimeline all = new ReplayTimeline(s.frameIndex(), s.events(), true);
		assertTrue(visible.size() < all.size());
		for (int i = 1; i < visible.size(); i++)
			assertTrue("visible timeline turns never go backwards past the restore",
					visible.turn(i) >= 0 ? visible.turn(i) >= visible.turn(i - 1) || visible.turn(i - 1) < 0 : true);
	}

	@Test
	public void snapshotDocsChainAndRestartPerSession()
	{
		Harness h = new Harness();
		h.frame(0, 0);
		h.snapshot("CP", 0, 0, "D:1", 20, mapKey("D:1"));
		int n = GameRecorder.DOC_CHAIN_MAX + 8;
		for (int t = 1; t <= n; t++)
			h.turn(t, "D:1", 20, mapDelta("D:1", 3 + t % 5));
		h.frame(-1, -1);
		h.frame(n + 1, (n + 1) * 10);
		h.snapshot("CP", n + 1, (n + 1) * 10, "D:1", 20, mapKey("D:1"));
		h.turn(n + 2, "D:1", 20, mapDelta("D:1", 4));
		h.rec.close();
		h.w.drain();

		List<MemoryRecordingStore.Snap> snaps = h.w.store.snaps;
		assertNull(h.w.store.docDict);
		assertEquals(n + 3, snaps.size());
		assertFalse(Deflate.usesDictionary(snaps.get(0).doc));
		assertTrue(Deflate.usesDictionary(snaps.get(1).doc));
		assertFalse(Deflate.usesDictionary(snaps.get(GameRecorder.DOC_CHAIN_MAX).doc));
		assertFalse("resume restarts the chain", Deflate.usesDictionary(snaps.get(n + 1).doc));
		for (int i = 0; i < snaps.size(); i++) // snapshot i is turn i
			assertTrue(h.w.store.snapshotDoc(i + 1).contains("\"turn\":" + i + ","));
	}

	@Test
	public void docDictionaryEmbeddedAndKeptOnResume()
	{
		byte[] dict = TestSupport.doc("CP", 0, 0, "D:1", 20, 20, 1, mapKey("D:1"))
				.getBytes(StandardCharsets.UTF_8);
		Harness h = new Harness();
		h.rec.setDocDict(dict);
		h.frame(0, 0);
		h.snapshot("CP", 0, 0, "D:1", 20, mapKey("D:1"));
		int n = GameRecorder.DOC_CHAIN_MAX + 8;
		for (int t = 1; t <= n; t++)
			h.turn(t, "D:1", 20, mapDelta("D:1", 3 + t % 5));
		h.rec.close();
		h.w.drain();

		MemoryRecordingStore s = h.w.store;
		assertArrayEquals(dict, s.docDict);
		long adler = Deflate.adler(dict);
		List<MemoryRecordingStore.Snap> snaps = s.snaps;
		assertTrue(Deflate.startsDocChain(snaps.get(0).doc, adler));
		assertTrue(Deflate.usesDictionary(snaps.get(0).doc));
		assertFalse(Deflate.startsDocChain(snaps.get(1).doc, adler));
		assertTrue(Deflate.startsDocChain(snaps.get(GameRecorder.DOC_CHAIN_MAX).doc, adler));
		assertTrue("the dictionary pays off on a chain start",
				snaps.get(0).doc.length < Deflate.deflate(dict).length / 2);

		// A newer app with a different bundled dictionary resumes the file.
		GameRecorder rec = new GameRecorder(h.w.writer, h.nat, h.clock, "test",
				TestSupport.ROWS, TestSupport.COLS);
		rec.setDocDict("another dictionary".getBytes(StandardCharsets.UTF_8));
		rec.setEnabled(true);
		h.clock.t += 50;
		h.scr.mutate(20);
		rec.onFrame(h.scr.c.clone(), h.scr.fg.clone(), h.scr.bg.clone(), n + 1, (n + 1) * 10, false);
		for (int t = n + 1; t <= n + 3; t++)
			rec.onSnapshot(TestSupport.payload(TestSupport.doc("CP", t, t * 10, "D:1", 20, 20, 1,
					t == n + 1 ? mapKey("D:1") : mapDelta("D:1", 4)), new String[0][]));
		rec.close();
		h.w.drain();

		assertArrayEquals(dict, s.docDict);
		assertEquals(n + 4, snaps.size());
		assertTrue("resume restarts on the file's dictionary",
				Deflate.startsDocChain(snaps.get(n + 1).doc, adler));
		for (int i = 0; i < snaps.size(); i++) // snapshot i is turn i
			assertTrue(s.snapshotDoc(i + 1).contains("\"turn\":" + i + ","));
	}

	@Test
	public void temporaryMessagesRollBack()
	{
		Harness h = new Harness();
		h.frame(0, 0);
		h.rec.onMessage("You hit the rat.".getBytes(StandardCharsets.UTF_8), 0, 0, false);
		h.rec.onMessage("Really quit?".getBytes(StandardCharsets.UTF_8), 1, 0, true);
		h.rec.onMessageRollback(1);
		h.frame(0, 0);
		h.rec.close();
		h.w.drain();
		List<RecordingReader.MessageRow> m = h.w.store.messagesUpTo(Integer.MAX_VALUE, 100);
		assertEquals(1, m.size());
		assertEquals("You hit the rat.", m.get(0).text);
	}

	@Test
	public void clockModes()
	{
		Harness h = new Harness();
		h.frame(0, 0);
		for (int t = 1; t <= 20; t++)
			h.turn(t, "D:1", 20, null);
		h.rec.close();
		h.w.drain();
		ReplayTimeline tl = new ReplayTimeline(h.w.store.frameIndex(), h.w.store.events(), false);
		ReplayClock c = new ReplayClock();
		c.setMode(ReplayClock.Mode.TURN_TIME);
		c.setMsPerTurn(100);
		int p = c.advance(tl, 0, 1000); // 10 turns at 1x
		assertTrue("~10 turns", Math.abs(tl.turn(p) - 10) <= 1);
		c.setMode(ReplayClock.Mode.AS_PLAYED);
		c.setDirection(-1);
		assertEquals(0, c.advance(tl, p, 1_000_000));
	}

	@Test
	public void turnLimitTrimsAtAnchors()
	{
		Harness h = new Harness();
		h.rec.setTurnLimit(100);
		h.frame(0, 0);
		h.snapshot("CP", 0, 0, "D:1", 20, mapKey("D:1"));
		int resets = h.nat.resets;
		for (int t = 1; t <= 300; t++)
		{
			// After a checkpoint reset, native sends a map keyframe.
			boolean key = h.nat.resets != resets;
			resets = h.nat.resets;
			h.turn(t, "D:1", 20, key ? mapKey("D:1") : mapDelta("D:1", 3 + t % 5));
		}
		h.rec.close();
		h.w.drain();

		MemoryRecordingStore s = h.w.store;
		assertTrue(h.nat.resets > 20);
		int firstTurn = s.frames.get(0).turn;
		assertTrue("kept from " + firstTurn, firstTurn >= 300 - 125 && firstTurn <= 200);
		for (MemoryRecordingStore.Snap sn : s.snaps)
			assertTrue(sn.row.turn >= firstTurn);
		assertNull(s.blob(TestSupport.hash(150 + 1000)));

		ReplayTimeline tl = new ReplayTimeline(s.frameIndex(), s.events(), false);
		KnowledgeResolver r = new KnowledgeResolver(s, tl);
		for (int i = 0; i < r.rows().size(); i++)
		{
			RecordingReader.SnapshotRow row = r.rows().get(i);
			assertNotNull(r.levelMap("D:1", i));
			assertEquals("desc " + row.turn, s.blob(TestSupport.hash(row.turn + 1000)));
		}
		assertTrue(s.snapshotDoc(s.snaps.get(0).row.id).contains("\"turn\":" + firstTurn));
	}

	@Test
	public void crashOrphansDroppedOnResume()
	{
		Harness h = new Harness();
		h.frame(0, 0);
		h.snapshot("CP", 0, 0, "D:1", 20, mapKey("D:1"));
		for (int t = 1; t <= 10; t++)
			h.turn(t, "D:1", 20, mapDelta("D:1", 4));
		h.rec.onGameSaved();
		// Committed by the periodic commit, but their frames sit in the open chunk.
		for (int t = 11; t <= 15; t++)
			h.turn(t, "D:1", 20, mapDelta("D:1", 5));
		h.w.drain();

		// Process dies; the next launch restores the turn-10 save.
		GameRecorder rec = new GameRecorder(h.w.writer, h.nat, h.clock, "test",
				TestSupport.ROWS, TestSupport.COLS);
		rec.setEnabled(true);
		h.clock.t += 50;
		h.scr.mutate(20);
		rec.onFrame(h.scr.c.clone(), h.scr.fg.clone(), h.scr.bg.clone(), 10, 100, false);
		rec.close();
		h.w.drain();

		MemoryRecordingStore s = h.w.store;
		int last = TestSupport.last(s.frames).frame;
		for (MemoryRecordingStore.Snap x : s.snaps)
			assertTrue(x.row.turn <= 10 && x.row.frame <= last);
		for (int[] in : s.inputs)
			assertTrue(in[0] <= last);
		for (RecordingReader.EventRow e : s.events)
			assertTrue(e.frame <= last);
	}

	@Test
	public void deathClosesRecording()
	{
		Harness h = new Harness();
		h.frame(0, 0);
		h.snapshot("CP", 0, 0, "D:1", 20, mapKey("D:1"));
		for (int t = 1; t <= 5; t++)
			h.turn(t, "D:1", 20, mapDelta("D:1", 3 + t));
		h.rec.onGameEnd(1, "Killed".getBytes(StandardCharsets.UTF_8),
				"morgue.txt".getBytes(StandardCharsets.UTF_8));
		h.w.drain();
		MemoryRecordingStore s = h.w.store;
		int recorded = s.frames.size();
		assertTrue(s.closed);
		assertFalse(h.rec.isRecording());
		for (int i = 0; i < 4; i++)
			h.frame(-1, -1); // death screens
		for (int i = 0; i < 6; i++)
			h.frame(GameRecorder.TURN_NO_GAME, GameRecorder.TURN_NO_GAME); // next char's menus
		h.w.drain();

		assertFalse(h.rec.isRecording());
		assertEquals(recorded, s.frames.size());
		assertEquals(5, TestSupport.last(s.frames).turn);
	}

	@Test
	public void skippedUiScreensTakeNoTime()
	{
		Harness h = new Harness();
		h.rec.setIncludeUi(false);
		h.frame(0, 0);
		h.frame(1, 10);
		for (int i = 0; i < 100; i++)
		{
			h.clock.t += 100;
			h.rec.onFrame(h.scr.c.clone(), h.scr.fg.clone(), h.scr.bg.clone(), 1, 10, true);
		}
		h.frame(2, 20);
		h.rec.flush();
		h.w.drain();
		List<MemoryRecordingStore.Frame> f = h.w.store.frames;
		assertEquals(3, f.size());
		// Only the 100 ms before the popup opened counts, not the 10 s on it.
		assertEquals(f.get(1).tMs + 100, f.get(2).tMs);
	}
}
