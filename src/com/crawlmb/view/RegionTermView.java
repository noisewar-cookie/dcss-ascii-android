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

	private final int startRow, endRow;
	private final int regionRows;
	// startCol/endCol/regionCols are mutable so the router can re-aim a
	// panel at a different terminal slice once it has scanned the actual
	// rendered layout (used for newgame species/background pickers, where
	// the upstream Grid widget's stretch_h means we can't statically pin
	// column boundaries — we read them off the terminal at detection time).
	private int startCol, endCol;
	private int regionCols;
	// If > 0, autoSizeFontByWidth fits this many cols into the panel's measured
	// width instead of regionCols — used by newgame category panels so their
	// glyphs match the full-80-col mainmenu rendering even though the panel
	// only spans a sub-slice of the terminal.
	private int fontReferenceCols = 0;

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
	private boolean verticalScrollEnabled = false;
	private int scrollOffsetX = 0;
	private int scrollOffsetY = 0;
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

	// Re-aim this panel at a new terminal column slice. No-op if the bounds
	// are unchanged. Triggers a re-measure so the bitmap is recreated to
	// the new width and the font auto-sizes for the new col count.
	public void setRegionCols(int startCol, int endCol)
	{
		if (this.startCol == startCol && this.endCol == endCol)
			return;
		this.startCol = startCol;
		this.endCol = endCol;
		this.regionCols = endCol - startCol;
		if (canvas != null)
			requestLayout();
	}

	public int getStartCol() { return startCol; }
	public int getEndCol() { return endCol; }

	// Decouple font sizing from this panel's regionCols so glyphs match a
	// reference rendering (e.g. the full-80-col mainmenu). 0 = use regionCols.
	public void setFontReferenceCols(int cols)
	{
		if (this.fontReferenceCols == cols)
			return;
		this.fontReferenceCols = cols;
		if (canvas != null)
			requestLayout();
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

	public boolean isVerticalScrollEnabled()
	{
		return verticalScrollEnabled;
	}

	public boolean isScrollEnabled()
	{
		return horizontalScrollEnabled || verticalScrollEnabled;
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
		ensureScrollDetector();
	}

	public void setVerticalScrollEnabled(boolean enabled)
	{
		if (this.verticalScrollEnabled == enabled)
			return;
		this.verticalScrollEnabled = enabled;
		if (!enabled)
		{
			scrollOffsetY = 0;
			// Vertical scroll caps the reported height; toggling it must
			// re-measure so siblings reflow.
			requestLayout();
			invalidate();
		}
		else
		{
			requestLayout();
		}
		ensureScrollDetector();
	}

	private void ensureScrollDetector()
	{
		if (scrollDetector != null)
			return;
		scrollDetector = new GestureDetector(getContext(),
				new GestureDetector.SimpleOnGestureListener()
				{
					@Override
					public boolean onScroll(MotionEvent e1, MotionEvent e2,
							float distanceX, float distanceY)
					{
						boolean changed = false;
						if (horizontalScrollEnabled)
						{
							int maxX = Math.max(0, canvas_width - getWidth());
							int newX = Math.max(0,
									Math.min(maxX, scrollOffsetX + (int) distanceX));
							if (newX != scrollOffsetX)
							{
								scrollOffsetX = newX;
								changed = true;
							}
						}
						if (verticalScrollEnabled)
						{
							int maxY = Math.max(0, canvas_height - getHeight());
							int newY = Math.max(0,
									Math.min(maxY, scrollOffsetY + (int) distanceY));
							if (newY != scrollOffsetY)
							{
								scrollOffsetY = newY;
								changed = true;
							}
						}
						if (changed)
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

	@Override
	public boolean onTouchEvent(MotionEvent event)
	{
		if (isScrollEnabled() && scrollDetector != null)
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
			canvas.drawBitmap(bitmap, drawOffsetX - scrollOffsetX,
					-scrollOffsetY, null);
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
			int fitCols = fontReferenceCols > 0 ? fontReferenceCols : regionCols;
			font_text_size = MIN_FONT_SIZE;
			do
			{
				font_text_size += 1;
				setFontSize(font_text_size, false);
			} while (char_width * fitCols <= maxWidth && font_text_size < MAX_FONT_SIZE);

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

		// When vertical scroll is enabled, cap reported height to the parent
		// constraint so this view doesn't push siblings (or itself) past the
		// visible area; the bitmap is taller and the user pans within the
		// visible window via scrollOffsetY. Without scroll on, report the
		// full bitmap height as before so no scroll is needed.
		int reportedHeight = canvas_height;
		if (verticalScrollEnabled)
		{
			int parentLimit = MeasureSpec.getSize(heightMeasureSpec);
			int mode = MeasureSpec.getMode(heightMeasureSpec);
			if (mode != MeasureSpec.UNSPECIFIED && parentLimit > 0
					&& canvas_height > parentLimit)
			{
				reportedHeight = parentLimit;
			}
			// Re-clamp existing offset against the new viewport size.
			int maxY = Math.max(0, canvas_height - reportedHeight);
			if (scrollOffsetY > maxY)
				scrollOffsetY = maxY;
		}

		setMeasuredDimension(width, reportedHeight);
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
