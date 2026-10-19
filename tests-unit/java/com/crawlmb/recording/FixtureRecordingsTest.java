package com.crawlmb.recording;

import static org.junit.Assert.*;

import com.crawlmb.recording.ui.ReplayScreens;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Real recordings from earlier app builds, one per format version, must still
 * open and replay end to end. Add one whenever RecFormat.SCHEMA_VERSION rises.
 */
public class FixtureRecordingsTest
{
	// Gradle runs unit tests from the module directory.
	private static final File DIR = new File("tests-unit/fixtures/recordings");

	private static File[] fixtures()
	{
		File[] f = DIR.listFiles((d, n) -> n.endsWith(RecFormat.FILE_EXTENSION));
		assertNotNull("missing " + DIR.getAbsolutePath(), f);
		assertTrue("no fixtures in " + DIR.getAbsolutePath(), f.length > 0);
		return f;
	}

	@Test
	public void currentSchemaHasAFixture() throws Exception
	{
		Set<Integer> versions = new HashSet<>();
		for (File f : fixtures())
			versions.add(Integer.parseInt(load(f).meta.get("schema_version")));
		assertTrue("add a fixture recorded with schema " + RecFormat.SCHEMA_VERSION,
				versions.contains(RecFormat.SCHEMA_VERSION));
	}

	@Test
	public void everyFixtureReplays() throws Exception
	{
		for (File f : fixtures())
		{
			try
			{
				replay(load(f));
			}
			catch (Throwable t)
			{
				throw new AssertionError(f.getName() + ": " + t, t);
			}
		}
	}

	private static void replay(MemoryRecordingStore s)
	{
		assertTrue(ImportValidator.validateMeta(s.meta).error, ImportValidator.validateMeta(s.meta).ok);
		GameIdentity id = GameIdentity.parse(s.meta.get("identity")
				.getBytes(StandardCharsets.UTF_8));
		assertFalse(id.name.isEmpty());

		final ScreenState[] shown = new ScreenState[1];
		ReplayController rc = new ReplayController(s, false, 4, new ReplayController.RenderTarget()
		{
			@Override public void render(ScreenState st, int pos) { shown[0] = st; }
			@Override public void onPositionChanged(int pos, boolean playing) {}
		});
		int n = rc.timeline().size();
		assertTrue("no frames", n > 0);
		int lastRow = -2, maps = 0;
		for (int p = 0; p < n; p++)
		{
			shown[0] = null;
			rc.seek(p);
			assertNotNull("frame " + p + " not rendered", shown[0]);
			int row = rc.resolver().rowAt(rc.timeline().frame(p), rc.timeline().aut(p));
			if (row == lastRow)
				continue;
			lastRow = row;
			KnowledgeResolver.Knowledge k = rc.knowledge();
			if (k == null)
				continue;
			if (k.map != null)
				maps++;
			ExamineCursor cur = new ExamineCursor(k, rc.resolver());
			assertNotNull(cur.summary());
			assertNotNull(cur.describe());
			assertFalse(cur.targets().isEmpty());
			ReplayScreens rs = new ReplayScreens(msg -> {}, rc.resolver()::blob, k);
			for (ReplayScreens.Kind kind : ReplayScreens.Kind.values())
			{
				rs.open(kind);
				assertNotNull(rs.apply(shown[0]));
				rs.back();
			}
		}
		assertTrue("no level maps", maps > 0);
		rc.messages(200);
	}

	/** Copies the file's rows into a MemoryRecordingStore, as SqliteRecordingStore reads them. */
	private static MemoryRecordingStore load(File src) throws Exception
	{
		// Work on a copy: opening a WAL-mode file leaves -wal/-shm files beside it.
		File tmp = File.createTempFile("fixture", RecFormat.FILE_EXTENSION);
		tmp.deleteOnExit();
		Files.copy(src.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
		MemoryRecordingStore s = new MemoryRecordingStore();
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tmp.getPath());
				Statement st = c.createStatement())
		{
			validateStructure(c, st);
			try (ResultSet r = st.executeQuery("SELECT key, value FROM meta"))
			{
				while (r.next())
					s.meta.put(r.getString(1), r.getString(2));
			}
			try (ResultSet r = st.executeQuery("SELECT kind, first_frame FROM sessions ORDER BY id"))
			{
				while (r.next())
					s.sessions.add(r.getString(1) + "@" + r.getInt(2));
			}
			try (ResultSet r = st.executeQuery("SELECT from_frame, to_frame FROM abandoned"))
			{
				while (r.next())
					s.abandonedRanges.add(new int[] { r.getInt(1), r.getInt(2) });
			}
			try (ResultSet r = st.executeQuery("SELECT id, data FROM frame_chunks"))
			{
				while (r.next())
					s.chunks.put(r.getInt(1), r.getBytes(2));
			}
			try (ResultSet r = st.executeQuery(
					"SELECT frame, chunk_id, t_ms, turn, aut, flags FROM frames ORDER BY frame"))
			{
				while (r.next())
				{
					MemoryRecordingStore.Frame f = new MemoryRecordingStore.Frame();
					f.frame = r.getInt(1);
					f.chunkId = r.getInt(2);
					f.tMs = r.getLong(3);
					f.turn = r.getInt(4);
					f.aut = r.getInt(5);
					f.flags = r.getInt(6);
					s.frames.add(f);
				}
			}
			try (ResultSet r = st.executeQuery("SELECT id, frame, turn, aut, kind, level,"
					+ " has_panels, map_key, hp, hp_max, mp, mp_max, xl, depth, doc"
					+ " FROM snapshots ORDER BY id"))
			{
				while (r.next())
				{
					MemoryRecordingStore.Snap x = new MemoryRecordingStore.Snap();
					x.row = new RecordingReader.SnapshotRow(r.getLong(1), r.getInt(2), r.getInt(3),
							r.getLong(4), r.getString(5), r.getString(6) == null ? "" : r.getString(6),
							r.getInt(7) != 0, r.getInt(8) != 0, r.getInt(9), r.getInt(10),
							r.getInt(11), r.getInt(12), r.getInt(13), r.getInt(14));
					x.doc = r.getBytes(15);
					s.snaps.add(x);
				}
			}
			boolean bases = columns(c, "blobs").contains("base");
			try (ResultSet r = st.executeQuery(bases ? "SELECT hash, data, base FROM blobs"
					: "SELECT hash, data, NULL FROM blobs"))
			{
				while (r.next())
				{
					s.blobs.put(r.getString(1), r.getBytes(2));
					if (r.getString(3) != null)
						s.blobBases.put(r.getString(1), r.getString(3));
				}
			}
			String dict = s.meta.get("doc_dict");
			if (dict != null)
				s.docDict = Deflate.inflate(s.blobs.get(dict), Deflate.WINDOW);
			try (ResultSet r = st.executeQuery(
					"SELECT frame, turn, channel, text, temp FROM messages ORDER BY id"))
			{
				while (r.next())
				{
					MemoryRecordingStore.Msg m = new MemoryRecordingStore.Msg();
					m.frame = r.getInt(1);
					m.turn = r.getInt(2);
					m.channel = r.getInt(3);
					m.text = r.getString(4);
					m.temp = r.getInt(5) != 0;
					s.msgs.add(m);
				}
			}
			try (ResultSet r = st.executeQuery(
					"SELECT frame, turn, type, label FROM events ORDER BY frame, id"))
			{
				while (r.next())
					s.events.add(new RecordingReader.EventRow(r.getInt(1), r.getInt(2),
							r.getString(3), r.getString(4)));
			}
		}
		return s;
	}

	// Same check the importer runs before any other query.
	private static void validateStructure(Connection c, Statement st) throws SQLException
	{
		List<ImportValidator.MasterRow> master = new ArrayList<>();
		try (ResultSet r = st.executeQuery("SELECT type, name, tbl_name FROM sqlite_master"))
		{
			while (r.next())
				master.add(new ImportValidator.MasterRow(r.getString(1), r.getString(2),
						r.getString(3)));
		}
		Map<String, Set<String>> cols = new HashMap<>();
		for (String t : RecordingSchema.REQUIRED.keySet())
			cols.put(t, columns(c, t));
		ImportValidator.Result res = ImportValidator.validateStructure(master, cols);
		assertTrue(res.error, res.ok);
	}

	private static Set<String> columns(Connection c, String table) throws SQLException
	{
		Set<String> out = new HashSet<>();
		try (Statement st = c.createStatement();
				ResultSet r = st.executeQuery("PRAGMA table_info(" + table + ")"))
		{
			while (r.next())
				out.add(r.getString("name"));
		}
		return out;
	}
}
