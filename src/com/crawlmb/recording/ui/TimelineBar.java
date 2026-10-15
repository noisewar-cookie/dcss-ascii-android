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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Timeline scrubber: a SeekBar with branch bands, red spans for
 * turns that weren't recorded, and ticks for new floors (gray), temporary
 * portals (blue), level ups (green), HP under 10% (light red), first sight of
 * a unique (red) and Zot's malevolent forces (purple). Progress is in bar
 * units, not positions (gaps add units before their post-gap position), and
 * relative to the visible window (Range): convert with posAt()/progressOf().
 */
public final class TimelineBar extends SeekBar
{
	private static final int GAP_COLOUR = 0xFFE04040;
	// Not stored: a LEVEL event into a timed portal branch (by abbreviation).
	private static final String PORTAL = "portal";
	private static final Set<String> PORTAL_BRANCHES = new HashSet<>(Arrays.asList("Sewer",
			"Ossuary", "Bailey", "IceCv", "Volcano", "WizLab", "Desolation", "Gauntlet",
			"Bazaar", "Trove", "Lab"));
	// Draw order = priority: later entries paint over earlier ones.
	private static final String[] TICK_TYPES = { EventDeriver.LOW_HP, EventDeriver.LEVEL,
			PORTAL, EventDeriver.XL, EventDeriver.UNIQUE, EventDeriver.MALEVOLENCE };
	private static final int[] TICK_COLOURS = { 0xFFFF6464, 0xFF909090, 0xFF4080FF, 0xFF40D040,
			0xFFFF2020, 0xFFB050FF };
	static final String[] TICK_LABELS = { "HP under 10%", "New floor", "Portals", "Level up",
			"Unique sighted", "Zot's malevolence" };
	// A game recorded from turn 5000 would otherwise be almost all red.
	private static final float MAX_GAP_SHARE = 0.5f;

	private int[] bandStart = new int[0];
	private int[] bandColour = new int[0];
	private final int[][] ticks = new int[TICK_TYPES.length][0];
	private boolean[] shown = tickTypesAllShown();
	private int[] unitOf = { 0 };
	private int[] gapPos = new int[0];
	private int[] gapUnits = new int[0];
	private String[] gapText = new String[0];
	private int units = 1;
	private int winLo, winHi; // visible units, inclusive
	private boolean shortTicks; // Range on: ticks stay inside the band so the range bar above fits
	private ReplayTimeline tl;
	private final Paint paint = new Paint();

	private static final int[] BRANCH_PALETTE = { 0xFF3A3A3A, 0xFF2E4A2E, 0xFF4A3A22, 0xFF22384A,
			0xFF4A2238, 0xFF38224A, 0xFF224A44, 0xFF4A4422 };

	public TimelineBar(Context ctx)
	{
		super(ctx);
	}

	public void setData(ReplayTimeline tl, KnowledgeResolver resolver)
	{
		this.tl = tl;
		buildUnits(tl, Math.max(1, tl.size()));
		winLo = 0;
		winHi = units - 1;
		setMax(winHi);
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
				if (TICK_TYPES[t].equals(tickType(e))
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

	private static String tickType(RecordingReader.EventRow e)
	{
		if (!EventDeriver.LEVEL.equals(e.type) || e.label == null || !e.label.startsWith("Entered "))
			return e.type;
		String level = e.label.substring("Entered ".length());
		int colon = level.indexOf(':');
		return PORTAL_BRANCHES.contains(colon < 0 ? level : level.substring(0, colon))
				? PORTAL : e.type;
	}

	static int tickColour(int type) { return TICK_COLOURS[type]; }

	public int units() { return units; }

	/** Show only units lo..hi (inclusive); the thumb pins to an edge outside it. */
	public void setWindow(int lo, int hi, boolean shortTicks)
	{
		this.shortTicks = shortTicks;
		winLo = Math.max(0, Math.min(lo, units - 1));
		winHi = Math.max(winLo, Math.min(hi, units - 1));
		setMax(winHi - winLo);
		invalidate();
	}

	/** Label of the unrecorded span under a bar progress, or null. */
	public String gapAt(int progress)
	{
		int unit = progress + winLo;
		for (int i = 0; i < gapPos.length; i++)
			if (unit >= unitOf[gapPos[i]] - gapUnits[i] && unit < unitOf[gapPos[i]])
				return gapText[i];
		return null;
	}

	static boolean[] tickTypesAllShown()
	{
		boolean[] b = new boolean[TICK_TYPES.length];
		Arrays.fill(b, true);
		return b;
	}

	/** Hidden types are neither drawn nor used by nextTick. */
	public void setTickTypesShown(boolean[] shown)
	{
		this.shown = shown.clone();
		invalidate();
	}

	/** Position at a bar progress; inside a gap span snaps to the post-gap position. */
	public int posAt(int progress)
	{
		return posOfUnit(progress + winLo);
	}

	/** Game turn at a unit of the whole timeline (negative in menus). */
	public int turnAtUnit(int unit)
	{
		return tl == null ? -1 : tl.turn(posOfUnit(unit));
	}

	private int posOfUnit(int unit)
	{
		int lo = 0, hi = unitOf.length - 1;
		while (lo < hi)
		{
			int mid = (lo + hi) >>> 1;
			if (unitOf[mid] < unit)
				lo = mid + 1;
			else
				hi = mid;
		}
		return lo;
	}

	public int progressOf(int pos)
	{
		int unit = unitOf[Math.max(0, Math.min(unitOf.length - 1, pos))];
		return Math.max(0, Math.min(winHi - winLo, unit - winLo));
	}

	/** Nearest ticked position after (dir > 0) or before pos, or -1. */
	public int nextTick(int pos, int dir)
	{
		int best = -1;
		for (int t = 0; t < ticks.length; t++)
			if (shown[t])
				for (int p : ticks[t])
					if (dir > 0 ? p > pos && (best < 0 || p < best) : p < pos && p > best)
						best = p;
		return best;
	}

	private void buildUnits(ReplayTimeline tl, int size)
	{
		List<Integer> pos = new ArrayList<>();
		List<Integer> missed = new ArrayList<>();
		List<String> text = new ArrayList<>();
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
				text.add(e.label);
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
		gapText = text.toArray(new String[0]);
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
		int right = getWidth() - getPaddingRight();
		int h = getHeight();
		float u = Math.max(1, right - getPaddingLeft()) / (float) (winHi - winLo + 1);
		float left = getPaddingLeft() - u * winLo;
		c.save();
		c.clipRect(getPaddingLeft(), 0, right, h);
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
			if (!shown[t])
				continue;
			paint.setColor(TICK_COLOURS[t]);
			for (int p : ticks[t])
			{
				float x = left + u * unitOf[p];
				c.drawRect(x - 1.5f, h * (shortTicks ? 0.25f : 0.1f), x + 1.5f,
						h * (shortTicks ? 0.75f : 0.9f), paint);
			}
		}
		c.restore();
		super.onDraw(c);
	}
}
