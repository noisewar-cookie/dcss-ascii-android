package com.crawlmb.view;

import android.content.Context;
import android.content.res.Resources;

public interface TerminalRenderer
{
	// Terminal grid dimensions of the frame protocol. Must match
	// MENU_LINES / COLS in libandroid.cc.
	int FRAME_ROWS = 48;
	int FRAME_COLS = 80;

	boolean onGameStart();

	// Atomic frame delivery from libandroid.cc via NativeWrapper.frameUpdate:
	// the full FRAME_ROWS x FRAME_COLS grid (row-major, chars + ARGB fg/bg),
	// one call per dirty storm, with display_lock held throughout. The
	// renderer diffs against its shadow and classifies the frame BEFORE
	// routing/painting any cell — that ordering (not per-cell delivery with
	// classification at storm end) is what prevents one-frame flashes of
	// content rendered under the previous screen's layout, and mid-storm
	// tearing.
	void onFrame(char[] chars, int[] fg, int[] bg);

	void increaseFontSize();
	void decreaseFontSize();
	Context getContext();
	Resources getResources();

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

	// libandroid.cc → NativeWrapper on LEVELMAP open/level-switch. 1-
	// indexed player cell; RegionRouter uses it to scroll fullView so
	// the player lands centered under the LEVELMAP font scale.
	default void setMapAnchor(int col, int row) {}

	// Word-wrap parameters queried by NativeWrapper.gameStart just before
	// initGame (views are measured by then — StartGame fires from the first
	// layout pass). getMsgWrapCols returns the msg_max_width to pass to DCSS,
	// or 0 when word wrap is off/unavailable; getMsgRows returns the terminal
	// rows the message window should span; getProseWrapCols returns the wrap
	// cap for prose popup Texts (describe/god/hints), 0 = off. Defaults keep
	// TermView (landscape) on stock behavior.
	default int getMsgWrapCols() { return 0; }
	default int getMsgRows() { return 7; }
	default int getProseWrapCols() { return 0; }
}
