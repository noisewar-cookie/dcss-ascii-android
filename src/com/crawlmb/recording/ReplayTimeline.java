package com.crawlmb.recording;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Navigable view over a recording's frames. Positions are indices into the
 * visible (non-abandoned unless requested) frames. Pure logic: step, skip and
 * jump math for the transport controls.
 */
public final class ReplayTimeline
{
	public enum Unit { FRAME, INPUT, TURN }

	private final int[] frame;
	private final long[] tMs;
	private final int[] turn;
	private final int[] aut;
	private final int[] flags;
	private final int[] chunkId;
	private final List<RecordingReader.EventRow> events;
	private final int[] eventPos;

	public ReplayTimeline(RecordingReader.FrameIndex fi, List<RecordingReader.EventRow> allEvents,
			boolean includeAbandoned)
	{
		int n = 0;
		for (int i = 0; i < fi.size(); i++)
			if (includeAbandoned || !fi.abandoned[i])
				n++;
		frame = new int[n];
		tMs = new long[n];
		turn = new int[n];
		aut = new int[n];
		flags = new int[n];
		chunkId = new int[n];
		int k = 0;
		for (int i = 0; i < fi.size(); i++)
		{
			if (!includeAbandoned && fi.abandoned[i])
				continue;
			frame[k] = fi.frame[i];
			tMs[k] = fi.tMs[i];
			turn[k] = fi.turn[i];
			aut[k] = fi.aut[i];
			flags[k] = fi.flags[i];
			chunkId[k] = fi.chunkId[i];
			k++;
		}
		events = new ArrayList<>();
		List<Integer> pos = new ArrayList<>();
		for (RecordingReader.EventRow e : allEvents)
		{
			int p = posOfFrame(e.frame);
			if (p >= 0 && frame[p] == e.frame)
			{
				events.add(e);
				pos.add(p);
			}
		}
		eventPos = new int[pos.size()];
		for (int i = 0; i < eventPos.length; i++)
			eventPos[i] = pos.get(i);
	}

	public int size() { return frame.length; }
	public int frame(int pos) { return frame[pos]; }
	public long tMs(int pos) { return tMs[pos]; }
	public int turn(int pos) { return turn[pos]; }
	public int aut(int pos) { return aut[pos]; }
	public int flags(int pos) { return flags[pos]; }
	public int chunkId(int pos) { return chunkId[pos]; }
	public List<RecordingReader.EventRow> events() { return events; }
	public int eventPosition(int i) { return eventPos[i]; }

	public int clamp(int pos) { return Math.max(0, Math.min(size() - 1, pos)); }

	/** Position of the last frame <= f (frames are ascending). -1 if none. */
	public int posOfFrame(int f)
	{
		int i = Arrays.binarySearch(frame, f);
		return i >= 0 ? i : -i - 2;
	}

	/** First in-game position whose turn >= t (or the last position). */
	public int posForTurn(int t)
	{
		for (int i = 0; i < size(); i++)
			if (turn[i] >= t)
				return i;
		return size() - 1;
	}

	public int step(int pos, Unit unit, int dir)
	{
		if (size() == 0)
			return 0;
		switch (unit)
		{
		case FRAME:
			return clamp(pos + dir);
		case INPUT:
			for (int i = pos + dir; i >= 0 && i < size(); i += dir)
				if ((flags[i] & RecFormat.FRAME_INPUT_WAIT) != 0)
					return i;
			return dir > 0 ? size() - 1 : 0;
		case TURN:
		default:
			return dir > 0 ? nextTurnStart(pos) : prevTurnStart(pos);
		}
	}

	/** Jump by n turns (positive or negative). */
	public int skipTurns(int pos, int n)
	{
		if (size() == 0)
			return 0;
		int base = turn[pos];
		if (base < 0)
			return n > 0 ? posForTurn(0) : 0;
		if (n > 0)
			return posForTurn(base + n);
		int target = Math.max(0, base + n);
		return Math.min(posForTurn(target), pos);
	}

	/** Next/previous event (optionally of the given types). Returns pos or -1. */
	public int nextEvent(int pos, int dir, Set<String> types)
	{
		if (dir > 0)
		{
			for (int i = 0; i < eventPos.length; i++)
				if (eventPos[i] > pos && matches(i, types))
					return eventPos[i];
		}
		else
		{
			for (int i = eventPos.length - 1; i >= 0; i--)
				if (eventPos[i] < pos && matches(i, types))
					return eventPos[i];
		}
		return -1;
	}

	private boolean matches(int i, Set<String> types)
	{
		return types == null || types.contains(events.get(i).type);
	}

	private int nextTurnStart(int pos)
	{
		int t = turn[pos];
		for (int i = pos + 1; i < size(); i++)
			if (turn[i] != t)
				return i;
		return size() - 1;
	}

	private int prevTurnStart(int pos)
	{
		int start = startOfTurn(pos);
		if (start < pos)
			return start; // first go to the start of the current turn
		if (start == 0)
			return 0;
		return startOfTurn(start - 1);
	}

	private int startOfTurn(int pos)
	{
		int t = turn[pos];
		int i = pos;
		while (i > 0 && turn[i - 1] == t)
			i--;
		return i;
	}
}
