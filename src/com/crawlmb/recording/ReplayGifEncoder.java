package com.crawlmb.recording;

import android.graphics.Bitmap;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Clips part of a recording into a looping animated GIF of frames captured
 * off the replay views. A frame is written only when the screen changes, as
 * the rectangle that differs from the previous frame, with its own palette.
 * Runs on a background thread.
 */
public final class ReplayGifEncoder
{
	// Browsers stretch delays under 20 ms, so briefer screens are dropped.
	private static final int MIN_DELAY_MS = 20;
	private static final long END_HOLD_MS = 2000;

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
			double speed, ReplayVideoEncoder.Frames frames, File out,
			ReplayVideoEncoder.Progress progress)
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

			frames.start(from);
			int w = frames.width(), h = frames.height();
			frames.setOutputSize(w, h);

			try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out)))
			{
				Gif gif = new Gif(os, w, h);
				int[] px = new int[w * h];
				ScreenState shown = null;
				int pending = from; // position on screen, captured once it's kept
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
					if (shown != null && (s == shown
							|| (s.gridEquals(shown) && s.sideChannelsEqual(shown))))
						continue;
					// A screen too brief to show is replaced; its time carries over.
					if (shown != null && heldMs >= MIN_DELAY_MS)
					{
						capture(frames, pending, px, w, h);
						gif.frame(px, heldMs);
						heldMs = 0;
					}
					shown = s;
					pending = pos;
				}
				capture(frames, pending, px, w, h);
				gif.frame(px, heldMs + END_HOLD_MS);
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

	private static void capture(ReplayVideoEncoder.Frames frames, int pos, int[] px, int w,
			int h) throws IOException
	{
		Bitmap bm = frames.frame(pos);
		bm.getPixels(px, 0, w, 0, 0, w, h);
	}

	/** GIF89a writer: per-frame palettes, infinite loop, delta frames kept on screen. */
	private static final class Gif
	{
		private final OutputStream out;
		private final int w, h;
		private final int[] pal = new int[256];
		private int palSize;
		private final Map<Integer, Integer> nearest = new HashMap<>();
		private int[] shown;
		private double elapsedMs;
		private long writtenCs;

		Gif(OutputStream out, int w, int h) throws IOException
		{
			this.out = out;
			this.w = w;
			this.h = h;
			out.write(new byte[] { 'G', 'I', 'F', '8', '9', 'a' });
			short16(w);
			short16(h);
			out.write(0x70); // no global table, 8-bit colour
			out.write(0);
			out.write(0);
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
			out.write(0x87); // local table, 256 entries
			palette(px, x0, y0, fw, fh);
			for (int c : pal)
			{
				out.write(c >> 16 & 0xFF);
				out.write(c >> 8 & 0xFF);
				out.write(c & 0xFF);
			}
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

		// The rect's 256 commonest colours; anti-aliased edges take the nearest.
		private void palette(int[] px, int x0, int y0, int fw, int fh)
		{
			Map<Integer, int[]> counts = new HashMap<>();
			for (int y = 0; y < fh; y++)
				for (int x = 0, i = (y0 + y) * w + x0; x < fw; x++, i++)
				{
					int rgb = px[i] & 0xFFFFFF;
					int[] n = counts.get(rgb);
					if (n == null)
						counts.put(rgb, new int[] { 1 });
					else
						n[0]++;
				}
			List<Map.Entry<Integer, int[]>> byCount = new ArrayList<>(counts.entrySet());
			Collections.sort(byCount, (a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
			nearest.clear();
			palSize = Math.min(pal.length, byCount.size());
			for (int i = 0; i < palSize; i++)
			{
				pal[i] = byCount.get(i).getKey();
				nearest.put(pal[i], i);
			}
			for (int i = palSize; i < pal.length; i++)
				pal[i] = 0;
		}

		private int index(int rgb)
		{
			Integer hit = nearest.get(rgb);
			if (hit != null)
				return hit;
			int best = 0;
			long bestD = Long.MAX_VALUE;
			for (int i = 0; i < palSize; i++)
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
