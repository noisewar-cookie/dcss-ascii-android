package com.crawlmb;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.app.AlertDialog;

import java.util.HashMap;
import java.util.Map;

import com.crawlmb.keyboard.CrawlKeyboardWrapper.KeyboardType;
import com.crawlmb.keymap.KeyMapper;

final public class Preferences
{

	public static final int rows = 24;
	public static final int cols = 80;

	public static final String NAME = "crawl";

	public static final String KEY_VIBRATE = "crawl.vibrate";
	public static final String KEY_FULLSCREEN = "crawl.fullscreen";
	public static final String KEY_ORIENTATION = "crawl.orientation";
	public static final String KEY_SKIPSPLASH = "crawl.skipsplash";
	public static final String KEY_SKIPCONTROLSINFO = "crawl.skipcontrolsinfo";
	public static final String KEY_WORDWRAP = "crawl.wordwrap";
	public static final String KEY_RELOADINPROGRESS = "crawl.reloadinprogress";

	public static final String KEY_FONTFACE = "crawl.fontface";
	public static final String KEY_ENABLETOUCH = "crawl.enabletouch";
	public static final String KEY_PORTRAITKB = "crawl.portraitkb";
	public static final String KEY_LANDSCAPEKB = "crawl.landscapekb";
	public static final String KEY_PORTRAITFONTSIZE = "crawl.portraitfontsize";
	public static final String KEY_LANDSCAPEFONTSIZE = "crawl.landscapefontsize";
	public static final String KEY_KEYBOARDTRANSPARENCY = "crawl.keyboardtransparency";

	public static final String KEY_PROFILES = "crawl.profiles";
	public static final String KEY_ACTIVEPROFILE = "crawl.activeprofile";

	// Custom data folder (SAF tree URI). When set, the game runs against a
	// staging mirror of this folder instead of the live app-private dirs.
	// Empty string means custom mode is off. See Paths.isCustomMode().
	public static final String KEY_CUSTOMFOLDERURI = "crawl.customfolderuri";

	// SAF URI the current staging tree was last set up for. Compared
	// against the newly-picked URI on enable: a mismatch means staging
	// holds another folder's data and must be wiped before pulling.
	public static final String KEY_STAGINGORIGINURI = "crawl.stagingoriginuri";

	// Vertical stack order of the gameplay panels, comma-separated permutation
	// of PANEL_KEYS (hud implies the status-lights bar directly below it).
	public static final String KEY_PANELORDER = "crawl.panelorder";
	public static final String[] PANEL_KEYS = { "map", "hud", "mlist", "msg" };

	// Unfolded mode (foldables opened in book posture). Settings are kept
	// separate from the single-screen ones above so folding back reverts to
	// the single-screen layout untouched. KEY_UNFOLDED is the user gate
	// (default on, only exposed once KEY_FOLDABLESEEN is set); KEY_UNFOLDEDMAPSIDE
	// picks which half holds the map ("left"/"right"); KEY_UNFOLDEDPANELORDER is
	// the vertical stack order of the non-map panels; KEY_UNFOLDEDKEYBOARDSIDE
	// picks which half the keyboard sits on ("left"/"right").
	public static final String KEY_UNFOLDED = "crawl.dualscreen";
	public static final String KEY_FOLDABLESEEN = "crawl.foldableseen";
	public static final String KEY_UNFOLDEDMAPSIDE = "crawl.dualmapside";
	public static final String KEY_UNFOLDEDPANELORDER = "crawl.dualpanelorder";
	public static final String KEY_UNFOLDEDKEYBOARDSIDE = "crawl.dualkeyboardside";
	// Custom fold centerline: fraction of screen width where the split falls.
	// 0.50 = physical hinge (default). Range [0.25, 0.75].
	public static final String KEY_FOLD_CENTERLINE = "crawl.dualcenterline";
	public static final float FOLD_CENTERLINE_DEFAULT = 0.50f;
	public static final float FOLD_CENTERLINE_MIN = 0.25f;
	public static final float FOLD_CENTERLINE_MAX = 0.75f;
	public static final String[] UNFOLDED_PANEL_KEYS = { "hud", "mlist", "msg" };
	public static final String SIDE_LEFT = "left";
	public static final String SIDE_RIGHT = "right";
	// Keyboard side only: spans the whole display (today's behavior).
	public static final String SIDE_BOTH = "both";

	// Global bevel margin (dp): a uniform inset applied to the game area and
	// all overlays on every edge, in every mode (single-screen too). Keeps
	// content clear of rounded display corners / curved edges. 0 = off.
	public static final String KEY_BEVELMARGIN = "crawl.bevelmargin";

	// 9-grid touch zone dividers as "v1,v2,h1,h2" — fractions of the touch
	// area (v = vertical line x, h = horizontal line y from top). Lines may
	// coincide (collapses the grid toward 2x2) but never cross, and stay
	// GRID_LINE_PADDING away from the edges.
	public static final String KEY_GRIDLINES = "crawl.gridlines";
	// Unfolded (foldable) mode keeps a separate divider config per physical
	// half so each half's 9-grid can be tuned independently (see
	// getGridLines(side)). Single-screen and HALF still use KEY_GRIDLINES.
	public static final String KEY_GRIDLINES_LEFT = "crawl.gridlines.left";
	public static final String KEY_GRIDLINES_RIGHT = "crawl.gridlines.right";
	// Snap step 1/36 lies on the default thirds (12/36, 24/36); padding is
	// one snap step so the drag bounds align with the snap grid.
	public static final float GRID_LINE_SNAP_STEP = 1f / 36f;
	public static final float GRID_LINE_PADDING = 1f / 36f;
	public static final float[] GRID_LINE_DEFAULTS =
			{ 1f / 3f, 2f / 3f, 1f / 3f, 2f / 3f };

    private static final String KEYBOARD_LAYOUT_COUNT = "layout_count";
    public static final String KEYBOARD_LAYOUT_CURRENT = "layout_current";
    public static final String KEYBOARD_LABEL_PREFIX = "label_";
    public static final String KEYBOARD_CODE_PREFIX = "code_";
    public static final String KEYBOARD_LONGPRESS_PREFIX = "longpress_";
    // Version of the built-in key layouts the stored remaps were written
    // against. Bumped when keys are inserted/removed so index-keyed remaps
    // can be migrated. Absent = 1 (pre-insertion layouts).
    private static final String KEYBOARD_REMAP_VERSION = "keyboard_remap_version";
    // One-shot: reset users stranded in the "None" keyboard mode, which had
    // no input surface before the DirectionalTouchView fix.
    private static final String NO_KEYBOARD_UNSTICK_DONE = "no_keyboard_unstick_done";

	private static final String KEY_HAPTICFEEDBACKENABLED = "crawl.hapticfeedbackenabled";
	private static final String KEY_KEYBOARDARROWSENABLED = "crawl.keyboardarrowsenabled";
	private static final String KEY_LONGPRESSENABLED = "crawl.longpressenabled";
	private static final String KEY_TOUCHDIRECTIONREPEAT = "crawl.touchdirectionrepeat";
	private static final String KEY_HELPBUTTONENABLED = "crawl.helpbuttonenabled";
	private static final String KEY_WIKIBUTTONENABLED = "crawl.wikibuttonenabled";
	private static final String KEY_HUDBUTTONLONGPRESS = "crawl.hudbuttonlongpress";

    private static SharedPreferences sharedPreferences;
	private static int fontSize = 17;
	private static Resources resources;

	private static KeyMapper keymapper;

	Preferences()
	{
	}

	public static void init(Resources res, SharedPreferences sharedPreferences)
	{
		Preferences.sharedPreferences = sharedPreferences;
		resources = res;

		keymapper = new KeyMapper(sharedPreferences);
	}

	public static Resources getResources()
	{
		return resources;
	}

	public static String getString(String key)
	{
		return sharedPreferences.getString(key, "");
	}

	public static boolean getFullScreen()
	{
		return sharedPreferences.getBoolean(Preferences.KEY_FULLSCREEN, true);
	}

	/** @deprecated Unused. */
	@Deprecated
	public static void setFullScreen(boolean value)
	{
		SharedPreferences.Editor ed = sharedPreferences.edit();
		ed.putBoolean(Preferences.KEY_FULLSCREEN, value);
		ed.apply();
	}
	
	public static boolean getSkipSplash()
	{
		return sharedPreferences.getBoolean(Preferences.KEY_SKIPSPLASH, false);
	}

	public static boolean getSkipControlsInfo()
	{
		return sharedPreferences.getBoolean(Preferences.KEY_SKIPCONTROLSINFO, false);
	}

	// Word wrap (messages wrapped by DCSS at the visible column count, msg
	// window extended to extra history rows). Read once at layout/game start;
	// changing it mid-session has no effect until the next app launch.
	public static boolean getWordwrap()
	{
		return sharedPreferences.getBoolean(Preferences.KEY_WORDWRAP, false);
	}

	// One-shot flag marking that the next launch follows a save-restore
	// process restart (PreferencesActivity sets it just before killing the
	// process). GameActivity reads it via consumeReloadInProgress() to show
	// the "Reloading..." overlay across the restart.
	//
	// commit() (synchronous), not apply(): the caller kills the process
	// immediately after, so an async apply() could be lost before it reaches
	// disk and the overlay would never show.
	public static void setReloadInProgressSync(boolean value)
	{
		sharedPreferences.edit()
				.putBoolean(Preferences.KEY_RELOADINPROGRESS, value).commit();
	}

	// Persisted SAF tree URI for the custom data folder, or "" if custom
	// mode is off. Whether the URI permission is still held is a separate
	// runtime check — see CustomFolderSync.hasPersistedPermission.
	public static String getCustomFolderUri()
	{
		return sharedPreferences.getString(Preferences.KEY_CUSTOMFOLDERURI, "");
	}

	public static void setCustomFolderUri(String uri)
	{
		sharedPreferences.edit()
				.putString(Preferences.KEY_CUSTOMFOLDERURI,
						uri == null ? "" : uri)
				.commit();
	}

	public static String getStagingOriginUri()
	{
		return sharedPreferences.getString(Preferences.KEY_STAGINGORIGINURI, "");
	}

	public static void setStagingOriginUri(String uri)
	{
		sharedPreferences.edit()
				.putString(Preferences.KEY_STAGINGORIGINURI,
						uri == null ? "" : uri)
				.commit();
	}

	// Reads and clears the reload flag in one shot. Cleared on read so a
	// normal later launch never re-shows the overlay even if the relaunch
	// that set it never reached GameActivity.
	public static boolean consumeReloadInProgress()
	{
		boolean v = sharedPreferences.getBoolean(
				Preferences.KEY_RELOADINPROGRESS, false);
		if (v)
			sharedPreferences.edit()
					.putBoolean(Preferences.KEY_RELOADINPROGRESS, false).apply();
		return v;
	}

	// Validated panel stack order: exactly the four PANEL_KEYS in some
	// permutation, else the default order (map, hud, mlist, msg).
	public static String[] getPanelOrder()
	{
		String stored = sharedPreferences.getString(KEY_PANELORDER, "");
		if (!stored.isEmpty())
		{
			String[] order = stored.split(",");
			if (order.length == PANEL_KEYS.length && isPanelPermutation(order))
				return order;
		}
		return PANEL_KEYS.clone();
	}

	public static void setPanelOrder(String[] order)
	{
		sharedPreferences.edit()
				.putString(KEY_PANELORDER,
						android.text.TextUtils.join(",", order)).apply();
	}

	// --- Unfolded (foldable) settings -----------------------------------

	public static boolean getUnfoldedEnabled()
	{
		return sharedPreferences.getBoolean(KEY_UNFOLDED, true);
	}

	public static void setUnfoldedEnabled(boolean value)
	{
		sharedPreferences.edit().putBoolean(KEY_UNFOLDED, value).apply();
	}

	// Sticky: set once the app ever observes a fold feature or a hinge sensor,
	// so the unfolded preferences stay visible even while folded shut.
	public static boolean getFoldableSeen()
	{
		return sharedPreferences.getBoolean(KEY_FOLDABLESEEN, false);
	}

	public static void setFoldableSeen(boolean value)
	{
		if (value == getFoldableSeen())
			return;
		sharedPreferences.edit().putBoolean(KEY_FOLDABLESEEN, value).apply();
	}

	public static String getUnfoldedMapSide()
	{
		String s = sharedPreferences.getString(KEY_UNFOLDEDMAPSIDE, SIDE_LEFT);
		return SIDE_RIGHT.equals(s) ? SIDE_RIGHT : SIDE_LEFT;
	}

	public static void setUnfoldedMapSide(String side)
	{
		sharedPreferences.edit().putString(KEY_UNFOLDEDMAPSIDE,
				SIDE_RIGHT.equals(side) ? SIDE_RIGHT : SIDE_LEFT).apply();
	}

	// The half holding the panels is the one the map is NOT on.
	public static String getUnfoldedPanelSide()
	{
		return SIDE_LEFT.equals(getUnfoldedMapSide()) ? SIDE_RIGHT : SIDE_LEFT;
	}

	public static String[] getUnfoldedPanelOrder()
	{
		String stored = sharedPreferences.getString(KEY_UNFOLDEDPANELORDER, "");
		if (!stored.isEmpty())
		{
			String[] order = stored.split(",");
			if (order.length == UNFOLDED_PANEL_KEYS.length
					&& isUnfoldedPanelPermutation(order))
				return order;
		}
		return UNFOLDED_PANEL_KEYS.clone();
	}

	public static void setUnfoldedPanelOrder(String[] order)
	{
		sharedPreferences.edit().putString(KEY_UNFOLDEDPANELORDER,
				android.text.TextUtils.join(",", order)).apply();
	}

	// Defaults to the panels' half so, out of the box, the keyboard sits under
	// the message/prompt panels rather than over the view-only map.
	public static String getUnfoldedKeyboardSide()
	{
		String s = sharedPreferences.getString(KEY_UNFOLDEDKEYBOARDSIDE, "");
		if (SIDE_LEFT.equals(s) || SIDE_RIGHT.equals(s) || SIDE_BOTH.equals(s))
			return s;
		return getUnfoldedPanelSide();
	}

	public static void setUnfoldedKeyboardSide(String side)
	{
		String v = SIDE_BOTH.equals(side) ? SIDE_BOTH
				: SIDE_RIGHT.equals(side) ? SIDE_RIGHT : SIDE_LEFT;
		sharedPreferences.edit().putString(KEY_UNFOLDEDKEYBOARDSIDE, v).apply();
	}

	public static float getFoldCenterline()
	{
		float v = sharedPreferences.getFloat(KEY_FOLD_CENTERLINE,
				FOLD_CENTERLINE_DEFAULT);
		return Math.max(FOLD_CENTERLINE_MIN, Math.min(FOLD_CENTERLINE_MAX, v));
	}

	public static void setFoldCenterline(float fraction)
	{
		float v = Math.max(FOLD_CENTERLINE_MIN,
				Math.min(FOLD_CENTERLINE_MAX, fraction));
		sharedPreferences.edit().putFloat(KEY_FOLD_CENTERLINE, v).apply();
	}

	private static boolean isUnfoldedPanelPermutation(String[] order)
	{
		for (String key : UNFOLDED_PANEL_KEYS)
		{
			boolean found = false;
			for (String o : order)
				if (key.equals(o))
					found = true;
			if (!found)
				return false;
		}
		return true;
	}

	// Parsed grid-line caches — grid taps are a hot path; invalidated by the
	// matching setGridLines. Callers must not mutate the returned array.
	private static float[] gridLinesCache = null;
	private static float[] gridLinesCacheLeft = null;
	private static float[] gridLinesCacheRight = null;

	// Validated grid line fractions {v1, v2, h1, h2}: ordered, within the
	// padding bounds, else the default thirds. Single-screen / HALF config.
	public static float[] getGridLines()
	{
		if (gridLinesCache != null)
			return gridLinesCache;
		gridLinesCache = parseGridLines(
				sharedPreferences.getString(KEY_GRIDLINES, ""));
		return gridLinesCache;
	}

	// Per-physical-half config for unfolded mode. SIDE_LEFT / SIDE_RIGHT pick
	// the half's own config; anything else falls back to the single-screen one.
	public static float[] getGridLines(String side)
	{
		if (SIDE_LEFT.equals(side))
		{
			if (gridLinesCacheLeft == null)
				gridLinesCacheLeft = parseGridLines(
						sharedPreferences.getString(KEY_GRIDLINES_LEFT, ""));
			return gridLinesCacheLeft;
		}
		if (SIDE_RIGHT.equals(side))
		{
			if (gridLinesCacheRight == null)
				gridLinesCacheRight = parseGridLines(
						sharedPreferences.getString(KEY_GRIDLINES_RIGHT, ""));
			return gridLinesCacheRight;
		}
		return getGridLines();
	}

	public static void setGridLines(float[] lines)
	{
		setGridLines(null, lines);
	}

	public static void setGridLines(String side, float[] lines)
	{
		String key = SIDE_LEFT.equals(side) ? KEY_GRIDLINES_LEFT
				: SIDE_RIGHT.equals(side) ? KEY_GRIDLINES_RIGHT : KEY_GRIDLINES;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.length; i++)
		{
			if (i > 0)
				sb.append(',');
			sb.append(lines[i]);
		}
		sharedPreferences.edit().putString(key, sb.toString()).apply();
		if (SIDE_LEFT.equals(side))
			gridLinesCacheLeft = lines.clone();
		else if (SIDE_RIGHT.equals(side))
			gridLinesCacheRight = lines.clone();
		else
			gridLinesCache = lines.clone();
	}

	private static float[] parseGridLines(String stored)
	{
		if (!stored.isEmpty())
		{
			String[] parts = stored.split(",");
			if (parts.length == 4)
			{
				float[] lines = new float[4];
				try
				{
					for (int i = 0; i < 4; i++)
						lines[i] = Float.parseFloat(parts[i]);
					float lo = GRID_LINE_PADDING;
					float hi = 1f - GRID_LINE_PADDING;
					if (lines[0] >= lo && lines[0] <= lines[1]
							&& lines[1] <= hi
							&& lines[2] >= lo && lines[2] <= lines[3]
							&& lines[3] <= hi)
						return lines;
				}
				catch (NumberFormatException ignored)
				{
				}
			}
		}
		return GRID_LINE_DEFAULTS.clone();
	}

	private static boolean isPanelPermutation(String[] order)
	{
		for (String key : PANEL_KEYS)
		{
			boolean found = false;
			for (String o : order)
				if (key.equals(o))
					found = true;
			if (!found)
				return false;
		}
		return true;
	}

	public static boolean isScreenPortraitOrientation()
	{
		Configuration config = resources.getConfiguration();
		return (config.orientation == Configuration.ORIENTATION_PORTRAIT);
	}

	/** @deprecated Unused. */
	@Deprecated
	public static int getOrientation()
	{
		return Integer.parseInt(sharedPreferences.getString(Preferences.KEY_ORIENTATION, "0"));
	}

	/** @deprecated Unused. */
	@Deprecated
	public static int getDefaultFontSize()
	{
		return fontSize;
	}

	/** @deprecated Unused. */
	@Deprecated
	public static int setDefaultFontSize(int value)
	{
		return fontSize = value;
	}

	public static int getKeyboardTransparency(){
		return sharedPreferences.getInt(Preferences.KEY_KEYBOARDTRANSPARENCY, 140);
	}

	// Edge margin in dp, relative to device-adaptive baseline. Range: -16..64.
	public static int getBevelMarginDp()
	{
		String v = sharedPreferences.getString(Preferences.KEY_BEVELMARGIN, "0");
		int dp;
		try { dp = Integer.parseInt(v); }
		catch (NumberFormatException e) { dp = 0; }
		return Math.max(-16, Math.min(dp, 64));
	}

	public static void setKeyboardTransparency(int transparency) {
		sharedPreferences.edit().putInt(Preferences.KEY_KEYBOARDTRANSPARENCY, transparency).apply();
	}

	/** @deprecated Unused. */
	@Deprecated
	public static boolean getVibrate()
	{
		return sharedPreferences.getBoolean(Preferences.KEY_VIBRATE, false);
	}

	public static String getPortraitKeyboard()
	{
		return sharedPreferences.getString(Preferences.KEY_PORTRAITKB,
				resources.getString(R.string.portraitkb_default));
	}

	public static String getFontFace(){
		return sharedPreferences.getString(Preferences.KEY_FONTFACE, "VeraMoBd.ttf");
	}

	public static void setFontFace(String fontface){
		sharedPreferences.edit().putString(Preferences.KEY_FONTFACE, fontface).apply();
	}

	public static void setPortraitKeyboard(String value)
	{
		SharedPreferences.Editor ed = sharedPreferences.edit();
		ed.putString(Preferences.KEY_PORTRAITKB, value);
		ed.apply();
	}

	public static String getLandscapeKeyboard()
	{
		return sharedPreferences.getString(Preferences.KEY_LANDSCAPEKB,
				resources.getString(R.string.landscapekb_default));
	}

	public static void setLandscapeKeyboard(String value)
	{
		SharedPreferences.Editor ed = sharedPreferences.edit();
		ed.putString(Preferences.KEY_LANDSCAPEKB, value);
		ed.apply();
	}

	public static int getPortraitFontSize()
	{
		return sharedPreferences.getInt(Preferences.KEY_PORTRAITFONTSIZE, 0);
	}

	public static void setPortraitFontSize(int value)
	{
		SharedPreferences.Editor ed = sharedPreferences.edit();
		ed.putInt(Preferences.KEY_PORTRAITFONTSIZE, value);
		ed.apply();
	}

	public static int getLandscapeFontSize()
	{
		return sharedPreferences.getInt(Preferences.KEY_LANDSCAPEFONTSIZE, 0);
	}

	public static void setLandscapeFontSize(int value)
	{
		SharedPreferences.Editor ed = sharedPreferences.edit();
		ed.putInt(Preferences.KEY_LANDSCAPEFONTSIZE, value);
		ed.apply();
	}

	public static boolean getEnableTouch()
	{
		return sharedPreferences.getBoolean(Preferences.KEY_ENABLETOUCH, true);
	}

	/** @deprecated Unused. */
	@Deprecated
	public static int alert(Context ctx, String title, String msg)
	{
		new AlertDialog.Builder(ctx).setTitle(title).setMessage(msg)
				.setNegativeButton("OK", new DialogInterface.OnClickListener()
				{
					public void onClick(DialogInterface dialog, int whichButton)
					{
					}
				}).show();
		return 0;
	}

	public static KeyMapper getKeyMapper()
	{
		return keymapper;
	}

	public static boolean getHapticFeedbackEnabled() 
	{
		return sharedPreferences.getBoolean(Preferences.KEY_HAPTICFEEDBACKENABLED, true);
	}

    public static int getLayoutCount(){
        return sharedPreferences.getInt(Preferences.KEYBOARD_LAYOUT_COUNT, 0);
    }

    public static void setCustomLayoutCount(int layoutCount){
        sharedPreferences.edit().putInt(KEYBOARD_LAYOUT_COUNT, layoutCount).apply();
    }

    public static void setCurrentKeyboardLayout(int currentKeyboardLayout){
        sharedPreferences.edit().putInt(KEYBOARD_LAYOUT_CURRENT, currentKeyboardLayout).apply();
    }

    public static int getCurrentKeyboardLayout(){
        return sharedPreferences.getInt(KEYBOARD_LAYOUT_CURRENT, 0);
    }

	public static boolean getKeyboardArrowsEnabled(){
		return sharedPreferences.getBoolean(KEY_KEYBOARDARROWSENABLED, true);
	}

	public static boolean getLongpressAltEnabled(){
		return sharedPreferences.getBoolean(KEY_LONGPRESSENABLED, true);
	}

	// On-screen HUD shortcut buttons (help / wiki). Default on.
	public static boolean getHelpButtonEnabled(){
		return sharedPreferences.getBoolean(KEY_HELPBUTTONENABLED, true);
	}

	public static boolean getWikiButtonEnabled(){
		return sharedPreferences.getBoolean(KEY_WIKIBUTTONENABLED, true);
	}

	// When on, the HUD shortcut buttons require a long-press to fire; a short
	// tap falls through to the touch controls beneath them. Default off.
	public static boolean getHudButtonLongpressEnabled(){
		return sharedPreferences.getBoolean(KEY_HUDBUTTONLONGPRESS, false);
	}

	// Repeat interval (ms) for held 9-grid direction taps. 0 disables the
	// hold-to-repeat feature. Stored as a string by EditTextPreference.
	public static int getTouchDirectionRepeat()
	{
		String val = sharedPreferences.getString(KEY_TOUCHDIRECTIONREPEAT, "250");
		try
		{
			return Math.max(0, Integer.parseInt(val.trim()));
		}
		catch (NumberFormatException e)
		{
			return 250;
		}
	}

    public static SharedPreferences getCurrentKeyboardPreferences(Context context, KeyboardType keyboardType){
        int currentType = sharedPreferences.getInt(KEYBOARD_LAYOUT_CURRENT, 0);
        if (currentType == 0){
            return null;
        }
        return getKeyboardPreferences(context, currentType, keyboardType);
    }

    public static SharedPreferences getKeyboardPreferences(Context context, int id, KeyboardType keyboardType){
        if (keyboardType == null){
            return null;
        }
        String preferenceName = keyboardType.name() + '_' + id;
        SharedPreferences sharedPreferences = context.getSharedPreferences(preferenceName, 0);

        return sharedPreferences;
    }

    public static void deleteLayout(Context context, int layoutNumber){
        // Delete entries in associated layout files
        for (KeyboardType keyboardType : KeyboardType.values()){
            String preferenceName = keyboardType.name() + '_' + layoutNumber;
            SharedPreferences sharedPreferences = context.getSharedPreferences(preferenceName, 0);
            sharedPreferences.edit().clear().apply();
        }
        setCurrentKeyboardLayout(0);
        setCustomLayoutCount(0);
    }

    public static void addNewKeyboardLayout() {
        setCustomLayoutCount(1);
        setCurrentKeyboardLayout(1);
    }

    public static void addKeybindingToLayout(Context context, KeyboardType keyboardType, int keyIndex, int newCode, String label){
        String sharedPreferenceName = keyboardType.name() + "_1"; // Change this if we're using multiple layouts
        SharedPreferences sharedPreferences = context.getSharedPreferences(sharedPreferenceName, 0);

        String codePreferenceName = KEYBOARD_CODE_PREFIX + keyIndex;
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(codePreferenceName, newCode);

        String labelPreferenceName = KEYBOARD_LABEL_PREFIX + keyIndex;
        editor.putString(labelPreferenceName, label);

        editor.apply();
    }

    public static void setLongpressInLayout(Context context, KeyboardType keyboardType, int keyIndex, int code){
        String sharedPreferenceName = keyboardType.name() + "_1"; // Change this if we're using multiple layouts
        SharedPreferences sharedPreferences = context.getSharedPreferences(sharedPreferenceName, 0);
        sharedPreferences.edit().putInt(KEYBOARD_LONGPRESS_PREFIX + keyIndex, code).apply();
    }

    // Custom longpress code for a key in the custom layout, or -1 if none.
    public static int getLongpressInLayout(Context context, KeyboardType keyboardType, int keyIndex){
        String sharedPreferenceName = keyboardType.name() + "_1"; // Change this if we're using multiple layouts
        SharedPreferences sharedPreferences = context.getSharedPreferences(sharedPreferenceName, 0);
        return sharedPreferences.getInt(KEYBOARD_LONGPRESS_PREFIX + keyIndex, -1);
    }

    // "None" keyboard mode had no input surface (no DirectionalTouchView), so
    // anyone who reached it — via the Show/Hide toggle or the pref — was stuck
    // with no way out but wiping data. Now that None is usable, flip stranded
    // users back to Crawl Keyboard once so the fix is visible on update. The
    // one-shot flag means we never override a deliberate None choice later.
    public static void unstickNoKeyboard(){
        if (sharedPreferences.getBoolean(NO_KEYBOARD_UNSTICK_DONE, false)){
            return;
        }
        SharedPreferences.Editor ed = sharedPreferences.edit();
        if ("0".equals(getPortraitKeyboard())){
            ed.putString(KEY_PORTRAITKB, "1");
        }
        if ("0".equals(getLandscapeKeyboard())){
            ed.putString(KEY_LANDSCAPEKB, "1");
        }
        ed.putBoolean(NO_KEYBOARD_UNSTICK_DONE, true);
        ed.apply();
    }

    // Layout v2 inserted "," at QWERTY index 31 and "+" at SYMBOLS index
    // 34, shifting later key indices up by one; shift stored remaps to
    // match. Runs once; safe on fresh installs (empty remap files).
    public static void migrateKeyboardRemaps(Context context){
        if (sharedPreferences.getInt(KEYBOARD_REMAP_VERSION, 1) >= 2){
            return;
        }
        shiftRemapIndices(context, KeyboardType.QWERTY, 31);
        shiftRemapIndices(context, KeyboardType.SYMBOLS, 34);
        sharedPreferences.edit().putInt(KEYBOARD_REMAP_VERSION, 2).apply();
    }

    // Moves every remap entry with index >= insertIndex up by one. Final
    // state is computed before writing so overlapping old/new indices
    // (code_32 removed as old, rewritten as new) can't clobber each other.
    private static void shiftRemapIndices(Context context, KeyboardType keyboardType, int insertIndex){
        SharedPreferences prefs = getKeyboardPreferences(context, 1, keyboardType);
        Map<String, ?> all = prefs.getAll();
        Map<String, Object> moved = new HashMap<>();
        for (Map.Entry<String, ?> entry : all.entrySet()){
            Integer index = remapEntryIndex(entry.getKey());
            if (index == null || index < insertIndex){
                continue;
            }
            String prefix = remapEntryPrefix(entry.getKey());
            moved.put(prefix + (index + 1), entry.getValue());
        }
        if (moved.isEmpty()){
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : all.keySet()){
            Integer index = remapEntryIndex(key);
            if (index != null && index >= insertIndex && !moved.containsKey(key)){
                editor.remove(key);
            }
        }
        for (Map.Entry<String, Object> entry : moved.entrySet()){
            if (entry.getValue() instanceof Integer){
                editor.putInt(entry.getKey(), (Integer) entry.getValue());
            }else if (entry.getValue() instanceof String){
                editor.putString(entry.getKey(), (String) entry.getValue());
            }
        }
        editor.apply();
    }

    private static String remapEntryPrefix(String preferenceKey){
        if (preferenceKey.startsWith(KEYBOARD_CODE_PREFIX)){
            return KEYBOARD_CODE_PREFIX;
        }
        if (preferenceKey.startsWith(KEYBOARD_LABEL_PREFIX)){
            return KEYBOARD_LABEL_PREFIX;
        }
        if (preferenceKey.startsWith(KEYBOARD_LONGPRESS_PREFIX)){
            return KEYBOARD_LONGPRESS_PREFIX;
        }
        return null;
    }

    private static Integer remapEntryIndex(String preferenceKey){
        String prefix = remapEntryPrefix(preferenceKey);
        if (prefix == null){
            return null;
        }
        try{
            return Integer.parseInt(preferenceKey.substring(prefix.length()));
        }catch (NumberFormatException e){
            return null;
        }
    }

    public static void clearKeybindingInLayout(Context context, KeyboardType keyboardType, int keyIndex){
        String sharedPreferenceName = keyboardType.name() + "_1"; // Change this if we're using multiple layouts
        SharedPreferences sharedPreferences = context.getSharedPreferences(sharedPreferenceName, 0);

        String codePreferenceName = KEYBOARD_CODE_PREFIX + keyIndex;

        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(codePreferenceName);

        String labelPreferenceName = KEYBOARD_LABEL_PREFIX + keyIndex;
        editor.remove(labelPreferenceName);

        editor.remove(KEYBOARD_LONGPRESS_PREFIX + keyIndex);

        editor.apply();
    }

}
