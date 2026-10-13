package com.crawlmb.recording;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * SQLite schema for .dcrec files and the allow-list
 * the importer validates against. Pure Java.
 */
public final class RecordingSchema
{
	private RecordingSchema() {}

	public static final String[] DDL = {
		"CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)",
		"CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY, kind TEXT NOT NULL,"
			+ " first_frame INTEGER NOT NULL, restored_turn INTEGER, restored_aut INTEGER,"
			+ " created_utc INTEGER, version INTEGER)",
		// Frames [from_frame, to_frame) belong to an abandoned branch.
		"CREATE TABLE IF NOT EXISTS abandoned(id INTEGER PRIMARY KEY, session_id INTEGER,"
			+ " from_frame INTEGER NOT NULL, to_frame INTEGER NOT NULL)",
		"CREATE TABLE IF NOT EXISTS frame_chunks(id INTEGER PRIMARY KEY,"
			+ " first_frame INTEGER NOT NULL, last_frame INTEGER NOT NULL, data BLOB NOT NULL)",
		"CREATE TABLE IF NOT EXISTS frames(frame INTEGER PRIMARY KEY, chunk_id INTEGER NOT NULL,"
			+ " t_ms INTEGER NOT NULL, turn INTEGER NOT NULL, aut INTEGER NOT NULL,"
			+ " flags INTEGER NOT NULL)",
		"CREATE TABLE IF NOT EXISTS snapshots(id INTEGER PRIMARY KEY, frame INTEGER NOT NULL,"
			+ " turn INTEGER, aut INTEGER, kind TEXT, level TEXT, has_panels INTEGER,"
			+ " map_key INTEGER, hp INTEGER, hp_max INTEGER, mp INTEGER, mp_max INTEGER,"
			+ " xl INTEGER, depth INTEGER, doc BLOB NOT NULL)",
		// base: data is deflated with that blob's text as preset dictionary.
		"CREATE TABLE IF NOT EXISTS blobs(hash TEXT PRIMARY KEY, kind INTEGER, data BLOB NOT NULL,"
			+ " base TEXT)",
		"CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY, frame INTEGER NOT NULL,"
			+ " turn INTEGER, channel INTEGER, temp INTEGER NOT NULL DEFAULT 0, text TEXT)",
		"CREATE TABLE IF NOT EXISTS inputs(id INTEGER PRIMARY KEY, frame INTEGER NOT NULL,"
			+ " key INTEGER)",
		"CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY, frame INTEGER NOT NULL,"
			+ " turn INTEGER, type TEXT, label TEXT)",
		"CREATE INDEX IF NOT EXISTS idx_frames_turn ON frames(turn)",
		"CREATE INDEX IF NOT EXISTS idx_snapshots_frame ON snapshots(frame)",
		"CREATE INDEX IF NOT EXISTS idx_snapshots_turn ON snapshots(turn)",
		"CREATE INDEX IF NOT EXISTS idx_messages_frame ON messages(frame)",
		"CREATE INDEX IF NOT EXISTS idx_events_frame ON events(type, frame)",
		"CREATE INDEX IF NOT EXISTS idx_inputs_frame ON inputs(frame)",
	};

	/** Required columns per table (extra columns from newer minor versions are OK). */
	public static final Map<String, Set<String>> REQUIRED;
	static
	{
		Map<String, Set<String>> m = new LinkedHashMap<>();
		m.put("meta", set("key", "value"));
		m.put("sessions", set("id", "kind", "first_frame", "restored_turn", "restored_aut"));
		m.put("abandoned", set("from_frame", "to_frame"));
		m.put("frame_chunks", set("id", "first_frame", "last_frame", "data"));
		m.put("frames", set("frame", "chunk_id", "t_ms", "turn", "aut", "flags"));
		m.put("snapshots", set("id", "frame", "turn", "aut", "kind", "level", "has_panels",
				"map_key", "doc"));
		m.put("blobs", set("hash", "kind", "data"));
		m.put("messages", set("id", "frame", "turn", "channel", "temp", "text"));
		m.put("inputs", set("frame", "key"));
		m.put("events", set("frame", "turn", "type", "label"));
		REQUIRED = Collections.unmodifiableMap(m);
	}

	/** Columns added after v1; openForWriting adds them to older files. */
	public static final String[][] ADDED_COLUMNS = {
		{ "sessions", "version", "INTEGER" },
		{ "blobs", "base", "TEXT" },
	};

	public static final Set<String> KNOWN_INDEXES = set("idx_frames_turn", "idx_snapshots_frame",
			"idx_snapshots_turn", "idx_messages_frame", "idx_events_frame", "idx_inputs_frame");

	private static Set<String> set(String... s)
	{
		return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(s)));
	}
}
