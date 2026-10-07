package com.crawlmb.view;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewTreeObserver;

// The window's ViewTreeObserver via the decor view, which outlives rebuildViews.
// A detached view's getViewTreeObserver() is a throwaway floating observer, so
// adding/removing through it leaks listeners into (or misses) the window's one.
public final class WindowVto {
	private WindowVto() {}

	public static ViewTreeObserver of(View v) {
		Context c = v.getContext();
		while (c instanceof ContextWrapper && !(c instanceof Activity))
			c = ((ContextWrapper) c).getBaseContext();
		if (c instanceof Activity && ((Activity) c).getWindow() != null)
			return ((Activity) c).getWindow().getDecorView()
					.getViewTreeObserver();
		return v.getViewTreeObserver();
	}
}
