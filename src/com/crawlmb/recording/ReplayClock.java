package com.crawlmb.recording;

/**
 * Converts wall-clock time into timeline movement.
 *   AS_PLAYED: recorded timestamps, idle gaps capped.
 *   TURN_TIME: fixed duration per game turn; frames within a turn are instant.
 * Negative direction = rewind.
 */
public final class ReplayClock
{
	public enum Mode { AS_PLAYED, TURN_TIME }

	public static final double[] SPEEDS = { 0.25, 0.5, 1, 2, 4, 8, 16, 64 };

	private Mode mode = Mode.AS_PLAYED;
	private double speed = 1.0;
	private int direction = 1;
	private long idleCapMs = 1000;
	private long msPerTurn = 250;
	private long maxTurnsPerFrame = 4;
	private double acc;

	public Mode mode() { return mode; }
	public double speed() { return speed; }
	public int direction() { return direction; }

	public void setMode(Mode m) { mode = m; acc = 0; }
	public void setSpeed(double s) { speed = Math.max(0.01, s); }
	public void setDirection(int d) { direction = d >= 0 ? 1 : -1; acc = 0; }
	public void setIdleCapMs(long ms) { idleCapMs = Math.max(1, ms); }
	public void setMsPerTurn(long ms) { msPerTurn = Math.max(1, ms); }
	public void reset() { acc = 0; }

	/**
	 * Advance from pos by wallMs of real time. Returns the new position; stops
	 * at either end of the timeline.
	 */
	public int advance(ReplayTimeline tl, int pos, long wallMs)
	{
		if (tl.size() == 0)
			return 0;
		acc += wallMs * speed;
		int p = pos;
		while (true)
		{
			int next = p + direction;
			if (next < 0 || next >= tl.size())
			{
				acc = 0;
				return p;
			}
			double cost = cost(tl, Math.min(p, next), Math.max(p, next));
			if (acc < cost)
				return p;
			acc -= cost;
			p = next;
		}
	}

	/** Virtual ms to move between adjacent positions a < b. */
	double cost(ReplayTimeline tl, int a, int b)
	{
		if (mode == Mode.AS_PLAYED)
			return Math.min(Math.max(0, tl.tMs(b) - tl.tMs(a)), idleCapMs);
		int ta = tl.turn(a), tb = tl.turn(b);
		if (ta < 0 || tb < 0)
			return Math.min(Math.max(0, tl.tMs(b) - tl.tMs(a)), idleCapMs); // menus
		long dt = Math.min(Math.abs(tb - ta), maxTurnsPerFrame);
		return dt * msPerTurn;
	}

	public boolean atEnd(ReplayTimeline tl, int pos)
	{
		return direction > 0 ? pos >= tl.size() - 1 : pos <= 0;
	}
}
