package com.crawlmb.recording;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory RecordingSink + RecordingReader with the same semantics as
 * SqliteRecordingStore (abandoned branches, temporary-message rollback).
 * Test fixture; also a reference for the SQL implementation.
 */
public final class MemoryRecordingStore implements RecordingSink, RecordingReader
{
	static final class Frame
	{
		int frame, turn, aut, flags, chunkId;
		long tMs;
	}

	static final class Msg
	{
		int frame, turn, channel;
		String text;
		boolean temp;
	}

	static final class Snap
	{
		SnapshotRow row;
		byte[] doc;
		boolean anchor;
	}

	final Map<String, String> meta = new LinkedHashMap<>();
	final List<Frame> frames = new ArrayList<>();
	final Map<Integer, byte[]> chunks = new HashMap<>();
	final List<Snap> snaps = new ArrayList<>();
	final Map<String, byte[]> blobs = new HashMap<>();
	final Map<String, String> blobBases = new HashMap<>();
	final Map<String, Integer> blobLastFrame = new HashMap<>();
	final List<Msg> msgs = new ArrayList<>();
	final List<int[]> inputs = new ArrayList<>();
	final List<EventRow> events = new ArrayList<>();
	final List<int[]> abandonedRanges = new ArrayList<>(); // [from, toExclusive)
	final List<String> sessions = new ArrayList<>();
	byte[] docDict; // the blobs-table dictionary row in SQLite
	private int nextChunkId = 1;
	private long nextSnapId = 1;
	int commits;
	boolean closed;
	boolean opened;

	@Override
	public OpenResult open(GameIdentity id, String appVersion, int[] layout, byte[] dict)
	{
		closed = false;
		if (opened)
		{
			int next = frames.isEmpty() ? 0 : frames.get(frames.size() - 1).frame + 1;
			int lastTurn = -1;
			for (Frame f : frames)
				lastTurn = Math.max(lastTurn, f.turn);
			snaps.removeIf(x -> x.row.frame >= next);
			msgs.removeIf(x -> x.frame >= next);
			inputs.removeIf(x -> x[0] >= next);
			events.removeIf(x -> x.frame >= next);
			return new OpenResult(false, next, lastTurn,
					Integer.parseInt(meta.getOrDefault("doc_dict_depth", "1")), docDict);
		}
		opened = true;
		meta.put("schema_version", Integer.toString(RecFormat.SCHEMA_VERSION));
		meta.put("min_reader_version", Integer.toString(RecFormat.MIN_READER_VERSION));
		meta.put("identity", id.json);
		meta.put("app_version", appVersion);
		meta.put("doc_dict_depth", Integer.toString(RecFormat.DOC_DICT_DEPTH));
		if (dict != null)
		{
			docDict = dict.clone();
			meta.put("doc_dict", "doc_dict");
		}
		return new OpenResult(true, 0, -1, RecFormat.DOC_DICT_DEPTH, docDict);
	}

	@Override
	public void startSession(String kind, int firstFrame, int restoredTurn, long restoredAut)
	{
		sessions.add(kind + "@" + firstFrame);
		if (!RecFormat.SESSION_RESUME.equals(kind))
			return;
		int from = Integer.MAX_VALUE;
		for (Frame f : frames)
			if (f.turn >= 0 && f.aut > restoredAut && !isAbandoned(f.frame))
				from = Math.min(from, f.frame);
		if (from < firstFrame)
			abandonedRanges.add(new int[] { from, firstFrame });
	}

	boolean isAbandoned(int frame)
	{
		for (int[] r : abandonedRanges)
			if (frame >= r[0] && frame < r[1])
				return true;
		return false;
	}

	@Override
	public void writeChunk(int firstFrame, int lastFrame, byte[] deflated,
			List<FrameChunkEncoder.FrameInfo> fl)
	{
		int id = nextChunkId++;
		chunks.put(id, deflated);
		for (FrameChunkEncoder.FrameInfo i : fl)
		{
			Frame f = new Frame();
			f.frame = i.frame;
			f.tMs = i.tMs;
			f.turn = i.turn;
			f.aut = i.aut;
			f.flags = i.flags;
			f.chunkId = id;
			frames.add(f);
		}
	}

	@Override
	public void updateFrameFlags(int frame, int flags)
	{
		for (Frame f : frames)
			if (f.frame == frame)
				f.flags = flags;
	}

	@Override
	public boolean writeBlob(String hash, int kind, byte[] deflated, String base, int frame)
	{
		blobLastFrame.put(hash, frame);
		if (blobs.containsKey(hash))
			return false;
		blobs.put(hash, deflated);
		if (base != null)
			blobBases.put(hash, base);
		return true;
	}

	@Override
	public void writeSnapshot(int frame, SnapshotPayload p, byte[] docDeflated, boolean anchor)
	{
		Snap s = new Snap();
		s.anchor = anchor;
		s.row = new SnapshotRow(nextSnapId++, frame, p.turn, p.aut, p.kind, p.level,
				p.hasPanels, p.mapKeyframe, p.hp, p.hpMax, p.mp, p.mpMax, p.xl, p.depth);
		s.doc = docDeflated;
		snaps.add(s);
	}

	@Override
	public void trimBefore(int turn)
	{
		List<Integer> sessionFirst = new ArrayList<>();
		for (String k : sessions)
			sessionFirst.add(Integer.parseInt(k.substring(k.indexOf('@') + 1)));
		int cut = -1;
		for (int i = snaps.size() - 1; i > 0 && cut < 0; i--)
		{
			SnapshotRow r = snaps.get(i).row;
			if (r.turn <= turn && snaps.get(i).anchor && !isAbandoned(r.frame))
				cut = i;
		}
		if (cut < 0)
			return;
		int f = snaps.get(cut).row.frame;
		snaps.subList(0, cut).clear();
		frames.removeIf(x -> x.frame < f);
		msgs.removeIf(m -> m.frame < f);
		inputs.removeIf(in -> in[0] < f);
		events.removeIf(e -> e.frame < f);
		abandonedRanges.removeIf(r -> r[1] <= f);
		for (int i = sessionFirst.size() - 1; i > 0; i--)
			if (sessionFirst.get(i) <= f)
			{
				sessions.subList(0, i).clear();
				break;
			}
		Set<String> keep = new HashSet<>();
		for (Map.Entry<String, Integer> e : blobLastFrame.entrySet())
			for (String h = e.getValue() >= f ? e.getKey() : null; h != null && keep.add(h); )
				h = blobBases.get(h);
		blobs.keySet().retainAll(keep);
		blobBases.keySet().retainAll(keep);
		blobLastFrame.keySet().retainAll(keep);
	}

	@Override
	public void writeMessage(int frame, int turn, int channel, String text, boolean temporary)
	{
		Msg m = new Msg();
		m.frame = frame;
		m.turn = turn;
		m.channel = channel;
		m.text = text;
		m.temp = temporary;
		msgs.add(m);
	}

	@Override
	public void rollbackMessages(int count)
	{
		for (int i = msgs.size() - 1; i >= 0 && count > 0; i--)
			if (msgs.get(i).temp)
			{
				msgs.remove(i);
				count--;
			}
	}

	@Override
	public void writeInput(int frame, int key) { inputs.add(new int[] { frame, key }); }

	@Override
	public void writeEvent(int frame, int turn, String type, String label)
	{
		events.add(new EventRow(frame, turn, type, label));
	}

	@Override
	public void setMeta(String key, String value) { meta.put(key, value); }

	@Override
	public void commit() { commits++; }

	@Override
	public void close() { closed = true; }

	// ------------------------------------------------------------------ reader

	@Override
	public Map<String, String> meta() { return meta; }

	@Override
	public FrameIndex frameIndex()
	{
		List<Frame> sorted = new ArrayList<>(frames);
		sorted.sort((a, b) -> Integer.compare(a.frame, b.frame));
		int n = sorted.size();
		int[] fr = new int[n], tu = new int[n], au = new int[n], fl = new int[n], ch = new int[n];
		long[] t = new long[n];
		boolean[] ab = new boolean[n];
		for (int i = 0; i < n; i++)
		{
			Frame f = sorted.get(i);
			fr[i] = f.frame;
			t[i] = f.tMs;
			tu[i] = f.turn;
			au[i] = f.aut;
			fl[i] = f.flags;
			ch[i] = f.chunkId;
			ab[i] = isAbandoned(f.frame);
		}
		return new FrameIndex(fr, t, tu, au, fl, ch, ab);
	}

	@Override
	public byte[] chunk(int chunkId) { return Deflate.inflate(chunks.get(chunkId)); }

	@Override
	public List<SnapshotRow> snapshotIndex()
	{
		List<SnapshotRow> out = new ArrayList<>();
		for (Snap s : snaps)
			if (!isAbandoned(s.row.frame))
				out.add(s.row);
		out.sort((a, b) -> a.frame != b.frame ? Integer.compare(a.frame, b.frame)
				: Long.compare(a.id, b.id));
		return out;
	}

	@Override
	public String snapshotDoc(long id)
	{
		int depth = Integer.parseInt(meta.getOrDefault("doc_dict_depth", "1"));
		int end = 0;
		while (snaps.get(end).row.id != id)
			end++;
		long adler = docDict != null ? Deflate.adler(docDict) : -1;
		int start = end;
		while (!Deflate.startsDocChain(snaps.get(start).doc, adler))
			start--;
		ArrayDeque<byte[]> window = new ArrayDeque<>();
		for (int i = start; i <= end; i++)
		{
			window.addLast(Deflate.inflate(snaps.get(i).doc, Deflate.DEFAULT_MAX_INFLATED,
					Deflate.windowDict(docDict, window)));
			if (window.size() > depth)
				window.removeFirst();
		}
		return new String(window.peekLast(), StandardCharsets.UTF_8);
	}

	@Override
	public String blob(String hash)
	{
		byte[] b = blobBytes(hash);
		return b == null ? null : new String(b, StandardCharsets.UTF_8);
	}

	private byte[] blobBytes(String hash)
	{
		byte[] b = blobs.get(hash);
		if (b == null)
			return null;
		String base = blobBases.get(hash);
		return Deflate.inflate(b, Deflate.DEFAULT_MAX_INFLATED,
				base == null ? null : blobBytes(base));
	}

	@Override
	public List<MessageRow> messagesUpTo(int toFrame, int limit)
	{
		List<MessageRow> out = new ArrayList<>();
		for (Msg m : msgs)
			if (m.frame <= toFrame && !m.temp && !isAbandoned(m.frame))
				out.add(new MessageRow(m.frame, m.turn, m.channel, m.text));
		return out.size() > limit ? out.subList(out.size() - limit, out.size()) : out;
	}

	@Override
	public List<EventRow> events() { return events; }

	// close() is shared by RecordingSink and RecordingReader.
}
