package com.crawlmb.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

public class StatusBarView extends View
{
	private String[] texts = new String[0];
	private int[] colours = new int[0];
	private float fontSizePx = 14f;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private float scrollX = 0f;
	private float lastTouchX;
	private float contentWidth;

	public StatusBarView(Context context)
	{
		super(context);
		paint.setTextAlign(Paint.Align.LEFT);
	}

	public void setTypeface(Typeface tf, String assetName)
	{
		paint.setTypeface(tf);
		paint.setTextScaleX(GameFontShaper.scaleXFor(tf, assetName));
	}

	public void setFontSizePx(float px)
	{
		fontSizePx = px;
		paint.setTextSize(px);
		requestLayout();
	}

	public static Typeface loadGameTypeface(Context ctx, String assetPath)
	{
		try
		{
			return Typeface.createFromAsset(ctx.getAssets(), assetPath);
		}
		catch (Exception e)
		{
			return Typeface.MONOSPACE;
		}
	}

	public void updateLights(String joined, int[] argb)
	{
		if (joined == null || joined.isEmpty())
		{
			texts = new String[0];
			colours = new int[0];
		}
		else
		{
			texts = joined.split("\t", -1);
			colours = argb;
		}
		scrollX = 0f;
		invalidate();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
	{
		paint.setTextSize(fontSizePx);
		int h = (int) Math.ceil(paint.getFontSpacing());
		int w = MeasureSpec.getSize(widthMeasureSpec);
		setMeasuredDimension(w, h + getPaddingTop() + getPaddingBottom());
	}

	@Override
	protected void onDraw(Canvas canvas)
	{
		super.onDraw(canvas);
		if (texts.length == 0)
			return;

		paint.setTextSize(fontSizePx);
		float x = getPaddingLeft() - scrollX;
		float y = -paint.getFontMetrics().top + getPaddingTop();
		float spaceW = paint.measureText(" ");

		for (int i = 0; i < texts.length; i++)
		{
			if (i < colours.length)
				paint.setColor(colours[i] | 0xFF000000);
			canvas.drawText(texts[i], x, y, paint);
			x += paint.measureText(texts[i]) + spaceW;
		}
		contentWidth = x + scrollX - getPaddingLeft();
	}

	@Override
	public boolean onTouchEvent(MotionEvent event)
	{
		float maxScroll = Math.max(0,
				contentWidth - (getWidth() - getPaddingLeft() - getPaddingRight()));
		switch (event.getAction())
		{
			case MotionEvent.ACTION_DOWN:
				lastTouchX = event.getX();
				return true;
			case MotionEvent.ACTION_MOVE:
				float dx = lastTouchX - event.getX();
				lastTouchX = event.getX();
				scrollX = Math.max(0, Math.min(maxScroll, scrollX + dx));
				invalidate();
				return true;
		}
		return super.onTouchEvent(event);
	}
}
