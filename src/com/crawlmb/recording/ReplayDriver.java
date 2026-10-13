package com.crawlmb.recording;

import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives ReplayController from the UI thread's Choreographer while playing,
 * and decodes the next chunk in the play direction on a background thread.
 */
public final class ReplayDriver implements Choreographer.FrameCallback
{
	private final ReplayController controller;
	private final ExecutorService prefetch = Executors.newSingleThreadExecutor();
	private final Handler main = new Handler(Looper.getMainLooper());
	private long lastNanos;
	private boolean running;
	private int prefetching = -1; // UI thread; chunk queued or decoding

	public ReplayDriver(ReplayController controller)
	{
		this.controller = controller;
	}

	public void play()
	{
		controller.play();
		start();
	}

	public void pause()
	{
		controller.pause();
		running = false;
	}

	public void start()
	{
		if (running)
			return;
		running = true;
		lastNanos = 0;
		Choreographer.getInstance().postFrameCallback(this);
	}

	@Override
	public void doFrame(long frameTimeNanos)
	{
		if (!running)
			return;
		long dtMs = lastNanos == 0 ? 0 : (frameTimeNanos - lastNanos) / 1_000_000L;
		lastNanos = frameTimeNanos;
		controller.tick(Math.min(dtMs, 250)); // don't jump after a stall
		final int next = controller.prefetchChunk();
		if (next >= 0 && next != prefetching && !prefetch.isShutdown())
		{
			prefetching = next;
			prefetch.execute(() ->
			{
				try
				{
					controller.prefetch(next);
				}
				catch (RuntimeException e)
				{
					// Reader closed by release(), or a damaged chunk; the UI
					// thread hits (and reports) the same chunk when it gets there.
				}
				main.post(() ->
				{
					if (prefetching == next)
						prefetching = -1;
				});
			});
		}
		if (controller.isPlaying())
			Choreographer.getInstance().postFrameCallback(this);
		else
			running = false;
	}

	public void release()
	{
		running = false;
		prefetch.shutdownNow();
		main.removeCallbacksAndMessages(null);
	}
}
