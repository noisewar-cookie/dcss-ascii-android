package com.crawlmb.recording;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One level's map knowledge as of a snapshot, rebuilt from a keyframe plus
 * deltas (android-recorder.cc build_map). Cells reference blobs by hash.
 */
public final class LevelMap
{
	public static final class Item
	{
		public final String name;
		public final String descHash;

		Item(String name, String descHash)
		{
			this.name = name;
			this.descHash = descHash;
		}
	}

	public static final class Cell
	{
		public int glyph;          // char32
		public int colour;         // DCSS colour enum (map via colourMap in Java)
		public int flags;          // MAP_* subset from native
		public String featNameHash;
		public String featDescHash; // only for cells seen in LOS
		public boolean moreItems;
		public List<Item> items;

		Cell copy()
		{
			Cell c = new Cell();
			c.glyph = glyph;
			c.colour = colour;
			c.flags = flags;
			c.featNameHash = featNameHash;
			c.featDescHash = featDescHash;
			c.moreItems = moreItems;
			c.items = items; // immutable once built
			return c;
		}
	}

	public final String level;
	public final int width;
	public final int height;
	private final Cell[] cells;

	public LevelMap(String level, int width, int height)
	{
		this.level = level;
		this.width = width;
		this.height = height;
		this.cells = new Cell[width * height];
	}

	public LevelMap copy()
	{
		LevelMap m = new LevelMap(level, width, height);
		for (int i = 0; i < cells.length; i++)
			m.cells[i] = cells[i] == null ? null : cells[i].copy();
		return m;
	}

	/** Null when the player knows nothing about (x, y). */
	public Cell get(int x, int y)
	{
		if (x < 0 || y < 0 || x >= width || y >= height)
			return null;
		return cells[y * width + x];
	}

	/** Apply a snapshot's "map" object (keyframe or delta). */
	@SuppressWarnings("unchecked")
	public void apply(Map<String, Object> map)
	{
		if (MiniJson.getLong(map, "key", 0) != 0)
			java.util.Arrays.fill(cells, null);
		List<Object> list = MiniJson.getArray(map, "cells");
		if (list != null)
			for (Object o : list)
				if (o instanceof List)
					applyCell((List<Object>) o);
		applyLos(MiniJson.getObject(map, "los"));
	}

	// [x,y,g,c,f,t,d,m(,items)], or [x,y] = knowledge lost.
	@SuppressWarnings("unchecked")
	private void applyCell(List<Object> a)
	{
		int x = (int) num(a, 0, -1);
		int y = (int) num(a, 1, -1);
		if (x < 0 || y < 0 || x >= width || y >= height)
			return; // bounds-checked: untrusted input
		int idx = y * width + x;
		if (a.size() < 6)
		{
			cells[idx] = null;
			return;
		}
		Cell c = new Cell();
		c.glyph = (int) num(a, 2, ' ');
		c.colour = (int) num(a, 3, 7);
		c.flags = (int) num(a, 4, 0) & ~ExamineCursor.MAP_VISIBLE;
		c.featNameHash = str(a, 5);
		c.featDescHash = str(a, 6);
		c.moreItems = num(a, 7, 0) != 0;
		if (a.size() > 8 && a.get(8) instanceof List)
		{
			c.items = new ArrayList<>();
			for (Object io : (List<Object>) a.get(8))
				if (io instanceof List && ((List<Object>) io).size() >= 2)
				{
					List<Object> pair = (List<Object>) io;
					c.items.add(new Item(String.valueOf(pair.get(0)), String.valueOf(pair.get(1))));
				}
		}
		cells[idx] = c;
	}

	// Visibility lives in the snapshot's LOS bitmap, not the cells.
	private void applyLos(Map<String, Object> los)
	{
		if (los == null)
			return;
		for (Cell c : cells)
			if (c != null)
				c.flags &= ~ExamineCursor.MAP_VISIBLE;
		int x0 = (int) MiniJson.getLong(los, "x", 0);
		int y0 = (int) MiniJson.getLong(los, "y", 0);
		int w = (int) MiniJson.getLong(los, "w", 0);
		String b = MiniJson.getString(los, "b", "");
		if (w <= 0)
			return;
		for (int i = 0; i < b.length() * 4; i++)
		{
			int nibble = Character.digit(b.charAt(i / 4), 16);
			if (nibble < 0 || (nibble & (8 >> (i % 4))) == 0)
				continue;
			Cell c = get(x0 + i % w, y0 + i / w);
			if (c != null)
				c.flags |= ExamineCursor.MAP_VISIBLE;
		}
	}

	private static long num(List<Object> a, int i, long def)
	{
		return i < a.size() && a.get(i) instanceof Number ? ((Number) a.get(i)).longValue() : def;
	}

	private static String str(List<Object> a, int i)
	{
		return i < a.size() && a.get(i) instanceof String ? (String) a.get(i) : null;
	}
}
