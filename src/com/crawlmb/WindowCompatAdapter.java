package com.crawlmb;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

// Normalizes the window/inset model so every OS version presents the same
// contract as Android 15 (API 35), which forces edge-to-edge by default.
//
// API 35 is the clean code path: each method here is a no-op or a behavioral
// match on 35+, and back-fills the equivalent behavior on API < 35. Activity
// code (inset listeners, layout, fullscreen toggles) can then be written once
// against the API 35 model and work unchanged on older devices.
public final class WindowCompatAdapter
{
	private WindowCompatAdapter()
	{
	}

	// Make the window draw edge-to-edge (content behind the system bars) on
	// every version. API 35 already does this by default; on older versions
	// we opt in explicitly so the activity's WindowInsets listeners receive
	// the same real system-bar / cutout insets they get on 35. Without this,
	// API 33/34 keep the legacy decor-fits model and inset listeners see
	// near-zero insets — the layout then mismatches what the API 35-tuned
	// code expects.
	public static void applyEdgeToEdge(Activity activity)
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM)
			WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
		// API 35+: already edge-to-edge by default — nothing to do.
	}

	// Show or hide the status bar. Replaces the legacy FLAG_FULLSCREEN
	// add/clear: that flag is deprecated from API 30 and, combined with the
	// edge-to-edge decor model applied above, can deliver inconsistent insets
	// across versions. Routing through the insets controller keeps inset
	// dispatch consistent whether the bar is shown or hidden. Status bar only,
	// matching FLAG_FULLSCREEN's original scope (the nav bar is untouched).
	public static void applyFullscreen(Activity activity, boolean fullscreen)
	{
		Window window = activity.getWindow();
		WindowInsetsControllerCompat controller =
				WindowCompat.getInsetsController(window, window.getDecorView());
		if (fullscreen)
		{
			controller.setSystemBarsBehavior(WindowInsetsControllerCompat
					.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
			controller.hide(WindowInsetsCompat.Type.statusBars());
		}
		else
		{
			controller.show(WindowInsetsCompat.Type.statusBars());
		}
	}

	// Pad `root` by the system-bar + display-cutout insets so its content
	// isn't drawn behind the bars. For plain content screens (help, prefs)
	// that have no inset handling of their own. GameActivity does NOT use
	// this — it installs its own richer listener that additionally handles
	// waterfall and rounded-corner safe areas.
	public static void padRootForSystemBars(View root)
	{
		ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) ->
		{
			Insets bars = windowInsets.getInsets(
					WindowInsetsCompat.Type.systemBars()
					| WindowInsetsCompat.Type.displayCutout());
			v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
			return WindowInsetsCompat.CONSUMED;
		});
	}
}
