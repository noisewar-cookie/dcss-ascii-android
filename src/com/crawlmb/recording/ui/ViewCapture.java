package com.crawlmb.recording.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;

import com.crawlmb.recording.ReplayVideoEncoder;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Export frames drawn from the replay's own views, so videos and GIFs match
 * the screen: the encoder thread asks for a position, the UI thread seeks
 * there, waits for the views to settle and draws the game panel (minus its
 * inset padding and the unused bands around its content) into one reused
 * bitmap.
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
		/** Repaint every cell: routing is diff-only, so a resize can leave stale pixels. */
		void repaint();
	}

	// A view that keeps requesting layout can't stall the export.
	private static final int MAX_LAYOUT_WAITS = 10;

	private final Source src;
	private final AtomicBoolean cancel;
	private final Handler main = new Handler(Looper.getMainLooper());
	private int width, height;
	private Bitmap bitmap;
	private Canvas canvas;
	// Panel size + padding at the last frame; insets settling mid-export resize it.
	private int geometry;

	/** UI thread, once the panel is laid out. */
	ViewCapture(Source src, View panel, AtomicBoolean cancel)
	{
		this.src = src;
		this.cancel = cancel;
		geometry = geometry(panel);
	}

	// Measured on the first frame, not the watcher's: a layout change on the
	// seek (recorded layout, HUD rows) would rescale everything after frame 0.
	@Override
	public void start(int pos) throws IOException
	{
		show(pos);
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
	public Bitmap frame(int pos) throws IOException
	{
		show(pos);
		return bitmap;
	}

	private void show(final int pos) throws IOException
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
		int g = geometry(panel);
		if (g != geometry)
		{
			geometry = g;
			src.repaint();
			Choreographer.getInstance().postFrameCallback(t -> settle(pos, 2, waits, done));
			return;
		}
		if (bitmap == null)
		{
			width = contentWidth(panel);
			height = contentBottom(panel) - contentTop(panel);
		}
		else
			draw(panel);
		done.countDown();
	}

	private void draw(View panel)
	{
		int pw = contentWidth(panel), ph = height;
		// Menus fill from the top; clamp so a frame never reads past the panel.
		int top = Math.max(0, Math.min(contentTop(panel), contentHeight(panel) - ph));
		int w = bitmap.getWidth(), h = bitmap.getHeight();
		float s = Math.min((float) w / pw, (float) h / ph);
		canvas.drawColor(Color.BLACK);
		canvas.save();
		canvas.translate((w - pw * s) / 2, (h - ph * s) / 2);
		canvas.scale(s, s);
		canvas.clipRect(0, 0, pw, ph);
		canvas.translate(-panel.getPaddingLeft(), -panel.getPaddingTop() - top);
		panel.draw(canvas);
		canvas.restore();
	}

	private static int contentWidth(View v)
	{
		return v.getWidth() - v.getPaddingLeft() - v.getPaddingRight();
	}

	// The split stack is bottom-pinned (unfolded, its halves centre), so with
	// the replay controls hidden the extra height is empty space above the
	// topmost visible leaf view.
	private static int contentTop(View panel)
	{
		int top = leafTop(panel);
		return top == Integer.MAX_VALUE ? 0 : Math.max(0, top - panel.getPaddingTop());
	}

	// MAX_VALUE when nothing visible: a visible group whose children are all
	// hidden (e.g. the unused quick-controls column) must not pin the crop at 0.
	private static int leafTop(View v)
	{
		if (!(v instanceof ViewGroup))
			return 0;
		ViewGroup g = (ViewGroup) v;
		int top = Integer.MAX_VALUE;
		for (int i = 0; i < g.getChildCount(); i++)
		{
			View c = g.getChildAt(i);
			int t = c.getVisibility() == View.VISIBLE ? leafTop(c) : Integer.MAX_VALUE;
			if (t != Integer.MAX_VALUE)
				top = Math.min(top, c.getTop() + t);
		}
		return top;
	}

	// Unfolded, the halves centre, leaving a matching empty band below too.
	private static int contentBottom(View panel)
	{
		int bottom = leafBottom(panel);
		return bottom == Integer.MIN_VALUE ? contentHeight(panel)
				: Math.min(contentHeight(panel), bottom - panel.getPaddingTop());
	}

	private static int leafBottom(View v)
	{
		if (!(v instanceof ViewGroup))
			return v.getHeight();
		ViewGroup g = (ViewGroup) v;
		int bottom = Integer.MIN_VALUE;
		for (int i = 0; i < g.getChildCount(); i++)
		{
			View c = g.getChildAt(i);
			int b = c.getVisibility() == View.VISIBLE ? leafBottom(c) : Integer.MIN_VALUE;
			if (b != Integer.MIN_VALUE)
				bottom = Math.max(bottom, c.getTop() + b);
		}
		return bottom;
	}

	private static int geometry(View v)
	{
		return Objects.hash(v.getWidth(), v.getHeight(), v.getPaddingLeft(),
				v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom());
	}

	private static int contentHeight(View v)
	{
		return v.getHeight() - v.getPaddingTop() - v.getPaddingBottom();
	}
}
