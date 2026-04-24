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
}
