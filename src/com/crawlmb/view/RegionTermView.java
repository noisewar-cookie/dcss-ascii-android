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

	// startRow/endRow/startCol/endCol/regionRows/regionCols are mutable so
	// the router can re-aim a panel at a different terminal slice once it
	// has scanned the actual rendered layout (used for newgame species/
	// background pickers, where the upstream Grid widget's stretch_h means
	// we can't statically pin column boundaries — and the description
	// Switcher's variable height means the sub-items grid's row position is
	// dynamic too).
	private int startRow, endRow;
	private int regionRows;
	private int startCol, endCol;
	private int regionCols;
	// If > 0, autoSizeFontByWidth fits this many cols into the panel's measured
	// width instead of regionCols — used by newgame category panels so their
	// glyphs match the full-80-col mainmenu rendering even though the panel
	// only spans a sub-slice of the terminal.
	private int fontReferenceCols = 0;
	// Optional per-row col shift applied during drawPoint. Length must equal
	// regionRows; entry[i] = number of terminal cols to shift row i's content
	// left when rendering. Used to strip variable leading whitespace from each
	// row of the newgame sub-items col-1 cells (rows are padded with 0/2/4
	// spaces in upstream) so all rows render flush at panel col 0.
	private int[] rowColShift = null;

	Typeface tfStd;
	Typeface tfTiny;
	Bitmap bitmap;
	Canvas canvas;
	Paint fore;
	Paint back;

	// Mirror of every drawn cell (pre-rowColShift). Replayed into a fresh
	// bitmap on font-scale/region-rebind so content survives without a DCSS
	// re-emit. cellChar[r][c] == 0 means "never written" and is skipped.
	private char[][] cellChar;
	private int[][] cellFg;
	private int[][] cellBg;
	private int mirrorRows = 0;
	private int mirrorCols = 0;
	// Guards bitmap/canvas/mirror against the drawPoint (game thread) vs
	// onMeasure/clear (UI thread) race.
	private final Object renderLock = new Object();

	public int canvas_width = 0;
	public int canvas_height = 0;

	private int char_height = 0;
	private int char_width = 0;
	private int font_text_size = 0;

	private Handler handler = null;
	private boolean triggerGameStart = false;

	private float fontScaleMultiplier = 1.0f;
	// Content zoom applied at draw time only — scales the rendered bitmap
	// around the panel center without changing the panel's measured size, so
	// content beyond the panel's bounds is clipped by the view rather than
	// resizing siblings. Used by the map panel pinch zoom.
	private float contentZoom = 1.0f;
	private boolean centerHorizontally = false;
	// When centering, treat the bitmap as this many cols wide instead of
	// regionCols. Lets a panel whose region includes trailing whitespace
	// (e.g. the map's cols 33-36 between dungeon view and HUD) center on
	// the visible content rather than the padded bitmap. 0 = use regionCols.
	private int centerContentCols = 0;
	private int drawOffsetX = 0;
	private int offsetCols = 0;

	private boolean horizontalScrollEnabled = false;
	private boolean verticalScrollEnabled = false;
	private int scrollOffsetX = 0;
	private int scrollOffsetY = 0;
	private int maxContentRow = -1;
	private int maxContentCol = -1;
	private GestureDetector scrollDetector;

	private static final int AXIS_NONE = 0;
	private static final int AXIS_HORIZONTAL = 1;
	private static final int AXIS_VERTICAL = 2;
	private int lockedAxis = AXIS_NONE;
	private float axisAccumX = 0;
	private float axisAccumY = 0;
	private static final float AXIS_LOCK_THRESHOLD = 10f;

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

	// Re-aim this panel at a new terminal row slice. Same semantics as
	// setRegionCols — used by newgame sub panels whose start row depends on
	// the description Switcher's runtime height.
	public void setRegionRows(int startRow, int endRow)
	{
		if (this.startRow == startRow && this.endRow == endRow)
			return;
		this.startRow = startRow;
		this.endRow = endRow;
		this.regionRows = endRow - startRow;
		// rowColShift is sized to the old regionRows; clear so a stale array
		// can't index out of bounds before the router re-applies it.
		this.rowColShift = null;
		if (canvas != null)
			requestLayout();
	}

	public int getStartCol() { return startCol; }
	public int getEndCol() { return endCol; }
	public int getEndRow() { return endRow; }

	// Set per-row col shift for drawPoint. Length must equal regionRows;
	// pass null to clear. See rowColShift field comment.
	public void setRowColShift(int[] shifts)
	{
		if (shifts != null && shifts.length != regionRows)
		{
			Log.w(TAG, "setRowColShift length " + shifts.length
					+ " != regionRows " + regionRows + ", ignoring");
			return;
		}
		this.rowColShift = shifts;
		// No need to requestLayout; shifts only affect drawPoint placement.
		// The next storm's drawPoints will use the new shifts directly.
	}

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

	public void setContentZoom(float zoom)
	{
		if (this.contentZoom == zoom)
			return;
		this.contentZoom = zoom;
		invalidate();
	}

	public float getContentZoom()
	{
		return contentZoom;
	}

	public boolean isHorizontalScrollEnabled()
	{
		return horizontalScrollEnabled;
	}

	public boolean isVerticalScrollEnabled()
	{
		return verticalScrollEnabled;
	}

	// Zero the drag-scroll offsets and repaint. Called by RegionRouter on
	// every screen transition so a panel never re-opens at its prior offset.
	public void resetScroll()
	{
		if (scrollOffsetX == 0 && scrollOffsetY == 0)
			return;
		scrollOffsetX = 0;
		scrollOffsetY = 0;
		invalidate();
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
						boolean bothAxes = horizontalScrollEnabled
								&& verticalScrollEnabled;
						if (bothAxes && lockedAxis == AXIS_NONE)
						{
							axisAccumX += Math.abs(distanceX);
							axisAccumY += Math.abs(distanceY);
							if (axisAccumX >= AXIS_LOCK_THRESHOLD
									|| axisAccumY >= AXIS_LOCK_THRESHOLD)
							{
								lockedAxis = axisAccumX >= axisAccumY
										? AXIS_HORIZONTAL : AXIS_VERTICAL;
							}
							else
							{
								return true;
							}
						}
						boolean scrollH = horizontalScrollEnabled
								&& (!bothAxes || lockedAxis == AXIS_HORIZONTAL);
						boolean scrollV = verticalScrollEnabled
								&& (!bothAxes || lockedAxis == AXIS_VERTICAL);
						boolean changed = false;
						if (scrollH)
						{
							int contentW = maxContentCol >= 0
									? (int)((maxContentCol + 1) * char_width)
									: canvas_width;
							int maxX = Math.max(0, contentW - getWidth());
							int newX = Math.max(0,
									Math.min(maxX, scrollOffsetX + (int) distanceX));
							if (newX != scrollOffsetX)
							{
								scrollOffsetX = newX;
								changed = true;
							}
						}
						if (scrollV)
						{
							int contentH = maxContentRow >= 0
									? (int)((maxContentRow + 1) * char_height)
									: canvas_height;
							int maxY = Math.max(0, contentH - getHeight());
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
			int action = event.getActionMasked();
			if (action == MotionEvent.ACTION_UP
					|| action == MotionEvent.ACTION_CANCEL)
			{
				lockedAxis = AXIS_NONE;
				axisAccumX = 0;
				axisAccumY = 0;
			}
			scrollDetector.onTouchEvent(event);
			return true;
		}
		return super.onTouchEvent(event);
	}

	public void setCenterHorizontally(boolean center)
	{
		this.centerHorizontally = center;
	}

	public void setCenterContentCols(int cols)
	{
		this.centerContentCols = cols;
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
		if (bitmap == null)
			return;
		if (contentZoom != 1.0f)
		{
			// Scale around the panel's geometric center so the dungeon view
			// roughly stays put around the player's typical position. View
			// clipping handles overflow when zoom > 1 — that's the intended
			// "crop to panel" behavior.
			canvas.save();
			canvas.scale(contentZoom, contentZoom,
					getWidth() / 2f, getHeight() / 2f);
			canvas.drawBitmap(bitmap, drawOffsetX - scrollOffsetX,
					-scrollOffsetY, null);
			canvas.restore();
		}
		else
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

		synchronized (renderLock)
		{
			// Record before drawing so the cell survives a bitmap recreate.
			// Mirror uses pre-shift coords; rowColShift only affects render.
			if (cellChar != null
					&& localR >= 0 && localR < mirrorRows
					&& localC >= 0 && localC < mirrorCols)
			{
				cellChar[localR][localC] = ch;
				cellFg[localR][localC] = fcolor;
				cellBg[localR][localC] = bcolor;
			}
			drawCellLocked(localR, localC, ch, fcolor, bcolor, extendedErase);
		}
	}

	// Caller must hold renderLock. Applies rowColShift on the fly so mirror
	// replay uses the current shift.
	private void drawCellLocked(int localR, int localC, char ch, int fcolor, int bcolor, boolean extendedErase)
	{
		// Apply per-row left shift; negative localC means the col was
		// stripped, so skip it.
		if (rowColShift != null && localR >= 0 && localR < rowColShift.length)
		{
			localC -= rowColShift[localR];
			if (localC < 0)
				return;
		}

		if (canvas == null)
			return;

		float x = localC * char_width;
		float y = localR * char_height;

		back.setColor(bcolor);
		canvas.drawRect(x, y, x + char_width + (extendedErase ? 1 : 0),
				y + char_height + (extendedErase ? 1 : 0), back);

		if (ch != ' ')
		{
			fore.setColor(fcolor);
			canvas.drawText(ch + "", x, y + char_height - fore.descent(), fore);
		}

		if (ch != ' ' && ch != 0)
		{
			if (localR > maxContentRow)
				maxContentRow = localR;
			if (localC > maxContentCol)
				maxContentCol = localC;
		}
	}

	// Replay the mirror into the current bitmap after a recreate.
	// Caller must hold renderLock.
	private void repaintAllFromMirrorLocked()
	{
		if (cellChar == null || canvas == null)
			return;
		for (int r = 0; r < mirrorRows; r++)
		{
			char[] row = cellChar[r];
			int[] fgRow = cellFg[r];
			int[] bgRow = cellBg[r];
			for (int c = 0; c < mirrorCols; c++)
			{
				char ch = row[c];
				if (ch == 0)
					continue;
				drawCellLocked(r, c, ch, fgRow[c], bgRow[c], false);
			}
		}
	}

	public boolean onGameStart()
	{
		computeCanvasSize();
		if (canvas_width == 0 || canvas_height == 0)
			return false;

		synchronized (renderLock)
		{
			bitmap = Bitmap.createBitmap(canvas_width, canvas_height, Bitmap.Config.RGB_565);
			canvas = new Canvas(bitmap);
			ensureMirrorSizedLocked();
		}
		return true;
	}

	// (Re)allocate the mirror to match regionRows/regionCols. Drops prior
	// content. Caller must hold renderLock.
	private void ensureMirrorSizedLocked()
	{
		if (mirrorRows == regionRows && mirrorCols == regionCols
				&& cellChar != null)
			return;
		mirrorRows = regionRows;
		mirrorCols = regionCols;
		if (mirrorRows > 0 && mirrorCols > 0)
		{
			cellChar = new char[mirrorRows][mirrorCols];
			cellFg = new int[mirrorRows][mirrorCols];
			cellBg = new int[mirrorRows][mirrorCols];
		}
		else
		{
			cellChar = null;
			cellFg = null;
			cellBg = null;
		}
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
			int contentWidth = centerContentCols > 0
					? centerContentCols * char_width
					: canvas_width;
			drawOffsetX = (width - contentWidth) / 2;
		}
		else
		{
			drawOffsetX = 0;
		}
		drawOffsetX += offsetCols * char_width;

		// Recreate the bitmap on canvas-size change and replay the mirror
		// into it. Locked so concurrent drawPoint never sees a torn pair.
		synchronized (renderLock)
		{
			ensureMirrorSizedLocked();
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
				repaintAllFromMirrorLocked();
			}
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
			int contentH = maxContentRow >= 0
					? (int)((maxContentRow + 1) * char_height)
					: canvas_height;
			int maxY = Math.max(0, contentH - reportedHeight);
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
		synchronized (renderLock)
		{
			maxContentRow = -1;
			maxContentCol = -1;
			if (cellChar != null)
			{
				for (int r = 0; r < mirrorRows; r++)
				{
					java.util.Arrays.fill(cellChar[r], (char) 0);
					java.util.Arrays.fill(cellFg[r], 0);
					java.util.Arrays.fill(cellBg[r], 0);
				}
			}
			if (canvas != null)
			{
				back.setColor(Color.BLACK);
				canvas.drawPaint(back);
			}
		}
	}
}
