package com.crawlmb.recording.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.SeekBar;

import com.crawlmb.recording.EventDeriver;
import com.crawlmb.recording.KnowledgeResolver;
import com.crawlmb.recording.RecordingReader;
import com.crawlmb.recording.ReplayTimeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Timeline scrubber: a SeekBar with branch bands, red spans for
 * turns that weren't recorded, and ticks for new levels (gray), level ups
 * (blue), HP under 10% (light red), first sight of a unique (red) and Zot's
 * malevolent forces (purple). Progress is in bar units, not
 * positions (gaps add units before their post-gap position): convert with
 * posAt()/progressOf().
 */
public final class TimelineBar extends SeekBar
{
	private static final int GAP_COLOUR = 0xFFE04040;
	// Draw order = priority: later entries paint over earlier ones.
	private static final String[] TICK_TYPES = { EventDeriver.LOW_HP, EventDeriver.LEVEL,
			EventDeriver.XL, EventDeriver.UNIQUE, EventDeriver.MALEVOLENCE };
	private static final int[] TICK_COLOURS = { 0xFFFF9C9C, 0xFF909090, 0xFF4080FF, 0xFFFF2020,
			0xFFB050FF };
	// A game recorded from turn 5000 would otherwise be almost all red.
	private static final float MAX_GAP_SHARE = 0.5f;

	private int[] bandStart = new int[0];
	private int[] bandColour = new int[0];
	private final int[][] ticks = new int[TICK_TYPES.length][0];
	private int[] unitOf = { 0 };
	private int[] gapPos = new int[0];
	private int[] gapUnits = new int[0];
	private int units = 1;
	private final Paint paint = new Paint();

	private static final int[] BRANCH_PALETTE = { 0xFF3A3A3A, 0xFF2E4A2E, 0xFF4A3A22, 0xFF22384A,
			0xFF4A2238, 0xFF38224A, 0xFF224A44, 0xFF4A4422 };

	public TimelineBar(Context ctx)
	{
		super(ctx);
	}

	public void setData(ReplayTimeline tl, KnowledgeResolver resolver)
	{
		buildUnits(tl, Math.max(1, tl.size()));
		setMax(Math.max(0, units - 1));
		// Bands: one per run of snapshots on the same branch.
		List<RecordingReader.SnapshotRow> rows = resolver.rows();
		Map<String, Integer> branchColour = new HashMap<>();
		int n = 0;
		int[] start = new int[rows.size() + 1];
		int[] colour = new int[rows.size() + 1];
		String prev = null;
		for (RecordingReader.SnapshotRow r : rows)
		{
			String branch = r.level.contains(":") ? r.level.substring(0, r.level.indexOf(':')) : r.level;
			if (branch.equals(prev))
				continue;
			prev = branch;
			Integer c = branchColour.get(branch);
			if (c == null)
			{
				c = BRANCH_PALETTE[branchColour.size() % BRANCH_PALETTE.length];
				branchColour.put(branch, c);
			}
			start[n] = Math.max(0, tl.posOfFrame(r.frame));
			colour[n] = c;
			n++;
		}
		bandStart = java.util.Arrays.copyOf(start, n);
		bandColour = java.util.Arrays.copyOf(colour, n);
		for (int t = 0; t < TICK_TYPES.length; t++)
		{
			List<Integer> l = new ArrayList<>();
			// A resumed session re-derives sightings; only the first one counts.
			Set<String> uniques = new HashSet<>();
			for (int i = 0; i < tl.events().size(); i++)
			{
				RecordingReader.EventRow e = tl.events().get(i);
				if (TICK_TYPES[t].equals(e.type)
						&& (!EventDeriver.LOW_HP.equals(e.type) || isCriticalHp(e))
						&& (!EventDeriver.UNIQUE.equals(e.type) || uniques.add(e.label)))
					l.add(tl.eventPosition(i));
			}
			ticks[t] = new int[l.size()];
			for (int i = 0; i < l.size(); i++)
				ticks[t][i] = l.get(i);
		}
		invalidate();
	}

	/** Position at a bar progress; inside a gap span snaps to the post-gap position. */
	public int posAt(int progress)
	{
		int lo = 0, hi = unitOf.length - 1;
		while (lo < hi)
		{
			int mid = (lo + hi) >>> 1;
			if (unitOf[mid] < progress)
				lo = mid + 1;
			else
				hi = mid;
		}
		return lo;
	}

	public int progressOf(int pos)
	{
		return unitOf[Math.max(0, Math.min(unitOf.length - 1, pos))];
	}

	/** Nearest ticked position after (dir > 0) or before pos, or -1. */
	public int nextTick(int pos, int dir)
	{
		int best = -1;
		for (int[] l : ticks)
			for (int p : l)
				if (dir > 0 ? p > pos && (best < 0 || p < best) : p < pos && p > best)
					best = p;
		return best;
	}

	private void buildUnits(ReplayTimeline tl, int size)
	{
		List<Integer> pos = new ArrayList<>();
		List<Integer> missed = new ArrayList<>();
		for (int i = 0; i < tl.events().size(); i++)
		{
			RecordingReader.EventRow e = tl.events().get(i);
			if (!EventDeriver.GAP.equals(e.type))
				continue;
			int p = tl.eventPosition(i);
			int m = Math.max(0, tl.turn(p)) - (p > 0 ? Math.max(0, tl.turn(p - 1)) : 0);
			if (m > 0 && (pos.isEmpty() || pos.get(pos.size() - 1) != p))
			{
				pos.add(p);
				missed.add(m);
			}
		}
		// Size gaps by the recorded frames-per-turn: as wide as they'd have been.
		long recordedTurns = 0, totalMissed = 0;
		for (int p = 1; p < tl.size(); p++)
			if (!pos.contains(p))
				recordedTurns += Math.max(0, tl.turn(p) - Math.max(0, tl.turn(p - 1)));
		for (int m : missed)
			totalMissed += m;
		double perTurn = (double) size / Math.max(1, recordedTurns);
		double cap = size * MAX_GAP_SHARE / (1 - MAX_GAP_SHARE);
		if (totalMissed * perTurn > cap)
			perTurn = cap / totalMissed;
		gapPos = new int[pos.size()];
		gapUnits = new int[pos.size()];
		for (int i = 0; i < gapPos.length; i++)
		{
			gapPos[i] = pos.get(i);
			gapUnits[i] = Math.max(1, (int) Math.round(missed.get(i) * perTurn));
		}
		unitOf = new int[size];
		int extra = 0, g = 0;
		for (int p = 0; p < size; p++)
		{
			while (g < gapPos.length && gapPos[g] == p)
				extra += gapUnits[g++];
			unitOf[p] = p + extra;
		}
		units = unitOf[size - 1] + 1;
	}

	// Older recordings logged LOW_HP under a third; the label is "HP cur/max".
	private static boolean isCriticalHp(RecordingReader.EventRow e)
	{
		String s = e.label == null ? "" : e.label;
		int slash = s.indexOf('/');
		if (!s.startsWith("HP ") || slash < 0)
			return false;
		try
		{
			int hp = Integer.parseInt(s.substring(3, slash).trim());
			int max = Integer.parseInt(s.substring(slash + 1).trim());
			return max > 0 && hp * 10 < max;
		}
		catch (NumberFormatException ex)
		{
			return false;
		}
	}

	@Override
	protected synchronized void onDraw(Canvas c)
	{
		int left = getPaddingLeft(), right = getWidth() - getPaddingRight();
		int h = getHeight();
		float u = Math.max(1, right - left) / (float) units;
		for (int i = 0; i < bandStart.length; i++)
		{
			int end = i + 1 < bandStart.length ? unitOf[bandStart[i + 1]] : units;
			paint.setColor(bandColour[i]);
			c.drawRect(left + u * unitOf[bandStart[i]], h * 0.25f, left + u * end, h * 0.75f, paint);
		}
		paint.setColor(GAP_COLOUR);
		for (int i = 0; i < gapPos.length; i++)
		{
			float x0 = left + u * (unitOf[gapPos[i]] - gapUnits[i]);
			float x1 = left + u * unitOf[gapPos[i]];
			c.drawRect(x0, h * 0.25f, Math.max(x1, x0 + 2), h * 0.75f, paint);
		}
		for (int t = 0; t < TICK_TYPES.length; t++)
		{
			paint.setColor(TICK_COLOURS[t]);
			for (int p : ticks[t])
			{
				float x = left + u * unitOf[p];
				c.drawRect(x - 1.5f, h * 0.1f, x + 1.5f, h * 0.9f, paint);
			}
		}
		super.onDraw(c);
	}
}
