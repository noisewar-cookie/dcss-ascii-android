package com.crawlmb;

import android.content.Context;

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
public final class Paths
{
    private Paths() {}

    public static File getUserVisibleDir(Context ctx, String name)
    {
        File ext = ctx.getExternalFilesDir(null);
        File base = (ext != null) ? ext : ctx.getFilesDir();
        File dir = new File(base, name);
        if (!dir.exists())
            dir.mkdirs();
        return dir;
    }

    public static File getSettingsDir(Context ctx)
    {
        return getUserVisibleDir(ctx, "settings");
    }

    public static File getMorgueDir(Context ctx)
    {
        return getUserVisibleDir(ctx, "morgue");
    }
}
