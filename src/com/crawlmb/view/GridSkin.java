package com.crawlmb.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import com.crawlmb.R;

// Floating 9-grid themes, drawn by both the in-game overlay and the editor
// preview. Visual only: tap zones stay the full 3x3 cells.
public class GridSkin
{
	public static final int DEFAULT = 0;
	public static final int ROUNDED = 1;
	public static final int CROSS = 2;
	public static final int ROUNDED_CROSS = 3;
	public static final int DPAD = 4;
	public static final int ROUNDED_DPAD = 5;
	public static final int NONE = 6;
	public static final int COUNT = 7;

	public static final int ARROWS_NONE = 0;
	public static final int ARROWS_TRIANGLE = 1;
	public static final int ARROWS_CARAT = 2;
	public static final int ARROWS_ARROW = 3;
	public static final int ARROWS_BOX = 4;
	public static final int ARROWS_COUNT = 5;

	public static final float DEFAULT_OPACITY_EXPONENT = 2f;
	// Slider positions; 45 = 20% alpha at the default exponent.
	public static final int LINE_OPACITY_DEFAULT = 45;
	public static final int FILL_OPACITY_DEFAULT = 35;

	private static final float RADIUS_DP = 12;
	private static final float DPAD_GAP_DP = 2;
	// D-Pad arms taper: the side facing the center is this share of the
	// outer side.
	private static final float DPAD_INNER = 0.75f;
	// Arrow side as a share of its cell's shorter side.
	private static final float ARROW_SCALE = 0.8f;
	// Cubic control-point factor for a quarter circle (matches drawRoundRect).
	private static final float KAPPA = 0.5523f;
	// Cross outline, clockwise from the top arm's top-left; the concave
	// corners (2, 5, 8, 11) stay sharp when rounded.
	private static final int[] CROSS_XI =
			{ 1, 2, 2, 3, 3, 2, 2, 1, 1, 0, 0, 1 };
	private static final int[] CROSS_YI =
			{ 0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 1, 1 };
	// Per style, drawables for arms up, left, right, down (drawDpad order).
	private static final int[][] ARROW_RES = {
			null,
			{ R.drawable.ic_grid_arrow_triangle_up,
					R.drawable.ic_grid_arrow_triangle_left,
					R.drawable.ic_grid_arrow_triangle_right,
					R.drawable.ic_grid_arrow_triangle_down },
			{ R.drawable.ic_grid_arrow_carat_up,
					R.drawable.ic_grid_arrow_carat_left,
					R.drawable.ic_grid_arrow_carat_right,
					R.drawable.ic_grid_arrow_carat_down },
			{ R.drawable.ic_grid_arrow_arrow_up,
					R.drawable.ic_grid_arrow_arrow_left,
					R.drawable.ic_grid_arrow_arrow_right,
					R.drawable.ic_grid_arrow_arrow_down },
			{ R.drawable.ic_grid_arrow_box_up,
					R.drawable.ic_grid_arrow_box_left,
					R.drawable.ic_grid_arrow_box_right,
					R.drawable.ic_grid_arrow_box_down } };

	// Everything the float grid saves besides its geometry; one value for
	// every region. Colors are the resolved ones (fallback applied).
	public static class Style
	{
		public int theme;
		public int lineOpacity;
		public int lineColor;
		public boolean fill;
		public int fillOpacity;
		public int fillColor;
		public int arrows;

		public void reset(int defaultColor)
		{
			theme = DEFAULT;
			lineOpacity = LINE_OPACITY_DEFAULT;
			lineColor = defaultColor;
			fill = false;
			fillOpacity = FILL_OPACITY_DEFAULT;
			fillColor = defaultColor;
			arrows = ARROWS_NONE;
		}
	}

	private final Context context;
	private final Paint borderPaint;
	private final Paint linePaint;
	private final float radius;
	private final float dpadGap;
	private final float[] xs = new float[4]; // {L, v1, v2, R}
	private final float[] ys = new float[4]; // {T, h1, h2, B}
	private final float[] px = new float[12];
	private final float[] py = new float[12];
	private final Path path = new Path();
	private final RectF rect = new RectF();
	private final Drawable[][] arrowIcons = new Drawable[ARROWS_COUNT][];

	// Slider percent -> paint alpha. exponent > 1 gives the faint end more of
	// the slider's travel.
	public static int opacityAlpha(int percent, float exponent)
	{
		return Math.round(255f
				* (float) Math.pow(percent / 100f, exponent));
	}

	public GridSkin(Context context, Paint borderPaint, Paint linePaint)
	{
		this.context = context;
		this.borderPaint = borderPaint;
		this.linePaint = linePaint;
		float density = context.getResources().getDisplayMetrics().density;
		this.radius = RADIUS_DP * density;
		this.dpadGap = DPAD_GAP_DP * density;
	}

	private static boolean isDpad(int skin)
	{
		return skin == DPAD || skin == ROUNDED_DPAD;
	}

	// Outer edges inset by half the frame stroke so it isn't clipped at the
	// screen edge; divider positions stay relative to the full box.
	private void computeCoords(RectF box, float[] lines)
	{
		float inset = borderPaint.getStrokeWidth() / 2f;
		xs[0] = box.left + inset;
		xs[1] = box.left + lines[0] * box.width();
		xs[2] = box.left + lines[1] * box.width();
		xs[3] = box.right - inset;
		ys[0] = box.top + inset;
		ys[1] = box.top + lines[2] * box.height();
		ys[2] = box.top + lines[3] * box.height();
		ys[3] = box.bottom - inset;
	}

	// Fill (null = none) and interior dividers.
	public void drawContent(Canvas canvas, int skin, RectF box, float[] lines,
			Paint fill)
	{
		computeCoords(box, lines);
		switch (skin)
		{
		case NONE:
			break;
		case ROUNDED:
			canvas.save();
			path.reset();
			rect.set(xs[0], ys[0], xs[3], ys[3]);
			float r = clampRadius(rect);
			path.addRoundRect(rect, r, r, Path.Direction.CW);
			canvas.clipPath(path);
			drawDefaultContent(canvas, box, fill);
			canvas.restore();
			break;
		case CROSS:
		case ROUNDED_CROSS:
			if (fill != null)
			{
				buildCross(skin == ROUNDED_CROSS);
				canvas.drawPath(path, fill);
			}
			// Center box edges; corner boxes aren't drawn.
			canvas.drawLine(xs[1], ys[1], xs[1], ys[2], linePaint);
			canvas.drawLine(xs[2], ys[1], xs[2], ys[2], linePaint);
			canvas.drawLine(xs[1], ys[1], xs[2], ys[1], linePaint);
			canvas.drawLine(xs[1], ys[2], xs[2], ys[2], linePaint);
			break;
		case DPAD:
		case ROUNDED_DPAD:
			if (fill != null)
				drawDpad(canvas, skin == ROUNDED_DPAD, fill);
			break;
		default:
			drawDefaultContent(canvas, box, fill);
			break;
		}
	}

	private void drawDefaultContent(Canvas canvas, RectF box, Paint fill)
	{
		if (fill != null)
			canvas.drawRect(box, fill);
		for (int i = 1; i <= 2; i++)
		{
			canvas.drawLine(xs[i], box.top, xs[i], box.bottom, linePaint);
			canvas.drawLine(box.left, ys[i], box.right, ys[i], linePaint);
		}
	}

	public void drawFrame(Canvas canvas, int skin, RectF box, float[] lines)
	{
		computeCoords(box, lines);
		switch (skin)
		{
		case NONE:
			break;
		case ROUNDED:
			rect.set(xs[0], ys[0], xs[3], ys[3]);
			float r = clampRadius(rect);
			canvas.drawRoundRect(rect, r, r, borderPaint);
			break;
		case CROSS:
		case ROUNDED_CROSS:
			buildCross(skin == ROUNDED_CROSS);
			canvas.drawPath(path, borderPaint);
			break;
		case DPAD:
		case ROUNDED_DPAD:
			drawDpad(canvas, skin == ROUNDED_DPAD, borderPaint);
			break;
		default:
			canvas.drawRect(xs[0], ys[0], xs[3], ys[3], borderPaint);
			break;
		}
	}

	// One arrow per direction arm, centered in the arm's cell (the D-Pad's
	// shrunk cell) and scaled uniformly to fit.
	public void drawArrows(Canvas canvas, int style, int skin, RectF box,
			float[] lines, int color, int alpha)
	{
		if (style <= ARROWS_NONE || style >= ARROWS_COUNT || alpha <= 0)
			return;
		if (arrowIcons[style] == null)
		{
			arrowIcons[style] = new Drawable[4];
			for (int arm = 0; arm < 4; arm++)
				arrowIcons[style][arm] = context
						.getDrawable(ARROW_RES[style][arm]).mutate();
		}
		computeCoords(box, lines);
		for (int arm = 0; arm < 4; arm++)
		{
			armCell(arm, isDpad(skin));
			float size = Math.min(rect.width(), rect.height()) * ARROW_SCALE;
			if (size <= 0)
				continue;
			float half = size / 2f;
			Drawable d = arrowIcons[style][arm];
			d.setTint(color | 0xFF000000);
			d.setAlpha(alpha);
			d.setBounds(Math.round(rect.centerX() - half),
					Math.round(rect.centerY() - half),
					Math.round(rect.centerX() + half),
					Math.round(rect.centerY() + half));
			d.draw(canvas);
		}
	}

	private float clampRadius(RectF r)
	{
		return Math.max(0f, Math.min(radius,
				Math.min(r.width(), r.height()) / 2f));
	}

	// rect = arm's cell (0 up, 1 left, 2 right, 3 down), shrunk by the D-Pad
	// gap when dpad.
	private void armCell(int arm, boolean dpad)
	{
		int xi = arm == 1 ? 0 : arm == 2 ? 2 : 1;
		int yi = arm == 0 ? 0 : arm == 3 ? 2 : 1;
		float gap = dpad ? dpadGap : 0f;
		rect.set(xs[xi] + gap, ys[yi] + gap, xs[xi + 1] - gap,
				ys[yi + 1] - gap);
	}

	// The four arms, shrunk by the gap and tapered toward the center; the
	// center box is dropped.
	private void drawDpad(Canvas canvas, boolean rounded, Paint paint)
	{
		for (int arm = 0; arm < 4; arm++)
		{
			armCell(arm, true);
			if (rect.width() <= 0 || rect.height() <= 0)
				continue;
			float l = rect.left;
			float t = rect.top;
			float r = rect.right;
			float b = rect.bottom;
			float iw = rect.width() * (1f - DPAD_INNER) / 2f;
			float ih = rect.height() * (1f - DPAD_INNER) / 2f;
			switch (arm)
			{
			case 0: // up: inner side is the bottom
				setQuad(l, t, r, t, r - iw, b, l + iw, b);
				break;
			case 1: // left: inner side is the right
				setQuad(l, t, r, t + ih, r, b - ih, l, b);
				break;
			case 2: // right: inner side is the left
				setQuad(l, t + ih, r, t, r, b, l, b - ih);
				break;
			default: // down: inner side is the top
				setQuad(l + iw, t, r - iw, t, r, b, l, b);
				break;
			}
			buildPolygon(4, rounded, false);
			canvas.drawPath(path, paint);
		}
	}

	private void setQuad(float x0, float y0, float x1, float y1, float x2,
			float y2, float x3, float y3)
	{
		px[0] = x0;
		py[0] = y0;
		px[1] = x1;
		py[1] = y1;
		px[2] = x2;
		py[2] = y2;
		px[3] = x3;
		py[3] = y3;
	}

	private void buildCross(boolean rounded)
	{
		for (int i = 0; i < 12; i++)
		{
			px[i] = xs[CROSS_XI[i]];
			py[i] = ys[CROSS_YI[i]];
		}
		buildPolygon(12, rounded, true);
	}

	// Closed path through px/py[0..n). rounded = arc every corner, except
	// the cross's concave ones when cross.
	private void buildPolygon(int n, boolean rounded, boolean cross)
	{
		path.reset();
		for (int i = 0; i < n; i++)
		{
			boolean arc = rounded && !(cross && i % 3 == 2);
			int prev = (i + n - 1) % n;
			int next = (i + 1) % n;
			float dPrev = dist(i, prev);
			float dNext = dist(i, next);
			// Half an edge at most, so neighbouring arcs never overlap.
			float r = arc && dPrev > 0f && dNext > 0f
					? Math.min(radius, Math.min(dPrev, dNext) / 2f) : 0f;
			if (r <= 0f)
			{
				if (i == 0)
					path.moveTo(px[i], py[i]);
				else
					path.lineTo(px[i], py[i]);
				continue;
			}
			float ax = px[i] + (px[prev] - px[i]) * r / dPrev;
			float ay = py[i] + (py[prev] - py[i]) * r / dPrev;
			float bx = px[i] + (px[next] - px[i]) * r / dNext;
			float by = py[i] + (py[next] - py[i]) * r / dNext;
			if (i == 0)
				path.moveTo(ax, ay);
			else
				path.lineTo(ax, ay);
			path.cubicTo(ax + (px[i] - ax) * KAPPA, ay + (py[i] - ay) * KAPPA,
					bx + (px[i] - bx) * KAPPA, by + (py[i] - by) * KAPPA,
					bx, by);
		}
		path.close();
	}

	private float dist(int a, int b)
	{
		return (float) Math.hypot(px[a] - px[b], py[a] - py[b]);
	}
}
