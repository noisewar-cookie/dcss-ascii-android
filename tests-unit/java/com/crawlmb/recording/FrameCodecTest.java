package com.crawlmb.recording;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class FrameCodecTest
{
	@Test
	public void roundTripAcrossChunksWithSideChannels()
	{
		FrameChunkEncoder enc = new FrameChunkEncoder(TestSupport.ROWS, TestSupport.COLS);
		TestSupport.FakeScreen scr = new TestSupport.FakeScreen(42);
		List<ScreenState> truth = new ArrayList<>();
		List<FrameChunkEncoder.Chunk> chunks = new ArrayList<>();
		long t = 1000;

		for (int f = 0; f < 700; f++)
		{
			scr.mutate(f % 50 == 0 ? 2000 : 30);
			if (f == 333)
				scr.newColour(0xFF123456);
			t += f % 7 == 0 ? 1500 : 16;
			if (f % 10 == 0)
				enc.hudVitals(t, "HP 10/20\tMP 3/4", new int[] { 1, 2 });
			if (f % 25 == 0)
				enc.hudBars(t, new int[] { 1000, f, 0, 1, 2, 3, 4, 5 }, new int[0]);
			if (f == 100)
				enc.messageHistory(t, new char[] { 'h', 'i' }, new int[] { 5, 6 }, new int[] { 7, 8 });
			if (f == 101)
				enc.messageHistoryMode(t, true);
			if (f == 200)
				enc.layout(t, new int[] { 40, 5, 38, 30, 1, 1, 0xFF305FE8 });
			if (f == 250)
				enc.mapAnchor(t, 12, 9);
			FrameChunkEncoder.Chunk c = enc.frame(f, t, f / 3, f * 10, scr.c, scr.fg, scr.bg);
			if (c != null)
				chunks.add(c);
			truth.add(enc.state().copy());
		}
		chunks.add(enc.flush());
		assertTrue("expected several chunks", chunks.size() >= 3);

		int checked = 0;
		for (FrameChunkEncoder.Chunk c : chunks)
		{
			byte[] raw = Deflate.inflate(Deflate.deflate(c.raw));
			List<FrameChunkDecoder.DecodedFrame> dec = FrameChunkDecoder.decode(raw);
			assertEquals(c.frames.size(), dec.size());
			for (FrameChunkDecoder.DecodedFrame d : dec)
			{
				ScreenState want = truth.get(d.frame);
				assertTrue("grid mismatch at frame " + d.frame, want.gridEquals(d.state));
				assertTrue("side channels mismatch at frame " + d.frame,
						want.sideChannelsEqual(d.state));
				checked++;
			}
		}
		assertEquals(700, checked);
	}

	@Test
	public void unchangedFrameIsTiny()
	{
		FrameChunkEncoder enc = new FrameChunkEncoder(TestSupport.ROWS, TestSupport.COLS);
		TestSupport.FakeScreen scr = new TestSupport.FakeScreen(1);
		enc.frame(0, 0, 0, 0, scr.c, scr.fg, scr.bg);
		FrameChunkEncoder.Chunk k = enc.flush();
		enc.frame(1, 10, 0, 0, scr.c, scr.fg, scr.bg);
		int before = enc.flush().raw.length;
		assertTrue(before > 1000); // keyframe
		enc.frame(2, 20, 0, 0, scr.c, scr.fg, scr.bg);
		enc.frame(3, 30, 0, 0, scr.c, scr.fg, scr.bg);
		assertEquals("unchanged delta should add only a few bytes", true,
				enc.flush().raw.length - before < 16);
		assertNotNull(k);
	}

	@Test
	public void repeatedHudValuesAreDropped()
	{
		FrameChunkEncoder enc = new FrameChunkEncoder(TestSupport.ROWS, TestSupport.COLS);
		TestSupport.FakeScreen scr = new TestSupport.FakeScreen(1);
		enc.hudVitals(0, "HP 1/2", new int[] { 1 });
		enc.frame(0, 0, 0, 0, scr.c, scr.fg, scr.bg);
		enc.frame(1, 10, 0, 0, scr.c, scr.fg, scr.bg);
		int plain = enc.flush().raw.length;
		enc.frame(2, 20, 0, 0, scr.c, scr.fg, scr.bg);
		enc.hudVitals(25, "HP 1/2", new int[] { 1 });
		enc.hudBars(25, null, null);
		enc.frame(3, 30, 0, 0, scr.c, scr.fg, scr.bg);
		assertEquals("repeat should add nothing", plain, enc.flush().raw.length);
		enc.frame(4, 40, 0, 0, scr.c, scr.fg, scr.bg);
		enc.hudVitals(45, "HP 2/2", new int[] { 1 });
		enc.frame(5, 50, 0, 0, scr.c, scr.fg, scr.bg);
		List<FrameChunkDecoder.DecodedFrame> dec = FrameChunkDecoder.decode(enc.flush().raw);
		assertEquals("HP 2/2", dec.get(1).state.hudVitals);
	}

	@Test
	public void rejectsCorruptChunks()
	{
		FrameChunkEncoder enc = new FrameChunkEncoder(TestSupport.ROWS, TestSupport.COLS);
		TestSupport.FakeScreen scr = new TestSupport.FakeScreen(7);
		enc.frame(0, 0, 0, 0, scr.c, scr.fg, scr.bg);
		scr.mutate(10);
		enc.frame(1, 5, 0, 0, scr.c, scr.fg, scr.bg);
		byte[] raw = enc.flush().raw;

		byte[] truncated = java.util.Arrays.copyOf(raw, raw.length - 3);
		try
		{
			FrameChunkDecoder.decode(truncated);
			fail("truncated chunk should fail");
		}
		catch (ByteIO.FormatException expected) {}

		byte[] badMagic = raw.clone();
		badMagic[0] ^= 0x55;
		try
		{
			FrameChunkDecoder.decode(badMagic);
			fail("bad magic should fail");
		}
		catch (ByteIO.FormatException expected) {}
	}

	@Test
	public void inflateCapStopsZipBombs()
	{
		byte[] zeros = new byte[2 * 1024 * 1024];
		byte[] packed = Deflate.deflate(zeros);
		try
		{
			Deflate.inflate(packed, 1024 * 1024);
			fail("cap should trigger");
		}
		catch (ByteIO.FormatException expected) {}
		assertEquals(zeros.length, Deflate.inflate(packed).length);
	}

	@Test
	public void emptyPayloadRoundTrips()
	{
		// Empty panel text (e.g. religion with no god) is stored as a blob.
		assertEquals(0, Deflate.inflate(Deflate.deflate(new byte[0])).length);
	}
}
