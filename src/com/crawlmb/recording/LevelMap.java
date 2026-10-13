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
		if (list == null)
			return;
		for (Object o : list)
		{
			if (!(o instanceof Map))
				continue;
			Map<String, Object> m = (Map<String, Object>) o;
			int x = (int) MiniJson.getLong(m, "x", -1);
			int y = (int) MiniJson.getLong(m, "y", -1);
			if (x < 0 || y < 0 || x >= width || y >= height)
				continue; // bounds-checked: untrusted input
			int idx = y * width + x;
			if (MiniJson.getLong(m, "clr", 0) != 0)
			{
				cells[idx] = null;
				continue;
			}
			Cell c = new Cell();
			c.glyph = (int) MiniJson.getLong(m, "g", ' ');
			c.colour = (int) MiniJson.getLong(m, "c", 7);
			c.flags = (int) MiniJson.getLong(m, "f", 0);
			c.featNameHash = MiniJson.getString(m, "t", null);
			c.featDescHash = MiniJson.getString(m, "d", null);
			c.moreItems = MiniJson.getLong(m, "m", 0) != 0;
			List<Object> its = MiniJson.getArray(m, "i");
			if (its != null)
			{
				c.items = new ArrayList<>();
				for (Object io : its)
					if (io instanceof List && ((List<Object>) io).size() >= 2)
					{
						List<Object> pair = (List<Object>) io;
						c.items.add(new Item(String.valueOf(pair.get(0)), String.valueOf(pair.get(1))));
					}
			}
			cells[idx] = c;
		}
	}
}
