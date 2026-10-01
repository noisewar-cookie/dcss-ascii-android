package com.crawlmb.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

// HSV picker: saturation/value square over a hue bar, then an old | new
// preview strip.
public class ColorPickerView extends View
{
	private static final int DEFAULT_WIDTH_DP = 280;
	private static final int BAR_HEIGHT_DP = 28;
	private static final int GAP_DP = 12;
	private static final int MARKER_RADIUS_DP = 8;
	private static final int[] HUE_COLORS = { 0xFFFF0000, 0xFFFFFF00,
			0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000 };
	private static final int DRAG_NONE = 0;
	private static final int DRAG_SV = 1;
	private static final int DRAG_HUE = 2;

	private final float density;
	private final int oldColor;
	private final float[] hsv = new float[3];
	private final float[] pureHue = { 0f, 1f, 1f };
	private final RectF svRect = new RectF();
	private final RectF hueRect = new RectF();
	private final RectF previewRect = new RectF();
	private final Paint svPaint = new Paint();
	private final Paint valuePaint = new Paint();
	private final Paint huePaint = new Paint();
	private final Paint fillPaint = new Paint();
	private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private float shaderHue = -1f;
	private int drag = DRAG_NONE;

	public ColorPickerView(Context context, int color)
	{
		super(context);
		density = context.getResources().getDisplayMetrics().density;
		oldColor = color | 0xFF000000;
		Color.colorToHSV(oldColor, hsv);
		markerPaint.setStyle(Paint.Style.STROKE);
	}

	public int getColor()
	{
		return Color.HSVToColor(hsv);
	}

	private float dp(float v)
	{
		return v * density;
	}

	@Override
	protected void onMeasure(int widthSpec, int heightSpec)
	{
		int w = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
				? Math.round(dp(DEFAULT_WIDTH_DP))
				: MeasureSpec.getSize(widthSpec);
		int h = Math.round(w * 0.6f + 2 * dp(GAP_DP) + 2 * dp(BAR_HEIGHT_DP));
		setMeasuredDimension(w, h);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh)
	{
		super.onSizeChanged(w, h, oldw, oldh);
		float svBottom = w * 0.6f;
		svRect.set(0, 0, w, svBottom);
		float hueTop = svBottom + dp(GAP_DP);
		hueRect.set(0, hueTop, w, hueTop + dp(BAR_HEIGHT_DP));
		float previewTop = hueRect.bottom + dp(GAP_DP);
		previewRect.set(0, previewTop, w, previewTop + dp(BAR_HEIGHT_DP));
		valuePaint.setShader(new LinearGradient(0, svRect.top, 0,
				svRect.bottom, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
		huePaint.setShader(new LinearGradient(hueRect.left, 0, hueRect.right,
				0, HUE_COLORS, null, Shader.TileMode.CLAMP));
		shaderHue = -1f;
	}

	@Override
	protected void onDraw(Canvas canvas)
	{
		super.onDraw(canvas);
		if (shaderHue != hsv[0])
		{
			shaderHue = hsv[0];
			pureHue[0] = hsv[0];
			svPaint.setShader(new LinearGradient(svRect.left, 0, svRect.right,
					0, 0xFFFFFFFF, Color.HSVToColor(pureHue),
					Shader.TileMode.CLAMP));
		}
		canvas.drawRect(svRect, svPaint);
		canvas.drawRect(svRect, valuePaint);
		drawMarker(canvas, svRect.left + hsv[1] * svRect.width(),
				svRect.top + (1f - hsv[2]) * svRect.height());

		canvas.drawRect(hueRect, huePaint);
		drawMarker(canvas, hueRect.left + hsv[0] / 360f * hueRect.width(),
				hueRect.centerY());

		float mid = previewRect.centerX();
		fillPaint.setColor(oldColor);
		canvas.drawRect(previewRect.left, previewRect.top, mid,
				previewRect.bottom, fillPaint);
		fillPaint.setColor(getColor());
		canvas.drawRect(mid, previewRect.top, previewRect.right,
				previewRect.bottom, fillPaint);
	}

	// Black ring under a white one so it reads on any color.
	private void drawMarker(Canvas canvas, float x, float y)
	{
		float r = dp(MARKER_RADIUS_DP);
		markerPaint.setStrokeWidth(dp(3));
		markerPaint.setColor(0xFF000000);
		canvas.drawCircle(x, y, r, markerPaint);
		markerPaint.setStrokeWidth(dp(1.5f));
		markerPaint.setColor(0xFFFFFFFF);
		canvas.drawCircle(x, y, r, markerPaint);
	}

	private static float clamp01(float v)
	{
		return Math.max(0f, Math.min(1f, v));
	}

	@Override
	public boolean onTouchEvent(MotionEvent event)
	{
		float x = event.getX();
		float y = event.getY();
		switch (event.getActionMasked())
		{
		case MotionEvent.ACTION_DOWN:
			// Hue bar hit zone extends into the gaps around it.
			float slack = dp(GAP_DP) / 2f;
			if (svRect.contains(x, y))
				drag = DRAG_SV;
			else if (y >= hueRect.top - slack && y <= hueRect.bottom + slack)
				drag = DRAG_HUE;
			else
				return false;
			getParent().requestDisallowInterceptTouchEvent(true);
			break;
		case MotionEvent.ACTION_MOVE:
			break;
		default:
			drag = DRAG_NONE;
			return true;
		}
		if (drag == DRAG_SV)
		{
			hsv[1] = clamp01((x - svRect.left) / svRect.width());
			hsv[2] = 1f - clamp01((y - svRect.top) / svRect.height());
		}
		else if (drag == DRAG_HUE)
			hsv[0] = clamp01((x - hueRect.left) / hueRect.width()) * 359.9f; // hue is [0, 360)
		invalidate();
		return true;
	}
}
