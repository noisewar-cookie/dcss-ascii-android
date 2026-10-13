package com.crawlmb.recording;

import android.content.ContentResolver;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Imports a shared .dcrec. The incoming file is untrusted:
 * it is copied into private storage, size-capped, opened read-only, integrity
 * checked, and structurally validated (ImportValidator) before anything else
 * reads it. Runs on a background thread.
 */
public final class RecordingImporter
{
	public static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024;

	public static final class Result
	{
		public final File file;      // non-null on success
		public final String error;   // e.g. "newer_schema", "not_a_recording", "corrupt"
		public final Map<String, String> meta;

		Result(File file, String error, Map<String, String> meta)
		{
			this.file = file;
			this.error = error;
			this.meta = meta;
		}

		public boolean ok() { return file != null; }
	}

	private RecordingImporter() {}

	public static Result importFrom(ContentResolver cr, Uri uri, File importsDir, long maxBytes)
	{
		if (!importsDir.isDirectory() && !importsDir.mkdirs())
			return new Result(null, "io_error", null);
		File tmp = new File(importsDir, "import-" + System.currentTimeMillis() + ".tmp");
		try
		{
			try (InputStream in = cr.openInputStream(uri))
			{
				if (in == null)
					return new Result(null, "io_error", null);
				copyCapped(in, tmp, maxBytes);
			}
			String headerErr = fixHeader(tmp);
			if (headerErr != null)
				return fail(tmp, headerErr, null);

			Map<String, String> meta;
			SQLiteDatabase db = SQLiteDatabase.openDatabase(tmp.getPath(), null,
					SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
			try
			{
				try (Cursor c = db.rawQuery("PRAGMA quick_check", null))
				{
					if (!c.moveToFirst() || !"ok".equals(c.getString(0)))
						return fail(tmp, "corrupt", null);
				}
				List<ImportValidator.MasterRow> master = new ArrayList<>();
				try (Cursor c = db.rawQuery("SELECT type, name, tbl_name FROM sqlite_master", null))
				{
					while (c.moveToNext())
						master.add(new ImportValidator.MasterRow(c.getString(0), c.getString(1),
								c.getString(2)));
				}
				// Reject foreign objects before querying anything else.
				for (ImportValidator.MasterRow r : master)
					if (!"table".equals(r.type) && !"index".equals(r.type))
						return fail(tmp, "not_a_recording", null);
				Map<String, Set<String>> cols = new HashMap<>();
				for (String table : RecordingSchema.REQUIRED.keySet())
				{
					Set<String> names = new HashSet<>();
					// table is from our allow-list, never from the file.
					try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null))
					{
						while (c.moveToNext())
							names.add(c.getString(1));
					}
					if (!names.isEmpty())
						cols.put(table, names);
				}
				ImportValidator.Result s = ImportValidator.validateStructure(master, cols);
				if (!s.ok)
					return fail(tmp, "not_a_recording", null);
				meta = RecordingExporter.readMeta(db);
				ImportValidator.Result m = ImportValidator.validateMeta(meta);
				if (!m.ok)
					return fail(tmp, "newer_schema".equals(m.error) ? "newer_schema" : "not_a_recording",
							meta);
			}
			finally
			{
				db.close();
			}

			String base = "imported_" + System.currentTimeMillis() + RecFormat.FILE_EXTENSION;
			File out = RecordingExporter.unique(importsDir, base);
			if (!tmp.renameTo(out))
				return fail(tmp, "io_error", null);
			return new Result(out, null, meta);
		}
		catch (IOException e)
		{
			return fail(tmp, "io_error", null);
		}
		catch (RuntimeException e) // SQLiteException etc.
		{
			return fail(tmp, "corrupt", null);
		}
	}

	private static Result fail(File tmp, String err, Map<String, String> meta)
	{
		RecordingExporter.deleteWithSidecars(tmp);
		return new Result(null, err, meta);
	}

	private static void copyCapped(InputStream in, File dst, long max) throws IOException
	{
		long total = 0;
		try (OutputStream out = new FileOutputStream(dst))
		{
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) > 0)
			{
				total += n;
				if (total > max)
					throw new IOException("file too large");
				out.write(buf, 0, n);
			}
		}
	}

	/**
	 * Checks the SQLite magic and, for WAL-mode files, flips header bytes 18/19
	 * to rollback-journal mode so a read-only open doesn't need -shm/-wal files.
	 */
	static String fixHeader(File f) throws IOException
	{
		try (RandomAccessFile raf = new RandomAccessFile(f, "rw"))
		{
			if (raf.length() < 100)
				return "not_a_recording";
			byte[] h = new byte[100];
			raf.readFully(h);
			byte[] magic = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
			for (int i = 0; i < magic.length; i++)
				if (h[i] != magic[i])
					return "not_a_recording";
			if (h[18] == 2 || h[19] == 2)
			{
				raf.seek(18);
				raf.write(new byte[] { 1, 1 });
			}
			return null;
		}
	}
}
