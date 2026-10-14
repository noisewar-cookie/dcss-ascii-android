package com.crawlmb;

import com.crawlmb.keylistener.GameKeyListener;
import com.crawlmb.recording.GameRecorder;
import com.crawlmb.recording.Recordings;
import com.crawlmb.view.TerminalRenderer;

public class NativeWrapper
{
	// Load native library
	static
	{
		System.loadLibrary("crawl");
	}

	private TerminalRenderer renderer = null;
	private GameKeyListener keyListener = null;
	// Last wrap width pushed live, to skip redundant updateMsgWrap calls.
	private int lastMsgWrapCols = -1;
	private int lastProseWrapCols = -1;
	private int lastSkillsWrapCols = -1;

	// Last compact-HUD pushes. These native rows/bars aren't part of the
	// retained terminal grid, so a view rebuild (onStart/fold) leaves them
	// blank until the next output.cc stat push. Cache them here and re-apply
	// on link() so the new views repaint immediately.
	private String lastHudTitleTexts = null;
	private int[] lastHudTitleColours = null;
	private String lastHudVitalsTexts = null;
	private int[] lastHudVitalsColours = null;
	private int[] lastHudHp = null;
	private int[] lastHudMp = null;

	// Game recording (com.crawlmb.recording). Null until gameStart. Every
	// display callback below is teed into it; it never takes display_lock.
	private GameRecorder recorder = null;
	// Pref toggled mid-game: applied on the game thread at the next key.
	private volatile Boolean pendingRecord = null;

	// Serializes all terminal-state access: taken by frameUpdate (game
	// thread) and RegionRouter's UI-thread replay paths.
	public static final Object display_lock = new Object();
	private static final String TAG = NativeWrapper.class.getCanonicalName();

	public void gameStart()
	{
		android.content.Context ctx = renderer.getContext();
		// Active paths resolve to either the live app-private dirs (default)
		// or the custom-staging mirror of the user's SAF folder (custom
		// mode). In custom mode the staging tree is pre-populated by the
		// launch-time pull in SplashActivity, including a dat/ symlink to
		// the live data dir so DCSS finds bundled game content.
		String dataDir = Paths.getActiveDataDir(ctx).getPath();
		String settingsDir = Paths.getActiveSettingsDir(ctx).getPath();
		String morgueDir = Paths.getActiveMorgueDir(ctx).getPath();
		// Word wrap: hand DCSS the wrap width / msg-window rows before boot.
		// gameStart runs after the first layout pass (StartGame fires from
		// onSizeChanged), so the msg panel's font metrics are final here.
		// wrapCols 0 = off; libandroid then keeps stock options.
		int wrapCols = renderer.getMsgWrapCols();
		int msgRows = renderer.getMsgRows();
		int proseCols = renderer.getProseWrapCols();
		setWordwrap(wrapCols, msgRows, proseCols);
		setSkillsWrapCols(renderer.getSkillsWrapCols());
		setNewturnMark(renderer.getNewturnMark());
		setCompactHud(renderer.getCompactHud());
		setMpBarColour(renderer.getMpBarColor());
		recorder = Recordings.recorder(ctx);
		pendingRecord = null;
		applyRecording(Preferences.getRecordGames());
		recorder.onLayout(currentLayout());
		initGame(dataDir, settingsDir, morgueDir);
	}

	// Re-sync DCSS's msg wrap width after a fold/unfold changes the panel width
	// (msg_max_width is otherwise fixed at boot). No-op unless a game is loaded
	// and the width actually changed.
	public void updateMsgWrap()
	{
		if (renderer == null || !gameInProgress())
			return;
		int wrapCols = renderer.getMsgWrapCols();
		int proseCols = renderer.getProseWrapCols();
		int skillsCols = renderer.getSkillsWrapCols();
		boolean msgChanged = wrapCols > 0 && wrapCols != lastMsgWrapCols;
		boolean proseChanged = proseCols > 0 && proseCols != lastProseWrapCols;
		boolean skillsChanged = skillsCols > 0 && skillsCols != lastSkillsWrapCols;
		if (!msgChanged && !proseChanged && !skillsChanged)
			return;
		if (recorder != null)
			recorder.onLayout(currentLayout());
		synchronized (display_lock)
		{
			if (skillsChanged)
			{
				lastSkillsWrapCols = skillsCols;
				setSkillsWrapCols(skillsCols);
			}
			if (msgChanged)
			{
				lastMsgWrapCols = wrapCols;
				setMsgMaxWidthLive(wrapCols);
			}
			if (proseChanged)
			{
				lastProseWrapCols = proseCols;
				setProseWrapColsLive(proseCols);
			}
		}
	}

	public native void initGame(String dataDir, String settingsDir, String morgueDir);
	private native void setWordwrap(int msgWrapCols, int msgRows, int proseWrapCols);
	private native void setNewturnMark(boolean enabled);
	private native void setCompactHud(boolean enabled);
	private native void setMpBarColour(int argb);
	private native void setMsgMaxWidthLive(int msgWrapCols);
	private native void setProseWrapColsLive(int proseWrapCols);
	private native void setSkillsWrapCols(int skillsWrapCols);
	public static native void nativeSaveGame();
	// True while a game is loaded (crawl_state.need_save) — false on the
	// DCSS main menu / character creation. Safe from the UI thread.
	public static native boolean gameInProgress();
	// The '?' command keyhelp as a colour-tagged string, built from live
	// keybindings. Call only while a game is in progress (thread parked in getch).
	public static native String getCommandHelp();
	// Game recording (android-recorder.cc). Call on the game thread or before
	// initGame. getGameIdentity returns UTF-8 JSON bytes.
	public static native void setRecordingEnabled(boolean enabled);
	public static native void recResetSession();
	public static native byte[] getGameIdentity();

	public NativeWrapper(GameKeyListener s)
	{
		keyListener = s;
	}

	public int getch(final int v)
	{
		keyListener.gameThread.setFullyInitialized();
		GameRecorder rec = recorder;
		if (rec != null)
			rec.onInputWait();
		int key = keyListener.getKey(v);
		Boolean p = pendingRecord;
		if (p != null && rec != null)
		{
			pendingRecord = null;
			applyRecording(p);
		}
		if (rec != null)
			rec.onInput(key);
		return key;
	}

	// LEGACY: no native caller since 0.34.1. Don't reactivate — the
	// downstream GameThread.onGameExit clears game_thread_running and
	// auto-posts StartGame, which misfires on Save & Quit.
	public void onGameExit()
	{
		keyListener.handler.sendEmptyMessage(CrawlDialog.Action.OnGameExit.ordinal());
		// Log.d(TAG, "onGameExit()");
	}

	// Native save hook (autosave and Save & Quit). Static: arrives on
	// whichever thread ran the save, with no renderer in hand.
	public static void notifyGameSaved(String savePath)
	{
		android.content.Context ctx = CrawlApplication.getAppContext();
		if (ctx == null || savePath == null)
			return;
		Recordings.onGameSaved();
		CustomFolderSync.pushSaveFileAsync(ctx, new java.io.File(savePath));
	}

	// Push the compact-HUD flag to native after a live settings toggle (no
	// game restart). android_compact_hud is read at print_stats time, so the
	// caller forces a redraw (Ctrl-R) afterward to repaint stats through the
	// now-current HUD branch.
	public void applyCompactHud(boolean enabled)
	{
		setCompactHud(enabled);
		if (recorder != null)
			recorder.onLayout(currentLayout());
	}

	// Live "Record games" toggle from prefs (UI thread). Native recorder state
	// is game-thread only, so it's handed over via getch.
	public void requestRecording(boolean on)
	{
		GameRecorder rec = recorder;
		if (rec != null)
			pendingRecord = rec.isEnabled() != on ? on : null;
	}

	private void applyRecording(boolean on)
	{
		recorder.setEnabled(on);
		setRecordingEnabled(on);
		if (on)
			recResetSession();
	}

	// Layout the game is rendered with, recorded so replay can force it
	// (RecFormat.LAYOUT_* order).
	private int[] currentLayout()
	{
		TerminalRenderer r = renderer;
		if (r == null)
			return new int[7];
		return new int[] { r.getMsgWrapCols(), r.getMsgRows(), r.getProseWrapCols(),
				r.getSkillsWrapCols(), r.getCompactHud() ? 1 : 0, r.getNewturnMark() ? 1 : 0,
				r.getMpBarColor() };
	}

	private native void refreshTerminal();

	public boolean onGameStart()
	{
		// Log.d(TAG, "onGameStart()");
		synchronized (display_lock)
		{
			return renderer != null && renderer.onGameStart();
		}
	}

	public void increaseFontSize()
	{
		// Log.d(TAG, "increaseFontSzie()");
		synchronized (display_lock)
		{
			if (renderer == null) // replay mode: renderer unlinked
				return;
			renderer.increaseFontSize();
			resize();
		}
	}

	public void link(TerminalRenderer r)
	{
		synchronized (display_lock)
		{
			renderer = r;
			// Repaint the compact rows/bars on the freshly built views (they're
			// not in the retained grid that refreshTerminal replays).
			if (r != null)
			{
				if (lastHudTitleTexts != null)
					r.updateHudTitle(lastHudTitleTexts, lastHudTitleColours);
				if (lastHudVitalsTexts != null)
					r.updateHudVitals(lastHudVitalsTexts, lastHudVitalsColours);
				if (lastHudHp != null)
					r.updateHudBars(lastHudHp, lastHudMp);
			}
		}
	}

	public void decreaseFontSize()
	{
		// Log.d(TAG, "decreaseFontSize()");
		synchronized (display_lock)
		{
			if (renderer == null) // replay mode: renderer unlinked
				return;
			renderer.decreaseFontSize();
			resize();
		}
	}

	public void fatal(String msg)
	{
		// Log.d(TAG, "fatal("+msg+")");
		synchronized (display_lock)
		{
			keyListener.fatalMessage = msg;
			keyListener.fatalError = true;
			keyListener.handler.sendMessage(keyListener.handler.obtainMessage(
					CrawlDialog.Action.GameFatalAlert.ordinal(), 0, 0, msg));
		}
	}

	public void resize()
	{
		// Log.d(TAG, "resize()");
		synchronized (display_lock)
		{
			if (renderer == null)
				return;
			renderer.onGameStart();
			refreshTerminal();
		}
	}

	// Called from libandroid.cc (_send_frame): one call per dirty storm,
	// carrying the whole 48x80 terminal grid (row-major chars + ARGB fg/bg).
	// Runs on the game thread (sendTerminalToScreen) or the UI thread
	// (refreshTerminal). display_lock is held for the entire frame — diff,
	// classification, routing and painting all see one consistent grid.
	// turn/aut: game-thread frames carry you.num_turns / you.elapsed_time
	// (-1 outside a game); UI-thread repaints (refreshTerminal) pass -2 and
	// are not recorded.
	public void frameUpdate(char[] chars, int[] fg, int[] bg, int turn, int aut)
	{
		GameRecorder rec = recorder;
		if (rec != null && turn != -2)
			rec.onFrame(chars, fg, bg, turn, aut);
		synchronized (display_lock)
		{
			if (renderer != null)
				renderer.onFrame(chars, fg, bg);
		}
	}

	public void updateStatusLights(String texts, int[] colours)
	{
		if (recorder != null)
			recorder.onStatusLights(texts, colours);
		synchronized (display_lock)
		{
			if (renderer != null)
				renderer.updateStatusLights(texts, colours);
		}
	}

	// Compact HUD native rows (pushed from output.cc). Tab-joined coloured
	// segments for the title/vitals rows; the vitals row is horizontally
	// scrollable when it overflows.
	public void updateHudTitle(String texts, int[] colours)
	{
		if (recorder != null)
			recorder.onHudTitle(texts, colours);
		synchronized (display_lock)
		{
			lastHudTitleTexts = texts;
			lastHudTitleColours = colours;
			if (renderer != null)
				renderer.updateHudTitle(texts, colours);
		}
	}

	public void updateHudVitals(String texts, int[] colours)
	{
		if (recorder != null)
			recorder.onHudVitals(texts, colours);
		synchronized (display_lock)
		{
			lastHudVitalsTexts = texts;
			lastHudVitalsColours = colours;
			if (renderer != null)
				renderer.updateHudVitals(texts, colours);
		}
	}

	// Vertical HP/MP bars. Each array is 8 ints:
	// [def_pm, cur_pm, old_pm, argbDefault, argbChangePos, argbPoison,
	//  argbChangeNeg, argbEmpty]; mp is length 0 for species with no MP.
	public void updateHudBars(int[] hp, int[] mp)
	{
		if (recorder != null)
			recorder.onHudBars(hp, mp);
		synchronized (display_lock)
		{
			lastHudHp = hp;
			lastHudMp = mp;
			if (renderer != null)
				renderer.updateHudBars(hp, mp);
		}
	}

	// Called from libandroid.cc at entry and exit of the Ctrl+P / startup
	// message history popup. Forwarded to the renderer so RegionRouter can
	// classify the popup as MenuType.MESSAGES and widen fullView's region
	// to the full 48-row terminal.
	public void setMessageHistoryMode(boolean active)
	{
		if (recorder != null)
			recorder.onMessageHistoryMode(active);
		synchronized (display_lock)
		{
			if (renderer != null)
				renderer.setMessageHistoryMode(active);
		}
	}

	// Called from libandroid.cc with the whole Ctrl+P log.
	public void setMessageHistory(char[] chars, int[] fg, int[] bg)
	{
		if (recorder != null)
			recorder.onMessageHistory(chars, fg, bg);
		synchronized (display_lock)
		{
			if (renderer != null)
				renderer.setMessageHistory(chars, fg, bg);
		}
	}

	// No display_lock: the native thread can hold it while blocked on keys.
	public boolean consumeMessageLogKey(int key)
	{
		TerminalRenderer r = renderer;
		return r != null && r.handleMessageLogKey(key);
	}

	// Called from libandroid.cc at entry and exit of the character-log
	// popup opened from the High Scores menu. Forwarded to the renderer so
	// RegionRouter can classify the popup as MenuType.MORGUE and hold that
	// classification while the user scrolls through the morgue text.
	public void setCharacterLogMode(boolean active)
	{
		if (recorder != null)
			recorder.onCharacterLogMode(active);
		synchronized (display_lock)
		{
			if (renderer != null)
				renderer.setCharacterLogMode(active);
		}
	}

	// libandroid.cc (viewmap.cc goto_level) on map open / level nav.
	// 1-indexed player cell.
	public void setMapAnchor(int col, int row)
	{
		if (recorder != null)
			recorder.onMapAnchor(col, row);
		synchronized (display_lock)
		{
			if (renderer != null)
				renderer.setMapAnchor(col, row);
		}
	}

	// Called from libandroid.cc (patched _show_morgue) when a High Scores
	// entry is activated: opens the morgue file in the native scrolling
	// viewer (CharFileViewer) instead of the in-terminal scroller, so the
	// whole file is rendered with real vscroll and nothing hides behind
	// the keyboard. Arrives on the game thread — hop to the UI thread to
	// start the activity.
	public void showCharacterFile(String path)
	{
		final android.content.Context ctx;
		synchronized (display_lock)
		{
			ctx = renderer != null ? renderer.getContext() : null;
		}
		if (ctx == null || path == null)
			return;
		final android.content.Intent intent =
				new android.content.Intent(ctx, CharFileViewer.class);
		intent.putExtra(CharFileViewer.EXTRA_FILE_PATH, path);
		new android.os.Handler(android.os.Looper.getMainLooper())
				.post(() -> ctx.startActivity(intent));
	}

	// Ask DCSS to repaint the current screen state. Used after a font scale
	// change recreates the underlying bitmap (which is then blank): the
	// native side re-sends the full frame via frameUpdate, refilling the
	// new bitmap. Distinct from resize() which also re-runs onGameStart and
	// resets detection state.
	public void redrawScreen()
	{
		synchronized (display_lock)
		{
			refreshTerminal();
		}
	}

	// ---- Game recording callbacks from libandroid.cc (game thread) ----

	public void onRecSnapshot(byte[] payload)
	{
		GameRecorder rec = recorder;
		if (rec != null)
			rec.onSnapshot(payload);
	}

	public void onRecMessage(byte[] utf8, int channel, int turn, boolean temporary)
	{
		GameRecorder rec = recorder;
		if (rec != null)
			rec.onMessage(utf8, channel, turn, temporary);
	}

	public void onRecMessageRollback(int count)
	{
		GameRecorder rec = recorder;
		if (rec != null)
			rec.onMessageRollback(count);
	}

	public void onRecGameEnd(int deathType, byte[] reason, byte[] morgueFile)
	{
		GameRecorder rec = recorder;
		if (rec != null && rec.onGameEnd(deathType, reason, morgueFile))
		{
			android.content.Context ctx = CrawlApplication.getAppContext();
			if (ctx != null)
				Recordings.syncFinished(ctx);
		}
	}
}
