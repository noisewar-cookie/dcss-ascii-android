package com.crawlmb.recording;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites a finished MP4's moov: drops the muxer's moov-level meta (Android
 * puts the OS version there) and any udta, then adds an iTunes-style title and
 * comment, the only metadata exports carry.
 */
final class Mp4Tags
{
	private static final int MOOV = fourcc("moov"), MDAT = fourcc("mdat"),
			META = fourcc("meta"), UDTA = fourcc("udta"), STCO = fourcc("stco"),
			CO64 = fourcc("co64");
	// Containers on the path to the chunk offset tables.
	private static final int[] CONTAINERS = { MOOV, fourcc("trak"), fourcc("mdia"),
			fourcc("minf"), fourcc("stbl") };

	private Mp4Tags() {}

	/** title/comment null: strip only. */
	static void write(File mp4, String title, String comment) throws IOException
	{
		File tmp = new File(mp4.getPath() + ".tmp");
		boolean ok = false;
		try
		{
			try (RandomAccessFile in = new RandomAccessFile(mp4, "r");
					OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp)))
			{
				List<long[]> boxes = topLevel(in);
				int moov = -1;
				boolean mdatAfter = false;
				for (int i = 0; i < boxes.size(); i++)
					if (boxes.get(i)[2] == MOOV)
						moov = i;
					else if (boxes.get(i)[2] == MDAT && moov >= 0)
						mdatAfter = true;
				if (moov < 0)
					throw new IOException("no moov box");
				long[] m = boxes.get(moov);
				if (m[1] > Integer.MAX_VALUE)
					throw new IOException("moov too large");
				byte[] old = new byte[(int) m[1]];
				in.seek(m[0]);
				in.readFully(old);
				byte[] moovNew = rebuild(old, title, comment);
				// mdat moves by the size change, so its chunk offsets must too.
				if (mdatAfter)
					shift(ByteBuffer.wrap(moovNew), moovNew.length - old.length);
				byte[] buf = new byte[1 << 16];
				for (int i = 0; i < boxes.size(); i++)
				{
					if (i == moov)
					{
						out.write(moovNew);
						continue;
					}
					in.seek(boxes.get(i)[0]);
					for (long left = boxes.get(i)[1]; left > 0; )
					{
						int n = in.read(buf, 0, (int) Math.min(buf.length, left));
						if (n < 0)
							throw new IOException("truncated");
						out.write(buf, 0, n);
						left -= n;
					}
				}
			}
			if (!tmp.renameTo(mp4))
				throw new IOException("couldn't replace " + mp4.getName());
			ok = true;
		}
		finally
		{
			if (!ok)
				tmp.delete();
		}
	}

	// {offset, size, type} of each top-level box.
	private static List<long[]> topLevel(RandomAccessFile in) throws IOException
	{
		List<long[]> out = new ArrayList<>();
		long len = in.length();
		for (long at = 0; at + 8 <= len; )
		{
			in.seek(at);
			long size = in.readInt() & 0xFFFFFFFFL;
			int type = in.readInt();
			if (size == 1)
				size = in.readLong();
			else if (size == 0)
				size = len - at;
			if (size < 8 || at + size > len)
				throw new IOException("bad box at " + at);
			out.add(new long[] { at, size, type });
			at += size;
		}
		return out;
	}

	private static byte[] rebuild(byte[] moov, String title, String comment)
			throws IOException
	{
		ByteArrayOutputStream kids = new ByteArrayOutputStream();
		ByteBuffer b = ByteBuffer.wrap(moov);
		for (int at = 8; at + 8 <= moov.length; )
		{
			int size = b.getInt(at), type = b.getInt(at + 4);
			if (size < 8 || at + size > moov.length)
				throw new IOException("bad moov child");
			if (type != META && type != UDTA)
				kids.write(moov, at, size);
			at += size;
		}
		if (title != null)
			kids.write(box(UDTA, box(META, new byte[4], hdlr(),
					box(fourcc("ilst"), item("©nam", title), item("©cmt", comment)))));
		return box(MOOV, kids.toByteArray());
	}

	private static void shift(ByteBuffer b, int delta) throws IOException
	{
		shift(b, 8, b.capacity(), delta);
	}

	private static void shift(ByteBuffer b, int from, int to, int delta) throws IOException
	{
		for (int at = from; at + 8 <= to; )
		{
			int size = b.getInt(at), type = b.getInt(at + 4);
			if (size < 8 || at + size > to)
				throw new IOException("bad box in moov");
			if (type == STCO || type == CO64)
			{
				int n = b.getInt(at + 12);
				for (int i = 0; i < n; i++)
					if (type == STCO)
						b.putInt(at + 16 + 4 * i, b.getInt(at + 16 + 4 * i) + delta);
					else
						b.putLong(at + 16 + 8 * i, b.getLong(at + 16 + 8 * i) + delta);
			}
			else
				for (int c : CONTAINERS)
					if (type == c)
						shift(b, at + 8, at + size, delta);
			at += size;
		}
	}

	private static byte[] hdlr() throws IOException
	{
		ByteBuffer b = ByteBuffer.allocate(25);
		b.putInt(0).putInt(0).putInt(fourcc("mdir")).putInt(fourcc("appl"));
		return box(fourcc("hdlr"), b.array());
	}

	private static byte[] item(String key, String text) throws IOException
	{
		ByteBuffer head = ByteBuffer.allocate(8).putInt(1).putInt(0); // UTF-8, no locale
		return box(fourcc(key), box(fourcc("data"), head.array(),
				text.getBytes(StandardCharsets.UTF_8)));
	}

	private static byte[] box(int type, byte[]... parts) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(bytes);
		int size = 8;
		for (byte[] p : parts)
			size += p.length;
		d.writeInt(size);
		d.writeInt(type);
		for (byte[] p : parts)
			d.write(p);
		return bytes.toByteArray();
	}

	// Latin-1, so © ("©") is the single byte 0xA9 these keys use.
	private static int fourcc(String s)
	{
		byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
		return (b[0] & 0xFF) << 24 | (b[1] & 0xFF) << 16 | (b[2] & 0xFF) << 8 | b[3] & 0xFF;
	}
}
