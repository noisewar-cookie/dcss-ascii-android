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
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.crawlmb.Preferences;
import com.crawlmb.R;

import java.util.Locale;

// "Reposition Fold Centerline": drag the split line to resize the two
// unfolded halves. Snaps to whole %, clamped [25%, 75%]. Persisted via
// Preferences; applied in onFoldStateChanged via Posture.withCenterline.
public class CenterlineController
{
	public interface Callbacks
	{
		void onSave(float fraction);
		void onExit(boolean restoreIme);
	}

	private static final int BAR_HEIGHT_DP = 56;
	private static final int BACKGROUND_COLOR = 0xFF000000;
	private static final int GRAB_RADIUS_DP = 32;

	private final Activity activity;
	private final RelativeLayout screenLayout;
	private final View keyboardView;
	private final int bottomInset;
	private final int highlightColor;
	private final Callbacks callbacks;
	private final float density;

	private FrameLayout root;
	private EditorView editorView;
	private boolean active = false;

	private float centerline; // fraction of screen width [0.25, 0.75]
	private int trackedPointerId = -1;
	private float grabOffsetX = 0;

	public CenterlineController(Activity activity,
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

	public boolean isActive()
	{
		return active;
	}

	public void enter()
	{
		if (active)
			return;
		active = true;
		centerline = Preferences.getFoldCenterline();
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
		float result = centerline;
		teardown();
		callbacks.onSave(result);
	}

	private void reset()
	{
		trackedPointerId = -1;
		centerline = Preferences.FOLD_CENTERLINE_DEFAULT;
		if (editorView != null)
			editorView.invalidate();
	}

	private void teardown()
	{
		active = false;
		if (root != null && root.getParent() instanceof ViewGroup)
			((ViewGroup) root.getParent()).removeView(root);
	}

	private void buildUi()
	{
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

		editorView = new EditorView(activity, barHeight);
		editorView.setHapticFeedbackEnabled(Preferences.getHapticFeedbackEnabled());

		root = new FrameLayout(activity);
		root.setBackgroundColor(BACKGROUND_COLOR);
		root.setClickable(true);
		root.setFocusable(true);
		root.addView(editorView, new FrameLayout.LayoutParams(
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
				.setOnClickListener(v -> exit(true));

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

	private static float snap(float frac)
	{
		return Math.round(frac * 100f) / 100f;
	}

	private static float clamp(float frac)
	{
		return Math.max(Preferences.FOLD_CENTERLINE_MIN,
				Math.min(Preferences.FOLD_CENTERLINE_MAX, frac));
	}

	private class EditorView extends View
	{
		private final Paint linePaint = new Paint();
		private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint halfPaint = new Paint();
		private final int barHeight;

		EditorView(Context context, int barHeight)
		{
			super(context);
			this.barHeight = barHeight;
			linePaint.setStyle(Paint.Style.STROKE);
			linePaint.setStrokeWidth(Math.max(6, Math.round(3 * density)));
			linePaint.setColor(highlightColor);
			labelPaint.setColor(0xFFFFFFFF);
			labelPaint.setTextAlign(Paint.Align.CENTER);
			labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
			labelPaint.setTextSize(TypedValue.applyDimension(
					TypedValue.COMPLEX_UNIT_SP, 22,
					context.getResources().getDisplayMetrics()));
			labelPaint.setShadowLayer(4, 0, 0, 0xFF000000);
			halfPaint.setStyle(Paint.Style.FILL);
		}

		@Override
		protected void onDraw(Canvas canvas)
		{
			super.onDraw(canvas);
			int w = getWidth();
			int h = getHeight();
			if (w <= 0 || h <= 0)
				return;
			float drawH = h - barHeight;
			float lineX = centerline * w;

			halfPaint.setColor(0x18FFFFFF);
			canvas.drawRect(0, 0, lineX, drawH, halfPaint);
			halfPaint.setColor(0x10FFFFFF);
			canvas.drawRect(lineX, 0, w, drawH, halfPaint);

			canvas.drawLine(lineX, 0, lineX, drawH, linePaint);

			// Position % on the line.
			String text = String.format(Locale.US, "%d%%",
					Math.round(centerline * 100));
			float labelY = drawH * 0.15f
					- (labelPaint.ascent() + labelPaint.descent()) / 2f;
			canvas.drawText(text, lineX, labelY, labelPaint);

			// Each half's width % centered inside it.
			float savedSize = labelPaint.getTextSize();
			labelPaint.setTextSize(TypedValue.applyDimension(
					TypedValue.COMPLEX_UNIT_SP, 14,
					getResources().getDisplayMetrics()));
			float sideLabelY = drawH * 0.5f
					- (labelPaint.ascent() + labelPaint.descent()) / 2f;
			canvas.drawText(text, lineX / 2f, sideLabelY, labelPaint);
			canvas.drawText(String.format(Locale.US, "%d%%",
					Math.round((1f - centerline) * 100)),
					lineX + (w - lineX) / 2f, sideLabelY, labelPaint);
			labelPaint.setTextSize(savedSize);
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
			float lineX = centerline * getWidth();
			float grabRadius = GRAB_RADIUS_DP * density;
			if (Math.abs(x - lineX) > grabRadius)
				return;
			trackedPointerId = event.getPointerId(0);
			grabOffsetX = x - lineX;
			performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
		}

		private void onMove(MotionEvent event)
		{
			if (trackedPointerId < 0)
				return;
			int idx = event.findPointerIndex(trackedPointerId);
			if (idx < 0)
				return;
			float x = event.getX(idx);
			int w = getWidth();
			if (w <= 0)
				return;
			centerline = clamp(snap((x - grabOffsetX) / w));
			invalidate();
		}

		private void onUp()
		{
			trackedPointerId = -1;
		}
	}
}
