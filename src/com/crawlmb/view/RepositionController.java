package com.crawlmb.view;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.crawlmb.Preferences;
import com.crawlmb.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// "Reposition In-Game UI" mode: a full-screen overlay on top of the frozen
// game screen that lets the user reorder the vertical stack of the gameplay
// panel units (map / hud+statusBar / mlist / msg) by pressing and dragging.
// Panels move purely via translationY — layout params are never touched.
// SAVE hands the chosen order to the activity, which persists it and
// rebuilds the view tree; CANCEL zeroes the translations. The router must
// be frozen (RegionRouter.setRepositionFrozen) before entry so detection-
// driven applyMode calls can't stomp the forced split-panel visibility.
public class RepositionController
{
	public interface Callbacks
	{
		// Invoked after teardown with the chosen top-to-bottom unit order.
		void onSave(String[] order);

		// Invoked after teardown on any non-save exit (CANCEL button, BACK,
		// activity stop). restoreIme = false when the activity is stopping.
		void onCancel(boolean restoreIme);
	}

	private static final long SNAP_ANIM_MS = 150;
	private static final int DIM_COLOR = 0x99000000;
	private static final int BAR_HEIGHT_DP = 56;
	private static final float DRAG_ELEVATION_DP = 8;

	private final Activity activity;
	private final RelativeLayout screenLayout;
	private final RegionRouter router;
	private final ViewGroup splitContainer;
	private final View keyboardView; // null unless the Crawl keyboard is shown
	private final int bottomInset;
	private final int highlightColor;
	private final Callbacks callbacks;
	private final float density;

	private final Unit[] units;
	// Current top-to-bottom order being edited. Starts as the laid-out
	// (persisted) order.
	private final List<Unit> workingOrder = new ArrayList<>();

	private FrameLayout repositionRoot;
	private OverlayView overlayView;
	private boolean active = false;
	// Show the name label on every panel — set while panel content may be
	// stale or blank (mode entered from a menu/pregame screen). When live
	// gameplay pixels are detected, labels become drag-only.
	private boolean persistentLabels = true;
	private ViewTreeObserver.OnGlobalLayoutListener layoutListener;

	// Overlay-space offsets of splitContainer, recomputed per layout pass
	// (absorbs the game panel's safe-area inset padding).
	private int splitTopInOverlay = 0;
	private int splitLeftInOverlay = 0;

	// Touch state: IDLE (dragged null) -> DRAGGING (grab on DOWN) -> snap
	// animation -> IDLE.
	private Unit dragged = null;
	private int trackedPointerId = -1;
	private float downY = 0;
	private float grabBaseTy = 0;

	// One draggable unit of the stack. The hud unit spans two views (the
	// HUD panel and the status-lights bar directly below it).
	private static class Unit
	{
		final String key;
		final String label;
		final View[] members;
		int laidOutTop; // px within splitContainer, untranslated
		int height;     // px, sum of member heights

		Unit(String key, String label, View... members)
		{
			this.key = key;
			this.label = label;
			this.members = members;
		}

		float translationY()
		{
			return members[0].getTranslationY();
		}
	}

	public RepositionController(Activity activity,
			RelativeLayout screenLayout, RegionRouter router,
			ViewGroup splitContainer, View mapView, View hudView,
			View statusBar, View mlistView, View msgView, View keyboardView,
			int bottomInset, int highlightColor, Callbacks callbacks)
	{
		this.activity = activity;
		this.screenLayout = screenLayout;
		this.router = router;
		this.splitContainer = splitContainer;
		this.keyboardView = keyboardView;
		this.bottomInset = bottomInset;
		this.highlightColor = highlightColor;
		this.callbacks = callbacks;
		this.density = activity.getResources().getDisplayMetrics().density;
		units = new Unit[] {
				new Unit("map",
						activity.getString(R.string.reposition_panel_map),
						mapView),
				new Unit("hud",
						activity.getString(R.string.reposition_panel_hud),
						hudView, statusBar),
				new Unit("mlist",
						activity.getString(R.string.reposition_panel_mlist),
						mlistView),
				new Unit("msg",
						activity.getString(R.string.reposition_panel_msg),
						msgView) };
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

		// The layout was built from the persisted order, so it is also the
		// initial working order.
		workingOrder.clear();
		for (String key : Preferences.getPanelOrder())
			workingOrder.add(unitByKey(key));

		persistentLabels = !router.pendingFrozenGameplay();
		router.setFrozenGameplayCallback(() ->
		{
			persistentLabels = false;
			if (overlayView != null)
				overlayView.invalidate();
		});
		router.forceSplitVisibleForReposition();

		hideSystemIme();
		buildUi();

		layoutListener = this::recomputeGeometry;
		splitContainer.getViewTreeObserver()
				.addOnGlobalLayoutListener(layoutListener);
		recomputeGeometry();
	}

	// Non-save exit: put every panel back where the layout left it.
	public void cancel(boolean restoreIme)
	{
		if (!active)
			return;
		endDragIfActive();
		for (Unit u : units)
			for (View v : u.members)
			{
				v.animate().cancel();
				v.setTranslationY(0);
				v.setTranslationZ(0);
			}
		teardown();
		callbacks.onCancel(restoreIme);
	}

	private void save()
	{
		endDragIfActive();
		String[] order = new String[workingOrder.size()];
		for (int i = 0; i < order.length; i++)
			order[i] = workingOrder.get(i).key;
		teardown();
		callbacks.onSave(order);
	}

	// Animate back to the default order without leaving the mode.
	private void resetOrder()
	{
		endDragIfActive();
		List<Unit> defaultOrder = new ArrayList<>();
		for (String key : Preferences.PANEL_KEYS)
			defaultOrder.add(unitByKey(key));
		if (workingOrder.equals(defaultOrder))
			return;
		workingOrder.clear();
		workingOrder.addAll(defaultOrder);
		applyBaseTranslations(true);
		overlayView.invalidate();
	}

	private void teardown()
	{
		active = false;
		splitContainer.getViewTreeObserver()
				.removeOnGlobalLayoutListener(layoutListener);
		router.setFrozenGameplayCallback(null);
		// Unfreezing reapplies the pending/current mode — this restores the
		// layer visibility that forceSplitVisibleForReposition overrode.
		router.setRepositionFrozen(false);
		if (repositionRoot != null
				&& repositionRoot.getParent() instanceof ViewGroup)
			((ViewGroup) repositionRoot.getParent())
					.removeView(repositionRoot);
	}

	private void buildUi()
	{
		// Clickable + focusable so anything the overlay/bar don't handle is
		// still swallowed (reload-overlay precedent) — the game must receive
		// no input while the mode is active.
		repositionRoot = new FrameLayout(activity);
		repositionRoot.setClickable(true);
		repositionRoot.setFocusable(true);

		overlayView = new OverlayView(activity);
		overlayView.setHapticFeedbackEnabled(
				Preferences.getHapticFeedbackEnabled());
		repositionRoot.addView(overlayView, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));

		View bar = activity.getLayoutInflater().inflate(
				R.layout.reposition_buttons, repositionRoot, false);
		int barHeight;
		int barBottomPad;
		if (keyboardView != null && keyboardView.getHeight() > 0)
		{
			// Cover the Crawl keyboard exactly (its height already includes
			// the absorbed bottom-inset padding).
			barHeight = keyboardView.getHeight();
			barBottomPad = keyboardView.getPaddingBottom();
		}
		else
		{
			// No keyboard area: a compact strip over the screen bottom.
			barBottomPad = bottomInset;
			barHeight = (int) (BAR_HEIGHT_DP * density) + barBottomPad;
		}
		bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop(),
				bar.getPaddingRight(), barBottomPad);
		bar.setClickable(true);
		repositionRoot.addView(bar, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, barHeight,
				Gravity.BOTTOM));

		bar.findViewById(R.id.reposition_save)
				.setOnClickListener(v -> save());
		bar.findViewById(R.id.reposition_reset)
				.setOnClickListener(v -> resetOrder());
		bar.findViewById(R.id.reposition_cancel)
				.setOnClickListener(v -> cancel(true));

		screenLayout.addView(repositionRoot, new RelativeLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
	}

	// The overlay can't cover the system soft keyboard (separate window), so
	// suppress it explicitly — same calls as GameActivity.enterReloadState.
	private void hideSystemIme()
	{
		activity.getWindow().setSoftInputMode(
				WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
		InputMethodManager imm = (InputMethodManager)
				activity.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.hideSoftInputFromWindow(screenLayout.getWindowToken(), 0);
	}

	// Refresh unit sizes and the split-container offset after any layout
	// pass (covers a late map-shrink font rescale landing mid-mode), then
	// re-pin every non-dragged unit to its working-order slot.
	private void recomputeGeometry()
	{
		if (!active)
			return;
		for (Unit u : units)
		{
			int top = Integer.MAX_VALUE;
			int h = 0;
			for (View v : u.members)
			{
				top = Math.min(top, v.getTop());
				h += v.getHeight();
			}
			u.laidOutTop = top;
			u.height = h;
		}
		int[] splitLoc = new int[2];
		int[] overlayLoc = new int[2];
		splitContainer.getLocationOnScreen(splitLoc);
		overlayView.getLocationOnScreen(overlayLoc);
		splitLeftInOverlay = splitLoc[0] - overlayLoc[0];
		splitTopInOverlay = splitLoc[1] - overlayLoc[1];
		applyBaseTranslations(false);
		overlayView.invalidate();
	}

	// Top of a unit's slot in split-container coordinates: unit heights are
	// invariant during the mode, so the slots tile the container exactly and
	// only their order changes.
	private int slotTop(Unit target)
	{
		int top = 0;
		for (Unit u : workingOrder)
		{
			if (u == target)
				return top;
			top += u.height;
		}
		return top;
	}

	// Move every non-dragged unit to its working-order slot; the dragged
	// unit follows the finger instead.
	private void applyBaseTranslations(boolean animate)
	{
		for (Unit u : units)
		{
			if (u == dragged)
				continue;
			float ty = slotTop(u) - u.laidOutTop;
			for (View v : u.members)
			{
				if (animate)
					v.animate().translationY(ty)
							.setDuration(SNAP_ANIM_MS)
							.setInterpolator(new DecelerateInterpolator())
							.setUpdateListener(a ->
									overlayView.postInvalidateOnAnimation())
							.start();
				else
				{
					v.animate().cancel();
					v.setTranslationY(ty);
				}
			}
		}
	}

	private Unit unitByKey(String key)
	{
		for (Unit u : units)
			if (u.key.equals(key))
				return u;
		return units[0];
	}

	// Unit whose working-order slot contains this split-container y.
	private Unit unitAtSlotY(float ySplit)
	{
		int top = 0;
		for (Unit u : workingOrder)
		{
			if (ySplit >= top && ySplit < top + u.height)
				return u;
			top += u.height;
		}
		return null;
	}

	private void onTouchDown(MotionEvent event)
	{
		if (dragged != null)
			return;
		float y = event.getY();
		Unit u = unitAtSlotY(y - splitTopInOverlay);
		if (u == null)
			return;
		trackedPointerId = event.getPointerId(0);
		downY = y;
		startDrag(u);
	}

	private void onTouchMove(MotionEvent event)
	{
		if (dragged == null)
			return;
		int idx = event.findPointerIndex(trackedPointerId);
		if (idx < 0)
			return;
		float y = event.getY(idx);
		float ty = grabBaseTy + (y - downY);
		for (View v : dragged.members)
			v.setTranslationY(ty);
		maybeSwap(y - splitTopInOverlay);
		overlayView.invalidate();
	}

	private void onTouchEnd()
	{
		if (dragged != null)
			endDrag();
	}

	private void startDrag(Unit u)
	{
		dragged = u;
		float elevation = DRAG_ELEVATION_DP * density;
		for (View v : dragged.members)
		{
			// A DOWN during an in-flight snap re-bases from wherever the
			// cancelled animation left the unit.
			v.animate().cancel();
			v.setTranslationZ(elevation);
		}
		grabBaseTy = dragged.translationY();
		overlayView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
		overlayView.invalidate();
	}

	private void endDrag()
	{
		Unit u = dragged;
		dragged = null;
		trackedPointerId = -1;
		float ty = slotTop(u) - u.laidOutTop;
		for (View v : u.members)
			v.animate().translationY(ty).translationZ(0)
					.setDuration(SNAP_ANIM_MS)
					.setInterpolator(new DecelerateInterpolator())
					.setUpdateListener(a ->
							overlayView.postInvalidateOnAnimation())
					.start();
		overlayView.invalidate();
	}

	private void endDragIfActive()
	{
		if (dragged != null)
			endDrag();
	}

	// Center-50% rule: when the finger enters the middle half of another
	// unit's slot, swap that unit with the dragged one and animate the
	// displaced units to their new slots. Post-swap the finger lies inside
	// the dragged unit's own new slot (never a target), so no oscillation.
	private void maybeSwap(float ySplit)
	{
		int draggedIdx = workingOrder.indexOf(dragged);
		int top = 0;
		for (int i = 0; i < workingOrder.size(); i++)
		{
			Unit u = workingOrder.get(i);
			if (u != dragged)
			{
				float bandTop = top + 0.25f * u.height;
				float bandBottom = top + 0.75f * u.height;
				if (ySplit >= bandTop && ySplit <= bandBottom)
				{
					Collections.swap(workingOrder, i, draggedIdx);
					applyBaseTranslations(true);
					return;
				}
			}
			top += u.height;
		}
	}

	// Draws the panel borders/dim/labels over the live panel views and owns
	// all touch handling of the mode (the button bar sits on top of it).
	private class OverlayView extends View
	{
		private final Paint borderPaint = new Paint();
		private final Paint dimPaint = new Paint();
		private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

		OverlayView(Context context)
		{
			super(context);
			borderPaint.setStyle(Paint.Style.STROKE);
			borderPaint.setStrokeWidth(Math.max(4, Math.round(2 * density)));
			borderPaint.setColor(highlightColor);
			dimPaint.setStyle(Paint.Style.FILL);
			dimPaint.setColor(DIM_COLOR);
			labelPaint.setColor(0xFFFFFFFF);
			labelPaint.setTextAlign(Paint.Align.CENTER);
			labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
			labelPaint.setTextSize(TypedValue.applyDimension(
					TypedValue.COMPLEX_UNIT_SP, 20,
					context.getResources().getDisplayMetrics()));
			labelPaint.setShadowLayer(4, 0, 0, 0xFF000000);
		}

		@Override
		protected void onDraw(Canvas canvas)
		{
			super.onDraw(canvas);
			// Dragged unit last so its decor stays on top.
			for (Unit u : units)
				if (u != dragged)
					drawUnit(canvas, u);
			if (dragged != null)
				drawUnit(canvas, dragged);
		}

		private void drawUnit(Canvas canvas, Unit u)
		{
			float top = splitTopInOverlay + u.laidOutTop + u.translationY();
			float bottom = top + u.height;
			float left = splitLeftInOverlay;
			float right = left + splitContainer.getWidth();
			if (u == dragged)
				canvas.drawRect(left, top, right, bottom, dimPaint);
			// Inner border: inset by half the stroke so it lies inside.
			float inset = borderPaint.getStrokeWidth() / 2f;
			canvas.drawRect(left + inset, top + inset, right - inset,
					bottom - inset, borderPaint);
			if (u == dragged || persistentLabels)
			{
				float cx = (left + right) / 2f;
				float cy = (top + bottom) / 2f
						- (labelPaint.ascent() + labelPaint.descent()) / 2f;
				canvas.drawText(u.label, cx, cy, labelPaint);
			}
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
