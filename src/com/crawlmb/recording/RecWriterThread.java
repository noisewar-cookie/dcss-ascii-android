package com.crawlmb.recording;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single writer thread that owns the current RecordingSink. Producers
 * (game thread, UI thread) only enqueue work, so no disk IO or compression
 * happens on the game thread.
 *
 * TODO(perf): queue is unbounded. The debug perf log reports its depth;
 * if it grows during autoexplore, coalesce consecutive animation-only frames.
 */
public final class RecWriterThread
{
	public interface Op
	{
		void run(RecordingSink sink) throws Exception;
	}

	public interface SinkFactory
	{
		RecordingSink create(GameIdentity id) throws Exception;
	}

	public interface ErrorListener
	{
		void onWriterError(Throwable t);
	}

	private final ExecutorService exec = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "dcrec-writer");
		t.setDaemon(true);
		return t;
	});
	private final SinkFactory factory;
	private final ErrorListener errors;
	private final AtomicInteger pending = new AtomicInteger();
	private RecordingSink sink; // writer thread only

	public RecWriterThread(SinkFactory factory, ErrorListener errors)
	{
		this.factory = factory;
		this.errors = errors;
	}

	public int pendingOps() { return pending.get(); }

	/** Fire-and-forget; skipped if no sink is open. */
	public void submit(Op op)
	{
		pending.incrementAndGet();
		exec.execute(() ->
		{
			try
			{
				if (sink != null)
					op.run(sink);
			}
			catch (Throwable t)
			{
				errors.onWriterError(t);
			}
			finally
			{
				pending.decrementAndGet();
			}
		});
	}

	/** Close any open sink, then open one for `id`. Blocks the caller. */
	public RecordingSink.OpenResult open(GameIdentity id, String appVersion, int[] layout,
			byte[] docDict, long timeoutMs) throws Exception
	{
		return call(() ->
		{
			closeSinkQuietly();
			sink = factory.create(id);
			try
			{
				return sink.open(id, appVersion, layout, docDict);
			}
			catch (RuntimeException e)
			{
				closeSinkQuietly(); // a refused file (e.g. game ended) stays untouched
				throw e;
			}
		}, timeoutMs);
	}

	/** Commit and close the current sink (asynchronously). */
	public void closeSink()
	{
		exec.execute(this::closeSinkQuietly);
	}

	/** Wait until everything queued so far has been written. */
	public void drain(long timeoutMs)
	{
		try
		{
			call(() -> null, timeoutMs);
		}
		catch (Exception e)
		{
			errors.onWriterError(e);
		}
	}

	public void shutdown(long timeoutMs)
	{
		closeSink();
		exec.shutdown();
		try
		{
			exec.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	private <T> T call(Callable<T> c, long timeoutMs) throws Exception
	{
		Future<T> f = exec.submit(c);
		return f.get(timeoutMs, TimeUnit.MILLISECONDS);
	}

	private void closeSinkQuietly()
	{
		if (sink == null)
			return;
		try
		{
			sink.commit();
			sink.close();
		}
		catch (Throwable t)
		{
			errors.onWriterError(t);
		}
		sink = null;
	}
}
