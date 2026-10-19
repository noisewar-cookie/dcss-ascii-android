package com.crawlmb.recording;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.zip.Adler32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** zlib helpers with an inflate size cap (zip-bomb guard for imports). */
public final class Deflate
{
	public static final int DEFAULT_MAX_INFLATED = 64 * 1024 * 1024;
	public static final int WINDOW = 32 * 1024; // deflate can't reach further back

	private Deflate() {}

	public static byte[] deflate(byte[] in)
	{
		return deflate(in, null);
	}

	/** With a preset dictionary; the zlib FDICT header bit marks the output. */
	public static byte[] deflate(byte[] in, byte[] dict)
	{
		Deflater d = new Deflater(6);
		try
		{
			if (dict != null)
				d.setDictionary(dict);
			d.setInput(in);
			d.finish();
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, in.length / 4));
			byte[] buf = new byte[16 * 1024];
			while (!d.finished())
				out.write(buf, 0, d.deflate(buf));
			return out.toByteArray();
		}
		finally
		{
			d.end();
		}
	}

	/**
	 * Preset dictionary from the file dictionary (may be null) followed by
	 * consecutive docs, oldest first: their last 32 KB.
	 */
	public static byte[] windowDict(byte[] fileDict, Collection<byte[]> docs)
	{
		if (docs.isEmpty())
			return fileDict;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (fileDict != null)
			out.write(fileDict, 0, fileDict.length);
		for (byte[] d : docs)
			out.write(d, 0, d.length);
		byte[] all = out.toByteArray();
		return all.length <= WINDOW ? all : Arrays.copyOfRange(all, all.length - WINDOW, all.length);
	}

	public static byte[] inflate(byte[] in)
	{
		return inflate(in, DEFAULT_MAX_INFLATED);
	}

	public static byte[] inflate(byte[] in, int maxBytes)
	{
		return inflate(in, maxBytes, null);
	}

	/** True if the stream was deflated with a preset dictionary (FDICT). */
	public static boolean usesDictionary(byte[] in)
	{
		return in.length > 1 && (in[1] & 0x20) != 0;
	}

	public static long adler(byte[] dict)
	{
		Adler32 a = new Adler32();
		a.update(dict);
		return a.getValue();
	}

	/**
	 * True if the doc starts a chain: no preset dictionary, or exactly the
	 * file dictionary (fileDictAdler, -1 for none).
	 */
	public static boolean startsDocChain(byte[] doc, long fileDictAdler)
	{
		if (!usesDictionary(doc))
			return true;
		if (fileDictAdler < 0 || doc.length < 6)
			return false;
		long id = ((doc[2] & 0xFFL) << 24) | ((doc[3] & 0xFF) << 16) | ((doc[4] & 0xFF) << 8)
				| (doc[5] & 0xFF);
		return id == fileDictAdler;
	}

	public static byte[] inflate(byte[] in, int maxBytes, byte[] dict)
	{
		Inflater inf = new Inflater();
		try
		{
			inf.setInput(in);
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, in.length * 4));
			byte[] buf = new byte[16 * 1024];
			while (!inf.finished())
			{
				int n = inf.inflate(buf);
				if (n == 0 && inf.needsDictionary() && dict != null)
				{
					// Throws IllegalArgumentException on an Adler-32 mismatch.
					inf.setDictionary(dict);
					dict = null;
					continue;
				}
				// An empty payload finishes with n == 0 and needsInput() true.
				if (n == 0 && !inf.finished()
						&& (inf.needsInput() || inf.needsDictionary()))
					throw new ByteIO.FormatException("truncated deflate stream");
				out.write(buf, 0, n);
				if (out.size() > maxBytes)
					throw new ByteIO.FormatException("inflated data exceeds " + maxBytes + " bytes");
			}
			return out.toByteArray();
		}
		catch (DataFormatException | IllegalArgumentException e)
		{
			throw new ByteIO.FormatException("corrupt deflate stream: " + e.getMessage());
		}
		finally
		{
			inf.end();
		}
	}
}
