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

	// 9-grid touch zone dividers as "v1,v2,h1,h2" — fractions of the touch
	// area (v = vertical line x, h = horizontal line y from top). Lines may
	// coincide (collapses the grid toward 2x2) but never cross, and stay
	// GRID_LINE_PADDING away from the edges.
	public static final String KEY_GRIDLINES = "crawl.gridlines";
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

	private static final String KEY_HAPTICFEEDBACKENABLED = "crawl.hapticfeedbackenabled";
	private static final String KEY_KEYBOARDARROWSENABLED = "crawl.keyboardarrowsenabled";
	private static final String KEY_TOUCHDIRECTIONREPEAT = "crawl.touchdirectionrepeat";

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

	// Parsed KEY_GRIDLINES cache — grid taps are a hot path; invalidated by
	// setGridLines. Callers must not mutate the returned array.
	private static float[] gridLinesCache = null;

	// Validated grid line fractions {v1, v2, h1, h2}: ordered, within the
	// padding bounds, else the default thirds.
	public static float[] getGridLines()
	{
		if (gridLinesCache != null)
			return gridLinesCache;
		gridLinesCache = parseGridLines(
				sharedPreferences.getString(KEY_GRIDLINES, ""));
		return gridLinesCache;
	}

	public static void setGridLines(float[] lines)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.length; i++)
		{
			if (i > 0)
				sb.append(',');
			sb.append(lines[i]);
		}
		sharedPreferences.edit()
				.putString(KEY_GRIDLINES, sb.toString()).apply();
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

	public static int getOrientation()
	{
		return Integer.parseInt(sharedPreferences.getString(Preferences.KEY_ORIENTATION, "0"));
	}

	public static int getDefaultFontSize()
	{
		return fontSize;
	}

	public static int setDefaultFontSize(int value)
	{
		return fontSize = value;
	}

	public static int getKeyboardTransparency(){
		return sharedPreferences.getInt(Preferences.KEY_KEYBOARDTRANSPARENCY, 140);
	}

	public static void setKeyboardTransparency(int transparency) {
		sharedPreferences.edit().putInt(Preferences.KEY_KEYBOARDTRANSPARENCY, transparency).apply();
	}

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
