package com.crawlmb.recording;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tees NativeWrapper's display/input/recorder callbacks into a recording.
 *
 * Threading: frame, side-channel, input and native recorder callbacks arrive on
 * the game thread; onGameSaved/flush/close may arrive on the UI thread. All
 * public methods are synchronized; disk IO happens on RecWriterThread.
 *
 * Lifecycle:
 *   IDLE          out-of-game frames (menus, char creation) are not recorded.
 *   RECORDING     first in-game frame or snapshot resolves the game identity,
 *                 opens/creates the file (new game from frame 0, turn 0) or
 *                 starts a "resume" session in an existing one.
 *   TAIL          after GAME_END, keep recording the death/morgue screens until
 *                 the game resets (TURN_NO_GAME) or TAIL_MAX_FRAMES, then close.
 *   Leaving a game without GAME_END (save & quit) closes the recording on the
 *   first out-of-game frame.
 */
public final class GameRecorder
{
	/** Native calls (NativeWrapper statics). Must be invoked on the game thread. */
	public interface NativeBridge
	{
		byte[] gameIdentity();
		void resetSession();
	}

	public interface Clock
	{
		long nowMs();
	}

	public interface PerfLog
	{
		void log(String line);
	}

	static final int TAIL_MAX_FRAMES = 300;
	/** Frame turn once the game is reset (next character's menus). */
	static final int TURN_NO_GAME = -3;
	static final long OPEN_TIMEOUT_MS = 5000;
	static final long COMMIT_INTERVAL_MS = 2000;
	static final int PERF_LOG_EVERY = 1000;
	/** Bounds a random-access doc read to this many inflates. */
	static final int DOC_CHAIN_MAX = 128;
	/** Blobs read by a recursive walk; longer chains saved little on a real game. */
	static final int BLOB_CHAIN_MAX = 32;

	private enum State { IDLE, RECORDING, TAIL }

	private final RecWriterThread writer;
	private final NativeBridge nativeBridge;
	private final Clock clock;
	private final String appVersion;
	private final int rows;
	private final int cols;

	private boolean enabled;
	private State state = State.IDLE;
	private FrameChunkEncoder encoder;
	private int nextFrame;
	private int lastFrame = -1;
	private int lastTurn = -1;
	private int tailFrames;
	private GameIdentity identity;
	private boolean awaitKeySnapshot;
	private int turnLimit;
	private int lastAnchorTurn;
	private long lastCommitMs;
	private final EventDeriver deriver = new EventDeriver();
	// Writer thread only. Docs deflate against the previous docDictDepth docs;
	// the chain restarts each session so an export can drop abandoned tails.
	private final ArrayDeque<byte[]> docWindow = new ArrayDeque<>();
	private int docDictDepth = 1;
	private int docChain;
	// The open file's dictionary under every chain; -1 adler when it has none.
	private byte[] docDict;
	private long docDictAdler = -1;
	// Writer thread only. Blobs deflate against the previous blob in the same
	// slot (panel name, else kind): successive panel texts differ by a few lines.
	private final Map<String, BlobLink> blobSlots = new HashMap<>();

	private static final class BlobLink
	{
		final String hash;
		final byte[] raw;
		final int depth;

		BlobLink(String hash, byte[] raw, int depth)
		{
			this.hash = hash;
			this.raw = raw;
			this.depth = depth;
		}
	}
	private byte[] newFileDocDict;
	private PerfLog perfLog;
	private int perfFrames;
	private long perfFrameNs;
	private long perfFrameMaxNs;
	private int perfSnaps;
	private long perfSnapNs;
	private long perfSnapBytes;

	public GameRecorder(RecWriterThread writer, NativeBridge nativeBridge, Clock clock,
			String appVersion, int rows, int cols)
	{
		this.writer = writer;
		this.nativeBridge = nativeBridge;
		this.clock = clock;
		this.appVersion = appVersion;
		this.rows = rows;
		this.cols = cols;
		this.encoder = new FrameChunkEncoder(rows, cols);
	}

	public synchronized void setEnabled(boolean on)
	{
		if (enabled == on)
			return;
		enabled = on;
		if (!on)
		{
			if (state == State.RECORDING)
			{
				emit(encoder.flush());
				final int frame = lastFrame;
				final int turn = lastTurn;
				writer.submit(s -> s.writeEvent(frame, turn, EventDeriver.REC_OFF,
						"Recording turned off"));
			}
			closeRecording();
		}
		else
			awaitKeySnapshot = true; // caller also resets native session
	}

	/**
	 * Keep only about the last n turns of the game in progress (0 = all).
	 * Cuts land on anchors, added every n/10 turns, so up to n + n/10 remain.
	 */
	public synchronized void setTurnLimit(int n)
	{
		if (turnLimit == n)
			return;
		turnLimit = n;
		if (n > 0 && state == State.RECORDING)
		{
			final int cut = lastTurn - n;
			writer.submit(s -> s.trimBefore(cut));
		}
	}

	public synchronized boolean isEnabled() { return enabled; }

	public synchronized boolean isRecording() { return state != State.IDLE; }

	/** Debug timing: Java-side frame encode and snapshot handling costs. */
	public synchronized void setPerfLog(PerfLog log) { perfLog = log; }

	/** Doc dictionary embedded in files created from now on; null for none. */
	public synchronized void setDocDict(byte[] dict)
	{
		newFileDocDict = dict != null && dict.length > 0 && dict.length <= Deflate.WINDOW
				? dict.clone() : null;
	}

	// ------------------------------------------------------------ game thread

	public synchronized void onLayout(int[] layout)
	{
		encoder.layout(clock.nowMs(), layout);
	}

	/**
	 * turn/aut: -1 = not in a game (death screens), TURN_NO_GAME after the game
	 * is reset; callers drop UI-thread repaints (-2).
	 */
	public synchronized void onFrame(char[] chars, int[] fg, int[] bg, int turn, int aut)
	{
		if (!enabled)
			return;
		long now = clock.nowMs();

		// One character per file: the tail ends at the reset, before the next game.
		if (state == State.TAIL && (turn == TURN_NO_GAME || turn >= 0))
			closeRecording();
		if (turn >= 0 && state == State.IDLE)
			openRecording(turn, aut);
		else if (turn < 0 && state == State.RECORDING)
			closeRecording(); // left the game without dying (save & quit)
		if (state == State.IDLE)
			return;

		int flags = 0;
		if (state == State.TAIL)
		{
			flags |= RecFormat.FRAME_POST_GAME;
			if (++tailFrames > TAIL_MAX_FRAMES)
			{
				closeRecording();
				return;
			}
		}

		final long t0 = perfLog != null ? System.nanoTime() : 0;
		FrameChunkEncoder.Chunk closed = encoder.frame(nextFrame, now, turn, aut, chars, fg, bg);
		encoder.lastFrame().flags |= flags;
		lastFrame = nextFrame++;
		if (turn >= 0)
			lastTurn = turn;
		emit(closed);
		maybeCommit(now);
		if (perfLog != null)
			notePerfFrame(System.nanoTime() - t0);
	}

	public synchronized void onHudTitle(String texts, int[] colours)
	{
		if (enabled) encoder.hudTitle(clock.nowMs(), texts, colours);
	}

	public synchronized void onHudVitals(String texts, int[] colours)
	{
		if (enabled) encoder.hudVitals(clock.nowMs(), texts, colours);
	}

	public synchronized void onHudBars(int[] hp, int[] mp)
	{
		if (enabled) encoder.hudBars(clock.nowMs(), hp, mp);
	}

	public synchronized void onStatusLights(String texts, int[] colours)
	{
		if (enabled) encoder.statusLights(clock.nowMs(), texts, colours);
	}

	public synchronized void onMessageHistoryMode(boolean active)
	{
		if (enabled) encoder.messageHistoryMode(clock.nowMs(), active);
	}

	public synchronized void onMessageHistory(char[] chars, int[] fg, int[] bg)
	{
		if (enabled) encoder.messageHistory(clock.nowMs(), chars, fg, bg);
	}

	public synchronized void onCharacterLogMode(boolean active)
	{
		if (enabled) encoder.characterLogMode(clock.nowMs(), active);
	}

	public synchronized void onMapAnchor(int col, int row)
	{
		if (enabled) encoder.mapAnchor(clock.nowMs(), col, row);
	}

	/** Game is about to block for a key: the last frame is what the player saw. */
	public synchronized void onInputWait()
	{
		if (!enabled || state == State.IDLE)
			return;
		FrameChunkEncoder.FrameInfo f = encoder.lastFrame();
		if (f == null)
			return;
		if (encoder.isOpen())
			f.flags |= RecFormat.FRAME_INPUT_WAIT; // written with its chunk
		else
		{
			final int frame = f.frame;
			final int flags = f.flags | RecFormat.FRAME_INPUT_WAIT;
			writer.submit(s -> s.updateFrameFlags(frame, flags));
		}
	}

	public synchronized void onInput(int key)
	{
		if (!enabled || state == State.IDLE || lastFrame < 0)
			return;
		final int frame = lastFrame;
		writer.submit(s -> s.writeInput(frame, key));
	}

	public synchronized void onSnapshot(byte[] payload)
	{
		if (!enabled)
			return;
		final long t0 = perfLog != null ? System.nanoTime() : 0;
		final SnapshotPayload p;
		try
		{
			p = SnapshotPayload.parse(payload);
		}
		catch (RuntimeException e)
		{
			return; // TODO: log; a bad snapshot must never crash the game
		}
		if (state == State.IDLE)
		{
			openRecording(p.turn, (int) p.aut);
			// openRecording reset native caches: this payload's map is a delta
			// against stale caches. Drop it; the next one is a keyframe.
			return;
		}
		final boolean anchor = awaitKeySnapshot;
		if (anchor)
		{
			if (!p.mapKeyframe)
				return;
			awaitKeySnapshot = false;
			lastAnchorTurn = p.turn;
		}
		final int frame = attachFrame();
		final List<EventDeriver.Event> events = deriver.derive(p);
		final int turn = p.turn;
		final int limit = state == State.RECORDING ? turnLimit : 0;
		writer.submit(s ->
		{
			if (anchor)
				resetDocChain(docDictDepth, docDict);
			writeBlobs(s, p, frame);
			final byte[] raw = p.docJson.getBytes(StandardCharsets.UTF_8);
			byte[] dict = Deflate.windowDict(docDict, docWindow);
			// A window with the file dictionary's Adler-32 would read as a chain
			// start (Deflate.startsDocChain), so start one here.
			if (!docWindow.isEmpty() && docDictAdler >= 0 && Deflate.adler(dict) == docDictAdler)
			{
				docWindow.clear();
				docChain = 0;
				dict = docDict;
			}
			s.writeSnapshot(frame, p, Deflate.deflate(raw, dict), anchor);
			if (++docChain % DOC_CHAIN_MAX == 0)
				docWindow.clear();
			else
			{
				docWindow.addLast(raw);
				if (docWindow.size() > docDictDepth)
					docWindow.removeFirst();
			}
			for (EventDeriver.Event e : events)
				s.writeEvent(frame, turn, e.type, e.label);
			if (anchor && limit > 0)
				s.trimBefore(turn - limit);
		});
		// Checkpoint: the next snapshot restarts every chain, so a trim can cut there.
		if (limit > 0 && p.turn - lastAnchorTurn >= limit / 10)
		{
			nativeBridge.resetSession();
			awaitKeySnapshot = true;
		}
		// Resting/waiting may not redraw the grid, so frames alone can't drive commits.
		maybeCommit(clock.nowMs());
		if (perfLog != null)
		{
			perfSnaps++;
			perfSnapNs += System.nanoTime() - t0;
			perfSnapBytes += payload.length;
		}
	}

	public synchronized void onMessage(byte[] utf8, int channel, int turn, boolean temporary)
	{
		if (!enabled || state == State.IDLE)
			return;
		final String text = new String(utf8, StandardCharsets.UTF_8);
		final int frame = attachFrame();
		final boolean malev = text.contains("malevolent force fills");
		final String plain = malev ? text.replaceAll("<[^<>]*>", "") : null;
		writer.submit(s ->
		{
			s.writeMessage(frame, turn, channel, text, temporary);
			if (malev)
				s.writeEvent(frame, turn, EventDeriver.MALEVOLENCE, plain);
		});
	}

	public synchronized void onMessageRollback(int count)
	{
		if (!enabled || state == State.IDLE)
			return;
		writer.submit(s -> s.rollbackMessages(count));
	}

	/** False if nothing was being recorded. */
	public synchronized boolean onGameEnd(int deathType, byte[] reason, byte[] morgueFile)
	{
		if (!enabled || state == State.IDLE)
			return false;
		final String r = new String(reason, StandardCharsets.UTF_8);
		final String m = new String(morgueFile, StandardCharsets.UTF_8);
		emit(encoder.flush()); // the death frame lands in the same commit as the end meta
		final int frame = attachFrame();
		final int turn = lastTurn;
		writer.submit(s ->
		{
			s.setMeta("end_reason", r);
			s.setMeta("death_type", Integer.toString(deathType));
			s.setMeta("morgue", m); // filename only, never a path
			s.writeEvent(frame, turn, EventDeriver.GAME_END, r);
			s.commit();
		});
		state = State.TAIL;
		tailFrames = 0;
		nativeBridge.resetSession();
		return true;
	}

	// --------------------------------------------------------------- any thread

	/** Save marker + flush (NativeWrapper.notifyGameSaved, onPause). */
	public synchronized void onGameSaved()
	{
		if (state != State.RECORDING)
			return;
		emit(encoder.flush());
		final int frame = lastFrame;
		final int turn = lastTurn;
		writer.submit(s ->
		{
			s.writeEvent(frame, turn, EventDeriver.SAVE, "Saved");
			s.commit();
		});
	}

	/** Flush and commit without a save marker (onPause, onStop). */
	public synchronized void flush()
	{
		if (state == State.IDLE)
			return;
		emit(encoder.flush());
		writer.submit(RecordingSink::commit);
	}

	public synchronized void close()
	{
		closeRecording();
	}

	// ----------------------------------------------------------------- internal

	private void openRecording(int turn, int aut)
	{
		final GameIdentity id;
		try
		{
			id = GameIdentity.parse(nativeBridge.gameIdentity());
		}
		catch (RuntimeException e)
		{
			return;
		}
		RecordingSink.OpenResult r;
		try
		{
			r = writer.open(id, appVersion, encoder.state().layout, newFileDocDict,
					OPEN_TIMEOUT_MS);
		}
		catch (Exception e)
		{
			return; // stays IDLE; retried on the next in-game frame
		}
		identity = id;
		nativeBridge.resetSession();
		awaitKeySnapshot = true;
		deriver.reset();

		encoder.flush(); // drop anything encoded out of game
		state = State.RECORDING;
		final int depth = r.docDictDepth;
		final byte[] dict = r.docDict;
		if (r.created)
		{
			nextFrame = 0;
			final int first = nextFrame;
			writer.submit(s ->
			{
				resetDocChain(depth, dict);
				s.startSession(RecFormat.SESSION_NEW, first, -1, -1);
				// Recording enabled mid-game (or on an old save).
				if (turn > 0)
					s.writeEvent(first, turn, EventDeriver.GAP, gapLabel(0, turn));
			});
		}
		else
		{
			// Resume: continue numbering in the file.
			nextFrame = r.nextFrame;
			final int first = nextFrame;
			final int recordedTo = r.lastTurn;
			writer.submit(s ->
			{
				resetDocChain(depth, dict);
				s.startSession(RecFormat.SESSION_RESUME, first, turn, aut);
				s.writeEvent(first, turn, EventDeriver.RESUME, "Resumed");
				// Played with recording off (or on another device) since the last frame.
				if (recordedTo >= 0 && turn > recordedTo)
					s.writeEvent(first, turn, EventDeriver.GAP, gapLabel(recordedTo, turn));
			});
		}
		lastCommitMs = clock.nowMs();
	}

	private void resetDocChain(int depth, byte[] dict)
	{
		docWindow.clear();
		docDictDepth = depth;
		docDict = dict;
		docDictAdler = dict != null ? Deflate.adler(dict) : -1;
		docChain = 0;
		blobSlots.clear();
	}

	private void closeRecording()
	{
		if (state == State.IDLE)
		{
			encoder.flush();
			return;
		}
		emit(encoder.flush());
		writer.closeSink();
		state = State.IDLE;
		identity = null;
		nextFrame = 0;
		lastFrame = -1;
	}

	static String gapLabel(int fromTurn, int toTurn)
	{
		return "Turns " + fromTurn + "–" + toTurn + " not recorded";
	}

	private void notePerfFrame(long ns)
	{
		perfFrames++;
		perfFrameNs += ns;
		perfFrameMaxNs = Math.max(perfFrameMaxNs, ns);
		if (perfFrames < PERF_LOG_EVERY)
			return;
		perfLog.log("java: frames n=" + perfFrames + " avg=" + perfFrameNs / perfFrames / 1000
				+ "us max=" + perfFrameMaxNs / 1000 + "us; snapshots n=" + perfSnaps
				+ (perfSnaps > 0 ? " avg=" + perfSnapNs / perfSnaps / 1000 + "us avg_payload="
						+ perfSnapBytes / perfSnaps + "B" : "")
				+ "; writer queue=" + writer.pendingOps());
		perfFrames = 0;
		perfFrameNs = perfFrameMaxNs = 0;
		perfSnaps = 0;
		perfSnapNs = perfSnapBytes = 0;
	}

	/** Frame a snapshot/message belongs to: the frame on screen when it happened. */
	private int attachFrame()
	{
		return lastFrame >= 0 ? lastFrame : nextFrame;
	}

	private void emit(FrameChunkEncoder.Chunk c)
	{
		if (c == null)
			return;
		if (state == State.IDLE)
			return;
		writer.submit(s -> s.writeChunk(c.firstFrame, c.lastFrame, Deflate.deflate(c.raw), c.frames));
	}

	private void maybeCommit(long now)
	{
		if (state != State.IDLE && now - lastCommitMs >= COMMIT_INTERVAL_MS)
		{
			lastCommitMs = now;
			writer.submit(RecordingSink::commit);
		}
	}

	private void writeBlobs(RecordingSink s, SnapshotPayload p, int frame)
	{
		Map<String, String> panelOf = new HashMap<>();
		Map<String, Object> panels = MiniJson.getObject(p.doc, "panels");
		if (panels != null)
			for (Map.Entry<String, Object> e : panels.entrySet())
				if (e.getValue() instanceof String)
					panelOf.put((String) e.getValue(), e.getKey());
		for (SnapshotPayload.Blob b : p.blobs)
		{
			String panel = panelOf.get(b.hash);
			String slot = panel != null ? "p:" + panel : "k:" + b.kind;
			BlobLink prev = blobSlots.get(slot);
			// Depth bound keeps a random-access read to BLOB_CHAIN_MAX inflates.
			if (prev != null && prev.depth + 1 >= BLOB_CHAIN_MAX)
				prev = null;
			byte[] z = Deflate.deflate(b.utf8, prev == null ? null : prev.raw);
			if (s.writeBlob(b.hash, b.kind, z, prev == null ? null : prev.hash, frame))
				blobSlots.put(slot, new BlobLink(b.hash, b.utf8, prev == null ? 0 : prev.depth + 1));
			else
				blobSlots.remove(slot); // stored earlier with an unknown chain depth
		}
	}

	public synchronized GameIdentity identity() { return identity; }
}
