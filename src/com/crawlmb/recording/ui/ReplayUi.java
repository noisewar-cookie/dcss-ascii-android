package com.crawlmb.recording.ui;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.RelativeLayout;

import com.crawlmb.keyboard.CrawlKeyboardWrapper;
import com.crawlmb.keylistener.KeyListener;
import com.crawlmb.recording.ExamineCursor;
import com.crawlmb.recording.EventDeriver;
import com.crawlmb.recording.KnowledgeResolver;
import com.crawlmb.recording.RecordingReader;
import com.crawlmb.recording.ReplayClock;
import com.crawlmb.recording.ReplayController;
import com.crawlmb.recording.ReplayDriver;
import com.crawlmb.recording.ReplayReadOnly;
import com.crawlmb.recording.ReplayRendererAdapter;
import com.crawlmb.recording.ReplayTimeline;
import com.crawlmb.recording.ScreenState;
import com.crawlmb.view.TerminalRenderer;

import java.util.Collections;

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
	}

	private enum SkipUnit { TURNS_10, TURNS_100, EVENT, LEVEL }

	private static final ReplayTimeline.Unit[] STEP_UNITS = { ReplayTimeline.Unit.TURN,
			ReplayTimeline.Unit.INPUT, ReplayTimeline.Unit.FRAME };

	private final Context ctx;
	private final Host host;
	private final RecordingReader reader;
	private final ReplayController controller;
	private final ReplayDriver driver;
	private ReplayRendererAdapter adapter;

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
	private int speedIdx = 2; // 1x
	private int stepIdx = 0;
	private SkipUnit skipUnit = SkipUnit.TURNS_10;
	private boolean wasPlayingBeforeScrub;
	private boolean started;
	private int lastNotifiedPos = -1;

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
						if (adapter != null)
							adapter.render(s, pos);
					}

					@Override
					public void onPositionChanged(int pos, boolean playing)
					{
						ReplayUi.this.onPositionChanged(pos, playing);
					}
				});
		this.driver = new ReplayDriver(controller);
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
		adapter = new ReplayRendererAdapter(renderer, this);
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
		if (started)
		{
			controller.refresh();
			return;
		}
		started = true;
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
				host.toast(e.label);
				return;
			}
		}
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
		}
		refreshBrowsingForNewPosition();
	}

	@Override
	public void onSpeed()
	{
		speedIdx = (speedIdx + 1) % ReplayClock.SPEEDS.length;
		controller.setSpeed(ReplayClock.SPEEDS[speedIdx]);
		updateLabels();
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
	public void onScrubStart()
	{
		wasPlayingBeforeScrub = controller.isPlaying();
		driver.pause();
	}

	@Override
	public void onScrub(int pos, boolean fromUser)
	{
		if (fromUser)
			controller.seek(pos);
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
		if (cursor == null && screens == null)
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
		int row = controller.resolver().rowAt(tl.frame(pos), tl.aut(pos));
		if (row >= 0)
		{
			RecordingReader.SnapshotRow r = controller.resolver().rows().get(row);
			b.append(" \u00B7 ").append(r.level).append(" \u00B7 XL ").append(r.xl)
					.append(" \u00B7 HP ").append(r.hp).append('/').append(r.hpMax);
		}
		b.append(playing ? " \u00B7 Playing" : " \u00B7 Paused");
		return b.toString();
	}

	private void updateLabels()
	{
		if (controls == null)
			return;
		double s = ReplayClock.SPEEDS[speedIdx];
		String speed = s >= 64 ? "Max" : (s < 1 ? (s == 0.25 ? "\u00BCx" : "\u00BDx") : ((int) s) + "x");
		String step = "Step: " + STEP_UNITS[stepIdx].name().toLowerCase();
		String skip;
		switch (skipUnit)
		{
		case LEVEL: skip = "Skip: level"; break;
		case EVENT: skip = "Skip: event"; break;
		case TURNS_100: skip = "Skip: 100 turns"; break;
		default: skip = "Skip: 10 turns"; break;
		}
		String clock = controller.clock().mode() == ReplayClock.Mode.AS_PLAYED ? "As played" : "Turn time";
		controls.setLabels(speed, step, skip, clock);
		controls.setPlaying(controller.isPlaying());
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
