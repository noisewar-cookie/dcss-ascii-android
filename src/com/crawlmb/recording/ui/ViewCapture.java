package com.crawlmb.recording.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;

import com.crawlmb.recording.ReplayVideoEncoder;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Export frames drawn from the replay's own views, so videos and GIFs match
 * the screen: the encoder thread asks for a position, the UI thread seeks
 * there, waits for the views to settle and draws the game panel (minus its
 * inset padding) into one reused bitmap.
 */
final class ViewCapture implements ReplayVideoEncoder.Frames
{
	interface Source
	{
		/** The game panel; null while the views are being rebuilt. */
		View panel();
		void seek(int pos);
		/** pos has been drawn on the current views. */
		boolean drawn(int pos);
	}

	// A view that keeps requesting layout can't stall the export.
	private static final int MAX_LAYOUT_WAITS = 10;

	private final Source src;
	private final AtomicBoolean cancel;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final int width, height;
	private Bitmap bitmap;
	private Canvas canvas;

	/** UI thread, once the panel is laid out. */
	ViewCapture(Source src, View panel, AtomicBoolean cancel)
	{
		this.src = src;
		this.cancel = cancel;
		width = contentWidth(panel);
		height = contentHeight(panel);
	}

	@Override
	public int width()
	{
		return width;
	}

	@Override
	public int height()
	{
		return height;
	}

	@Override
	public void setOutputSize(int w, int h)
	{
		bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
		canvas = new Canvas(bitmap);
	}

	@Override
	public Bitmap frame(final int pos) throws IOException
	{
		final CountDownLatch done = new CountDownLatch(1);
		main.post(() ->
		{
			if (cancel.get())
				return;
			src.seek(pos);
			settle(pos, 2, 0, done);
		});
		try
		{
			while (!done.await(100, TimeUnit.MILLISECONDS))
				if (cancel.get())
					throw new IOException("cancelled");
		}
		catch (InterruptedException e)
		{
			throw new IOException("interrupted");
		}
		return bitmap;
	}

	// Handler hops drain what the render posted (mode switches, HUD rows,
	// post-layout redraws); a pending layout or view rebuild waits a frame.
	private void settle(final int pos, final int hops, final int waits,
			final CountDownLatch done)
	{
		if (cancel.get())
			return;
		if (hops > 0)
		{
			main.post(() -> settle(pos, hops - 1, waits, done));
			return;
		}
		View panel = src.panel();
		boolean ready = panel != null && src.drawn(pos) && contentWidth(panel) > 0
				&& contentHeight(panel) > 0;
		if (!ready || (panel.isLayoutRequested() && waits < MAX_LAYOUT_WAITS))
		{
			Choreographer.getInstance().postFrameCallback(
					t -> settle(pos, 2, ready ? waits + 1 : waits, done));
			return;
		}
		draw(panel);
		done.countDown();
	}

	private void draw(View panel)
	{
		int pw = contentWidth(panel), ph = contentHeight(panel);
		int w = bitmap.getWidth(), h = bitmap.getHeight();
		float s = Math.min((float) w / pw, (float) h / ph);
		canvas.drawColor(Color.BLACK);
		canvas.save();
		canvas.translate((w - pw * s) / 2, (h - ph * s) / 2);
		canvas.scale(s, s);
		canvas.clipRect(0, 0, pw, ph);
		canvas.translate(-panel.getPaddingLeft(), -panel.getPaddingTop());
		panel.draw(canvas);
		canvas.restore();
	}

	private static int contentWidth(View v)
	{
		return v.getWidth() - v.getPaddingLeft() - v.getPaddingRight();
	}

	private static int contentHeight(View v)
	{
		return v.getHeight() - v.getPaddingTop() - v.getPaddingBottom();
	}
}
