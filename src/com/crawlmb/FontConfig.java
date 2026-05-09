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

    // Newgame portrait layout: each species/background category renders into
    // its own vertically-stacked panel sampled from a fixed terminal
    // rectangle, so each gets an independent scale knob. The welcome row 0
    // is its own thin panel above the categories; sub-options are a wider
    // panel below them.
    public final float portraitNewgameWelcomeFontScale;
    public final boolean portraitNewgameWelcomeScrollable;
    public final boolean portraitNewgameWelcomeVScrollable;
    public final float portraitNewgameSpeciesSimpleFontScale;
    public final boolean portraitNewgameSpeciesSimpleScrollable;
    public final boolean portraitNewgameSpeciesSimpleVScrollable;
    public final float portraitNewgameSpeciesIntermediateFontScale;
    public final boolean portraitNewgameSpeciesIntermediateScrollable;
    public final boolean portraitNewgameSpeciesIntermediateVScrollable;
    public final float portraitNewgameSpeciesAdvancedFontScale;
    public final boolean portraitNewgameSpeciesAdvancedScrollable;
    public final boolean portraitNewgameSpeciesAdvancedVScrollable;
    public final float portraitNewgameBackgroundWarriorFontScale;
    public final boolean portraitNewgameBackgroundWarriorScrollable;
    public final boolean portraitNewgameBackgroundWarriorVScrollable;
    public final float portraitNewgameBackgroundZealotFontScale;
    public final boolean portraitNewgameBackgroundZealotScrollable;
    public final boolean portraitNewgameBackgroundZealotVScrollable;
    public final float portraitNewgameBackgroundAdventurerFontScale;
    public final boolean portraitNewgameBackgroundAdventurerScrollable;
    public final boolean portraitNewgameBackgroundAdventurerVScrollable;
    public final float portraitNewgameBackgroundWarriorMageFontScale;
    public final boolean portraitNewgameBackgroundWarriorMageScrollable;
    public final boolean portraitNewgameBackgroundWarriorMageVScrollable;
    public final float portraitNewgameBackgroundMageFontScale;
    public final boolean portraitNewgameBackgroundMageScrollable;
    public final boolean portraitNewgameBackgroundMageVScrollable;
    public final float portraitNewgameSubFontScale;
    public final boolean portraitNewgameSubScrollable;
    public final boolean portraitNewgameSubVScrollable;

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

        // Newgame panels. Each category gets its own knob; default each to
        // the pregame scale so the screens still render before the user has
        // tuned them. Per-category overrides let the user fit any group on
        // small screens without affecting the others.
        float ngDefault = this.portraitPregameFontScale;
        boolean ngHDefault = this.portraitDefaultScrollable;
        boolean ngVDefault = this.portraitDefaultVScrollable;
        this.portraitNewgameWelcomeFontScale            = getFloat(props, "portrait_newgame_welcome_font_scale", ngDefault);
        this.portraitNewgameWelcomeScrollable           = getBool (props, "portrait_newgame_welcome_scrollable", ngHDefault);
        this.portraitNewgameWelcomeVScrollable          = getBool (props, "portrait_newgame_welcome_vscrollable", ngVDefault);
        this.portraitNewgameSpeciesSimpleFontScale      = getFloat(props, "portrait_newgame_species_simple_font_scale", ngDefault);
        this.portraitNewgameSpeciesSimpleScrollable     = getBool (props, "portrait_newgame_species_simple_scrollable", ngHDefault);
        this.portraitNewgameSpeciesSimpleVScrollable    = getBool (props, "portrait_newgame_species_simple_vscrollable", ngVDefault);
        this.portraitNewgameSpeciesIntermediateFontScale= getFloat(props, "portrait_newgame_species_intermediate_font_scale", ngDefault);
        this.portraitNewgameSpeciesIntermediateScrollable=getBool (props, "portrait_newgame_species_intermediate_scrollable", ngHDefault);
        this.portraitNewgameSpeciesIntermediateVScrollable=getBool(props, "portrait_newgame_species_intermediate_vscrollable", ngVDefault);
        this.portraitNewgameSpeciesAdvancedFontScale    = getFloat(props, "portrait_newgame_species_advanced_font_scale", ngDefault);
        this.portraitNewgameSpeciesAdvancedScrollable   = getBool (props, "portrait_newgame_species_advanced_scrollable", ngHDefault);
        this.portraitNewgameSpeciesAdvancedVScrollable  = getBool (props, "portrait_newgame_species_advanced_vscrollable", ngVDefault);
        this.portraitNewgameBackgroundWarriorFontScale  = getFloat(props, "portrait_newgame_background_warrior_font_scale", ngDefault);
        this.portraitNewgameBackgroundWarriorScrollable = getBool (props, "portrait_newgame_background_warrior_scrollable", ngHDefault);
        this.portraitNewgameBackgroundWarriorVScrollable= getBool (props, "portrait_newgame_background_warrior_vscrollable", ngVDefault);
        this.portraitNewgameBackgroundZealotFontScale   = getFloat(props, "portrait_newgame_background_zealot_font_scale", ngDefault);
        this.portraitNewgameBackgroundZealotScrollable  = getBool (props, "portrait_newgame_background_zealot_scrollable", ngHDefault);
        this.portraitNewgameBackgroundZealotVScrollable = getBool (props, "portrait_newgame_background_zealot_vscrollable", ngVDefault);
        this.portraitNewgameBackgroundAdventurerFontScale=getFloat(props, "portrait_newgame_background_adventurer_font_scale", ngDefault);
        this.portraitNewgameBackgroundAdventurerScrollable=getBool(props, "portrait_newgame_background_adventurer_scrollable", ngHDefault);
        this.portraitNewgameBackgroundAdventurerVScrollable=getBool(props,"portrait_newgame_background_adventurer_vscrollable", ngVDefault);
        this.portraitNewgameBackgroundWarriorMageFontScale=getFloat(props,"portrait_newgame_background_warrior_mage_font_scale", ngDefault);
        this.portraitNewgameBackgroundWarriorMageScrollable=getBool(props,"portrait_newgame_background_warrior_mage_scrollable", ngHDefault);
        this.portraitNewgameBackgroundWarriorMageVScrollable=getBool(props,"portrait_newgame_background_warrior_mage_vscrollable", ngVDefault);
        this.portraitNewgameBackgroundMageFontScale     = getFloat(props, "portrait_newgame_background_mage_font_scale", ngDefault);
        this.portraitNewgameBackgroundMageScrollable    = getBool (props, "portrait_newgame_background_mage_scrollable", ngHDefault);
        this.portraitNewgameBackgroundMageVScrollable   = getBool (props, "portrait_newgame_background_mage_vscrollable", ngVDefault);
        this.portraitNewgameSubFontScale                = getFloat(props, "portrait_newgame_sub_font_scale", ngDefault);
        this.portraitNewgameSubScrollable               = getBool (props, "portrait_newgame_sub_scrollable", ngHDefault);
        this.portraitNewgameSubVScrollable              = getBool (props, "portrait_newgame_sub_vscrollable", ngVDefault);
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
