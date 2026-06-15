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
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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
import com.crawlmb.keyboard.CrawlKeyboardView;
import com.crawlmb.keyboard.CrawlKeyboardWrapper;
import com.crawlmb.keyboard.DirectionalTouchView;
import com.crawlmb.GameThread;
import com.crawlmb.NativeWrapper;
import com.crawlmb.Preferences;
import com.crawlmb.R;
import com.crawlmb.WindowCompatAdapter;
import com.crawlmb.view.QuickControlsView;
import com.crawlmb.view.RegionRouter;
import com.crawlmb.view.RegionTermView;
import com.crawlmb.view.StatusBarView;
import com.crawlmb.view.TerminalRenderer;
import com.crawlmb.view.TermView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
	private RegionTermView portraitSkillsView = null;
	private RegionTermView portraitItemsView = null;
	private RegionTermView portraitMapView = null;
	private StatusBarView portraitStatusBar = null;
	private FontConfig portraitFontConfig = null;
	private RegionRouter portraitRouter = null;
	private View[] portraitExtraScrollTargets = null;
	private View portraitContextHost = null;
	// Inset targets, populated per rebuildViews(). The window is edge-to-edge
	// on every OS version (see WindowCompatAdapter), so the safe-area insets
	// must be distributed across these children rather than padding
	// screenLayout as a whole — see the inset listener in rebuildViews().
	private View portraitKeyboardView = null;
	private View portraitDirectionalView = null;

	// "Reloading..." overlay shown across a save-restore process restart (set
	// just before the kill in PreferencesActivity). reloadOverlayActive is
	// read once in onCreate from the persisted one-shot flag; the opaque
	// overlay covers the whole screen — including the Crawl keyboard — until
	// DCSS paints its first post-boot screen. See enterReloadState.
	private static final String RELOADING_ASSET = "reloading.txt";
	private static final long RELOAD_TIMEOUT_MS = 12000;
	private boolean reloadOverlayActive = false;
	private View reloadOverlay = null;
	private Runnable reloadTimeoutRunnable = null;

	protected Handler handler = null;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Log.d("Crawl", "onCreate");

		// Force the API 35 edge-to-edge window model on every OS version so
		// the inset listener in rebuildViews() receives the same real insets
		// it gets on Android 15. No-op on API 35+.
		WindowCompatAdapter.applyEdgeToEdge(this);

		// Read (and clear) the save-restore reload flag once per launch, before
		// rebuildViews() so the first layout already carries the overlay.
		reloadOverlayActive = Preferences.consumeReloadInProgress();

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

			// Portrait-only for now. Landscape support is retained in code
			// (buildLandscapeLayout, Preferences.getLandscapeKeyboard, etc.)
			// for future re-enable, but the orientation picker is hidden in
			// preferences.xml and the stored crawl.orientation pref is ignored
			// here.
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

			if (screenLayout != null)
				screenLayout.removeAllViews();
			screenLayout = new RelativeLayout(this);
			portraitKeyboardView = null;
			portraitDirectionalView = null;

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

					// Rounded-corner safe area, API 35+ only. API 35 forces
					// edge-to-edge, so content draws into the physical display
					// corners and the corner arc clips the outermost glyphs
					// (e.g. the top of the "H" in the menu). Gated to 35+
					// deliberately: applying it on API 33 — where the adapter
					// opts into edge-to-edge but the platform reports corners
					// differently — shifted every screen ~1 column, and API 33
					// and below already render correctly without it.
					if (VERSION.SDK_INT >= VERSION_CODES.VANILLA_ICE_CREAM)
					{
						int tl = cornerRadius(platform,
								android.view.RoundedCorner.POSITION_TOP_LEFT);
						int tr = cornerRadius(platform,
								android.view.RoundedCorner.POSITION_TOP_RIGHT);
						int bl = cornerRadius(platform,
								android.view.RoundedCorner.POSITION_BOTTOM_LEFT);
						int br = cornerRadius(platform,
								android.view.RoundedCorner.POSITION_BOTTOM_RIGHT);
						left = Math.max(left, Math.max(tl, bl));
						top = Math.max(top, Math.max(tl, tr));
						right = Math.max(right, Math.max(tr, br));
						bottom = Math.max(bottom, Math.max(bl, br));
					}
				}

				Log.d("Crawl", "insets: l=" + left + " t=" + top
						+ " r=" + right + " b=" + bottom);

				// Distribute the safe-area insets per-child instead of padding
				// screenLayout as a whole. The crawl keyboard is a fixed-size
				// KeyboardView whose key grid can't reflow to a narrower width
				// — shrinking its space just clips the rightmost keys and the
				// bottom row. So when it's present it keeps the full window
				// width and absorbs only the bottom inset as its own bottom
				// padding (empty space below the keys, over the nav bar); the
				// game area takes top/left/right and already sits ABOVE the
				// keyboard. With no crawl keyboard the game panel reaches the
				// window bottom and takes the bottom inset itself.
				View keyboard = portraitKeyboardView;
				View gameArea = screenLayout.findViewById(gamePanelId);
				if (keyboard != null)
				{
					keyboard.setPadding(0, 0, 0, bottom);
					if (gameArea != null)
						gameArea.setPadding(left, top, right, 0);
					if (portraitDirectionalView != null)
						portraitDirectionalView.setPadding(left, top, right, 0);
				}
				else
				{
					if (gameArea != null)
						gameArea.setPadding(left, top, right, bottom);
					if (portraitDirectionalView != null)
						portraitDirectionalView.setPadding(left, top, right, bottom);
				}
				return WindowInsetsCompat.CONSUMED;
			});

			boolean hapticFeedbackEnabled = Preferences
					.getHapticFeedbackEnabled();

			// Portrait-only: stored isScreenPortraitOrientation() pref ignored.
			TerminalRenderer renderer = buildPortraitLayout(hapticFeedbackEnabled);
			gameKeyListener.link(renderer, handler);

			String keyboardType = Preferences.getPortraitKeyboard();

			String[] keyboards = getResources().getStringArray(
					R.array.virtualKeyboardValues);

			if (keyboardType.equals(keyboards[1])) // Crawl Keyboard
			{
				CrawlKeyboardWrapper virtualKeyboard = new CrawlKeyboardWrapper(this, gameKeyListener);
				virtualKeyboard.virtualKeyboardView
						.setHapticFeedbackEnabled(hapticFeedbackEnabled);
				screenLayout.addView(virtualKeyboard.virtualKeyboardView);
				portraitKeyboardView = virtualKeyboard.virtualKeyboardView;
				if (portraitRouter != null)
					portraitRouter.setKeyboardView(
							virtualKeyboard.virtualKeyboardView);

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

			if (reloadOverlayActive)
				enterReloadState();
		}
	}

	// Lay an opaque "Reloading..." overlay over the freshly-built screen and
	// keep it up until DCSS paints its first post-boot screen. The overlay is
	// the last child of screenLayout, so it draws on top of the game panel,
	// the Crawl keyboard and DirectionalTouchView, and (being clickable)
	// swallows touches meant for them. The system soft keyboard is a separate
	// window the overlay can't cover, so it's suppressed explicitly here and
	// restored on exit. Called from rebuildViews after setContentView.
	private void enterReloadState() {
		// Drop any overlay left attached to a previous screenLayout (rebuilds
		// recreate screenLayout) before attaching a fresh one.
		removeReloadOverlay();

		reloadOverlay = buildReloadOverlay();
		screenLayout.addView(reloadOverlay, new RelativeLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

		getWindow().setSoftInputMode(
				WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
		InputMethodManager imm = (InputMethodManager)
				getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.hideSoftInputFromWindow(screenLayout.getWindowToken(), 0);

		// Dismiss exactly when DCSS reaches the main menu / gameplay...
		if (portraitRouter != null)
			portraitRouter.setReloadCompleteListener(this::exitReloadState);

		// ...and a backstop in case that signal never arrives (e.g. a boot
		// that never reaches a recognized screen) so the overlay can't stick.
		reloadTimeoutRunnable = this::exitReloadState;
		reloadOverlay.postDelayed(reloadTimeoutRunnable, RELOAD_TIMEOUT_MS);
	}

	// Idempotent: may be invoked by both the router callback and the timeout.
	private void exitReloadState() {
		if (!reloadOverlayActive)
			return;
		reloadOverlayActive = false;

		if (portraitRouter != null)
			portraitRouter.setReloadCompleteListener(null);

		if (reloadTimeoutRunnable != null && reloadOverlay != null)
			reloadOverlay.removeCallbacks(reloadTimeoutRunnable);
		reloadTimeoutRunnable = null;

		removeReloadOverlay();
		restoreKeyboardAfterReload();
	}

	private void removeReloadOverlay() {
		if (reloadOverlay != null && reloadOverlay.getParent() instanceof ViewGroup)
			((ViewGroup) reloadOverlay.getParent()).removeView(reloadOverlay);
		reloadOverlay = null;
	}

	// The Crawl keyboard was only ever covered (not hidden), so it needs no
	// restore. Only the system soft keyboard, suppressed in enterReloadState,
	// has to be re-shown here when it's the configured type.
	private void restoreKeyboardAfterReload() {
		String keyboardType = Preferences.getPortraitKeyboard();
		String[] keyboards = getResources().getStringArray(
				R.array.virtualKeyboardValues);
		if (keyboardType.equals(keyboards[2])) // System keyboard
		{
			getWindow().setSoftInputMode(
					WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
			InputMethodManager imm = (InputMethodManager)
					getSystemService(Context.INPUT_METHOD_SERVICE);
			if (imm != null)
				imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
		}
	}

	private View buildReloadOverlay() {
		TextView tv = new TextView(this);
		tv.setText(loadReloadMessage());
		tv.setTextColor(Color.WHITE);
		tv.setBackgroundColor(Color.BLACK);
		tv.setGravity(Gravity.CENTER);
		tv.setTypeface(Typeface.MONOSPACE);
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
		// Opaque + clickable so it both hides and intercepts input for the
		// views beneath it during the reload.
		tv.setClickable(true);
		tv.setFocusable(true);
		return tv;
	}

	// Read the (editable) reload message from assets/reloading.txt, one line
	// per row. Falls back to a literal if the asset is missing so the overlay
	// is never blank. UTF-8 to allow non-ASCII text.
	private CharSequence loadReloadMessage() {
		StringBuilder sb = new StringBuilder();
		try (InputStream is = getAssets().open(RELOADING_ASSET);
				BufferedReader br = new BufferedReader(
						new InputStreamReader(is, StandardCharsets.UTF_8)))
		{
			String line;
			boolean first = true;
			while ((line = br.readLine()) != null)
			{
				if (!first)
					sb.append('\n');
				sb.append(line);
				first = false;
			}
		}
		catch (IOException e)
		{
			Log.w("GameActivity", "Could not load " + RELOADING_ASSET
					+ ": " + e.getMessage());
		}
		String msg = sb.toString().trim();
		return msg.isEmpty() ? "Reloading..." : msg;
	}

	private TerminalRenderer buildPortraitLayout(boolean hapticFeedbackEnabled) {
		term = null;
		FontConfig fontConfig = FontConfig.load(getAssets());
		portraitFontConfig = fontConfig;

		FrameLayout gamePanel = new FrameLayout(this);
		gamePanel.setId(View.generateViewId());
		gamePanelId = gamePanel.getId();

		RegionTermView fullView = new RegionTermView(this, 0, 0, 28, 80);
		fullView.setFontScaleMultiplier(fontConfig.portraitDefaultFontScale);
		fullView.setGameStartTrigger(handler);
		portraitFullView = fullView;

		// Quick Controls panel: app-only static info shown below the DCSS
		// main menu. Sits in a vertical LinearLayout with fullView so the
		// panel's slot always starts at fullView's bottom and stretches down
		// to the keyboard top — regardless of mainmenu font scale changes.
		// The LinearLayout replaces the direct fullView add to gamePanel; it
		// stays as the only FrameLayout child responsible for the "menu"
		// layer, with skills/split/newgame siblings layered on top as before.
		// Grid is 80 cols (matches fullView's reference width so glyph size
		// is consistent at the same font scale) and exactly enough rows for
		// the loaded content — sizing rows to content avoids trailing empty
		// rows that would otherwise appear as whitespace when scrolling.
		String[] qcLines = loadQuickControls();
		int qcRows = Math.max(1, qcLines.length);
		QuickControlsView quickControlsView = new QuickControlsView(this, qcRows, 80);
		quickControlsView.setFontScaleMultiplier(fontConfig.portraitQuickControlsFontScale);
		quickControlsView.setFontReferenceCols(RegionRouter.TERMINAL_COLS);
		quickControlsView.setHorizontalScrollEnabled(fontConfig.portraitQuickControlsScrollable);
		quickControlsView.setVerticalScrollEnabled(fontConfig.portraitQuickControlsVScrollable);
		quickControlsView.setFontColor(fontConfig.portraitQuickControlsFontColor);
		quickControlsView.setVisibility(View.INVISIBLE);
		quickControlsView.setLines(qcLines);

		LinearLayout menuStack = new LinearLayout(this);
		menuStack.setOrientation(LinearLayout.VERTICAL);
		menuStack.addView(fullView, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		// height=0 + weight=1 → quickControlsView claims all space left over
		// after fullView's WRAP_CONTENT. Combined with verticalScrollEnabled,
		// content taller than the slot scrolls within the slot rather than
		// pushing the panel past the keyboard.
		menuStack.addView(quickControlsView, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, 0, 1.0f));
		gamePanel.addView(menuStack, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

		// Skills menu view: 41 rows tall. The 24 source rows expand to 41
		// after folding the second skill column underneath the first — the
		// fold offset is RegionRouter.SKILL_FOLD_ROWS (= 17), and the
		// help/button block at original rows 19..23 also shifts down by
		// the same amount, landing at rows 36..40. Vertical scroll is on
		// by default because at typical font scales the folded layout
		// overflows the gamePanel; RegionTermView caps its reported height
		// to the parent so siblings aren't pushed offscreen.
		RegionTermView skillsView = new RegionTermView(this, 0, 0, 41, 80);
		skillsView.setFontScaleMultiplier(fontConfig.portraitSkillsFontScale);
		skillsView.setHorizontalScrollEnabled(fontConfig.portraitSkillsScrollable);
		skillsView.setVerticalScrollEnabled(fontConfig.portraitSkillsVScrollable);
		skillsView.setVisibility(View.INVISIBLE);
		portraitSkillsView = skillsView;
		gamePanel.addView(skillsView, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

		// 112 rows: 48-row terminal × 2 columns folded into one column,
		// plus spacer rows inserted before help-screen section headings.
		RegionTermView itemsView = new RegionTermView(this, 0, 0, 112, 80);
		itemsView.setFontScaleMultiplier(fontConfig.portraitItemsFontScale);
		itemsView.setHorizontalScrollEnabled(fontConfig.portraitItemsScrollable);
		itemsView.setVerticalScrollEnabled(fontConfig.portraitItemsVScrollable);
		itemsView.setVisibility(View.INVISIBLE);
		portraitItemsView = itemsView;
		gamePanel.addView(itemsView, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

		// Stack bottom-to-top: msg → mlist → statusBar → hud → map.
		// The onGlobalLayout listener below shrinks map font if the
		// panels overflow the available height.
		RelativeLayout splitContainer = new RelativeLayout(this);
		splitContainer.setBackgroundColor(Color.BLACK);
		splitContainer.setVisibility(View.INVISIBLE);

		RegionTermView mapView = new RegionTermView(this,
				RegionRouter.MAP_START_ROW, RegionRouter.MAP_START_COL,
				RegionRouter.MAP_END_ROW, RegionRouter.MAP_END_COL);
		mapView.setId(View.generateViewId());
		mapView.setFontScaleMultiplier(fontConfig.portraitMapFontScale);
		mapView.setCenterHorizontally(true);
		mapView.setCenterContentCols(33);
		mapView.setOffsetCols(fontConfig.portraitMapOffsetCols);
		portraitMapView = mapView;

		RegionTermView hudView = new RegionTermView(this,
				RegionRouter.HUD_START_ROW, RegionRouter.HUD_START_COL,
				RegionRouter.HUD_END_ROW, RegionRouter.HUD_END_COL);
		hudView.setId(View.generateViewId());
		hudView.setFontScaleMultiplier(fontConfig.portraitHudFontScale);
		hudView.setOffsetCols(fontConfig.portraitHudOffsetCols);

		RegionTermView mlistView = new RegionTermView(this,
				RegionRouter.MLIST_START_ROW, RegionRouter.HUD_START_COL,
				RegionRouter.MLIST_END_ROW, RegionRouter.HUD_END_COL);
		mlistView.setId(View.generateViewId());
		mlistView.setFontScaleMultiplier(fontConfig.portraitHudFontScale);
		mlistView.setOffsetCols(fontConfig.portraitHudOffsetCols);

		RegionTermView msgView = new RegionTermView(this,
				RegionRouter.MSG_START_ROW, RegionRouter.MSG_START_COL,
				RegionRouter.MSG_END_ROW, RegionRouter.MSG_END_COL);
		msgView.setId(View.generateViewId());
		msgView.setFontScaleMultiplier(fontConfig.portraitMsgFontScale);
		msgView.setHorizontalScrollEnabled(true);
		portraitMsgView = msgView;

		StatusBarView statusBar = new StatusBarView(this);
		statusBar.setId(View.generateViewId());
		portraitStatusBar = statusBar;
		Typeface gameTf = StatusBarView.loadGameTypeface(this,
				Preferences.getFontFace());
		statusBar.setTypeface(gameTf);
		int screenWidth = getResources().getDisplayMetrics().widthPixels;
		int hudCols = RegionRouter.HUD_END_COL - RegionRouter.HUD_START_COL;
		Paint sizingPaint = new Paint();
		sizingPaint.setTypeface(gameTf);
		int baseFontSize = 1;
		do
		{
			baseFontSize++;
			sizingPaint.setTextSize(baseFontSize);
		}
		while (sizingPaint.measureText("X") * hudCols <= screenWidth
				&& baseFontSize < 200);
		baseFontSize--;
		float statusFontPx = Math.round(baseFontSize
				* fontConfig.portraitHudFontScale);
		statusBar.setFontSizePx(statusFontPx);
		sizingPaint.setTextSize(statusFontPx);
		int statusBarHeight = (int) Math.ceil(sizingPaint.getFontSpacing());
		int charWidthPx = (int) sizingPaint.measureText("X");
		statusBar.setPadding(
				charWidthPx * fontConfig.portraitHudOffsetCols, 0, 0, 0);

		RelativeLayout.LayoutParams msgParams = new RelativeLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
		msgParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
		splitContainer.addView(msgView, msgParams);

		RelativeLayout.LayoutParams mlistParams = new RelativeLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
		mlistParams.addRule(RelativeLayout.ABOVE, msgView.getId());
		splitContainer.addView(mlistView, mlistParams);

		RelativeLayout.LayoutParams statusParams = new RelativeLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, statusBarHeight);
		statusParams.addRule(RelativeLayout.ABOVE, mlistView.getId());
		splitContainer.addView(statusBar, statusParams);

		RelativeLayout.LayoutParams hudParams = new RelativeLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
		hudParams.addRule(RelativeLayout.ABOVE, statusBar.getId());
		splitContainer.addView(hudView, hudParams);

		RelativeLayout.LayoutParams mapParams = new RelativeLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
		mapParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
		mapParams.addRule(RelativeLayout.ABOVE, hudView.getId());
		splitContainer.addView(mapView, mapParams);

		gamePanel.addView(splitContainer, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

		// Newgame portrait layout: each species/background category is its
		// own RegionTermView sampling a fixed terminal rectangle, stacked
		// vertically in a LinearLayout. Each panel only renders the chars
		// that DCSS writes within its rect, so the upstream multi-column
		// terminal layout remains untouched. Containers are INVISIBLE until
		// RegionRouter detects the matching newgame screen.
		LinearLayout newgameSpecies = new LinearLayout(this);
		newgameSpecies.setOrientation(LinearLayout.VERTICAL);

		RegionTermView ngsWelcome = new RegionTermView(this,
				RegionRouter.NEWGAME_WELCOME_ROW0, RegionRouter.NEWGAME_COL_LEFT,
				RegionRouter.NEWGAME_WELCOME_ROW1, RegionRouter.NEWGAME_COL_FULL_END);
		ngsWelcome.setFontScaleMultiplier(fontConfig.portraitNewgameWelcomeFontScale);
		ngsWelcome.setHorizontalScrollEnabled(fontConfig.portraitNewgameWelcomeScrollable);
		ngsWelcome.setVerticalScrollEnabled(fontConfig.portraitNewgameWelcomeVScrollable);

		RegionTermView ngsSimple = makeNewgameCategoryView(
				RegionRouter.NEWGAME_SPECIES_ROW0, RegionRouter.NEWGAME_COL_LEFT,
				RegionRouter.NEWGAME_SPECIES_ROW1, RegionRouter.NEWGAME_COL_LEFT_END);
		RegionTermView ngsIntermediate = makeNewgameCategoryView(
				RegionRouter.NEWGAME_SPECIES_ROW0, RegionRouter.NEWGAME_COL_MID,
				RegionRouter.NEWGAME_SPECIES_ROW1, RegionRouter.NEWGAME_COL_MID_END);
		RegionTermView ngsAdvanced = makeNewgameCategoryView(
				RegionRouter.NEWGAME_SPECIES_ROW0, RegionRouter.NEWGAME_COL_RIGHT,
				RegionRouter.NEWGAME_SPECIES_ROW1, RegionRouter.NEWGAME_COL_RIGHT_END);

		// Sub-options block: three stacked panels per screen — description
		// (full-width, samples the rows above the sub-items grid), then
		// subLeft (col-0 cells: +/#/%/?), then subRight (col-1 cells:
		// */!/Space/Tab with per-row leading-whitespace stripped so they
		// align under col-0). The router title-scans for "+ - Recommended"
		// to find where the sub-items grid actually starts (description
		// height is dynamic) and re-aims via setRegionRows/setRegionCols/
		// setRowColShift before each frame.
		RegionTermView ngsDesc = makeNewgameDescView();
		RegionTermView ngsSubLeft = makeNewgameSubView();
		RegionTermView ngsSubRight = makeNewgameSubView();

		newgameSpecies.addView(ngsWelcome, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameSpecies.addView(ngsSimple, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameSpecies.addView(ngsIntermediate, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameSpecies.addView(ngsAdvanced, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameSpecies.addView(ngsDesc, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameSpecies.addView(ngsSubLeft, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameSpecies.addView(ngsSubRight, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

		ScrollView ngsScroll = new ScrollView(this);
		ngsScroll.setVisibility(View.INVISIBLE);
		ngsScroll.addView(newgameSpecies, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		gamePanel.addView(ngsScroll, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

		LinearLayout newgameBackground = new LinearLayout(this);
		newgameBackground.setOrientation(LinearLayout.VERTICAL);

		RegionTermView ngbWelcome = new RegionTermView(this,
				RegionRouter.NEWGAME_WELCOME_ROW0, RegionRouter.NEWGAME_COL_LEFT,
				RegionRouter.NEWGAME_BG_WELCOME_ROW1, RegionRouter.NEWGAME_COL_FULL_END);
		ngbWelcome.setFontScaleMultiplier(fontConfig.portraitNewgameWelcomeFontScale);
		ngbWelcome.setHorizontalScrollEnabled(fontConfig.portraitNewgameWelcomeScrollable);
		ngbWelcome.setVerticalScrollEnabled(fontConfig.portraitNewgameWelcomeVScrollable);

		RegionTermView ngbWarrior = makeNewgameCategoryView(
				RegionRouter.NEWGAME_BG_WARRIOR_ROW0, RegionRouter.NEWGAME_COL_LEFT,
				RegionRouter.NEWGAME_BG_WARRIOR_ROW1, RegionRouter.NEWGAME_COL_LEFT_END);
		RegionTermView ngbZealot = makeNewgameCategoryView(
				RegionRouter.NEWGAME_BG_ZEALOT_ROW0, RegionRouter.NEWGAME_COL_LEFT,
				RegionRouter.NEWGAME_BG_ZEALOT_ROW1, RegionRouter.NEWGAME_COL_LEFT_END);
		RegionTermView ngbAdventurer = makeNewgameCategoryView(
				RegionRouter.NEWGAME_BG_ADVENTURER_ROW0, RegionRouter.NEWGAME_COL_MID,
				RegionRouter.NEWGAME_BG_ADVENTURER_ROW1, RegionRouter.NEWGAME_COL_MID_END);
		RegionTermView ngbWarMage = makeNewgameCategoryView(
				RegionRouter.NEWGAME_BG_WARMAGE_ROW0, RegionRouter.NEWGAME_COL_MID,
				RegionRouter.NEWGAME_BG_WARMAGE_ROW1, RegionRouter.NEWGAME_COL_MID_END);
		RegionTermView ngbMage = makeNewgameCategoryView(
				RegionRouter.NEWGAME_BG_MAGE_ROW0, RegionRouter.NEWGAME_COL_RIGHT,
				RegionRouter.NEWGAME_BG_MAGE_ROW1, RegionRouter.NEWGAME_COL_RIGHT_END);

		RegionTermView ngbDesc = makeNewgameDescView();
		RegionTermView ngbSubLeft = makeNewgameSubView();
		RegionTermView ngbSubRight = makeNewgameSubView();

		newgameBackground.addView(ngbWelcome, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameBackground.addView(ngbWarrior, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameBackground.addView(ngbZealot, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameBackground.addView(ngbAdventurer, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameBackground.addView(ngbWarMage, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameBackground.addView(ngbMage, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameBackground.addView(ngbDesc, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameBackground.addView(ngbSubLeft, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameBackground.addView(ngbSubRight, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

		ScrollView ngbScroll = new ScrollView(this);
		ngbScroll.setVisibility(View.INVISIBLE);
		ngbScroll.addView(newgameBackground, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		gamePanel.addView(ngbScroll, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

		LinearLayout newgameWeapon = new LinearLayout(this);
		newgameWeapon.setOrientation(LinearLayout.VERTICAL);

		RegionTermView ngwContent = makeNewgameSubView();
		RegionTermView ngwSubLeft = makeNewgameSubView();
		RegionTermView ngwSubRight = makeNewgameSubView();

		newgameWeapon.addView(ngwContent, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameWeapon.addView(ngwSubLeft, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		newgameWeapon.addView(ngwSubRight, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

		ScrollView ngwScroll = new ScrollView(this);
		ngwScroll.setVisibility(View.INVISIBLE);
		ngwScroll.addView(newgameWeapon, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		gamePanel.addView(ngwScroll, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

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
		router.setSkillsView(skillsView);
		router.setItemsView(itemsView);
		router.setQuickControlsView(quickControlsView);
		router.setSplitContainer(splitContainer);
		router.setStatusBarView(statusBar);
		router.addRegion(mapView);
		router.addRegion(hudView);
		router.addRegion(mlistView);
		router.addRegion(msgView);
		router.addRegion(ngsWelcome);
		router.addRegion(ngsSimple);
		router.addRegion(ngsIntermediate);
		router.addRegion(ngsAdvanced);
		router.addRegion(ngsDesc);
		router.addRegion(ngsSubLeft);
		router.addRegion(ngsSubRight);
		router.addRegion(ngbWelcome);
		router.addRegion(ngbWarrior);
		router.addRegion(ngbZealot);
		router.addRegion(ngbAdventurer);
		router.addRegion(ngbWarMage);
		router.addRegion(ngbMage);
		router.addRegion(ngbDesc);
		router.addRegion(ngbSubLeft);
		router.addRegion(ngbSubRight);
		router.addRegion(ngwContent);
		router.addRegion(ngwSubLeft);
		router.addRegion(ngwSubRight);
		router.setNewgameSpeciesContainer(ngsScroll);
		router.setNewgameBackgroundContainer(ngbScroll);
		router.setNewgameSpeciesPanels(ngsSimple, ngsIntermediate, ngsAdvanced);
		router.setNewgameBackgroundPanels(ngbWarrior, ngbZealot, ngbAdventurer,
				ngbWarMage, ngbMage);
		router.setNewgameSubPanels(ngsDesc, ngsSubLeft, ngsSubRight,
				ngbDesc, ngbSubLeft, ngbSubRight);
		router.setNewgameWeaponContainer(ngwScroll);
		router.setNewgameWeaponPanels(ngwContent, ngwSubLeft, ngwSubRight);
		router.setFontConfig(fontConfig);
		router.setShowLoadingMessage(getIntent().getBooleanExtra(
				SplashActivity.EXTRA_ASSETS_FRESHLY_INSTALLED, false));
		router.setRedrawRequester(() -> gameKeyListener.nativew.redrawScreen());
		portraitRouter = router;
		// The newgame desc panels live in their own LinearLayout containers
		// (newgameSpecies/newgameBackground), so DirectionalTouchView's
		// default fullView/skillsView/msgView candidate set never picks
		// them as drag-scroll targets. Register them explicitly so dragging
		// over the description text scrolls it horizontally. Same applies
		// to quickControlsView — it lives in menuStack, isn't fullView/
		// skillsView/msgView, so without this registration drags over the
		// QC panel are swallowed by DirectionalTouchView's 9-grid tap
		// handler and the panel never scrolls.
		portraitExtraScrollTargets = new View[] {
				ngsDesc, ngbDesc, quickControlsView, ngsScroll, ngbScroll,
				ngwScroll };

		final float MIN_FONT_SCALE = 0.3f;
		final float MIN_SCALE_DELTA = 0.01f;

		gamePanel.getViewTreeObserver().addOnGlobalLayoutListener(
				new ViewTreeObserver.OnGlobalLayoutListener()
				{
					private int lastAvailable = -1;
					private int lastHudH = -1;
					private int lastMsgH = -1;
					private int lastStatusH = -1;
					private int lastMlistH = -1;

					@Override
					public void onGlobalLayout()
					{
						if (splitContainer.getVisibility() != View.VISIBLE)
							return;

						int available = gamePanel.getHeight();
						int hudH = hudView.getMeasuredHeight();
						int msgH = msgView.getMeasuredHeight();
						int statusH = statusBar.getMeasuredHeight();
						int mlistH = mlistView.getMeasuredHeight();
						if (available <= 0 || hudH <= 0 || msgH <= 0)
							return;

						if (available == lastAvailable
								&& hudH == lastHudH && msgH == lastMsgH
								&& statusH == lastStatusH
								&& mlistH == lastMlistH)
							return;
						lastAvailable = available;
						lastHudH = hudH;
						lastMsgH = msgH;
						lastStatusH = statusH;
						lastMlistH = mlistH;

						int mapTarget = available - hudH - statusH
								- mlistH - msgH;
						if (mapTarget <= 0)
							return;
						int mapH = mapView.getMeasuredHeight();
						if (mapH <= mapTarget)
							return;

						float curMapScale = mapView.getFontScaleMultiplier();
						if (curMapScale <= MIN_FONT_SCALE)
							return;

						float ratio = (mapTarget - 1f) / mapH;
						float newScale = Math.max(MIN_FONT_SCALE,
								curMapScale * ratio);
						if (curMapScale - newScale < MIN_SCALE_DELTA)
							return;
						mapView.setFontScaleMultiplier(newScale);
					}
				});

		return router;
	}

	private RegionTermView makeNewgameCategoryView(int row0, int col0, int row1, int col1)
	{
		FontConfig fc = FontConfig.load(getAssets());
		RegionTermView v = new RegionTermView(this, row0, col0, row1, col1);
		v.setFontScaleMultiplier(fc.portraitNewgameCategoryFontScale);
		v.setFontReferenceCols(RegionRouter.TERMINAL_COLS);
		v.setHorizontalScrollEnabled(fc.portraitNewgameCategoryScrollable);
		v.setVerticalScrollEnabled(fc.portraitNewgameCategoryVScrollable);
		return v;
	}

	private RegionTermView makeNewgameSubView()
	{
		FontConfig fc = FontConfig.load(getAssets());
		// Initial bounds span the full sub row range across all 80 cols; the
		// router narrows each panel to its left/right col range once the
		// upstream sub-items grid is rendered and title-scan resolves the
		// col-1 anchor.
		RegionTermView v = new RegionTermView(this,
				RegionRouter.NEWGAME_SUB_ROW0, RegionRouter.NEWGAME_COL_LEFT,
				RegionRouter.NEWGAME_SUB_ROW1, RegionRouter.NEWGAME_COL_FULL_END);
		v.setFontScaleMultiplier(fc.portraitNewgameSubFontScale);
		v.setFontReferenceCols(RegionRouter.TERMINAL_COLS);
		v.setHorizontalScrollEnabled(fc.portraitNewgameSubScrollable);
		v.setVerticalScrollEnabled(fc.portraitNewgameSubVScrollable);
		return v;
	}

	private RegionTermView makeNewgameDescView()
	{
		FontConfig fc = FontConfig.load(getAssets());
		// Description panel — full-width, rendered above the sub-options
		// grid. Uses its own scale/scroll knobs so the user can enable
		// horizontal drag-scrolling on the description text independently
		// of the sub-options panels.
		RegionTermView v = new RegionTermView(this,
				RegionRouter.NEWGAME_SUB_ROW0, RegionRouter.NEWGAME_COL_LEFT,
				RegionRouter.NEWGAME_SUB_ROW1, RegionRouter.NEWGAME_COL_FULL_END);
		v.setFontScaleMultiplier(fc.portraitNewgameDescFontScale);
		v.setFontReferenceCols(RegionRouter.TERMINAL_COLS);
		v.setHorizontalScrollEnabled(fc.portraitNewgameDescScrollable);
		v.setVerticalScrollEnabled(fc.portraitNewgameDescVScrollable);
		return v;
	}

	// Read assets/quick_controls.txt and return one entry per line with tabs
	// expanded to 4 spaces. Returns an empty array on I/O failure so the
	// panel still measures normally; the user sees a blank QC area rather
	// than a crash. UTF-8 because the file contains directional arrow chars
	// outside ASCII.
	private String[] loadQuickControls()
	{
		final String QUICK_CONTROLS_ASSET = "quick_controls.txt";
		final String TAB = "    ";
		List<String> lines = new ArrayList<>();
		try (InputStream is = getAssets().open(QUICK_CONTROLS_ASSET);
				BufferedReader br = new BufferedReader(
						new InputStreamReader(is, StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = br.readLine()) != null)
				lines.add(line.replace("\t", TAB));
		}
		catch (IOException e)
		{
			Log.w("GameActivity", "Could not load " + QUICK_CONTROLS_ASSET
					+ ": " + e.getMessage());
		}
		return lines.toArray(new String[0]);
	}

	private TerminalRenderer buildLandscapeLayout(boolean hapticFeedbackEnabled) {
		gamePanelId = View.NO_ID;
		portraitMsgView = null;
		portraitFullView = null;
		portraitSkillsView = null;
		portraitItemsView = null;
		portraitMapView = null;
		portraitFontConfig = null;
		portraitRouter = null;
		portraitExtraScrollTargets = null;
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
		if (portraitStatusBar != null)
			view.setStatusBarView(portraitStatusBar);
		if (portraitFullView != null)
			view.setMenuView(portraitFullView);
		if (portraitSkillsView != null)
			view.setSkillsView(portraitSkillsView);
		if (portraitItemsView != null)
			view.setItemsView(portraitItemsView);
		if (portraitRouter != null)
			view.setRouter(portraitRouter);
		if (portraitExtraScrollTargets != null)
			view.setExtraScrollTargets(portraitExtraScrollTargets);
		if (portraitMapView != null && portraitFontConfig != null)
			view.setMapZoom(portraitMapView,
					portraitFontConfig.portraitMapZoomStep1,
					portraitFontConfig.portraitMapZoomStep2);

		view.setHapticFeedbackEnabled(hapticFeedbackEnabled);
		screenLayout.addView(view);
		portraitDirectionalView = view;
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

		// When Android resumes the activity from the recents/task switcher,
		// our view-tree state survives but DCSS only repaints on its own
		// update cycle — so any single-column remapped panels (skills, the
		// newgame sub fold) can show stale 1:1 pre-fold content carried over
		// from before the pause. Ask DCSS to replay the current screen once
		// after layout settles. Posted so it runs after the resume layout
		// pass restores any view dimensions.
		if (gameKeyListener != null && gameKeyListener.nativew != null) {
			final NativeWrapper nw = gameKeyListener.nativew;
			View root = findViewById(android.R.id.content);
			if (root != null)
				root.post(nw::redrawScreen);
		}
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
		WindowCompatAdapter.applyFullscreen(this, Preferences.getFullScreen());
	}

	// Scale raw corner radius to inscribed-rectangle inset: R*(1-cos45°).
	@android.annotation.SuppressLint("NewApi")
	private static int cornerRadius(android.view.WindowInsets insets, int position)
	{
		android.view.RoundedCorner corner = insets.getRoundedCorner(position);
		if (corner == null)
			return 0;
		return (int) Math.ceil(corner.getRadius() * (1.0 - Math.cos(Math.PI / 4)));
	}

	public Handler getHandler() {
		return handler;
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
