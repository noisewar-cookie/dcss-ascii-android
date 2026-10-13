package com.crawlmb.recording;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes one Screen-track chunk (raw, already inflated) into a full
 * ScreenState per frame. All sizes are bounded so a hostile file can't
 * exhaust memory.
 */
public final class FrameChunkDecoder
{
	public static final int MAX_ROWS = 256;
	public static final int MAX_COLS = 256;
	public static final int MAX_PALETTE = 1 << 16;
	public static final int MAX_STRING = 1 << 16;
	public static final int MAX_INTS = 1 << 12;
	public static final int MAX_MSG_HIST_CELLS = 4 * 1024 * 1024;

	public static final class DecodedFrame
	{
		public final int frame;
		public final long tMs;
		public final ScreenState state;

		DecodedFrame(int frame, long tMs, ScreenState state)
		{
			this.frame = frame;
			this.tMs = tMs;
			this.state = state;
		}
	}

	private FrameChunkDecoder() {}

	public static List<DecodedFrame> decode(byte[] raw)
	{
		ByteIO.In in = new ByteIO.In(raw);
		if (in.u32() != RecFormat.CHUNK_MAGIC)
			throw new ByteIO.FormatException("bad chunk magic");
		int version = in.u8();
		if (version > RecFormat.CHUNK_VERSION)
			throw new ByteIO.FormatException("chunk version " + version + " is newer than reader");

		if (in.u8() != RecFormat.EV_KEYFRAME)
			throw new ByteIO.FormatException("chunk must start with a keyframe");
		in.varint(); // dt, always 0
		long t = in.varint();
		int frame = in.varintInt(Integer.MAX_VALUE);
		int rows = in.varintInt(MAX_ROWS);
		int cols = in.varintInt(MAX_COLS);
		List<Integer> palette = new ArrayList<>();
		int np = in.varintInt(MAX_PALETTE);
		for (int i = 0; i < np; i++)
			palette.add(in.u32());

		ScreenState s = new ScreenState(rows, cols);
		for (int i = 0; i < rows * cols; i++)
			readCell(in, s, i, palette);
		readSideChannels(in, s);

		List<DecodedFrame> out = new ArrayList<>();
		out.add(new DecodedFrame(frame, t, s.copy()));

		while (in.hasMore())
		{
			int type = in.u8();
			t += in.varint();
			switch (type)
			{
			case RecFormat.EV_DELTA:
			{
				frame = in.varintInt(Integer.MAX_VALUE);
				int runs = in.varintInt(rows * cols);
				int pos = 0;
				for (int r = 0; r < runs; r++)
				{
					pos += in.varintInt(rows * cols);
					int len = in.varintInt(rows * cols);
					if (pos + len > rows * cols)
						throw new ByteIO.FormatException("delta run out of bounds");
					for (int k = pos; k < pos + len; k++)
						readCell(in, s, k, palette);
					pos += len;
				}
				out.add(new DecodedFrame(frame, t, s.copy()));
				break;
			}
			case RecFormat.EV_PALETTE_ADD:
			{
				int idx = in.varintInt(MAX_PALETTE);
				int argb = in.u32();
				if (idx != palette.size())
					throw new ByteIO.FormatException("palette index out of order");
				palette.add(argb);
				break;
			}
			case RecFormat.EV_HUD_TITLE:
				s.hudTitle = in.str(MAX_STRING);
				s.hudTitleColours = in.ints(MAX_INTS);
				break;
			case RecFormat.EV_HUD_VITALS:
				s.hudVitals = in.str(MAX_STRING);
				s.hudVitalsColours = in.ints(MAX_INTS);
				break;
			case RecFormat.EV_HUD_BARS:
				s.hudHp = in.ints(MAX_INTS);
				s.hudMp = in.ints(MAX_INTS);
				break;
			case RecFormat.EV_STATUS_LIGHTS:
				s.statusLights = in.str(MAX_STRING);
				s.statusColours = in.ints(MAX_INTS);
				break;
			case RecFormat.EV_MSG_HIST_MODE:
				s.msgHistoryMode = in.u8() != 0;
				break;
			case RecFormat.EV_MSG_HIST_CONTENT:
				readMsgHistory(in, s);
				break;
			case RecFormat.EV_CHARLOG_MODE:
				s.charLogMode = in.u8() != 0;
				break;
			case RecFormat.EV_MAP_ANCHOR:
				s.mapAnchorCol = (int) in.svarint();
				s.mapAnchorRow = (int) in.svarint();
				break;
			case RecFormat.EV_LAYOUT:
				s.layout = fixLayout(in.ints(MAX_INTS));
				break;
			default:
				if (type >= RecFormat.EV_SKIPPABLE)
				{
					in.skip(in.varintInt(Integer.MAX_VALUE));
					break;
				}
				throw new ByteIO.FormatException("unknown event type " + type);
			}
		}
		return out;
	}

	private static void readCell(ByteIO.In in, ScreenState s, int i, List<Integer> palette)
	{
		s.chars[i] = (char) in.varintInt(0xFFFF);
		s.fg[i] = colour(palette, in.varintInt(MAX_PALETTE));
		s.bg[i] = colour(palette, in.varintInt(MAX_PALETTE));
	}

	private static int colour(List<Integer> palette, int idx)
	{
		if (idx >= palette.size())
			throw new ByteIO.FormatException("palette index " + idx + " undefined");
		return palette.get(idx);
	}

	private static void readSideChannels(ByteIO.In in, ScreenState s)
	{
		s.hudTitle = in.str(MAX_STRING);
		s.hudTitleColours = in.ints(MAX_INTS);
		s.hudVitals = in.str(MAX_STRING);
		s.hudVitalsColours = in.ints(MAX_INTS);
		s.hudHp = in.ints(MAX_INTS);
		s.hudMp = in.ints(MAX_INTS);
		s.statusLights = in.str(MAX_STRING);
		s.statusColours = in.ints(MAX_INTS);
		s.msgHistoryMode = in.u8() != 0;
		readMsgHistory(in, s);
		s.charLogMode = in.u8() != 0;
		s.mapAnchorCol = (int) in.svarint();
		s.mapAnchorRow = (int) in.svarint();
		s.layout = fixLayout(in.ints(MAX_INTS));
	}

	private static void readMsgHistory(ByteIO.In in, ScreenState s)
	{
		int n = in.varintInt(MAX_MSG_HIST_CELLS + 1);
		if (n == 0)
		{
			s.msgHistChars = null;
			s.msgHistFg = null;
			s.msgHistBg = null;
			return;
		}
		n -= 1;
		char[] c = new char[n];
		int[] f = new int[n];
		int[] b = new int[n];
		for (int k = 0; k < n; k++)
		{
			c[k] = (char) in.varintInt(0xFFFF);
			f[k] = (int) in.svarint();
			b[k] = (int) in.svarint();
		}
		s.msgHistChars = c;
		s.msgHistFg = f;
		s.msgHistBg = b;
	}

	private static int[] fixLayout(int[] l)
	{
		int[] out = new int[RecFormat.LAYOUT_SIZE];
		if (l != null)
			System.arraycopy(l, 0, out, 0, Math.min(l.length, out.length));
		return out;
	}
}
