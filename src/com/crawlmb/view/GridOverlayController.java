package com.crawlmb.view;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
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
import java.util.List;
import java.util.Locale;

// "Reposition Grid Overlay" mode: a blank full-screen editor for the 9-grid
// touch zone dividers (see Preferences.getGridLines). Edits a working copy;
// SAVE hands it to the activity to persist — nothing layout-visible changes,
// so no view rebuild is needed.
//
// UNFOLDED (foldable): the display shows two halves and the 9-grid is applied
// per half, so the editor draws TWO independent grids — one per physical half,
// each editing its own crawl.gridlines.<side> config (setUnfolded). Each grid
// is laid out over its half's real touch region: the keyboard-side half is
// shortened by the keyboard reserve, so its dividers line up with where taps
// actually map, and swapping keyboard side re-fits automatically.
public class GridOverlayController
{
	public interface Callbacks
	{
		// Invoked after teardown with the chosen {v1, v2, h1, h2}
		// (single-screen / HALF).
		void onSave(float[] lines);

		// Invoked after teardown with each physical half's chosen config
		// (UNFOLDED). Default no-op so single-screen callers need not implement.
		default void onSaveUnfolded(float[] left, float[] right) {}

		// Invoked after teardown on any non-save exit (EXIT button, BACK,
		// activity stop). restoreIme = false when the activity is stopping.
		void onExit(boolean restoreIme);
	}

	private static final int BAR_HEIGHT_DP = 56;
	private static final int GRAB_RADIUS_DP = 24;
	private static final int BACKGROUND_COLOR = 0xFF000000;
	private static final int LINE_ALPHA = 0x80; // 50%
	// Magnetic snap: pull onto a snap-grid multiple when within this
	// fraction of a step — light enough that free positions stay reachable.
	private static final float SNAP_THRESHOLD =
			Preferences.GRID_LINE_SNAP_STEP * 0.35f;

	private final Activity activity;
	private final RelativeLayout screenLayout;
	private final View keyboardView; // null unless the Crawl keyboard is shown
	private final int bottomInset;
	private final int highlightColor;
	private final Callbacks callbacks;
	private final float density;

	// UNFOLDED config (null => single-screen / HALF, one full-screen grid).
	// Per physical half [left, right]: window-x start, width, bottom reserve
	// (keyboard-side half shortened), and the pref side key it edits.
	private int[] unfoldedStarts = null;
	private int[] unfoldedWidths = null;
	private int[] unfoldedReserves = null;
	private String[] unfoldedSides = null;

	private FrameLayout gridRoot;
	private final List<EditorView> editors = new ArrayList<>();
	private boolean active = false;

	public GridOverlayController(Activity activity,
			RelativeLayout screenLayout, View keyboardView, int bottomInset,
			int highlightColor, Callbacks callbacks)
	{
		this.activity = activity;
		this.screenLayout = screenLayout;
		this.keyboardView = keyboardView;
		this.bottomInset = bottomInset;
		this.highlightColor = highlightColor;
		this.callbacks = callbacks;
		this.density = activity.getResources().getDisplayMetrics().density;
	}

	// Switch to the two-grid UNFOLDED editor. Call before enter(). Arrays are
	// parallel per physical half; reserves already account for the keyboard
	// (keyboard-side half = keyboard height, others 0; Both keyboard = both).
	public void setUnfolded(int[] starts, int[] widths, int[] reserves,
			String[] sides)
	{
		this.unfoldedStarts = starts;
		this.unfoldedWidths = widths;
		this.unfoldedReserves = reserves;
		this.unfoldedSides = sides;
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
		hideSystemIme();
		buildUi();
	}

	public void exit(boolean restoreIme)
	{
		if (!active)
			return;
		teardown();
		callbacks.onExit(restoreIme);
	}

	private void save()
	{
		if (unfoldedStarts != null)
		{
			float[] left = linesForSide(Preferences.SIDE_LEFT);
			float[] right = linesForSide(Preferences.SIDE_RIGHT);
			teardown();
			callbacks.onSaveUnfolded(left, right);
		}
		else
		{
			float[] result = editors.get(0).lines.clone();
			teardown();
			callbacks.onSave(result);
		}
	}

	private float[] linesForSide(String side)
	{
		for (int i = 0; i < editors.size(); i++)
			if (unfoldedSides != null && side.equals(unfoldedSides[i]))
				return editors.get(i).lines.clone();
		return Preferences.GRID_LINE_DEFAULTS.clone();
	}

	private void reset()
	{
		for (EditorView ev : editors)
		{
			System.arraycopy(Preferences.GRID_LINE_DEFAULTS, 0, ev.lines, 0,
					ev.lines.length);
			ev.resetDrag();
			ev.invalidate();
		}
	}

	private void teardown()
	{
		active = false;
		if (gridRoot != null && gridRoot.getParent() instanceof ViewGroup)
			((ViewGroup) gridRoot.getParent()).removeView(gridRoot);
	}

	private void buildUi()
	{
		// Opaque blank screen; clickable + focusable so anything the editors
		// and bar don't handle is still swallowed (reposition precedent).
		gridRoot = new FrameLayout(activity);
		gridRoot.setBackgroundColor(BACKGROUND_COLOR);
		gridRoot.setClickable(true);
		gridRoot.setFocusable(true);

		// Bar sizing mirrors RepositionController.buildUi: cover the Crawl
		// keyboard exactly, or a compact strip when no keyboard is shown.
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

		if (unfoldedStarts != null)
			addUnfoldedEditors();
		else
			addSingleEditor(barHeight);

		addBar(barHeight, barBottomPad);

		screenLayout.addView(gridRoot, new RelativeLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
	}

	// Single full-screen editor above the bar — line fractions map 1:1 to the
	// in-game 9-grid touch region.
	private void addSingleEditor(int barHeight)
	{
		EditorView ev = new EditorView(activity,
				Preferences.getGridLines().clone());
		ev.setHapticFeedbackEnabled(Preferences.getHapticFeedbackEnabled());
		FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT);
		p.bottomMargin = barHeight;
		gridRoot.addView(ev, p);
		editors.add(ev);
	}

	// One editor per physical half, positioned over that half's real touch
	// region: window-x band × (full height − keyboard reserve). The dividers
	// therefore correspond to where taps map on each half.
	private void addUnfoldedEditors()
	{
		boolean haptic = Preferences.getHapticFeedbackEnabled();
		for (int i = 0; i < unfoldedStarts.length; i++)
		{
			EditorView ev = new EditorView(activity,
					Preferences.getGridLines(unfoldedSides[i]).clone());
			ev.setHapticFeedbackEnabled(haptic);
			FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
					unfoldedWidths[i], ViewGroup.LayoutParams.MATCH_PARENT);
			p.leftMargin = unfoldedStarts[i];
			p.bottomMargin = unfoldedReserves[i];
			gridRoot.addView(ev, p);
			editors.add(ev);
		}
	}

	private void addBar(int barHeight, int barBottomPad)
	{
		View bar = activity.getLayoutInflater().inflate(
				R.layout.reposition_buttons, gridRoot, false);
		bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop(),
				bar.getPaddingRight(), barBottomPad);
		bar.setClickable(true);
		int[] span = barHorizontalSpan();
		FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
				span == null ? ViewGroup.LayoutParams.MATCH_PARENT : span[1],
				barHeight, Gravity.BOTTOM);
		if (span != null)
			p.leftMargin = span[0];
		gridRoot.addView(bar, p);

		bar.findViewById(R.id.reposition_save)
				.setOnClickListener(v -> save());
		bar.findViewById(R.id.reposition_reset)
				.setOnClickListener(v -> reset());
		bar.findViewById(R.id.reposition_cancel)
				.setOnClickListener(v -> exit(true));
	}

	// Bar horizontal extent: null = full width. UNFOLDED Left/Right (exactly
	// one half reserved) puts the bar over the keyboard footprint (that half)
	// so the other half's grid stays full height; Both / no-keyboard = full.
	private int[] barHorizontalSpan()
	{
		if (unfoldedReserves == null)
			return null;
		int reservedCount = 0;
		int reservedIdx = -1;
		for (int i = 0; i < unfoldedReserves.length; i++)
			if (unfoldedReserves[i] > 0)
			{
				reservedCount++;
				reservedIdx = i;
			}
		if (reservedCount == 1)
			return new int[] { unfoldedStarts[reservedIdx],
					unfoldedWidths[reservedIdx] };
		return null;
	}

	// The overlay can't cover the system soft keyboard (separate window), so
	// suppress it explicitly — same calls as RepositionController.
	private void hideSystemIme()
	{
		activity.getWindow().setSoftInputMode(
				WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
		InputMethodManager imm = (InputMethodManager)
				activity.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.hideSoftInputFromWindow(screenLayout.getWindowToken(), 0);
	}

	private static float snap(float frac)
	{
		float step = Preferences.GRID_LINE_SNAP_STEP;
		float nearest = Math.round(frac / step) * step;
		return Math.abs(frac - nearest) < SNAP_THRESHOLD ? nearest : frac;
	}

	// Draws one half's four divider lines and owns that grid's touch handling.
	// Each instance holds its own working {v1,v2,h1,h2} and drag state, so the
	// two UNFOLDED grids edit independently.
	private class EditorView extends View
	{
		private static final float LABEL_MARGIN_DP = 4;

		// Working divider fractions {v1, v2, h1, h2} being edited (mutated in
		// place; reset() overwrites, save() reads).
		final float[] lines;

		// Drag state: indices into lines[], -1 = not dragging that axis. An
		// intersection grab sets both.
		private int dragV = -1;
		private int dragH = -1;
		private int trackedPointerId = -1;
		private float grabOffsetX = 0;
		private float grabOffsetY = 0;

		private final Paint linePaint = new Paint();
		private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

		EditorView(Context context, float[] lines)
		{
			super(context);
			this.lines = lines;
			linePaint.setStyle(Paint.Style.STROKE);
			linePaint.setStrokeWidth(Math.max(4, Math.round(2 * density)));
			linePaint.setColor(
					(highlightColor & 0x00FFFFFF) | (LINE_ALPHA << 24));
			// Additive blend over the black background: each line adds its
			// 50%, so coincident lines stack to full opacity instead of the
			// 75% that normal alpha compositing would give.
			linePaint.setXfermode(
					new PorterDuffXfermode(PorterDuff.Mode.ADD));
			labelPaint.setColor(highlightColor);
			labelPaint.setTextSize(TypedValue.applyDimension(
					TypedValue.COMPLEX_UNIT_SP, 12,
					context.getResources().getDisplayMetrics()));
		}

		void resetDrag()
		{
			dragV = -1;
			dragH = -1;
			trackedPointerId = -1;
		}

		@Override
		protected void onDraw(Canvas canvas)
		{
			super.onDraw(canvas);
			float w = getWidth();
			float h = getHeight();
			canvas.drawLine(lines[0] * w, 0, lines[0] * w, h, linePaint);
			canvas.drawLine(lines[1] * w, 0, lines[1] * w, h, linePaint);
			canvas.drawLine(0, lines[2] * h, w, lines[2] * h, linePaint);
			canvas.drawLine(0, lines[3] * h, w, lines[3] * h, linePaint);
			// Position-% labels. Each pair takes opposite sides (v1 left /
			// v2 right, h1 above / h2 below) so coincident lines stay
			// readable, and the second of each pair shows its distance from
			// the far edge — equal numbers mean a symmetric layout.
			drawVLabel(canvas, lines[0], true, w);
			drawVLabel(canvas, lines[1], false, w);
			drawHLabel(canvas, lines[2], true, h);
			drawHLabel(canvas, lines[3], false, h);
		}

		private String percentText(float frac)
		{
			return String.format(Locale.US, "%d%%", Math.round(frac * 100));
		}

		// Label at the top of a vertical line, beside it on preferLeft's
		// side unless that would clip the editor edge.
		private void drawVLabel(Canvas canvas, float frac, boolean preferLeft,
				float w)
		{
			String text = percentText(preferLeft ? frac : 1f - frac);
			float margin = LABEL_MARGIN_DP * density;
			float x = frac * w;
			float tw = labelPaint.measureText(text);
			float lx = preferLeft ? x - margin - tw : x + margin;
			if (lx < margin)
				lx = x + margin;
			else if (lx + tw > w - margin)
				lx = x - margin - tw;
			canvas.drawText(text, lx, margin - labelPaint.ascent(),
					labelPaint);
		}

		// Label at the left of a horizontal line, above or below it per
		// preferAbove unless that would clip the editor edge.
		private void drawHLabel(Canvas canvas, float frac, boolean preferAbove,
				float h)
		{
			String text = percentText(preferAbove ? frac : 1f - frac);
			float margin = LABEL_MARGIN_DP * density;
			float y = frac * h;
			float above = y - margin - labelPaint.descent();
			float below = y + margin - labelPaint.ascent();
			float baseline = preferAbove ? above : below;
			if (baseline + labelPaint.ascent() < margin)
				baseline = below;
			else if (baseline + labelPaint.descent() > h - margin)
				baseline = above;
			canvas.drawText(text, margin, baseline, labelPaint);
		}

		private void onTouchDown(MotionEvent event)
		{
			if (dragV >= 0 || dragH >= 0)
				return;
			float x = event.getX();
			float y = event.getY();
			float w = getWidth();
			float h = getHeight();
			if (w <= 0 || h <= 0)
				return;
			float grabRadius = GRAB_RADIUS_DP * density;

			// Within the grab radius on both axes = intersection grab; both
			// lines follow the finger.
			int nearV = nearestLine(x / w, 0);
			int nearH = nearestLine(y / h, 2);
			boolean vHit = Math.abs(lines[nearV] * w - x) <= grabRadius;
			boolean hHit = Math.abs(lines[nearH] * h - y) <= grabRadius;
			if (!vHit && !hHit)
				return;
			dragV = vHit ? nearV : -1;
			dragH = hHit ? nearH : -1;
			grabOffsetX = vHit ? x - lines[nearV] * w : 0;
			grabOffsetY = hHit ? y - lines[nearH] * h : 0;
			trackedPointerId = event.getPointerId(0);
			performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
		}

		// Index into lines[] of the line nearest frac on one axis; base = 0 for
		// the vertical pair, 2 for the horizontal.
		private int nearestLine(float frac, int base)
		{
			float d0 = Math.abs(frac - lines[base]);
			float d1 = Math.abs(frac - lines[base + 1]);
			if (d0 != d1)
				return d0 < d1 ? base : base + 1;
			// Equidistant — typically a collapsed (overlapping) pair. Pick by
			// side so the pair can be pulled apart in either direction: grabbing
			// from the left/top takes the lower line, right/bottom the upper.
			return frac < lines[base] ? base : base + 1;
		}

		private void onTouchMove(MotionEvent event)
		{
			if (dragV < 0 && dragH < 0)
				return;
			int idx = event.findPointerIndex(trackedPointerId);
			if (idx < 0)
				return;
			if (dragV >= 0)
				lines[dragV] = constrain(
						snap((event.getX(idx) - grabOffsetX) / getWidth()),
						dragV);
			if (dragH >= 0)
				lines[dragH] = constrain(
						snap((event.getY(idx) - grabOffsetY) / getHeight()),
						dragH);
			invalidate();
		}

		private void onTouchEnd()
		{
			dragV = -1;
			dragH = -1;
			trackedPointerId = -1;
		}

		// Clamp to the edge padding and to the partner line on the same axis —
		// lines may meet (equality collapses the middle band) but never cross.
		private float constrain(float frac, int index)
		{
			float lo = Preferences.GRID_LINE_PADDING;
			float hi = 1f - Preferences.GRID_LINE_PADDING;
			// Even indices (v1/h1) are bounded above by their partner; odd
			// (v2/h2) below.
			if ((index & 1) == 0)
				hi = Math.min(hi, lines[index + 1]);
			else
				lo = Math.max(lo, lines[index - 1]);
			return Math.max(lo, Math.min(hi, frac));
		}

		@Override
		public boolean onTouchEvent(MotionEvent event)
		{
			switch (event.getActionMasked())
			{
			case MotionEvent.ACTION_DOWN:
				onTouchDown(event);
				break;
			case MotionEvent.ACTION_MOVE:
				onTouchMove(event);
				break;
			case MotionEvent.ACTION_POINTER_UP:
				if (event.getPointerId(event.getActionIndex())
						== trackedPointerId)
					onTouchEnd();
				break;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				onTouchEnd();
				break;
			}
			return true;
		}
	}
}
