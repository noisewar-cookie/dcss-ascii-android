package com.crawlmb.recording;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates an untrusted .dcrec before any other query touches it.
 * Android's Java SQLite API can't enable SQLITE_DBCONFIG_DEFENSIVE
 * or trusted_schema=OFF, so instead we refuse any file that contains objects
 * we didn't create: triggers, views, unknown tables or indexes. Pure Java.
 */
public final class ImportValidator
{
	public static final class Result
	{
		public final boolean ok;
		public final String error;
		public final int schemaVersion;

		private Result(boolean ok, String error, int schemaVersion)
		{
			this.ok = ok;
			this.error = error;
			this.schemaVersion = schemaVersion;
		}

		static Result fail(String e) { return new Result(false, e, -1); }
	}

	/** One sqlite_master row. */
	public static final class MasterRow
	{
		public final String type, name, tblName;

		public MasterRow(String type, String name, String tblName)
		{
			this.type = type;
			this.name = name;
			this.tblName = tblName;
		}
	}

	private ImportValidator() {}

	/**
	 * @param master   all rows of sqlite_master
	 * @param columns  PRAGMA table_info names per known table (only queried for allow-listed tables)
	 * @param meta     meta table contents (only read after the structure passed)
	 */
	public static Result validateStructure(List<MasterRow> master, Map<String, Set<String>> columns)
	{
		for (MasterRow r : master)
		{
			if (r.name != null && r.name.startsWith("sqlite_"))
				continue; // sqlite_autoindex_*, sqlite_sequence, sqlite_stat*
			switch (r.type == null ? "" : r.type)
			{
			case "table":
				if (!RecordingSchema.REQUIRED.containsKey(r.name))
					return Result.fail("unexpected table: " + r.name);
				break;
			case "index":
				if (!RecordingSchema.KNOWN_INDEXES.contains(r.name)
						|| !RecordingSchema.REQUIRED.containsKey(r.tblName))
					return Result.fail("unexpected index: " + r.name);
				break;
			default: // trigger, view, anything else
				return Result.fail("unexpected " + r.type + ": " + r.name);
			}
		}
		for (Map.Entry<String, Set<String>> e : RecordingSchema.REQUIRED.entrySet())
		{
			Set<String> have = columns.get(e.getKey());
			if (have == null)
				return Result.fail("missing table: " + e.getKey());
			for (String c : e.getValue())
				if (!have.contains(c))
					return Result.fail("missing column " + e.getKey() + "." + c);
		}
		return new Result(true, null, -1);
	}

	/** Oldest reader version able to play the file; pre-v3 files only have schema_version. */
	public static int minReaderVersion(Map<String, String> meta)
	{
		String v = meta.get("min_reader_version");
		if (v == null)
			v = meta.get("schema_version");
		try
		{
			return Integer.parseInt(v);
		}
		catch (RuntimeException e)
		{
			return Integer.MAX_VALUE;
		}
	}

	public static Result validateMeta(Map<String, String> meta)
	{
		String v = meta.get("schema_version");
		int ver;
		try
		{
			ver = Integer.parseInt(v);
		}
		catch (RuntimeException e)
		{
			return Result.fail("missing schema_version");
		}
		if (ver < 1)
			return Result.fail("bad schema_version");
		int need = minReaderVersion(meta);
		if (need > RecFormat.SCHEMA_VERSION)
			return new Result(false, "newer_schema", need); // UI: "update the app"
		String id = meta.get("identity");
		if (id == null)
			return Result.fail("missing identity");
		try
		{
			MiniJson.parseObject(id);
		}
		catch (RuntimeException e)
		{
			return Result.fail("bad identity");
		}
		return new Result(true, null, ver);
	}
}
