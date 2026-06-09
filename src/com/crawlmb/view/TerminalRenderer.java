package com.crawlmb.view;

import android.content.Context;
import android.content.res.Resources;

public interface TerminalRenderer
{
	boolean onGameStart();
	void drawPoint(int r, int c, char ch, int fcolor, int bcolor, boolean extendedErase);
	void postInvalidate();
	void increaseFontSize();
	void decreaseFontSize();
	Context getContext();
	Resources getResources();

	// Called from libandroid.cc at the start of each dirty-cell storm with
	// a flag indicating whether the new frame's terminal grid contains any
	// gameplay HUD anchor. Lets the renderer adjust drawPoint routing
	// before the storm begins (e.g. to skip forwarding to the gameplay
	// split panels on a gameplay->menu transition). Default is no-op for
	// renderers that don't care (e.g. landscape TermView).
	default void preStormHint(boolean isGameplay) {}

	default void updateStatusLights(String texts, int[] colours) {}
}
