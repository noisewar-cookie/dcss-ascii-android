package com.crawlmb.recording;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Screen-track encoder. Turns the stream of NativeWrapper display calls into
 * self-contained chunks: each chunk starts with a KEYFRAME (full grid, full
 * palette, all side-channel state) followed by side-channel events and DELTA
 * frames. Any chunk decodes on its own.
 *
 * Not thread-safe: GameRecorder serializes calls. Output is raw (uncompressed);
 * the writer thread deflates it off the game thread.
 */
public final class FrameChunkEncoder
{
	/** Per-frame index row (frames table). flags may be updated until written. */
	public static final class FrameInfo
	{
		public final int frame;
		public final long tMs;
		public final int turn;
		public final int aut;
		public int flags;

		FrameInfo(int frame, long tMs, int turn, int aut, int flags)
		{
			this.frame = frame;
			this.tMs = tMs;
			this.turn = turn;
			this.aut = aut;
			this.flags = flags;
		}
	}

	/** A closed chunk ready to be compressed and stored. */
	public static final class Chunk
	{
		public final int firstFrame;
		public final int lastFrame;
		public final byte[] raw;
		public final List<FrameInfo> frames;

		Chunk(int firstFrame, int lastFrame, byte[] raw, List<FrameInfo> frames)
		{
			this.firstFrame = firstFrame;
			this.lastFrame = lastFrame;
			this.raw = raw;
			this.frames = frames;
		}
	}

	// Merge changed runs separated by <= this many unchanged cells.
	private static final int RUN_MERGE_GAP = 3;

	private final ScreenState shadow;
	private final Map<Integer, Integer> paletteIndex = new HashMap<>();
	private final List<Integer> palette = new ArrayList<>();
	private final ByteIO.Out out = new ByteIO.Out(64 * 1024);
	private boolean open;
	private long lastEventMs;
	private List<FrameInfo> frames = new ArrayList<>();
	private FrameInfo lastFrame;

	public FrameChunkEncoder(int rows, int cols)
	{
		shadow = new ScreenState(rows, cols);
		for (int i = 0; i < shadow.chars.length; i++)
			shadow.chars[i] = ' ';
	}

	/** Current state (grid of the last frame + latest side channels). */
	public ScreenState state() { return shadow; }

	public FrameInfo lastFrame() { return lastFrame; }

	public boolean isOpen() { return open; }

	// ------------------------------------------------------------------ frames

	/**
	 * Record one frame. Returns the previous chunk if it was closed to make
	 * room for this frame, else null. The new frame stays in the open chunk so
	 * its flags (e.g. input-wait) can still be updated.
	 */
	public Chunk frame(int frameIdx, long tMs, int turn, int aut,
			char[] chars, int[] fg, int[] bg)
	{
		if (chars.length != shadow.chars.length || fg.length != shadow.fg.length
				|| bg.length != shadow.bg.length)
			throw new IllegalArgumentException("frame size mismatch");

		Chunk closed = null;
		if (open && (frames.size() >= RecFormat.CHUNK_MAX_FRAMES
				|| out.size() >= RecFormat.CHUNK_MAX_RAW_BYTES))
			closed = flush();

		if (!open)
		{
			System.arraycopy(chars, 0, shadow.chars, 0, chars.length);
			System.arraycopy(fg, 0, shadow.fg, 0, fg.length);
			System.arraycopy(bg, 0, shadow.bg, 0, bg.length);
			writeKeyframe(frameIdx, tMs);
			lastFrame = new FrameInfo(frameIdx, tMs, turn, aut, RecFormat.FRAME_KEY_IN_CHUNK);
		}
		else
		{
			writeDelta(frameIdx, tMs, chars, fg, bg);
			lastFrame = new FrameInfo(frameIdx, tMs, turn, aut, 0);
		}
		frames.add(lastFrame);
		return closed;
	}

	/** Close the open chunk (on save, pause, session end). Null if none open. */
	public Chunk flush()
	{
		if (!open)
			return null;
		Chunk c = new Chunk(frames.get(0).frame, frames.get(frames.size() - 1).frame,
				out.toByteArray(), frames);
		out.reset();
		frames = new ArrayList<>();
		open = false;
		return c;
	}

	private void writeKeyframe(int frameIdx, long tMs)
	{
		out.reset();
		paletteIndex.clear();
		palette.clear();
		// Collect palette from the grid first so the keyframe is self-describing.
		for (int i = 0; i < shadow.fg.length; i++)
		{
			idx(shadow.fg[i]);
			idx(shadow.bg[i]);
		}
		out.u32(RecFormat.CHUNK_MAGIC);
		out.u8(RecFormat.CHUNK_VERSION);
		out.u8(RecFormat.EV_KEYFRAME).varint(0);
		out.varint(tMs).varint(frameIdx).varint(shadow.rows).varint(shadow.cols);
		out.varint(palette.size());
		for (int argb : palette)
			out.u32(argb);
		for (int i = 0; i < shadow.chars.length; i++)
			cell(i);
		writeSideChannels();
		lastEventMs = tMs;
		open = true;
	}

	private void writeSideChannels()
	{
		ScreenState s = shadow;
		out.str(s.hudTitle).ints(s.hudTitleColours);
		out.str(s.hudVitals).ints(s.hudVitalsColours);
		out.ints(s.hudHp).ints(s.hudMp);
		out.str(s.statusLights).ints(s.statusColours);
		out.u8(s.msgHistoryMode ? 1 : 0);
		msgHistory(s.msgHistChars, s.msgHistFg, s.msgHistBg);
		out.u8(s.charLogMode ? 1 : 0);
		out.svarint(s.mapAnchorCol).svarint(s.mapAnchorRow);
		out.ints(s.layout);
	}

	private void writeDelta(int frameIdx, long tMs, char[] chars, int[] fg, int[] bg)
	{
		// Pass 1: find runs and register new colours (emitted before the delta).
		List<int[]> runs = new ArrayList<>();
		int n = chars.length;
		int i = 0;
		while (i < n)
		{
			if (same(i, chars, fg, bg)) { i++; continue; }
			int start = i;
			int end = i + 1; // exclusive
			int j = end;
			while (j < n)
			{
				if (!same(j, chars, fg, bg)) { end = j + 1; j++; continue; }
				if (j - end >= RUN_MERGE_GAP) break;
				j++;
			}
			runs.add(new int[] { start, end - start });
			i = end;
		}
		for (int[] r : runs)
			for (int k = r[0]; k < r[0] + r[1]; k++)
			{
				newColour(fg[k], tMs);
				newColour(bg[k], tMs);
			}

		header(RecFormat.EV_DELTA, tMs);
		out.varint(frameIdx).varint(runs.size());
		int prevEnd = 0;
		for (int[] r : runs)
		{
			out.varint(r[0] - prevEnd).varint(r[1]);
			for (int k = r[0]; k < r[0] + r[1]; k++)
			{
				shadow.chars[k] = chars[k];
				shadow.fg[k] = fg[k];
				shadow.bg[k] = bg[k];
				cell(k);
			}
			prevEnd = r[0] + r[1];
		}
	}

	private boolean same(int i, char[] chars, int[] fg, int[] bg)
	{
		return shadow.chars[i] == chars[i] && shadow.fg[i] == fg[i] && shadow.bg[i] == bg[i];
	}

	private void cell(int i)
	{
		out.varint(shadow.chars[i]).varint(paletteIndex.get(shadow.fg[i]))
				.varint(paletteIndex.get(shadow.bg[i]));
	}

	private int idx(int argb)
	{
		Integer k = paletteIndex.get(argb);
		if (k != null)
			return k;
		int v = palette.size();
		palette.add(argb);
		paletteIndex.put(argb, v);
		return v;
	}

	private void newColour(int argb, long tMs)
	{
		if (paletteIndex.containsKey(argb))
			return;
		int v = idx(argb);
		header(RecFormat.EV_PALETTE_ADD, tMs);
		out.varint(v).u32(argb);
	}

	private void header(int type, long tMs)
	{
		long dt = Math.max(0, tMs - lastEventMs);
		lastEventMs = Math.max(lastEventMs, tMs);
		out.u8(type).varint(dt);
	}

	private void msgHistory(char[] c, int[] f, int[] b)
	{
		if (c == null)
		{
			out.varint(0);
			return;
		}
		out.varint(c.length + 1L);
		for (int k = 0; k < c.length; k++)
			out.varint(c[k]).svarint(f[k]).svarint(b[k]);
	}

	// ----------------------------------------------------------- side channels
	// Always update the shadow; write an event only if a chunk is open (a new
	// chunk's keyframe captures the shadow anyway). HUD calls repeat unchanged
	// values on most refreshes, so those are dropped.

	public void hudTitle(long tMs, String texts, int[] colours)
	{
		if (Objects.equals(texts, shadow.hudTitle)
				&& Arrays.equals(colours, shadow.hudTitleColours))
			return;
		shadow.hudTitle = texts;
		shadow.hudTitleColours = colours == null ? null : colours.clone();
		if (!open) return;
		header(RecFormat.EV_HUD_TITLE, tMs);
		out.str(texts).ints(colours);
	}

	public void hudVitals(long tMs, String texts, int[] colours)
	{
		if (Objects.equals(texts, shadow.hudVitals)
				&& Arrays.equals(colours, shadow.hudVitalsColours))
			return;
		shadow.hudVitals = texts;
		shadow.hudVitalsColours = colours == null ? null : colours.clone();
		if (!open) return;
		header(RecFormat.EV_HUD_VITALS, tMs);
		out.str(texts).ints(colours);
	}

	public void hudBars(long tMs, int[] hp, int[] mp)
	{
		if (Arrays.equals(hp, shadow.hudHp) && Arrays.equals(mp, shadow.hudMp))
			return;
		shadow.hudHp = hp == null ? null : hp.clone();
		shadow.hudMp = mp == null ? null : mp.clone();
		if (!open) return;
		header(RecFormat.EV_HUD_BARS, tMs);
		out.ints(hp).ints(mp);
	}

	public void statusLights(long tMs, String texts, int[] colours)
	{
		if (Objects.equals(texts, shadow.statusLights)
				&& Arrays.equals(colours, shadow.statusColours))
			return;
		shadow.statusLights = texts;
		shadow.statusColours = colours == null ? null : colours.clone();
		if (!open) return;
		header(RecFormat.EV_STATUS_LIGHTS, tMs);
		out.str(texts).ints(colours);
	}

	public void messageHistoryMode(long tMs, boolean active)
	{
		shadow.msgHistoryMode = active;
		if (!open) return;
		header(RecFormat.EV_MSG_HIST_MODE, tMs);
		out.u8(active ? 1 : 0);
	}

	public void messageHistory(long tMs, char[] chars, int[] fg, int[] bg)
	{
		shadow.msgHistChars = chars == null ? null : chars.clone();
		shadow.msgHistFg = fg == null ? null : fg.clone();
		shadow.msgHistBg = bg == null ? null : bg.clone();
		if (!open) return;
		header(RecFormat.EV_MSG_HIST_CONTENT, tMs);
		msgHistory(chars, fg, bg);
	}

	public void characterLogMode(long tMs, boolean active)
	{
		shadow.charLogMode = active;
		if (!open) return;
		header(RecFormat.EV_CHARLOG_MODE, tMs);
		out.u8(active ? 1 : 0);
	}

	public void mapAnchor(long tMs, int col, int row)
	{
		shadow.mapAnchorCol = col;
		shadow.mapAnchorRow = row;
		if (!open) return;
		header(RecFormat.EV_MAP_ANCHOR, tMs);
		out.svarint(col).svarint(row);
	}

	public void layout(long tMs, int[] layout)
	{
		int[] l = new int[RecFormat.LAYOUT_SIZE];
		System.arraycopy(layout, 0, l, 0, Math.min(layout.length, l.length));
		shadow.layout = l;
		if (!open) return;
		header(RecFormat.EV_LAYOUT, tMs);
		out.ints(l);
	}
}
