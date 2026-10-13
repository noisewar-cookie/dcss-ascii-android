package com.crawlmb.recording;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves "everything the player could look up" at a replay position from the
 * Knowledge track: latest snapshot <= frame, panels carried forward from the
 * latest snapshot that refreshed them, and level maps rebuilt from keyframe +
 * deltas. Abandoned-branch snapshots are skipped.
 */
public final class KnowledgeResolver
{
	/** Resolved view for the examine UI. */
	public static final class Knowledge
	{
		public final RecordingReader.SnapshotRow row;
		public final Map<String, Object> doc;
		public final Map<String, String> panels; // panel name -> blob hash
		public final LevelMap map;               // current level

		Knowledge(RecordingReader.SnapshotRow row, Map<String, Object> doc,
				Map<String, String> panels, LevelMap map)
		{
			this.row = row;
			this.doc = doc;
			this.panels = panels;
			this.map = map;
		}

		public Map<String, Object> player() { return MiniJson.getObject(doc, "player"); }
		public List<Object> inventory() { return MiniJson.getArray(doc, "inv"); }
		public List<Object> monsters() { return MiniJson.getArray(doc, "mons"); }
		public List<Object> spells() { return MiniJson.getArray(doc, "spells"); }
		public List<Object> abilities() { return MiniJson.getArray(doc, "abil"); }
	}

	private final RecordingReader reader;
	private final List<RecordingReader.SnapshotRow> rows = new ArrayList<>();
	private final int[] rowFrames;
	private final LinkedHashMap<Long, Map<String, Object>> docCache =
			new LinkedHashMap<Long, Map<String, Object>>(64, 0.75f, true)
			{
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, Map<String, Object>> e)
				{
					return size() > 256;
				}
			};
	private final LinkedHashMap<String, String> blobCache =
			new LinkedHashMap<String, String>(64, 0.75f, true)
			{
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, String> e)
				{
					return size() > 512;
				}
			};
	// Incremental level-map cache: last rebuilt map per level and the row index it is valid for.
	private final Map<String, LevelMap> mapCache = new LinkedHashMap<>();
	private final Map<String, Integer> mapCacheRow = new LinkedHashMap<>();

	public KnowledgeResolver(RecordingReader reader, ReplayTimeline visibleTimeline)
	{
		this.reader = reader;
		Set<Integer> visibleFrames = new HashSet<>();
		for (int i = 0; i < visibleTimeline.size(); i++)
			visibleFrames.add(visibleTimeline.frame(i));
		for (RecordingReader.SnapshotRow r : reader.snapshotIndex())
			if (visibleFrames.contains(r.frame) || nearestVisible(visibleTimeline, r.frame))
				rows.add(r);
		rowFrames = new int[rows.size()];
		for (int i = 0; i < rows.size(); i++)
			rowFrames[i] = rows.get(i).frame;
	}

	// Snapshots can attach to a frame number that was never written (snapshot
	// before the first frame of a session); keep them if the timeline covers it.
	private static boolean nearestVisible(ReplayTimeline tl, int frame)
	{
		int p = tl.posOfFrame(frame);
		return p >= 0 && p + 1 < tl.size() && tl.frame(p + 1) > frame;
	}

	public List<RecordingReader.SnapshotRow> rows() { return Collections.unmodifiableList(rows); }

	/** Index of the latest snapshot at or before frame, or -1. */
	public int rowAt(int frame)
	{
		int lo = 0, hi = rowFrames.length - 1, ans = -1;
		while (lo <= hi)
		{
			int mid = (lo + hi) >>> 1;
			if (rowFrames[mid] <= frame) { ans = mid; lo = mid + 1; }
			else hi = mid - 1;
		}
		// Several snapshots can share a frame (TE then CP): take the last one.
		return ans;
	}

	/** rowAt(frame), minus snapshots taken after the frame's game time. */
	public int rowAt(int frame, long aut)
	{
		int i = rowAt(frame);
		// A turn's TE/CP snapshots are taken before its frame is flushed, so
		// they attach to the previous frame; step back to match the screen.
		int j = i;
		while (aut >= 0 && j > 0 && rows.get(j).aut > aut)
			j--;
		return j >= 0 && rows.get(j).aut <= aut ? j : i;
	}

	public Knowledge resolve(int frame, long aut)
	{
		int i = rowAt(frame, aut);
		if (i < 0)
			return null;
		RecordingReader.SnapshotRow row = rows.get(i);
		Map<String, Object> doc = doc(row);
		return new Knowledge(row, doc, panelsAt(i), levelMap(row.level, i));
	}

	/** Map of any visited level as of snapshot index i (null if never seen). */
	public LevelMap levelMap(String level, int i)
	{
		Integer cachedRow = mapCacheRow.get(level);
		LevelMap base = null;
		int from;
		if (cachedRow != null && cachedRow <= i)
		{
			base = mapCache.get(level).copy();
			from = cachedRow + 1;
		}
		else
		{
			// Walk back to the latest keyframe for this level.
			int k = i;
			while (k >= 0 && !(rows.get(k).level.equals(level) && rows.get(k).mapKey))
				k--;
			if (k < 0)
				return null;
			from = k;
		}
		for (int j = from; j <= i; j++)
		{
			RecordingReader.SnapshotRow r = rows.get(j);
			if (!r.level.equals(level))
				continue;
			Map<String, Object> map = MiniJson.getObject(doc(r), "map");
			if (map == null)
				continue;
			if (base == null)
				base = new LevelMap(level, (int) MiniJson.getLong(map, "w", 80),
						(int) MiniJson.getLong(map, "h", 70));
			base.apply(map);
		}
		if (base != null)
		{
			mapCache.put(level, base.copy());
			mapCacheRow.put(level, i);
		}
		return base;
	}

	/** Levels visited up to snapshot index i, in first-visit order. */
	public List<String> levelsVisited(int i)
	{
		Set<String> seen = new java.util.LinkedHashSet<>();
		for (int j = 0; j <= i && j < rows.size(); j++)
			if (!rows.get(j).level.isEmpty())
				seen.add(rows.get(j).level);
		return new ArrayList<>(seen);
	}

	public String blob(String hash)
	{
		if (hash == null)
			return null;
		String v = blobCache.get(hash);
		if (v == null)
		{
			try
			{
				v = reader.blob(hash);
			}
			catch (RuntimeException e)
			{
				return null; // corrupt/imported blob: callers show "unavailable"
			}
			if (v != null)
				blobCache.put(hash, v);
		}
		return v;
	}

	private Map<String, String> panelsAt(int i)
	{
		for (int k = i; k >= 0; k--)
		{
			if (!rows.get(k).hasPanels)
				continue;
			Map<String, Object> p = MiniJson.getObject(doc(rows.get(k)), "panels");
			Map<String, String> out = new LinkedHashMap<>();
			if (p != null)
				for (Map.Entry<String, Object> e : p.entrySet())
					if (e.getValue() instanceof String)
						out.put(e.getKey(), (String) e.getValue());
			return out;
		}
		return Collections.emptyMap();
	}

	private Map<String, Object> doc(RecordingReader.SnapshotRow r)
	{
		Map<String, Object> d = docCache.get(r.id);
		if (d == null)
		{
			try
			{
				d = MiniJson.parseObject(reader.snapshotDoc(r.id));
			}
			catch (RuntimeException e)
			{
				d = Collections.emptyMap(); // examine shows "unavailable"
			}
			docCache.put(r.id, d);
		}
		return d;
	}
}
