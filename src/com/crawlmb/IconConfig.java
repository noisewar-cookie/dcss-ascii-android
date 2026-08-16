package com.crawlmb;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

// On-screen shortcut buttons (help / wiki) and modal overlay tunables. Loaded
// from assets/icon_config.txt; mirrors FontConfig's parse-with-defaults model
// so a missing or malformed file falls back to sane values.
public class IconConfig
{
    private static final String TAG = "IconConfig";
    private static final String ASSET_PATH = "icon_config.txt";

    public final float hudButtonOpacity;
    public final float hudButtonRowSpan;
    public final int hudButtonMarginDp;
    public final int modalPaddingPercent;
    public final int modalBorderColor;
    public final int modalBorderWidthDp;

    private IconConfig(Properties props)
    {
        this.hudButtonOpacity    = Math.max(0f, Math.min(1f,
                getFloat(props, "hud_button_opacity", 0.6f)));
        this.hudButtonRowSpan    = Math.max(0.5f, getFloat(props, "hud_button_row_span", 2.0f));
        this.hudButtonMarginDp   = Math.max(0, getInt(props, "hud_button_margin_dp", 4));
        // Clamp: keep the modal from collapsing to nothing (40% per side = 20%
        // of the UI left visible).
        this.modalPaddingPercent = Math.max(0, Math.min(40,
                getInt(props, "modal_padding_percent", 5)));
        this.modalBorderColor    = getColor(props, "modal_border_color", 0xFF8F5630);
        this.modalBorderWidthDp  = Math.max(0, getInt(props, "modal_border_width_dp", 2));
    }

    public static IconConfig load(AssetManager assets)
    {
        Properties props = new Properties();
        try
        {
            InputStream is = assets.open(ASSET_PATH);
            props.load(is);
            is.close();
        }
        catch (IOException e)
        {
            Log.w(TAG, "Could not load " + ASSET_PATH + ", using defaults");
        }

        return new IconConfig(props);
    }

    private static float getFloat(Properties props, String key, float def)
    {
        String val = props.getProperty(key);
        if (val == null)
            return def;
        try
        {
            return Float.parseFloat(val.trim());
        }
        catch (NumberFormatException e)
        {
            Log.w(TAG, "Invalid value for " + key + ": " + val);
            return def;
        }
    }

    private static int getInt(Properties props, String key, int def)
    {
        String val = props.getProperty(key);
        if (val == null)
            return def;
        try
        {
            return Integer.parseInt(val.trim());
        }
        catch (NumberFormatException e)
        {
            Log.w(TAG, "Invalid value for " + key + ": " + val);
            return def;
        }
    }

    // Parse "RRGGBB", "#RRGGBB", "0xRRGGBB", or full 8-digit ARGB. Alpha is
    // forced to 0xFF when only 6 hex digits are supplied.
    private static int getColor(Properties props, String key, int def)
    {
        String val = props.getProperty(key);
        if (val == null)
            return def;
        String t = val.trim();
        if (t.startsWith("#"))
            t = t.substring(1);
        else if (t.startsWith("0x") || t.startsWith("0X"))
            t = t.substring(2);
        try
        {
            long parsed = Long.parseLong(t, 16);
            if (t.length() <= 6)
                parsed |= 0xFF000000L;
            return (int) parsed;
        }
        catch (NumberFormatException e)
        {
            Log.w(TAG, "Invalid color for " + key + ": " + val);
            return def;
        }
    }
}
