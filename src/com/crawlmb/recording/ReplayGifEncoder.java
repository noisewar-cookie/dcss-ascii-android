package com.crawlmb.recording;

import android.graphics.Typeface;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Clips part of a recording into a looping animated GIF of the classic
 * console, drawn by the MP4 export's painter. A frame is written only when the
 * screen changes, as the rectangle that differs from the previous frame.
 * Runs on a background thread.
 */
public final class ReplayGifEncoder
{
	private static final float CELL_PX = 10;
	// Browsers stretch delays under 20 ms, so briefer screens are dropped.
	private static final int MIN_DELAY_MS = 20;
	private static final long END_HOLD_MS = 2000;
	private static final int MAX_BASE_COLOURS = 64;

	private ReplayGifEncoder() {}

	/** GIF length in ms for positions from..to, paced like the replay clock. */
	public static long durationMs(ReplayTimeline tl, int from, int to, ReplayClock.Mode mode,
			double speed)
	{
		ReplayClock clock = new ReplayClock();
		clock.setMode(mode);
		double ms = 0;
		for (int i = from + 1; i <= to; i++)
			ms += clock.cost(tl, i - 1, i);
		return (long) (ms / speed) + END_HOLD_MS;
	}

	public static void encode(File src, int fromFrame, int toFrame, ReplayClock.Mode mode,
			double speed, Typeface tf, File out, ReplayVideoEncoder.Progress progress)
			throws IOException
	{
		SqliteRecordingStore store = SqliteRecordingStore.openForReplay(src);
		boolean ok = false;
		try
		{
			ReplayTimeline tl = ReplayVideoEncoder.timeline(store);
			int from = Math.max(0, tl.posOfFrame(fromFrame)), to = tl.posOfFrame(toFrame);
			if (tl.size() == 0 || to <= from)
				throw new IOException("nothing to clip");
			ChunkCache cache = new ChunkCache(store, 2);
			ReplayClock clock = new ReplayClock();
			clock.setMode(mode);

			int first = from;
			while (first < to && tl.turn(first) < 0)
				first++;
			ReplayVideoEncoder.ConsolePainter painter = new ReplayVideoEncoder.ConsolePainter(tf);
			int[] size = painter.sizeFor(cache.state(tl.chunkId(first), tl.frame(first)), CELL_PX);
			int w = size[0];
			painter.setSize(w, size[1]);
			// Gameplay layouts reserve rows the console never fills; crop them off.
			int h = usedHeight(tl, cache, painter, from, to);

			try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out)))
			{
				Gif gif = new Gif(os, w, h, palette(tl, cache, from, to));
				int[] pending = new int[w * h];
				ScreenState shown = null;
				double heldMs = 0; // how long the pending screen has been up
				for (int pos = from; pos <= to; pos++)
				{
					if (progress.cancelled())
						throw new IOException("cancelled");
					if ((pos & 63) == 0)
						progress.update(pos - from, to - from);
					if (pos > from)
						heldMs += clock.cost(tl, pos - 1, pos) / speed;
					ScreenState s = cache.state(tl.chunkId(pos), tl.frame(pos));
					if (shown != null && (s == shown || s.gridEquals(shown)))
						continue;
					// A screen too brief to show is replaced; its time carries over.
					if (shown != null && heldMs >= MIN_DELAY_MS)
					{
						gif.frame(pending, heldMs);
						heldMs = 0;
					}
					shown = s;
					painter.paint(s);
					painter.bitmap.getPixels(pending, 0, w, 0, 0, w, h);
				}
				gif.frame(pending, heldMs + END_HOLD_MS);
				gif.finish();
			}
			ok = true;
		}
		finally
		{
			store.close();
			if (!ok)
				out.delete();
		}
	}

	private static int usedHeight(ReplayTimeline tl, ChunkCache cache,
			ReplayVideoEncoder.ConsolePainter painter, int from, int to)
	{
		int h = 1;
		ScreenState prev = null;
		for (int pos = from; pos <= to; pos++)
		{
			ScreenState s = cache.state(tl.chunkId(pos), tl.frame(pos));
			if (s != prev)
				h = Math.max(h, painter.usedHeight(s));
			prev = s;
		}
		return h;
	}

	/** The clip's cell colours plus their anti-aliased blends against black. */
	private static int[] palette(ReplayTimeline tl, ChunkCache cache, int from, int to)
	{
		Set<Integer> base = new LinkedHashSet<>();
		base.add(0);
		ScreenState prev = null;
		for (int pos = from; pos <= to && base.size() < MAX_BASE_COLOURS; pos++)
		{
			ScreenState s = cache.state(tl.chunkId(pos), tl.frame(pos));
			if (s == prev)
				continue;
			prev = s;
			for (int i = 0; i < s.fg.length && base.size() < MAX_BASE_COLOURS; i++)
			{
				base.add(s.fg[i] & 0xFFFFFF);
				// Checked per add: 65 base colours plus blends would overflow the 256 table.
				if (base.size() < MAX_BASE_COLOURS)
					base.add(s.bg[i] & 0xFFFFFF);
			}
		}
		int[] pal = new int[256];
		int n = 0;
		for (int c : base)
			pal[n++] = c;
		for (int c : base)
			for (int a = 1; a <= 3 && c != 0; a++)
				pal[n++] = scale(c, a, 4);
		return pal;
	}

	private static int scale(int rgb, int num, int den)
	{
		int r = (rgb >> 16 & 0xFF) * num / den, g = (rgb >> 8 & 0xFF) * num / den,
				b = (rgb & 0xFF) * num / den;
		return r << 16 | g << 8 | b;
	}

	/** GIF89a writer: global palette, infinite loop, delta frames kept on screen. */
	private static final class Gif
	{
		private final OutputStream out;
		private final int w, h;
		private final int[] pal;
		private final Map<Integer, Integer> nearest = new HashMap<>();
		private int[] shown;
		private double elapsedMs;
		private long writtenCs;

		Gif(OutputStream out, int w, int h, int[] pal) throws IOException
		{
			this.out = out;
			this.w = w;
			this.h = h;
			this.pal = pal;
			out.write(new byte[] { 'G', 'I', 'F', '8', '9', 'a' });
			short16(w);
			short16(h);
			out.write(0xF7); // global table, 8-bit colour, 256 entries
			out.write(0);
			out.write(0);
			for (int c : pal)
			{
				out.write(c >> 16 & 0xFF);
				out.write(c >> 8 & 0xFF);
				out.write(c & 0xFF);
			}
			out.write(new byte[] { 0x21, (byte) 0xFF, 11, 'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E',
					'2', '.', '0', 3, 1, 0, 0, 0 });
		}

		void frame(int[] px, double ms) throws IOException
		{
			int x0 = 0, y0 = 0, x1 = w - 1, y1 = h - 1;
			if (shown == null)
				shown = new int[px.length];
			else
			{
				x0 = w;
				y0 = h;
				x1 = -1;
				y1 = -1;
				for (int y = 0; y < h; y++)
					for (int x = 0, i = y * w; x < w; x++, i++)
						if (px[i] != shown[i])
						{
							x0 = Math.min(x0, x);
							x1 = Math.max(x1, x);
							y0 = Math.min(y0, y);
							y1 = y;
						}
				if (x1 < 0) // same pixels: a 1-pixel frame still carries the delay
				{
					x0 = x1 = 0;
					y0 = y1 = 0;
				}
			}
			System.arraycopy(px, 0, shown, 0, px.length);
			elapsedMs += ms;
			long cs = Math.max(2, Math.round(elapsedMs / 10) - writtenCs);
			writtenCs += cs;

			out.write(new byte[] { 0x21, (byte) 0xF9, 4, 1 << 2 }); // do not dispose
			short16((int) Math.min(cs, 0xFFFF));
			out.write(0);
			out.write(0);
			int fw = x1 - x0 + 1, fh = y1 - y0 + 1;
			out.write(0x2C);
			short16(x0);
			short16(y0);
			short16(fw);
			short16(fh);
			out.write(0);
			byte[] idx = new byte[fw * fh];
			for (int y = 0, k = 0; y < fh; y++)
				for (int x = 0; x < fw; x++)
					idx[k++] = (byte) index(px[(y0 + y) * w + x0 + x] & 0xFFFFFF);
			lzw(idx);
		}

		void finish() throws IOException
		{
			out.write(0x3B);
		}

		private int index(int rgb)
		{
			Integer hit = nearest.get(rgb);
			if (hit != null)
				return hit;
			int best = 0;
			long bestD = Long.MAX_VALUE;
			for (int i = 0; i < pal.length; i++)
			{
				int dr = (rgb >> 16 & 0xFF) - (pal[i] >> 16 & 0xFF);
				int dg = (rgb >> 8 & 0xFF) - (pal[i] >> 8 & 0xFF);
				int db = (rgb & 0xFF) - (pal[i] & 0xFF);
				long d = (long) dr * dr + dg * dg + db * db;
				if (d < bestD)
				{
					bestD = d;
					best = i;
				}
			}
			nearest.put(rgb, best);
			return best;
		}

		private void short16(int v) throws IOException
		{
			out.write(v & 0xFF);
			out.write(v >> 8 & 0xFF);
		}

		// LZW with 8-bit roots, variable codes up to 12 bits, clear on a full table.
		private final int[] dict = new int[4096 * 256];
		private final int[] used = new int[4096];
		private final byte[] block = new byte[255];
		private int blockLen, bits, bitCount, codeSize;

		private void lzw(byte[] data) throws IOException
		{
			final int clear = 256, eoi = 257;
			out.write(8);
			bits = 0;
			bitCount = 0;
			blockLen = 0;
			codeSize = 9;
			int next = eoi + 1, nUsed = 0;
			emit(clear);
			int prefix = data[0] & 0xFF;
			for (int i = 1; i < data.length; i++)
			{
				int k = data[i] & 0xFF;
				int key = prefix << 8 | k;
				if (dict[key] != 0)
				{
					prefix = dict[key];
					continue;
				}
				emit(prefix);
				if (next < 4096)
				{
					if (next == 1 << codeSize)
						codeSize++;
					dict[key] = next++;
					used[nUsed++] = key;
				}
				else
				{
					emit(clear);
					for (int u = 0; u < nUsed; u++)
						dict[used[u]] = 0;
					nUsed = 0;
					next = eoi + 1;
					codeSize = 9;
				}
				prefix = k;
			}
			emit(prefix);
			emit(eoi);
			if (bitCount > 0)
				put(bits & 0xFF);
			if (blockLen > 0)
				flushBlock();
			out.write(0);
			for (int u = 0; u < nUsed; u++)
				dict[used[u]] = 0;
		}

		private void emit(int code) throws IOException
		{
			bits |= code << bitCount;
			bitCount += codeSize;
			while (bitCount >= 8)
			{
				put(bits & 0xFF);
				bits >>>= 8;
				bitCount -= 8;
			}
		}

		private void put(int b) throws IOException
		{
			block[blockLen++] = (byte) b;
			if (blockLen == 255)
				flushBlock();
		}

		private void flushBlock() throws IOException
		{
			out.write(blockLen);
			out.write(block, 0, blockLen);
			blockLen = 0;
		}
	}
}
