package com.crawlmb.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.crawlmb.CrawlDialog;
import com.crawlmb.Preferences;

import java.util.Hashtable;

public class RegionTermView extends View
{
	private static final String TAG = "RegionTermView";
	public static final int MAX_FONT_SIZE = 72;
	public static final int MIN_FONT_SIZE = 6;

	private final int startRow, startCol, endRow, endCol;
	private final int regionRows, regionCols;

	Typeface tfStd;
	Typeface tfTiny;
	Bitmap bitmap;
	Canvas canvas;
	Paint fore;
	Paint back;

	public int canvas_width = 0;
	public int canvas_height = 0;

	private int char_height = 0;
	private int char_width = 0;
	private int font_text_size = 0;

	private Handler handler = null;
	private boolean triggerGameStart = false;

	private float fontScaleMultiplier = 1.0f;
	private boolean centerHorizontally = false;
	private int drawOffsetX = 0;
	private int offsetCols = 0;

	private boolean horizontalScrollEnabled = false;
	private int scrollOffsetX = 0;
	private GestureDetector scrollDetector;

	public RegionTermView(Context context, int startRow, int startCol, int endRow, int endCol)
	{
		super(context);
		this.startRow = startRow;
		this.startCol = startCol;
		this.endRow = endRow;
		this.endCol = endCol;
		this.regionRows = endRow - startRow;
		this.regionCols = endCol - startCol;
		initPaints();
	}

	public void setFontScaleMultiplier(float multiplier)
	{
		if (this.fontScaleMultiplier == multiplier)
			return;
		this.fontScaleMultiplier = multiplier;
		// If we're already laid out, kick a re-measure so the font is
		// re-rasterized at the new scale and the bitmap is recreated to
		// match the new char dimensions (see onMeasure).
		if (canvas != null)
			requestLayout();
	}

	public float getFontScaleMultiplier()
	{
		return fontScaleMultiplier;
	}

	public boolean isHorizontalScrollEnabled()
	{
		return horizontalScrollEnabled;
	}

	public void setHorizontalScrollEnabled(boolean enabled)
	{
		if (this.horizontalScrollEnabled == enabled)
			return;
		this.horizontalScrollEnabled = enabled;
		if (!enabled)
		{
			// Reset offset so a new scrollable menu doesn't inherit a stale
			// scroll position from a prior one.
			scrollOffsetX = 0;
			invalidate();
		}
		if (enabled && scrollDetector == null)
		{
			scrollDetector = new GestureDetector(getContext(),
					new GestureDetector.SimpleOnGestureListener()
					{
						@Override
						public boolean onScroll(MotionEvent e1, MotionEvent e2,
								float distanceX, float distanceY)
						{
							int maxScroll = Math.max(0, canvas_width - getWidth());
							scrollOffsetX = Math.max(0,
									Math.min(maxScroll, scrollOffsetX + (int) distanceX));
							invalidate();
							return true;
						}

						@Override
						public boolean onDown(MotionEvent e)
						{
							return true;
						}
					});
		}
	}

	@Override
	public boolean onTouchEvent(MotionEvent event)
	{
		if (horizontalScrollEnabled && scrollDetector != null)
		{
			scrollDetector.onTouchEvent(event);
			return true;
		}
		return super.onTouchEvent(event);
	}

	public void setCenterHorizontally(boolean center)
	{
		this.centerHorizontally = center;
	}

	public void setOffsetCols(int cols)
	{
		this.offsetCols = cols;
	}

	public void setGameStartTrigger(Handler handler)
	{
		this.handler = handler;
		this.triggerGameStart = true;
	}

	private void initPaints()
	{
		fore = new Paint();
		fore.setTextAlign(Paint.Align.LEFT);
		if (isHighRes())
			fore.setAntiAlias(true);
		fore.setColor(Color.WHITE);

		back = new Paint();
		back.setColor(Color.BLACK);
	}

	@Override
	protected void onDraw(Canvas canvas)
	{
		if (bitmap != null)
		{
			canvas.drawBitmap(bitmap, drawOffsetX - scrollOffsetX, 0, null);
		}
	}

	public void computeCanvasSize()
	{
		canvas_width = regionCols * char_width;
		canvas_height = regionRows * char_height;
	}

	public void drawPoint(int r, int c, char ch, int fcolor, int bcolor, boolean extendedErase)
	{
		if (r < startRow || r >= endRow || c < startCol || c >= endCol)
			return;

		int localR = r - startRow;
		int localC = c - startCol;

		float x = localC * char_width;
		float y = localR * char_height;

		if (canvas == null)
		{
			Log.d(TAG, "null canvas in drawPoint");
			return;
		}

		back.setColor(bcolor);
		canvas.drawRect(x, y, x + char_width + (extendedErase ? 1 : 0),
				y + char_height + (extendedErase ? 1 : 0), back);

		if (ch != ' ')
		{
			fore.setColor(fcolor);
			canvas.drawText(ch + "", x, y + char_height - fore.descent(), fore);
		}
	}

	public boolean onGameStart()
	{
		computeCanvasSize();
		if (canvas_width == 0 || canvas_height == 0)
			return false;

		bitmap = Bitmap.createBitmap(canvas_width, canvas_height, Bitmap.Config.RGB_565);
		canvas = new Canvas(bitmap);
		return true;
	}

	public void autoSizeFontByWidth(int maxWidth)
	{
		if (maxWidth == 0)
			maxWidth = getMeasuredWidth();
		setFontFace();

		if (!isHighRes())
		{
			setFontSizeLegacy();
		}
		else
		{
			font_text_size = MIN_FONT_SIZE;
			do
			{
				font_text_size += 1;
				setFontSize(font_text_size, false);
			} while (char_width * regionCols <= maxWidth && font_text_size < MAX_FONT_SIZE);

			font_text_size -= 1;

			int scaledSize = Math.round(font_text_size * fontScaleMultiplier);
			scaledSize = Math.max(MIN_FONT_SIZE, Math.min(scaledSize, MAX_FONT_SIZE));
			setFontSize(scaledSize, false);
		}
	}

	public void increaseFontSize()
	{
		setFontSize(font_text_size + 1, false);
	}

	public void decreaseFontSize()
	{
		setFontSize(font_text_size - 1, false);
	}

	private void setFontSize(int size, boolean persist)
	{
		setFontFace();

		if (size < MIN_FONT_SIZE)
			size = MIN_FONT_SIZE;
		else if (size > MAX_FONT_SIZE)
			size = MAX_FONT_SIZE;

		font_text_size = size;
		fore.setTextSize(font_text_size);

		char_height = (int) Math.ceil(fore.getFontSpacing());
		char_width = (int) fore.measureText("X", 0, 1);
	}

	private void setFontSizeLegacy()
	{
		font_text_size = 12;
		char_height = 12;
		char_width = 6;
		setFontSize(font_text_size, false);
	}

	private void setFontFace()
	{
		if (!isHighRes())
		{
			tfTiny = getTypeface("6x12.ttf");
			fore.setTypeface(tfTiny);
		}
		else
		{
			String fontFace = Preferences.getFontFace();
			tfStd = getTypeface(fontFace);
			fore.setTypeface(tfStd);
		}
	}

	private static final Hashtable<String, Typeface> cache = new Hashtable<String, Typeface>();

	public Typeface getTypeface(String assetPath)
	{
		synchronized (cache)
		{
			if (!cache.containsKey(assetPath))
			{
				try
				{
					Typeface t = Typeface.createFromAsset(getContext().getAssets(), assetPath);
					cache.put(assetPath, t);
				}
				catch (Exception e)
				{
					Log.e(TAG, "Could not get typeface '" + assetPath + "' because " + e.getMessage());
					return null;
				}
			}
			return cache.get(assetPath);
		}
	}

	public boolean isHighRes()
	{
		Display display =
				((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
		int maxWidth = display.getWidth();
		int maxHeight = display.getHeight();
		return Math.max(maxWidth, maxHeight) > 480;
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
	{
		int width = MeasureSpec.getSize(widthMeasureSpec);

		autoSizeFontByWidth(width);
		computeCanvasSize();

		if (centerHorizontally)
		{
			drawOffsetX = Math.max(0, (width - canvas_width) / 2);
		}
		else
		{
			drawOffsetX = 0;
		}
		drawOffsetX += offsetCols * char_width;

		// Recreate the bitmap if the canvas dimensions have changed (e.g.
		// after a font scale change). drawPoint draws at char_width/height
		// positions, so an undersized bitmap would crop content. We swap
		// references rather than null-then-assign so a concurrent drawPoint
		// from the native thread never sees a null canvas.
		if (canvas_width > 0 && canvas_height > 0
				&& (bitmap == null
					|| bitmap.getWidth() != canvas_width
					|| bitmap.getHeight() != canvas_height))
		{
			Bitmap newBitmap = Bitmap.createBitmap(canvas_width, canvas_height,
					Bitmap.Config.RGB_565);
			Canvas newCanvas = new Canvas(newBitmap);
			bitmap = newBitmap;
			canvas = newCanvas;
		}

		setMeasuredDimension(width, canvas_height);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh)
	{
		super.onSizeChanged(w, h, oldw, oldh);
		if (triggerGameStart && handler != null)
		{
			// One-shot: only fire on the very first size change (initial
			// layout). Later size changes come from font-scale updates and
			// must not retrigger StartGame, which would call resize() and
			// reset RegionRouter's menu/mode state, snapping menu scale
			// back to default. The zoom gestures call resize() directly
			// via NativeWrapper.increase/decreaseFontSize, so they don't
			// depend on this trigger.
			triggerGameStart = false;
			handler.sendEmptyMessage(CrawlDialog.Action.StartGame.ordinal());
		}
	}

	public void clear()
	{
		if (canvas != null)
		{
			canvas.drawPaint(back);
		}
	}
}
