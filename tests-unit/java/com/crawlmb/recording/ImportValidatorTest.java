package com.crawlmb.recording;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class ImportValidatorTest
{
	private static List<ImportValidator.MasterRow> goodMaster()
	{
		List<ImportValidator.MasterRow> m = new ArrayList<>();
		for (String t : RecordingSchema.REQUIRED.keySet())
		{
			m.add(new ImportValidator.MasterRow("table", t, t));
			m.add(new ImportValidator.MasterRow("index", "sqlite_autoindex_" + t + "_1", t));
		}
		m.add(new ImportValidator.MasterRow("index", "idx_frames_turn", "frames"));
		return m;
	}

	private static Map<String, Set<String>> goodColumns()
	{
		Map<String, Set<String>> c = new HashMap<>();
		for (Map.Entry<String, Set<String>> e : RecordingSchema.REQUIRED.entrySet())
		{
			Set<String> s = new HashSet<>(e.getValue());
			s.add("future_column"); // newer minor versions may add columns
			c.put(e.getKey(), s);
		}
		return c;
	}

	@Test
	public void acceptsOwnSchema()
	{
		assertTrue(ImportValidator.validateStructure(goodMaster(), goodColumns()).ok);
	}

	@Test
	public void rejectsTriggersViewsAndUnknownObjects()
	{
		String[][] bad = { { "trigger", "evil", "frames" }, { "view", "v", "frames" },
				{ "table", "extra", "extra" }, { "index", "idx_evil", "frames" } };
		for (String[] b : bad)
		{
			List<ImportValidator.MasterRow> m = goodMaster();
			m.add(new ImportValidator.MasterRow(b[0], b[1], b[2]));
			assertFalse(b[0] + " " + b[1], ImportValidator.validateStructure(m, goodColumns()).ok);
		}
	}

	@Test
	public void rejectsMissingColumns()
	{
		Map<String, Set<String>> c = goodColumns();
		c.get("frames").remove("chunk_id");
		assertFalse(ImportValidator.validateStructure(goodMaster(), c).ok);
	}

	@Test
	public void metaVersionGate()
	{
		Map<String, String> meta = new HashMap<>();
		meta.put("schema_version", "1");
		meta.put("identity", "{\"name\":\"x\"}");
		assertTrue(ImportValidator.validateMeta(meta).ok);
		meta.put("schema_version", "99");
		ImportValidator.Result r = ImportValidator.validateMeta(meta);
		assertFalse(r.ok);
		assertEquals("newer_schema", r.error);
		// min_reader_version gates, not the writer's schema_version.
		meta.put("schema_version", "99");
		meta.put("min_reader_version", Integer.toString(RecFormat.SCHEMA_VERSION));
		assertTrue(ImportValidator.validateMeta(meta).ok);
		meta.put("min_reader_version", "99");
		assertEquals("newer_schema", ImportValidator.validateMeta(meta).error);
		meta.remove("min_reader_version");
		meta.put("schema_version", "1");
		meta.put("identity", "{not json");
		assertFalse(ImportValidator.validateMeta(meta).ok);
	}
}
