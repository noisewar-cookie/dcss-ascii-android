package com.crawlmb.activity;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

import com.crawlmb.Paths;
import com.crawlmb.Preferences;
import com.crawlmb.R;

public class SplashActivity extends Activity {
    public static final String TAG = SplashActivity.class.getName();
    // Intent extra forwarded to GameActivity: true when this launch ran
    // InstallProgramTask (first install or asset hash mismatch). GameActivity
    // uses it to decide whether to show the in-game loading message overlay —
    // skipped on cached launches where DCSS init is fast enough to not warrant
    // it.
    public static final String EXTRA_ASSETS_FRESHLY_INSTALLED =
            "com.crawlmb.assets_freshly_installed";
    private static final int INSTALL_DIALOG_ID = 0;
    private static final int WARNING_DIALOG_ID = 1;
    private ProgressDialog installDialog;
    private int versionCode = -1;
    private String versionName;
    private boolean updating = false;
    private boolean assetsFreshlyInstalled = false;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);


        setBackground();

        installIfRequired();
    }

    // Install a new version if we need to.
    //
    // Gating is content-hash based: setup.sh writes the SHA-256 of assets/dat/
    // contents to assets/dat-hash.txt at build time. We compare it to the copy
    // in filesDir from the previous install. Match → skip extraction so file
    // mtimes stay stable and DCSS's TextDB freshness check (database.cc:_needs_update)
    // keeps the already-built SQLite caches valid. Mismatch (or first install,
    // or upgrade from a pre-hash build) → re-extract.
    private void installIfRequired() {
        String expectedHash = readAssetHash();
        String installedHash = readInstalledHash();

        if (expectedHash != null && expectedHash.equals(installedHash)) {
            startGameActivity();
            return;
        }

        // If save folder exists, we are updating
        File saveDir = new File(getFilesDir() + "/saves");
        if (saveDir.exists()) {
            updating = true;
        }
        assetsFreshlyInstalled = true;
        new InstallProgramTask().execute();
    }

    private String readAssetHash() {
        AssetManager assetManager = getAssets();
        StringBuilder sb = new StringBuilder();
        BufferedInputStream in = null;
        try {
            in = new BufferedInputStream(assetManager.open("dat-hash.txt"));
            byte[] buf = new byte[128];
            int len;
            while ((len = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, len));
            }
            return sb.toString().trim();
        } catch (IOException ex) {
            Log.w(TAG, "dat-hash.txt missing from assets — will re-extract: " + ex);
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    private String readInstalledHash() {
        File hashFile = new File(getFilesDir() + "/dat-hash.txt");
        if (!hashFile.exists()) {
            return null;
        }
        String contents = readFile(hashFile);
        return contents == null ? null : contents.trim();
    }

    private int getApplicationVersionCode() {
        if (versionCode == -1) {
            try {
                versionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            } catch (NameNotFoundException e) {
                e.printStackTrace();
            }

        }
        return versionCode;
    }

    private String getVersionName() {
        if (versionName == null) {
            try {
                versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (NameNotFoundException e) {
                e.printStackTrace();
            }

        }
        return versionName;
    }

    private String readFile(File file) {

        FileInputStream fis;
        BufferedInputStream bis;
        DataInputStream dis;
        StringBuffer sb = new StringBuffer();

        try {
            fis = new FileInputStream(file);

            // Here BufferedInputStream is added for fast reading.
            bis = new BufferedInputStream(fis);
            dis = new DataInputStream(bis);

            // dis.available() returns 0 if the file does not have more lines.
            while (dis.available() != 0) {

                // this statement reads the line from the file and print it to
                // the console.
                sb.append(dis.readLine());
                if (dis.available() != 0) {
                    sb.append("\n");
                }
            }

            // dispose all the resources after using them.
            fis.close();
            bis.close();
            dis.close();

        } catch (FileNotFoundException e) {
            Log.e(TAG, "File not found", e);
            Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show();
            return null;
        } catch (IOException e) {
            Log.e(TAG, "File not found", e);
            Toast.makeText(this, R.string.error_reading_file, Toast.LENGTH_SHORT).show();
            return null;
        }

        return sb.toString();
    }

    private void setBackground() {
        int backgroundResource;
        int[] backgroundResources = {
                R.drawable.title_denzi_dragon,
                R.drawable.title_denzi_evil_mage,
                R.drawable.title_denzi_invasion,
                R.drawable.title_denzi_kitchen_duty,
                R.drawable.title_denzi_summoner,
                R.drawable.title_denzi_undead_warrior,
                R.drawable.title_firemage,
                R.drawable.title_omndra_zot_demon,
                R.drawable.title_shadyamish_octm
        };
        Random generator = new Random();
        backgroundResource = backgroundResources[generator.nextInt(backgroundResources.length)];

        ImageView background = (ImageView) findViewById(R.id.background);
        background.setImageResource(backgroundResource);
    }

    @Override
    public Dialog onCreateDialog(int id) {
        switch (id) {
            case INSTALL_DIALOG_ID:
                installDialog = new ProgressDialog(this);
                installDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                if (updating) {
                    installDialog.setTitle(getString(R.string.updating_dialog_title, getVersionName()));
                } else {
                    installDialog.setTitle(getString(R.string.install_dialog_title, getVersionName()));
                }
                installDialog.setIndeterminate(true);
                installDialog.setCancelable(false);
                return installDialog;
            case WARNING_DIALOG_ID:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setIcon(android.R.drawable.ic_dialog_alert);
                builder.setTitle(R.string.warning_dialog_title);
                builder.setMessage(getString(R.string.warning_dialog_message, getVersionName()));
                builder.setPositiveButton(R.string.warning_dialog_positive_button, new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new InstallProgramTask().execute();
                    }
                });
                builder.setNeutralButton(R.string.warning_dialog_neutral_button, new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent launchBrowser = new Intent(Intent.ACTION_VIEW, Uri.parse("http://code.google.com/p/dungeon-crawl-android/downloads/list"));
                        startActivity(launchBrowser);
                        finish();
                    }
                });
                builder.setNegativeButton(R.string.menu_quit, new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                });
                return builder.create();

            default:
                return null;
        }
    }

    private void chmod(String filename, int permissions) {
        // Using undocumented method, as per Nethack app

        try {
            Class<?> fileUtils = Class.forName("android.os.FileUtils");
            Method setPermissions =
                    fileUtils.getMethod("setPermissions", String.class, int.class, int.class, int.class);
            int a = (Integer) setPermissions.invoke(null, filename, permissions, -1, -1);
            if (a != 0) {
                // This will probably always happen now when running from SD
                // card (or a Samsung phone),
                // so don't generate error spew.
                // Log.i("NetHackDbg",
                // "android.os.FileUtils.setPermissions() returned " + a +
                // " for '" + filename + "', probably didn't work.");
            }
        } catch (ClassNotFoundException e) {
            Log.i("NetHackDbg", "android.os.FileUtils.setPermissions() failed - ClassNotFoundException.");
        } catch (IllegalAccessException e) {
            Log.i("NetHackDbg", "android.os.FileUtils.setPermissions() failed - IllegalAccessException.");
        } catch (InvocationTargetException e) {
            Log.i("NetHackDbg", "android.os.FileUtils.setPermissions() failed - InvocationTargetException.");
        } catch (NoSuchMethodException e) {
            Log.i("NetHackDbg", "android.os.FileUtils.setPermissions() failed - NoSuchMethodException.");
        }
    }

    private void startGameActivity() {
        if (Preferences.getSkipSplash()) {
            runOnUiThread(new StartGameRunnable());
            return;
        }
        // Brief pause on splash, then start game
        Timer timer = new Timer();
        TimerTask gameStartTask = new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new StartGameRunnable());
            }
        };
        timer.schedule(gameStartTask, 800);
    }

    private final class StartGameRunnable implements Runnable {
        @Override
        public void run() {
            Intent intent = new Intent(SplashActivity.this, GameActivity.class);
            intent.putExtra(EXTRA_ASSETS_FRESHLY_INSTALLED, assetsFreshlyInstalled);
            startActivity(intent);
            finish();
        }
    }

    private class InstallProgramTask extends AsyncTask<Void, Integer, Void> {
        // Number of files that need creating. Hard-coded I know, but
        // counting them dynamically took a surprising amount of time
        private static final int TOTAL_FILES = 800;

        private int installedFiles = 0;

        @Override
        protected void onPreExecute() {
            showDialog(INSTALL_DIALOG_ID);
        }

        @Override
        protected Void doInBackground(Void... params) {
            if (installDialog != null) {
                installDialog.setIndeterminate(false);
                installDialog.setMax(TOTAL_FILES);
            }
            publishProgress(installedFiles);
            // These should simply return false if they already exist, so it
            // won't overwrite anything
            mkdir("/saves");
            publishProgress(++installedFiles);
            mkdir("/saves/db");
            publishProgress(++installedFiles);
            mkdir("/saves/des");
            publishProgress(++installedFiles);
            mkdir("/saves/sprint");
            publishProgress(++installedFiles);
            mkdir("/saves/zotdef");
            publishProgress(++installedFiles);
            mkdir("/saves/bones");
            publishProgress(++installedFiles);

            // settings/ and morgue/ live in user-visible external storage
            // (Paths.getSettingsDir / getMorgueDir). Migrate from any old
            // internal copy on first run after this change, then ensure the
            // external dirs exist.
            migrateUserVisibleDir("settings");
            migrateUserVisibleDir("morgue");
            Paths.getMorgueDir(SplashActivity.this);
            publishProgress(++installedFiles);

            delete(new File(getFilesDir() + "/dat"));
            copyFileOrDir("dat");
            // Stamp the content hash that gates the next launch's re-extract.
            // Done immediately after copyFileOrDir("dat") so installedHash and
            // the actual on-disk content stay in sync on a clean install.
            copyFileOrDir("dat-hash.txt");
            // Create settings folder if needed, but always refresh init.txt
            // (other files like macro.txt are preserved)
            File settingsFolder = Paths.getSettingsDir(SplashActivity.this);
            if (settingsFolder.list() == null
                    || settingsFolder.list().length == 0) {
                copyAssetDirTo("settings", settingsFolder);
            } else {
                copyAssetFileTo("settings/init.txt",
                        new File(settingsFolder, "init.txt"));
                // Seed macro.txt only when missing — never overwrite user macros.
                File macroFile = new File(settingsFolder, "macro.txt");
                if (!macroFile.exists()) {
                    copyAssetFileTo("settings/macro.txt", macroFile);
                }
            }
            copyFileOrDir("docs");
            writeVersionFile();
            publishProgress(++installedFiles);
            Log.d(TAG, "Total number of files copied: " + installedFiles);
            return null;
        }

        private void delete(File path) {
            if (!path.exists()) {
                return;
            }
            File[] list = path.listFiles();
            if (list == null) //File, not directory
            {
                path.delete();
                return;
            }

            for (int i = 0; i < list.length; i++) {
                delete(list[i]);
            }

        }

        private void mkdir(String relativePath) {
            // FIXME: making files using this method, because we can't seem to
            // make files using the native code for some reason
            File dir = new File(getFilesDir() + relativePath);
            dir.mkdirs();
            chmod(dir.getAbsolutePath(), 0777);
        }

        private void copyFileOrDir(String path) {
            AssetManager assetManager = getAssets();
            String assets[] = null;
            try {
                assets = assetManager.list(path);
                publishProgress(++installedFiles, TOTAL_FILES);
                if (assets.length == 0) // then we know it's a file, not a
                // directory
                {
                    copyFile(path);
                } else {
                    String fullPath = getFilesDir().toString() + "/" + path;

                    File dir = new File(fullPath);
                    dir.mkdir();
                    chmod(fullPath, 0777);
                    for (int i = 0; i < assets.length; i++) {
                        copyFileOrDir(path + "/" + assets[i]);
                    }
                }
            } catch (IOException ex) {
                Log.e(TAG, "IOException: " + ex);
            }
        }


        // Move <name>/ from internal getFilesDir() to its external location
        // if the external dir is empty/missing. One-shot upgrade path for
        // users installed before settings/morgue moved to external storage.
        private void migrateUserVisibleDir(String name) {
            File oldDir = new File(getFilesDir(), name);
            if (!oldDir.exists() || !oldDir.isDirectory())
                return;
            File newDir = Paths.getUserVisibleDir(SplashActivity.this, name);
            String[] existing = newDir.list();
            if (existing != null && existing.length > 0)
                return;
            File[] items = oldDir.listFiles();
            if (items != null) {
                for (File f : items) {
                    File dest = new File(newDir, f.getName());
                    if (f.isDirectory())
                        copyTree(f, dest);
                    else
                        copyFileTo(f, dest);
                }
            }
            delete(oldDir);
        }

        private void copyTree(File src, File dest) {
            if (src.isDirectory()) {
                dest.mkdirs();
                File[] items = src.listFiles();
                if (items != null) {
                    for (File f : items)
                        copyTree(f, new File(dest, f.getName()));
                }
            } else {
                copyFileTo(src, dest);
            }
        }

        private void copyFileTo(File src, File dest) {
            try {
                BufferedInputStream in = new BufferedInputStream(
                        new FileInputStream(src));
                BufferedOutputStream out = new BufferedOutputStream(
                        new FileOutputStream(dest, false));
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1)
                    out.write(buf, 0, len);
                out.flush();
                out.close();
                in.close();
            } catch (IOException ex) {
                Log.e(TAG, "Migration copy failed for " + src + ": " + ex);
            }
        }

        // Copy an asset directory tree to an arbitrary destination File
        // (the legacy copyFileOrDir hardcodes getFilesDir()).
        private void copyAssetDirTo(String assetPath, File destDir) {
            AssetManager assetManager = getAssets();
            try {
                String[] entries = assetManager.list(assetPath);
                publishProgress(++installedFiles, TOTAL_FILES);
                if (entries.length == 0) {
                    copyAssetFileTo(assetPath, destDir);
                    return;
                }
                destDir.mkdirs();
                chmod(destDir.getAbsolutePath(), 0777);
                for (String entry : entries) {
                    copyAssetDirTo(assetPath + "/" + entry,
                            new File(destDir, entry));
                }
            } catch (IOException ex) {
                Log.e(TAG, "IOException: " + ex);
            }
        }

        private void copyAssetFileTo(String assetPath, File dest) {
            AssetManager assetManager = getAssets();
            try {
                dest.createNewFile();
                BufferedOutputStream out = new BufferedOutputStream(
                        new FileOutputStream(dest, false));
                BufferedInputStream in = new BufferedInputStream(
                        assetManager.open(assetPath));
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1)
                    out.write(buf, 0, len);
                out.flush();
                out.close();
                in.close();
            } catch (IOException ex) {
                Log.e(TAG, "Exception copying asset " + assetPath + ": " + ex);
            }
            chmod(dest.getAbsolutePath(), 0666);
        }

        private void copyFile(String fileName) {
            AssetManager assetManager = getAssets();
            String destname = getFilesDir().toString() + "/" + fileName;
            Log.d(TAG, "Copying: " + fileName + " to " + destname);
            File newasset = new File(destname);
            try {
                newasset.createNewFile();
                BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(newasset, false));
                BufferedInputStream in = new BufferedInputStream(assetManager.open(fileName));
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
                out.flush();
                out.close();
                in.close();
            } catch (IOException ex) {
                Log.e(TAG, "Exception occured copying " + fileName + ": " + ex);
            }
            chmod(destname, 0666);
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (installDialog == null) {
                return;
            }
            installDialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(Void result) {
            removeDialog(INSTALL_DIALOG_ID);
            startGameActivity();
        }
    }

    private void writeVersionFile() {
        FileOutputStream fileOutputStream;
        try {
            fileOutputStream = new FileOutputStream(getFilesDir() + "/version.txt", false);
            BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
            bufferedOutputStream.write(String.valueOf(getApplicationVersionCode()).getBytes());
            bufferedOutputStream.flush();
            bufferedOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}