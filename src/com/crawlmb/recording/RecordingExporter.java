package com.crawlmb.recording;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * Builds a clean, single-file share copy of a recording.
 * Never share the live file: WAL sidecars may hold recent writes.
 *
 * Call GameRecorder.flush() + RecWriterThread.drain() first if the game is
 * still being recorded. Runs on a background thread.
 */
public final class RecordingExporter
{
	public static final class Options
	{
		public boolean includeAbandoned = false;
		public boolean includeKeystrokes = true;
		public boolean includeSeed = true;
		public boolean includeCharCreation = true;
	}

	private RecordingExporter() {}

	public static File export(File src, File outDir, Options o) throws IOException
	{
		if (!outDir.isDirectory() && !outDir.mkdirs())
			throw new IOException("cannot create " + outDir);
		File tmp = new File(outDir, "export-" + System.currentTimeMillis() + ".tmp");
		try
		{
			snapshotCopy(src, tmp);
			File out = finish(tmp, outDir, o);
			return out;
		}
		catch (IOException | RuntimeException e)
		{
			deleteWithSidecars(tmp);
			throw e;
		}
	}

	/** Consistent single-file copy of a possibly-live WAL database. */
	static void snapshotCopy(File src, File dst) throws IOException
	{
		SQLiteDatabase db = SQLiteDatabase.openDatabase(src.getPath(), null,
				SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
		try
		{
			if (sqliteAtLeast(db, 3, 27))
			{
				// VACUUM INTO reads a consistent snapshot even while the writer
				// thread is appending, and works on a read-only connection.
				db.execSQL("VACUUM INTO ?", new Object[] { dst.getPath() });
				return;
			}
		}
		finally
		{
			db.close();
		}
		// Pre-3.27 (older Android, roughly API < 30 — verify): checkpoint the WAL
		// into the main file, then copy bytes. Requires the writer to be idle.
		SQLiteDatabase rw = SQLiteDatabase.openDatabase(src.getPath(), null,
				SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
		try (Cursor c = rw.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null))
		{
			if (c.moveToFirst() && c.getInt(0) != 0)
				throw new IOException("recording is busy; pause the game and retry");
		}
		finally
		{
			rw.close();
		}
		copy(src, dst);
	}

	private static File finish(File tmp, File outDir, Options o) throws IOException
	{
		SQLiteDatabase db = SQLiteDatabase.openDatabase(tmp.getPath(), null,
				SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
		String name;
		try
		{
			// Rollback journal, not WAL, so the shared file has no sidecars.
			try (Cursor c = db.rawQuery("PRAGMA journal_mode=DELETE", null)) { c.moveToFirst(); }

			db.beginTransaction();
			try
			{
				if (!o.includeAbandoned)
				{
					String inAb = " WHERE EXISTS (SELECT 1 FROM abandoned a WHERE %1$s >= a.from_frame"
							+ " AND %1$s < a.to_frame)";
					db.execSQL("DELETE FROM frames" + String.format(inAb, "frames.frame"));
					db.execSQL("DELETE FROM snapshots" + String.format(inAb, "snapshots.frame"));
					db.execSQL("DELETE FROM messages" + String.format(inAb, "messages.frame"));
					db.execSQL("DELETE FROM inputs" + String.format(inAb, "inputs.frame"));
					db.execSQL("DELETE FROM events" + String.format(inAb, "events.frame"));
					db.execSQL("DELETE FROM frame_chunks WHERE NOT EXISTS"
							+ " (SELECT 1 FROM frames f WHERE f.chunk_id = frame_chunks.id)");
					db.execSQL("DELETE FROM abandoned");
				}
				if (!o.includeKeystrokes)
					db.execSQL("DELETE FROM inputs");
				if (!o.includeCharCreation)
					db.execSQL("DELETE FROM frames WHERE turn < 0 AND frame <"
							+ " (SELECT IFNULL(MIN(frame), 0) FROM frames WHERE turn >= 0)");
				Map<String, String> meta = readMeta(db);
				if (!o.includeSeed && meta.get("identity") != null)
					putMeta(db, "identity",
							meta.get("identity").replaceAll("\"seed\":\"[0-9]*\"", "\"seed\":\"\""));
				putMeta(db, "exported_utc", Long.toString(System.currentTimeMillis()));
				putMeta(db, "export_options", "abandoned=" + o.includeAbandoned + ",keys="
						+ o.includeKeystrokes + ",seed=" + o.includeSeed + ",chargen="
						+ o.includeCharCreation);
				putMeta(db, "source_complete", meta.containsKey("end_reason") ? "1" : "0");
				db.setTransactionSuccessful();
			}
			finally
			{
				db.endTransaction();
			}
			db.execSQL("VACUUM");
			try (Cursor c = db.rawQuery("PRAGMA integrity_check", null))
			{
				if (!c.moveToFirst() || !"ok".equals(c.getString(0)))
					throw new IOException("integrity check failed on export copy");
			}
			name = exportName(db);
		}
		finally
		{
			db.close();
		}
		File out = unique(outDir, name);
		if (!tmp.renameTo(out))
			throw new IOException("rename failed");
		deleteWithSidecars(tmp);
		return out;
	}

	/** <name>_<species><job>_<place-or-result>_<turns>t.dcrec. */
	static String exportName(SQLiteDatabase db)
	{
		Map<String, String> meta = readMeta(db);
		Map<String, Object> id;
		try
		{
			id = MiniJson.parseObject(meta.get("identity"));
		}
		catch (RuntimeException e)
		{
			id = null;
		}
		String name = MiniJson.getString(id, "name", "game");
		String sp = abbrev(MiniJson.getString(id, "species", ""));
		String job = abbrev(MiniJson.getString(id, "job", ""));
		String place = "";
		long turns = 0;
		try (Cursor c = db.rawQuery("SELECT level, turn FROM snapshots ORDER BY frame DESC, id DESC LIMIT 1", null))
		{
			if (c.moveToFirst())
			{
				place = c.getString(0) == null ? "" : c.getString(0);
				turns = c.getLong(1);
			}
		}
		String result = meta.containsKey("end_reason") ? "end-" + place : place;
		String raw = name + "_" + sp + job + "_" + result + "_" + turns + "t";
		return raw.replaceAll("[^A-Za-z0-9_-]", "-") + RecFormat.FILE_EXTENSION;
	}

	private static String abbrev(String s)
	{
		String t = s.replaceAll("[^A-Za-z]", "");
		return t.length() <= 2 ? t : t.substring(0, 2);
	}

	static Map<String, String> readMeta(SQLiteDatabase db)
	{
		Map<String, String> m = new java.util.LinkedHashMap<>();
		try (Cursor c = db.rawQuery("SELECT key, value FROM meta", null))
		{
			while (c.moveToNext())
				m.put(c.getString(0), c.getString(1));
		}
		return m;
	}

	private static void putMeta(SQLiteDatabase db, String k, String v)
	{
		db.execSQL("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)", new Object[] { k, v });
	}

	static boolean sqliteAtLeast(SQLiteDatabase db, int major, int minor)
	{
		try (Cursor c = db.rawQuery("SELECT sqlite_version()", null))
		{
			if (!c.moveToFirst())
				return false;
			String[] p = c.getString(0).split("\\.");
			int ma = Integer.parseInt(p[0]);
			int mi = p.length > 1 ? Integer.parseInt(p[1]) : 0;
			return ma > major || (ma == major && mi >= minor);
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	static File unique(File dir, String name)
	{
		File f = new File(dir, name);
		String base = name.substring(0, name.length() - RecFormat.FILE_EXTENSION.length());
		for (int i = 2; f.exists(); i++)
			f = new File(dir, base + "-" + i + RecFormat.FILE_EXTENSION);
		return f;
	}

	static void copy(File src, File dst) throws IOException
	{
		try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst))
		{
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) > 0)
				out.write(buf, 0, n);
		}
	}

	static void deleteWithSidecars(File f)
	{
		f.delete();
		new File(f.getPath() + "-wal").delete();
		new File(f.getPath() + "-shm").delete();
		new File(f.getPath() + "-journal").delete();
	}
}
