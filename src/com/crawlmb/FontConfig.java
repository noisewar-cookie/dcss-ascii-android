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
    public final float portraitMapZoomStep1;
    public final float portraitMapZoomStep2;
    public final float portraitHudFontScale;
    public final int portraitHudOffsetCols;
    public final float portraitMsgFontScale;
    public final float landscapeFontScale;

    public final float portraitPregameFontScale;
    public final boolean portraitPregameScrollable;
    public final float portraitMainmenuFontScale;
    public final boolean portraitMainmenuScrollable;
    public final float portraitQuickControlsFontScale;
    public final boolean portraitQuickControlsScrollable;
    public final boolean portraitQuickControlsVScrollable;
    public final int portraitQuickControlsFontColor;
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

    // Newgame portrait layout: each species/background category renders into
    // its own vertically-stacked panel sampled from a fixed terminal
    // rectangle, so each gets an independent scale knob. The welcome row 0
    // is its own thin panel above the categories; sub-options are a wider
    // panel below them.
    public final float portraitNewgameWelcomeFontScale;
    public final boolean portraitNewgameWelcomeScrollable;
    public final boolean portraitNewgameWelcomeVScrollable;
    // One shared knob set for all 8 category panels (species
    // simple/intermediate/advanced + background warrior/zealot/adventurer/
    // warrior-mage/mage). They render the same kind of content at the same
    // glyph size, so per-category scale isn't useful in practice.
    public final float portraitNewgameCategoryFontScale;
    public final boolean portraitNewgameCategoryScrollable;
    public final boolean portraitNewgameCategoryVScrollable;
    public final float portraitNewgameDescFontScale;
    public final boolean portraitNewgameDescScrollable;
    public final boolean portraitNewgameDescVScrollable;
    public final float portraitNewgameSubFontScale;
    public final boolean portraitNewgameSubScrollable;
    public final boolean portraitNewgameSubVScrollable;
    public final float portraitNewgameNameFontScale;
    public final boolean portraitNewgameNameScrollable;
    public final boolean portraitNewgameNameVScrollable;

    private FontConfig(Properties props)
    {
        this.portraitDefaultFontScale  = getFloat(props, "portrait_default_font_scale", 1.25f);
        this.portraitDefaultScrollable = getBool (props, "portrait_default_scrollable", true);
        this.portraitDefaultVScrollable= getBool (props, "portrait_default_vscrollable", true);
        this.portraitMapFontScale      = getFloat(props, "portrait_map_font_scale", 1.0f);
        this.portraitMapOffsetCols     = getInt  (props, "portrait_map_offset_cols", 0);
        this.portraitMapZoomStep1      = getFloat(props, "portrait_map_zoom_step1", 1.25f);
        this.portraitMapZoomStep2      = getFloat(props, "portrait_map_zoom_step2", 1.5f);
        this.portraitHudFontScale      = getFloat(props, "portrait_hud_font_scale", 1.0f);
        this.portraitHudOffsetCols     = getInt  (props, "portrait_hud_offset_cols", 0);
        this.portraitMsgFontScale      = getFloat(props, "portrait_msg_font_scale", 1.5f);
        this.landscapeFontScale        = getFloat(props, "landscape_font_scale", 1.0f);

        this.portraitPregameFontScale  = getFloat(props, "portrait_pregame_font_scale", this.portraitDefaultFontScale);
        this.portraitPregameScrollable = getBool (props, "portrait_pregame_scrollable", this.portraitDefaultScrollable);
        this.portraitMainmenuFontScale = getFloat(props, "portrait_mainmenu_font_scale", this.portraitDefaultFontScale);
        this.portraitMainmenuScrollable= getBool (props, "portrait_mainmenu_scrollable", this.portraitDefaultScrollable);
        this.portraitQuickControlsFontScale  = getFloat(props, "portrait_quickcontrols_font_scale", 1.6f);
        this.portraitQuickControlsScrollable = getBool (props, "portrait_quickcontrols_scrollable", true);
        this.portraitQuickControlsVScrollable= getBool (props, "portrait_quickcontrols_vscrollable", true);
        // Default 0xFFC0C0C0 matches DCSS LIGHTGRAY (see colourMap in
        // android-console-patches/libandroid.cc), the color of most main-
        // menu prompts including "Enter your name:".
        this.portraitQuickControlsFontColor  = getColor(props, "portrait_quickcontrols_font_color", 0xFFC0C0C0);
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

        // Newgame panels. Each category gets its own knob; default each to
        // the pregame scale so the screens still render before the user has
        // tuned them. Per-category overrides let the user fit any group on
        // small screens without affecting the others.
        float ngDefault = this.portraitPregameFontScale;
        boolean ngHDefault = this.portraitDefaultScrollable;
        boolean ngVDefault = this.portraitDefaultVScrollable;
        this.portraitNewgameWelcomeFontScale     = getFloat(props, "portrait_newgame_welcome_font_scale", ngDefault);
        this.portraitNewgameWelcomeScrollable    = getBool (props, "portrait_newgame_welcome_scrollable", ngHDefault);
        this.portraitNewgameWelcomeVScrollable   = getBool (props, "portrait_newgame_welcome_vscrollable", ngVDefault);
        this.portraitNewgameCategoryFontScale    = getFloat(props, "portrait_newgame_category_font_scale", ngDefault);
        this.portraitNewgameCategoryScrollable   = getBool (props, "portrait_newgame_category_scrollable", ngHDefault);
        this.portraitNewgameCategoryVScrollable  = getBool (props, "portrait_newgame_category_vscrollable", ngVDefault);
        this.portraitNewgameDescFontScale        = getFloat(props, "portrait_newgame_desc_font_scale", ngDefault);
        this.portraitNewgameDescScrollable       = getBool (props, "portrait_newgame_desc_scrollable", ngHDefault);
        this.portraitNewgameDescVScrollable      = getBool (props, "portrait_newgame_desc_vscrollable", ngVDefault);
        this.portraitNewgameSubFontScale         = getFloat(props, "portrait_newgame_sub_font_scale", ngDefault);
        this.portraitNewgameSubScrollable        = getBool (props, "portrait_newgame_sub_scrollable", ngHDefault);
        this.portraitNewgameSubVScrollable       = getBool (props, "portrait_newgame_sub_vscrollable", ngVDefault);
        this.portraitNewgameNameFontScale        = getFloat(props, "portrait_newgame_name_font_scale", ngDefault);
        this.portraitNewgameNameScrollable       = getBool (props, "portrait_newgame_name_scrollable", ngHDefault);
        this.portraitNewgameNameVScrollable      = getBool (props, "portrait_newgame_name_vscrollable", ngVDefault);
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

    // Parse "RRGGBB", "#RRGGBB", "0xRRGGBB", or full 8-digit ARGB. Returns
    // an int suitable for android.graphics.Color (alpha forced to 0xFF when
    // only 6 hex digits supplied — the rendering surface is opaque).
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
