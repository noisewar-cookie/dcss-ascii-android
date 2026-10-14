package com.crawlmb.recording;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * .dcrec storage on Android's SQLite. One instance per file.
 *
 * Writer use: created by the RecWriterThread factory; every method runs on that
 * thread inside one long-lived transaction that commit() closes and reopens.
 * Reader use: openForReplay() opens read-only (imports must pass
 * ImportValidator first — see RecordingImporter).
 */
public final class SqliteRecordingStore implements RecordingSink, RecordingReader
{
	private final File file;
	private SQLiteDatabase db;
	private final boolean readOnly;
	private List<int[]> abandonedCache; // reader side
	private long lastDocId = -1; // reader side: chained-doc cache
	private ArrayDeque<byte[]> lastWindow = new ArrayDeque<>(); // docs up to lastDocId
	private int docDictDepth; // 0 until read from meta
	private Boolean blobBases; // reader side: pre-v3 files lack blobs.base
	// Reader side: recent blob texts, so stepping through a base chain stays cheap.
	private final Map<String, byte[]> blobCache = new LinkedHashMap<String, byte[]>(64, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, byte[]> e)
		{
			return size() > 64;
		}
	};

	private SQLiteStatement insFrame;
	private SQLiteStatement insMessage;

	private SqliteRecordingStore(File file, boolean readOnly)
	{
		this.file = file;
		this.readOnly = readOnly;
	}

	public File file() { return file; }

	/** Writer: create or reopen. Caller then calls open(identity, ...). */
	public static SqliteRecordingStore openForWriting(File file)
	{
		SqliteRecordingStore s = new SqliteRecordingStore(file, false);
		file.getParentFile().mkdirs();
		s.db = SQLiteDatabase.openDatabase(file.getPath(), null,
				SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
		s.db.enableWriteAheadLogging();
		for (String ddl : RecordingSchema.DDL)
			s.db.execSQL(ddl);
		for (String[] col : RecordingSchema.ADDED_COLUMNS)
			if (!s.hasColumn(col[0], col[1]))
				s.db.execSQL("ALTER TABLE " + col[0] + " ADD COLUMN " + col[1] + " " + col[2]);
		s.insFrame = s.db.compileStatement(
				"INSERT OR REPLACE INTO frames(frame, chunk_id, t_ms, turn, aut, flags) VALUES (?,?,?,?,?,?)");
		s.insMessage = s.db.compileStatement(
				"INSERT INTO messages(frame, turn, channel, temp, text) VALUES (?,?,?,?,?)");
		s.db.beginTransaction();
		return s;
	}

	/**
	 * GameIdentity.key() of an existing file, null if it has no identity yet.
	 * Throws if the file can't be read (treat as belonging to another game).
	 * Read-write so a leftover -wal from a crash can be recovered.
	 */
	public static String identityKey(File file)
	{
		try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
				SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
				Cursor c = db.rawQuery("SELECT value FROM meta WHERE key = 'identity'", null))
		{
			return c.moveToFirst()
					? GameIdentity.parse(c.getString(0).getBytes(StandardCharsets.UTF_8)).key()
					: null;
		}
	}

	/** Recorded game identity, or null if unreadable (library list). */
	public static GameIdentity identity(File file)
	{
		try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
				SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
				Cursor c = db.rawQuery("SELECT value FROM meta WHERE key = 'identity'", null))
		{
			return c.moveToFirst()
					? GameIdentity.parse(c.getString(0).getBytes(StandardCharsets.UTF_8))
					: null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** Recorded kill_method_type of a finished game, or -1 (library list). */
	public static int deathType(File file)
	{
		try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
				SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
				Cursor c = db.rawQuery("SELECT value FROM meta WHERE key = 'death_type'", null))
		{
			return c.moveToFirst() ? Integer.parseInt(c.getString(0)) : -1;
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}

	/** Reader: read-only. For imported files, validate first (RecordingImporter). */
	public static SqliteRecordingStore openForReplay(File file)
	{
		SqliteRecordingStore s = new SqliteRecordingStore(file, true);
		s.db = SQLiteDatabase.openDatabase(file.getPath(), null,
				SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
		Map<String, String> v = new LinkedHashMap<>();
		v.put("schema_version", s.metaValue("schema_version"));
		v.put("min_reader_version", s.metaValue("min_reader_version"));
		if (ImportValidator.minReaderVersion(v) > RecFormat.SCHEMA_VERSION)
		{
			s.close();
			throw new ByteIO.FormatException("recording needs a newer app");
		}
		return s;
	}

	// =================================================================== sink

	@Override
	public OpenResult open(GameIdentity id, String appVersion, int[] layout)
	{
		String existing = metaValue("identity");
		if (existing != null)
		{
			long next = longQuery("SELECT IFNULL(MAX(frame), -1) + 1 FROM frames");
			long lastTurn = longQuery("SELECT IFNULL(MAX(turn), -1) FROM frames WHERE turn >= 0");
			setMeta("app_version_last", appVersion);
			setVersions();
			return new OpenResult(false, (int) next, (int) lastTurn, fileDocDictDepth());
		}
		setVersions();
		setMeta("codec", RecFormat.CODEC_DEFLATE);
		setMeta("doc_dict_depth", Integer.toString(RecFormat.DOC_DICT_DEPTH));
		setMeta("identity", id.json);
		setMeta("dcss_version", id.dcssVersion);
		setMeta("app_version", appVersion);
		setMeta("created_utc", Long.toString(System.currentTimeMillis()));
		setMeta("grid", TerminalSize.ROWS + "x" + TerminalSize.COLS);
		setMeta("layout", join(layout));
		return new OpenResult(true, 0, -1, RecFormat.DOC_DICT_DEPTH);
	}

	@Override
	public void startSession(String kind, int firstFrame, int restoredTurn, long restoredAut)
	{
		ContentValues v = new ContentValues();
		v.put("kind", kind);
		v.put("first_frame", firstFrame);
		v.put("restored_turn", restoredTurn);
		v.put("restored_aut", restoredAut);
		v.put("created_utc", System.currentTimeMillis());
		v.put("version", RecFormat.SCHEMA_VERSION);
		long sid = db.insertOrThrow("sessions", null, v);
		if (!RecFormat.SESSION_RESUME.equals(kind))
			return;
		// Frames from earlier sessions that are "in the future" of the restored
		// save belong to an abandoned branch (crash after save, backup restore).
		long from = longQuery("SELECT IFNULL(MIN(f.frame), -1) FROM frames f WHERE f.turn >= 0"
				+ " AND f.aut > " + restoredAut + " AND f.frame < " + firstFrame
				+ " AND NOT EXISTS (SELECT 1 FROM abandoned a"
				+ " WHERE f.frame >= a.from_frame AND f.frame < a.to_frame)");
		if (from >= 0)
		{
			ContentValues a = new ContentValues();
			a.put("session_id", sid);
			a.put("from_frame", from);
			a.put("to_frame", firstFrame);
			db.insertOrThrow("abandoned", null, a);
		}
	}

	@Override
	public void writeChunk(int firstFrame, int lastFrame, byte[] deflated,
			List<FrameChunkEncoder.FrameInfo> frames)
	{
		ContentValues v = new ContentValues();
		v.put("first_frame", firstFrame);
		v.put("last_frame", lastFrame);
		v.put("data", deflated);
		long chunkId = db.insertOrThrow("frame_chunks", null, v);
		for (FrameChunkEncoder.FrameInfo f : frames)
		{
			insFrame.clearBindings();
			insFrame.bindLong(1, f.frame);
			insFrame.bindLong(2, chunkId);
			insFrame.bindLong(3, f.tMs);
			insFrame.bindLong(4, f.turn);
			insFrame.bindLong(5, f.aut);
			insFrame.bindLong(6, f.flags);
			insFrame.executeInsert();
		}
	}

	@Override
	public void updateFrameFlags(int frame, int flags)
	{
		ContentValues v = new ContentValues();
		v.put("flags", flags);
		db.update("frames", v, "frame = ?", new String[] { Integer.toString(frame) });
	}

	@Override
	public boolean writeBlob(String hash, int kind, byte[] deflated, String base)
	{
		ContentValues v = new ContentValues();
		v.put("hash", hash);
		v.put("kind", kind);
		v.put("data", deflated);
		v.put("base", base);
		return db.insertWithOnConflict("blobs", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
	}

	@Override
	public void writeSnapshot(int frame, SnapshotPayload p, byte[] docDeflated)
	{
		ContentValues v = new ContentValues();
		v.put("frame", frame);
		v.put("turn", p.turn);
		v.put("aut", p.aut);
		v.put("kind", p.kind);
		v.put("level", p.level);
		v.put("has_panels", p.hasPanels ? 1 : 0);
		v.put("map_key", p.mapKeyframe ? 1 : 0);
		v.put("hp", p.hp);
		v.put("hp_max", p.hpMax);
		v.put("mp", p.mp);
		v.put("mp_max", p.mpMax);
		v.put("xl", p.xl);
		v.put("depth", p.depth);
		v.put("doc", docDeflated);
		db.insertOrThrow("snapshots", null, v);
	}

	@Override
	public void writeMessage(int frame, int turn, int channel, String text, boolean temporary)
	{
		insMessage.clearBindings();
		insMessage.bindLong(1, frame);
		insMessage.bindLong(2, turn);
		insMessage.bindLong(3, channel);
		insMessage.bindLong(4, temporary ? 1 : 0);
		insMessage.bindString(5, text);
		insMessage.executeInsert();
	}

	@Override
	public void rollbackMessages(int count)
	{
		db.execSQL("DELETE FROM messages WHERE id IN (SELECT id FROM messages WHERE temp = 1"
				+ " ORDER BY id DESC LIMIT " + Math.max(0, count) + ")");
	}

	@Override
	public void writeInput(int frame, int key)
	{
		ContentValues v = new ContentValues();
		v.put("frame", frame);
		v.put("key", key);
		db.insertOrThrow("inputs", null, v);
	}

	@Override
	public void writeEvent(int frame, int turn, String type, String label)
	{
		ContentValues v = new ContentValues();
		v.put("frame", frame);
		v.put("turn", turn);
		v.put("type", type);
		v.put("label", label);
		db.insertOrThrow("events", null, v);
	}

	@Override
	public void setMeta(String key, String value)
	{
		ContentValues v = new ContentValues();
		v.put("key", key);
		v.put("value", value);
		db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
	}

	@Override
	public void commit()
	{
		if (readOnly || db == null || !db.inTransaction())
			return;
		db.setTransactionSuccessful();
		db.endTransaction();
		db.beginTransaction();
	}

	@Override
	public void close()
	{
		if (db == null)
			return;
		if (!readOnly && db.inTransaction())
		{
			db.setTransactionSuccessful();
			db.endTransaction();
		}
		db.close();
		db = null;
	}

	// ================================================================= reader

	@Override
	public Map<String, String> meta()
	{
		Map<String, String> m = new LinkedHashMap<>();
		try (Cursor c = db.rawQuery("SELECT key, value FROM meta", null))
		{
			while (c.moveToNext())
				m.put(c.getString(0), c.getString(1));
		}
		return m;
	}

	@Override
	public FrameIndex frameIndex()
	{
		List<int[]> ab = abandoned();
		try (Cursor c = db.rawQuery(
				"SELECT frame, t_ms, turn, aut, flags, chunk_id FROM frames ORDER BY frame", null))
		{
			int n = c.getCount();
			int[] fr = new int[n], tu = new int[n], au = new int[n], fl = new int[n], ch = new int[n];
			long[] t = new long[n];
			boolean[] a = new boolean[n];
			for (int i = 0; c.moveToNext(); i++)
			{
				fr[i] = c.getInt(0);
				t[i] = c.getLong(1);
				tu[i] = c.getInt(2);
				au[i] = c.getInt(3);
				fl[i] = c.getInt(4);
				ch[i] = c.getInt(5);
				a[i] = inRanges(ab, fr[i]);
			}
			return new FrameIndex(fr, t, tu, au, fl, ch, a);
		}
	}

	@Override
	public byte[] chunk(int chunkId)
	{
		try (Cursor c = db.rawQuery("SELECT data FROM frame_chunks WHERE id = ?",
				new String[] { Integer.toString(chunkId) }))
		{
			if (!c.moveToFirst())
				throw new ByteIO.FormatException("missing chunk " + chunkId);
			return Deflate.inflate(c.getBlob(0));
		}
	}

	@Override
	public List<SnapshotRow> snapshotIndex()
	{
		List<int[]> ab = abandoned();
		List<SnapshotRow> out = new ArrayList<>();
		try (Cursor c = db.rawQuery("SELECT id, frame, turn, aut, kind, level, has_panels, map_key,"
				+ " hp, hp_max, mp, mp_max, xl, depth FROM snapshots ORDER BY frame, id", null))
		{
			while (c.moveToNext())
			{
				int frame = c.getInt(1);
				if (inRanges(ab, frame))
					continue;
				out.add(new SnapshotRow(c.getLong(0), frame, c.getInt(2), c.getLong(3),
						c.getString(4), c.getString(5) == null ? "" : c.getString(5),
						c.getInt(6) != 0, c.getInt(7) != 0, c.getInt(8), c.getInt(9),
						c.getInt(10), c.getInt(11), c.getInt(12), c.getInt(13)));
			}
		}
		return out;
	}

	@Override
	public synchronized String snapshotDoc(long id)
	{
		if (id != lastDocId)
		{
			// A chained doc (FDICT) inflates against the previous docDictDepth
			// rows' docs; walk back to the chain start or the cached doc.
			if (docDictDepth == 0)
				docDictDepth = fileDocDictDepth();
			Deque<byte[]> chain = new ArrayDeque<>();
			ArrayDeque<byte[]> window = new ArrayDeque<>();
			for (long cur = id; ; )
			{
				byte[] z = rawDoc(cur);
				chain.push(z);
				if (!Deflate.usesDictionary(z))
					break;
				if (chain.size() > 2 * GameRecorder.DOC_CHAIN_MAX)
					throw new ByteIO.FormatException("doc chain too long at " + id);
				cur = longQuery("SELECT IFNULL(MAX(id), -1) FROM snapshots WHERE id < " + cur);
				if (cur == lastDocId)
				{
					window = new ArrayDeque<>(lastWindow);
					break;
				}
			}
			for (byte[] z : chain)
			{
				if (!Deflate.usesDictionary(z))
					window.clear();
				window.addLast(Deflate.inflate(z, SnapshotPayload.MAX_DOC_BYTES,
						Deflate.windowDict(window)));
				if (window.size() > docDictDepth)
					window.removeFirst();
			}
			lastDocId = id;
			lastWindow = window;
		}
		return new String(lastWindow.peekLast(), StandardCharsets.UTF_8);
	}

	// Files from before doc_dict_depth chained to one doc.
	private int fileDocDictDepth()
	{
		int d = intMeta("doc_dict_depth");
		return d > 0 && d <= 64 ? d : 1;
	}

	private byte[] rawDoc(long id)
	{
		try (Cursor c = db.rawQuery("SELECT doc FROM snapshots WHERE id = ?",
				new String[] { Long.toString(id) }))
		{
			if (!c.moveToFirst())
				throw new ByteIO.FormatException("missing snapshot " + id);
			return c.getBlob(0);
		}
	}

	@Override
	public synchronized String blob(String hash)
	{
		if (blobBases == null)
			blobBases = hasColumn("blobs", "base");
		byte[] b = blobBytes(hash, 0);
		return b == null ? null : new String(b, StandardCharsets.UTF_8);
	}

	private byte[] blobBytes(String hash, int depth)
	{
		byte[] cached = blobCache.get(hash);
		if (cached != null)
			return cached;
		if (depth > 2 * GameRecorder.DOC_CHAIN_MAX)
			throw new ByteIO.FormatException("blob chain too long at " + hash);
		byte[] z;
		String base;
		try (Cursor c = db.rawQuery(blobBases ? "SELECT data, base FROM blobs WHERE hash = ?"
				: "SELECT data, NULL FROM blobs WHERE hash = ?", new String[] { hash }))
		{
			if (!c.moveToFirst())
				return null;
			z = c.getBlob(0);
			base = c.getString(1);
		}
		byte[] dict = null;
		if (base != null)
		{
			dict = blobBytes(base, depth + 1);
			if (dict == null)
				throw new ByteIO.FormatException("missing base blob " + base);
		}
		byte[] out = Deflate.inflate(z, SnapshotPayload.MAX_BLOB_BYTES, dict);
		blobCache.put(hash, out);
		return out;
	}

	@Override
	public List<MessageRow> messagesUpTo(int toFrame, int limit)
	{
		List<int[]> ab = abandoned();
		List<MessageRow> out = new ArrayList<>();
		// Over-fetch to leave room for abandoned rows, then trim.
		try (Cursor c = db.rawQuery("SELECT frame, turn, channel, text FROM messages"
				+ " WHERE temp = 0 AND frame <= ? ORDER BY id DESC LIMIT ?",
				new String[] { Integer.toString(toFrame), Integer.toString(limit * 2 + 100) }))
		{
			while (c.moveToNext() && out.size() < limit)
			{
				int frame = c.getInt(0);
				if (!inRanges(ab, frame))
					out.add(new MessageRow(frame, c.getInt(1), c.getInt(2), c.getString(3)));
			}
		}
		Collections.reverse(out);
		return out;
	}

	@Override
	public List<EventRow> events()
	{
		List<EventRow> out = new ArrayList<>();
		try (Cursor c = db.rawQuery("SELECT frame, turn, type, label FROM events ORDER BY frame, id",
				null))
		{
			while (c.moveToNext())
				out.add(new EventRow(c.getInt(0), c.getInt(1), c.getString(2), c.getString(3)));
		}
		return out;
	}

	// ================================================================ helpers

	private List<int[]> abandoned()
	{
		if (readOnly && abandonedCache != null)
			return abandonedCache;
		List<int[]> r = new ArrayList<>();
		try (Cursor c = db.rawQuery("SELECT from_frame, to_frame FROM abandoned", null))
		{
			while (c.moveToNext())
				r.add(new int[] { c.getInt(0), c.getInt(1) });
		}
		if (readOnly)
			abandonedCache = r;
		return r;
	}

	private static boolean inRanges(List<int[]> ranges, int frame)
	{
		for (int[] r : ranges)
			if (frame >= r[0] && frame < r[1])
				return true;
		return false;
	}

	// meta schema_version: newest format any session wrote; min_reader_version:
	// oldest reader able to play the whole file. Both only ever rise.
	private void setVersions()
	{
		setMeta("schema_version", Integer.toString(Math.max(RecFormat.SCHEMA_VERSION,
				intMeta("schema_version"))));
		setMeta("min_reader_version", Integer.toString(Math.max(RecFormat.MIN_READER_VERSION,
				intMeta("min_reader_version"))));
	}

	private int intMeta(String key)
	{
		try
		{
			return Integer.parseInt(metaValue(key));
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	private boolean hasColumn(String table, String column)
	{
		try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null))
		{
			int name = c.getColumnIndexOrThrow("name");
			while (c.moveToNext())
				if (column.equals(c.getString(name)))
					return true;
		}
		return false;
	}

	private String metaValue(String key)
	{
		try (Cursor c = db.rawQuery("SELECT value FROM meta WHERE key = ?", new String[] { key }))
		{
			return c.moveToFirst() ? c.getString(0) : null;
		}
	}

	private long longQuery(String sql)
	{
		try (Cursor c = db.rawQuery(sql, null))
		{
			return c.moveToFirst() ? c.getLong(0) : 0;
		}
	}

	private static String join(int[] a)
	{
		StringBuilder b = new StringBuilder();
		for (int i = 0; a != null && i < a.length; i++)
			b.append(i == 0 ? "" : ",").append(a[i]);
		return b.toString();
	}

	/** Grid size recorded in meta (mirrors TerminalRenderer.FRAME_ROWS/COLS). */
	static final class TerminalSize
	{
		static final int ROWS = com.crawlmb.view.TerminalRenderer.FRAME_ROWS;
		static final int COLS = com.crawlmb.view.TerminalRenderer.FRAME_COLS;
	}
}
