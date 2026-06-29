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
		String dataDir = ctx.getFilesDir().getPath();
		String settingsDir = Paths.getSettingsDir(ctx).getPath();
		String morgueDir = Paths.getMorgueDir(ctx).getPath();
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

	// this is called from native thread just before exiting
	public void onGameExit()
	{
		keyListener.handler.sendEmptyMessage(CrawlDialog.Action.OnGameExit.ordinal());
		// Log.d(TAG, "onGameExit()");
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
