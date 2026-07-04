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

	// Called from libandroid.cc (via NativeWrapper) at entry/exit of the
	// Ctrl+P / startup message history popup. RegionRouter uses this to
	// classify the popup as MenuType.MESSAGES so fullView's region widens
	// from rows 0..28 to the full 48-row terminal — otherwise the
	// scroller's FS_START_AT_END places newest messages at rows past 28
	// which the default region clips. Default no-op for renderers that
	// don't care (e.g. landscape TermView).
	default void setMessageHistoryMode(boolean active) {}

	// Called from libandroid.cc (via NativeWrapper) at entry/exit of the
	// character-log popup opened from the High Scores menu. RegionRouter
	// uses this to classify the popup as MenuType.MORGUE and hold that
	// classification while the user scrolls, so morgue text lines don't
	// trip other content anchors (Turns:/Skill/Granted powers:) and cause
	// the font scale to ping-pong. Default no-op for renderers that don't
	// care (e.g. landscape TermView).
	default void setCharacterLogMode(boolean active) {}
}
