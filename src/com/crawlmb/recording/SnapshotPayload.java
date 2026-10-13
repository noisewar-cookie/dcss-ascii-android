package com.crawlmb.recording;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the "DCS1" snapshot payload produced by android-recorder.cc
 * (make_payload) and extracts the denormalized fields stored in the
 * snapshots table.
 */
public final class SnapshotPayload
{
	public static final int MAX_DOC_BYTES = 16 * 1024 * 1024;
	public static final int MAX_BLOBS = 100_000;
	public static final int MAX_BLOB_BYTES = 4 * 1024 * 1024;

	public static final class Blob
	{
		public final String hash; // 32 hex chars, same as doc references
		public final int kind;
		public final byte[] utf8;

		Blob(String hash, int kind, byte[] utf8)
		{
			this.hash = hash;
			this.kind = kind;
			this.utf8 = utf8;
		}
	}

	public final String docJson;
	public final Map<String, Object> doc;
	public final List<Blob> blobs;

	// Denormalized summary (snapshots table columns).
	public final String kind;
	public final int turn;
	public final long aut;
	public final String level;
	public final String branch;
	public final int depth;
	public final int hp, hpMax, mp, mpMax, xl;
	public final boolean hasPanels;
	public final boolean mapKeyframe;

	private SnapshotPayload(String docJson, Map<String, Object> doc, List<Blob> blobs)
	{
		this.docJson = docJson;
		this.doc = doc;
		this.blobs = blobs;
		kind = MiniJson.getString(doc, "kind", "?");
		turn = (int) MiniJson.getLong(doc, "turn", -1);
		aut = MiniJson.getLong(doc, "aut", -1);
		level = MiniJson.getString(doc, "level", "");
		branch = MiniJson.getString(doc, "branch", "");
		depth = (int) MiniJson.getLong(doc, "depth", 0);
		Map<String, Object> p = MiniJson.getObject(doc, "player");
		hp = (int) MiniJson.getLong(p, "hp", 0);
		hpMax = (int) MiniJson.getLong(p, "hpmax", 0);
		mp = (int) MiniJson.getLong(p, "mp", 0);
		mpMax = (int) MiniJson.getLong(p, "mpmax", 0);
		xl = (int) MiniJson.getLong(p, "xl", 0);
		hasPanels = MiniJson.getObject(doc, "panels") != null;
		Map<String, Object> map = MiniJson.getObject(doc, "map");
		mapKeyframe = MiniJson.getLong(map, "key", 0) != 0;
	}

	public static SnapshotPayload parse(byte[] payload)
	{
		ByteIO.In in = new ByteIO.In(payload);
		if (in.u32() != RecFormat.SNAPSHOT_MAGIC)
			throw new ByteIO.FormatException("bad snapshot magic");
		int docLen = in.u32();
		if (docLen < 0 || docLen > MAX_DOC_BYTES)
			throw new ByteIO.FormatException("doc too large");
		String json = new String(in.raw(docLen), StandardCharsets.UTF_8);
		int n = in.u32();
		if (n < 0 || n > MAX_BLOBS)
			throw new ByteIO.FormatException("too many blobs");
		List<Blob> blobs = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
		{
			long a = in.u64();
			long b = in.u64();
			int kind = in.u8();
			int len = in.u32();
			if (len < 0 || len > MAX_BLOB_BYTES)
				throw new ByteIO.FormatException("blob too large");
			blobs.add(new Blob(hex(a, b), kind, in.raw(len)));
		}
		return new SnapshotPayload(json, MiniJson.parseObject(json), blobs);
	}

	/** Same format as android-recorder.cc hex(): two 16-digit lowercase lanes. */
	public static String hex(long a, long b)
	{
		return String.format("%016x%016x", a, b);
	}
}
