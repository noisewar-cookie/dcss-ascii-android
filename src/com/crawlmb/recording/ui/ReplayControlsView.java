package com.crawlmb.recording.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.crawlmb.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Replay controls hosted in the keyboard area (replaces the Crawl keyboard in
 * replay mode). Rows:
 *   status line (turn · place · XL · state) / examine summary
 *   timeline scrubber
 *   transport:   |<<  <|  >/||  |>  >>|  1x
 *   modes:       Step: turn   Skip: 10 turns   Clock: as played   Filter
 *   screens:     Inv  Spells  Abil  Char  Train  Examine     (paused only)
 * In examine mode those rows become Examine / @ Player / Done (the cursor
 * moves via ReplayDirectionPad over the map). While a browse screen is open
 * everything but the status line gives way to the Crawl keyboard.
 * Pure view: all decisions are made by ReplayUi through {@link Actions}.
 */
public final class ReplayControlsView extends LinearLayout
{
	public interface Actions
	{
		void onScrub(int pos, boolean fromUser);
		void onScrubStart();
		void onScrubEnd();
		void onSkip(int dir);
		void onStep(int dir);
		void onPlayPause();
		void onSpeed();
		void onStepUnit();
		void onSkipUnit();
		void onClockMode();
		void onFilter();
		void onScreen(ReplayScreens.Kind kind);
		void onExamine();
		void onExamineMove(int dx, int dy);
		void onExamineHome();
		void onExamineDescribe();
		void onExamineDone();
	}

	private final Actions actions;
	private final TextView status;
	private final TimelineBar timeline;
	private final LinearLayout normalRows;
	private final LinearLayout examinePad;
	private final LinearLayout keyboardHost;
	private boolean examining;
	private final Button playPause;
	private final Button speed;
	private final Button stepUnit;
	private final Button skipUnit;
	private final Button clockMode;
	private final List<Button> pausedOnly = new ArrayList<>();

	public ReplayControlsView(Context ctx, Actions actions)
	{
		super(ctx);
		this.actions = actions;
		setOrientation(VERTICAL);
		setBackgroundColor(ContextCompat.getColor(ctx, R.color.crawl_surface));
		setId(View.generateViewId());
		int pad = dp(4);
		setPadding(pad, pad, pad, pad);

		status = new TextView(ctx);
		status.setTypeface(Typeface.MONOSPACE);
		status.setTextColor(ContextCompat.getColor(ctx, R.color.crawl_text));
		status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
		status.setSingleLine(false);
		status.setLines(2); // fixed: a 1-line status would resize the terminal
		addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

		timeline = new TimelineBar(ctx);
		timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
		{
			@Override public void onProgressChanged(SeekBar s, int p, boolean user) { actions.onScrub(timeline.posAt(p), user); }
			@Override public void onStartTrackingTouch(SeekBar s) { actions.onScrubStart(); }
			@Override public void onStopTrackingTouch(SeekBar s) { actions.onScrubEnd(); }
		});
		addView(timeline, new LayoutParams(LayoutParams.MATCH_PARENT, dp(32)));

		normalRows = new LinearLayout(ctx);
		normalRows.setOrientation(VERTICAL);
		LinearLayout transport = row(ctx);
		button(transport, "|\u25C0\u25C0", v -> actions.onSkip(-1), false);
		button(transport, "\u25C0|", v -> actions.onStep(-1), false);
		playPause = button(transport, "\u25B6", v -> actions.onPlayPause(), false);
		button(transport, "|\u25B6", v -> actions.onStep(1), false);
		button(transport, "\u25B6\u25B6|", v -> actions.onSkip(1), false);
		speed = button(transport, "1x", v -> actions.onSpeed(), false);
		normalRows.addView(transport);

		LinearLayout modes = row(ctx);
		stepUnit = button(modes, "Step: turn", v -> actions.onStepUnit(), false);
		skipUnit = button(modes, "Skip: 10 turns", v -> actions.onSkipUnit(), false);
		clockMode = button(modes, "As played", v -> actions.onClockMode(), false);
		button(modes, "Filter", v -> actions.onFilter(), false);
		normalRows.addView(modes);

		LinearLayout screens = row(ctx);
		button(screens, "Inv", v -> actions.onScreen(ReplayScreens.Kind.INVENTORY), true);
		button(screens, "Spells", v -> actions.onScreen(ReplayScreens.Kind.SPELLS), true);
		button(screens, "Abil", v -> actions.onScreen(ReplayScreens.Kind.ABILITIES), true);
		button(screens, "Char", v -> actions.onScreen(ReplayScreens.Kind.CHARACTER), true);
		button(screens, "Train", v -> actions.onScreen(ReplayScreens.Kind.TRAINING), true);
		button(screens, "Examine", v -> actions.onExamine(), true);
		normalRows.addView(screens);
		normalRows.setGravity(Gravity.CENTER_VERTICAL);
		addView(normalRows, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

		examinePad = row(ctx);
		button(examinePad, "Examine", v -> actions.onExamineDescribe(), false);
		button(examinePad, "@ Player", v -> actions.onExamineHome(), false);
		button(examinePad, "Done", v -> actions.onExamineDone(), false);
		examinePad.setVisibility(GONE);
		addView(examinePad, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

		keyboardHost = new LinearLayout(ctx);
		keyboardHost.setVisibility(GONE);
		keyboardHost.setGravity(Gravity.BOTTOM);
		addView(keyboardHost, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
	}

	/**
	 * One height for every mode (controls, examine, keyboard): status plus the
	 * taller of the timeline+rows and the Crawl keyboard, so the terminal above
	 * never resizes.
	 */
	@Override
	protected void onMeasure(int widthSpec, int heightSpec)
	{
		int w = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
		int exactW = MeasureSpec.makeMeasureSpec(Math.max(0, w), MeasureSpec.EXACTLY);
		int any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
		status.measure(exactW, any);
		int body = dp(32) + 3 * dp(40);
		if (keyboardHost.getChildCount() > 0)
		{
			View kb = keyboardHost.getChildAt(0);
			kb.measure(exactW, any);
			body = Math.max(body, kb.getMeasuredHeight());
		}
		int h = getPaddingTop() + getPaddingBottom() + status.getMeasuredHeight() + body;
		super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
	}

	/** Crawl keyboard shown while a browse screen is open. */
	public void setKeyboard(View keyboard)
	{
		keyboardHost.removeAllViews();
		if (keyboard.getParent() instanceof android.view.ViewGroup)
			((android.view.ViewGroup) keyboard.getParent()).removeView(keyboard);
		keyboardHost.addView(keyboard, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
	}

	public void setBrowseMode(boolean on)
	{
		keyboardHost.setVisibility(on ? VISIBLE : GONE);
		timeline.setVisibility(on ? GONE : VISIBLE);
		normalRows.setVisibility(on || examining ? GONE : VISIBLE);
		examinePad.setVisibility(!on && examining ? VISIBLE : GONE);
	}

	public TimelineBar timeline() { return timeline; }

	public void setStatus(CharSequence s) { status.setText(s); }

	public void setPosition(int pos)
	{
		int p = timeline.progressOf(pos);
		if (timeline.getProgress() != p)
			timeline.setProgress(p);
	}

	/** Screen and examine buttons only work while paused. */
	public void setPlaying(boolean playing)
	{
		playPause.setText(playing ? "||" : "\u25B6");
		for (Button b : pausedOnly)
		{
			b.setEnabled(!playing);
			b.setAlpha(playing ? 0.35f : 1f);
		}
	}

	public void setLabels(String speedLabel, String stepLabel, String skipLabel, String clockLabel)
	{
		speed.setText(speedLabel);
		stepUnit.setText(stepLabel);
		skipUnit.setText(skipLabel);
		clockMode.setText(clockLabel);
	}

	public void setExamineMode(boolean on)
	{
		examining = on;
		normalRows.setVisibility(on ? GONE : VISIBLE);
		examinePad.setVisibility(on ? VISIBLE : GONE);
		timeline.setEnabled(!on);
	}

	private LinearLayout row(Context ctx)
	{
		LinearLayout r = new LinearLayout(ctx);
		r.setOrientation(HORIZONTAL);
		r.setGravity(Gravity.CENTER_VERTICAL);
		return r;
	}

	private Button button(LinearLayout row, String label, OnClickListener l, boolean pausedOnlyButton)
	{
		Button b = new Button(getContext());
		b.setText(label);
		b.setAllCaps(false);
		b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
		b.setMinWidth(0);
		b.setMinimumWidth(0);
		b.setPadding(dp(2), 0, dp(2), 0);
		b.setOnClickListener(l);
		row.addView(b, new LayoutParams(0, dp(40), 1f));
		if (pausedOnlyButton)
			pausedOnly.add(b);
		return b;
	}

	private int dp(int v)
	{
		return Math.round(v * getResources().getDisplayMetrics().density);
	}
}
