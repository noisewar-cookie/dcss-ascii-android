package com.crawlmb.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.crawlmb.Preferences;
import com.crawlmb.R;
import com.crawlmb.view.FoldStateController;

public class ControlsInfoActivity extends Activity {

	private static final int[] SCREENS = {
			R.drawable.controls_info,
			R.drawable.controls_info_2,
	};

	private final Handler handler = new Handler();
	private final Runnable advanceRunnable = new Runnable() {
		@Override
		public void run() {
			advance();
		}
	};
	private int screenIndex = 0;
	private boolean finished = false;
	// Both help screens shown side-by-side on an open foldable; a single tap
	// (or one longer dwell) then proceeds straight to the splash.
	private boolean twoUp = false;
	private FoldStateController foldStateController = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_controls_info);
		hideSystemBars();

		findViewById(R.id.controls_info_root).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				advance();
			}
		});

		if (Preferences.getSkipControlsInfo()) {
			// Hide the help image and seed screenIndex so a single advance()
			// jumps straight to SplashActivity through the existing path.
			// Posted (not called directly) so it runs after onResume — calling
			// finish() before the activity is resumed prevents SplashActivity's
			// window from becoming visible, so the splash art never appears.
			findViewById(R.id.controls_info_image).setVisibility(View.GONE);
			screenIndex = SCREENS.length - 1;
			handler.post(advanceRunnable);
			return;
		}

		handler.postDelayed(advanceRunnable, 5000);
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (finished || twoUp || Preferences.getSkipControlsInfo())
			return;
		if (foldStateController == null)
			foldStateController = new FoldStateController(this,
					this::onFoldStateChanged);
		foldStateController.start();
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (foldStateController != null)
			foldStateController.stop();
	}

	// Posture callback (main thread). Once, when the device is open with unfolded
	// mode enabled, switch to the side-by-side help layout and single-tap advance.
	private void onFoldStateChanged(boolean unfoldedActive,
			FoldStateController.Posture posture) {
		if (twoUp || finished || posture == null || !unfoldedActive)
			return;
		twoUp = true;
		showSideBySide(posture);
		// Both screens are visible now — one tap proceeds; extend the auto-advance
		// dwell since there is twice as much to read.
		handler.removeCallbacks(advanceRunnable);
		handler.postDelayed(advanceRunnable, 10000);
	}

	// Fill the open display with screen 1 on the left half and screen 2 on the
	// right, split at the hinge (weights mirror the fold geometry).
	private void showSideBySide(FoldStateController.Posture posture) {
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
		FrameLayout root = findViewById(R.id.controls_info_root);
		findViewById(R.id.controls_info_image).setVisibility(View.GONE);

		LinearLayout split = new LinearLayout(this);
		split.setOrientation(LinearLayout.HORIZONTAL);
		split.setBackgroundColor(Color.BLACK);

		int gap = Math.max(0, posture.rightStart - posture.leftWidth);
		float leftWeight = Math.max(1, posture.leftWidth);
		float rightWeight = Math.max(1, posture.totalWidth - posture.rightStart);

		split.addView(makeHelpImage(SCREENS[0]),
				new LinearLayout.LayoutParams(0,
						ViewGroup.LayoutParams.MATCH_PARENT, leftWeight));
		split.addView(new View(this),
				new LinearLayout.LayoutParams(gap,
						ViewGroup.LayoutParams.MATCH_PARENT));
		split.addView(makeHelpImage(SCREENS[1]),
				new LinearLayout.LayoutParams(0,
						ViewGroup.LayoutParams.MATCH_PARENT, rightWeight));

		root.addView(split, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
	}

	private ImageView makeHelpImage(int resId) {
		ImageView iv = new ImageView(this);
		iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
		iv.setImageResource(resId);
		return iv;
	}

	private void advance() {
		if (finished)
			return;
		handler.removeCallbacks(advanceRunnable);
		// Two-up shows both screens at once, so any advance proceeds.
		if (twoUp) {
			finished = true;
			startActivity(new Intent(this, SplashActivity.class));
			finish();
			return;
		}
		screenIndex++;
		if (screenIndex >= SCREENS.length) {
			finished = true;
			startActivity(new Intent(this, SplashActivity.class));
			finish();
			return;
		}
		((ImageView) findViewById(R.id.controls_info_image))
				.setImageResource(SCREENS[screenIndex]);
		handler.postDelayed(advanceRunnable, 5000);
	}

	private void hideSystemBars() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			WindowInsetsController controller = getWindow().getInsetsController();
			if (controller != null) {
				controller.hide(WindowInsets.Type.systemBars());
				controller.setSystemBarsBehavior(
						WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
			}
		} else {
			getWindow().getDecorView().setSystemUiVisibility(
					View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
					| View.SYSTEM_UI_FLAG_FULLSCREEN
					| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
					| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
					| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
					| View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		handler.removeCallbacks(advanceRunnable);
	}
}
