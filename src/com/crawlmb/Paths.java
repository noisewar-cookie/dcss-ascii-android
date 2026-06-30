package com.crawlmb;

import android.content.Context;
import android.os.Build;

import java.io.File;

// Centralizes filesystem locations for user-editable / user-visible content
// (init.txt, macro.txt, morgue dumps). These live under
// getExternalFilesDir(...), which resolves to
// /storage/emulated/0/Android/data/<pkg>/files/ — accessible to file
// browsers and over USB without runtime permissions. Falls back to internal
// storage if external is unmounted.
//
// Game-critical state (saves/, bones/, dat/, version.txt) intentionally
// stays at getFilesDir(). Don't add it here.
//
// Custom data folder mode (API 30+): when the user picks a SAF tree via
// PreferencesActivity, a parallel "custom/" staging tree mirrors the SAF
// folder. NativeWrapper points DCSS at the staging tree instead of the
// live tree for the entire session. The live tree is never touched while
// custom mode is active — toggling custom off reverts the session
// instantly to the previous live state. See CustomFolderSync for the
// SAF<->staging sync mechanics.
public final class Paths
{
    private Paths() {}

    // Below this API level the SAF/DocumentFile guarantees we rely on
    // (renameDocument, persistable URI permissions across reboots,
    // DocumentsContract listing performance) get spotty enough that
    // workarounds add more risk than the feature removes. Hide custom
    // mode entirely below this version — see PreferencesActivity.
    public static final int CUSTOM_FOLDER_MIN_SDK = Build.VERSION_CODES.R;

    public static boolean isCustomFolderSupported()
    {
        return Build.VERSION.SDK_INT >= CUSTOM_FOLDER_MIN_SDK;
    }

    // Is custom mode currently active? A URI being persisted in prefs is
    // enough — runtime SAF permission validity is checked separately at
    // launch (CustomFolderSync.hasPersistedPermission) and a failure there
    // forces the user through a re-pick / disable dialog before the game
    // starts.
    public static boolean isCustomMode(Context ctx)
    {
        if (!isCustomFolderSupported())
            return false;
        return !Preferences.getCustomFolderUri().isEmpty();
    }

    // --- Live (default) paths -------------------------------------------
    // These ALWAYS return the live app-private location regardless of
    // custom mode. Use these in code paths that must operate on the live
    // tree even when custom mode is on (e.g. asset seeding in SplashActivity,
    // backup/restore — though those are disabled in custom mode anyway).

    public static File getLiveUserVisibleDir(Context ctx, String name)
    {
        File ext = ctx.getExternalFilesDir(null);
        File base = (ext != null) ? ext : ctx.getFilesDir();
        File dir = new File(base, name);
        if (!dir.exists())
            dir.mkdirs();
        return dir;
    }

    public static File getLiveSettingsDir(Context ctx)
    {
        return getLiveUserVisibleDir(ctx, "settings");
    }

    public static File getLiveMorgueDir(Context ctx)
    {
        return getLiveUserVisibleDir(ctx, "morgue");
    }

    public static File getLiveDataDir(Context ctx)
    {
        return ctx.getFilesDir();
    }

    // --- Custom staging paths -------------------------------------------
    // Mirror of the user's SAF folder. Settings/morgue land alongside the
    // live external dirs (under a "custom/" subdir); saves land under the
    // internal app dir to avoid forcing /sdcard residence for save data
    // that the game opens with raw POSIX I/O.

    public static File getCustomStagingExternalRoot(Context ctx)
    {
        File ext = ctx.getExternalFilesDir(null);
        File base = (ext != null) ? ext : ctx.getFilesDir();
        File dir = new File(base, "custom");
        if (!dir.exists())
            dir.mkdirs();
        return dir;
    }

    public static File getCustomStagingInternalRoot(Context ctx)
    {
        File dir = new File(ctx.getFilesDir(), "custom");
        if (!dir.exists())
            dir.mkdirs();
        return dir;
    }

    public static File getCustomStagingSettingsDir(Context ctx)
    {
        File dir = new File(getCustomStagingExternalRoot(ctx), "settings");
        if (!dir.exists())
            dir.mkdirs();
        return dir;
    }

    public static File getCustomStagingMorgueDir(Context ctx)
    {
        File dir = new File(getCustomStagingExternalRoot(ctx), "morgue");
        if (!dir.exists())
            dir.mkdirs();
        return dir;
    }

    // The root passed as -dir to DCSS in custom mode. Must contain
    // saves/ (synced) and dat/ (symlinked to the live dat tree —
    // CustomFolderSync handles the symlink as part of pull). DCSS
    // derives several other paths from this root.
    public static File getCustomStagingDataDir(Context ctx)
    {
        return getCustomStagingInternalRoot(ctx);
    }

    public static File getCustomStagingSavesDir(Context ctx)
    {
        File dir = new File(getCustomStagingDataDir(ctx), "saves");
        if (!dir.exists())
            dir.mkdirs();
        return dir;
    }

    // --- Active paths ---------------------------------------------------
    // Pick live or custom-staging based on mode. NativeWrapper uses these
    // to wire DCSS at launch. Existing in-app editors/viewers should NOT
    // call these — they're greyed out in custom mode and should stay
    // bound to the live tree if they ever do run (defense in depth).

    public static File getActiveDataDir(Context ctx)
    {
        return isCustomMode(ctx)
                ? getCustomStagingDataDir(ctx)
                : getLiveDataDir(ctx);
    }

    public static File getActiveSettingsDir(Context ctx)
    {
        return isCustomMode(ctx)
                ? getCustomStagingSettingsDir(ctx)
                : getLiveSettingsDir(ctx);
    }

    public static File getActiveMorgueDir(Context ctx)
    {
        return isCustomMode(ctx)
                ? getCustomStagingMorgueDir(ctx)
                : getLiveMorgueDir(ctx);
    }

    // --- Legacy API (kept for in-app editors/viewers) -------------------
    // These existed before custom mode was added. They return the LIVE
    // dir. Editors/viewers are disabled in custom mode at the UI layer,
    // so these stay pinned to live by design — never follow custom mode.

    public static File getUserVisibleDir(Context ctx, String name)
    {
        return getLiveUserVisibleDir(ctx, name);
    }

    public static File getSettingsDir(Context ctx)
    {
        return getLiveSettingsDir(ctx);
    }

    public static File getMorgueDir(Context ctx)
    {
        return getLiveMorgueDir(ctx);
    }
}
