package com.crawlmb.view;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.crawlmb.Preferences;
import com.crawlmb.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// "Customize Grid Overlay": edit the 9-grid touch zone dividers. Single-
// screen shows one editor; fold modes (setHalves) show one per half, each
// editing its own config, sized to that half's real touch region. The Float
// toggle switches every editor between the docked grid (fills the region) and
// a floating box with its own dividers; the two are saved independently.
public class GridOverlayController
{
	public interface Callbacks
	{
		// Per editor: its config side (null = single-screen/HALF), lines and,
		// when floating, its box (+ the shared opacity). Only the shown mode
		// is saved; it also becomes the live mode in-game.
		void onSave(String[] sides, float[][] lines, float[][] rects,
				boolean floating, int opacity);
		void onExit(boolean restoreIme);
	}

	private static final int BAR_HEIGHT_DP = 56;
	private static final int GRAB_RADIUS_DP = 24;
	private static final int HANDLE_DP = 20;
	private static final int HANDLE_HIT_DP = 48;
	private static final int MOVE_ICON_DP = 24;
	private static final long BAR_FADE_MS = 150;
	private static final int BACKGROUND_COLOR = 0xFF000000;
	private static final int LINE_ALPHA = 0x80;
	private static final int BOX_FILL_ALPHA = 0x20;
	private static final float SNAP_THRESHOLD =
			Preferences.GRID_LINE_SNAP_STEP * 0.35f;

	// Box drag bits: which edges follow the finger; MOVE = whole box.
	private static final int EDGE_LEFT = 1;
	private static final int EDGE_TOP = 2;
	private static final int EDGE_RIGHT = 4;
	private static final int EDGE_BOTTOM = 8;
	private static final int BOX_MOVE = 16;

	private final Activity activity;
	private final RelativeLayout screenLayout;
	private final View keyboardView;
	private final int bottomInset;
	private final int highlightColor;
	private final Callbacks callbacks;
	private final float density;

	// Fold modes: per-half [left, width] geometry, keyboard reserve and config
	// side. null = one full-width editor.
	private int[] halfStarts = null;
	private int[] halfWidths = null;
	private int[] halfReserves = null;
	private String[] halfSides = null;

	private FrameLayout gridRoot;
	private View bar;
	private Button floatButton;
	private View opacityRow;
	private TextView opacityLabel;
	private SeekBar opacitySeek;
	private int opacity;
	private int barHeight;
	private boolean barHidden = false;
	private boolean floating = false;
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

	// Switch to per-half editors (UNFOLDED: both halves; HALF: the content
	// half). Call before enter(). Arrays are parallel per half; reserves
	// already account for the keyboard under that half.
	public void setHalves(int[] starts, int[] widths, int[] reserves,
			String[] sides)
	{
		this.halfStarts = starts;
		this.halfWidths = widths;
		this.halfReserves = reserves;
		this.halfSides = sides;
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
		// UNFOLDED halves are always saved together, so the left flag speaks
		// for both.
		floating = Preferences.isGridFloat(
				halfSides != null ? halfSides[0] : null);
		opacity = Preferences.getGridOpacity();
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
		int n = editors.size();
		String[] sides = new String[n];
		float[][] lines = new float[n][];
		float[][] rects = floating ? new float[n][] : null;
		for (int i = 0; i < n; i++)
		{
			EditorView ev = editors.get(i);
			sides[i] = ev.side;
			lines[i] = ev.lines().clone();
			if (floating)
				rects[i] = ev.floatRect.clone();
		}
		boolean savedFloating = floating;
		teardown();
		callbacks.onSave(sides, lines, rects, savedFloating, opacity);
	}

	private void reset()
	{
		for (EditorView ev : editors)
			ev.resetCurrentMode();
		if (floating)
			opacitySeek.setProgress(Preferences.GRID_OPACITY_DEFAULT);
		showBar(true);
	}

	private void toggleFloat()
	{
		floating = !floating;
		updateFloatButton();
		for (EditorView ev : editors)
		{
			ev.resetDrag();
			ev.invalidate();
		}
	}

	private void updateFloatButton()
	{
		floatButton.setText(floating ? R.string.reposition_float_on
				: R.string.reposition_float_off);
		opacityRow.setVisibility(floating ? View.VISIBLE : View.GONE);
	}

	private void setOpacity(int percent)
	{
		opacity = percent;
		opacityLabel.setText(activity.getString(R.string.reposition_opacity,
				percent));
		for (EditorView ev : editors)
			ev.invalidate();
	}

	// Fade the bar out while a floating box is dragged over it, so the box can
	// reach the screen bottom when there's no keyboard.
	private void showBar(boolean visible)
	{
		if (bar == null || barHidden == !visible)
			return;
		barHidden = !visible;
		bar.animate().alpha(visible ? 1f : 0f).setDuration(BAR_FADE_MS)
				.start();
		for (EditorView ev : editors)
			ev.invalidate();
	}

	private void teardown()
	{
		active = false;
		if (gridRoot != null && gridRoot.getParent() instanceof ViewGroup)
			((ViewGroup) gridRoot.getParent()).removeView(gridRoot);
	}

	private void buildUi()
	{
		gridRoot = new FrameLayout(activity);
		gridRoot.setBackgroundColor(BACKGROUND_COLOR);
		gridRoot.setClickable(true);
		gridRoot.setFocusable(true);

		boolean hasKeyboard = keyboardView != null
				&& keyboardView.getHeight() > 0;
		int barBottomPad;
		if (hasKeyboard)
		{
			barHeight = keyboardView.getHeight();
			barBottomPad = keyboardView.getPaddingBottom();
		}
		else
		{
			// Float toggle, action buttons, opacity slider.
			barBottomPad = bottomInset;
			barHeight = (int) (3 * BAR_HEIGHT_DP * density) + barBottomPad;
		}

		// Bar first: editors layer above it so a floating box's handles stay
		// grabbable over the bar (other touches there fall through to it).
		addBar(barBottomPad);

		if (halfStarts != null)
			addHalfEditors();
		else
			addSingleEditor(hasKeyboard);

		screenLayout.addView(gridRoot, new RelativeLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
	}

	// The editor spans the in-game touch region: above the keyboard, else the
	// full screen. With no keyboard the docked grid keeps its old area above
	// the bar.
	private void addSingleEditor(boolean hasKeyboard)
	{
		EditorView ev = new EditorView(activity, null,
				hasKeyboard ? 0 : barHeight, hasKeyboard ? 0 : bottomInset);
		ev.setHapticFeedbackEnabled(Preferences.getHapticFeedbackEnabled());
		FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT);
		p.bottomMargin = hasKeyboard ? barHeight : 0;
		gridRoot.addView(ev, p);
		editors.add(ev);
	}

	private void addHalfEditors()
	{
		boolean haptic = Preferences.getHapticFeedbackEnabled();
		for (int i = 0; i < halfStarts.length; i++)
		{
			// A keyboard-reserved half already ends above the nav bar.
			EditorView ev = new EditorView(activity, halfSides[i], 0,
					halfReserves[i] > 0 ? 0 : bottomInset);
			ev.setHapticFeedbackEnabled(haptic);
			FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
					halfWidths[i], ViewGroup.LayoutParams.MATCH_PARENT);
			p.leftMargin = halfStarts[i];
			p.bottomMargin = halfReserves[i];
			gridRoot.addView(ev, p);
			editors.add(ev);
		}
	}

	private void addBar(int barBottomPad)
	{
		bar = activity.getLayoutInflater().inflate(
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

		floatButton = bar.findViewById(R.id.reposition_float);
		floatButton.setVisibility(View.VISIBLE);
		opacityRow = bar.findViewById(R.id.reposition_opacity_row);
		opacityLabel = bar.findViewById(R.id.reposition_opacity_label);
		opacitySeek = bar.findViewById(R.id.reposition_opacity);
		setOpacity(opacity);
		opacitySeek.setProgress(opacity);
		opacitySeek.setOnSeekBarChangeListener(
				new SeekBar.OnSeekBarChangeListener()
				{
					@Override
					public void onProgressChanged(SeekBar sb, int progress,
							boolean fromUser)
					{
						setOpacity(progress);
					}

					@Override
					public void onStartTrackingTouch(SeekBar sb)
					{
					}

					@Override
					public void onStopTrackingTouch(SeekBar sb)
					{
					}
				});
		updateFloatButton();
		floatButton.setOnClickListener(v -> toggleFloat());
		bar.findViewById(R.id.reposition_save)
				.setOnClickListener(v -> save());
		bar.findViewById(R.id.reposition_reset)
				.setOnClickListener(v -> reset());
		bar.findViewById(R.id.reposition_cancel)
				.setOnClickListener(v -> exit(true));
	}

	// null = full width; one reserved half = bar over that half only.
	private int[] barHorizontalSpan()
	{
		if (halfReserves == null)
			return null;
		int reservedCount = 0;
		int reservedIdx = -1;
		for (int i = 0; i < halfReserves.length; i++)
			if (halfReserves[i] > 0)
			{
				reservedCount++;
				reservedIdx = i;
			}
		if (reservedCount == 1)
			return new int[] { halfStarts[reservedIdx],
					halfWidths[reservedIdx] };
		return null;
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

	private static float snap(float frac)
	{
		float step = Preferences.GRID_LINE_SNAP_STEP;
		float nearest = Math.round(frac / step) * step;
		return Math.abs(frac - nearest) < SNAP_THRESHOLD ? nearest : frac;
	}

	private static float clamp(float v, float lo, float hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}

	// One region's grid editor. Holds both the docked and floating configs so
	// toggling keeps unsaved edits to either.
	private class EditorView extends View
	{
		private static final float LABEL_MARGIN_DP = 4;
		final String side;
		// Docked-only bottom band left to the bar (no-keyboard single screen).
		private final int dockedBottomPad;
		// Nav bar band the floating box can't enter. Box fractions stay
		// relative to the full view so they match the in-game touch region.
		private final int floatBottomPad;
		final float[] dockLines; // working {v1, v2, h1, h2}
		final float[] floatLines; // fractions of the box
		final float[] floatRect; // {l, t, r, b} fractions of this view
		private int dragV = -1;
		private int dragH = -1;
		private int boxDrag = 0;
		private int trackedPointerId = -1;
		private float grabOffsetX = 0;
		private float grabOffsetY = 0;
		private final RectF area = new RectF();

		private final Paint linePaint = new Paint();
		private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint borderPaint = new Paint();
		private final Paint fillPaint = new Paint();
		private final Paint iconBgPaint = new Paint();
		private final Drawable handleIcon;
		private final Drawable moveIcon;

		EditorView(Context context, String side, int dockedBottomPad,
				int floatBottomPad)
		{
			super(context);
			this.side = side;
			this.dockedBottomPad = dockedBottomPad;
			this.floatBottomPad = floatBottomPad;
			dockLines = Preferences.getGridLines(side).clone();
			floatLines = Preferences.getGridFloatLines(side).clone();
			floatRect = Preferences.getGridFloatRect(side).clone();
			float stroke = Math.max(4, Math.round(2 * density));
			linePaint.setStyle(Paint.Style.STROKE);
			linePaint.setStrokeWidth(stroke);
			linePaint.setColor(
					(highlightColor & 0x00FFFFFF) | (LINE_ALPHA << 24));
			// Additive: coincident lines stack to full opacity.
			linePaint.setXfermode(
					new PorterDuffXfermode(PorterDuff.Mode.ADD));
			labelPaint.setColor(highlightColor);
			labelPaint.setTextSize(TypedValue.applyDimension(
					TypedValue.COMPLEX_UNIT_SP, 12,
					context.getResources().getDisplayMetrics()));
			borderPaint.setStyle(Paint.Style.STROKE);
			borderPaint.setStrokeWidth(stroke * 1.5f);
			borderPaint.setColor(highlightColor);
			fillPaint.setColor(
					(highlightColor & 0x00FFFFFF) | (BOX_FILL_ALPHA << 24));
			iconBgPaint.setColor(BACKGROUND_COLOR);
			handleIcon = tinted(context, R.drawable.ic_grid_handle);
			moveIcon = tinted(context, R.drawable.ic_grid_move);
		}

		private Drawable tinted(Context context, int resId)
		{
			Drawable d = context.getDrawable(resId).mutate();
			d.setTint(highlightColor);
			return d;
		}

		float[] lines()
		{
			return floating ? floatLines : dockLines;
		}

		void resetDrag()
		{
			dragV = -1;
			dragH = -1;
			boxDrag = 0;
			trackedPointerId = -1;
		}

		void resetCurrentMode()
		{
			System.arraycopy(Preferences.GRID_LINE_DEFAULTS, 0, lines(), 0, 4);
			if (floating)
			{
				System.arraycopy(Preferences.GRID_RECT_DEFAULTS, 0, floatRect,
						0, 4);
				normalizeRect();
			}
			resetDrag();
			invalidate();
		}

		// Box sides can't shrink below this, so the corner, edge-center and
		// move hitboxes never overlap: 2 hitboxes + the inset visual.
		private float minBoxPx()
		{
			return (2 * HANDLE_HIT_DP + HANDLE_DP) * density;
		}

		@Override
		protected void onSizeChanged(int w, int h, int oldw, int oldh)
		{
			super.onSizeChanged(w, h, oldw, oldh);
			normalizeRect();
		}

		// Grow a too-small stored box (region shrank since it was saved) about
		// its center, then shift it back inside the view.
		private void normalizeRect()
		{
			float w = getWidth();
			float h = getHeight();
			if (w <= 0 || h <= 0)
				return;
			normalizeAxis(0, 2, minBoxPx() / w, 1f);
			float maxB = Math.max(0f, (h - floatBottomPad) / h);
			normalizeAxis(1, 3, minBoxPx() / h, maxB);
		}

		private void normalizeAxis(int lo, int hi, float minFrac, float max)
		{
			minFrac = Math.min(minFrac, max);
			if (floatRect[hi] - floatRect[lo] < minFrac)
			{
				float c = (floatRect[lo] + floatRect[hi]) / 2f;
				floatRect[lo] = c - minFrac / 2f;
				floatRect[hi] = c + minFrac / 2f;
			}
			if (floatRect[lo] < 0f)
			{
				floatRect[hi] -= floatRect[lo];
				floatRect[lo] = 0f;
			}
			if (floatRect[hi] > max)
			{
				floatRect[lo] -= floatRect[hi] - max;
				floatRect[hi] = max;
			}
		}

		// Grid area in view px: the floating box, or the docked region.
		private void computeArea()
		{
			float w = getWidth();
			float h = getHeight();
			if (floating)
				area.set(floatRect[0] * w, floatRect[1] * h,
						floatRect[2] * w, floatRect[3] * h);
			else
				area.set(0, 0, w, h - dockedBottomPad);
		}

		// View y where the visible bar starts (bar and editors are gridRoot
		// siblings), or the view height when it doesn't overlap.
		private float barTop()
		{
			if (barHidden || bar.getLeft() >= getRight()
					|| bar.getRight() <= getLeft())
				return getHeight();
			return Math.min(getHeight(), bar.getTop() - getTop());
		}

		// Handle visual centers per axis, inset so they stay on-screen when the
		// box touches the screen edge: {near edge, middle, far edge}.
		private float[] handleCenters(float lo, float hi)
		{
			float half = HANDLE_DP * density / 2f;
			return new float[] { lo + half, (lo + hi) / 2f, hi - half };
		}

		@Override
		protected void onDraw(Canvas canvas)
		{
			super.onDraw(canvas);
			computeArea();
			float aw = area.width();
			float ah = area.height();
			if (aw <= 0 || ah <= 0)
				return;
			float[] lines = lines();
			// Floating lines/frame preview the in-game opacity; fill, labels
			// and handles are editor-only.
			int opacityAlpha = Math.round(opacity * 2.55f);
			linePaint.setAlpha(floating ? opacityAlpha : LINE_ALPHA);
			borderPaint.setAlpha(opacityAlpha);
			// Grid content stays off the bar; the box frame and handles don't.
			canvas.save();
			canvas.clipRect(0, 0, getWidth(), barTop());
			if (floating)
				canvas.drawRect(area, fillPaint);
			for (int i = 0; i < 2; i++)
			{
				float x = area.left + lines[i] * aw;
				canvas.drawLine(x, area.top, x, area.bottom, linePaint);
				float y = area.top + lines[i + 2] * ah;
				canvas.drawLine(area.left, y, area.right, y, linePaint);
			}
			// Pair on opposite sides; second shows distance from far edge.
			drawVLabel(canvas, lines[0], true);
			drawVLabel(canvas, lines[1], false);
			drawHLabel(canvas, lines[2], true);
			drawHLabel(canvas, lines[3], false);
			canvas.restore();
			if (floating)
				drawBoxFrame(canvas);
		}

		private void drawBoxFrame(Canvas canvas)
		{
			float inset = borderPaint.getStrokeWidth() / 2f;
			canvas.drawRect(area.left + inset, area.top + inset,
					area.right - inset, area.bottom - inset, borderPaint);
			float[] xs = handleCenters(area.left, area.right);
			float[] ys = handleCenters(area.top, area.bottom);
			float size = HANDLE_DP * density;
			for (int xi = 0; xi < 3; xi++)
				for (int yi = 0; yi < 3; yi++)
					if (xi != 1 || yi != 1)
						drawIcon(canvas, handleIcon, xs[xi], ys[yi], size);
			drawIcon(canvas, moveIcon, xs[1], ys[1], MOVE_ICON_DP * density);
		}

		// Black backing so the icon reads over grid lines.
		private void drawIcon(Canvas canvas, Drawable icon, float cx, float cy,
				float size)
		{
			float half = size / 2f;
			canvas.drawRect(cx - half, cy - half, cx + half, cy + half,
					iconBgPaint);
			icon.setBounds(Math.round(cx - half), Math.round(cy - half),
					Math.round(cx + half), Math.round(cy + half));
			icon.draw(canvas);
		}

		private String percentText(float frac)
		{
			return String.format(Locale.US, "%d%%", Math.round(frac * 100));
		}

		// Clear of the box's corner handles when floating.
		private float labelMargin()
		{
			return (LABEL_MARGIN_DP + (floating ? HANDLE_DP : 0)) * density;
		}

		// Label at the top of a vertical line, beside it on preferLeft's
		// side unless that would clip the grid area edge.
		private void drawVLabel(Canvas canvas, float frac, boolean preferLeft)
		{
			String text = percentText(preferLeft ? frac : 1f - frac);
			float margin = LABEL_MARGIN_DP * density;
			float x = area.left + frac * area.width();
			float tw = labelPaint.measureText(text);
			float lx = preferLeft ? x - margin - tw : x + margin;
			if (lx < area.left + margin)
				lx = x + margin;
			else if (lx + tw > area.right - margin)
				lx = x - margin - tw;
			canvas.drawText(text, lx,
					area.top + labelMargin() - labelPaint.ascent(), labelPaint);
		}

		// Label at the left of a horizontal line, above or below it per
		// preferAbove unless that would clip the grid area edge.
		private void drawHLabel(Canvas canvas, float frac, boolean preferAbove)
		{
			String text = percentText(preferAbove ? frac : 1f - frac);
			float margin = LABEL_MARGIN_DP * density;
			float y = area.top + frac * area.height();
			float above = y - margin - labelPaint.descent();
			float below = y + margin - labelPaint.ascent();
			float baseline = preferAbove ? above : below;
			if (baseline + labelPaint.ascent() < area.top + margin)
				baseline = below;
			else if (baseline + labelPaint.descent() > area.bottom - margin)
				baseline = above;
			canvas.drawText(text, area.left + labelMargin(), baseline,
					labelPaint);
		}

		// Box drag bits for a handle hitbox at (x, y), or 0.
		private int boxHandleAt(float x, float y)
		{
			computeArea();
			float[] xs = handleCenters(area.left, area.right);
			float[] ys = handleCenters(area.top, area.bottom);
			float reach = HANDLE_HIT_DP * density / 2f;
			for (int xi = 0; xi < 3; xi++)
				for (int yi = 0; yi < 3; yi++)
				{
					if (Math.abs(x - xs[xi]) > reach
							|| Math.abs(y - ys[yi]) > reach)
						continue;
					if (xi == 1 && yi == 1)
						return BOX_MOVE;
					return (xi == 0 ? EDGE_LEFT : xi == 2 ? EDGE_RIGHT : 0)
							| (yi == 0 ? EDGE_TOP : yi == 2 ? EDGE_BOTTOM : 0);
				}
			return 0;
		}

		// Priority: box handles (even over the bar), then the bar's own
		// buttons, then divider lines. false = not ours, so the touch falls
		// through to the bar or the swallowing root.
		private boolean onTouchDown(MotionEvent event)
		{
			if (dragV >= 0 || dragH >= 0 || boxDrag != 0)
				return true;
			float x = event.getX();
			float y = event.getY();
			if (getWidth() <= 0 || getHeight() <= 0)
				return false;
			if (floating)
			{
				int bits = boxHandleAt(x, y);
				if (bits != 0)
				{
					boxDrag = bits;
					grabOffsetX = (bits & (EDGE_LEFT | BOX_MOVE)) != 0
							? x - area.left
							: (bits & EDGE_RIGHT) != 0 ? x - area.right : 0;
					grabOffsetY = (bits & (EDGE_TOP | BOX_MOVE)) != 0
							? y - area.top
							: (bits & EDGE_BOTTOM) != 0 ? y - area.bottom : 0;
					beginDrag(event);
					return true;
				}
			}
			if (y >= barTop())
				return false;
			computeArea();
			float aw = area.width();
			float ah = area.height();
			if (aw <= 0 || ah <= 0)
				return false;
			float grabRadius = GRAB_RADIUS_DP * density;
			// A floating box's lines end at the box; no grabbing across the
			// dead space outside it.
			if (x < area.left - grabRadius || x > area.right + grabRadius
					|| y < area.top - grabRadius
					|| y > area.bottom + grabRadius)
				return false;

			// Within the grab radius on both axes = intersection grab; both
			// lines follow the finger.
			float[] lines = lines();
			int nearV = nearestLine((x - area.left) / aw, 0);
			int nearH = nearestLine((y - area.top) / ah, 2);
			float lineX = area.left + lines[nearV] * aw;
			float lineY = area.top + lines[nearH] * ah;
			boolean vHit = Math.abs(lineX - x) <= grabRadius;
			boolean hHit = Math.abs(lineY - y) <= grabRadius;
			if (!vHit && !hHit)
				return false;
			dragV = vHit ? nearV : -1;
			dragH = hHit ? nearH : -1;
			grabOffsetX = vHit ? x - lineX : 0;
			grabOffsetY = hHit ? y - lineY : 0;
			beginDrag(event);
			return true;
		}

		private void beginDrag(MotionEvent event)
		{
			trackedPointerId = event.getPointerId(0);
			performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
			if (floating && barTop() < getHeight())
				showBar(false);
		}

		// Index into lines[] of the line nearest frac on one axis; base = 0 for
		// the vertical pair, 2 for the horizontal.
		private int nearestLine(float frac, int base)
		{
			float[] lines = lines();
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
			if (dragV < 0 && dragH < 0 && boxDrag == 0)
				return;
			int idx = event.findPointerIndex(trackedPointerId);
			if (idx < 0)
				return;
			float x = event.getX(idx) - grabOffsetX;
			float y = event.getY(idx) - grabOffsetY;
			if (boxDrag != 0)
				dragBox(x, y);
			else
			{
				computeArea();
				float[] lines = lines();
				if (dragV >= 0)
					lines[dragV] = constrain(
							snap((x - area.left) / area.width()), dragV);
				if (dragH >= 0)
					lines[dragH] = constrain(
							snap((y - area.top) / area.height()), dragH);
			}
			invalidate();
		}

		// x/y are the grabbed edge (or box top-left for a move), already
		// offset. The box never leaves the view (= screen edge / keyboard top)
		// or enters the nav bar, and never goes below minBoxPx.
		private void dragBox(float x, float y)
		{
			float w = getWidth();
			float h = getHeight();
			float maxB = Math.max(0f, h - floatBottomPad);
			float minW = Math.min(minBoxPx(), w);
			float minH = Math.min(minBoxPx(), maxB);
			float l = floatRect[0] * w;
			float t = floatRect[1] * h;
			float r = floatRect[2] * w;
			float b = floatRect[3] * h;
			if (boxDrag == BOX_MOVE)
			{
				float bw = r - l;
				float bh = b - t;
				l = clamp(x, 0, w - bw);
				t = clamp(y, 0, maxB - bh);
				r = l + bw;
				b = t + bh;
			}
			else
			{
				if ((boxDrag & EDGE_LEFT) != 0)
					l = clamp(x, 0, r - minW);
				if ((boxDrag & EDGE_RIGHT) != 0)
					r = clamp(x, l + minW, w);
				if ((boxDrag & EDGE_TOP) != 0)
					t = clamp(y, 0, b - minH);
				if ((boxDrag & EDGE_BOTTOM) != 0)
					b = clamp(y, t + minH, maxB);
			}
			floatRect[0] = l / w;
			floatRect[1] = t / h;
			floatRect[2] = r / w;
			floatRect[3] = b / h;
		}

		private void onTouchEnd()
		{
			resetDrag();
			showBar(true);
		}

		// Clamp to the edge padding and to the partner line on the same axis —
		// lines may meet (equality collapses the middle band) but never cross.
		private float constrain(float frac, int index)
		{
			float[] lines = lines();
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
				return onTouchDown(event);
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
