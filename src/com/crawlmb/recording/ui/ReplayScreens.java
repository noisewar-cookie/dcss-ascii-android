package com.crawlmb.recording.ui;

import com.crawlmb.recording.KnowledgeResolver;
import com.crawlmb.recording.MiniJson;
import com.crawlmb.recording.ReplayReadOnly;
import com.crawlmb.recording.ScreenState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Browse-only replay screens laid out like crawl's own (inventory, spells,
 * abilities, %, skills, describe popups) and drawn into the terminal grid as a
 * ReplayController overlay, so RegionRouter classifies, scales and routes
 * them exactly like the live menus. Title rows reuse crawl's anchors
 * ("Inventory:", "Your spells (", "Ability - ") on purpose. Content comes
 * from the Knowledge track at the paused position; edit keys toast instead.
 * Pure Java (no views) so it is unit-testable.
 */
public final class ReplayScreens
{
	public enum Kind { INVENTORY, SPELLS, ABILITIES, CHARACTER, TRAINING }

	public interface Host
	{
		void toast(String msg);
	}

	/** Hash -> text (KnowledgeResolver.blob). Null when missing or unreadable. */
	public interface Blobs
	{
		String get(String hash);
	}

	public static final int KEY_ESCAPE = 27;
	public static final String NOT_RECORDED = "Not recorded at this point.";

	// fullView paints rows 0..27 for prose menus (DEFAULT/DESCRIBE/OVERVIEW).
	static final int PROSE_ROWS = 28;
	// List menus (itemsView / SPELLS) get the whole terminal.
	static final int LIST_ROWS = 70;
	private static final int COLS = 80;
	private static final int BG = 0xFF000000;

	// key, panel, title, text when the panel was recorded empty
	private static final String[][] CHARACTER_KEYS = {
		{ "^", "religion", "Religion", "You are not religious." },
		{ "A", "mutations", "Mutations", "You have no mutations." },
		{ "O", "dungeon", "Dungeon overview", "(none)" },
		{ "N", "notes", "Notes", "(none)" },
		{ "K", "kills", "Kills", "You haven't killed anything." },
	};

	interface KeyHandler
	{
		/** Returns true if consumed. */
		boolean onKey(int c);
	}

	static final class Screen
	{
		final ReplayReadOnly.Screen kind;
		final List<char[]> text = new ArrayList<>();
		final List<int[]> colour = new ArrayList<>();
		final int pageRows;
		int page;
		KeyHandler keys;

		Screen(ReplayReadOnly.Screen kind, int pageRows)
		{
			this.kind = kind;
			this.pageRows = pageRows;
		}

		int bodyRows()
		{
			return text.size() > pageRows ? pageRows - 1 : pageRows;
		}

		int pages()
		{
			return Math.max(1, (text.size() + bodyRows() - 1) / bodyRows());
		}
	}

	private final Host host;
	private final Blobs blobs;
	private final KnowledgeResolver.Knowledge k;
	private final ArrayDeque<Screen> stack = new ArrayDeque<>();
	private int proseCols = COLS;

	public ReplayScreens(Host host, Blobs blobs, KnowledgeResolver.Knowledge k)
	{
		this.host = host;
		this.blobs = blobs;
		this.k = k;
	}

	/** Describe-page wrap width, as the game's prose wrap (0 = 80 cols). */
	public void setProseCols(int cols)
	{
		proseCols = cols > 0 ? Math.min(cols, COLS) : COLS;
	}

	public boolean isOpen()
	{
		return !stack.isEmpty();
	}

	public void open(Kind kind)
	{
		stack.clear();
		switch (kind)
		{
		case INVENTORY: stack.push(inventory()); break;
		case SPELLS: stack.push(spells()); break;
		case ABILITIES: stack.push(abilities()); break;
		case CHARACTER: stack.push(character()); break;
		case TRAINING:
		default: stack.push(training()); break;
		}
	}

	/** Examine 'v' result, as crawl's describe popup. */
	public void openDescription(String title, String text)
	{
		stack.push(describe(ReplayReadOnly.Screen.EXAMINE, title, text, null));
	}

	/** Several things on one cell: letter-select which to describe. */
	public void openChoice(final List<String[]> targets, boolean unseenItems)
	{
		Screen s = new Screen(ReplayReadOnly.Screen.EXAMINE, PROSE_ROWS);
		addTagged(s, "Describe what?");
		addTagged(s, "");
		for (int i = 0; i < targets.size(); i++)
			addPlain(s, letter(i) + " - " + targets.get(i)[0]);
		if (unseenItems)
		{
			addTagged(s, "");
			addTagged(s, "There may be more items here you haven't seen.");
		}
		s.keys = c ->
		{
			int i = index(c);
			if (i < 0 || i >= targets.size())
				return false;
			stack.push(describe(ReplayReadOnly.Screen.EXAMINE, targets.get(i)[0],
					targets.get(i)[1], null));
			return true;
		};
		stack.push(s);
	}

	public void back()
	{
		if (!stack.isEmpty())
			stack.pop();
	}

	/** Keys from the Crawl keyboard or hardware. Esc closes the top screen. */
	public void onKey(int c)
	{
		Screen s = stack.peek();
		if (s == null)
			return;
		if (c == KEY_ESCAPE)
		{
			back();
			return;
		}
		if (s.keys != null && s.keys.onKey(c))
			return;
		switch (c)
		{
		case '+': case '>': case ' ': case 'J':
			if (s.page + 1 < s.pages())
				s.page++;
			else if (c == ' ')
				back(); // like crawl: space past the last page closes
			break;
		case '-': case '<': case 'K':
			if (s.page > 0)
				s.page--;
			break;
		case 13:
			back();
			break;
		default:
			if (ReplayReadOnly.isEditKey(s.kind, c))
				host.toast(ReplayReadOnly.MESSAGE);
			break;
		}
	}

	/** ReplayController overlay: the top screen replaces the recorded grid. */
	public ScreenState apply(ScreenState recorded)
	{
		Screen s = stack.peek();
		if (s == null)
			return recorded;
		ScreenState out = new ScreenState(recorded.rows, recorded.cols);
		out.copySideChannelsFrom(recorded);
		// Recorded popup flags would force MESSAGES / MORGUE routing.
		out.msgHistoryMode = false;
		out.charLogMode = false;
		java.util.Arrays.fill(out.chars, ' ');
		java.util.Arrays.fill(out.fg, CrawlText.LIGHTGRAY);
		java.util.Arrays.fill(out.bg, BG);
		int body = s.bodyRows();
		int first = s.page * body;
		int rows = Math.min(out.rows, s.pageRows);
		for (int r = 0; r < body && r < rows && first + r < s.text.size(); r++)
			put(out, r, s.text.get(first + r), s.colour.get(first + r));
		if (s.pages() > 1 && rows > 0)
		{
			List<char[]> t = new ArrayList<>();
			List<int[]> c = new ArrayList<>();
			addTagged(t, c, "<lightgrey>[<white>+</white>] next page  [<white>-</white>] previous page"
					+ "  (" + (s.page + 1) + "/" + s.pages() + ")</lightgrey>");
			put(out, rows - 1, t.get(0), c.get(0));
		}
		return out;
	}

	/** Visible text of the top screen, one string per row (tests). */
	List<String> visibleRows()
	{
		List<String> out = new ArrayList<>();
		Screen s = stack.peek();
		if (s == null)
			return out;
		ScreenState st = apply(new ScreenState(72, COLS));
		for (int r = 0; r < s.pageRows; r++)
			out.add(new String(st.chars, r * COLS, COLS).replaceAll("\\s+$", ""));
		return out;
	}

	// ---------------------------------------------------------------- screens

	private Screen inventory()
	{
		final List<Map<String, Object>> items = maps(k.inventory());
		Screen s = new Screen(ReplayReadOnly.Screen.INVENTORY, LIST_ROWS);
		addTagged(s, "<white>Inventory: " + items.size() + (items.size() == 1 ? " item" : " items")
				+ "</white>");
		if (items.isEmpty())
			addTagged(s, "You aren't carrying anything.");
		String cls = null;
		for (Map<String, Object> it : items)
		{
			// "k" (class subtitle) and "c" (menu colour) only in newer recordings.
			String k = MiniJson.getString(it, "k", null);
			if (k != null && !k.equals(cls))
				addTagged(s, "<blue>" + escape(k) + "</blue>");
			cls = k;
			addTagged(s, coloured(MiniJson.getString(it, "c", null), escape(itemLine(it))));
		}
		s.keys = c ->
		{
			for (Map<String, Object> it : items)
				if (itemLine(it).charAt(0) == c)
				{
					stack.push(describe(ReplayReadOnly.Screen.ITEM, itemLine(it),
							dropFirstLine(blobs.get(MiniJson.getString(it, "d", null)), itemLine(it)),
							"(w)ield, (W)ear, (P)ut on, (q)uaff, (r)ead, (d)rop, ({)inscribe"));
					return true;
				}
			return false;
		};
		return s;
	}

	private Screen spells()
	{
		final List<Map<String, Object>> spells = maps(k.spells());
		Screen s = new Screen(ReplayReadOnly.Screen.SPELLS, LIST_ROWS);
		addTagged(s, "<white>" + pad("Your spells (describe)", 25)
				+ "           Type                      Failure  Level</white>");
		String table = panel("spells");
		final List<Character> letters = new ArrayList<>();
		final List<String> titles = new ArrayList<>();
		// Newer recordings carry list_spells()' coloured row per spell.
		for (Map<String, Object> sp : spells)
		{
			String l = MiniJson.getString(sp, "l", null);
			String key = MiniJson.getString(sp, "s", "");
			if (l == null || key.isEmpty())
				continue;
			addTagged(s, key + " - " + l);
			letters.add(key.charAt(0));
			titles.add(MiniJson.getString(sp, "n", ""));
		}
		if (table != null && letters.isEmpty())
			for (String line : table.split("\n"))
				if (line.length() > 4 && Character.isLetter(line.charAt(0))
						&& line.startsWith(" - ", 1))
				{
					addTagged(s, line);
					letters.add(line.charAt(0));
					titles.add(line.substring(4, Math.min(line.length(), 24)).trim());
				}
		if (letters.isEmpty())
			for (int i = 0; i < spells.size(); i++)
			{
				char l = letter(i);
				String n = MiniJson.getString(spells.get(i), "n", "");
				addPlain(s, l + " - " + n);
				letters.add(l);
				titles.add(n);
			}
		if (letters.isEmpty())
			addTagged(s, table == null && k.spells() == null ? NOT_RECORDED
					: "You don't know any spells.");
		s.keys = c ->
		{
			int i = letters.indexOf((char) c);
			if (i < 0)
				return false;
			Map<String, Object> sp = spellNamed(spells, titles.get(i));
			String name = sp == null ? titles.get(i) : MiniJson.getString(sp, "n", titles.get(i));
			stack.push(describe(ReplayReadOnly.Screen.SPELL, name,
					sp == null ? null : blobs.get(MiniJson.getString(sp, "d", null)),
					"(F)orget this spell, (c)ast"));
			return true;
		};
		return s;
	}

	private Screen abilities()
	{
		final List<Map<String, Object>> abil = maps(k.abilities());
		Screen s = new Screen(ReplayReadOnly.Screen.ABILITIES, LIST_ROWS);
		addTagged(s, "<white>Ability - describe what?            Cost                            Failure</white>");
		String list = panel("abilities_list");
		final List<Character> keys = new ArrayList<>();
		final List<Map<String, Object>> order = new ArrayList<>();
		// Newer recordings: describe_talent row, hotkey, colour, invocation
		// flag; as choose_ability_menu, invocations go last under a subtitle.
		for (int pass = 0; pass < 2; pass++)
		{
			boolean subtitle = pass == 1;
			for (Map<String, Object> a : abil)
			{
				String l = MiniJson.getString(a, "l", null);
				String key = MiniJson.getString(a, "s", "");
				if (l == null || key.isEmpty() || (MiniJson.getLong(a, "i", 0) != 0) != (pass == 1))
					continue;
				if (subtitle)
					addTagged(s, "<blue> Invocations -    </blue>");
				subtitle = false;
				addTagged(s, coloured(MiniJson.getString(a, "c", null), escape(key + " - " + l)));
				keys.add(key.charAt(0));
				order.add(a);
			}
		}
		if (keys.isEmpty())
			for (int i = 0; i < abil.size(); i++)
			{
				String n = MiniJson.getString(abil.get(i), "n", "");
				addPlain(s, pad(letter(i) + " - " + n, 68) + failure(list, n));
				keys.add(letter(i));
				order.add(abil.get(i));
			}
		if (abil.isEmpty())
			addTagged(s, k.abilities() == null ? NOT_RECORDED : "You have no special abilities.");
		s.keys = c ->
		{
			int i = keys.indexOf((char) c);
			if (i < 0)
				return false;
			Map<String, Object> a = order.get(i);
			stack.push(describe(ReplayReadOnly.Screen.ABILITY, MiniJson.getString(a, "n", "Ability"),
					blobs.get(MiniJson.getString(a, "d", null)), null));
			return true;
		};
		return s;
	}

	private Screen character()
	{
		Screen s = new Screen(ReplayReadOnly.Screen.CHARACTER, PROSE_ROWS);
		String overview = panel("overview");
		addText(s, overview != null ? overview : NOT_RECORDED);
		StringBuilder more = new StringBuilder("<lightgrey>");
		for (String[] key : CHARACTER_KEYS)
			more.append('[').append("<white>").append(key[0]).append("</white>] ")
					.append(key[2]).append("  ");
		addTagged(s, "");
		addTagged(s, more.append("</lightgrey>").toString());
		s.keys = c ->
		{
			for (String[] key : CHARACTER_KEYS)
				if (key[0].charAt(0) == c)
				{
					String text = panel(key[1]);
					if (text != null && text.trim().isEmpty())
						text = key[3];
					stack.push(describe(ReplayReadOnly.Screen.CHARACTER, key[2], text, null));
					return true;
				}
			return false;
		};
		return s;
	}

	private Screen training()
	{
		Screen s = new Screen(ReplayReadOnly.Screen.TRAINING, PROSE_ROWS);
		addTagged(s, "Skills (training)");
		addTagged(s, "");
		String skills = panel("skills");
		if (skills == null)
			addTagged(s, NOT_RECORDED);
		else
		{
			boolean any = false;
			for (String line : skills.split("\n"))
				if (!line.trim().isEmpty() && !line.trim().equals("Skills:"))
				{
					addTagged(s, coloured(skillColour(line), escape(line)));
					any = true;
				}
			if (!any)
				addTagged(s, "You have no skills.");
		}
		return s;
	}

	/** Crawl's describe popup: title, blank row, wrapped text, action line. */
	private Screen describe(ReplayReadOnly.Screen kind, String title, String text, String actions)
	{
		Screen s = new Screen(kind, PROSE_ROWS);
		addPlain(s, title == null || title.isEmpty() ? " " : title);
		addTagged(s, "");
		String body = text == null ? NOT_RECORDED : text.replaceAll("^\\s*\n", "");
		// Item blobs start with the item name, already shown as the title.
		if (title != null && !title.isEmpty() && body.startsWith(title + "\n"))
			body = body.substring(title.length()).replaceAll("^\\s*\n", "");
		addTagged(s.text, s.colour, body.trim().isEmpty() ? "(none)" : body, proseCols);
		if (actions != null)
		{
			addTagged(s, "");
			addTagged(s.text, s.colour, actions.replace("<", "<<"), proseCols);
		}
		return s;
	}

	// ---------------------------------------------------------------- helpers

	private String panel(String name)
	{
		String hash = k.panels == null ? null : k.panels.get(name);
		return hash == null ? null : blobs.get(hash);
	}

	// "n" is DESC_INVENTORY ("m - 2 potions..."), which carries the real
	// inventory letter; "s" is index_to_letter and wrong for 0.34 gear slots.
	private static String itemLine(Map<String, Object> it)
	{
		String n = MiniJson.getString(it, "n", "");
		if (n.length() > 4 && n.startsWith(" - ", 1))
			return n;
		return MiniJson.getString(it, "s", "?") + " - " + n;
	}

	private static String escape(String s)
	{
		return s.replace("<", "<<");
	}

	private static String coloured(String colour, String tagged)
	{
		return colour == null || CrawlText.colour(colour) == null ? tagged
				: "<" + colour + ">" + tagged + "</" + colour + ">";
	}

	// Item blobs start with the DESC_INVENTORY name; the title shows it already.
	private static String dropFirstLine(String text, String title)
	{
		if (text == null || title.length() < 4 || !text.startsWith(title.substring(0, 4)))
			return text;
		int nl = text.indexOf('\n');
		return nl < 0 ? "" : text.substring(nl + 1);
	}

	// dump_skills() " %c Level x(y) Name" -> SkillMenuEntry::get_colour.
	private static String skillColour(String line)
	{
		if (line.length() < 2)
			return null;
		char m = line.charAt(1);
		boolean bright = m == '+' || m == '*';
		int lv = line.indexOf("Level "), open = line.indexOf('('), close = line.indexOf(')');
		if (lv >= 0 && open > lv && close > open)
		{
			try
			{
				double real = Double.parseDouble(line.substring(lv + 6, open));
				double cur = Double.parseDouble(line.substring(open + 1, close));
				if (cur > real)
					return bright ? "lightgreen" : "green";
				if (cur < real)
					return bright ? "lightmagenta" : "magenta";
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		switch (m)
		{
		case 'O': return "yellow";
		case '*': return "white";
		case '+': return "lightgrey";
		default: return "darkgrey";
		}
	}

	private static Map<String, Object> spellNamed(List<Map<String, Object>> spells, String title)
	{
		for (Map<String, Object> sp : spells)
		{
			String n = MiniJson.getString(sp, "n", "");
			if (!title.isEmpty() && (n.equals(title) || n.startsWith(title)))
				return sp;
		}
		return null;
	}

	// print_abilities(): "a: Name (fail), Name2 (fail)".
	private static String failure(String list, String name)
	{
		if (list == null || name.isEmpty())
			return "";
		int i = list.indexOf(name + " (");
		if (i < 0)
			return "";
		int open = i + name.length() + 2, close = list.indexOf(')', open);
		return close < 0 ? "" : list.substring(open, close);
	}

	private static String pad(String s, int width)
	{
		StringBuilder b = new StringBuilder(s);
		while (b.length() < width)
			b.append(' ');
		return b.toString();
	}

	private static char letter(int i)
	{
		return i < 26 ? (char) ('a' + i) : (char) ('A' + i - 26);
	}

	private static int index(int c)
	{
		if (c >= 'a' && c <= 'z')
			return c - 'a';
		if (c >= 'A' && c <= 'Z')
			return c - 'A' + 26;
		return -1;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> maps(List<Object> l)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		if (l != null)
			for (Object o : l)
				if (o instanceof Map)
					out.add((Map<String, Object>) o);
		return out;
	}

	private static void put(ScreenState st, int row, char[] t, int[] c)
	{
		for (int col = 0; col < t.length && col < st.cols; col++)
		{
			st.chars[row * st.cols + col] = t[col];
			st.fg[row * st.cols + col] = c[col];
		}
	}

	private static void addPlain(Screen s, String line)
	{
		addText(s, line.replace("<", "<<"));
	}

	private static void addTagged(Screen s, String line)
	{
		addTagged(s.text, s.colour, line);
	}

	private static void addText(Screen s, String text)
	{
		addTagged(s.text, s.colour, text);
	}

	static void addTagged(List<char[]> text, List<int[]> colour, String s)
	{
		addTagged(text, colour, s, COLS);
	}

	/** DCSS colour-tagged text -> rows of cells, word-wrapped to cols. */
	static void addTagged(List<char[]> text, List<int[]> colour, String s, int cols)
	{
		StringBuilder chars = new StringBuilder();
		List<Integer> fg = new ArrayList<>();
		int cur = CrawlText.LIGHTGRAY;
		// formatted_string semantics: a closing tag pops to the outer colour.
		ArrayDeque<Integer> outer = new ArrayDeque<>();
		int i = 0, n = s.length();
		while (i <= n)
		{
			char ch = i < n ? s.charAt(i) : '\n';
			if (ch == '\n')
			{
				wrap(text, colour, chars.toString(), fg, cols);
				chars.setLength(0);
				fg.clear();
				i++;
				continue;
			}
			if (ch == '<' && i + 1 < n && s.charAt(i + 1) == '<')
			{
				chars.append('<');
				fg.add(cur);
				i += 2;
				continue;
			}
			if (ch == '<')
			{
				int close = s.indexOf('>', i + 1);
				if (close > 0)
				{
					String tag = s.substring(i + 1, close).toLowerCase(java.util.Locale.ROOT);
					if (tag.startsWith("/"))
					{
						cur = outer.isEmpty() ? CrawlText.LIGHTGRAY : outer.pop();
						i = close + 1;
						continue;
					}
					Integer c = CrawlText.colour(tag);
					if (c != null || tag.startsWith("bg:"))
					{
						if (c != null)
						{
							outer.push(cur);
							cur = c;
						}
						i = close + 1;
						continue;
					}
				}
			}
			chars.append(ch == '\t' || ch == '\r' ? ' ' : ch);
			fg.add(cur);
			i++;
		}
	}

	private static void wrap(List<char[]> text, List<int[]> colour, String line, List<Integer> fg,
			int cols)
	{
		int start = 0;
		do
		{
			int end = Math.min(line.length(), start + cols);
			if (end < line.length())
			{
				int sp = line.lastIndexOf(' ', end);
				if (sp > start)
					end = sp;
			}
			char[] t = line.substring(start, end).toCharArray();
			int[] c = new int[t.length];
			for (int j = 0; j < t.length; j++)
				c[j] = fg.get(start + j);
			text.add(t);
			colour.add(c);
			start = end;
			while (start < line.length() && start > 0 && line.charAt(start) == ' ')
				start++;
		}
		while (start < line.length());
	}
}
