package com.crawlmb.recording.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.crawlmb.R;

import java.util.Collections;

/**
 * Range picker over the whole timeline: a line with a caret at each end of
 * the selected span, labelled with its turn unless at that end of the game.
 * Drag a caret to move that end, drag the span to pan it.
 * Values are TimelineBar units (inclusive).
 */
public final class RangeBar extends View
{
	public interface Listener
	{
		void onRangeChanged(int lo, int hi);
	}

	public interface Labeler
	{
		String label(int unit);
	}

	private static final int NONE = 0, LO = 1, HI = 2, PAN = 3;
	/** y of the line, in dp from the top. */
	static final int LINE_DP = 16;

	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path caret = new Path();
	private final int lineColour;
	private final int spanColour;
	private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
	private Listener listener;
	private Labeler labeler;
	private int units = 1;
	private int lo, hi;
	private int drag = NONE;
	private float grabOffset;

	public RangeBar(Context ctx)
	{
		super(ctx);
		lineColour = ContextCompat.getColor(ctx, R.color.crawl_border);
		spanColour = ContextCompat.getColor(ctx, R.color.crawl_accent);
		text.setColor(spanColour);
		text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11,
				ctx.getResources().getDisplayMetrics()));
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b)
	{
		super.onLayout(changed, l, t, r, b);
		// Carets sit near the screen edges: keep the back gesture off them.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, r - l, b - t)));
	}

	public void setListener(Listener l) { listener = l; }

	public void setLabeler(Labeler l) { labeler = l; }

	public void setRange(int units, int lo, int hi)
	{
		this.units = Math.max(1, units);
		this.hi = Math.max(0, Math.min(hi, this.units - 1));
		this.lo = Math.max(0, Math.min(lo, this.hi));
		invalidate();
	}

	public int lo() { return lo; }
	public int hi() { return hi; }

	private float x(int unit)
	{
		int w = getWidth() - getPaddingLeft() - getPaddingRight();
		return getPaddingLeft() + w * (units <= 1 ? 0f : unit / (float) (units - 1));
	}

	private int unitAt(float x)
	{
		int w = Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
		int u = Math.round((x - getPaddingLeft()) / w * (units - 1));
		return Math.max(0, Math.min(units - 1, u));
	}

	@Override
	protected void onDraw(Canvas c)
	{
		float y = dp(LINE_DP);
		paint.setStrokeWidth(dp(2));
		paint.setColor(lineColour);
		c.drawLine(x(0), y, x(units - 1), y, paint);
		paint.setStrokeWidth(dp(4));
		paint.setColor(spanColour);
		c.drawLine(x(lo), y, Math.max(x(hi), x(lo) + 2), y, paint);
		drawCaret(c, x(lo), y);
		drawCaret(c, x(hi), y);
		drawLabels(c, y - dp(5));
	}

	/** Turn above each inner caret, centred on it but kept apart and on screen. */
	private void drawLabels(Canvas c, float baseline)
	{
		if (labeler == null)
			return;
		String a = lo > 0 ? labeler.label(lo) : null;
		String b = hi < units - 1 ? labeler.label(hi) : null;
		float wa = a == null ? 0 : text.measureText(a), wb = b == null ? 0 : text.measureText(b);
		float la = x(lo) - wa / 2, lb = x(hi) - wb / 2, gap = dp(8);
		if (a != null && b != null && la + wa + gap > lb)
		{
			float mid = (x(lo) + x(hi)) / 2;
			la = mid - gap / 2 - wa;
			lb = mid + gap / 2;
		}
		if (a != null)
			c.drawText(a, Math.max(0, Math.min(la, getWidth() - wa - (b != null ? wb + gap : 0))), baseline, text);
		if (b != null)
			c.drawText(b, Math.min(getWidth() - wb, Math.max(lb, a != null ? wa + gap : 0)), baseline, text);
	}

	private void drawCaret(Canvas c, float x, float y)
	{
		float s = dp(6);
		caret.reset();
		caret.moveTo(x, y + dp(2));
		caret.lineTo(x - s, y + dp(2) + s * 1.4f);
		caret.lineTo(x + s, y + dp(2) + s * 1.4f);
		caret.close();
		c.drawPath(caret, paint);
	}

	@Override
	public boolean onTouchEvent(MotionEvent ev)
	{
		if (!isEnabled())
			return false;
		float ex = ev.getX();
		switch (ev.getActionMasked())
		{
		case MotionEvent.ACTION_DOWN:
			float dLo = Math.abs(ex - x(lo)), dHi = Math.abs(ex - x(hi));
			int grab = dp(20);
			if (Math.min(dLo, dHi) <= grab)
				// Equal distance (collapsed span): pick by side so both ends stay reachable.
				drag = dLo < dHi || dLo == dHi && ex < x(lo) ? LO : HI;
			else if (ex > x(lo) && ex < x(hi))
			{
				drag = PAN;
				grabOffset = ex - x(lo);
			}
			else
				return false;
			getParent().requestDisallowInterceptTouchEvent(true);
			return true;
		case MotionEvent.ACTION_MOVE:
			int span = hi - lo;
			// Keep the active timeline at least ~2% of the game wide.
			int min = Math.max(1, units / 50);
			if (drag == LO)
				lo = Math.max(0, Math.min(unitAt(ex), hi - min));
			else if (drag == HI)
				hi = Math.min(units - 1, Math.max(unitAt(ex), lo + min));
			else if (drag == PAN)
			{
				lo = Math.max(0, Math.min(unitAt(ex - grabOffset), units - 1 - span));
				hi = lo + span;
			}
			else
				return false;
			invalidate();
			if (listener != null)
				listener.onRangeChanged(lo, hi);
			return true;
		case MotionEvent.ACTION_UP:
		case MotionEvent.ACTION_CANCEL:
			drag = NONE;
			return true;
		default:
			return true;
		}
	}

	private int dp(int v)
	{
		return Math.round(v * getResources().getDisplayMetrics().density);
	}
}
