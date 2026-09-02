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
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
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
import android.widget.Toast;
import android.os.Handler;
import android.os.Message;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.crawlmb.CrawlDialog;
import com.crawlmb.CustomFolderSync;
import com.crawlmb.FontConfig;
import com.crawlmb.IconConfig;
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
import com.crawlmb.view.FoldStateController;
import com.crawlmb.view.GridOverlayController;
import com.crawlmb.view.QuickControlsView;
import com.crawlmb.view.RegionRouter;
import com.crawlmb.view.RegionTermView;
import com.crawlmb.view.HudButtonController;
import com.crawlmb.view.ModalOverlayController;
import com.crawlmb.view.RepositionController;
import com.crawlmb.view.StatusBarView;
import com.crawlmb.view.TerminalRenderer;
import com.crawlmb.view.TermView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
	private RegionTermView portraitHudView = null;
	private RegionTermView portraitMlistView = null;
	private RelativeLayout portraitSplitContainer = null;
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

	// Reposition In-Game UI mode (see RepositionController). Requested from
	// PreferencesActivity via the "repositionUi" result extra; entered after
	// the post-return rebuildViews' first layout pass has sized the panels.
	private boolean pendingRepositionEntry = false;
	private RepositionController repositionController = null;
	// Reposition Grid Overlay mode (see GridOverlayController). Requested
	// from PreferencesActivity via the "repositionGrid" result extra;
	// entered after a layout pass has sized the keyboard for the button bar.
	private boolean pendingGridOverlayEntry = false;
	private GridOverlayController gridOverlayController = null;
	// On-screen HUD shortcut buttons (help / wiki). Recreated each
	// rebuildViews() so they re-anchor to the HUD's current slot.
	private HudButtonController hudButtonController = null;
	// Modal window opened by the HUD shortcut buttons. Recreated each
	// rebuildViews() so it attaches to the current screenLayout.
	private ModalOverlayController modalController = null;
	// Wiki modal browser-back support. Each link tap swaps in a fresh WebView
	// (see createWikiWebView), so there's no in-WebView history — we track the
	// visited URLs ourselves and rebuild the previous page on BACK.
	private final ArrayDeque<String> wikiBackStack = new ArrayDeque<>();
	private WebView currentWikiWebView = null;
	// Centered "Loading wiki…" placeholder shown over the WebView until the page
	// finishes painting; toggled by createWikiWebView's load callbacks.
	private TextView wikiLoadingView = null;
	private boolean wikiModalActive = false;
	// Last bottom safe-area inset, captured by the inset listener for the
	// reposition button bar in the no-keyboard case.
	private int lastBottomInset = 0;

	protected Handler handler = null;

	// Unfolded (foldable) mode. unfoldedActive tracks the current posture-driven
	// state used by buildPortraitLayout; foldPosture holds the latest hinge
	// geometry (window coords) for the split. Both are updated by the
	// FoldStateController callback, which rebuilds the view tree on transitions.
	private boolean unfoldedActive = false;
	// HALF mode: device open with a usable hinge but unfolded mode OFF — the
	// normal single-screen layout is confined to one half (keyboard side; Both
	// => right), the other half black. Derived from posture + !unfoldedActive.
	private boolean halfActive = false;
	private FoldStateController.Posture foldPosture = null;
	private FoldStateController foldStateController = null;

	// Keyboard/half geometry for the current fold mode, recomputed at the top of
	// rebuildViews. kbConfine => keyboard pinned to one half (kbHalfWidth px,
	// kbHalfLeft side). In HALF mode the whole single-screen UI shares this half;
	// in UNFOLDED it is only the keyboard's half (Both => no confine).
	private boolean kbConfine = false;
	private int kbHalfWidth = 0;
	private boolean kbHalfLeft = false;
	// UNFOLDED menu confinement: half-type menus (main menu, lists, char
	// creation) render on the keyboard-side half (Both => right); wide-text
	// menus span both halves. Independent of kbConfine (Both keyboard is full
	// width but its menus still confine to the right half).
	private boolean menuConfine = false;
	private int menuHalfWidth = 0;
	private boolean menuHalfLeft = false;
	// Half containers of the unfolded split, so the auto-fit listener can fit
	// each half to its own height (the keyboard-side half is shortened).
	private View unfoldedMapHalf = null;
	private View unfoldedPanelHalf = null;

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

		// Mark the device foldable up front (hinge sensor, API 30+) so the
		// unfolded preferences are exposed even before the first unfold.
		// FoldStateController also sets this when it observes a fold feature.
		if (VERSION.SDK_INT >= VERSION_CODES.R
				&& getPackageManager().hasSystemFeature(
						PackageManager.FEATURE_SENSOR_HINGE_ANGLE))
			Preferences.setFoldableSeen(true);
	}

	@Override
	public void onStart() {
		super.onStart();

		if (dialog == null)
			dialog = new CrawlDialog(this, gameKeyListener);
		final CrawlDialog crawlDialog = dialog;
		handler = new GameHandler(crawlDialog);

		rebuildViews();

		// Start posture tracking after the first rebuild. The initial callback
		// arrives asynchronously on the main thread; if it reports a different
		// unfolded state than the one we just built for, onFoldStateChanged rebuilds.
		if (foldStateController == null)
			foldStateController = new FoldStateController(this,
					this::onFoldStateChanged);
		foldStateController.start();
	}

	// Posture callback (main thread). Adopts the new hinge geometry and, when
	// the unfolded state flips, rebuilds the view tree into the matching
	// layout. Also rebuilds when the split geometry shifts materially while
	// staying unfolded (e.g. the hinge position changes between devices/emulator
	// configs).
	private void onFoldStateChanged(boolean newUnfoldedActive,
			FoldStateController.Posture posture) {
		// HALF mode: open with a usable hinge but the mode gate is off.
		boolean newHalfActive = posture != null && !newUnfoldedActive;
		boolean geometryChanged = (newUnfoldedActive || newHalfActive)
				&& (unfoldedActive || halfActive)
				&& !sameSplit(foldPosture, posture);
		foldPosture = posture;
		if (newUnfoldedActive == unfoldedActive && newHalfActive == halfActive
				&& !geometryChanged)
			return;
		unfoldedActive = newUnfoldedActive;
		halfActive = newHalfActive;
		rebuildViews();
		if (gameKeyListener != null && gameKeyListener.nativew != null)
		{
			final NativeWrapper nw = gameKeyListener.nativew;
			View root = findViewById(android.R.id.content);
			if (root != null)
				root.post(nw::redrawScreen);
		}
	}

	// Resolve the keyboard/half geometry for the current fold mode. In HALF mode
	// the whole single-screen UI shares the keyboard-side half (Both => right);
	// in UNFOLDED only the keyboard is confined (Both => full width, no confine).
	private void computeKbHalf() {
		kbConfine = false;
		kbHalfWidth = 0;
		kbHalfLeft = false;
		menuConfine = false;
		menuHalfWidth = 0;
		menuHalfLeft = false;
		if (foldPosture == null || !(unfoldedActive || halfActive))
			return;
		String side = Preferences.getUnfoldedKeyboardSide();
		boolean both = Preferences.SIDE_BOTH.equals(side);
		if (both)
			side = Preferences.SIDE_RIGHT; // Both => right half
		kbHalfLeft = Preferences.SIDE_LEFT.equals(side);
		kbHalfWidth = kbHalfLeft ? foldPosture.leftWidth
				: foldPosture.totalWidth - foldPosture.rightStart;
		// UNFOLDED Both keeps the keyboard full width (no keyboard confine), but
		// its half-type menus still confine to the right half.
		kbConfine = kbHalfWidth > 0 && !(unfoldedActive && both);
		// Per-menu confinement only applies in UNFOLDED (HALF mode already
		// confines the whole single-screen UI via gamePanel's half width).
		menuConfine = unfoldedActive && kbHalfWidth > 0;
		menuHalfWidth = kbHalfWidth;
		menuHalfLeft = kbHalfLeft;
	}

	// UNFOLDED Left/Right: shorten the keyboard-side split half by the keyboard's
	// height so its panels reflow above the keyboard while the other half stays
	// full height. Runs once the keyboard view has a measured height.
	private void reserveKeyboardHalf(final View keyboardView) {
		boolean mapLeft = Preferences.SIDE_LEFT.equals(
				Preferences.getUnfoldedMapSide());
		final View half = (mapLeft == kbHalfLeft)
				? unfoldedMapHalf : unfoldedPanelHalf;
		if (half == null)
			return;
		keyboardView.getViewTreeObserver().addOnGlobalLayoutListener(
				new ViewTreeObserver.OnGlobalLayoutListener() {
			@Override
			public void onGlobalLayout() {
				int kbHeight = keyboardView.getHeight();
				if (kbHeight <= 0)
					return;
				keyboardView.getViewTreeObserver()
						.removeOnGlobalLayoutListener(this);
				ViewGroup.MarginLayoutParams lp =
						(ViewGroup.MarginLayoutParams) half.getLayoutParams();
				if (lp.bottomMargin != kbHeight)
				{
					lp.bottomMargin = kbHeight;
					half.setLayoutParams(lp);
				}
				// Confined menus on the keyboard-side half must clear the
				// keyboard too (the panel is full height here).
				if (portraitRouter != null)
					portraitRouter.setUnfoldedMenuKbReserve(kbHeight);
			}
		});
	}

	private static boolean sameSplit(FoldStateController.Posture a,
			FoldStateController.Posture b) {
		if (a == null || b == null)
			return a == b;
		return a.leftWidth == b.leftWidth && a.rightStart == b.rightStart;
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
			intent.putExtra("gameInProgress", NativeWrapper.gameInProgress());
			startActivityForResult(intent, PREFERENCES_FINISHED);
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
            	if (data.getBooleanExtra("repositionUi", false))
            		pendingRepositionEntry = true;
            	if (data.getBooleanExtra("repositionGrid", false))
            		pendingGridOverlayEntry = true;
            }
        }
    }

	@Override
	public void finish() {
		// Log.d("Crawl","finish");
		gameKeyListener.gameThread.send(GameThread.Request.StopGame);
		// 5s cap so a wedged DocumentsProvider can't stall quit.
		CustomFolderSync.pushBlocking(this, 5000);
		super.finish();
	}

	private void rebuildViews() {
		synchronized (GameKeyListener.progress_lock) {
			// Log.d("Crawl","rebuildViews");

			computeKbHalf();

			// Portrait-only for single-screen. Landscape support is retained in
			// code (buildLandscapeLayout, Preferences.getLandscapeKeyboard, etc.)
			// for future re-enable, but the orientation picker is hidden in
			// preferences.xml and the stored crawl.orientation pref is ignored.
			//
			// In unfolded (foldable) mode we drop the portrait lock: on the unfolded
			// landscape inner display a portrait-locked app is letterboxed to a
			// centered ~portrait window, so each hinge half only gets half of
			// THAT (wasting the outer thirds of each physical screen). Releasing
			// to UNSPECIFIED lets the window fill the whole display, so each half
			// spans a full physical screen and the split lands on the hinge. The
			// unfolded builder still lays out portrait content in each half. If the
			// device is rotated out of book posture the fold goes HORIZONTAL,
			// unfoldedActive drops, and we snap back to the portrait lock below.
			// HALF mode also fills the open display (single-screen content on one
			// half); only true single-screen keeps the portrait lock.
			setRequestedOrientation((unfoldedActive || halfActive)
					? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
					: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

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

				lastBottomInset = bottom;

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
				CrawlKeyboardWrapper virtualKeyboard = kbConfine
						? new CrawlKeyboardWrapper(this, gameKeyListener,
								kbHalfWidth, kbHalfLeft)
						: new CrawlKeyboardWrapper(this, gameKeyListener);
				virtualKeyboard.virtualKeyboardView
						.setHapticFeedbackEnabled(hapticFeedbackEnabled);
				screenLayout.addView(virtualKeyboard.virtualKeyboardView);
				portraitKeyboardView = virtualKeyboard.virtualKeyboardView;
				if (portraitRouter != null)
					portraitRouter.setKeyboardView(
							virtualKeyboard.virtualKeyboardView);

				// Anchor the game panel above the keyboard EXCEPT in UNFOLDED
				// Left/Right: there the keyboard covers only its half, so the
				// panel keeps full height and only the keyboard-side half is
				// shortened (see reserveKeyboardHalf below).
				boolean anchorAbove = !(unfoldedActive && kbConfine);
				if (anchorAbove && gamePanelId != View.NO_ID)
				{
					View gamePanel = screenLayout.findViewById(gamePanelId);
					if (gamePanel != null)
					{
						RelativeLayout.LayoutParams gp = (RelativeLayout.LayoutParams) gamePanel.getLayoutParams();
						gp.addRule(RelativeLayout.ABOVE, virtualKeyboard.virtualKeyboardView.getId());
						gamePanel.setLayoutParams(gp);
					}
				}
				else if (unfoldedActive && kbConfine)
					reserveKeyboardHalf(virtualKeyboard.virtualKeyboardView);

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
				// None: DirectionalTouchView is still the only tap-to-key and
				// two-finger-longpress surface, so add it here too (fills the
				// screen — no keyboard strip below). NO_ID = no ABOVE anchor.
				addDirectionalKeyView(View.NO_ID, hapticFeedbackEnabled);
			}

			// On-screen HUD shortcut buttons, added last so they layer above
			// DirectionalTouchView and win taps over the touch overlay. Empty
			// container area passes touches through. Recreated each rebuild so
			// they re-anchor to the HUD's current slot after a reposition.
			if (portraitHudView != null)
			{
				IconConfig iconConfig = IconConfig.load(getAssets());
				modalController = new ModalOverlayController(this, screenLayout,
						iconConfig, this::restoreKeyboardAfterReload);
				hudButtonController = new HudButtonController(this, screenLayout,
						portraitHudView, iconConfig, portraitDirectionalView,
						new HudButtonController.Callbacks()
						{
							@Override
							public boolean isHelpEnabled() {
								return Preferences.getHelpButtonEnabled();
							}
							@Override
							public boolean isWikiEnabled() {
								return Preferences.getWikiButtonEnabled();
							}
							@Override
							public boolean isLongpressMode() {
								return Preferences.getHudButtonLongpressEnabled();
							}
							@Override
							public boolean isOverlayActive() {
								return (repositionController != null
										&& repositionController.isActive())
									|| (gridOverlayController != null
										&& gridOverlayController.isActive())
									|| (modalController != null
										&& modalController.isActive());
							}
							@Override
							public void onHelpTapped() {
								showHelpModal();
							}
							@Override
							public void onWikiTapped() {
								showWikiModal();
							}
						});
			}
			else
			{
				hudButtonController = null;
				modalController = null;
			}

			setContentView(screenLayout);
			dialog.restoreDialog();

			if (reloadOverlayActive)
				enterReloadState();

			if (pendingRepositionEntry)
				schedulePendingRepositionEntry();

			if (pendingGridOverlayEntry)
				schedulePendingGridOverlayEntry();
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

	// Base size (SP) of the info-modal text before help_modal_font_scale.
	private static final float HELP_MODAL_BASE_SP = 13f;
	// DCSS LIGHTGRAY — the terminal default colour for text with no colour tag.
	private static final int CRAWL_LIGHTGRAY = 0xFFC0C0C0;

	// Info button: the '?' command keyhelp, pulled live from native so it
	// reflects the player's actual (remappable) keybindings. It's 2-column
	// monospace-aligned, so render fixed-width WITHOUT wrap and let the 2D
	// scroll modal handle overflow — wrapping would shear the columns. Only
	// valid mid-game; on the main menu there are no bindings to report.
	private void showHelpModal() {
		if (modalController == null)
			return;
		wikiModalActive = false;
		TextView tv = new TextView(this);
		if (NativeWrapper.gameInProgress())
			tv.setText(parseCrawlColourString(NativeWrapper.getCommandHelp()));
		else
			tv.setText("Open a game to see the control keys.");
		tv.setTextColor(CRAWL_LIGHTGRAY);
		float scale = portraitFontConfig != null
				? portraitFontConfig.helpModalFontScale : 0.8f;
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, HELP_MODAL_BASE_SP * scale);
		tv.setTypeface(Typeface.MONOSPACE);
		modalController.show(tv, ModalOverlayController.SCROLL_BOTH);
	}

	// The 16 DCSS terminal colours (colour_to_str names), matching the ARGB
	// palette the console renderer uses (colourMap in libandroid.cc), so the
	// info modal's text colours match the in-game '?' help exactly. "gray"
	// aliases included for safety; parse_string accepts them too.
	private static final Map<String, Integer> CRAWL_COLOURS = buildCrawlColours();
	private static Map<String, Integer> buildCrawlColours() {
		Map<String, Integer> m = new HashMap<>();
		m.put("black", 0xFF000000);
		m.put("blue", 0xFF0040FF);
		m.put("green", 0xFF008040);
		m.put("cyan", 0xFF00A0A0);
		m.put("red", 0xFFFF4040);
		m.put("magenta", 0xFF9020FF);
		m.put("brown", 0xFFA64800);
		m.put("lightgrey", CRAWL_LIGHTGRAY);
		m.put("lightgray", CRAWL_LIGHTGRAY);
		m.put("darkgrey", 0xFF606060);
		m.put("darkgray", 0xFF606060);
		m.put("lightblue", 0xFF00FFFF);
		m.put("lightgreen", 0xFF00FF00);
		m.put("lightcyan", 0xFF20FFDC);
		m.put("lightred", 0xFFFF5050);
		m.put("lightmagenta", 0xFFFA4FFD);
		m.put("yellow", 0xFFFFFF00);
		m.put("white", 0xFFFFFFFF);
		return m;
	}

	// Convert a DCSS to_colour_string() output into a coloured CharSequence.
	// Grammar: open-only "<colour>" tags set the current foreground until the
	// next tag; "<<" is a literal '<'; "<bg:colour>" (background) is ignored.
	// Unknown tags are dropped. Text before any tag is LIGHTGRAY.
	private CharSequence parseCrawlColourString(String s) {
		SpannableStringBuilder out = new SpannableStringBuilder();
		int cur = CRAWL_LIGHTGRAY;
		int i = 0, n = s.length();
		while (i < n) {
			char ch = s.charAt(i);
			if (ch == '<' && i + 1 < n && s.charAt(i + 1) == '<') {
				appendColoured(out, "<", cur);
				i += 2;
				continue;
			}
			if (ch == '<') {
				int close = s.indexOf('>', i + 1);
				if (close < 0) {
					appendColoured(out, s.substring(i), cur);
					break;
				}
				String tag = s.substring(i + 1, close).toLowerCase(Locale.ROOT);
				i = close + 1;
				if (tag.startsWith("bg:"))
					continue;
				Integer c = CRAWL_COLOURS.get(tag);
				if (c != null)
					cur = c;
				continue;
			}
			int next = s.indexOf('<', i);
			if (next < 0)
				next = n;
			appendColoured(out, s.substring(i, next), cur);
			i = next;
		}
		return out;
	}

	private static void appendColoured(SpannableStringBuilder out, String text,
			int colour) {
		if (text.isEmpty())
			return;
		int start = out.length();
		out.append(text);
		out.setSpan(new ForegroundColorSpan(colour), start, out.length(),
				Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
	}

	// Landing page: the Ashenzaris_Archive quick-reference (also the target of
	// WIKI_JUMP_JS's "Character Attributes" jump).
	private static final String WIKI_LANDING_URL =
			"https://dcss.roguelikes.gg/wiki/Ashenzaris_Archive";

	// Allowlist base: any content page under the wiki. Taps to URLs outside this
	// prefix (external sites, the hamburger's "Play DCSS" links) are blocked;
	// see wikiBlockReason, which also blocks the Special: namespace within it.
	private static final String WIKI_ALLOW_PREFIX =
			"https://dcss.roguelikes.gg/wiki/";

	// Shown in place of the page when the load fails (no network etc.). The
	// modal shell can't scroll a WebView (it scrolls itself), so this is a
	// self-contained dark page the WebView renders directly.
	private static final String WIKI_OFFLINE_HTML =
			"<!DOCTYPE html><html><head><meta name='viewport' "
			+ "content='width=device-width, initial-scale=1'>"
			+ "<style>html,body{height:100%;margin:0;background:#0a0a0a;"
			+ "color:#e0e0e0;font-family:sans-serif}"
			+ "div{position:absolute;top:50%;left:0;right:0;"
			+ "transform:translateY(-50%);padding:0 24px;text-align:center}"
			+ "a{color:#8f9fff}</style></head><body><div>"
			+ "<h2>Wiki unavailable</h2>"
			+ "<p>Couldn't load the DCSS wiki. Check your internet "
			+ "connection and try again.</p>"
			+ "<p>" + WIKI_LANDING_URL + "</p>"
			+ "</div></body></html>";

	// Viewport meta forced into every main-frame HTML response (see
	// rewriteMainFrame). initial-scale=0.8 must be present at first parse — not
	// applied as a post-load mutation — or the visual zoom and the touch
	// hit-test regions desync and link taps miss. width=device-width keeps the
	// responsive mobile layout; the scale range keeps pinch-zoom enabled.
	private static final String WIKI_VIEWPORT =
			"<meta name=\"viewport\" content=\"width=device-width, "
			+ "initial-scale=0.8, minimum-scale=0.25, maximum-scale=5\">";

	// Injected into every main-frame HTML response (see rewriteMainFrame).
	// Rule 1: wide blocks — data tables (spell/species pages), <pre> — otherwise
	// get clipped at the viewport edge with no way to reach the cut-off columns;
	// making each a block-level scroll box lets it pan horizontally on its own
	// while the page keeps its responsive fit and 0.8 zoom.
	// Rule 2: the wiki's night-theme CSS sets `background:` (shorthand) on
	// wikitable th, which resets background-repeat to `repeat` and tiles the
	// sortable-header arrow across the whole cell; restore no-repeat + right
	// alignment (the arrow image and dark header colour still come from the
	// skin). !important beats the skin's non-important rules.
	// Rule 3: content "description" boxes (the italic god-lore quote, the
	// bordered tip/warning callouts) carry a hardcoded near-white inline
	// background — #f5faff on the lore <table>, #fbfbfb on the callouts — that
	// the night theme leaves untouched (author inline styles win), while it
	// recolours the text light, giving light-grey-on-white unreadable boxes.
	// Match by that inline background value and force a dark surface + light
	// text; links keep their theme colour. Saturated data cells (aptitude
	// grid #33FF66/#99CCFF/...) and the coloured info banners (#fc6/#e0fae0)
	// have their own dark text and stay readable, so they're left alone.
	private static final String WIKI_WIDE_CSS =
			"<style>table,pre{display:block !important;max-width:100% !important;"
			+ "overflow-x:auto !important;-webkit-overflow-scrolling:touch}"
			+ ".jquery-tablesorter th.headerSort,"
			+ ".sortable:not(.jquery-tablesorter)>*>tr:first-child>th{"
			+ "background-repeat:no-repeat !important;"
			+ "background-position:center right !important}"
			+ ".mw-parser-output [style*=\"#f5faff\" i],"
			+ ".mw-parser-output [style*=\"#fbfbfb\" i]"
			+ "{background:#1c1c1c !important;color:#d8d8d8 !important}"
			+ ".mw-parser-output [style*=\"#f5faff\" i] *:not(a),"
			+ ".mw-parser-output [style*=\"#fbfbfb\" i] *:not(a)"
			+ "{color:#d8d8d8 !important}"
			+ "</style>";

	// Matches the UA-rendered surfaces (default page background, scrollbars,
	// form controls) to the forced night theme and avoids a light flash before
	// the site CSS paints. The site's own dark styling is driven by the theme
	// class (see forceCitizenDark), not this.
	private static final String WIKI_DARK_META =
			"<meta name=\"color-scheme\" content=\"dark\">";

	// Runs once after the first load: jump to the "Character Attributes" section.
	// It's a styled <div>, not a heading, so match by exact text on any element
	// and retry briefly while the Citizen skin reflows.
	private static final String WIKI_JUMP_JS =
			"(function(){var n=0;function j(){var a=document.querySelectorAll("
			+ "'div,span,h1,h2,h3,h4,p,strong,b,td,th,a');var t=null;"
			+ "for(var i=0;i<a.length;i++){"
			+ "if((a[i].textContent||'').trim()==='Character Attributes')"
			+ "{t=a[i];break;}}"
			+ "if(t){t.scrollIntoView(true);return;}"
			+ "if(n++<12){setTimeout(j,150);}}j();})();";

	private void showWikiModal() {
		if (modalController == null)
			return;
		wikiBackStack.clear();
		wikiBackStack.push(WIKI_LANDING_URL);
		wikiModalActive = true;
		currentWikiWebView = createWikiWebView(WIKI_LANDING_URL);
		// The WebView paints black until its first page loads (a network round
		// trip); overlay a centered placeholder that its load callbacks clear.
		FrameLayout container = new FrameLayout(this);
		container.setBackgroundColor(Color.BLACK);
		container.addView(currentWikiWebView, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT));
		wikiLoadingView = new TextView(this);
		wikiLoadingView.setText("Loading wiki…");
		wikiLoadingView.setTextColor(CRAWL_LIGHTGRAY);
		wikiLoadingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
		container.addView(wikiLoadingView, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.WRAP_CONTENT,
				FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
		modalController.show(container, ModalOverlayController.SCROLL_NONE);
	}

	// Rebuild the previous wiki page on BACK. Returns false when there's no
	// history left (caller then dismisses the modal).
	private boolean wikiGoBack() {
		if (currentWikiWebView == null || wikiBackStack.size() <= 1)
			return false;
		wikiBackStack.pop();                 // drop the current page
		swapWikiWebView(currentWikiWebView, wikiBackStack.peek());
		return true;
	}

	// Build a fully configured WebView loading url. The jump to the Character
	// Attributes section runs whenever the loaded page is the landing page —
	// the initial open and every return to it (BACK or a link back), but not
	// other articles.
	//
	// Link taps DON'T navigate in place: this GPU stops drawing a WebView's
	// compositor layers after its first navigation (the page loads and stays
	// scrollable but paints blank). A freshly created WebView always paints its
	// first load, so shouldOverrideUrlLoading swaps in a new WebView per link —
	// every navigation becomes a first load.
	@android.annotation.SuppressLint("SetJavaScriptEnabled")
	private WebView createWikiWebView(String url) {
		WebView web = new WebView(this);
		web.setBackgroundColor(Color.BLACK); // avoid white flash before paint
		WebSettings s = web.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		// Responsive mobile layout (honors the page's width=device-width meta);
		// the 20% zoom-out is baked into the meta at parse time by
		// rewriteMainFrame, so it's the page's initial scale. Pinch-zoom on.
		s.setUseWideViewPort(true);
		s.setLoadWithOverviewMode(true);
		s.setBuiltInZoomControls(true);
		s.setDisplayZoomControls(false);
		// Dark mode comes from the site's OWN night theme (forced on in
		// rewriteMainFrame), not from WebView force-dark — keep algorithmic
		// darkening OFF. Force-dark ran a per-image classifier that inverted
		// low-colour tiles (spell/weapon icons) at random; the site's designed
		// dark theme shows every tile as authored.
		if (WebViewFeature.isFeatureSupported(
				WebViewFeature.ALGORITHMIC_DARKENING))
			WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
		web.setWebViewClient(new WebViewClient() {
			private boolean failed = false;
			private boolean jumped = !WIKI_LANDING_URL.equals(url);
			private void fallback(WebView v) {
				if (failed)
					return;
				failed = true;
				setWikiLoadingVisible(false);
				v.loadDataWithBaseURL(null, WIKI_OFFLINE_HTML,
						"text/html", "utf-8", null);
			}
			// Rewrite the viewport meta in the raw HTML of every top-level
			// navigation, so initial-scale=0.8 is applied at first parse. Doing
			// it here — rather than mutating the DOM after load — keeps the
			// visual scale and the touch hit-test regions in sync, so link taps
			// map correctly. Returns null (WebView loads normally) for
			// non-HTML, non-GET, or on any fetch error.
			@Override
			public android.webkit.WebResourceResponse shouldInterceptRequest(
					WebView v, WebResourceRequest req) {
				return rewriteMainFrame(req);
			}
			// Route user-initiated top-level navigations into a fresh WebView
			// (see method header). Server redirects (isRedirect) stay in place so
			// the current WebView follows them — it's still its first load.
			@Override
			public boolean shouldOverrideUrlLoading(WebView v,
					WebResourceRequest req) {
				if (failed || !req.isForMainFrame() || req.isRedirect())
					return false;
				String u = req.getUrl().toString();
				if (!u.startsWith("http"))
					return false;
				String block = wikiBlockReason(u);
				if (block != null) {
					Toast.makeText(GameActivity.this, block,
							Toast.LENGTH_SHORT).show();
					return true; // consume: keep the user on the current page
				}
				wikiBackStack.push(u);
				swapWikiWebView(v, u);
				return true;
			}
			@Override
			public void onReceivedError(WebView v, WebResourceRequest req,
					WebResourceError err) {
				if (req.isForMainFrame())
					fallback(v);
			}
			@Override
			public void onPageFinished(WebView v, String url) {
				if (failed)
					return;
				setWikiLoadingVisible(false);
				if (!jumped) {
					jumped = true;
					v.evaluateJavascript(WIKI_JUMP_JS, null);
				}
			}
		});
		web.loadUrl(url);
		return web;
	}

	// Navigation policy for wiki link taps. Content pages under the wiki are
	// allowed to browse freely; anything outside the wiki prefix (external
	// sites, the hamburger's "Play DCSS" links) is blocked, as is MediaWiki's
	// Special: namespace (login, account creation, utility pages) and
	// edit/history/raw action links. Returns a toast message when blocked, or
	// null when allowed.
	private String wikiBlockReason(String url) {
		String lower = url.toLowerCase(Locale.ROOT);
		if (!lower.startsWith(WIKI_ALLOW_PREFIX.toLowerCase(Locale.ROOT)))
			return "That link leaves the DCSS wiki.";
		if (lower.contains("/wiki/special:") || lower.contains("title=special:")
				|| lower.contains("action=") || lower.contains("veaction="))
			return "This page isn't available in-app.";
		return null;
	}

	// Replace the WebView currently in the modal with a fresh one loading url,
	// keeping its position under the close button and its layout params.
	private void swapWikiWebView(WebView old, String url) {
		android.view.ViewParent p = old.getParent();
		if (!(p instanceof ViewGroup))
			return;
		ViewGroup parent = (ViewGroup) p;
		int idx = parent.indexOfChild(old);
		ViewGroup.LayoutParams lp = old.getLayoutParams();
		WebView fresh = createWikiWebView(url);
		currentWikiWebView = fresh;
		parent.removeView(old);
		old.destroy();
		parent.addView(fresh, idx, lp);
		// The fresh WebView paints black until the next page loads; show the
		// placeholder again (its onPageFinished/fallback clears it).
		setWikiLoadingVisible(true);
	}

	// Toggle the "Loading wiki…" placeholder. No-op once the modal is gone.
	private void setWikiLoadingVisible(boolean visible) {
		if (wikiLoadingView != null)
			wikiLoadingView.setVisibility(visible ? View.VISIBLE : View.GONE);
	}

	// Fetch a top-level HTML navigation ourselves and swap its viewport meta for
	// WIKI_VIEWPORT, so the page lays out at initial-scale=0.8 from the first
	// paint. Runs on a WebView worker thread, so the blocking fetch is fine.
	// Anything we don't handle (subresources, non-GET, non-HTML, errors) returns
	// null and the WebView loads it the normal way.
	private android.webkit.WebResourceResponse rewriteMainFrame(
			WebResourceRequest req) {
		if (req == null || !req.isForMainFrame()
				|| !"GET".equalsIgnoreCase(req.getMethod()))
			return null;
		java.net.HttpURLConnection c = null;
		try {
			java.net.URL url = new java.net.URL(req.getUrl().toString());
			if (!url.getProtocol().startsWith("http"))
				return null;
			c = (java.net.HttpURLConnection) url.openConnection();
			c.setInstanceFollowRedirects(true);
			c.setConnectTimeout(15000);
			c.setReadTimeout(15000);
			// Forward the request's headers, but drop Accept-Encoding: setting it
			// ourselves disables HttpURLConnection's transparent gzip decoding, so
			// readAll would see raw gzip bytes. Link navigations send this header
			// (the initial loadUrl doesn't) — that's why the first page rendered
			// but link taps loaded garbage.
			java.util.Map<String, String> hdrs = req.getRequestHeaders();
			if (hdrs != null)
				for (java.util.Map.Entry<String, String> e : hdrs.entrySet())
					if (!"Accept-Encoding".equalsIgnoreCase(e.getKey()))
						c.setRequestProperty(e.getKey(), e.getValue());
			String cookie = android.webkit.CookieManager.getInstance()
					.getCookie(url.toString());
			if (cookie != null)
				c.setRequestProperty("Cookie", cookie);
			c.connect();
			String contentType = c.getContentType();
			if (contentType == null
					|| !contentType.toLowerCase().contains("text/html"))
				return null; // let the WebView load non-HTML itself
			String charset = parseCharset(contentType);
			java.io.InputStream in = c.getInputStream();
			String enc = c.getContentEncoding();
			if (enc != null && enc.toLowerCase().contains("gzip"))
				in = new java.util.zip.GZIPInputStream(in);
			String html = readAll(in, charset);
			String modified = injectHeadHtml(
					injectViewport(html), WIKI_DARK_META + WIKI_WIDE_CSS);
			modified = forceCitizenDark(modified);
			return new android.webkit.WebResourceResponse("text/html", charset,
					new java.io.ByteArrayInputStream(
							modified.getBytes(charset)));
		} catch (Exception e) {
			return null; // fall back to normal loading (error path fires later)
		} finally {
			if (c != null)
				c.disconnect();
		}
	}

	private static String parseCharset(String contentType) {
		int i = contentType.toLowerCase().indexOf("charset=");
		if (i >= 0) {
			String cs = contentType.substring(i + 8).trim();
			int semi = cs.indexOf(';');
			if (semi >= 0)
				cs = cs.substring(0, semi).trim();
			cs = cs.replace("\"", "").replace("'", "");
			if (java.nio.charset.Charset.isSupported(cs))
				return cs;
		}
		return "utf-8";
	}

	private static String readAll(java.io.InputStream in, String charset)
			throws java.io.IOException {
		java.io.ByteArrayOutputStream out =
				new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1)
			out.write(buf, 0, n);
		in.close();
		return out.toString(charset);
	}

	// Replace the page's viewport meta with WIKI_VIEWPORT (or insert one after
	// <head> if the page has none), so the mobile layout renders at 80%.
	private static String injectViewport(String html) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile(
				"<meta[^>]*name=[\"']?viewport[\"']?[^>]*>",
				java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
		if (m.find())
			return m.replaceFirst(
					java.util.regex.Matcher.quoteReplacement(WIKI_VIEWPORT));
		java.util.regex.Matcher h = java.util.regex.Pattern.compile(
				"<head[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE)
				.matcher(html);
		if (h.find())
			return html.substring(0, h.end()) + WIKI_VIEWPORT
					+ html.substring(h.end());
		return html;
	}

	// The wiki (Citizen skin) serves anonymous users the light theme
	// (skin-theme-clientpref-day on <html>); its clientPrefs() only overrides
	// that from a stored localStorage pref we never set, so it stays light.
	// Swap the class to the night theme so the site applies its OWN designed
	// dark styling. Every page SSRs this class and every navigation is refetched
	// here, so it's consistent across the modal.
	private static String forceCitizenDark(String html) {
		return html.replace("skin-theme-clientpref-day",
				"skin-theme-clientpref-night");
	}

	// Insert an HTML snippet just inside <head> (no-op if the page has none).
	private static String injectHeadHtml(String html, String snippet) {
		java.util.regex.Matcher h = java.util.regex.Pattern.compile(
				"<head[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE)
				.matcher(html);
		if (h.find())
			return html.substring(0, h.end()) + snippet
					+ html.substring(h.end());
		return html;
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
		unfoldedMapHalf = null;
		unfoldedPanelHalf = null;
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
		mapView.setCenterVertically(true);
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

		// Word wrap: DCSS wraps messages at the visible column count
		// (msg_max_width, passed at game start via NativeWrapper) and the
		// msg window is extended to msgHistoryRows terminal rows. The panel
		// keeps its classic visual slot (portraitMsgVisibleRows) and pins to
		// the newest line; dragging up reveals the extra history rows.
		boolean wordwrap = Preferences.getWordwrap();
		int msgEndRow = wordwrap
				? RegionRouter.MSG_START_ROW + fontConfig.msgHistoryRows
				: RegionRouter.MSG_END_ROW;
		RegionTermView msgView = new RegionTermView(this,
				RegionRouter.MSG_START_ROW, RegionRouter.MSG_START_COL,
				msgEndRow, RegionRouter.MSG_END_COL);
		msgView.setId(View.generateViewId());
		msgView.setFontScaleMultiplier(fontConfig.portraitMsgFontScale);
		if (wordwrap)
		{
			msgView.setVerticalScrollEnabled(true);
			msgView.setMaxVisibleRows(fontConfig.portraitMsgVisibleRows);
			msgView.setStickyScrollToBottom(true);
		}
		else
		{
			msgView.setHorizontalScrollEnabled(true);
		}
		portraitMsgView = msgView;

		StatusBarView statusBar = new StatusBarView(this);
		statusBar.setId(View.generateViewId());
		portraitStatusBar = statusBar;
		String gameFontFace = Preferences.getFontFace();
		Typeface gameTf = StatusBarView.loadGameTypeface(this, gameFontFace);
		statusBar.setTypeface(gameTf);
		int screenWidth = getResources().getDisplayMetrics().widthPixels;
		int hudCols = RegionRouter.HUD_END_COL - RegionRouter.HUD_START_COL;
		// In unfolded mode the hud/status share one half; the hud width-fits its 43
		// cols to the half at portraitHudFontScale (see buildUnfoldedSplitContainer),
		// so size the status lights to the same 43-col-per-half fit at the same
		// scale. Single-screen uses the full-window basis. statusScale keeps the
		// config value in both.
		int statusFitWidth = screenWidth;
		float statusScale = fontConfig.portraitHudFontScale;
		if (unfoldedActive && foldPosture != null)
		{
			boolean statusMapLeft = Preferences.getUnfoldedMapSide()
					.equals(Preferences.SIDE_LEFT);
			statusFitWidth = statusMapLeft
					? Math.max(1, foldPosture.totalWidth - foldPosture.rightStart)
					: Math.max(1, foldPosture.leftWidth);
		}
		// Width-fit VeraMoBd, then scale the chosen face so its line height
		// matches VeraMoBd's — keeps status-bar height consistent regardless
		// of which face the user picked.
		// HALF mode: the whole single-screen UI is confined to one half, so
		// fit the status lights to that half width.
		if (halfActive && kbConfine)
			statusFitWidth = Math.max(1, kbHalfWidth);
		int refBase = com.crawlmb.view.GameFontShaper.widthFitTextSize(
				this, hudCols, statusFitWidth, 2, 200);
		float matchedBase = com.crawlmb.view.GameFontShaper.matchReferenceLineHeight(
				this, gameTf, refBase);
		float statusFontPx = Math.round(matchedBase * statusScale);
		statusBar.setFontSizePx(statusFontPx);
		Paint gamePaint = new Paint();
		gamePaint.setTypeface(gameTf);
		gamePaint.setTextSize(statusFontPx);
		int statusBarHeight = (int) Math.ceil(gamePaint.getFontSpacing());
		int charWidthPx = (int) gamePaint.measureText("X");
		statusBar.setPadding(
				charWidthPx * fontConfig.portraitHudOffsetCols, 0, 0, 0);

		portraitHudView = hudView;
		portraitMlistView = mlistView;

		// splitRoot is the container the router toggles VISIBLE/INVISIBLE for
		// the in-game view. In unfolded (foldable) mode it's a horizontal
		// two-half split; otherwise the classic single-column stack.
		final ViewGroup splitRoot;
		if (unfoldedActive && foldPosture != null)
		{
			splitRoot = buildUnfoldedSplitContainer(mapView, hudView, statusBar,
					mlistView, msgView, statusBarHeight);
			// Single-screen reposition/applyMode forced-visibility path is not
			// used in unfolded mode; null it so schedulePendingRepositionEntry and
			// friends skip (unfolded reposition is a separate mode).
			portraitSplitContainer = null;
		}
		else
		{
			// Stack the panel units (hud unit = hud + statusBar) in the
			// persisted vertical order (crawl.panelorder). Fixed-height units
			// above the map chain top-down, units below it chain bottom-up, and
			// the map spans the remaining band between its neighbors
			// (RelativeLayout sizes it between the anchors). The default order
			// (map, hud, mlist, msg) reproduces the classic layout.
			String[] panelOrder = Preferences.getPanelOrder();
			int mapIdx = 0;
			for (int i = 0; i < panelOrder.length; i++)
				if (panelOrder[i].equals("map"))
					mapIdx = i;

			int prevBottomId = View.NO_ID;
			for (int i = 0; i < mapIdx; i++)
			{
				for (View v : panelUnitViews(panelOrder[i], hudView, statusBar,
						mlistView, msgView))
				{
					RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
							LayoutParams.MATCH_PARENT, v == statusBar
									? statusBarHeight : LayoutParams.WRAP_CONTENT);
					if (prevBottomId == View.NO_ID)
						lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
					else
						lp.addRule(RelativeLayout.BELOW, prevBottomId);
					splitContainer.addView(v, lp);
					prevBottomId = v.getId();
				}
			}

			int nextTopId = View.NO_ID;
			for (int i = panelOrder.length - 1; i > mapIdx; i--)
			{
				View[] unit = panelUnitViews(panelOrder[i], hudView, statusBar,
						mlistView, msgView);
				for (int j = unit.length - 1; j >= 0; j--)
				{
					View v = unit[j];
					RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
							LayoutParams.MATCH_PARENT, v == statusBar
									? statusBarHeight : LayoutParams.WRAP_CONTENT);
					if (nextTopId == View.NO_ID)
						lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
					else
						lp.addRule(RelativeLayout.ABOVE, nextTopId);
					splitContainer.addView(v, lp);
					nextTopId = v.getId();
				}
			}

			RelativeLayout.LayoutParams mapParams = new RelativeLayout.LayoutParams(
					LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
			if (mapIdx == 0)
				mapParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
			else
				mapParams.addRule(RelativeLayout.BELOW, prevBottomId);
			if (mapIdx == panelOrder.length - 1)
				mapParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
			else
				mapParams.addRule(RelativeLayout.ABOVE, nextTopId);
			splitContainer.addView(mapView, mapParams);

			portraitSplitContainer = splitContainer;
			splitRoot = splitContainer;
		}

		gamePanel.addView(splitRoot, new FrameLayout.LayoutParams(
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

		RegionTermView ngwWelcome = new RegionTermView(this,
				RegionRouter.NEWGAME_WELCOME_ROW0, RegionRouter.NEWGAME_COL_LEFT,
				RegionRouter.NEWGAME_WELCOME_ROW1, RegionRouter.NEWGAME_COL_FULL_END);
		ngwWelcome.setFontScaleMultiplier(fontConfig.portraitNewgameWelcomeFontScale);
		ngwWelcome.setHorizontalScrollEnabled(fontConfig.portraitNewgameWelcomeScrollable);
		ngwWelcome.setVerticalScrollEnabled(fontConfig.portraitNewgameWelcomeVScrollable);

		RegionTermView ngwContent = makeNewgameWeaponView();
		RegionTermView ngwSubLeft = makeNewgameSubView();
		RegionTermView ngwSubRight = makeNewgameSubView();

		newgameWeapon.addView(ngwWelcome, new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
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

		// HALF mode confines the whole single-screen UI to the keyboard-side half
		// (Both => right); the unused half shows the black screenLayout. Other
		// modes keep the panel full width.
		RelativeLayout.LayoutParams gamePanelParams = new RelativeLayout.LayoutParams(
				(halfActive && kbConfine) ? kbHalfWidth : LayoutParams.MATCH_PARENT,
				LayoutParams.MATCH_PARENT);
		gamePanelParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
		if (halfActive && kbConfine)
			gamePanelParams.addRule(kbHalfLeft
					? RelativeLayout.ALIGN_PARENT_LEFT
					: RelativeLayout.ALIGN_PARENT_RIGHT);
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
		router.setMenuStack(menuStack);
		router.setUnfoldedMenuGeometry(menuConfine, menuHalfWidth, menuHalfLeft);
		router.setSplitContainer(splitRoot);
		router.setStatusBarView(statusBar);
		router.addRegion(mapView);
		router.addRegion(hudView);
		router.addRegion(mlistView);
		router.addRegion(msgView);
		router.addRegion(ngsWelcome, RegionRouter.MenuType.NEWGAME_SPECIES);
		router.addRegion(ngsSimple, RegionRouter.MenuType.NEWGAME_SPECIES);
		router.addRegion(ngsIntermediate, RegionRouter.MenuType.NEWGAME_SPECIES);
		router.addRegion(ngsAdvanced, RegionRouter.MenuType.NEWGAME_SPECIES);
		router.addRegion(ngsDesc, RegionRouter.MenuType.NEWGAME_SPECIES);
		router.addRegion(ngsSubLeft, RegionRouter.MenuType.NEWGAME_SPECIES);
		router.addRegion(ngsSubRight, RegionRouter.MenuType.NEWGAME_SPECIES);
		router.addRegion(ngbWelcome, RegionRouter.MenuType.NEWGAME_BACKGROUND);
		router.addRegion(ngbWarrior, RegionRouter.MenuType.NEWGAME_BACKGROUND);
		router.addRegion(ngbZealot, RegionRouter.MenuType.NEWGAME_BACKGROUND);
		router.addRegion(ngbAdventurer, RegionRouter.MenuType.NEWGAME_BACKGROUND);
		router.addRegion(ngbWarMage, RegionRouter.MenuType.NEWGAME_BACKGROUND);
		router.addRegion(ngbMage, RegionRouter.MenuType.NEWGAME_BACKGROUND);
		router.addRegion(ngbDesc, RegionRouter.MenuType.NEWGAME_BACKGROUND);
		router.addRegion(ngbSubLeft, RegionRouter.MenuType.NEWGAME_BACKGROUND);
		router.addRegion(ngbSubRight, RegionRouter.MenuType.NEWGAME_BACKGROUND);
		router.addRegion(ngwWelcome, RegionRouter.MenuType.NEWGAME_WEAPON);
		router.addRegion(ngwContent, RegionRouter.MenuType.NEWGAME_WEAPON);
		router.addRegion(ngwSubLeft, RegionRouter.MenuType.NEWGAME_WEAPON);
		router.addRegion(ngwSubRight, RegionRouter.MenuType.NEWGAME_WEAPON);
		router.setNewgameSpeciesContainer(ngsScroll);
		router.setNewgameBackgroundContainer(ngbScroll);
		router.setNewgameSpeciesPanels(ngsSimple, ngsIntermediate, ngsAdvanced);
		router.setNewgameBackgroundPanels(ngbWarrior, ngbZealot, ngbAdventurer,
				ngbWarMage, ngbMage);
		router.setNewgameSubPanels(ngsDesc, ngsSubLeft, ngsSubRight,
				ngbDesc, ngbSubLeft, ngbSubRight);
		router.setNewgameWeaponContainer(ngwScroll);
		router.setNewgameWeaponPanels(ngwContent, ngwSubLeft, ngwSubRight);
		if (wordwrap)
			router.setMsgWordwrap(msgView, fontConfig.msgHistoryRows);
		router.setFontConfig(fontConfig);
		router.setShowLoadingMessage(getIntent().getBooleanExtra(
				SplashActivity.EXTRA_ASSETS_FRESHLY_INSTALLED, false));
		router.setRedrawRequester(() -> gameKeyListener.nativew.redrawScreen());
		// If we're resuming with the Ctrl+P / startup message history popup
		// still open on the C++ side, widen fullView's region to 48 rows now
		// — before onResume's redrawScreen pushes drawPoint calls. Otherwise
		// the freshly-constructed 28-row fullView drops rows 28..47, and the
		// later detection-driven setRegionRows reallocates the mirror empty.
		router.prepareForResume();
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

		if (unfoldedActive && foldPosture != null)
		{
			// Unfolded auto-fit: each half's whole content block fills its
			// half, not just the glyphs. Every panel width-fits its font to the
			// half in onMeasure — the map via 33 cols at scale 1.0, the panels
			// via their own region cols at their config scale (hud/mlist 1.0,
			// msg 1.5), reproducing the single-screen ratio. This listener then
			// shrinks a half's whole group by one shared factor only when the
			// stack overflows the half's height — so the block grows to fill and
			// backs off on height, preserving the config ratios throughout.
			final float mapBase = 1.0f;
			final float hudBase = fontConfig.portraitHudFontScale;
			final float msgBase = fontConfig.portraitMsgFontScale;
			gamePanel.getViewTreeObserver().addOnGlobalLayoutListener(
					new ViewTreeObserver.OnGlobalLayoutListener()
					{
						@Override
						public void onGlobalLayout()
						{
							if (splitRoot.getVisibility() != View.VISIBLE)
								return;

							int available = gamePanel.getHeight();
							if (available <= 0)
								return;

							// Each half fits to its OWN height: with a Left/Right
							// keyboard the keyboard-side half is shortened by a
							// bottom margin, the other stays full. Falls back to
							// the panel height when a half ref is missing.
							int availMap = unfoldedMapHalf != null
									&& unfoldedMapHalf.getHeight() > 0
									? unfoldedMapHalf.getHeight() : available;
							int availPanel = unfoldedPanelHalf != null
									&& unfoldedPanelHalf.getHeight() > 0
									? unfoldedPanelHalf.getHeight() : available;

							// Map half: fill width (base 1.0); back off only
							// if that makes the block taller than the half.
							float curMap = mapView.getFontScaleMultiplier();
							int mapHnow = mapView.getMeasuredHeight();
							float mapTarget = mapBase;
							if (mapHnow > 0 && curMap > 0)
							{
								float mapHatBase = mapHnow * (mapBase / curMap);
								if (mapHatBase > availMap)
									mapTarget = Math.max(MIN_FONT_SCALE,
											mapBase * (availMap - 1f)
													/ mapHatBase);
							}
							if (Math.abs(mapTarget - curMap) >= MIN_SCALE_DELTA)
								mapView.setFontScaleMultiplier(mapTarget);

							// Panel half: extrapolate the stack height to the
							// base ratios, then one shared factor fits it to
							// the half (statusBar is fixed-height, a constant).
							float curHud = hudView.getFontScaleMultiplier();
							float curMlist = mlistView.getFontScaleMultiplier();
							float curMsg = msgView.getFontScaleMultiplier();
							float hudHb = curHud > 0
									? hudView.getMeasuredHeight()
											* (hudBase / curHud)
									: hudView.getMeasuredHeight();
							float mlistHb = curMlist > 0
									? mlistView.getMeasuredHeight()
											* (hudBase / curMlist)
									: mlistView.getMeasuredHeight();
							float msgHb = curMsg > 0
									? msgView.getMeasuredHeight()
											* (msgBase / curMsg)
									: msgView.getMeasuredHeight();
							float stackHb = hudHb + mlistHb + msgHb
									+ statusBar.getMeasuredHeight();
							float hf = stackHb > 0
									? Math.min(1f, (availPanel - 1f) / stackHb)
									: 1f;
							float tHud = Math.max(MIN_FONT_SCALE, hudBase * hf);
							float tMlist = tHud;
							float tMsg = Math.max(MIN_FONT_SCALE, msgBase * hf);
							if (Math.abs(tHud - curHud) >= MIN_SCALE_DELTA)
								hudView.setFontScaleMultiplier(tHud);
							if (Math.abs(tMlist - curMlist) >= MIN_SCALE_DELTA)
								mlistView.setFontScaleMultiplier(tMlist);
							if (Math.abs(tMsg - curMsg) >= MIN_SCALE_DELTA)
								msgView.setFontScaleMultiplier(tMsg);

							// Keep DCSS's wrap width synced to the settled msg
							// panel (fold/unfold changes its width after boot).
							// Self-gates on game-loaded + width change.
							gameKeyListener.nativew.updateMsgWrap();
						}
					});
			return router;
		}

		// Fallback map shrinker for UNSPECIFIED-height layouts.
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

	// Views of one draggable panel unit, top to bottom. The status-lights bar
	// always sits directly below the HUD. "map" is never passed here — the
	// map is placed separately as the flexible unit.
	private View[] panelUnitViews(String key, View hudView, View statusBar,
			View mlistView, View msgView)
	{
		switch (key)
		{
		case "hud":
			return new View[] { hudView, statusBar };
		case "mlist":
			return new View[] { mlistView };
		default:
			return new View[] { msgView };
		}
	}

	// Unfolded (foldable) in-game container: a horizontal split with the
	// map alone on one half and the other panels stacked on the other, the two
	// separated by the physical hinge gap. Which half holds the map is the
	// crawl.dualmapside pref; the panel order within the panel half is
	// crawl.dualpanelorder. Half widths mirror the window's fold geometry so
	// the split lands on the hinge. Panels width-fit their font to their half
	// automatically (onMeasure); the auto-fit listener handles height.
	private ViewGroup buildUnfoldedSplitContainer(RegionTermView mapView,
			RegionTermView hudView, StatusBarView statusBar,
			RegionTermView mlistView, RegionTermView msgView,
			int statusBarHeight)
	{
		LinearLayout unfoldedRoot = new LinearLayout(this);
		unfoldedRoot.setOrientation(LinearLayout.HORIZONTAL);
		unfoldedRoot.setBackgroundColor(Color.BLACK);
		unfoldedRoot.setVisibility(View.INVISIBLE);

		FrameLayout mapHalf = new FrameLayout(this);
		// Width-fit the map's font to its 33 content cols (not the padded
		// 37-col region) so the dungeon view fills the half's width; it stays
		// width-bound by aspect ratio and centers vertically in the tall half.
		// Scale 1.0 = font width-fits the full half (the single-screen
		// portraitMapFontScale shrink is not applied in unfolded mode — the panel
		// fills its half; the auto-fit listener only shrinks on height overflow).
		mapView.setFontReferenceCols(33);
		mapView.setFontScaleMultiplier(1.0f);
		mapHalf.addView(mapView, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

		// Panels keep their single-screen geometry, just scoped to the half:
		// each width-fits its OWN region cols (hud/mlist 43, msg 80) at its
		// config scale (portraitHudFontScale / portraitMsgFontScale). That's
		// exactly what the single-column layout does against the full window, so
		// the half reproduces the classic ratio — the hud's stats occupy their
		// 43 cols leaving the right gutter for the command buttons, and the msg
		// shows its full wrapped width (getMsgWrapCols still yields ~52 cols).
		// The set-in-rebuildViews scale multipliers already hold the config
		// values, so no fontReferenceCols / scale override here. The auto-fit
		// listener then shrinks the whole stack by one shared factor only if it
		// overflows the half's height, preserving those ratios.

		LinearLayout panelHalf = new LinearLayout(this);
		panelHalf.setOrientation(LinearLayout.VERTICAL);
		panelHalf.setGravity(android.view.Gravity.CENTER_VERTICAL);
		for (String key : Preferences.getUnfoldedPanelOrder())
			for (View v : panelUnitViews(key, hudView, statusBar,
					mlistView, msgView))
				panelHalf.addView(v, new LinearLayout.LayoutParams(
						LayoutParams.MATCH_PARENT,
						v == statusBar ? statusBarHeight
								: LayoutParams.WRAP_CONTENT));

		int gap = Math.max(0, foldPosture.rightStart - foldPosture.leftWidth);
		float leftWeight = Math.max(1, foldPosture.leftWidth);
		float rightWeight = Math.max(1,
				foldPosture.totalWidth - foldPosture.rightStart);

		boolean mapLeft = Preferences.getUnfoldedMapSide()
				.equals(Preferences.SIDE_LEFT);
		View leftContent = mapLeft ? mapHalf : panelHalf;
		View rightContent = mapLeft ? panelHalf : mapHalf;

		unfoldedRoot.addView(leftContent, new LinearLayout.LayoutParams(
				0, LayoutParams.MATCH_PARENT, leftWeight));
		unfoldedRoot.addView(new View(this), new LinearLayout.LayoutParams(
				gap, LayoutParams.MATCH_PARENT));
		unfoldedRoot.addView(rightContent, new LinearLayout.LayoutParams(
				0, LayoutParams.MATCH_PARENT, rightWeight));
		// Kept so the auto-fit listener and reserveKeyboardHalf can address each
		// half individually (a Left/Right keyboard shortens only its own half).
		unfoldedMapHalf = mapHalf;
		unfoldedPanelHalf = panelHalf;
		return unfoldedRoot;
	}

	// Deferred entry into the reposition mode requested from Preferences:
	// freeze the fresh router now — before onResume's posted redrawScreen
	// storm can drive a detection applyMode that would stomp the forced
	// split visibility — and enter once the first layout pass has sized the
	// panels.
	private void schedulePendingRepositionEntry() {
		pendingRepositionEntry = false;
		if (portraitRouter == null || portraitSplitContainer == null)
			return;
		portraitRouter.setRepositionFrozen(true);
		final View gamePanel = screenLayout.findViewById(gamePanelId);
		screenLayout.getViewTreeObserver().addOnGlobalLayoutListener(
				new ViewTreeObserver.OnGlobalLayoutListener()
				{
					@Override
					public void onGlobalLayout()
					{
						if (gamePanel == null || gamePanel.getHeight() <= 0
								|| portraitMsgView == null
								|| portraitMsgView.getHeight() <= 0)
							return;
						screenLayout.getViewTreeObserver()
								.removeOnGlobalLayoutListener(this);
						enterRepositionMode();
					}
				});
	}

	private void enterRepositionMode() {
		if (repositionController != null && repositionController.isActive())
			return;
		repositionController = new RepositionController(this, screenLayout,
				portraitRouter, portraitSplitContainer, portraitMapView,
				portraitHudView, portraitStatusBar, portraitMlistView,
				portraitMsgView, portraitKeyboardView, lastBottomInset,
				portraitFontConfig.repositionHighlightColor,
				new RepositionController.Callbacks()
				{
					@Override
					public void onSave(String[] order)
					{
						repositionController = null;
						Preferences.setPanelOrder(order);
						rebuildViews();
						// Repaint the rebuilt panels (mirrors onResume).
						if (gameKeyListener != null
								&& gameKeyListener.nativew != null)
						{
							final NativeWrapper nw = gameKeyListener.nativew;
							View root = findViewById(android.R.id.content);
							if (root != null)
								root.post(nw::redrawScreen);
						}
					}

					@Override
					public void onCancel(boolean restoreIme)
					{
						repositionController = null;
						if (restoreIme)
							restoreKeyboardAfterReload();
					}
				});
		repositionController.enter();
	}

	// Deferred entry into the grid overlay editor requested from
	// Preferences: wait for a layout pass so the keyboard view is sized for
	// the button bar. No router freeze needed — the mode's opaque root
	// covers the whole screen.
	private void schedulePendingGridOverlayEntry() {
		pendingGridOverlayEntry = false;
		if (screenLayout.getHeight() > 0)
		{
			enterGridOverlayMode();
			return;
		}
		screenLayout.getViewTreeObserver().addOnGlobalLayoutListener(
				new ViewTreeObserver.OnGlobalLayoutListener()
				{
					@Override
					public void onGlobalLayout()
					{
						if (screenLayout.getHeight() <= 0)
							return;
						screenLayout.getViewTreeObserver()
								.removeOnGlobalLayoutListener(this);
						enterGridOverlayMode();
					}
				});
	}

	private void enterGridOverlayMode() {
		if (gridOverlayController != null && gridOverlayController.isActive())
			return;
		gridOverlayController = new GridOverlayController(this, screenLayout,
				portraitKeyboardView, lastBottomInset,
				portraitFontConfig.repositionHighlightColor,
				new GridOverlayController.Callbacks()
				{
					@Override
					public void onSave(float[] lines)
					{
						gridOverlayController = null;
						Preferences.setGridLines(lines);
						// Nothing layout-visible changes — no rebuild needed.
						restoreKeyboardAfterReload();
					}

					@Override
					public void onExit(boolean restoreIme)
					{
						gridOverlayController = null;
						if (restoreIme)
							restoreKeyboardAfterReload();
					}
				});
		gridOverlayController.enter();
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

	// Main weapon-list panel on the weapon-selection screen: prompt + weapon
	// list, below the separate welcome panel. Router re-aims the bottom edge
	// to subItemRow once the sub-items grid resolves.
	private RegionTermView makeNewgameWeaponView()
	{
		FontConfig fc = FontConfig.load(getAssets());
		RegionTermView v = new RegionTermView(this,
				RegionRouter.NEWGAME_WELCOME_ROW1, 0,
				RegionRouter.NEWGAME_SUB_ROW0, RegionRouter.TERMINAL_COLS);
		v.setFontScaleMultiplier(fc.portraitNewgameWeaponFontScale);
		v.setFontReferenceCols(RegionRouter.TERMINAL_COLS);
		v.setHorizontalScrollEnabled(fc.portraitNewgameWeaponScrollable);
		v.setVerticalScrollEnabled(fc.portraitNewgameWeaponVScrollable);
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
		// No keyboard (NO_ID): fill the screen instead of anchoring above one.
		if (virtualKeyboardId != View.NO_ID)
			directionalLayoutParams
					.addRule(RelativeLayout.ABOVE, virtualKeyboardId);
		view.setLayoutParams(directionalLayoutParams);
		// Fold: keep ONE full-width overlay (so two-finger app-menu / pinch see
		// both pointers) but split its 9-grid columns per half, so a tap maps
		// within the half it lands in. UNFOLDED = both halves; HALF = the single
		// content half (taps on the black half fall in no band and are ignored).
		if (foldPosture != null && unfoldedActive)
			view.setFoldHalves(
					new int[] { 0, foldPosture.rightStart },
					new int[] { foldPosture.leftWidth,
							foldPosture.totalWidth - foldPosture.rightStart });
		else if (foldPosture != null && halfActive && kbConfine)
			view.setFoldHalves(
					new int[] { kbHalfLeft ? 0 : foldPosture.rightStart },
					new int[] { kbHalfWidth });
		configureDirectionalView(view, hapticFeedbackEnabled);
		screenLayout.addView(view);
		portraitDirectionalView = view;
	}

	// Wire a touch overlay's pass-through, scroll/menu targets, router, map
	// zoom and haptics.
	private void configureDirectionalView(DirectionalTouchView view,
			boolean hapticFeedbackEnabled) {
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
					portraitFontConfig.portraitMapZoomStepOutBase,
					portraitFontConfig.portraitMapZoomStep1,
					portraitFontConfig.portraitMapZoomStep2);

		view.setHapticFeedbackEnabled(hapticFeedbackEnabled);
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
		// nativeSaveGame is synchronous: staging is current and the .cs
		// push queued before it returns. The blocking drain is in onStop
		// — blocking onPause stalls the incoming activity.
		NativeWrapper.nativeSaveGame();
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (foldStateController != null)
			foldStateController.stop();
		// Discard an in-progress reposition session — its overlay would not
		// survive the rebuildViews of the next onStart anyway. No IME restore
		// here (the activity is going background); the next rebuild resets it.
		if (repositionController != null && repositionController.isActive())
			repositionController.cancel(false);
		if (gridOverlayController != null && gridOverlayController.isActive())
			gridOverlayController.exit(false);
		// Drain pushes before the process can be cached and frozen
		// (MIUI freezes within seconds; queued work would be lost).
		// onStop runs after the next screen is visible, so the wait is
		// invisible in-app. 5s cap so a wedged provider can't ANR us.
		CustomFolderSync.pushBlocking(this, 5000);
	}

	@Override
	protected void onResume() {
		// Log.d("Crawl", "onResume");
		super.onResume();

		setScreen();

		// Activity results are delivered between onStart and onResume, so a
		// reposition request from Preferences lands after onStart's
		// rebuildViews has already run — consume it here, freezing the
		// router before the redraw posted below can drive applyMode.
		if (pendingRepositionEntry)
			schedulePendingRepositionEntry();

		if (pendingGridOverlayEntry)
			schedulePendingGridOverlayEntry();

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

	// System keys that keep their normal behavior while the reposition mode
	// swallows all game input.
	private static boolean isSystemKey(int keyCode) {
		switch (keyCode) {
		case KeyEvent.KEYCODE_VOLUME_UP:
		case KeyEvent.KEYCODE_VOLUME_DOWN:
		case KeyEvent.KEYCODE_VOLUME_MUTE:
		case KeyEvent.KEYCODE_POWER:
		case KeyEvent.KEYCODE_CAMERA:
			return true;
		}
		return false;
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (modalController != null && modalController.isActive()) {
			if (isSystemKey(keyCode))
				return super.onKeyDown(keyCode, event);
			if (keyCode == KeyEvent.KEYCODE_BACK) {
				// In the wiki modal, BACK acts as a browser back button until
				// history is exhausted, then dismisses.
				if (wikiModalActive && wikiGoBack())
					return true;
				modalController.dismiss();
			}
			return true;
		}
		if (repositionController != null && repositionController.isActive()) {
			if (isSystemKey(keyCode))
				return super.onKeyDown(keyCode, event);
			if (keyCode == KeyEvent.KEYCODE_BACK)
				repositionController.cancel(true);
			return true;
		}
		if (gridOverlayController != null && gridOverlayController.isActive()) {
			if (isSystemKey(keyCode))
				return super.onKeyDown(keyCode, event);
			if (keyCode == KeyEvent.KEYCODE_BACK)
				gridOverlayController.exit(true);
			return true;
		}
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
		if (modalController != null && modalController.isActive())
			return isSystemKey(keyCode) ? super.onKeyUp(keyCode, event) : true;
		if (repositionController != null && repositionController.isActive())
			return isSystemKey(keyCode) ? super.onKeyUp(keyCode, event) : true;
		if (gridOverlayController != null && gridOverlayController.isActive())
			return isSystemKey(keyCode) ? super.onKeyUp(keyCode, event) : true;
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
