package com.crawlmb.recording.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import com.crawlmb.keyboard.CrawlKeyboardWrapper;
import com.crawlmb.keylistener.KeyListener;
import com.crawlmb.recording.ExamineCursor;
import com.crawlmb.recording.GameIdentity;
import com.crawlmb.recording.EventDeriver;
import com.crawlmb.recording.KnowledgeResolver;
import com.crawlmb.recording.RecordingReader;
import com.crawlmb.recording.Recordings;
import com.crawlmb.recording.ReplayClock;
import com.crawlmb.recording.ReplayController;
import com.crawlmb.recording.ReplayDriver;
import com.crawlmb.recording.ReplayGifEncoder;
import com.crawlmb.recording.ReplayLayout;
import com.crawlmb.recording.ReplayReadOnly;
import com.crawlmb.recording.ReplayRendererAdapter;
import com.crawlmb.recording.ReplayTimeline;
import com.crawlmb.recording.ReplayVideoEncoder;
import com.crawlmb.recording.ScreenState;
import com.crawlmb.recording.SqliteRecordingStore;
import com.crawlmb.view.TerminalRenderer;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates replay mode inside GameActivity: owns the controller,
 * driver, keyboard-area controls, browse screens and examine mode, and
 * enforces "screens and examine only while paused". GameActivity calls
 * bind() on every view rebuild, onRendererReady() once the renderer is
 * measured, onKeyDown() first in its own onKeyDown, and release() on exit.
 */
public final class ReplayUi implements ReplayControlsView.Actions, ReplayScreens.Host,
		ReplayRendererAdapter.PositionListener
{
	public interface Host
	{
		void toast(String msg);
		void exitReplay();
		File recordingFile();
		/** The panel exports capture (map, HUD, messages); null mid-rebuild. */
		View gamePanel();
		/** Save-as picker for a clip; dst is called only if the user picks a file. */
		void pickClipDestination(String fileName, String mime, ClipDestination dst);
		/** Copy a finished clip to the picked file, then delete it. */
		void saveClip(File clip, Uri dst, String what);
		/** Rebuild the views from layout(); the recording switched layouts. */
		void relayout();
		/** Repaint every cell of the current screen into the views. */
		void repaint();
	}

	public interface ClipDestination
	{
		void picked(Uri dst);
	}

	/** UI thread. ok false with no error = cancelled. */
	public interface ExportDone
	{
		void done(boolean ok, String error);
	}

	private interface ExportJob
	{
		void run(ViewCapture frames, ReplayVideoEncoder.Progress progress) throws IOException;
	}

	private enum SkipUnit { TURNS_10, TURNS_100, TURNS_1000, EVENT, LEVEL }

	private static final ReplayTimeline.Unit[] STEP_UNITS = { ReplayTimeline.Unit.TURN,
			ReplayTimeline.Unit.INPUT };

	private final Context ctx;
	private final Host host;
	private final RecordingReader reader;
	private final ReplayController controller;
	private final ReplayDriver driver;
	private ReplayRendererAdapter adapter;
	private final ReplayLayout recLayout;

	private ReplayControlsView controls;
	private RelativeLayout screenLayout;
	private TerminalRenderer renderer;
	private boolean confined;
	private boolean confinedLeft;

	// Browse screens are drawn into the terminal grid (controller overlay).
	private ReplayScreens screens;
	private ReplayScreens.Kind screenKind; // null = examine description
	private ReplayDirectionPad pad;
	private ExamineCursor cursor;
	private int speedIdx = 1; // 1x
	private int stepIdx = 0;
	private SkipUnit skipUnit = SkipUnit.TURNS_10;
	private final boolean[] ticksShown = TimelineBar.tickTypesAllShown();
	private boolean wasPlayingBeforeScrub;
	private boolean started;
	private int lastNotifiedPos = -1;
	private boolean rangeOn;
	private int rangeLo, rangeHi = -1;
	// Level trim: the range bar covers only positions trimFrom..trimTo.
	private boolean trimOn;
	private int trimFrom, trimTo;
	private int trimXlLo, trimXlHi; // 0 = never trimmed
	// Shown in red in place of the status line until clearGapNote.
	private String gapNote;
	private final Runnable clearGapNote = this::clearGapNote;
	private static final int GAP_NOTE_MS = 2000;
	// Position last drawn on the current views; -1 while a rebuild is due.
	private int drawnPos = -1;
	// Export waiting for the views to be drawn (first open, or the rebuild
	// after returning from the clip picker).
	private Runnable pendingExport;
	private AtomicBoolean exportCancel;
	private AlertDialog exportDialog;

	public ReplayUi(Context ctx, Host host, RecordingReader reader, int chunkCacheSize)
	{
		this.ctx = ctx;
		this.host = host;
		this.reader = reader;
		// Delegating target so the renderer can be re-bound on fold/rotation rebuilds.
		this.controller = new ReplayController(reader, false, chunkCacheSize,
				new ReplayController.RenderTarget()
				{
					@Override
					public void render(ScreenState s, int pos)
					{
						if (adapter != null && recLayout.accept(s.layout))
						{
							adapter.render(s, pos);
							drawnPos = pos;
						}
						else
							drawnPos = -1;
					}

					@Override
					public void onPositionChanged(int pos, boolean playing)
					{
						ReplayUi.this.onPositionChanged(pos, playing);
					}
				});
		this.driver = new ReplayDriver(controller);
		controller.setClockMode(ReplayClock.Mode.TURN_TIME);
		this.recLayout = new ReplayLayout(controller.layoutAt(0), host::relayout);
	}

	/** Recorded layout to build the views from; null = the viewer's prefs. */
	public int[] layout()
	{
		return recLayout.applied();
	}

	/**
	 * Called from GameActivity.rebuildViews in place of the Crawl keyboard.
	 * Returns the controls view, already added to screenLayout at the bottom
	 * (width 0 = full width, else pinned to the keyboard's fold half). Nothing
	 * is drawn until onRendererReady: the renderer has no bitmaps yet.
	 */
	public View bind(TerminalRenderer renderer, RelativeLayout layout, int width,
			boolean alignLeft)
	{
		screenLayout = layout;
		this.renderer = renderer;
		confined = width > 0;
		confinedLeft = alignLeft;
		pad = null; // belonged to the old layout
		drawnPos = -1;
		adapter = new ReplayRendererAdapter(renderer, this);
		recLayout.onRebuilt();
		controls = new ReplayControlsView(ctx, this);
		RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
				width > 0 ? width : RelativeLayout.LayoutParams.MATCH_PARENT,
				RelativeLayout.LayoutParams.WRAP_CONTENT);
		lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
		if (width > 0)
			lp.addRule(alignLeft ? RelativeLayout.ALIGN_PARENT_LEFT
					: RelativeLayout.ALIGN_PARENT_RIGHT);
		layout.addView(controls, lp);
		controls.timeline().setData(controller.timeline(), controller.resolver());
		controls.timeline().setTickTypesShown(ticksShown);
		controls.setTrim(trimOn, trimFrom, trimTo);
		controls.setRange(rangeOn, rangeLo, rangeHi);
		CrawlKeyboardWrapper kb = new CrawlKeyboardWrapper(ctx, new KeyListener()
		{
			@Override
			public void addKey(int key, int keyIndex)
			{
				onBrowseKey(key);
			}

			@Override
			public void addDirectionKey(int key)
			{
			}
		}, width, alignLeft);
		controls.setKeyboard(kb.virtualKeyboardView);
		updateLabels();
		if (cursor != null)
		{
			controls.setExamineMode(true);
			showPad();
		}
		updateBrowsing();
		return controls;
	}

	/** Renderer measured (GameActivity intercepts StartGame): draw. */
	public void onRendererReady()
	{
		if (adapter != null)
			adapter.reset();
		boolean first = !started;
		started = true;
		if (!first)
			controller.refresh();
		if (pendingExport != null)
		{
			Runnable r = pendingExport;
			pendingExport = null;
			r.run();
			return;
		}
		if (!first)
			return;
		controller.seek(0);
		int gaps = 0;
		for (RecordingReader.EventRow e : controller.timeline().events())
			if (isGapNotice(e))
				gaps++;
		if (gaps > 0)
			host.toast("Recording was off for part of this game ("
					+ gaps + (gaps == 1 ? " gap" : " gaps") + " marked in red).");
	}

	/** Renderer bitmaps were recreated (font scale): repaint everything. */
	public void redraw()
	{
		if (adapter == null)
			return;
		adapter.reset();
		controller.refresh();
	}

	public void release()
	{
		if (exportCancel != null)
			exportCancel.set(true);
		exportCancel = null;
		if (exportDialog != null && exportDialog.isShowing())
			exportDialog.dismiss();
		driver.release();
		reader.close();
	}

	static boolean isGapNotice(RecordingReader.EventRow e)
	{
		return EventDeriver.GAP.equals(e.type) || EventDeriver.REC_OFF.equals(e.type);
	}

	/** Tell the watcher when playback runs over a stretch that wasn't recorded. */
	private void noteGapsCrossed(int pos)
	{
		int from = lastNotifiedPos;
		lastNotifiedPos = pos;
		if (from < 0 || pos <= from)
			return;
		ReplayTimeline tl = controller.timeline();
		for (int i = 0; i < tl.events().size(); i++)
		{
			int p = tl.eventPosition(i);
			RecordingReader.EventRow e = tl.events().get(i);
			if (p > from && p <= pos && isGapNotice(e))
			{
				showGapNote(e.label);
				return;
			}
		}
	}

	private void showGapNote(String text)
	{
		if (controls == null)
			return;
		gapNote = text;
		controls.removeCallbacks(clearGapNote);
		controls.postDelayed(clearGapNote, GAP_NOTE_MS);
		if (cursor != null || screens != null)
			return;
		SpannableString sp = new SpannableString(text);
		sp.setSpan(new ForegroundColorSpan(0xFFFF5050), 0, sp.length(),
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		controls.setStatus(sp);
	}

	private void clearGapNote()
	{
		if (gapNote == null)
			return;
		gapNote = null;
		if (controls == null)
			return;
		controls.removeCallbacks(clearGapNote);
		if (cursor == null && screens == null)
			controls.setStatus(statusLine(controller.position(), controller.isPlaying()));
	}

	// ============================================================ transport

	@Override
	public void onPlayPause()
	{
		if (controller.isPlaying())
		{
			driver.pause();
			return;
		}
		leaveBrowsing();
		controller.clock().setDirection(1);
		driver.play();
	}

	@Override
	public void onStep(int dir)
	{
		driver.pause();
		controller.step(STEP_UNITS[stepIdx], dir);
		refreshBrowsingForNewPosition();
	}

	@Override
	public void onSkip(int dir)
	{
		driver.pause();
		switch (skipUnit)
		{
		case EVENT:
			// Same events the timeline ticks, so skips land on visible marks.
			int p = controls == null ? -1 : controls.timeline().nextTick(controller.position(), dir);
			if (p >= 0)
				controller.seek(p);
			break;
		case LEVEL:
			controller.skipEvent(dir, Collections.singleton(EventDeriver.LEVEL));
			break;
		case TURNS_10:
			controller.skipTurns(10 * dir);
			break;
		case TURNS_100:
			controller.skipTurns(100 * dir);
			break;
		case TURNS_1000:
			controller.skipTurns(1000 * dir);
			break;
		}
		refreshBrowsingForNewPosition();
	}

	@Override
	public void onSpeed()
	{
		CharSequence[] items = new CharSequence[ReplayClock.SPEEDS.length];
		for (int i = 0; i < items.length; i++)
			items[i] = speedLabel(i);
		new AlertDialog.Builder(ctx)
				.setTitle("Playback speed")
				.setSingleChoiceItems(items, speedIdx, (d, which) ->
				{
					speedIdx = which;
					controller.setSpeed(ReplayClock.SPEEDS[speedIdx]);
					updateLabels();
					d.dismiss();
				})
				.show();
	}

	@Override
	public void onStepUnit()
	{
		stepIdx = (stepIdx + 1) % STEP_UNITS.length;
		updateLabels();
	}

	@Override
	public void onSkipUnit()
	{
		skipUnit = SkipUnit.values()[(skipUnit.ordinal() + 1) % SkipUnit.values().length];
		updateLabels();
	}

	@Override
	public void onClockMode()
	{
		ReplayClock.Mode m = controller.clock().mode() == ReplayClock.Mode.AS_PLAYED
				? ReplayClock.Mode.TURN_TIME : ReplayClock.Mode.AS_PLAYED;
		controller.setClockMode(m);
		updateLabels();
	}

	@Override
	public void onFilter()
	{
		CharSequence[] items = new CharSequence[TimelineBar.TICK_LABELS.length];
		for (int t = 0; t < items.length; t++)
		{
			SpannableString sp = new SpannableString("\u25A0  " + TimelineBar.TICK_LABELS[t]);
			sp.setSpan(new ForegroundColorSpan(TimelineBar.tickColour(t)), 0, 1,
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			items[t] = sp;
		}
		new AlertDialog.Builder(ctx)
				.setTitle("Events")
				.setMultiChoiceItems(items, ticksShown, (d, which, checked) ->
				{
					ticksShown[which] = checked;
					if (controls != null)
						controls.timeline().setTickTypesShown(ticksShown);
				})
				.setPositiveButton("Done", null)
				.show();
	}

	@Override
	public void onScrubStart()
	{
		wasPlayingBeforeScrub = controller.isPlaying();
		driver.pause();
	}

	@Override
	public void onScrub(int pos, boolean fromUser, String gap)
	{
		if (!fromUser)
			return;
		controller.seek(pos);
		if (gap != null)
			showGapNote(gap);
		else
			clearGapNote();
	}

	@Override
	public void onRangeChanged(boolean on, int lo, int hi)
	{
		rangeOn = on;
		rangeLo = lo;
		rangeHi = hi;
		if (controls != null)
		{
			int[] w = controls.windowPositions();
			controller.setPlayBounds(w[0], w[1]);
		}
		// Keep the playhead inside the new window, as video editors do. Not
		// mid-rebuild (drawnPos -1): the renderer can't draw yet.
		if (!on || !started || drawnPos < 0 || controls == null)
			return;
		int[] w = controls.windowPositions();
		int p = controller.position();
		int to = Math.max(w[0], Math.min(w[1], p));
		if (to != p)
		{
			controller.seek(to);
			refreshBrowsingForNewPosition();
		}
	}

	@Override
	public void onLevelTrim()
	{
		if (controls == null)
			return;
		if (trimOn)
		{
			trimOn = false;
			controls.setTrim(false, 0, 0);
			controls.setRange(rangeOn, rangeLo, rangeHi);
			return;
		}
		final List<RecordingReader.SnapshotRow> rows = controller.resolver().rows();
		int minXl = 0, maxXl = 0;
		for (RecordingReader.SnapshotRow row : rows)
			if (row.xl > 0)
			{
				if (minXl == 0)
					minXl = row.xl;
				maxXl = Math.max(maxXl, row.xl);
			}
		if (maxXl <= minXl)
		{
			host.toast("This recording covers only one experience level.");
			return;
		}
		final int base = minXl, top = maxXl;
		float density = ctx.getResources().getDisplayMetrics().density;
		final RangeBar bar = new RangeBar(ctx);
		bar.setMinSpan(0);
		bar.setLabelEnds(true);
		bar.setLabeler(u -> String.valueOf(base + u));
		// Last trim's levels, so re-enabling doesn't start over.
		int lo = trimXlLo > 0 ? trimXlLo - base : 0;
		int hi = trimXlHi > 0 ? trimXlHi - base : maxXl - base;
		bar.setRange(maxXl - base + 1, 0, -1, lo, hi);
		// Room for the carets' half-width and their touch slop at both ends.
		int inset = Math.round(20 * density);
		bar.setPadding(inset, 0, inset, 0);
		LinearLayout box = new LinearLayout(ctx);
		int pad = Math.round(24 * density);
		box.setPadding(pad - inset, pad / 2, pad - inset, 0);
		box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
				Math.round(30 * density)));
		final AlertDialog dlg = new AlertDialog.Builder(ctx)
				.setTitle("Trim to levels")
				.setMessage(trimLabel(rows, base + bar.lo(), base + bar.hi()))
				.setView(box)
				.setNegativeButton("Cancel", null)
				.setPositiveButton("Trim", (d, x) -> applyLevelTrim(rows, base + bar.lo(), base + bar.hi()))
				.setNeutralButton("Reset", null)
				.show();
		if (dlg.getWindow() != null)
		{
			// The theme's background insets 16dp per side; thin it so the slider gets the width.
			if (dlg.getWindow().getDecorView().getBackground() instanceof InsetDrawable)
				dlg.getWindow().setBackgroundDrawable(new InsetDrawable(((InsetDrawable)
						dlg.getWindow().getDecorView().getBackground()).getDrawable(),
						Math.round(4 * density)));
			dlg.getWindow().setLayout(controls.getWidth(), WindowManager.LayoutParams.WRAP_CONTENT);
		}
		bar.setListener((l, h) -> dlg.setMessage(trimLabel(rows, base + l, base + h)));
		// Set after show() so tapping it doesn't dismiss the dialog.
		dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
		{
			bar.setRange(top - base + 1, 0, -1, 0, top - base);
			dlg.setMessage(trimLabel(rows, base, top));
		});
	}

	private String trimLabel(List<RecordingReader.SnapshotRow> rows, int a, int b)
	{
		String xl = a == b ? "XL " + a : "XL " + a + " – " + b;
		ReplayTimeline tl = controller.timeline();
		int[] span = levelSpan(rows, a, b);
		return span == null ? xl : xl + "\n" + spanLabel(tl.turn(span[0]), tl.turn(span[1]));
	}

	/** Positions from reaching XL a until just before passing b, or null. */
	private int[] levelSpan(List<RecordingReader.SnapshotRow> rows, int a, int b)
	{
		ReplayTimeline tl = controller.timeline();
		if (tl.size() == 0)
			return null;
		int from = -1, to = tl.size() - 1, xl = 0;
		for (RecordingReader.SnapshotRow row : rows)
		{
			if (row.xl <= xl)
				continue;
			int p = Math.max(0, tl.posOfFrame(row.frame));
			// The starting XL keeps the menus before it.
			if (from < 0 && row.xl >= a)
				from = xl == 0 ? 0 : p;
			xl = row.xl;
			if (xl > b)
			{
				to = p - 1;
				break;
			}
		}
		return from < 0 ? null : new int[] { from, Math.max(from, to) };
	}

	private void applyLevelTrim(List<RecordingReader.SnapshotRow> rows, int a, int b)
	{
		int[] span = levelSpan(rows, a, b);
		if (controls == null || span == null)
			return;
		trimOn = true;
		trimXlLo = a;
		trimXlHi = b;
		trimFrom = span[0];
		trimTo = span[1];
		controls.setTrim(true, trimFrom, trimTo);
		controls.setRange(rangeOn, 0, -1);
	}

	@Override
	public void onClip()
	{
		driver.pause();
		ReplayTimeline tl = controller.timeline();
		int[] w = controls == null ? null : controls.windowPositions();
		if (w == null || tl.size() == 0 || w[1] <= w[0])
			return;
		final double speed = ReplayClock.SPEEDS[speedIdx];
		final ReplayClock.Mode mode = controller.clock().mode();
		long s = ReplayGifEncoder.durationMs(tl, w[0], w[1], mode, speed) / 1000;
		final String span = spanLabel(tl.turn(w[0]), tl.turn(w[1]));
		final String info = span + "\n" + String.format(Locale.US, "%d:%02d", s / 60, s % 60)
				+ " at " + speedLabel(speedIdx) + ", " + clockLabel().toLowerCase(Locale.US);
		final int fromFrame = tl.frame(w[0]), toFrame = tl.frame(w[1]);
		// Menu-only stretches (turn < 0) count as turn 0.
		int t0 = Math.max(0, tl.turn(w[0])), t1 = Math.max(0, tl.turn(w[1]));
		GameIdentity id = SqliteRecordingStore.identity(host.recordingFile());
		final String base = id != null ? id.clipName(t0, t1)
				: host.recordingFile().getName().replace(".dcrec", "") + "_" + t0 + "-" + t1;
		new AlertDialog.Builder(ctx)
				.setTitle("Clip as")
				.setItems(new String[] { "Animated GIF (.gif)", "Video (.mp4)" }, (d, which) ->
				{
					final boolean video = which == 1;
					final String name = base + (video ? ".mp4" : ".gif");
					host.pickClipDestination(name, video ? "video/mp4" : "image/gif",
							dst -> encodeClip(video, fromFrame, toFrame, mode, speed, info,
									name, dst));
				})
				.show();
	}

	private static String spanLabel(int from, int to)
	{
		if (to < 0)
			return "Menus";
		return from < 0 ? "Menus to turn " + to : "Turns " + from + "\u2013" + to;
	}

	// Encoded to a temp file, which the host copies to the picked destination.
	private void encodeClip(final boolean video, final int fromFrame, final int toFrame,
			final ReplayClock.Mode mode, final double speed, String info, String name,
			final Uri dst)
	{
		final String what = video ? "Video" : "GIF";
		final File src = host.recordingFile();
		File dir = Recordings.exportsDir(ctx);
		dir.mkdirs();
		final File out = new File(dir, name);
		final int back = controller.position();
		Runnable run = () -> runExport(video ? "Creating video…" : "Creating GIF…", info,
				(frames, p) ->
				{
					if (video)
						ReplayVideoEncoder.encode(src, fromFrame, toFrame, mode, speed, frames,
								out, p);
					else
						ReplayGifEncoder.encode(src, fromFrame, toFrame, mode, speed, frames,
								out, p);
				},
				(ok, err) ->
				{
					controller.seek(back);
					if (!ok)
					{
						out.delete();
						host.toast(err == null ? what + " cancelled"
								: what + " export failed: " + err);
					}
					else
						host.saveClip(out, dst, what);
				});
		// Returning from the picker rebuilt the views; they aren't measured yet.
		View panel = host.gamePanel();
		if (panel == null || panel.getWidth() == 0)
			pendingExport = run;
		else
			run.run();
	}

	/**
	 * Plays the whole recording into an MP4 at out once the views are first
	 * drawn (call before onRendererReady). done runs unless the replay is
	 * released first.
	 */
	public void exportVideo(final double speed, final File out, String title,
			final ExportDone done)
	{
		final File src = host.recordingFile();
		pendingExport = () -> runExport(title, null,
				(frames, p) -> ReplayVideoEncoder.encode(src, out, speed, frames, p),
				done);
	}

	// Frames come from these views, so the screen can't be touched meanwhile:
	// a non-cancelable progress dialog covers it.
	private void runExport(String title, String info, final ExportJob job,
			final ExportDone done)
	{
		driver.pause();
		leaveBrowsing();
		final Activity act = (Activity) ctx;
		View panel = host.gamePanel();
		if (panel == null || panel.getWidth() == 0)
		{
			done.done(false, "the screen isn't ready");
			return;
		}
		final AtomicBoolean cancel = new AtomicBoolean();
		exportCancel = cancel;
		final ViewCapture frames = new ViewCapture(new ViewCapture.Source()
		{
			@Override
			public View panel()
			{
				return host.gamePanel();
			}

			@Override
			public void seek(int pos)
			{
				controller.seek(pos);
			}

			@Override
			public boolean drawn(int pos)
			{
				return drawnPos == pos;
			}

			@Override
			public void repaint()
			{
				host.repaint();
			}
		}, panel, cancel);
		final ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
		bar.setMax(1000);
		int pad = Math.round(24 * ctx.getResources().getDisplayMetrics().density);
		bar.setPadding(pad, pad, pad, pad);
		final AlertDialog dlg = new AlertDialog.Builder(ctx)
				.setTitle(title)
				.setMessage(info)
				.setView(bar)
				.setCancelable(false)
				.setNegativeButton("Cancel", (d, x) -> cancel.set(true))
				.show();
		exportDialog = dlg;
		act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		final Handler main = new Handler(Looper.getMainLooper());
		new Thread(() ->
		{
			String error = null;
			try
			{
				job.run(frames, new ReplayVideoEncoder.Progress()
				{
					@Override
					public void update(int done, int total)
					{
						int p = (int) (1000L * done / Math.max(1, total));
						main.post(() -> bar.setProgress(p));
					}

					@Override
					public boolean cancelled()
					{
						return cancel.get();
					}
				});
			}
			catch (IOException | RuntimeException e)
			{
				error = e.getMessage();
			}
			final String err = error;
			main.post(() ->
			{
				// Released (replay left, activity destroyed): nothing to report to.
				if (exportCancel != cancel)
					return;
				exportCancel = null;
				exportDialog = null;
				act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
				dlg.dismiss();
				boolean ok = !cancel.get() && err == null;
				done.done(ok, ok || cancel.get() ? null : String.valueOf(err));
			});
		}, "dcrec-export").start();
	}

	@Override
	public void onScrubEnd()
	{
		if (wasPlayingBeforeScrub)
			driver.play();
	}

	// ============================================================ screens

	@Override
	public void onScreen(ReplayScreens.Kind kind)
	{
		if (!ReplayReadOnly.screensEnabled(controller.isPlaying()))
			return;
		KnowledgeResolver.Knowledge k = controller.knowledge();
		if (k == null)
		{
			host.toast("Nothing recorded yet at this point.");
			return;
		}
		screens = newScreens(k);
		screens.open(kind);
		screenKind = kind;
		updateBrowsing();
	}

	@Override
	public void toast(String msg)
	{
		host.toast(msg);
	}

	private ReplayScreens newScreens(KnowledgeResolver.Knowledge k)
	{
		KnowledgeResolver r = controller.resolver();
		ReplayScreens rs = new ReplayScreens(this, r::blob, k);
		// Wrap describe pages like the game does (0 when wordwrap is off).
		rs.setProseCols(renderer == null ? 0 : renderer.getProseWrapCols());
		return rs;
	}

	/** Crawl keyboard (and hardware keys) while a screen is open. */
	private void onBrowseKey(int c)
	{
		if (screens == null || !screens.isOpen())
			return;
		// The default layout has no Esc key: Del backs out instead.
		screens.onKey(c == 8 ? ReplayScreens.KEY_ESCAPE : c);
		updateBrowsing();
	}

	private void closeScreens()
	{
		screens = null;
		screenKind = null;
		updateBrowsing();
	}

	/** Sync overlay, controls and examine pad with the screen/cursor state. */
	private void updateBrowsing()
	{
		if (screens != null && !screens.isOpen())
		{
			screens = null;
			screenKind = null;
		}
		boolean browsing = screens != null;
		if (controls != null)
		{
			controls.setBrowseMode(browsing);
			if (browsing)
				controls.setStatus("Replay \u2013 read only \u00B7 Back closes");
			else if (cursor != null)
				controls.setStatus(cursor.summary());
			else
				controls.setStatus(statusLine(controller.position(), controller.isPlaying()));
		}
		if (pad != null)
			pad.setVisibility(browsing ? View.GONE : View.VISIBLE);
		controller.setOverlay(browsing || cursor != null ? this::compose : null);
	}

	private ScreenState compose(ScreenState recorded)
	{
		if (screens != null && screens.isOpen())
			return screens.apply(recorded);
		return cursor != null ? cursor.overlay(recorded) : recorded;
	}

	// ============================================================ examine

	@Override
	public void onExamine()
	{
		if (!ReplayReadOnly.screensEnabled(controller.isPlaying()))
			return;
		KnowledgeResolver.Knowledge k = controller.knowledge();
		if (k == null)
		{
			host.toast("Nothing recorded yet at this point.");
			return;
		}
		cursor = new ExamineCursor(k, controller.resolver());
		controls.setExamineMode(true);
		showPad();
		updateBrowsing();
	}

	/** Touch directions over the map (no centre action, hold repeats). */
	private void showPad()
	{
		if (pad != null || screenLayout == null || controls == null)
			return;
		pad = new ReplayDirectionPad(ctx, this::onExamineMove);
		RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
				RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
		lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
		// Unfolded with controls on one half: the map fills the other half.
		if (confined)
			lp.addRule(confinedLeft ? RelativeLayout.RIGHT_OF : RelativeLayout.LEFT_OF, controls.getId());
		else
			lp.addRule(RelativeLayout.ABOVE, controls.getId());
		screenLayout.addView(pad, lp);
	}

	private void hidePad()
	{
		if (pad != null && pad.getParent() instanceof android.view.ViewGroup)
			((android.view.ViewGroup) pad.getParent()).removeView(pad);
		pad = null;
	}

	@Override
	public void onExamineMove(int dx, int dy)
	{
		if (cursor == null || !cursor.move(dx, dy))
			return;
		controller.refresh();
		controls.setStatus(cursor.summary());
	}

	@Override
	public void onExamineHome()
	{
		if (cursor == null)
			return;
		cursor.reset();
		controller.refresh();
		controls.setStatus(cursor.summary());
	}

	@Override
	public void onExamineDescribe()
	{
		if (cursor == null)
			return;
		KnowledgeResolver.Knowledge k = controller.knowledge();
		if (k == null)
			return;
		screens = newScreens(k);
		java.util.List<String[]> targets = cursor.targets();
		if (targets.size() == 1)
			screens.openDescription(targets.get(0)[0], targets.get(0)[1]);
		else
			screens.openChoice(targets, cursor.hasUnseenItems());
		screenKind = null;
		updateBrowsing();
	}

	@Override
	public void onExamineDone()
	{
		cursor = null;
		hidePad();
		if (controls != null)
			controls.setExamineMode(false);
		updateBrowsing();
		updateLabels();
	}

	/** Leaving pause: close screens and examine (they're paused-only). */
	private void leaveBrowsing()
	{
		if (screens != null)
			closeScreens();
		if (cursor != null)
			onExamineDone();
	}

	/** Stepping while paused keeps screens/examine but refreshes them. */
	private void refreshBrowsingForNewPosition()
	{
		KnowledgeResolver.Knowledge k = controller.knowledge();
		if (cursor != null && k != null)
		{
			int x = cursor.x(), y = cursor.y();
			cursor = new ExamineCursor(k, controller.resolver());
			cursor.move(x - cursor.x(), y - cursor.y());
		}
		// A top-level screen re-opens at the new position; sub-pages close.
		if (screens != null)
		{
			screens = screenKind == null || k == null ? null : newScreens(k);
			if (screens != null)
				screens.open(screenKind);
			else
				screenKind = null;
		}
		updateBrowsing();
	}

	// ============================================================ status

	@Override
	public void onPositionChanged(int pos, boolean playing)
	{
		if (controls == null)
			return;
		noteGapsCrossed(pos);
		controls.setPosition(pos);
		controls.setPlaying(playing);
		if (cursor == null && screens == null && gapNote == null)
			controls.setStatus(statusLine(pos, playing));
	}

	private CharSequence statusLine(int pos, boolean playing)
	{
		ReplayTimeline tl = controller.timeline();
		if (tl.size() == 0)
			return "Empty recording";
		int turn = tl.turn(pos);
		StringBuilder b = new StringBuilder();
		b.append(turn >= 0 ? "Turn " + turn : "Menus");
		b.append(" \u00B7 Input ").append(tl.inputCount(pos));
		int row = controller.resolver().rowAt(tl.frame(pos), tl.aut(pos));
		if (row >= 0)
		{
			RecordingReader.SnapshotRow r = controller.resolver().rows().get(row);
			b.append(" \u00B7 ").append(r.level);
		}
		b.append(playing ? " \u00B7 Playing" : " \u00B7 Paused");
		return b.toString();
	}

	private void updateLabels()
	{
		if (controls == null)
			return;
		String step = "Step: " + STEP_UNITS[stepIdx].name().toLowerCase();
		String skip;
		switch (skipUnit)
		{
		case LEVEL: skip = "Skip: level"; break;
		case EVENT: skip = "Skip: event"; break;
		case TURNS_100: skip = "Skip: 100 turns"; break;
		case TURNS_1000: skip = "Skip: 1000 turns"; break;
		default: skip = "Skip: 10 turns"; break;
		}
		controls.setLabels(speedLabel(speedIdx), step, skip, clockLabel());
		controls.setPlaying(controller.isPlaying());
	}

	private static String speedLabel(int i)
	{
		double s = ReplayClock.SPEEDS[i];
		return s >= 64 ? "Max" : (s < 1 ? "\u00BDx" : ((int) s) + "x");
	}

	private String clockLabel()
	{
		return controller.clock().mode() == ReplayClock.Mode.AS_PLAYED ? "As played" : "Turn time";
	}

	// ============================================================ keys

	/** Hardware/system keys. Returns true if consumed. */
	public boolean onKeyDown(int keyCode, KeyEvent event)
	{
		int c = event.getUnicodeChar();
		boolean playing = controller.isPlaying();

		if (screens != null)
		{
			if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
				onBrowseKey(ReplayScreens.KEY_ESCAPE);
			else if (keyCode == KeyEvent.KEYCODE_ENTER)
				onBrowseKey(13);
			else if (c != 0)
				onBrowseKey(c);
			return true; // screens own the keyboard
		}

		if (cursor != null)
		{
			int[] d = direction(keyCode, c);
			if (d != null) { onExamineMove(d[0], d[1]); return true; }
			if (c == 'v' || keyCode == KeyEvent.KEYCODE_ENTER) { onExamineDescribe(); return true; }
			if (c == '@' || c == '.') { onExamineHome(); return true; }
			if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE || c == 'x')
			{
				onExamineDone();
				return true;
			}
			return true;
		}

		if (keyCode == KeyEvent.KEYCODE_BACK)
		{
			host.exitReplay();
			return true;
		}
		if (c == ' ' || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { onPlayPause(); return true; }
		if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { onStep(-1); return true; }
		if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { onStep(1); return true; }
		if (playing)
			return false; // browse keys are paused-only
		switch (c)
		{
		case 'i': onScreen(ReplayScreens.Kind.INVENTORY); return true;
		case 'I': onScreen(ReplayScreens.Kind.SPELLS); return true;
		case 'a': onScreen(ReplayScreens.Kind.ABILITIES); return true;
		case '%':
		case 'C': onScreen(ReplayScreens.Kind.CHARACTER); return true;
		case 'm': onScreen(ReplayScreens.Kind.TRAINING); return true;
		case 'x': onExamine(); return true;
		default:
			// Any other game command on a recording is an edit attempt.
			if (Character.isLetter(c))
			{
				host.toast(ReplayReadOnly.MESSAGE);
				return true;
			}
			return false;
		}
	}

	/** Arrow keys, numpad and vi-keys (hjklyubn), like DCSS targeting. */
	private static int[] direction(int keyCode, int c)
	{
		switch (keyCode)
		{
		case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_NUMPAD_4: return new int[] { -1, 0 };
		case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_NUMPAD_6: return new int[] { 1, 0 };
		case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_NUMPAD_8: return new int[] { 0, -1 };
		case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_NUMPAD_2: return new int[] { 0, 1 };
		case KeyEvent.KEYCODE_NUMPAD_7: return new int[] { -1, -1 };
		case KeyEvent.KEYCODE_NUMPAD_9: return new int[] { 1, -1 };
		case KeyEvent.KEYCODE_NUMPAD_1: return new int[] { -1, 1 };
		case KeyEvent.KEYCODE_NUMPAD_3: return new int[] { 1, 1 };
		default: break;
		}
		switch (c)
		{
		case 'h': return new int[] { -1, 0 };
		case 'l': return new int[] { 1, 0 };
		case 'k': return new int[] { 0, -1 };
		case 'j': return new int[] { 0, 1 };
		case 'y': return new int[] { -1, -1 };
		case 'u': return new int[] { 1, -1 };
		case 'b': return new int[] { -1, 1 };
		case 'n': return new int[] { 1, 1 };
		case 'H': return new int[] { -8, 0 };
		case 'L': return new int[] { 8, 0 };
		case 'K': return new int[] { 0, -8 };
		case 'J': return new int[] { 0, 8 };
		default: return null;
		}
	}
}
