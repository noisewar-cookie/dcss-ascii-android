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
		initGame(dataDir, settingsDir, morgueDir);
	}

	public native void initGame(String dataDir, String settingsDir, String morgueDir);
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

	// Called from native save_game() on Save & Quit paths where onPause
	// won't fire (activity stays foreground at char-select).
	public void notifyGameSaved()
	{
		if (renderer == null)
			return;
		android.content.Context ctx = renderer.getContext();
		if (ctx == null)
			return;
		CustomFolderSync.pushAsync(ctx);
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

	public void printTerminalChar(int y, int x, char c, int fgcolor, int bgcolor)
	{
		synchronized (display_lock)
		{
			renderer.drawPoint(y, x, c, fgcolor, bgcolor, false);
		}
	}

	public void invalidateTerminal()
	{
		synchronized (display_lock)
		{
			renderer.postInvalidate();
		}
	}

	// Called from libandroid.cc at the start of each dirty-cell storm.
	// isGameplay reflects whether the new terminal grid (already populated
	// on the C++ side) shows a gameplay HUD anchor. Forwarded to the
	// renderer so it can preemptively adjust drawPoint routing before the
	// storm flushes cells.
	public void preStormHint(boolean isGameplay)
	{
		synchronized (display_lock)
		{
			if (renderer != null)
				renderer.preStormHint(isGameplay);
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

	// Ask DCSS to repaint the current screen state. Used after a font scale
	// change recreates the underlying bitmap (which is then blank): DCSS
	// re-issues drawPoint calls for every cell, refilling the new bitmap.
	// Distinct from resize() which also re-runs onGameStart and resets
	// detection state.
	public void redrawScreen()
	{
		synchronized (display_lock)
		{
			refreshTerminal();
		}
	}

}
