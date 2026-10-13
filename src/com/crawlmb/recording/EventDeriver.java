package com.crawlmb.recording;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives timeline markers (events table) by diffing consecutive snapshots.
 * Runs on the writer thread.
 */
public final class EventDeriver
{
	public static final String LEVEL = "level";
	public static final String XL = "xl";
	public static final String GOD = "god";
	public static final String ITEM = "item";
	public static final String NEW_MONSTER = "new_monster";
	public static final String UNIQUE = "unique";
	public static final String LOW_HP = "low_hp";
	public static final String BIG_HIT = "big_hit";
	public static final String GAME_END = "game_end";
	// Zot's "A malevolent force fills ..." (shaft, alarm/mark, teleport trap).
	public static final String MALEVOLENCE = "malevolence";
	// Lifecycle events are written by GameRecorder directly:
	public static final String SAVE = "save";
	public static final String RESUME = "resume";
	public static final String BRANCH = "branch";
	public static final String REC_OFF = "rec_off";
	public static final String GAP = "gap";

	public static final class Event
	{
		public final String type;
		public final String label;

		Event(String type, String label)
		{
			this.type = type;
			this.label = label;
		}
	}

	private SnapshotPayload prev;
	private final Set<String> seenMonsters = new HashSet<>();
	private Set<String> prevInventory = null;

	/** Forget per-session state (call on resume so we don't diff across a gap). */
	public void reset()
	{
		prev = null;
		prevInventory = null;
	}

	public List<Event> derive(SnapshotPayload cur)
	{
		List<Event> out = new ArrayList<>();
		if (RecFormat.KIND_GAME_END.equals(cur.kind))
			out.add(new Event(GAME_END, "Game over on " + cur.level));

		if (prev != null)
		{
			if (!cur.level.equals(prev.level) && !cur.level.isEmpty())
				out.add(new Event(LEVEL, "Entered " + cur.level));
			if (cur.xl > prev.xl)
				out.add(new Event(XL, "Reached XL " + cur.xl));
			String g0 = god(prev), g1 = god(cur);
			if (!g1.equals(g0))
				out.add(new Event(GOD, g1.isEmpty() || g1.equals("No God")
						? "Abandoned " + g0 : "Joined " + g1));
			if (cur.hpMax > 0 && prev.hpMax > 0
					&& cur.hp * 10 < cur.hpMax && prev.hp * 10 >= prev.hpMax)
				out.add(new Event(LOW_HP, "HP " + cur.hp + "/" + cur.hpMax));
			int lost = prev.hp - cur.hp;
			if (cur.hpMax > 0 && lost * 4 > cur.hpMax)
				out.add(new Event(BIG_HIT, "Lost " + lost + " HP"));
		}

		// Monsters: first sighting of each name while visible.
		List<Object> mons = MiniJson.getArray(cur.doc, "mons");
		if (mons != null)
			for (Object o : mons)
			{
				@SuppressWarnings("unchecked")
				Map<String, Object> m = o instanceof Map ? (Map<String, Object>) o : null;
				if (m == null || MiniJson.getLong(m, "vis", 0) == 0)
					continue;
				String name = MiniJson.getString(m, "n", "");
				String key = stripArticle(name);
				if (key.isEmpty() || !seenMonsters.add(key))
					continue;
				// "u" from the recorder; older recordings: proper names have no article.
				boolean unique = MiniJson.getLong(m, "u", 0) != 0
						|| key.equals(name) && Character.isUpperCase(name.charAt(0));
				out.add(new Event(unique ? UNIQUE : NEW_MONSTER, "Saw " + name));
			}

		// Items: inventory names that weren't there last snapshot.
		Set<String> inv = new HashSet<>();
		List<Object> items = MiniJson.getArray(cur.doc, "inv");
		if (items != null)
			for (Object o : items)
				if (o instanceof Map)
				{
					@SuppressWarnings("unchecked")
					Map<String, Object> m = (Map<String, Object>) o;
					inv.add(MiniJson.getString(m, "s", "") + "|" + MiniJson.getString(m, "n", ""));
				}
		if (prevInventory != null)
			for (String k : inv)
				if (!prevInventory.contains(k))
				{
					String slot = k.substring(0, k.indexOf('|'));
					boolean slotWasUsed = false;
					for (String p : prevInventory)
						if (p.startsWith(slot + "|"))
							slotWasUsed = true;
					// Only new slots count as pickups (renames/equips change the name too).
					if (!slotWasUsed)
						out.add(new Event(ITEM, "Got " + k.substring(k.indexOf('|') + 1)));
				}
		prevInventory = inv;
		prev = cur;
		return out;
	}

	private static String god(SnapshotPayload s)
	{
		return MiniJson.getString(MiniJson.getObject(s.doc, "player"), "god", "");
	}

	static String stripArticle(String n)
	{
		if (n.startsWith("a ")) return n.substring(2);
		if (n.startsWith("an ")) return n.substring(3);
		if (n.startsWith("the ")) return n.substring(4);
		return n;
	}
}
