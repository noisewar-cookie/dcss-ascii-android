package com.crawlmb.recording;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class Mp4TagsTest
{
	private static byte[] box(String type, byte[]... parts)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int size = 8;
		for (byte[] p : parts)
			size += p.length;
		out.writeBytes(ByteBuffer.allocate(4).putInt(size).array());
		out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
		for (byte[] p : parts)
			out.writeBytes(p);
		return out.toByteArray();
	}

	private static byte[] cat(byte[]... parts)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts)
			out.writeBytes(p);
		return out.toByteArray();
	}

	private static int indexOf(byte[] hay, byte[] needle)
	{
		outer:
		for (int i = 0; i + needle.length <= hay.length; i++)
		{
			for (int j = 0; j < needle.length; j++)
				if (hay[i + j] != needle[j])
					continue outer;
			return i;
		}
		return -1;
	}

	// moov before mdat (the layout that needs offsets shifted), with an
	// Android-style moov-level meta that must not survive.
	private static File sample() throws Exception
	{
		byte[] ftyp = box("ftyp", "isom".getBytes(StandardCharsets.ISO_8859_1), new byte[4]);
		byte[] androidMeta = box("meta", new byte[4],
				"com.android.version 14".getBytes(StandardCharsets.ISO_8859_1));
		byte[] payload = "FRAME".getBytes(StandardCharsets.ISO_8859_1);
		ByteBuffer stco = ByteBuffer.allocate(12).putInt(0).putInt(1);
		// The moov's size doesn't depend on the offset value, so measure first.
		int moovSize = box("moov", box("mvhd", new byte[8]), androidMeta,
				box("trak", box("mdia", box("minf", box("stbl", box("stco", stco.array()))))))
				.length;
		stco.putInt(8, ftyp.length + moovSize + 8);
		byte[] moov = box("moov", box("mvhd", new byte[8]), androidMeta,
				box("trak", box("mdia", box("minf", box("stbl", box("stco", stco.array()))))));
		File f = File.createTempFile("tags", ".mp4");
		f.deleteOnExit();
		Files.write(f.toPath(), cat(ftyp, moov, box("mdat", payload)));
		return f;
	}

	@Test
	public void tagsReplaceAndroidMetaAndKeepOffsets() throws Exception
	{
		File f = sample();
		Mp4Tags.write(f, "Buddies - DCSS 0.34.1", "Buddies, Dungeon Crawl Stone Soup 0.34.1");
		byte[] out = Files.readAllBytes(f.toPath());

		assertEquals(-1, indexOf(out, "com.android".getBytes(StandardCharsets.ISO_8859_1)));
		assertTrue(indexOf(out, "Buddies - DCSS 0.34.1".getBytes(StandardCharsets.UTF_8)) > 0);
		assertTrue(indexOf(out, "Stone Soup 0.34.1".getBytes(StandardCharsets.UTF_8)) > 0);
		int stco = indexOf(out, "stco".getBytes(StandardCharsets.ISO_8859_1));
		int offset = ByteBuffer.wrap(out).getInt(stco + 12);
		assertEquals("FRAME", new String(out, offset, 5, StandardCharsets.ISO_8859_1));
		// Top-level sizes still tile the file.
		int at = 0;
		while (at < out.length)
			at += ByteBuffer.wrap(out).getInt(at);
		assertEquals(out.length, at);
	}

	@Test
	public void noIdentityStripsOnly() throws Exception
	{
		File f = sample();
		Mp4Tags.write(f, null, null);
		byte[] out = Files.readAllBytes(f.toPath());
		assertEquals(-1, indexOf(out, "com.android".getBytes(StandardCharsets.ISO_8859_1)));
		assertEquals(-1, indexOf(out, "udta".getBytes(StandardCharsets.ISO_8859_1)));
		int stco = indexOf(out, "stco".getBytes(StandardCharsets.ISO_8859_1));
		int offset = ByteBuffer.wrap(out).getInt(stco + 12);
		assertEquals("FRAME", new String(out, offset, 5, StandardCharsets.ISO_8859_1));
	}
}
