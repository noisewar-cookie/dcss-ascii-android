package com.crawlmb;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class FontConfig
{
    private static final String TAG = "FontConfig";
    private static final String ASSET_PATH = "font_config.txt";

    public final float portraitFullFontScale;
    public final float portraitMapFontScale;
    public final int portraitMapOffsetCols;
    public final float portraitHudFontScale;
    public final float portraitMsgFontScale;
    public final float landscapeFontScale;

    private FontConfig(float portraitFullFontScale, float portraitMapFontScale,
                       int portraitMapOffsetCols, float portraitHudFontScale,
                       float portraitMsgFontScale, float landscapeFontScale)
    {
        this.portraitFullFontScale = portraitFullFontScale;
        this.portraitMapFontScale = portraitMapFontScale;
        this.portraitMapOffsetCols = portraitMapOffsetCols;
        this.portraitHudFontScale = portraitHudFontScale;
        this.portraitMsgFontScale = portraitMsgFontScale;
        this.landscapeFontScale = landscapeFontScale;
    }

    public static FontConfig load(AssetManager assets)
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

        return new FontConfig(
            getFloat(props, "portrait_full_font_scale", 1.25f),
            getFloat(props, "portrait_map_font_scale", 1.0f),
            getInt(props,   "portrait_map_offset_cols", 2),
            getFloat(props, "portrait_hud_font_scale", 1.0f),
            getFloat(props, "portrait_msg_font_scale", 1.5f),
            getFloat(props, "landscape_font_scale", 1.0f)
        );
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
}
