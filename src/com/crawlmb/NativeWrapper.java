package com.crawlmb;

import com.crawlmb.keylistener.GameKeyListener;
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

	private final String display_lock = "lock";
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
		setWordwrap(renderer.getMsgWrapCols(), renderer.getMsgRows(),
				renderer.getProseWrapCols());
		initGame(dataDir, settingsDir, morgueDir);
	}

	public native void initGame(String dataDir, String settingsDir, String morgueDir);
	private native void setWordwrap(int msgWrapCols, int msgRows, int proseWrapCols);
	public static native void nativeSaveGame();

	public NativeWrapper(GameKeyListener s)
	{
		keyListener = s;
	}

	public int getch(final int v)
	{
		keyListener.gameThread.setFullyInitialized();
		int key = keyListener.getKey(v);
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
		CustomFolderSync.pushSaveFileAsync(ctx, new java.io.File(savePath));
	}

	private native void refreshTerminal();

	public boolean onGameStart()
	{
		// Log.d(TAG, "onGameStart()");
		synchronized (display_lock)
		{
			return renderer.onGameStart();
		}
	}

	public void increaseFontSize()
	{
		// Log.d(TAG, "increaseFontSzie()");
		synchronized (display_lock)
		{
			renderer.increaseFontSize();
			resize();
		}
	}

	public void link(TerminalRenderer r)
	{
		synchronized (display_lock)
		{
			renderer = r;
		}
	}

	public void decreaseFontSize()
	{
		// Log.d(TAG, "decreaseFontSize()");
		synchronized (display_lock)
		{
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
			renderer.onGameStart();
			refreshTerminal();
		}
	}

	// Called from libandroid.cc (_send_frame): one call per dirty storm,
	// carrying the whole 48x80 terminal grid (row-major chars + ARGB fg/bg).
	// Runs on the game thread (sendTerminalToScreen) or the UI thread
	// (refreshTerminal). display_lock is held for the entire frame — diff,
	// classification, routing and painting all see one consistent grid.
	public void frameUpdate(char[] chars, int[] fg, int[] bg)
	{
		synchronized (display_lock)
		{
			if (renderer != null)
				renderer.onFrame(chars, fg, bg);
		}
	}

	public void updateStatusLights(String texts, int[] colours)
	{
		synchronized (display_lock)
		{
			if (renderer != null)
				renderer.updateStatusLights(texts, colours);
		}
	}

	// Called from libandroid.cc at entry and exit of the Ctrl+P / startup
	// message history popup. Forwarded to the renderer so RegionRouter can
	// classify the popup as MenuType.MESSAGES and widen fullView's region
	// to the full 48-row terminal.
	public void setMessageHistoryMode(boolean active)
	{
		synchronized (display_lock)
		{
			if (renderer != null)
				renderer.setMessageHistoryMode(active);
		}
	}

	// Called from libandroid.cc at entry and exit of the character-log
	// popup opened from the High Scores menu. Forwarded to the renderer so
	// RegionRouter can classify the popup as MenuType.MORGUE and hold that
	// classification while the user scrolls through the morgue text.
	public void setCharacterLogMode(boolean active)
	{
		synchronized (display_lock)
		{
			if (renderer != null)
				renderer.setCharacterLogMode(active);
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

}
