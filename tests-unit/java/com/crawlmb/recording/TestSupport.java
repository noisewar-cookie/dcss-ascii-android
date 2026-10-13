package com.crawlmb.recording;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

/** Helpers shared by recording tests. */
final class TestSupport
{
	static final int ROWS = 72, COLS = 80;
	static final int[] COLOURS = { 0xFF000000, 0xFFAAAAAA, 0xFFFF5555, 0xFF55FF55,
			0xFF5555FF, 0xFFFFFF55, 0xFFFFFFFF, 0xFF305FE8 };

	private TestSupport() {}

	/** Grid like a DCSS screen: mostly blank, a few changes per step. */
	static final class FakeScreen
	{
		final char[] c = new char[ROWS * COLS];
		final int[] fg = new int[ROWS * COLS];
		final int[] bg = new int[ROWS * COLS];
		final Random rnd;

		FakeScreen(long seed)
		{
			rnd = new Random(seed);
			for (int i = 0; i < c.length; i++)
			{
				c[i] = ' ';
				fg[i] = COLOURS[1];
				bg[i] = COLOURS[0];
			}
		}

		void mutate(int cells)
		{
			for (int k = 0; k < cells; k++)
			{
				int i = rnd.nextInt(c.length);
				c[i] = (char) (rnd.nextInt(3) == 0 ? 0x2588 : 33 + rnd.nextInt(90));
				fg[i] = COLOURS[rnd.nextInt(COLOURS.length)];
				bg[i] = rnd.nextInt(10) == 0 ? COLOURS[rnd.nextInt(COLOURS.length)] : COLOURS[0];
			}
		}

		void newColour(int argb)
		{
			int i = rnd.nextInt(c.length);
			fg[i] = argb;
			c[i] = '@';
		}
	}

	/** Build a "DCS1" payload exactly like android-recorder.cc make_payload. */
	static byte[] payload(String docJson, String[][] blobs)
	{
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		byte[] doc = docJson.getBytes(StandardCharsets.UTF_8);
		u32(o, RecFormat.SNAPSHOT_MAGIC);
		u32(o, doc.length);
		o.write(doc, 0, doc.length);
		u32(o, blobs.length);
		for (String[] b : blobs)
		{
			// b = {hashHex32, kind, text}
			long a = Long.parseUnsignedLong(b[0].substring(0, 16), 16);
			long c = Long.parseUnsignedLong(b[0].substring(16), 16);
			u32(o, (int) (a >>> 32)); u32(o, (int) a);
			u32(o, (int) (c >>> 32)); u32(o, (int) c);
			o.write(Integer.parseInt(b[1]));
			byte[] t = b[2].getBytes(StandardCharsets.UTF_8);
			u32(o, t.length);
			o.write(t, 0, t.length);
		}
		return o.toByteArray();
	}

	static String hash(int n) { return String.format("%032x", n); }

	static void u32(ByteArrayOutputStream o, int v)
	{
		o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v);
	}

	/** Snapshot doc with the given fields and an optional map object. */
	static String doc(String kind, int turn, int aut, String level, int hp, int hpMax, int xl,
			String extra)
	{
		return "{\"v\":1,\"kind\":\"" + kind + "\",\"turn\":" + turn + ",\"aut\":" + aut
				+ ",\"level\":\"" + level + "\",\"branch\":\"D\",\"depth\":1"
				+ ",\"player\":{\"hp\":" + hp + ",\"hpmax\":" + hpMax + ",\"mp\":1,\"mpmax\":2,\"xl\":"
				+ xl + ",\"god\":\"No God\"}" + (extra == null ? "" : "," + extra) + "}";
	}

	/** Runs writer ops synchronously on the calling thread (deterministic tests). */
	static final class SyncWriter
	{
		final MemoryRecordingStore store = new MemoryRecordingStore();
		final RecWriterThread writer = new RecWriterThread(id -> store, t ->
		{
			throw new AssertionError(t);
		});

		void drain() { writer.drain(5000); }
	}

	static <T> T last(List<T> l) { return l.get(l.size() - 1); }
}
