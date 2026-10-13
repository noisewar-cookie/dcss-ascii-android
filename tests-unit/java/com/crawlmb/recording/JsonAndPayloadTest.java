package com.crawlmb.recording;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class JsonAndPayloadTest
{
	@Test
	public void parsesNativeStyleJson()
	{
		Map<String, Object> m = MiniJson.parseObject(
				"{\"a\":1,\"b\":\"x\\\"y\\n\\u00e9\",\"c\":[1,2,{\"d\":true}],\"e\":null,\"f\":-2.5}");
		assertEquals(1L, MiniJson.getLong(m, "a", 0));
		assertEquals("x\"y\n\u00e9", MiniJson.getString(m, "b", null));
		List<Object> c = MiniJson.getArray(m, "c");
		assertEquals(3, c.size());
		assertNull(m.get("e"));
		assertEquals(-2.5, (Double) m.get("f"), 1e-9);
	}

	@Test
	public void rejectsBadJsonAndDeepNesting()
	{
		String[] bad = { "{", "{\"a\":}", "[1,2", "{\"a\":1}x", "tru" };
		for (String b : bad)
			try
			{
				MiniJson.parse(b);
				fail("should reject " + b);
			}
			catch (ByteIO.FormatException expected) {}
		StringBuilder deep = new StringBuilder();
		for (int i = 0; i < 200; i++) deep.append('[');
		try
		{
			MiniJson.parse(deep.toString());
			fail("should reject deep nesting");
		}
		catch (ByteIO.FormatException expected) {}
	}

	@Test
	public void parsesSnapshotPayload()
	{
		String doc = TestSupport.doc("CP", 12, 120, "D:2", 7, 20, 3,
				"\"panels\":{\"overview\":\"" + TestSupport.hash(1) + "\"},"
						+ "\"map\":{\"level\":\"D:2\",\"key\":1,\"w\":80,\"h\":70,\"cells\":[]}");
		byte[] p = TestSupport.payload(doc, new String[][] {
				{ TestSupport.hash(1), "1", "Overview text \u2603" },
				{ "ffffffffffffffff0000000000000001", "3", "A goblin." } });
		SnapshotPayload s = SnapshotPayload.parse(p);
		assertEquals("CP", s.kind);
		assertEquals(12, s.turn);
		assertEquals(120L, s.aut);
		assertEquals("D:2", s.level);
		assertEquals(7, s.hp);
		assertEquals(3, s.xl);
		assertTrue(s.hasPanels);
		assertTrue(s.mapKeyframe);
		assertEquals(2, s.blobs.size());
		assertEquals(TestSupport.hash(1), s.blobs.get(0).hash);
		assertEquals("ffffffffffffffff0000000000000001", s.blobs.get(1).hash);
		assertEquals(3, s.blobs.get(1).kind);
	}

	@Test
	public void identityFileNameIsSafe()
	{
		GameIdentity id = GameIdentity.parse(
				("{\"name\":\"../Bob the \u00dcber\",\"birth\":1700000000,\"seed\":\"18446744073709551615\","
						+ "\"dcss_version\":\"0.34.1\"}")
						.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals("___Bob_the__ber_18446744073709551615_0.34.1.dcrec", id.fileName(1));
		assertEquals("___Bob_the__ber_18446744073709551615_0.34.1_2.dcrec", id.fileName(2));
		assertEquals("18446744073709551615", id.seed);
	}
}
