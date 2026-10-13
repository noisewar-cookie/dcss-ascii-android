package com.crawlmb.recording;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LRU of decoded Screen-track chunks (~35 KB per frame, ~9 MB per chunk).
 * Size it from ActivityManager.getMemoryClass() on device.
 * Not thread-safe; prefetch from one background thread via get().
 */
public final class ChunkCache
{
	private final RecordingReader reader;
	private final int capacity;
	private final LinkedHashMap<Integer, List<FrameChunkDecoder.DecodedFrame>> lru;

	public ChunkCache(RecordingReader reader, int capacity)
	{
		this.reader = reader;
		this.capacity = Math.max(1, capacity);
		this.lru = new LinkedHashMap<Integer, List<FrameChunkDecoder.DecodedFrame>>(8, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<Integer, List<FrameChunkDecoder.DecodedFrame>> e)
			{
				return size() > ChunkCache.this.capacity;
			}
		};
	}

	public synchronized List<FrameChunkDecoder.DecodedFrame> get(int chunkId)
	{
		List<FrameChunkDecoder.DecodedFrame> d = lru.get(chunkId);
		if (d == null)
		{
			d = FrameChunkDecoder.decode(reader.chunk(chunkId));
			lru.put(chunkId, d);
		}
		return d;
	}

	public synchronized boolean contains(int chunkId) { return lru.containsKey(chunkId); }

	/** Screen state for an exact frame number inside a chunk. */
	public ScreenState state(int chunkId, int frame)
	{
		List<FrameChunkDecoder.DecodedFrame> d = get(chunkId);
		int lo = 0, hi = d.size() - 1;
		while (lo <= hi)
		{
			int mid = (lo + hi) >>> 1;
			int f = d.get(mid).frame;
			if (f == frame) return d.get(mid).state;
			if (f < frame) lo = mid + 1; else hi = mid - 1;
		}
		// Not found exactly (shouldn't happen): nearest earlier frame.
		return d.get(Math.max(0, hi)).state;
	}
}
