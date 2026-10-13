package com.crawlmb.recording;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Varint/zigzag byte helpers for the recording codec. */
public final class ByteIO
{
	private ByteIO() {}

	/** Growable output buffer with varint helpers. */
	public static final class Out
	{
		private final ByteArrayOutputStream buf;

		public Out(int initial) { buf = new ByteArrayOutputStream(initial); }

		public int size() { return buf.size(); }
		public byte[] toByteArray() { return buf.toByteArray(); }
		public void reset() { buf.reset(); }

		public Out u8(int v) { buf.write(v & 0xff); return this; }

		public Out u32(int v)
		{
			buf.write(v >>> 24); buf.write(v >>> 16); buf.write(v >>> 8); buf.write(v);
			return this;
		}

		public Out varint(long v)
		{
			if (v < 0)
				throw new IllegalArgumentException("varint must be >= 0: " + v);
			while ((v & ~0x7FL) != 0)
			{
				buf.write((int) ((v & 0x7F) | 0x80));
				v >>>= 7;
			}
			buf.write((int) v);
			return this;
		}

		public Out svarint(long v) { return varint((v << 1) ^ (v >> 63)); }

		public Out bytes(byte[] b)
		{
			varint(b.length);
			buf.write(b, 0, b.length);
			return this;
		}

		public Out raw(byte[] b) { buf.write(b, 0, b.length); return this; }

		/** Null-safe string: length+1 (0 = null) then UTF-8. */
		public Out str(String s)
		{
			if (s == null)
				return varint(0);
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			varint(b.length + 1L);
			buf.write(b, 0, b.length);
			return this;
		}

		/** Null-safe int array: length+1 (0 = null) then zigzag varints. */
		public Out ints(int[] a)
		{
			if (a == null)
				return varint(0);
			varint(a.length + 1L);
			for (int v : a)
				svarint(v);
			return this;
		}
	}

	/** Cursor over a byte array. Throws {@link FormatException} on overrun. */
	public static final class In
	{
		private final byte[] b;
		private int pos;
		private final int end;

		public In(byte[] b) { this(b, 0, b.length); }
		public In(byte[] b, int off, int len) { this.b = b; this.pos = off; this.end = off + len; }

		public boolean hasMore() { return pos < end; }
		public int position() { return pos; }

		public int u8()
		{
			need(1);
			return b[pos++] & 0xff;
		}

		public int u32()
		{
			need(4);
			int v = ((b[pos] & 0xff) << 24) | ((b[pos + 1] & 0xff) << 16)
					| ((b[pos + 2] & 0xff) << 8) | (b[pos + 3] & 0xff);
			pos += 4;
			return v;
		}

		public long u64()
		{
			long hi = u32() & 0xffffffffL;
			long lo = u32() & 0xffffffffL;
			return (hi << 32) | lo;
		}

		public long varint()
		{
			long result = 0;
			for (int shift = 0; shift < 64; shift += 7)
			{
				int c = u8();
				result |= (long) (c & 0x7F) << shift;
				if ((c & 0x80) == 0)
					return result;
			}
			throw new FormatException("varint too long");
		}

		public int varintInt(int max)
		{
			long v = varint();
			if (v > max)
				throw new FormatException("value " + v + " exceeds bound " + max);
			return (int) v;
		}

		public long svarint()
		{
			long v = varint();
			return (v >>> 1) ^ -(v & 1);
		}

		public byte[] raw(int n)
		{
			need(n);
			byte[] r = Arrays.copyOfRange(b, pos, pos + n);
			pos += n;
			return r;
		}

		public byte[] bytes(int maxLen)
		{
			return raw(varintInt(maxLen));
		}

		public void skip(int n) { need(n); pos += n; }

		public String str(int maxLen)
		{
			int n = varintInt(maxLen + 1);
			if (n == 0)
				return null;
			need(n - 1);
			String s = new String(b, pos, n - 1, StandardCharsets.UTF_8);
			pos += n - 1;
			return s;
		}

		public int[] ints(int maxLen)
		{
			int n = varintInt(maxLen + 1);
			if (n == 0)
				return null;
			int[] a = new int[n - 1];
			for (int i = 0; i < a.length; i++)
				a[i] = (int) svarint();
			return a;
		}

		private void need(int n)
		{
			if (n < 0 || pos + n > end)
				throw new FormatException("unexpected end of data");
		}
	}

	/** Malformed recording data. Unchecked so decoders stay readable. */
	public static final class FormatException extends RuntimeException
	{
		public FormatException(String msg) { super(msg); }
	}
}
