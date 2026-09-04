package com.crawlmb.view;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.crawlmb.Preferences;
import com.crawlmb.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Unfolded "Reposition In-Game UI": drag the map side-to-side to swap halves,
// drag panels vertically to reorder the stack. Drawn as schematic rectangles
// (the horizontal LinearLayout can't be translated like the single-screen one).
public class UnfoldedRepositionController
{
	public interface Callbacks
	{
		void onSave(String mapSide, String[] panelOrder);
		void onCancel(boolean restoreIme);
	}

	private static final int BAR_HEIGHT_DP = 56;
	private static final int BACKGROUND_COLOR = 0xFF000000;
	private static final int BORDER_COLOR = 0x80FFFFFF;
	private static final int GAP_COLOR = 0xFF333333;
	private static final float SWAP_THRESHOLD = 0.30f; // fraction of half-width

	private final Activity activity;
	private final RelativeLayout screenLayout;
	private final View keyboardView;
	private final int bottomInset;
	private final int highlightColor;
	private final Callbacks callbacks;
	private final float density;

	private final int leftStart;
	private final int leftWidth;
	private final int rightStart;
	private final int rightWidth;

	private FrameLayout root;
	private SchematicView schematicView;
	private boolean active = false;

	private boolean mapOnLeft;
	private final List<String> panelOrder = new ArrayList<>();
	private int trackedPointerId = -1;
	private float downX, downY;
	private boolean draggingMap = false;
	private int draggedPanelIdx = -1;
	private float mapDragOffsetX = 0;
	private float draggedPanelOffsetY = 0;
	private final String labelMap;
	private final String labelHud;
	private final String labelMlist;
	private final String labelMsg;

	public UnfoldedRepositionController(Activity activity,
			RelativeLayout screenLayout, View keyboardView, int bottomInset,
			int highlightColor,
			int leftStart, int leftWidth, int rightStart, int rightWidth,
			Callbacks callbacks)
	{
		this.activity = activity;
		this.screenLayout = screenLayout;
		this.keyboardView = keyboardView;
		this.bottomInset = bottomInset;
		this.highlightColor = highlightColor;
		this.callbacks = callbacks;
		this.density = activity.getResources().getDisplayMetrics().density;
		this.leftStart = leftStart;
		this.leftWidth = leftWidth;
		this.rightStart = rightStart;
		this.rightWidth = rightWidth;
		labelMap = activity.getString(R.string.reposition_panel_map);
		labelHud = activity.getString(R.string.reposition_panel_hud);
		labelMlist = activity.getString(R.string.reposition_panel_mlist);
		labelMsg = activity.getString(R.string.reposition_panel_msg);
	}

	public boolean isActive()
	{
		return active;
	}

	public void enter()
	{
		if (active)
			return;
		active = true;
		mapOnLeft = Preferences.getUnfoldedMapSide()
				.equals(Preferences.SIDE_LEFT);
		panelOrder.clear();
		Collections.addAll(panelOrder, Preferences.getUnfoldedPanelOrder());
		hideSystemIme();
		buildUi();
	}

	public void cancel(boolean restoreIme)
	{
		if (!active)
			return;
		teardown();
		callbacks.onCancel(restoreIme);
	}

	private void save()
	{
		String mapSide = mapOnLeft ? Preferences.SIDE_LEFT
				: Preferences.SIDE_RIGHT;
		String[] order = panelOrder.toArray(new String[0]);
		teardown();
		callbacks.onSave(mapSide, order);
	}

	private void reset()
	{
		endDrag();
		mapOnLeft = true; // default: map on left
		panelOrder.clear();
		Collections.addAll(panelOrder, Preferences.UNFOLDED_PANEL_KEYS);
		if (schematicView != null)
			schematicView.invalidate();
	}

	private void teardown()
	{
		active = false;
		endDrag();
		if (root != null && root.getParent() instanceof ViewGroup)
			((ViewGroup) root.getParent()).removeView(root);
	}

	private void buildUi()
	{
		root = new FrameLayout(activity);
		root.setBackgroundColor(BACKGROUND_COLOR);
		root.setClickable(true);
		root.setFocusable(true);

		int barHeight;
		int barBottomPad;
		if (keyboardView != null && keyboardView.getHeight() > 0)
		{
			barHeight = keyboardView.getHeight();
			barBottomPad = keyboardView.getPaddingBottom();
		}
		else
		{
			barBottomPad = bottomInset;
			barHeight = (int) (BAR_HEIGHT_DP * density) + barBottomPad;
		}

		schematicView = new SchematicView(activity, barHeight);
		schematicView.setHapticFeedbackEnabled(
				Preferences.getHapticFeedbackEnabled());
		root.addView(schematicView, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));

		View bar = activity.getLayoutInflater().inflate(
				R.layout.reposition_buttons, root, false);
		bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop(),
				bar.getPaddingRight(), barBottomPad);
		bar.setClickable(true);
		root.addView(bar, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, barHeight,
				Gravity.BOTTOM));

		bar.findViewById(R.id.reposition_save)
				.setOnClickListener(v -> save());
		bar.findViewById(R.id.reposition_reset)
				.setOnClickListener(v -> reset());
		bar.findViewById(R.id.reposition_cancel)
				.setOnClickListener(v -> cancel(true));

		screenLayout.addView(root, new RelativeLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
	}

	private void hideSystemIme()
	{
		activity.getWindow().setSoftInputMode(
				WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
		InputMethodManager imm = (InputMethodManager)
				activity.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.hideSoftInputFromWindow(screenLayout.getWindowToken(), 0);
	}

	private void endDrag()
	{
		trackedPointerId = -1;
		draggingMap = false;
		draggedPanelIdx = -1;
		mapDragOffsetX = 0;
		draggedPanelOffsetY = 0;
	}

	private String labelFor(String key)
	{
		switch (key)
		{
		case "hud": return labelHud;
		case "mlist": return labelMlist;
		default: return labelMsg;
		}
	}

	private class SchematicView extends View
	{
		private static final float INSET_FRAC = 0.06f;
		private static final float HINGE_GAP_DP = 4;

		private final Paint fillPaint = new Paint();
		private final Paint borderPaint = new Paint();
		private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint gapPaint = new Paint();
		private final int barHeight;

		SchematicView(Context context, int barHeight)
		{
			super(context);
			this.barHeight = barHeight;
			fillPaint.setStyle(Paint.Style.FILL);
			borderPaint.setStyle(Paint.Style.STROKE);
			borderPaint.setStrokeWidth(Math.max(4, Math.round(2 * density)));
			borderPaint.setColor(BORDER_COLOR);
			labelPaint.setColor(0xFFFFFFFF);
			labelPaint.setTextAlign(Paint.Align.CENTER);
			labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
			labelPaint.setTextSize(TypedValue.applyDimension(
					TypedValue.COMPLEX_UNIT_SP, 18,
					context.getResources().getDisplayMetrics()));
			labelPaint.setShadowLayer(4, 0, 0, 0xFF000000);
			gapPaint.setStyle(Paint.Style.FILL);
			gapPaint.setColor(GAP_COLOR);
		}

		@Override
		protected void onDraw(Canvas canvas)
		{
			super.onDraw(canvas);
			int w = getWidth();
			int h = getHeight();
			if (w <= 0 || h <= 0)
				return;

			float availH = h - barHeight;
			float insetX = w * INSET_FRAC;
			float insetY = availH * INSET_FRAC;
			float drawLeft = insetX;
			float drawWidth = w - 2 * insetX;
			float drawTop = insetY;
			float drawHeight = availH - 2 * insetY;
			if (drawWidth <= 0 || drawHeight <= 0)
				return;

			int totalWindow = rightStart + rightWidth;
			if (totalWindow <= 0) totalWindow = 1;

			float hingeGap = HINGE_GAP_DP * density;
			float gapL = drawLeft + (leftWidth / (float) totalWindow) * drawWidth;
			float gapR = drawLeft + (rightStart / (float) totalWindow) * drawWidth;
			if (gapR - gapL < hingeGap)
			{
				float mid = (gapL + gapR) / 2f;
				gapL = mid - hingeGap / 2f;
				gapR = mid + hingeGap / 2f;
			}
			canvas.drawRect(gapL, drawTop, gapR, drawTop + drawHeight, gapPaint);

			float lhL = drawLeft;
			float lhR = gapL;
			float rhL = gapR;
			float rhR = drawLeft + drawWidth;

			float mapRectL, mapRectR, panelRectL, panelRectR;
			if (mapOnLeft)
			{
				mapRectL = lhL; mapRectR = lhR;
				panelRectL = rhL; panelRectR = rhR;
			}
			else
			{
				mapRectL = rhL; mapRectR = rhR;
				panelRectL = lhL; panelRectR = lhR;
			}

			float mapOff = draggingMap ? mapDragOffsetX : 0;
			fillPaint.setColor(highlightColor & 0x33FFFFFF);
			RectF mapRect = new RectF(mapRectL + mapOff, drawTop,
					mapRectR + mapOff, drawTop + drawHeight);
			canvas.drawRect(mapRect, fillPaint);
			borderPaint.setColor(draggingMap ? highlightColor : BORDER_COLOR);
			drawBorder(canvas, mapRect);
			borderPaint.setColor(BORDER_COLOR);
			drawLabel(canvas, labelMap, mapRect);

			int panelCount = Math.max(1, panelOrder.size());
			float slotH = drawHeight / panelCount;
			for (int i = 0; i < panelOrder.size(); i++)
			{
				float top = drawTop + i * slotH;
				float off = (i == draggedPanelIdx) ? draggedPanelOffsetY : 0;
				boolean isDragged = (i == draggedPanelIdx);
				fillPaint.setColor(isDragged
						? (highlightColor & 0x33FFFFFF) : 0x11FFFFFF);
				RectF r = new RectF(panelRectL, top + off,
						panelRectR, top + slotH + off);
				canvas.drawRect(r, fillPaint);
				borderPaint.setColor(isDragged ? highlightColor : BORDER_COLOR);
				drawBorder(canvas, r);
				borderPaint.setColor(BORDER_COLOR);
				drawLabel(canvas, labelFor(panelOrder.get(i)), r);
			}
		}

		private void drawBorder(Canvas canvas, RectF r)
		{
			float inset = borderPaint.getStrokeWidth() / 2f;
			canvas.drawRect(r.left + inset, r.top + inset,
					r.right - inset, r.bottom - inset, borderPaint);
		}

		private void drawLabel(Canvas canvas, String text, RectF r)
		{
			float cx = r.centerX();
			float cy = r.centerY()
					- (labelPaint.ascent() + labelPaint.descent()) / 2f;
			canvas.drawText(text, cx, cy, labelPaint);
		}

		// Returns panelOrder index, or -1 for map/outside.
		private int hitPanel(float x, float y)
		{
			int hw = getWidth();
			int hh = getHeight();
			float availH = hh - barHeight;
			float insetX = hw * INSET_FRAC;
			float insetY = availH * INSET_FRAC;
			float drawLeft = insetX;
			float drawWidth = hw - 2 * insetX;
			float drawTop = insetY;
			float drawHeight = availH - 2 * insetY;

			int totalWindow = rightStart + rightWidth;
			if (totalWindow <= 0) totalWindow = 1;
			float hingeGap = HINGE_GAP_DP * density;
			float gapL = drawLeft + (leftWidth / (float) totalWindow) * drawWidth;
			float gapR = drawLeft + (rightStart / (float) totalWindow) * drawWidth;
			if (gapR - gapL < hingeGap)
			{
				float mid = (gapL + gapR) / 2f;
				gapL = mid - hingeGap / 2f;
				gapR = mid + hingeGap / 2f;
			}
			float lhL = drawLeft, lhR = gapL;
			float rhL = gapR, rhR = drawLeft + drawWidth;

			float panelL, panelR;
			if (mapOnLeft)
			{ panelL = rhL; panelR = rhR; }
			else
			{ panelL = lhL; panelR = lhR; }

			if (x < panelL || x > panelR || y < drawTop
					|| y > drawTop + drawHeight)
				return -1;

			int count = panelOrder.size();
			if (count <= 0) return -1;
			float slotH = drawHeight / count;
			int idx = (int) ((y - drawTop) / slotH);
			return Math.max(0, Math.min(idx, count - 1));
		}

		private float panelDrawHeight()
		{
			int hh = getHeight();
			float availH = hh - barHeight;
			float insetY = availH * INSET_FRAC;
			return availH - 2 * insetY;
		}

		@Override
		public boolean onTouchEvent(MotionEvent event)
		{
			switch (event.getActionMasked())
			{
			case MotionEvent.ACTION_DOWN:
				onDown(event);
				break;
			case MotionEvent.ACTION_MOVE:
				onMove(event);
				break;
			case MotionEvent.ACTION_POINTER_UP:
				if (event.getPointerId(event.getActionIndex())
						== trackedPointerId)
					onUp();
				break;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				onUp();
				break;
			}
			return true;
		}

		private void onDown(MotionEvent event)
		{
			if (trackedPointerId >= 0)
				return;
			float x = event.getX();
			float y = event.getY();
			trackedPointerId = event.getPointerId(0);
			downX = x;
			downY = y;
			int panelIdx = hitPanel(x, y);
			if (panelIdx < 0)
			{
				draggingMap = true;
				mapDragOffsetX = 0;
			}
			else
			{
				draggedPanelIdx = panelIdx;
				draggedPanelOffsetY = 0;
			}
			performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
			invalidate();
		}

		private void onMove(MotionEvent event)
		{
			int idx = event.findPointerIndex(trackedPointerId);
			if (idx < 0)
				return;
			float x = event.getX(idx);
			float y = event.getY(idx);

			if (draggingMap)
			{
				mapDragOffsetX = x - downX;
				invalidate();
			}
			else if (draggedPanelIdx >= 0)
			{
				draggedPanelOffsetY = y - downY;
				float slotH = panelDrawHeight() / panelOrder.size();
				float slotsOffset = draggedPanelOffsetY / slotH;
				if (slotsOffset > 0.5f
						&& draggedPanelIdx < panelOrder.size() - 1)
				{
					Collections.swap(panelOrder, draggedPanelIdx,
							draggedPanelIdx + 1);
					draggedPanelIdx++;
					downY += slotH;
					draggedPanelOffsetY -= slotH;
					performHapticFeedback(
							HapticFeedbackConstants.VIRTUAL_KEY);
				}
				else if (slotsOffset < -0.5f && draggedPanelIdx > 0)
				{
					Collections.swap(panelOrder, draggedPanelIdx,
							draggedPanelIdx - 1);
					draggedPanelIdx--;
					downY -= slotH;
					draggedPanelOffsetY += slotH;
					performHapticFeedback(
							HapticFeedbackConstants.VIRTUAL_KEY);
				}
				invalidate();
			}
		}

		private void onUp()
		{
			if (draggingMap)
			{
				float halfW = (leftWidth + rightWidth) / 2f;
				float threshold = halfW * SWAP_THRESHOLD;
				boolean swap;
				if (mapOnLeft)
					swap = mapDragOffsetX > threshold;
				else
					swap = mapDragOffsetX < -threshold;
				if (swap)
					mapOnLeft = !mapOnLeft;
			}
			endDrag();
			if (schematicView != null)
				schematicView.invalidate();
		}
	}
}
