package com.crawlmb.recording;

import static org.junit.Assert.*;

import com.crawlmb.recording.ui.ReplayScreens;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReplayScreensTest
{
	private final List<String> toasts = new ArrayList<>();
	private final Map<String, String> blobs = new HashMap<>();

	private ReplayScreens screens(String docJson, Map<String, String> panels)
	{
		KnowledgeResolver.Knowledge k = new KnowledgeResolver.Knowledge(null,
				MiniJson.parseObject(docJson), panels, null);
		return new ReplayScreens(toasts::add, blobs::get, k);
	}

	private static String row(ScreenState s, int r)
	{
		return new String(s.chars, r * s.cols, s.cols).replaceAll("\\s+$", "");
	}

	private static String screen(ReplayScreens rs)
	{
		ScreenState recorded = new ScreenState(72, 80);
		recorded.msgHistoryMode = true;
		ScreenState s = rs.apply(recorded);
		assertFalse(s.msgHistoryMode);
		StringBuilder b = new StringBuilder();
		for (int r = 0; r < s.rows; r++)
			b.append(row(s, r)).append('\n');
		return b.toString();
	}

	@Test
	public void noDataNeverCrashesAndSaysSo()
	{
		ReplayScreens rs = screens("{}", new HashMap<String, String>());
		for (ReplayScreens.Kind kind : ReplayScreens.Kind.values())
		{
			rs.open(kind);
			assertTrue(rs.isOpen());
			assertFalse(screen(rs).trim().isEmpty());
		}
		rs.open(ReplayScreens.Kind.CHARACTER);
		assertTrue(screen(rs).contains(ReplayScreens.NOT_RECORDED));
		rs.onKey('^'); // religion before any god: the reported crash
		assertTrue(screen(rs).startsWith("Religion\n\n" + ReplayScreens.NOT_RECORDED));

		Map<String, String> panels = new HashMap<>();
		panels.put("religion", "h0");
		blobs.put("h0", "\n");
		rs = screens("{}", panels);
		rs.open(ReplayScreens.Kind.CHARACTER);
		rs.onKey('^'); // recorded, but no god yet
		assertTrue(screen(rs).startsWith("Religion\n\nYou are not religious."));

		rs.open(ReplayScreens.Kind.INVENTORY);
		assertTrue(screen(rs).contains("You aren't carrying anything."));
		rs.onKey('a'); // no item a
		assertTrue(screen(rs).startsWith("Inventory: 0 items"));
	}

	@Test
	public void inventoryUsesCrawlLayoutAndDescribes()
	{
		blobs.put("h1", "a - a +0 dagger\n\nA dagger.\nA short blade.");
		// "s" is index_to_letter; the real letter is in the DESC_INVENTORY name.
		ReplayScreens rs = screens("{\"inv\":[{\"s\":\"[\",\"n\":\"a - a +0 dagger\",\"q\":1,\"d\":\"h1\"}]}",
				new HashMap<String, String>());
		rs.open(ReplayScreens.Kind.INVENTORY);
		String s = screen(rs);
		assertTrue(s.startsWith("Inventory: 1 item\na - a +0 dagger\n"));

		rs.onKey('a');
		s = screen(rs);
		assertTrue(s, s.startsWith("a - a +0 dagger\n\nA dagger.\nA short blade.\n"));
		rs.onKey('d'); // drop = edit
		assertEquals(1, toasts.size());

		rs.onKey(ReplayScreens.KEY_ESCAPE);
		assertTrue(screen(rs).startsWith("Inventory:"));
		rs.onKey(ReplayScreens.KEY_ESCAPE);
		assertFalse(rs.isOpen());
	}

	@Test
	public void examineChoicePicksOneTarget()
	{
		ReplayScreens rs = screens("{}", new HashMap<String, String>());
		List<String[]> t = new ArrayList<>();
		t.add(new String[] { "An orc", "An orc.\nUgly." });
		t.add(new String[] { "Floor", "The floor." });
		rs.openChoice(t, false);
		assertTrue(screen(rs).startsWith("Describe what?\n\na - An orc\nb - Floor\n"));
		rs.onKey('b');
		assertTrue(screen(rs).startsWith("Floor\n\nThe floor."));
		rs.onKey(ReplayScreens.KEY_ESCAPE);
		assertTrue(screen(rs).startsWith("Describe what?"));
	}

	@Test
	public void longTextPagesAndColourTagsAreStripped()
	{
		StringBuilder text = new StringBuilder("<lightred>Red</lightred> <<tag>\n");
		for (int i = 0; i < 60; i++)
			text.append("line ").append(i).append('\n');
		ReplayScreens rs = screens("{}", new HashMap<String, String>());
		rs.openDescription("Title", text.toString());
		String s = screen(rs);
		assertTrue(s.startsWith("Title\n\nRed <tag>\nline 0\n"));
		assertTrue(s.contains("(1/3)"));
		rs.onKey('+');
		assertTrue(screen(rs).contains("(2/3)"));
		rs.onKey('-');
		assertTrue(screen(rs).contains("(1/3)"));
	}

	private static int fg(ReplayScreens rs, int row, int col)
	{
		ScreenState s = rs.apply(new ScreenState(72, 80));
		return s.fg[row * s.cols + col];
	}

	@Test
	public void screensUseGameColours()
	{
		ReplayScreens rs = screens("{\"inv\":["
				+ "{\"n\":\"a - a +0 dagger (weapon)\",\"c\":\"lightgrey\",\"k\":\"Hand Weapons\"},"
				+ "{\"n\":\"b - a scroll <x>\",\"c\":\"red\",\"k\":\"Scrolls\"}],"
				+ "\"abil\":["
				+ "{\"n\":\"Pray\",\"s\":\"p\",\"l\":\"Pray  None  0%\",\"c\":\"darkgrey\",\"i\":1},"
				+ "{\"n\":\"Evoke\",\"s\":\"a\",\"l\":\"Evoke  None  0%\",\"c\":\"lightgrey\",\"i\":0}]}",
				new HashMap<String, String>());
		rs.open(ReplayScreens.Kind.INVENTORY);
		assertTrue(screen(rs).startsWith("Inventory: 2 items\nHand Weapons\na - a +0 dagger (weapon)\n"
				+ "Scrolls\nb - a scroll <x>\n"));
		assertEquals(0xFFFFFFFF, fg(rs, 0, 0)); // MEL_TITLE white
		assertEquals(0xFF0040FF, fg(rs, 1, 0)); // MEL_SUBTITLE blue
		assertEquals(0xFFFF4040, fg(rs, 4, 0));

		rs.open(ReplayScreens.Kind.ABILITIES);
		assertTrue(screen(rs).contains("\na - Evoke  None  0%\n Invocations -\np - Pray  None  0%\n"));
		assertEquals(0xFF606060, fg(rs, 3, 0));
		rs.onKey('p');
		assertTrue(screen(rs).startsWith("Pray\n"));
	}

	@Test
	public void closingTagRestoresOuterColour()
	{
		ReplayScreens rs = screens("{}", new HashMap<String, String>());
		rs.openDescription("T", "<red>a<w>b</w>c</red>d");
		assertEquals(0xFFFF4040, fg(rs, 2, 0));
		assertEquals(0xFFFFFFFF, fg(rs, 2, 1));
		assertEquals(0xFFFF4040, fg(rs, 2, 2));
		assertEquals(com.crawlmb.recording.ui.CrawlText.LIGHTGRAY, fg(rs, 2, 3));
	}
}
