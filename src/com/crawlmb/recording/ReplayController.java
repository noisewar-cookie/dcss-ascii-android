package com.crawlmb.recording;

import java.util.List;
import java.util.Set;

/**
 * Playback state machine behind the transport controls:
 * play/pause, rewind (reverse play), speed, step, skip, scrub, examine.
 * Pure Java; the Android side drives tick() from a Choreographer/Handler and
 * implements RenderTarget by forwarding to TerminalRenderer.
 */
public final class ReplayController
{
	public interface RenderTarget
	{
		/** Paint a full screen state (grid + side channels + layout). */
		void render(ScreenState state, int pos);
		/** Position/play-state changed (update scrubber, labels, buttons). */
		void onPositionChanged(int pos, boolean playing);
	}

	private final RecordingReader reader;
	private final ReplayTimeline timeline;
	private final ChunkCache cache;
	/** Draws on top of the recorded screen (e.g. the examine cursor). */
	public interface Overlay
	{
		ScreenState apply(ScreenState recorded);
	}

	private final ReplayClock clock = new ReplayClock();
	private Overlay overlay;
	private final KnowledgeResolver knowledge;
	private final RenderTarget target;
	private int pos;
	private boolean playing;
	private int renderedPos = -1;

	public ReplayController(RecordingReader reader, boolean includeAbandoned,
			int chunkCacheSize, RenderTarget target)
	{
		this.reader = reader;
		this.timeline = new ReplayTimeline(reader.frameIndex(), reader.events(), includeAbandoned);
		this.cache = new ChunkCache(reader, chunkCacheSize);
		this.knowledge = new KnowledgeResolver(reader, timeline);
		this.target = target;
	}

	public ReplayTimeline timeline() { return timeline; }
	public ReplayClock clock() { return clock; }
	public int position() { return pos; }
	public boolean isPlaying() { return playing; }

	public void setOverlay(Overlay o)
	{
		overlay = o;
		refresh();
	}

	/** Re-render the current position (overlay changed, cursor moved). */
	public void refresh()
	{
		renderedPos = -1;
		seekInternal(pos);
	}

	public void play()
	{
		if (clock.atEnd(timeline, pos))
			return;
		playing = true;
		clock.reset();
		notifyChanged();
	}

	public void pause()
	{
		playing = false;
		notifyChanged();
	}

	/** Toggle rewind: reverse play at the current speed. */
	public void setRewinding(boolean rewind)
	{
		clock.setDirection(rewind ? -1 : 1);
		if (!playing)
			play();
	}

	public void setSpeed(double speed) { clock.setSpeed(speed); }

	public void setClockMode(ReplayClock.Mode m) { clock.setMode(m); }

	/** Called every display frame while playing. */
	public void tick(long wallMs)
	{
		if (!playing)
			return;
		int p = clock.advance(timeline, pos, wallMs);
		if (clock.atEnd(timeline, p))
			playing = false;
		seekInternal(p);
	}

	public void seek(int newPos)
	{
		seekInternal(timeline.clamp(newPos));
	}

	public void step(ReplayTimeline.Unit unit, int dir)
	{
		pause();
		seek(timeline.step(pos, unit, dir));
	}

	public void skipTurns(int n)
	{
		seek(timeline.skipTurns(pos, n));
	}

	/** Skip to next/previous event of the given types (null = any). */
	public boolean skipEvent(int dir, Set<String> types)
	{
		int p = timeline.nextEvent(pos, dir, types);
		if (p < 0)
			return false;
		seek(p);
		return true;
	}

	public void seekTurn(int turn) { seek(timeline.posForTurn(turn)); }

	/** Examine: everything the player could look up at the current position. */
	public KnowledgeResolver.Knowledge knowledge()
	{
		return timeline.size() == 0 ? null : knowledge.resolve(timeline.frame(pos), timeline.aut(pos));
	}

	public KnowledgeResolver resolver() { return knowledge; }

	/** Ctrl-P log as of the current position. */
	public List<RecordingReader.MessageRow> messages(int limit)
	{
		return reader.messagesUpTo(timeline.size() == 0 ? -1 : timeline.frame(pos), limit);
	}

	/** Chunk to decode ahead of time in the play direction (prefetch hint). */
	public int prefetchChunk()
	{
		int dir = clock.direction();
		int cur = timeline.chunkId(pos);
		for (int i = pos; i >= 0 && i < timeline.size(); i += dir)
			if (timeline.chunkId(i) != cur)
				return cache.contains(timeline.chunkId(i)) ? -1 : timeline.chunkId(i);
		return -1;
	}

	public void prefetch(int chunkId)
	{
		if (chunkId >= 0)
			cache.get(chunkId);
	}

	private void seekInternal(int p)
	{
		pos = p;
		if (timeline.size() > 0 && p != renderedPos)
		{
			ScreenState st = cache.state(timeline.chunkId(p), timeline.frame(p));
			if (overlay != null)
				st = overlay.apply(st);
			target.render(st, p);
			renderedPos = p;
		}
		notifyChanged();
	}

	private void notifyChanged()
	{
		target.onPositionChanged(pos, playing);
	}
}
