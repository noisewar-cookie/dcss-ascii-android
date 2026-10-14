package com.crawlmb.recording;

import static org.junit.Assert.*;

import org.junit.Test;

public class LevelMapTest
{
	private static void apply(LevelMap m, String json)
	{
		m.apply(MiniJson.parseObject(json));
	}

	@Test
	public void losSetsVisibilityAndClearRemovesKnowledge()
	{
		LevelMap m = new LevelMap("D:1", 80, 70);
		String t = TestSupport.hash(1);
		apply(m, "{\"key\":1,\"cells\":[[5,5,46,7,1,\"" + t + "\",0,0],"
				+ "[6,5,46,7,1,\"" + t + "\",0,0]]," + TestSupport.los(0, 0, 17, new int[] { 5, 5 }) + "}");
		assertTrue((m.get(5, 5).flags & ExamineCursor.MAP_VISIBLE) != 0);
		assertEquals(0, m.get(6, 5).flags & ExamineCursor.MAP_VISIBLE);
		assertEquals(1, m.get(5, 5).flags & ~ExamineCursor.MAP_VISIBLE);
		assertNull(m.get(5, 5).featDescHash);

		// Delta: (5,5) leaves LOS without a cell entry; (6,5) is forgotten.
		apply(m, "{\"key\":0,\"cells\":[[6,5]]," + TestSupport.los(0, 0, 17) + "}");
		assertEquals(0, m.get(5, 5).flags & ExamineCursor.MAP_VISIBLE);
		assertNull(m.get(6, 5));
	}
}
