package com.crawlmb.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import com.crawlmb.R;

public class ControlsInfoActivity extends Activity {

	private final Handler handler = new Handler();
	private final Runnable advanceRunnable = new Runnable() {
		@Override
		public void run() {
			advance();
		}
	};
	private boolean advanced = false;

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

		handler.postDelayed(advanceRunnable, 5000);
	}

	private void advance() {
		if (advanced)
			return;
		advanced = true;
		handler.removeCallbacks(advanceRunnable);
		startActivity(new Intent(this, SplashActivity.class));
		finish();
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
