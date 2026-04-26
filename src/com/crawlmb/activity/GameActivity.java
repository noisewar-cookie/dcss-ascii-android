/*
 * File: GameActivity.java Purpose: Generic ui functions in Android application
 * 
 * Copyright (c) 2010 David Barr, Sergey Belinsky
 * 
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of either:
 * 
 * a) the GNU General Public License as published by the Free Software
 * Foundation, version 2, or
 * 
 * b) the "Angband licence": This software may be copied and distributed for
 * educational, research, and not for profit purposes provided that this
 * copyright and statement are included in all such copies. Other copyrights may
 * also apply.
 */

package com.crawlmb.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.os.Handler;
import android.os.Message;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.crawlmb.CrawlDialog;
import com.crawlmb.FontConfig;
import com.crawlmb.PassThroughListener;
import com.crawlmb.keylistener.GameKeyListener;
import com.crawlmb.keyboard.CrawlKeyboardWrapper;
import com.crawlmb.keyboard.DirectionalTouchView;
import com.crawlmb.GameThread;
import com.crawlmb.NativeWrapper;
import com.crawlmb.Preferences;
import com.crawlmb.R;
import com.crawlmb.view.RegionRouter;
import com.crawlmb.view.RegionTermView;
import com.crawlmb.view.TerminalRenderer;
import com.crawlmb.view.TermView;

public class GameActivity extends Activity
{

	static final int PREFERENCES_FINISHED = 1;
	
	public static GameKeyListener gameKeyListener = null;
	private CrawlDialog dialog = null;

	private RelativeLayout screenLayout = null;
	private TermView term = null;
	private int gamePanelId = View.NO_ID;
	private RegionTermView portraitMsgView = null;
	private RegionTermView portraitFullView = null;
	private View portraitContextHost = null;

	protected Handler handler = null;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Log.d("Crawl", "onCreate");

		if (gameKeyListener == null) {
			gameKeyListener = new GameKeyListener();
		}
	}

	@Override
	public void onStart() {
		super.onStart();

		if (dialog == null)
			dialog = new CrawlDialog(this, gameKeyListener);
		final CrawlDialog crawlDialog = dialog;
		handler = new GameHandler(crawlDialog);

		rebuildViews();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		MenuInflater inflater = new MenuInflater(getApplication());
		inflater.inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
			ContextMenu.ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);
		new MenuInflater(getApplication()).inflate(R.menu.main, menu);
		menu.setHeaderTitle(R.string.menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);
		MenuItem lockTerminalPositionItem = menu.findItem(R.id.menu_lock_terminal_position);
		if (term != null && term.getLockPositioning()) {
			lockTerminalPositionItem.setTitle(R.string.menu_unlock_terminal_position);
		} else {
			lockTerminalPositionItem.setTitle(R.string.menu_lock_terminal_position);
		}
		lockTerminalPositionItem.setVisible(term != null);

		MenuItem changeTransparencyItem = menu.findItem(R.id.menu_change_transparency);

		View transparencySliderView = findViewById(R.id.transparencySliderView);

		changeTransparencyItem.setVisible(transparencySliderView != null);

		return true;
	}

	@Override
	public boolean onMenuItemSelected(int featureId, MenuItem item) {
		Intent intent;
		switch (item.getNumericShortcut()) {
		case '1':// Change keyboard transparency
			View transparencySliderView = findViewById(R.id.transparencySliderView);
			if (transparencySliderView != null){
				transparencySliderView.setVisibility(View.VISIBLE);
			}
			break;
		case '2':// Preferences
			intent = new Intent(this, PreferencesActivity.class);
			startActivityForResult(intent, PREFERENCES_FINISHED);
			break;
		case '3':// Reset terminal position
			if (term != null)
				term.resetTerminalPosition();
			break;
		case '4':// Lock terminal position
			if (term != null)
				term.toggleLockPosition();
			break;
		case '5':// Quit
			finish();
			break;
		case '6':// Show/Hide Keyboard
			toggleKeyboard();
			break;
		}
		return super.onMenuItemSelected(featureId, item);
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode,
            Intent data) {
        if (requestCode == PREFERENCES_FINISHED) {
            if (resultCode == RESULT_OK) {
            	if(data.getBooleanExtra("reloadCrawl", false)) {
            		// Because of a change in preferences, crawl must be reloaded
            		finish();
            		startActivity(getIntent());
            	}
            }
        }
    }

	@Override
	public void finish() {
		// Log.d("Crawl","finish");
		gameKeyListener.gameThread.send(GameThread.Request.StopGame);
		super.finish();
	}

	private void rebuildViews() {
		synchronized (GameKeyListener.progress_lock) {
			// Log.d("Crawl","rebuildViews");

			int orient = Preferences.getOrientation();
			switch (orient) {
			case 0: // sensor
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
				break;
			case 1: // portrait
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
				break;
			case 2: // landscape
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
				break;
			}

			if (screenLayout != null)
				screenLayout.removeAllViews();
			screenLayout = new RelativeLayout(this);

			ViewCompat.setOnApplyWindowInsetsListener(screenLayout, (v, windowInsets) -> {
				Insets base = windowInsets.getInsets(
						WindowInsetsCompat.Type.systemBars()
						| WindowInsetsCompat.Type.displayCutout());
				int left = base.left;
				int top = base.top;
				int right = base.right;
				int bottom = base.bottom;

				android.view.WindowInsets platform = windowInsets.toWindowInsets();
				if (platform != null)
				{
					// Waterfall insets for curved-edge displays (API 30+)
					if (VERSION.SDK_INT >= VERSION_CODES.R)
					{
						android.view.DisplayCutout cutout = platform.getDisplayCutout();
						if (cutout != null)
						{
							android.graphics.Insets wf = cutout.getWaterfallInsets();
							left = Math.max(left, wf.left);
							top = Math.max(top, wf.top);
							right = Math.max(right, wf.right);
							bottom = Math.max(bottom, wf.bottom);
						}
					}

					// Rounded corner safe area (API 31+)
					if (VERSION.SDK_INT >= VERSION_CODES.S)
					{
						int tl = cornerRadius(platform, android.view.RoundedCorner.POSITION_TOP_LEFT);
						int tr = cornerRadius(platform, android.view.RoundedCorner.POSITION_TOP_RIGHT);
						int bl = cornerRadius(platform, android.view.RoundedCorner.POSITION_BOTTOM_LEFT);
						int br = cornerRadius(platform, android.view.RoundedCorner.POSITION_BOTTOM_RIGHT);
						double f = 1.0 - Math.sqrt(2.0) / 2.0;
						top = Math.max(top, (int) Math.ceil(Math.max(tl, tr) * f));
						bottom = Math.max(bottom, (int) Math.ceil(Math.max(bl, br) * f));
						left = Math.max(left, (int) Math.ceil(Math.max(tl, bl) * f));
						right = Math.max(right, (int) Math.ceil(Math.max(tr, br) * f));
					}
				}

				v.setPadding(left, top, right, bottom);
				return WindowInsetsCompat.CONSUMED;
			});

			boolean hapticFeedbackEnabled = Preferences
					.getHapticFeedbackEnabled();

			TerminalRenderer renderer;
			boolean isPortrait = Preferences.isScreenPortraitOrientation();

			if (isPortrait)
			{
				renderer = buildPortraitLayout(hapticFeedbackEnabled);
			}
			else
			{
				renderer = buildLandscapeLayout(hapticFeedbackEnabled);
			}

			gameKeyListener.link(renderer, handler);

			String keyboardType;
			if (isPortrait)
				keyboardType = Preferences.getPortraitKeyboard();
			else
				keyboardType = Preferences.getLandscapeKeyboard();

			String[] keyboards = getResources().getStringArray(
					R.array.virtualKeyboardValues);

			if (keyboardType.equals(keyboards[1])) // Crawl Keyboard
			{
				CrawlKeyboardWrapper virtualKeyboard = new CrawlKeyboardWrapper(this, gameKeyListener);
				virtualKeyboard.virtualKeyboardView
						.setHapticFeedbackEnabled(hapticFeedbackEnabled);
				screenLayout.addView(virtualKeyboard.virtualKeyboardView);

				// Constrain game panel to sit above keyboard
				if (gamePanelId != View.NO_ID)
				{
					View gamePanel = screenLayout.findViewById(gamePanelId);
					if (gamePanel != null)
					{
						RelativeLayout.LayoutParams gp = (RelativeLayout.LayoutParams) gamePanel.getLayoutParams();
						gp.addRule(RelativeLayout.ABOVE, virtualKeyboard.virtualKeyboardView.getId());
						gamePanel.setLayoutParams(gp);
					}
				}

				addDirectionalKeyView(
						virtualKeyboard.virtualKeyboardView.getId(),
						hapticFeedbackEnabled);

				View transparencySliderView = getLayoutInflater().inflate(R.layout.transparency_seekbar, screenLayout);
				SeekBar transparencySeekbar = (SeekBar) transparencySliderView.findViewById(R.id.transparency_seekbar);
				transparencySeekbar.setProgress(Preferences.getKeyboardTransparency());
				transparencySeekbar.setOnSeekBarChangeListener(virtualKeyboard.virtualKeyboardView);

				if (VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB) {
					invalidateOptionsMenu();
				}

				getWindow()
						.setSoftInputMode(
								WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

			} else if (keyboardType.equals(keyboards[2])) // System Keyboard
			{
				InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
				getWindow()
						.setSoftInputMode(
								WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
				inputMethodManager.toggleSoftInput(
						InputMethodManager.SHOW_FORCED, 0);
			} else {
				getWindow()
						.setSoftInputMode(
								WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
			}

			setContentView(screenLayout);
			dialog.restoreDialog();
		}
	}

	private TerminalRenderer buildPortraitLayout(boolean hapticFeedbackEnabled) {
		term = null;
		FontConfig fontConfig = FontConfig.load(getAssets());

		FrameLayout gamePanel = new FrameLayout(this);
		gamePanel.setId(View.generateViewId());
		gamePanelId = gamePanel.getId();

		RegionTermView fullView = new RegionTermView(this, 0, 0, 24, 80);
		fullView.setFontScaleMultiplier(fontConfig.portraitFullFontScale);
		fullView.setGameStartTrigger(handler);
		portraitFullView = fullView;
		gamePanel.addView(fullView, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

		LinearLayout splitContainer = new LinearLayout(this);
		splitContainer.setOrientation(LinearLayout.VERTICAL);
		splitContainer.setVisibility(View.INVISIBLE);

		RegionTermView mapView = new RegionTermView(this,
				RegionRouter.MAP_START_ROW, RegionRouter.MAP_START_COL,
				RegionRouter.MAP_END_ROW, RegionRouter.MAP_END_COL);
		mapView.setFontScaleMultiplier(fontConfig.portraitMapFontScale);
		mapView.setOffsetCols(fontConfig.portraitMapOffsetCols);

		RegionTermView hudView = new RegionTermView(this,
				RegionRouter.HUD_START_ROW, RegionRouter.HUD_START_COL,
				RegionRouter.HUD_END_ROW, RegionRouter.HUD_END_COL);
		hudView.setFontScaleMultiplier(fontConfig.portraitHudFontScale);

		RegionTermView msgView = new RegionTermView(this,
				RegionRouter.MSG_START_ROW, RegionRouter.MSG_START_COL,
				RegionRouter.MSG_END_ROW, RegionRouter.MSG_END_COL);
		msgView.setFontScaleMultiplier(fontConfig.portraitMsgFontScale);
		msgView.setHorizontalScrollEnabled(true);
		portraitMsgView = msgView;

		splitContainer.addView(mapView, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		splitContainer.addView(hudView, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		splitContainer.addView(msgView, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

		gamePanel.addView(splitContainer, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

		RelativeLayout.LayoutParams gamePanelParams = new RelativeLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
		gamePanelParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
		gamePanel.setLayoutParams(gamePanelParams);

		registerForContextMenu(gamePanel);
		portraitContextHost = gamePanel;
		mapView.setHapticFeedbackEnabled(hapticFeedbackEnabled);

		screenLayout.addView(gamePanel);

		RegionRouter router = new RegionRouter(this);
		router.setFullView(fullView);
		router.setSplitContainer(splitContainer);
		router.addRegion(mapView);
		router.addRegion(hudView);
		router.addRegion(msgView);
		router.setFontConfig(fontConfig);
		router.setRedrawRequester(() -> gameKeyListener.nativew.redrawScreen());

		final float maxMapScale = fontConfig.portraitMapFontScale;
		final float maxHudScale = fontConfig.portraitHudFontScale;
		final float MIN_FONT_SCALE = 0.3f;
		final int MAX_ADJUST_ATTEMPTS = 5;

		gamePanel.getViewTreeObserver().addOnGlobalLayoutListener(
				new ViewTreeObserver.OnGlobalLayoutListener()
				{
					private int attempts = 0;

					@Override
					public void onGlobalLayout()
					{
						if (splitContainer.getVisibility() != View.VISIBLE)
							return;

						int available = gamePanel.getHeight();
						if (available <= 0)
							return;

						int mapH = mapView.getMeasuredHeight();
						int hudH = hudView.getMeasuredHeight();
						int msgH = msgView.getMeasuredHeight();
						int total = mapH + hudH + msgH;

						if (total <= available)
							return;

						float curMapScale = mapView.getFontScaleMultiplier();
						float curHudScale = hudView.getFontScaleMultiplier();
						if (curMapScale <= MIN_FONT_SCALE
								&& curHudScale <= MIN_FONT_SCALE)
							return;
						if (attempts >= MAX_ADJUST_ATTEMPTS)
							return;

						attempts++;
						int mapHudCurrent = mapH + hudH;
						int mapHudTarget = available - msgH;
						if (mapHudCurrent <= 0 || mapHudTarget <= 0)
							return;

						float ratio = (float) mapHudTarget / mapHudCurrent;
						mapView.setFontScaleMultiplier(Math.max(MIN_FONT_SCALE,
								curMapScale * ratio));
						hudView.setFontScaleMultiplier(Math.max(MIN_FONT_SCALE,
								curHudScale * ratio));
						splitContainer.requestLayout();
					}
				});

		return router;
	}

	private TerminalRenderer buildLandscapeLayout(boolean hapticFeedbackEnabled) {
		gamePanelId = View.NO_ID;
		portraitMsgView = null;
		portraitFullView = null;
		portraitContextHost = null;
		FontConfig fontConfig = FontConfig.load(getAssets());
		term = new TermView(this, gameKeyListener);
		term.setFontScaleMultiplier(fontConfig.landscapeFontScale);
		RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
				LayoutParams.FILL_PARENT, LayoutParams.WRAP_CONTENT);
		layoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
		term.setLayoutParams(layoutParams);
		term.setFocusable(true);
		registerForContextMenu(term);
		term.setHapticFeedbackEnabled(hapticFeedbackEnabled);

		screenLayout.addView(term);

		return term;
	}

	private void addDirectionalKeyView(int virtualKeyboardId,
			boolean hapticFeedbackEnabled) {
		DirectionalTouchView view = new DirectionalTouchView(this, gameKeyListener);
		RelativeLayout.LayoutParams directionalLayoutParams = new RelativeLayout.LayoutParams(
				LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);
		directionalLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
		directionalLayoutParams
				.addRule(RelativeLayout.ABOVE, virtualKeyboardId);
		view.setLayoutParams(directionalLayoutParams);

		if (term != null)
		{
			view.setPassThroughListener(term);
		}
		else
		{
			final View contextHost = portraitContextHost;
			final View hapticSource = view;
			view.setPassThroughListener(new PassThroughListener()
			{
				@Override
				public boolean onScroll(android.view.MotionEvent e1,
						android.view.MotionEvent e2, float dx, float dy) { return false; }
				@Override
				public boolean onScale(
						android.view.ScaleGestureDetector d) { return false; }
				@Override
				public boolean onScaleBegin(
						android.view.ScaleGestureDetector d) { return false; }
				@Override
				public void savePosition() {}
				@Override
				public void onLongPress(android.view.MotionEvent e) {
					if (contextHost == null)
						return;
					hapticSource.performHapticFeedback(
							HapticFeedbackConstants.LONG_PRESS);
					contextHost.showContextMenu();
				}
			});
		}

		if (portraitMsgView != null)
			view.setMessageView(portraitMsgView);
		if (portraitFullView != null)
			view.setMenuView(portraitFullView);

		view.setHapticFeedbackEnabled(hapticFeedbackEnabled);
		screenLayout.addView(view);
	}

	public void toggleKeyboard() {
		int currentKeyboard;
		if (Preferences.isScreenPortraitOrientation()) {
			currentKeyboard = Integer.parseInt(Preferences
					.getPortraitKeyboard());
			if (currentKeyboard == 2) // System keyboard
			{
				toggleSystemKeyboard();
				return;
			}
			currentKeyboard = currentKeyboard == 0 ? 1 : 0;
			Preferences.setPortraitKeyboard(String.valueOf(currentKeyboard));
		} else {
			currentKeyboard = Integer.parseInt(Preferences
					.getLandscapeKeyboard());
			if (currentKeyboard == 2) // System keyboard
			{
				toggleSystemKeyboard();
				return;
			}
			currentKeyboard = currentKeyboard == 0 ? 1 : 0;
			Preferences.setLandscapeKeyboard(String.valueOf(currentKeyboard));
		}

		rebuildViews();
	}

	private void toggleSystemKeyboard() {
		InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
		inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
	}

	@Override
	protected void onPause() {
		super.onPause();
		NativeWrapper.nativeSaveGame();
	}

	@Override
	protected void onResume() {
		// Log.d("Crawl", "onResume");
		super.onResume();

		setScreen();

	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK){
			View transparencySliderView = findViewById(R.id.transparencySliderView);
			if (transparencySliderView != null && transparencySliderView.getVisibility() == View.VISIBLE){
				SeekBar transparencySeekbar = (SeekBar) transparencySliderView.findViewById(R.id.transparency_seekbar);
				int transparency = transparencySeekbar.getProgress();
				Preferences.setKeyboardTransparency(transparency);
				transparencySliderView.setVisibility(View.GONE);
				return true;
			}
		}
		return gameKeyListener.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		return gameKeyListener.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event);
	}

	public void setScreen() {
		if (Preferences.getFullScreen()) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		} else {
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}
	}

	public Handler getHandler() {
		return handler;
	}

	private static int cornerRadius(android.view.WindowInsets insets, int position) {
		android.view.RoundedCorner c = insets.getRoundedCorner(position);
		return c != null ? c.getRadius() : 0;
	}

	private static class GameHandler extends Handler {
		private final CrawlDialog crawlDialog;

		public GameHandler(CrawlDialog crawlDialog) {
			this.crawlDialog = crawlDialog;
		}

		@Override
        public void handleMessage(Message msg) {
            crawlDialog.HandleMessage(msg);
        }
	}

}
