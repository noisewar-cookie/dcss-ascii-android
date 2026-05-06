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

    public final float portraitDefaultFontScale;
    public final boolean portraitDefaultScrollable;
    public final boolean portraitDefaultVScrollable;
    public final float portraitMapFontScale;
    public final int portraitMapOffsetCols;
    public final float portraitHudFontScale;
    public final int portraitHudOffsetCols;
    public final float portraitMsgFontScale;
    public final float landscapeFontScale;

    public final float portraitPregameFontScale;
    public final boolean portraitPregameScrollable;
    public final float portraitMainmenuFontScale;
    public final boolean portraitMainmenuScrollable;
    public final float portraitItemsFontScale;
    public final boolean portraitItemsScrollable;
    public final boolean portraitItemsVScrollable;
    public final float portraitSpellsFontScale;
    public final boolean portraitSpellsScrollable;
    public final boolean portraitSpellsVScrollable;
    public final float portraitOverviewFontScale;
    public final boolean portraitOverviewScrollable;
    public final float portraitSkillsFontScale;
    public final boolean portraitSkillsScrollable;
    public final boolean portraitSkillsVScrollable;
    public final float portraitReligionFontScale;
    public final boolean portraitReligionScrollable;
    public final float portraitHiscoresFontScale;
    public final boolean portraitHiscoresScrollable;
    public final float portraitTravelFontScale;
    public final boolean portraitTravelScrollable;
    public final boolean portraitTravelVScrollable;
    public final float portraitLevelmapFontScale;
    public final boolean portraitLevelmapScrollable;
    public final boolean portraitLevelmapVScrollable;
    public final float portraitVfeaturesFontScale;
    public final boolean portraitVfeaturesScrollable;
    public final boolean portraitVfeaturesVScrollable;

    private FontConfig(Properties props)
    {
        this.portraitDefaultFontScale  = getFloat(props, "portrait_default_font_scale", 1.25f);
        this.portraitDefaultScrollable = getBool (props, "portrait_default_scrollable", true);
        this.portraitDefaultVScrollable= getBool (props, "portrait_default_vscrollable", true);
        this.portraitMapFontScale      = getFloat(props, "portrait_map_font_scale", 1.0f);
        this.portraitMapOffsetCols     = getInt  (props, "portrait_map_offset_cols", 2);
        this.portraitHudFontScale      = getFloat(props, "portrait_hud_font_scale", 1.0f);
        this.portraitHudOffsetCols     = getInt  (props, "portrait_hud_offset_cols", 0);
        this.portraitMsgFontScale      = getFloat(props, "portrait_msg_font_scale", 1.5f);
        this.landscapeFontScale        = getFloat(props, "landscape_font_scale", 1.0f);

        this.portraitPregameFontScale  = getFloat(props, "portrait_pregame_font_scale", this.portraitDefaultFontScale);
        this.portraitPregameScrollable = getBool (props, "portrait_pregame_scrollable", this.portraitDefaultScrollable);
        this.portraitMainmenuFontScale = getFloat(props, "portrait_mainmenu_font_scale", this.portraitDefaultFontScale);
        this.portraitMainmenuScrollable= getBool (props, "portrait_mainmenu_scrollable", this.portraitDefaultScrollable);
        this.portraitItemsFontScale    = getFloat(props, "portrait_items_font_scale", this.portraitDefaultFontScale);
        this.portraitItemsScrollable   = getBool (props, "portrait_items_scrollable", this.portraitDefaultScrollable);
        this.portraitItemsVScrollable  = getBool (props, "portrait_items_vscrollable", false);
        this.portraitSpellsFontScale   = getFloat(props, "portrait_spells_font_scale", this.portraitDefaultFontScale);
        this.portraitSpellsScrollable  = getBool (props, "portrait_spells_scrollable", this.portraitDefaultScrollable);
        this.portraitSpellsVScrollable = getBool (props, "portrait_spells_vscrollable", this.portraitDefaultVScrollable);
        this.portraitOverviewFontScale = getFloat(props, "portrait_overview_font_scale", this.portraitDefaultFontScale);
        this.portraitOverviewScrollable= getBool (props, "portrait_overview_scrollable", this.portraitDefaultScrollable);
        this.portraitSkillsFontScale   = getFloat(props, "portrait_skills_font_scale", this.portraitDefaultFontScale);
        this.portraitSkillsScrollable  = getBool (props, "portrait_skills_scrollable", this.portraitDefaultScrollable);
        this.portraitSkillsVScrollable = getBool (props, "portrait_skills_vscrollable", true);
        this.portraitReligionFontScale = getFloat(props, "portrait_religion_font_scale", this.portraitDefaultFontScale);
        this.portraitReligionScrollable= getBool (props, "portrait_religion_scrollable", this.portraitDefaultScrollable);
        this.portraitHiscoresFontScale = getFloat(props, "portrait_hiscores_font_scale", this.portraitDefaultFontScale);
        this.portraitHiscoresScrollable= getBool (props, "portrait_hiscores_scrollable", this.portraitDefaultScrollable);
        this.portraitTravelFontScale   = getFloat(props, "portrait_travel_font_scale", this.portraitDefaultFontScale);
        this.portraitTravelScrollable  = getBool (props, "portrait_travel_scrollable", this.portraitDefaultScrollable);
        this.portraitTravelVScrollable = getBool (props, "portrait_travel_vscrollable", this.portraitDefaultVScrollable);
        this.portraitLevelmapFontScale = getFloat(props, "portrait_levelmap_font_scale", this.portraitDefaultFontScale);
        this.portraitLevelmapScrollable= getBool (props, "portrait_levelmap_scrollable", this.portraitDefaultScrollable);
        this.portraitLevelmapVScrollable=getBool (props, "portrait_levelmap_vscrollable", this.portraitDefaultVScrollable);
        this.portraitVfeaturesFontScale= getFloat(props, "portrait_vfeatures_font_scale", this.portraitDefaultFontScale);
        this.portraitVfeaturesScrollable=getBool (props, "portrait_vfeatures_scrollable", this.portraitDefaultScrollable);
        this.portraitVfeaturesVScrollable=getBool(props, "portrait_vfeatures_vscrollable", this.portraitDefaultVScrollable);
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

        return new FontConfig(props);
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

    private static boolean getBool(Properties props, String key, boolean def)
    {
        String val = props.getProperty(key);
        if (val == null)
            return def;
        String t = val.trim().toLowerCase();
        if (t.equals("true") || t.equals("1") || t.equals("yes") || t.equals("on"))
            return true;
        if (t.equals("false") || t.equals("0") || t.equals("no") || t.equals("off"))
            return false;
        Log.w(TAG, "Invalid value for " + key + ": " + val);
        return def;
    }
}
