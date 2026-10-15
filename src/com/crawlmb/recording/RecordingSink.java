package com.crawlmb.recording;

import java.util.List;

/**
 * Write side of a recording file. All methods are called on the single
 * writer thread (RecWriterThread), never on the game thread.
 * Implemented by SqliteRecordingStore (Android) and MemoryRecordingStore (tests).
 */
public interface RecordingSink
{
	final class OpenResult
	{
		public final boolean created;
		public final int nextFrame;
		/** Highest in-game turn already recorded, -1 if none. */
		public final int lastTurn;
		/** The file's doc dictionary depth; appended docs must match it. */
		public final int docDictDepth;

		public OpenResult(boolean created, int nextFrame, int lastTurn, int docDictDepth)
		{
			this.created = created;
			this.nextFrame = nextFrame;
			this.lastTurn = lastTurn;
			this.docDictDepth = docDictDepth;
		}
	}

	/** Create the file for this game or reopen it for appending. */
	OpenResult open(GameIdentity id, String appVersion, int[] layout);

	/**
	 * Start a session (new game or resume). For a resume, frames from earlier
	 * sessions with aut > restoredAut are marked abandoned.
	 */
	void startSession(String kind, int firstFrame, int restoredTurn, long restoredAut);

	void writeChunk(int firstFrame, int lastFrame, byte[] deflated,
			List<FrameChunkEncoder.FrameInfo> frames);

	void updateFrameFlags(int frame, int flags);

	/**
	 * Store a blob deflated (against base's text when base != null), sent with
	 * the snapshot at frame. Returns false if the hash was already stored; that
	 * row keeps its own base but records frame as its latest use.
	 */
	boolean writeBlob(String hash, int kind, byte[] deflated, String base, int frame);

	/** anchor: the doc chain, map caches and blob dedupe restarted here. */
	void writeSnapshot(int frame, SnapshotPayload p, byte[] docDeflated, boolean anchor);

	/**
	 * Delete everything before the latest anchor at or before turn, so the
	 * file keeps at least the turns after it.
	 */
	void trimBefore(int turn);

	void writeMessage(int frame, int turn, int channel, String text, boolean temporary);

	/** Delete the newest `count` temporary messages (DCSS roll_back). */
	void rollbackMessages(int count);

	void writeInput(int frame, int key);

	void writeEvent(int frame, int turn, String type, String label);

	void setMeta(String key, String value);

	/** Commit the current transaction (save, pause, periodic). */
	void commit();

	void close();
}
