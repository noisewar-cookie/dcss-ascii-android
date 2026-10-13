package com.crawlmb.recording.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.crawlmb.Preferences;

/**
 * Examine-mode touch overlay over the map: a plain 3x3 direction grid with no
 * centre action (unlike the in-game DirectionalTouchView). A press moves the
 * cursor once, holding repeats; sliding changes direction, the centre stops.
 */
public final class ReplayDirectionPad extends View
{
	public interface Listener
	{
		void onMove(int dx, int dy);
	}

	private static final int HOLD_INITIAL_DELAY_MS = 400;
	// Pref 0 disables in-game repeat, but here hold-to-repeat is the point.
	private static final int FALLBACK_REPEAT_MS = 120;

	private final Listener listener;
	private final Paint line = new Paint();
	private final Paint fill = new Paint();
	private int dx, dy;
	private boolean pressed;

	private final Runnable repeat = new Runnable()
	{
		@Override
		public void run()
		{
			if (!pressed || (dx == 0 && dy == 0))
				return;
			listener.onMove(dx, dy);
			postDelayed(this, repeatMs());
		}
	};

	public ReplayDirectionPad(Context ctx, Listener listener)
	{
		super(ctx);
		this.listener = listener;
		line.setColor(0x40FFFFFF);
		line.setStrokeWidth(Math.max(1, getResources().getDisplayMetrics().density));
		fill.setColor(0x30FFFFFF);
	}

	private static int repeatMs()
	{
		int ms = Preferences.getTouchDirectionRepeat();
		return ms > 0 ? ms : FALLBACK_REPEAT_MS;
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent e)
	{
		switch (e.getActionMasked())
		{
		case MotionEvent.ACTION_DOWN:
			pressed = true;
			setDirection(e.getX(), e.getY(), true);
			return true;
		case MotionEvent.ACTION_MOVE:
			setDirection(e.getX(), e.getY(), false);
			return true;
		case MotionEvent.ACTION_UP:
		case MotionEvent.ACTION_CANCEL:
			pressed = false;
			removeCallbacks(repeat);
			invalidate();
			return true;
		default:
			return true;
		}
	}

	private void setDirection(float x, float y, boolean down)
	{
		int ndx = Math.min(2, Math.max(0, (int) (3 * x / Math.max(1, getWidth())))) - 1;
		int ndy = Math.min(2, Math.max(0, (int) (3 * y / Math.max(1, getHeight())))) - 1;
		if (!down && ndx == dx && ndy == dy)
			return;
		dx = ndx;
		dy = ndy;
		removeCallbacks(repeat);
		invalidate();
		if (dx == 0 && dy == 0)
			return;
		performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
		listener.onMove(dx, dy);
		postDelayed(repeat, HOLD_INITIAL_DELAY_MS);
	}

	@Override
	protected void onDetachedFromWindow()
	{
		pressed = false;
		removeCallbacks(repeat);
		super.onDetachedFromWindow();
	}

	@Override
	protected void onDraw(Canvas c)
	{
		float w = getWidth() / 3f, h = getHeight() / 3f;
		if (pressed && (dx != 0 || dy != 0))
			c.drawRect((dx + 1) * w, (dy + 1) * h, (dx + 2) * w, (dy + 2) * h, fill);
		for (int i = 1; i < 3; i++)
		{
			c.drawLine(i * w, 0, i * w, getHeight(), line);
			c.drawLine(0, i * h, getWidth(), i * h, line);
		}
	}
}
