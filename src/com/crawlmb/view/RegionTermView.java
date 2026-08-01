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
import android.view.ScaleGestureDetector;
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
	// Non-null on panels owned by one menu type (newgame species/background/
	// weapon); routeCell skips them while another type is active.
	RegionRouter.MenuType ownerMenuType = null;

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
	// When true, reports the parent's height allocation and offsets the
	// bitmap down so short content sits centered in the panel. Prevents a
	// gap between map and HUD when a wide-cell font makes the width-bound
	// canvas_height shorter than the parent's allocation.
	private boolean centerVertically = false;
	private int drawOffsetY = 0;

	private boolean horizontalScrollEnabled = false;
	private boolean verticalScrollEnabled = false;
	// When > 0 and vertical scroll is enabled, onMeasure caps the reported
	// height to this many rows so the panel keeps a fixed visual slot while
	// its bitmap holds more rows (word-wrap msg panel: 7 visible of 14).
	private int maxVisibleRows = 0;
	// Keep the viewport pinned to the bottom of content (newest msg lines)
	// on every draw, unless the user has dragged away from the bottom.
	// Dragging back to the bottom re-engages the pin; resetScroll() also
	// re-engages it so transitions reopen at the latest content.
	private boolean stickyScrollToBottom = false;
	private boolean stickyUserAway = false;
	private int scrollOffsetX = 0;
	private int scrollOffsetY = 0;
	private boolean anchorToContent = false;
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

	// LEVELMAP free-pan + pinch-zoom. Session-only: contentZoom resets to
	// 1.0 on mode-off. Min is dynamic (fit-all); max/sensitivity from
	// portrait_levelmap_pinch_* in font_config.txt.
	private boolean mapPanMode = false;
	private ScaleGestureDetector mapPanScaleDetector;
	private boolean mapPanScaling = false;
	private float mapZoomMax = 1.5f;
	private float mapZoomInSensitivity = 1.0f;
	private float mapZoomOutSensitivity = 0.75f;

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
	// Returns true if the bounds changed.
	public boolean setRegionCols(int startCol, int endCol)
	{
		if (this.startCol == startCol && this.endCol == endCol)
			return false;
		this.startCol = startCol;
		this.endCol = endCol;
		this.regionCols = endCol - startCol;
		if (canvas != null)
			requestLayout();
		return true;
	}

	// Re-aim this panel at a new terminal row slice. Same semantics as
	// setRegionCols — used by newgame sub panels whose start row depends on
	// the description Switcher's runtime height.
	public boolean setRegionRows(int startRow, int endRow)
	{
		if (this.startRow == startRow && this.endRow == endRow)
			return false;
		this.startRow = startRow;
		this.endRow = endRow;
		this.regionRows = endRow - startRow;
		// rowColShift is sized to the old regionRows; clear so a stale array
		// can't index out of bounds before the router re-applies it.
		this.rowColShift = null;
		if (canvas != null)
			requestLayout();
		return true;
	}

	/** @deprecated Unused. */
	@Deprecated
	public int getStartCol() { return startCol; }
	/** @deprecated Unused. */
	@Deprecated
	public int getEndCol() { return endCol; }
	public int getEndRow() { return endRow; }

	// When true, onMeasure reports height based on maxContentRow (the lowest
	// row with non-space content) + 1 spacer row, instead of the full
	// canvas_height. The bitmap stays full-size; only the reported height
	// shrinks, so a LinearLayout sibling (e.g. Quick Controls) anchors
	// directly below the actual content.
	public void setAnchorToContent(boolean anchor)
	{
		if (this.anchorToContent == anchor)
			return;
		this.anchorToContent = anchor;
		if (canvas != null)
			requestLayout();
	}

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

	/** @deprecated Unused. */
	@Deprecated
	public float getContentZoom()
	{
		return contentZoom;
	}

	/** @deprecated Unused. */
	@Deprecated
	public boolean isHorizontalScrollEnabled()
	{
		return horizontalScrollEnabled;
	}

	/** @deprecated Unused. */
	@Deprecated
	public boolean isVerticalScrollEnabled()
	{
		return verticalScrollEnabled;
	}

	public void setMaxVisibleRows(int rows)
	{
		if (this.maxVisibleRows == rows)
			return;
		this.maxVisibleRows = rows;
		if (canvas != null)
			requestLayout();
	}

	public void setStickyScrollToBottom(boolean sticky)
	{
		this.stickyScrollToBottom = sticky;
		this.stickyUserAway = false;
	}

	// Visible terminal columns at the current font size, or 0 before the
	// first measure pass. Used to size the DCSS word-wrap width to what the
	// panel can actually show.
	public int computeVisibleCols()
	{
		int width = getMeasuredWidth();
		if (width <= 0 || char_width <= 0)
			return 0;
		return width / char_width;
	}

	// Zero the drag-scroll offsets and repaint. Called by RegionRouter on
	// every screen transition so a panel never re-opens at its prior offset.
	public void resetScroll()
	{
		stickyUserAway = false;
		if (scrollOffsetX == 0 && scrollOffsetY == 0)
			return;
		scrollOffsetX = 0;
		scrollOffsetY = 0;
		invalidate();
	}

	// Single-shot: snap scrollOffsetY to the bottom of content on the next
	// onDraw that has measurable height and content. Used by RegionRouter
	// when entering MenuType.MESSAGES so the Ctrl+P popup opens at the
	// latest messages (FS_START_AT_END places newest content at the bottom
	// of the bitmap, but resetScroll has just zeroed the offset to the top).
	// Consumed inside onDraw; if conditions aren't met (no content yet, view
	// not laid out), the flag persists until the next paint can satisfy it.
	private volatile boolean pendingScrollToBottom = false;

	public void requestScrollToBottom()
	{
		pendingScrollToBottom = true;
	}

	// Single-shot: next onDraw with metrics + laid-out viewport centers
	// the given 0-indexed cell. LEVELMAP player-center on open.
	private volatile int pendingCenterCol = -1;
	private volatile int pendingCenterRow = -1;

	public void requestCenterOnCell(int col, int row)
	{
		pendingCenterCol = col;
		pendingCenterRow = row;
		postInvalidate();
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
						if (mapPanScaling)
							return true;
						boolean bothAxes = horizontalScrollEnabled
								&& verticalScrollEnabled;
						// mapPanMode: free-pan (skip axis lock).
						if (bothAxes && !mapPanMode && lockedAxis == AXIS_NONE)
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
								&& (!bothAxes || mapPanMode
									|| lockedAxis == AXIS_HORIZONTAL);
						boolean scrollV = verticalScrollEnabled
								&& (!bothAxes || mapPanMode
									|| lockedAxis == AXIS_VERTICAL);
						boolean changed = false;
						if (scrollH)
						{
							int maxX = Math.max(0,
									getContentWidthForScroll() - getWidth());
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
							int maxY = Math.max(0,
									getContentHeightForScroll() - getHeight());
							int newY = Math.max(0,
									Math.min(maxY, scrollOffsetY + (int) distanceY));
							if (newY != scrollOffsetY)
							{
								scrollOffsetY = newY;
								changed = true;
							}
							if (stickyScrollToBottom)
								stickyUserAway = newY < maxY;
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
		boolean handled = false;
		if (mapPanMode && mapPanScaleDetector != null)
		{
			mapPanScaleDetector.onTouchEvent(event);
			handled = true;
		}
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
		return handled || super.onTouchEvent(event);
	}

	public void setMapPanMode(boolean enabled)
	{
		if (this.mapPanMode == enabled)
			return;
		this.mapPanMode = enabled;
		if (enabled)
			ensureMapPanScaleDetector();
		else if (contentZoom != 1.0f)
		{
			contentZoom = 1.0f;
			clampMapPanScroll();
			invalidate();
		}
	}

	// Non-positive values fall back to safe defaults so a bad config
	// entry can't disable pinch entirely.
	public void setMapPanConfig(float maxZoom,
			float zoomInSensitivity, float zoomOutSensitivity)
	{
		if (maxZoom > 0)
			this.mapZoomMax = maxZoom;
		this.mapZoomInSensitivity = zoomInSensitivity > 0
				? zoomInSensitivity : 1.0f;
		this.mapZoomOutSensitivity = zoomOutSensitivity > 0
				? zoomOutSensitivity : 1.0f;
	}

	// Fit-all: the zoom where the whole 80×48-cell bitmap fits in the
	// viewport. Sparse levels don't shrink the bitmap, so the bound is
	// fixed per viewport/scale. Capped at 1.0 so we never force zoom-in.
	private float computeDynamicMapZoomMin()
	{
		int vw = getWidth();
		int vh = getHeight();
		if (vw <= 0 || vh <= 0 || canvas_width <= 0 || canvas_height <= 0)
			return 0.5f;
		float fitW = (float) vw / canvas_width;
		float fitH = (float) vh / canvas_height;
		return Math.min(1.0f, Math.min(fitW, fitH));
	}

	private void ensureMapPanScaleDetector()
	{
		if (mapPanScaleDetector != null)
			return;
		mapPanScaleDetector = new ScaleGestureDetector(getContext(),
				new ScaleGestureDetector.SimpleOnScaleGestureListener()
				{
					@Override
					public boolean onScaleBegin(ScaleGestureDetector d)
					{
						mapPanScaling = true;
						return true;
					}

					@Override
					public boolean onScale(ScaleGestureDetector d)
					{
						if (!mapPanMode)
							return false;
						float rawFactor = d.getScaleFactor();
						// Sensitivity exponent: <1 pulls factor toward
						// 1.0 (less zoom per gesture), >1 pushes away.
						float sens = rawFactor < 1.0f
								? mapZoomOutSensitivity
								: mapZoomInSensitivity;
						float effFactor = sens == 1.0f
								? rawFactor
								: (float)Math.pow(rawFactor, sens);
						float newZoom = contentZoom * effFactor;
						float dynMin = computeDynamicMapZoomMin();
						newZoom = Math.max(dynMin,
								Math.min(mapZoomMax, newZoom));
						if (newZoom != contentZoom)
						{
							// Keep pixel under fingers pinned during zoom.
							float fx = d.getFocusX();
							float fy = d.getFocusY();
							float ratio = newZoom / contentZoom;
							scrollOffsetX = Math.round(
									(scrollOffsetX + fx) * ratio - fx);
							scrollOffsetY = Math.round(
									(scrollOffsetY + fy) * ratio - fy);
							contentZoom = newZoom;
							clampMapPanScroll();
							invalidate();
						}
						return true;
					}

					@Override
					public void onScaleEnd(ScaleGestureDetector d)
					{
						mapPanScaling = false;
					}
				});
	}

	// Scale by contentZoom in mapPanMode: bitmap scales from origin in
	// onDraw, so scroll bounds see the post-zoom extent.
	private int getContentWidthForScroll()
	{
		int contentW = computeContentWidth();
		if (mapPanMode)
			contentW = Math.round(contentW * contentZoom);
		return contentW;
	}

	private int getContentHeightForScroll()
	{
		int contentH = maxContentRow >= 0
				? (int)((maxContentRow + 1) * char_height)
				: canvas_height;
		if (mapPanMode)
			contentH = Math.round(contentH * contentZoom);
		return contentH;
	}

	private void clampMapPanScroll()
	{
		if (horizontalScrollEnabled)
		{
			int maxX = Math.max(0,
					getContentWidthForScroll() - getWidth());
			if (scrollOffsetX < 0)
				scrollOffsetX = 0;
			else if (scrollOffsetX > maxX)
				scrollOffsetX = maxX;
		}
		if (verticalScrollEnabled)
		{
			int maxY = Math.max(0,
					getContentHeightForScroll() - getHeight());
			if (scrollOffsetY < 0)
				scrollOffsetY = 0;
			else if (scrollOffsetY > maxY)
				scrollOffsetY = maxY;
		}
	}

	private int computeContentWidth()
	{
		synchronized (renderLock)
		{
			if (cellChar == null)
				return canvas_width;
			int maxCol = -1;
			for (int r = 0; r < mirrorRows; r++)
			{
				for (int c = mirrorCols - 1; c > maxCol; c--)
				{
					if (cellChar[r][c] != 0 && cellChar[r][c] != ' ')
					{
						maxCol = c;
						break;
					}
				}
			}
			return maxCol >= 0 ? (int)((maxCol + 1) * char_width) : 0;
		}
	}

	public void setCenterHorizontally(boolean center)
	{
		this.centerHorizontally = center;
	}

	public void setCenterVertically(boolean center)
	{
		this.centerVertically = center;
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
		if (pendingScrollToBottom && verticalScrollEnabled
				&& char_height > 0 && maxContentRow >= 0)
		{
			int viewportH = getHeight();
			if (viewportH > 0)
			{
				int contentH = (maxContentRow + 1) * char_height;
				int maxY = Math.max(0, contentH - viewportH);
				scrollOffsetY = maxY;
				pendingScrollToBottom = false;
			}
		}
		// Consume pending center. Retries next paint if char metrics or
		// content bounds aren't ready. Bounds mirror onScroll so the
		// target is a reachable scroll position; contentZoom factor keeps
		// goto_level re-centers correct at the user's current zoom.
		if (pendingCenterCol >= 0 && pendingCenterRow >= 0
				&& char_width > 0 && char_height > 0
				&& maxContentCol >= 0 && maxContentRow >= 0)
		{
			int viewportW = getWidth();
			int viewportH = getHeight();
			if (viewportW > 0 && viewportH > 0)
			{
				float zoom = mapPanMode ? contentZoom : 1.0f;
				int pxX = Math.round(
						(pendingCenterCol * char_width + char_width / 2f)
								* zoom);
				int pxY = Math.round(
						(pendingCenterRow * char_height + char_height / 2f)
								* zoom);
				if (horizontalScrollEnabled)
				{
					int targetX = drawOffsetX + pxX - viewportW / 2;
					int contentW = Math.round(
							(maxContentCol + 1) * char_width * zoom);
					int maxX = Math.max(0, contentW - viewportW);
					scrollOffsetX = Math.max(0, Math.min(maxX, targetX));
				}
				if (verticalScrollEnabled)
				{
					int targetY = drawOffsetY + pxY - viewportH / 2;
					int contentH = Math.round(
							(maxContentRow + 1) * char_height * zoom);
					int maxY = Math.max(0, contentH - viewportH);
					scrollOffsetY = Math.max(0, Math.min(maxY, targetY));
				}
				pendingCenterCol = -1;
				pendingCenterRow = -1;
			}
		}
		// Continuous variant of the snap above: keep the newest content row
		// bottom-aligned as new lines arrive, unless the user dragged away.
		if (stickyScrollToBottom && !stickyUserAway && verticalScrollEnabled
				&& char_height > 0 && maxContentRow >= 0)
		{
			int viewportH = getHeight();
			if (viewportH > 0)
			{
				int contentH = (maxContentRow + 1) * char_height;
				scrollOffsetY = Math.max(0, contentH - viewportH);
			}
		}
		if (mapPanMode && contentZoom != 1.0f)
		{
			// Scale around origin so post-zoom draw lands at
			// (drawOffsetX - scrollOffsetX, ...) in screen pixels — pan
			// bounds stay linear and drag feels 1:1 regardless of zoom.
			canvas.save();
			canvas.scale(contentZoom, contentZoom);
			canvas.drawBitmap(bitmap,
					(drawOffsetX - scrollOffsetX) / contentZoom,
					(drawOffsetY - scrollOffsetY) / contentZoom, null);
			canvas.restore();
		}
		else if (contentZoom != 1.0f)
		{
			// Dungeon-view stepped zoom: scale around panel center so
			// content stays put near player. Overflow clips to panel.
			canvas.save();
			canvas.scale(contentZoom, contentZoom,
					getWidth() / 2f, getHeight() / 2f);
			canvas.drawBitmap(bitmap, drawOffsetX - scrollOffsetX,
					drawOffsetY - scrollOffsetY, null);
			canvas.restore();
		}
		else
		{
			canvas.drawBitmap(bitmap, drawOffsetX - scrollOffsetX,
					drawOffsetY - scrollOffsetY, null);
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

	// (Re)allocate the mirror to match regionRows/regionCols. Preserves the
	// overlapping cell content so a regionRows/regionCols change carries the
	// current storm's chars through to the new bitmap via
	// repaintAllFromMirrorLocked — without this, applyMode transitions that
	// shrink the region (e.g. fullView 48→28 rows for SPELLS→DESCRIBE) drop
	// the storm's popup chars and force a full JNI refreshTerminal round-
	// trip to refill. Caller must hold renderLock.
	private void ensureMirrorSizedLocked()
	{
		if (mirrorRows == regionRows && mirrorCols == regionCols
				&& cellChar != null)
			return;
		char[][] oldCh = cellChar;
		int[][]  oldFg = cellFg;
		int[][]  oldBg = cellBg;
		int oldRows = mirrorRows;
		int oldCols = mirrorCols;
		mirrorRows = regionRows;
		mirrorCols = regionCols;
		if (mirrorRows > 0 && mirrorCols > 0)
		{
			cellChar = new char[mirrorRows][mirrorCols];
			cellFg = new int[mirrorRows][mirrorCols];
			cellBg = new int[mirrorRows][mirrorCols];
			if (oldCh != null)
			{
				int copyRows = Math.min(oldRows, mirrorRows);
				int copyCols = Math.min(oldCols, mirrorCols);
				for (int r = 0; r < copyRows; r++)
				{
					System.arraycopy(oldCh[r], 0, cellChar[r], 0, copyCols);
					System.arraycopy(oldFg[r], 0, cellFg[r], 0, copyCols);
					System.arraycopy(oldBg[r], 0, cellBg[r], 0, copyCols);
				}
			}
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
			// Width-fit VeraMoBd, then scale the chosen face so its line
			// height matches VeraMoBd's — so every face fills the same
			// vertical panel height. Width may over/undershoot naturally;
			// centering below uses the actual measured char_width.
			int refSize = GameFontShaper.widthFitTextSize(getContext(),
					fitCols, maxWidth, MIN_FONT_SIZE, MAX_FONT_SIZE);
			float matched = GameFontShaper.matchReferenceLineHeight(
					getContext(), fore.getTypeface(), refSize);
			font_text_size = Math.round(matched);

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

		// Height self-fit for tall-aspect screens (Galaxy Fold class):
		// shrink font until canvas fits the AT_MOST ceiling. Skip when
		// vscroll (overflow is intentional) or anchorToContent (reported
		// height tracks content, not bitmap).
		int heightMode = MeasureSpec.getMode(heightMeasureSpec);
		int heightLimit = MeasureSpec.getSize(heightMeasureSpec);
		if (!verticalScrollEnabled && !anchorToContent
				&& heightMode == MeasureSpec.AT_MOST && heightLimit > 0
				&& regionRows > 0 && canvas_height > heightLimit)
		{
			while (font_text_size > MIN_FONT_SIZE
					&& canvas_height > heightLimit)
			{
				setFontSize(font_text_size - 1, false);
				computeCanvasSize();
			}
		}

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
		if (anchorToContent && char_height > 0 && maxContentRow >= 0)
		{
			int cap = (maxContentRow + 2) * char_height;
			if (cap < reportedHeight)
				reportedHeight = cap;
		}
		if (verticalScrollEnabled)
		{
			int parentLimit = MeasureSpec.getSize(heightMeasureSpec);
			int mode = MeasureSpec.getMode(heightMeasureSpec);
			if (mode != MeasureSpec.UNSPECIFIED && parentLimit > 0
					&& canvas_height > parentLimit)
			{
				reportedHeight = parentLimit;
			}
			// Fixed visual slot: applied after the parent-limit clamp so a
			// large parent allocation can't raise the height back above the
			// row cap.
			if (maxVisibleRows > 0 && char_height > 0)
			{
				int cap = maxVisibleRows * char_height;
				if (cap < reportedHeight)
					reportedHeight = cap;
			}
			// Re-clamp existing offset against the new viewport size.
			int contentH = maxContentRow >= 0
					? (int)((maxContentRow + 1) * char_height)
					: canvas_height;
			int maxY = Math.max(0, contentH - reportedHeight);
			if (scrollOffsetY > maxY)
				scrollOffsetY = maxY;
		}

		// Vertical center: when the parent gives us a real height allocation
		// (AT_MOST/EXACTLY) larger than canvas_height, fill the allocation and
		// offset the bitmap down so content sits centered. Skip for scrollable
		// or content-anchored panels since those manage height themselves.
		drawOffsetY = 0;
		if (centerVertically && !verticalScrollEnabled && !anchorToContent)
		{
			int mode = MeasureSpec.getMode(heightMeasureSpec);
			int limit = MeasureSpec.getSize(heightMeasureSpec);
			if (mode != MeasureSpec.UNSPECIFIED && limit > canvas_height)
			{
				drawOffsetY = (limit - canvas_height) / 2;
				reportedHeight = limit;
			}
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

	// Paint the bitmap black without wiping the mirror. The next
	// onMeasure/repaintAllFromMirrorLocked redraws the mirror content at
	// the (possibly new) font scale, so the correct picture is restored
	// as soon as the layout pass completes. Use this to hide stale-scale
	// pixels during an INVISIBLE→VISIBLE transition where the font scale
	// is about to change.
	/** @deprecated Unused. */
	@Deprecated
	public void clearBitmap()
	{
		synchronized (renderLock)
		{
			if (canvas != null)
			{
				back.setColor(Color.BLACK);
				canvas.drawPaint(back);
			}
		}
	}
}
