package com.crawlmb.recording.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
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
 *   status line (turn · input · place · state) / examine summary
 *   range picker over the whole game   [clip to GIF] (Range on only)
 *   timeline scrubber (centred alone in the same box while Range is off)
 *   transport:   1x  |<<  <|  >/||  |>  >>|  [events]
 *   modes:       Step: turn   Skip: 10 turns   As played   Range
 *   screens:     @  Items  Spells  Abilities  Skills  [examine]     (paused only)
 * In examine mode those rows become Examine / Center on Player / Exit (the cursor
 * moves via ReplayDirectionPad over the map). While a browse screen is open
 * everything but the status line gives way to the Crawl keyboard.
 * Pure view: all decisions are made by ReplayUi through {@link Actions}.
 */
public final class ReplayControlsView extends LinearLayout
{
	public interface Actions
	{
		/** gap: label of the unrecorded span under the thumb (user drags only), else null. */
		void onScrub(int pos, boolean fromUser, String gap);
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
		void onRangeChanged(boolean on, int lo, int hi);
		void onClip();
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
	private final RangeBar rangeBar;
	private final Button rangeButton;
	private final ImageButton clip;
	private final LinearLayout timelineBox;
	private final LinearLayout rangeRow;
	private boolean rangeOn;
	private int lastPos;
	private final LinearLayout normalRows;
	private final LinearLayout examinePad;
	private final LinearLayout keyboardHost;
	private boolean examining;
	private static final int RANGE_DP = 30; // line, carets and their turn labels
	private static final int TIMELINE_DP = 32;
	private static final int TIMELINE_BOX_DP = RANGE_DP + TIMELINE_DP + 2;
	// Range off: centres the lone scrubber between a 1-line status and the buttons.
	private static final int TIMELINE_ALONE_DP = 12;
	private final Paint border = new Paint();
	private static final int CLIP_DP = 40;
	private final Button playPause;
	private ObjectAnimator playPulse;
	private final Button speed;
	private final Button stepUnit;
	private final Button skipUnit;
	private final Button clockMode;
	private final List<View> pausedOnly = new ArrayList<>();

	public ReplayControlsView(Context ctx, Actions actions)
	{
		super(ctx);
		this.actions = actions;
		setOrientation(VERTICAL);
		setBackgroundColor(ContextCompat.getColor(ctx, R.color.crawl_surface));
		setId(View.generateViewId());
		int pad = dp(4);
		setPadding(pad, pad, pad, pad);
		border.setColor(ContextCompat.getColor(ctx, R.color.crawl_border));
		setWillNotDraw(false);

		status = new TextView(ctx);
		status.setTypeface(Typeface.MONOSPACE);
		status.setTextColor(ContextCompat.getColor(ctx, R.color.crawl_text));
		status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
		status.setSingleLine(false);
		status.setLines(2); // fixed: a 1-line status would resize the terminal
		status.setGravity(Gravity.CENTER);
		addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

		// Fixed height for both timelines, so toggling Range never moves the rows below.
		timelineBox = new LinearLayout(ctx);
		timelineBox.setOrientation(VERTICAL);
		addView(timelineBox, new LayoutParams(LayoutParams.MATCH_PARENT, dp(TIMELINE_BOX_DP)));

		rangeRow = row(ctx);
		rangeRow.setVisibility(GONE);
		rangeBar = new RangeBar(ctx);
		rangeBar.setListener((lo, hi) -> applyRange());
		rangeRow.addView(rangeBar, new LayoutParams(0, dp(RANGE_DP), 1f));
		clip = new ImageButton(ctx);
		clip.setImageResource(R.drawable.ic_replay_clip);
		clip.setBackgroundResource(flat());
		clip.setPadding(0, 0, 0, 0);
		clip.setContentDescription("Clip range to GIF");
		clip.setOnClickListener(v -> actions.onClip());
		LayoutParams clipLp = new LayoutParams(dp(CLIP_DP), dp(RANGE_DP));
		rangeRow.addView(clip, clipLp);
		clip.setTranslationY(dp(RangeBar.LINE_DP) - dp(RANGE_DP) / 2f); // centre on RangeBar's line, not its labels
		timelineBox.addView(rangeRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		// The raised clip icon pokes above the box.
		rangeRow.setClipChildren(false);
		timelineBox.setClipChildren(false);
		setClipChildren(false);

		timeline = new TimelineBar(ctx);
		rangeBar.setLabeler(u ->
		{
			int t = timeline.turnAtUnit(u);
			return t < 0 ? "Menus" : String.valueOf(t);
		});
		timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
		{
			@Override public void onProgressChanged(SeekBar s, int p, boolean user)
			{
				actions.onScrub(timeline.posAt(p), user, user ? timeline.gapAt(p) : null);
			}
			@Override public void onStartTrackingTouch(SeekBar s) { actions.onScrubStart(); }
			@Override public void onStopTrackingTouch(SeekBar s) { actions.onScrubEnd(); }
		});
		LayoutParams tlp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(TIMELINE_DP));
		tlp.topMargin = dp(TIMELINE_ALONE_DP);
		timelineBox.addView(timeline, tlp);
		// The 20dp icon is centred and its path stops 2/24 short of its edge.
		float inkInset = (dp(CLIP_DP) - dp(20)) / 2f + dp(20) / 12f;
		// Scissors ink ends where the scrubber does; the range line closes half the gap to it.
		clipLp.rightMargin = Math.round(timeline.getPaddingRight() - inkInset);
		rangeBar.setPadding(timeline.getPaddingLeft(), 0, clipLp.rightMargin / 2, 0);

		normalRows = new LinearLayout(ctx);
		normalRows.setOrientation(VERTICAL);
		LinearLayout transport = row(ctx);
		speed = button(transport, "1x", v -> actions.onSpeed(), false);
		button(transport, "|\u25C0\u25C0", v -> actions.onSkip(-1), false);
		button(transport, "\u25C0|", v -> actions.onStep(-1), false);
		playPause = button(transport, "\u25B6", v -> actions.onPlayPause(), false);
		button(transport, "|\u25B6", v -> actions.onStep(1), false);
		button(transport, "\u25B6\u25B6|", v -> actions.onSkip(1), false);
		iconButton(transport, R.drawable.ic_replay_filter, "Events", v -> actions.onFilter(), false);
		for (int i = 0; i < transport.getChildCount(); i++)
		{
			View b = transport.getChildAt(i);
			b.setBackgroundResource(R.drawable.btn_replay_transport);
			if (b instanceof Button)
				((Button) b).setTextSize(TypedValue.COMPLEX_UNIT_SP, b == playPause ? 24 : 18);
		}
		normalRows.addView(transport);

		// Widths follow each button's longest label, so short ones don't hog the row.
		LinearLayout modes = row(ctx);
		stepUnit = fit(button(modes, "Step: turn", v -> actions.onStepUnit(), false), "Step: input");
		skipUnit = fit(button(modes, "Skip: 10 turns", v -> actions.onSkipUnit(), false),
				"Skip: 100 turns", "Skip: 1000 turns", "Skip: event", "Skip: level");
		clockMode = fit(button(modes, "As played", v -> actions.onClockMode(), false), "Turn time");
		rangeButton = fit(button(modes, "Range", v -> setRange(!rangeOn, rangeBar.lo(), rangeBar.hi()), false));
		normalRows.addView(modes);

		LinearLayout screens = row(ctx);
		fit(button(screens, "@", v -> actions.onScreen(ReplayScreens.Kind.CHARACTER), true));
		fit(button(screens, "Items", v -> actions.onScreen(ReplayScreens.Kind.INVENTORY), true));
		fit(button(screens, "Spells", v -> actions.onScreen(ReplayScreens.Kind.SPELLS), true));
		fit(button(screens, "Abilities", v -> actions.onScreen(ReplayScreens.Kind.ABILITIES), true));
		fit(button(screens, "Skills", v -> actions.onScreen(ReplayScreens.Kind.TRAINING), true));
		iconButton(screens, R.drawable.ic_replay_examine, "Examine", v -> actions.onExamine(), true)
				.setLayoutParams(new LayoutParams(0, dp(40), dp(CLIP_DP)));
		for (int i = 0; i < screens.getChildCount(); i++)
			screens.getChildAt(i).setBackgroundResource(flat());
		normalRows.addView(screens);
		normalRows.setGravity(Gravity.CENTER_VERTICAL);
		addView(normalRows, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

		examinePad = row(ctx);
		button(examinePad, "Examine", v -> actions.onExamineDescribe(), false);
		button(examinePad, "Center on Player", v -> actions.onExamineHome(), false);
		button(examinePad, "Exit", v -> actions.onExamineDone(), false);
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
		// Range bar height is always reserved so toggling it doesn't resize either.
		int body = dp(TIMELINE_BOX_DP) + 3 * dp(40);
		if (keyboardHost.getChildCount() > 0)
		{
			View kb = keyboardHost.getChildAt(0);
			kb.measure(exactW, any);
			body = Math.max(body, kb.getMeasuredHeight());
		}
		int h = getPaddingTop() + getPaddingBottom() + status.getMeasuredHeight() + body;
		super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
	}

	@Override
	protected void onDraw(Canvas c)
	{
		super.onDraw(c);
		// Separates the controls from the game text above.
		c.drawRect(0, 0, getWidth(), dp(1), border);
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
		timelineBox.setVisibility(on ? GONE : VISIBLE);
		normalRows.setVisibility(on || examining ? GONE : VISIBLE);
		examinePad.setVisibility(!on && examining ? VISIBLE : GONE);
	}

	public TimelineBar timeline() { return timeline; }

	public void setStatus(CharSequence s) { status.setText(s); }

	public void setPosition(int pos)
	{
		lastPos = pos;
		int p = timeline.progressOf(pos);
		if (timeline.getProgress() != p)
			timeline.setProgress(p);
	}

	@Override
	protected void onDetachedFromWindow()
	{
		super.onDetachedFromWindow();
		// The next setPlaying(true) after a re-attach restarts it.
		if (playPulse != null)
		{
			playPulse.cancel();
			playPulse = null;
			playPause.setAlpha(1f);
		}
	}

	/** Screen and examine buttons only work while paused. */
	public void setPlaying(boolean playing)
	{
		playPause.setText(playing ? "||" : "\u25B6");
		// Called every frame; only act on a state change so the pulse isn't restarted.
		if (playing != (playPulse != null))
		{
			// Bold alone barely thickens Roboto's bars; the stroke does the rest.
			playPause.setTypeface(playing ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
			playPause.getPaint().setStyle(playing ? Paint.Style.FILL_AND_STROKE : Paint.Style.FILL);
			playPause.getPaint().setStrokeWidth(playing ? dp(2) : 0);
			if (playing)
			{
				playPulse = ObjectAnimator.ofFloat(playPause, View.ALPHA, 1f, 0.4f);
				playPulse.setDuration(700);
				playPulse.setRepeatCount(ValueAnimator.INFINITE);
				playPulse.setRepeatMode(ValueAnimator.REVERSE);
				playPulse.start();
			}
			else
			{
				playPulse.cancel();
				playPulse = null;
				playPause.setAlpha(1f);
			}
		}
		for (View b : pausedOnly)
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
		rangeBar.setEnabled(!on);
		clip.setEnabled(!on);
		clip.setAlpha(on ? 0.35f : 1f);
	}

	/** Range on: the scrubber shows only units lo..hi of the whole game. Call after setData. */
	public void setRange(boolean on, int lo, int hi)
	{
		rangeOn = on;
		rangeButton.setActivated(on);
		rangeBar.setRange(timeline.units(), lo, hi < 0 ? timeline.units() - 1 : hi);
		rangeRow.setVisibility(on ? VISIBLE : GONE);
		((LayoutParams) timeline.getLayoutParams()).topMargin = on ? 0 : dp(TIMELINE_ALONE_DP);
		timeline.requestLayout();
		applyRange();
	}

	/** First and last position inside the scrubber's window: the range to clip. */
	public int[] windowPositions()
	{
		return new int[] { timeline.posAt(0), timeline.posAt(timeline.getMax()) };
	}

	private void applyRange()
	{
		if (rangeOn)
			timeline.setWindow(rangeBar.lo(), rangeBar.hi(), true);
		else
			timeline.setWindow(0, timeline.units() - 1, false);
		setPosition(lastPos);
		actions.onRangeChanged(rangeOn, rangeBar.lo(), rangeBar.hi());
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
		b.setSingleLine(true);
		b.setOnClickListener(l);
		row.addView(b, new LayoutParams(0, dp(40), 1f));
		if (pausedOnlyButton)
			pausedOnly.add(b);
		return b;
	}

	private ImageButton iconButton(LinearLayout row, int icon, String desc, OnClickListener l,
			boolean pausedOnlyButton)
	{
		ImageButton b = new ImageButton(getContext());
		b.setImageResource(icon);
		b.setPadding(0, 0, 0, 0);
		b.setContentDescription(desc);
		b.setOnClickListener(l);
		row.addView(b, new LayoutParams(0, dp(40), 1f));
		if (pausedOnlyButton)
			pausedOnly.add(b);
		return b;
	}

	/** Touch highlight with no button shape. */
	private int flat()
	{
		TypedValue tv = new TypedValue();
		getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
		return tv.resourceId;
	}

	/** Weight a button by the widest of its labels, the current one included. */
	private Button fit(Button b, String... otherLabels)
	{
		float w = b.getPaint().measureText(b.getText().toString());
		for (String s : otherLabels)
			w = Math.max(w, b.getPaint().measureText(s));
		((LayoutParams) b.getLayoutParams()).weight = w + dp(16);
		return b;
	}

	private int dp(int v)
	{
		return Math.round(v * getResources().getDisplayMetrics().density);
	}
}
