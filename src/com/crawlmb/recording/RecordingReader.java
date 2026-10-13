package com.crawlmb.recording;

import java.util.List;
import java.util.Map;

/**
 * Read side of a recording file, used by replay. Implemented by
 * SqliteRecordingStore (Android) and MemoryRecordingStore (tests).
 */
public interface RecordingReader
{
	final class FrameIndex
	{
		public final int[] frame;
		public final long[] tMs;
		public final int[] turn;
		public final int[] aut;
		public final int[] flags;
		public final int[] chunkId;
		public final boolean[] abandoned;

		public FrameIndex(int[] frame, long[] tMs, int[] turn, int[] aut, int[] flags,
				int[] chunkId, boolean[] abandoned)
		{
			this.frame = frame;
			this.tMs = tMs;
			this.turn = turn;
			this.aut = aut;
			this.flags = flags;
			this.chunkId = chunkId;
			this.abandoned = abandoned;
		}

		public int size() { return frame.length; }
	}

	final class SnapshotRow
	{
		public final long id;
		public final int frame;
		public final int turn;
		public final long aut;
		public final String kind;
		public final String level;
		public final boolean hasPanels;
		public final boolean mapKey;
		public final int hp, hpMax, mp, mpMax, xl, depth;

		public SnapshotRow(long id, int frame, int turn, long aut, String kind, String level,
				boolean hasPanels, boolean mapKey, int hp, int hpMax, int mp, int mpMax,
				int xl, int depth)
		{
			this.id = id;
			this.frame = frame;
			this.turn = turn;
			this.aut = aut;
			this.kind = kind;
			this.level = level;
			this.hasPanels = hasPanels;
			this.mapKey = mapKey;
			this.hp = hp;
			this.hpMax = hpMax;
			this.mp = mp;
			this.mpMax = mpMax;
			this.xl = xl;
			this.depth = depth;
		}
	}

	final class MessageRow
	{
		public final int frame;
		public final int turn;
		public final int channel;
		public final String text; // DCSS colour-tagged

		public MessageRow(int frame, int turn, int channel, String text)
		{
			this.frame = frame;
			this.turn = turn;
			this.channel = channel;
			this.text = text;
		}
	}

	final class EventRow
	{
		public final int frame;
		public final int turn;
		public final String type;
		public final String label;

		public EventRow(int frame, int turn, String type, String label)
		{
			this.frame = frame;
			this.turn = turn;
			this.type = type;
			this.label = label;
		}
	}

	Map<String, String> meta();

	/** All frames in order, with abandoned-branch flags resolved. */
	FrameIndex frameIndex();

	/** Inflated raw chunk bytes. */
	byte[] chunk(int chunkId);

	/** Snapshot rows ordered by frame (docs not loaded). */
	List<SnapshotRow> snapshotIndex();

	/** Inflated snapshot doc JSON. */
	String snapshotDoc(long id);

	/** Blob text, or null if missing. */
	String blob(String hash);

	/** Non-temporary messages with frame <= toFrame, newest last, at most `limit`. */
	List<MessageRow> messagesUpTo(int toFrame, int limit);

	List<EventRow> events();

	void close();
}
