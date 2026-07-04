package com.crawlmb;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// SAF tree <-> custom-staging incremental mirror. API 30+ only.
//
// Sync rules (both directions):
//   - mtime-based. A file is copied only when the source is newer than
//     the destination by more than MTIME_TOLERANCE_MS.
//   - NEVER destructive. We do not delete files based on absence on the
//     other side. PC may be offline, sync may be pending, and deleting
//     locally because SAF doesn't have the file (or vice versa) is what
//     wiped user saves in the previous design.
//   - After each copy, the local-side mtime is aligned to the remote so
//     subsequent passes are no-ops when nothing changed (no wasted SAF
//     I/O on every launch).
//
// Atomic per-file writes via tmp+rename so external watchers (Syncthing)
// never observe a partial file.
@TargetApi(Build.VERSION_CODES.R)
public final class CustomFolderSync
{
    private static final String TAG = "CustomFolderSync";

    private static final String[] SYNC_SUBTREES = {"settings", "morgue", "saves"};
    private static final String TMP_SUFFIX = ".crawltmp";

    // Mtime comparison tolerance. SAF providers often round to seconds,
    // and some filesystems (FAT32 on SD cards) have 2-second resolution.
    // A side is "newer" only if it exceeds the other by more than this.
    private static final long MTIME_TOLERANCE_MS = 2000L;

    private static ExecutorService pushExecutor;

    private static synchronized ExecutorService pushExecutor()
    {
        if (pushExecutor == null)
            pushExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "CustomFolderPush");
                t.setDaemon(false);
                return t;
            });
        return pushExecutor;
    }

    private CustomFolderSync() {}

    public static boolean hasPersistedPermission(Context ctx)
    {
        String uriStr = Preferences.getCustomFolderUri();
        if (uriStr.isEmpty())
            return false;
        Uri target = Uri.parse(uriStr);
        ContentResolver cr = ctx.getContentResolver();
        for (UriPermission p : cr.getPersistedUriPermissions())
        {
            if (p.getUri().equals(target) && p.isReadPermission()
                    && p.isWritePermission())
                return true;
        }
        return false;
    }

    // Subdirs DCSS expects under saves/, mirroring InstallProgramTask
    // on the live tree.
    private static final String[] SAVES_SUBDIRS = {
            "db", "des", "sprint", "zotdef", "bones"
    };

    // Seeded from assets when missing so an empty custom folder still
    // boots into a runnable state. Never overwritten if present.
    private static final String[] REQUIRED_CONFIG_FILES = {
            "init.txt", "macro.txt"
    };

    // Fill in saves subdirs, seed missing required configs, set up the
    // dat/ symlink. Called from pull() so every launch lands runnable.
    // Never overwrites existing user data.
    public static boolean ensureStagingScaffold(Context ctx)
    {
        File savesDir = Paths.getCustomStagingSavesDir(ctx);
        for (String sub : SAVES_SUBDIRS)
        {
            File d = new File(savesDir, sub);
            if (!d.exists())
                d.mkdirs();
        }

        File settingsDir = Paths.getCustomStagingSettingsDir(ctx);
        for (String name : REQUIRED_CONFIG_FILES)
        {
            File dest = new File(settingsDir, name);
            if (!dest.exists())
                seedAssetFile(ctx, "settings/" + name, dest);
        }

        return ensureDatSymlink(ctx);
    }

    private static void seedAssetFile(Context ctx, String assetPath, File dest)
    {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();
        try (InputStream in = ctx.getAssets().open(assetPath);
                OutputStream out = new FileOutputStream(dest, false))
        {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1)
                out.write(buf, 0, n);
        }
        catch (IOException e)
        {
            Log.w(TAG, "seed asset " + assetPath + " -> " + dest + " failed: " + e);
        }
    }

    // SAF -> staging incremental merge, then scaffold. Run on every
    // custom-mode launch. Cheap when nothing changed.
    public static boolean pull(Context ctx)
    {
        DocumentFile root = openRoot(ctx);
        if (root == null)
            return false;
        boolean ok = true;
        for (String name : SYNC_SUBTREES)
        {
            DocumentFile sub = root.findFile(name);
            if (sub == null || !sub.isDirectory())
                continue;
            ok &= pullTree(ctx, sub, stagingDirFor(ctx, name));
        }
        ok &= ensureStagingScaffold(ctx);
        return ok;
    }

    // staging -> SAF incremental merge. Run after each save (onPause).
    public static boolean push(Context ctx)
    {
        DocumentFile root = openRoot(ctx);
        if (root == null)
            return false;
        boolean ok = true;
        for (String name : SYNC_SUBTREES)
        {
            File stagingDir = stagingDirFor(ctx, name);
            if (!stagingDir.isDirectory())
                continue;
            ok &= pushTree(ctx, stagingDir, getOrCreateDir(root, name));
        }
        return ok;
    }

    public static void pushAsync(Context ctx)
    {
        if (!Paths.isCustomMode(ctx))
            return;
        Context app = ctx.getApplicationContext();
        pushExecutor().submit(() -> {
            try { push(app); }
            catch (Throwable t) { Log.w(TAG, "background push failed: " + t); }
        });
    }

    // FIFO-serialized behind any pushAsync already queued, so this can't
    // race with a preceding notifyGameSaved on the same tmp filename.
    public static boolean pushBlocking(Context ctx, long timeoutMs)
    {
        if (!Paths.isCustomMode(ctx))
            return true;
        Context app = ctx.getApplicationContext();
        Future<Boolean> f = pushExecutor().submit(() -> {
            try { return push(app); }
            catch (Throwable t)
            {
                Log.w(TAG, "blocking push failed: " + t);
                return false;
            }
        });
        try
        {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e)
        {
            Log.w(TAG, "blocking push timed out after " + timeoutMs + "ms"
                    + " — task continues in background");
            return false;
        }
        catch (Exception e)
        {
            Log.w(TAG, "blocking push interrupted: " + e);
            return false;
        }
    }

    public static void clearStaging(Context ctx)
    {
        deleteTree(Paths.getCustomStagingExternalRoot(ctx));
        deleteTree(Paths.getCustomStagingInternalRoot(ctx));
    }

    public static void releasePermission(Context ctx)
    {
        String uriStr = Preferences.getCustomFolderUri();
        if (uriStr.isEmpty())
            return;
        Uri target = Uri.parse(uriStr);
        try
        {
            ctx.getContentResolver().releasePersistableUriPermission(target,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
        catch (SecurityException ignored) {}
    }

    // ----- internals --------------------------------------------------

    private static File stagingDirFor(Context ctx, String name)
    {
        switch (name)
        {
            case "settings": return Paths.getCustomStagingSettingsDir(ctx);
            case "morgue":   return Paths.getCustomStagingMorgueDir(ctx);
            case "saves":    return Paths.getCustomStagingSavesDir(ctx);
            default:
                throw new IllegalArgumentException("unknown subtree " + name);
        }
    }

    private static DocumentFile openRoot(Context ctx)
    {
        String uriStr = Preferences.getCustomFolderUri();
        if (uriStr.isEmpty())
            return null;
        try
        {
            DocumentFile root = DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr));
            if (root == null || !root.isDirectory() || !root.canWrite())
                return null;
            return root;
        }
        catch (Exception e)
        {
            Log.w(TAG, "openRoot failed: " + e);
            return null;
        }
    }

    private static DocumentFile getOrCreateDir(DocumentFile parent, String name)
    {
        DocumentFile existing = parent.findFile(name);
        if (existing != null && existing.isDirectory())
            return existing;
        if (existing != null)
            existing.delete();
        return parent.createDirectory(name);
    }

    // For each SAF child: copy to staging only when SAF is newer.
    // Recurses into subdirectories. Never deletes from staging.
    private static boolean pullTree(Context ctx, DocumentFile src, File dest)
    {
        if (!dest.exists())
            dest.mkdirs();
        boolean ok = true;
        for (DocumentFile child : src.listFiles())
        {
            String name = child.getName();
            if (name == null || name.endsWith(TMP_SUFFIX))
                continue;
            File destChild = new File(dest, name);
            if (child.isDirectory())
            {
                ok &= pullTree(ctx, child, destChild);
            }
            else if (child.isFile())
            {
                long safMtime = child.lastModified();
                long localMtime = destChild.exists()
                        ? destChild.lastModified() : 0L;
                if (safMtime > localMtime + MTIME_TOLERANCE_MS)
                {
                    if (copyFromSaf(ctx, child, destChild))
                    {
                        if (safMtime > 0)
                            destChild.setLastModified(safMtime);
                    }
                    else
                    {
                        ok = false;
                    }
                }
            }
        }
        return ok;
    }

    private static boolean copyFromSaf(Context ctx, DocumentFile src, File dest)
    {
        try (InputStream in = ctx.getContentResolver().openInputStream(src.getUri()))
        {
            if (in == null)
                return false;
            try (OutputStream out = new FileOutputStream(dest, false))
            {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1)
                    out.write(buf, 0, n);
            }
            return true;
        }
        catch (IOException e)
        {
            Log.w(TAG, "pull copy failed " + dest + ": " + e);
            return false;
        }
    }

    // For each staging child: copy to SAF only when staging is newer.
    // Atomic per file via tmp+rename. Stale .crawltmp from a previous
    // killed push is cleaned up before use. Never deletes from SAF.
    private static boolean pushTree(Context ctx, File src, DocumentFile dest)
    {
        if (dest == null)
            return false;
        File[] kids = src.listFiles();
        if (kids == null)
            return true;

        // Build a name -> DocumentFile index of the remote dir once. Saves
        // O(N^2) findFile() scans when the remote dir has many entries.
        Map<String, DocumentFile> remote = new HashMap<>();
        for (DocumentFile rf : dest.listFiles())
        {
            String n = rf.getName();
            if (n == null) continue;
            if (n.endsWith(TMP_SUFFIX))
            {
                rf.delete();  // stale tmp from a killed push
                continue;
            }
            remote.put(n, rf);
        }

        boolean ok = true;
        for (File kid : kids)
        {
            String name = kid.getName();
            if (name.endsWith(TMP_SUFFIX))
                continue;
            DocumentFile remoteMatch = remote.get(name);
            if (kid.isDirectory())
            {
                DocumentFile remoteDir = (remoteMatch != null
                        && remoteMatch.isDirectory())
                        ? remoteMatch : getOrCreateDir(dest, name);
                ok &= pushTree(ctx, kid, remoteDir);
            }
            else if (kid.isFile())
            {
                long localMtime = kid.lastModified();
                long remoteMtime = (remoteMatch != null
                        && remoteMatch.isFile())
                        ? remoteMatch.lastModified() : 0L;
                if (localMtime > remoteMtime + MTIME_TOLERANCE_MS)
                    ok &= pushFile(ctx, kid, dest, remoteMatch);
            }
        }
        return ok;
    }

    private static boolean pushFile(Context ctx, File src,
            DocumentFile destDir, DocumentFile existingFinal)
    {
        String finalName = src.getName();
        String tmpName = finalName + TMP_SUFFIX;

        DocumentFile staleTmp = destDir.findFile(tmpName);
        if (staleTmp != null)
            staleTmp.delete();

        String mime = guessMime(finalName);
        DocumentFile tmp = destDir.createFile(mime, tmpName);
        if (tmp == null)
        {
            Log.w(TAG, "push createFile failed " + tmpName);
            return false;
        }

        try (InputStream in = new FileInputStream(src);
                OutputStream out = ctx.getContentResolver().openOutputStream(
                        tmp.getUri(), "w"))
        {
            if (out == null)
            {
                tmp.delete();
                return false;
            }
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1)
                out.write(buf, 0, n);
        }
        catch (IOException e)
        {
            Log.w(TAG, "push write failed " + tmpName + ": " + e);
            try { tmp.delete(); } catch (Exception ignored) {}
            return false;
        }

        // Some SAF providers (Syncthing) invalidate DocumentFile refs after
        // sibling changes; the pushTree snapshot's .delete() silently
        // no-ops, then renameDocument appends " (1)" instead of overwriting.
        DocumentFile freshExisting = destDir.findFile(finalName);
        if (freshExisting != null)
        {
            boolean deleted;
            try { deleted = freshExisting.delete(); }
            catch (Exception e) { deleted = false; }
            if (!deleted || destDir.findFile(finalName) != null)
            {
                Log.w(TAG, "push delete-existing failed for " + finalName
                        + " — aborting to avoid duplicate; will retry next push");
                try { tmp.delete(); } catch (Exception ignored) {}
                return false;
            }
        }

        try
        {
            Uri renamed = DocumentsContract.renameDocument(
                    ctx.getContentResolver(), tmp.getUri(), finalName);
            if (renamed == null)
            {
                Log.w(TAG, "push rename returned null " + tmpName + "->" + finalName);
                return false;
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "push rename failed " + tmpName + "->" + finalName
                    + ": " + e);
            return false;
        }

        // Provider may still suffix " (n)" despite the delete above.
        DocumentFile finalDoc = destDir.findFile(finalName);
        if (finalDoc == null)
        {
            Log.w(TAG, "push post-rename findFile null for " + finalName);
            return false;
        }

        // Align local mtime to remote so the next pull doesn't copy back.
        long m = finalDoc.lastModified();
        if (m > 0)
            src.setLastModified(m);
        return true;
    }

    private static String guessMime(String name)
    {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = name.substring(dot + 1).toLowerCase();
        switch (ext)
        {
            case "txt": return "text/plain";
            case "json": return "application/json";
            default: return "application/octet-stream";
        }
    }

    // staging/dat -> live/dat symlink so DCSS finds bundled game data
    // without us copying hundreds of MB into staging. Re-runs every
    // pull/bootstrap; no-op if the link already points at the right
    // target. Falls back to a copy if the platform refuses the symlink.
    private static boolean ensureDatSymlink(Context ctx)
    {
        File stagingRoot = Paths.getCustomStagingDataDir(ctx);
        File link = new File(stagingRoot, "dat");
        File target = new File(Paths.getLiveDataDir(ctx), "dat");
        if (!target.isDirectory())
        {
            Log.w(TAG, "live dat/ missing — game data wasn't installed");
            return false;
        }
        if (link.exists())
        {
            try
            {
                String linkRead = Os.readlink(link.getAbsolutePath());
                if (target.getAbsolutePath().equals(linkRead))
                    return true;
            }
            catch (ErrnoException e) { /* not a symlink — replace */ }
            deleteTree(link);
        }
        try
        {
            Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
            return true;
        }
        catch (ErrnoException e)
        {
            Log.w(TAG, "symlink dat failed: " + e + " — falling back to copy");
            return copyTree(target, link);
        }
    }

    // Local file-tree copy used by the dat-symlink copy fallback when
    // the platform refuses Os.symlink. Preserves source mtime on the
    // destination so mtime-based sync passes start from a sensible
    // baseline.
    private static boolean copyTree(File src, File dst)
    {
        if (!src.exists())
            return true;
        if (src.isDirectory())
        {
            if (!dst.exists() && !dst.mkdirs())
                return false;
            File[] kids = src.listFiles();
            if (kids == null) return true;
            boolean ok = true;
            for (File k : kids)
                ok &= copyTree(k, new File(dst, k.getName()));
            return ok;
        }
        try (InputStream in = new FileInputStream(src);
                OutputStream out = new FileOutputStream(dst, false))
        {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1)
                out.write(buf, 0, n);
            long m = src.lastModified();
            if (m > 0)
                dst.setLastModified(m);
            return true;
        }
        catch (IOException e)
        {
            Log.w(TAG, "copyTree failed " + src + " -> " + dst + ": " + e);
            return false;
        }
    }

    // Recursive delete that NEVER follows symlinks. The staging tree
    // contains staging/dat as a symlink to live/dat (set up by
    // ensureDatSymlink), and File.isDirectory() returns true for a
    // symlink-to-dir while File.listFiles() walks the target. Without
    // the symlink guard, clearStaging() would recursively delete the
    // user's live dat/ tree, leaving the next launch with no game data.
    //
    // Detection uses Os.lstat (POSIX, doesn't follow links) rather than
    // File.isFile/isDirectory (which do follow). On any detection error
    // we default to "treat as symlink" so we never accidentally recurse
    // through one — a stale symlink left undeleted is a minor leak; a
    // recursive delete through it is catastrophic.
    private static void deleteTree(File f)
    {
        if (f == null)
            return;
        boolean isSymlink = true;        // fail-safe default
        boolean isDirectory = false;
        try
        {
            android.system.StructStat st = Os.lstat(f.getAbsolutePath());
            isSymlink = android.system.OsConstants.S_ISLNK(st.st_mode);
            isDirectory = android.system.OsConstants.S_ISDIR(st.st_mode);
        }
        catch (ErrnoException e)
        {
            // ENOENT or similar — let f.delete() handle the cleanup.
            // If the path is gone there's nothing to do.
            if (!f.exists())
                return;
        }
        if (!isSymlink && isDirectory)
        {
            File[] kids = f.listFiles();
            if (kids != null)
                for (File k : kids)
                    deleteTree(k);
        }
        f.delete();
    }
}
