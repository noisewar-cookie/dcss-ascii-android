package com.crawlmb.recording;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Replay examine mode: the equivalent of pressing 'x' in-game on a paused
 * replay. A grid cursor starts on the player, moves within the recorded view
 * area, is drawn onto the recorded screen, and 'v' produces the full
 * description of whatever the player knew about that cell.
 *
 * Uses only the snapshot's "pos"/"view" geometry, the rebuilt LevelMap and the
 * monster list, so it shows exactly what x-look could have shown. Pure Java.
 */
public final class ExamineCursor
{
	// Set by LevelMap from the snapshot's LOS bitmap, never read from the
	// recorded flags (whose bits are DCSS's; see RecordingImplementation.md).
	public static final int MAP_VISIBLE = 0x40;

	private static final int HIGHLIGHT_BG = 0xFFBBBBBB;
	private static final int HIGHLIGHT_FG = 0xFF000000;

	private final KnowledgeResolver.Knowledge k;
	private final KnowledgeResolver resolver;
	private final int cx, cy, hx, hy, vx, vy, tx, ty;
	private final int mapW, mapH;
	private int x, y;

	public ExamineCursor(KnowledgeResolver.Knowledge k, KnowledgeResolver resolver)
	{
		this.k = k;
		this.resolver = resolver;
		Map<String, Object> pos = MiniJson.getObject(k.doc, "pos");
		Map<String, Object> v = MiniJson.getObject(k.doc, "view");
		mapW = k.map != null ? k.map.width : 80;
		mapH = k.map != null ? k.map.height : 70;
		x = (int) MiniJson.getLong(pos, "x", mapW / 2);
		y = (int) MiniJson.getLong(pos, "y", mapH / 2);
		cx = (int) MiniJson.getLong(v, "cx", x);
		cy = (int) MiniJson.getLong(v, "cy", y);
		hx = (int) MiniJson.getLong(v, "hx", 16);
		hy = (int) MiniJson.getLong(v, "hy", 8);
		vx = (int) MiniJson.getLong(v, "vx", 1);
		vy = (int) MiniJson.getLong(v, "vy", 1);
		tx = (int) MiniJson.getLong(v, "tx", 1);
		ty = (int) MiniJson.getLong(v, "ty", 1);
	}

	public int x() { return x; }
	public int y() { return y; }

	/** Move like x-look: stays inside the recorded view and the level. */
	public boolean move(int dx, int dy)
	{
		int nx = x + dx, ny = y + dy;
		if (Math.abs(nx - cx) > hx || Math.abs(ny - cy) > hy)
			return false;
		if (nx < 0 || ny < 0 || nx >= mapW || ny >= mapH)
			return false;
		x = nx;
		y = ny;
		return true;
	}

	/** Back to the player ('@' / home). */
	public void reset()
	{
		Map<String, Object> pos = MiniJson.getObject(k.doc, "pos");
		x = (int) MiniJson.getLong(pos, "x", x);
		y = (int) MiniJson.getLong(pos, "y", y);
	}

	/** Recorded-screen index of the cursor cell, or -1 if off-grid. */
	public int screenIndex(int rows, int cols)
	{
		// crawl_view.grid2screen; console screen coordinates are 1-based.
		int sc = x - cx + hx + 1 + vx - tx;
		int sr = y - cy + hy + 1 + vy - ty;
		if (sc < 1 || sr < 1 || sc > cols || sr > rows)
			return -1;
		return (sr - 1) * cols + (sc - 1);
	}

	/** ReplayController overlay: draw the cursor as an inverted cell. */
	public ScreenState overlay(ScreenState s)
	{
		int i = screenIndex(s.rows, s.cols);
		if (i < 0)
			return s;
		ScreenState c = s.copy();
		c.fg[i] = HIGHLIGHT_FG;
		c.bg[i] = HIGHLIGHT_BG;
		if (c.chars[i] == ' ' || c.chars[i] == 0)
			c.chars[i] = ' ';
		return c;
	}

	public LevelMap.Cell cell()
	{
		return k.map == null ? null : k.map.get(x, y);
	}

	public Map<String, Object> monster()
	{
		List<Object> mons = k.monsters();
		if (mons == null)
			return null;
		for (Object o : mons)
			if (o instanceof Map)
			{
				@SuppressWarnings("unchecked")
				Map<String, Object> m = (Map<String, Object>) o;
				if (MiniJson.getLong(m, "x", -1) == x && MiniJson.getLong(m, "y", -1) == y)
					return m;
			}
		return null;
	}

	public boolean isPlayerCell()
	{
		Map<String, Object> pos = MiniJson.getObject(k.doc, "pos");
		return MiniJson.getLong(pos, "x", -1) == x && MiniJson.getLong(pos, "y", -1) == y;
	}

	/** One-line summary, shown in the controls while moving (like x-look's message line). */
	public String summary()
	{
		if (isPlayerCell())
			return "You. " + cap(featureName(cell()));
		LevelMap.Cell c = cell();
		if (c == null)
			return "You have no knowledge of this place.";
		StringBuilder b = new StringBuilder();
		Map<String, Object> m = monster();
		if (m != null)
			b.append(cap(MiniJson.getString(m, "n", "a monster"))).append(". ");
		if (c.items != null && !c.items.isEmpty())
		{
			b.append(cap(c.items.get(0).name));
			if (c.items.size() > 1 || c.moreItems)
				b.append(" and more items");
			b.append(". ");
		}
		b.append(cap(featureName(c)));
		if ((c.flags & MAP_VISIBLE) == 0)
			b.append(" (remembered)");
		return b.toString().trim();
	}

	/** Full 'v' description: monster, else items, else terrain. */
	public String describe()
	{
		LevelMap.Cell c = cell();
		Map<String, Object> m = monster();
		if (m != null)
		{
			String d = resolver.blob(MiniJson.getString(m, "d", null));
			return d != null ? d : cap(MiniJson.getString(m, "n", "A monster")) + ".";
		}
		if (c == null)
			return "You have no knowledge of this place.";
		if (c.items != null && !c.items.isEmpty())
		{
			StringBuilder b = new StringBuilder();
			for (LevelMap.Item it : c.items)
			{
				String d = resolver.blob(it.descHash);
				b.append(d != null ? d : cap(it.name) + ".").append("\n\n");
			}
			if (c.moreItems)
				b.append("There may be more items here you haven't seen.\n\n");
			return b.append(featureText(c)).toString();
		}
		return featureText(c);
	}

	/** Everything on the cell, each {title, description}: monster, items, terrain. */
	public List<String[]> targets()
	{
		List<String[]> out = new ArrayList<>();
		LevelMap.Cell c = cell();
		Map<String, Object> m = monster();
		if (m != null)
		{
			String n = cap(MiniJson.getString(m, "n", "a monster"));
			String d = resolver.blob(MiniJson.getString(m, "d", null));
			out.add(new String[] { n, d != null ? d : n + "." });
		}
		if (c == null)
		{
			if (out.isEmpty())
				out.add(new String[] { "", "You have no knowledge of this place." });
			return out;
		}
		if (c.items != null)
			for (LevelMap.Item it : c.items)
			{
				String d = resolver.blob(it.descHash);
				out.add(new String[] { cap(it.name), d != null ? d : cap(it.name) + "." });
			}
		out.add(new String[] { cap(featureName(c)), featureText(c) });
		return out;
	}

	public boolean hasUnseenItems()
	{
		LevelMap.Cell c = cell();
		return c != null && c.moreItems;
	}

	private String featureText(LevelMap.Cell c)
	{
		String d = resolver.blob(c.featDescHash);
		return d != null ? d : cap(featureName(c)) + ".";
	}

	private String featureName(LevelMap.Cell c)
	{
		if (c == null)
			return "";
		String n = resolver.blob(c.featNameHash);
		return n == null ? "unknown terrain" : n;
	}

	private static String cap(String s)
	{
		return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
