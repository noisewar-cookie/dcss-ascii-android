/*
 * File: PreferencesActivity.java Purpose: Preferences activity for Android
 * application
 * 
 * Copyright (c) 2009 David Barr, Sergey Belinsky
 * 
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of either:
 * 
 * a) the GNU General Public License as published by the Free Software
 * Foundation, version 2, or
 * 
 * b) the "Angband licence": This software may be copied and distributed for
 * educational, research, and not for profit purposes provided that this
 * copyright and statement are included in all such copies. Other copyrights may
 * also apply.
 */

package com.crawlmb.activity;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.widget.Toast;

import com.crawlmb.CustomFolderSync;
import com.crawlmb.EditConfigFilePreference;
import com.crawlmb.Paths;
import com.crawlmb.Preferences;
import com.crawlmb.R;
import com.crawlmb.WindowCompatAdapter;
import com.crawlmb.keymap.KeyMapPreference;

import android.content.ContentResolver;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class PreferencesActivity extends PreferenceActivity implements
        OnSharedPreferenceChangeListener {

    // Backup/restore for saves, morgue, and config files uses a single
    // user-chosen .zip via SAF. Backup writes a zip of a directory tree;
    // restore extracts it back. Enum lives at the enclosing-class level
    // because Java < 16 disallows static declarations inside non-static
    // inner classes; ZipBackupTask is non-static for activity access.
    private enum CopyResult { SUCCESS, ERROR_IO, ERROR_NOT_FOUND }

    private static final int DIALOG_COPY_FILES_PROGRESS = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompatAdapter.applyEdgeToEdge(this);
        getPreferenceManager().setSharedPreferencesName(Preferences.NAME);

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.preferences);

        // Plain content screen with no inset handling of its own — keep the
        // preference list clear of the system bars now that the window is
        // edge-to-edge.
        WindowCompatAdapter.padRootForSystemBars(
                findViewById(android.R.id.content));

        setHelpIntent();

        setConfigFilePreferences();

        setCharacterFilesIntent();

        setCustomizeKeyboardIntent();

        setRepositionUiClickListener();

        setRepositionGridClickListener();

        setRepositionCenterlineClickListener();

        gateUnfoldedPrefs();

        addBackupRestorePreferences();

        addCustomFolderPreferences();

        // Greys out backup/restore/editor/morgue rows whenever custom mode
        // is active. Must run AFTER addBackupRestorePreferences /
        // setConfigFilePreferences / setCharacterFilesIntent so all the
        // targets exist by the time we walk them.
        applyCustomModeGuards();

        // Must run after every add* call above so the walk sees all rows,
        // and before views are bound (still in onCreate).
        applyCompactLayout(getPreferenceScreen());
    }

    // Swap every row (including nested PreferenceScreen rows, but NOT
    // category headers) to the half-size layout. Done in code rather than
    // android:layout in preferences.xml so programmatically added rows are
    // covered too; the platform Preference styles are private, so a theme
    // override can't do it.
    private void applyCompactLayout(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (p instanceof PreferenceCategory)
                p.setLayoutResource(R.layout.preference_category_compact);
            else
                p.setLayoutResource(R.layout.preference_compact);
            // PlainCheckBox widget: keeps the green check, drops the orange
            // pressed/selected highlight.
            if (p instanceof CheckBoxPreference)
                p.setWidgetLayoutResource(
                        R.layout.preference_widget_checkbox);
            // The setters above disable row recycling, so the ListView
            // re-inflates every row on every scroll. Safe to re-enable:
            // layouts are final before the adapter binds (API 26+).
            if (android.os.Build.VERSION.SDK_INT
                    >= android.os.Build.VERSION_CODES.O)
                p.setRecycleEnabled(true);
            if (p instanceof PreferenceGroup)
                applyCompactLayout((PreferenceGroup) p);
        }
    }

    // Tap a backup/restore preference -> launch a single-file SAF picker
    // (ACTION_CREATE_DOCUMENT for backup, ACTION_OPEN_DOCUMENT for restore).
    // The chosen Uri comes back via onActivityResult, identified by request
    // code, and is handed to a ZipBackupTask. No storage permissions needed.
    private static final int REQ_BACKUP_SAVES = 1001;
    private static final int REQ_RESTORE_SAVES = 1002;
    private static final int REQ_BACKUP_MORGUE = 1003;
    private static final int REQ_RESTORE_MORGUE = 1004;
    private static final int REQ_BACKUP_CONFIG = 1005;
    private static final int REQ_RESTORE_CONFIG = 1006;
    private static final int REQ_PICK_CUSTOM_FOLDER = 1007;

    private void addBackupRestorePreferences() {
        addZipPreference("configFiles", R.string.backup_config_files,
                REQ_BACKUP_CONFIG, R.string.config_backup_filename, true);
        addZipPreference("configFiles", R.string.restore_config_files,
                REQ_RESTORE_CONFIG, 0, false);
        addZipPreference("morgue", R.string.backup_morgue_directory,
                REQ_BACKUP_MORGUE, R.string.morgue_backup_filename, true);
        addZipPreference("morgue", R.string.restore_morgue_directory,
                REQ_RESTORE_MORGUE, 0, false);
        addZipPreference("saveFiles", R.string.backup_save_directory,
                REQ_BACKUP_SAVES, R.string.saves_backup_filename, true);
        addZipPreference("saveFiles", R.string.restore_save_directory,
                REQ_RESTORE_SAVES, 0, false);
    }

    // Tints the black source glyph to the title colour. mutate() stops the
    // tint leaking to other users of the shared drawable.
    private android.graphics.drawable.Drawable tintedIcon(int iconRes) {
        android.graphics.drawable.Drawable d =
                androidx.core.content.ContextCompat.getDrawable(this, iconRes);
        if (d == null)
            return null;
        d = d.mutate();
        d.setTintList(androidx.core.content.ContextCompat.getColorStateList(
                this, R.color.preference_title));
        return d;
    }

    private void addZipPreference(String categoryKey, int titleRes,
            final int requestCode, final int suggestedNameRes,
            final boolean isBackup) {
        Preference p = new Preference(this);
        p.setTitle(titleRes);
        p.setIcon(tintedIcon(isBackup
                ? R.drawable.ic_pref_backup : R.drawable.ic_pref_restore));
        p.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference pref) {
                if (isBackup)
                    launchCreateDocument(requestCode, suggestedNameRes);
                else
                    launchOpenDocument(requestCode);
                return true;
            }
        });
        ((PreferenceCategory) findPreference(categoryKey)).addPreference(p);
    }

    private void launchCreateDocument(int requestCode, int suggestedNameRes) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, getString(suggestedNameRes));
        startActivityForResult(intent, requestCode);
    }

    private void launchOpenDocument(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null)
            return;
        Uri uri = data.getData();
        if (uri == null)
            return;
        if (requestCode == REQ_PICK_CUSTOM_FOLDER) {
            onCustomFolderPicked(uri);
            return;
        }
        File savesDir = new File(getFilesDir(), "saves");
        File morgueDir = Paths.getMorgueDir(this);
        File settingsDir = Paths.getSettingsDir(this);
        switch (requestCode) {
            case REQ_BACKUP_SAVES:
                new ZipBackupTask(true, uri, savesDir, null, false).execute();
                break;
            case REQ_RESTORE_SAVES:
                // Force an app restart after a save restore. The running game
                // keeps its save package open and saves the in-memory (pre-
                // restore) state on exit, which would overwrite/tear the files
                // we just wrote. Restarting the process means the next launch
                // loads the restored save cleanly with no intervening save.
                new ZipBackupTask(false, uri, savesDir, null, true).execute();
                break;
            case REQ_BACKUP_MORGUE:
                new ZipBackupTask(true, uri, morgueDir, null, false).execute();
                break;
            case REQ_RESTORE_MORGUE:
                new ZipBackupTask(false, uri, morgueDir, null, false).execute();
                break;
            case REQ_BACKUP_CONFIG:
                new ZipBackupTask(true, uri, settingsDir,
                        configAllowlist(), false).execute();
                break;
            case REQ_RESTORE_CONFIG:
                new ZipBackupTask(false, uri, settingsDir,
                        configAllowlist(), false).execute();
                break;
        }
    }

    private java.util.Set<String> configAllowlist() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String n : getResources().getStringArray(R.array.config_files))
            set.add(n + ".txt");
        return set;
    }

    // Zips a directory tree to a user-chosen .zip, or extracts a .zip back
    // into a directory tree. If allowlist is non-null, both backup and
    // restore skip entries whose name isn't in the set. Used for config
    // backup/restore so the zip contains only the user-facing config files
    // (init.txt, macro.txt, no_vi_command_keys.txt) and tampered zips can't
    // drop foreign files into settings/.
    private final class ZipBackupTask extends AsyncTask<Void, Void, CopyResult> {
        private static final String TAG = "ZipBackupTask";
        private final boolean isBackup;
        private final Uri uri;
        private final File baseDir;
        private final java.util.Set<String> allowlist;
        private final boolean restartAppOnSuccess;

        ZipBackupTask(boolean isBackup, Uri uri, File baseDir,
                java.util.Set<String> allowlist,
                boolean restartAppOnSuccess) {
            this.isBackup = isBackup;
            this.uri = uri;
            this.baseDir = baseDir;
            this.allowlist = allowlist;
            this.restartAppOnSuccess = restartAppOnSuccess;
        }

        @Override
        protected void onPreExecute() {
            showDialog(DIALOG_COPY_FILES_PROGRESS);
        }

        @Override
        protected CopyResult doInBackground(Void... voids) {
            try {
                return isBackup ? zipTree() : unzipTree();
            } catch (IOException ex) {
                Log.e(TAG, (isBackup ? "Backup" : "Restore") + " failed", ex);
                return CopyResult.ERROR_IO;
            }
        }

        private CopyResult zipTree() throws IOException {
            if (!baseDir.exists() || !baseDir.isDirectory())
                return CopyResult.ERROR_NOT_FOUND;
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null)
                return CopyResult.ERROR_IO;
            java.util.zip.ZipOutputStream zip = null;
            int[] written = {0};
            try {
                zip = new java.util.zip.ZipOutputStream(os);
                walkAndZip(baseDir, "", zip, written);
            } finally {
                if (zip != null) zip.close();
                else os.close();
            }
            return written[0] > 0
                    ? CopyResult.SUCCESS : CopyResult.ERROR_NOT_FOUND;
        }

        private void walkAndZip(File dir, String relPath,
                java.util.zip.ZipOutputStream zip, int[] written)
                throws IOException {
            File[] kids = dir.listFiles();
            if (kids == null)
                return;
            byte[] buf = new byte[8192];
            for (File kid : kids) {
                String entryName = relPath.isEmpty()
                        ? kid.getName() : relPath + "/" + kid.getName();
                if (kid.isDirectory()) {
                    walkAndZip(kid, entryName, zip, written);
                } else if (kid.isFile()) {
                    if (allowlist != null && !allowlist.contains(entryName))
                        continue;
                    zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
                    FileInputStream in = new FileInputStream(kid);
                    try {
                        int n;
                        while ((n = in.read(buf)) != -1)
                            zip.write(buf, 0, n);
                    } finally {
                        in.close();
                    }
                    zip.closeEntry();
                    written[0]++;
                }
            }
        }

        private CopyResult unzipTree() throws IOException {
            if (!baseDir.exists())
                baseDir.mkdirs();
            String basePath = baseDir.getCanonicalPath() + File.separator;
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null)
                return CopyResult.ERROR_IO;
            int read = 0;
            java.util.zip.ZipInputStream zip = null;
            try {
                zip = new java.util.zip.ZipInputStream(is);
                byte[] buf = new byte[8192];
                java.util.zip.ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    String entryName = e.getName();
                    if (e.isDirectory())
                        continue;
                    if (allowlist != null
                            && !allowlist.contains(entryName))
                        continue;
                    File dest = new File(baseDir, entryName);
                    // Zip-slip guard: reject entries that escape baseDir.
                    if (!dest.getCanonicalPath().startsWith(basePath))
                        continue;
                    File parent = dest.getParentFile();
                    if (parent != null && !parent.exists())
                        parent.mkdirs();
                    FileOutputStream out = new FileOutputStream(dest, false);
                    try {
                        int n;
                        while ((n = zip.read(buf)) != -1)
                            out.write(buf, 0, n);
                    } finally {
                        out.close();
                    }
                    zip.closeEntry();
                    read++;
                }
            } finally {
                if (zip != null) zip.close();
                else is.close();
            }
            return read > 0 ? CopyResult.SUCCESS : CopyResult.ERROR_NOT_FOUND;
        }

        @Override
        protected void onPostExecute(CopyResult r) {
            removeDialog(DIALOG_COPY_FILES_PROGRESS);
            int toastRes;
            switch (r) {
                case SUCCESS:
                    toastRes = isBackup
                            ? R.string.files_copied_successfully
                            : R.string.files_copied_successfully;
                    break;
                case ERROR_NOT_FOUND:
                    toastRes = isBackup
                            ? R.string.error_nothing_to_backup
                            : R.string.error_backup_empty_or_invalid;
                    break;
                default:
                    toastRes = R.string.error_copying_files;
            }
            if (r == CopyResult.SUCCESS && restartAppOnSuccess) {
                showRestartRequiredDialog();
                return;
            }
            Toast.makeText(PreferencesActivity.this, toastRes,
                    Toast.LENGTH_LONG).show();
        }
    }

    // After a successful save restore the process must be killed before the
    // still-running game can save its stale in-memory state over the restored
    // files. A hard kill (no finish()/onPause) guarantees no further save runs;
    // the next cold launch loads the restored save intact.
    private void showRestartRequiredDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.save_restored_restart_title)
                .setMessage(R.string.save_restored_restart_message)
                .setCancelable(false)
                .setPositiveButton(R.string.close_app, (d, w) -> {
                        // Persist (synchronously) so the next launch can show
                        // the "Reloading..." overlay; the kill below would
                        // otherwise lose an async write.
                        Preferences.setReloadInProgressSync(true);
                        android.os.Process.killProcess(
                                android.os.Process.myPid());
                })
                .show();
    }

    @Override
    public Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_COPY_FILES_PROGRESS:
                ProgressDialog filesCopyingDialog = new ProgressDialog(this);
                filesCopyingDialog.setMessage(getString(R.string.copying_files));
                return filesCopyingDialog;
            default:
                break;
        }
        return null;
    }

    // --- Custom data folder (SAF, API 30+) ---------------------------------

    private Preference customFolderPickPref;
    private Preference customFolderDisablePref;

    private void addCustomFolderPreferences() {
        PreferenceCategory cat = (PreferenceCategory) findPreference("customFolder");
        if (cat == null)
            return;
        if (!Paths.isCustomFolderSupported()) {
            // Pre-API-30: show an explanatory note instead of the picker.
            // The category title still makes the section discoverable, but
            // the row is purely informational.
            Preference note = new Preference(this);
            note.setTitle(R.string.custom_folder_min_sdk_note);
            note.setSelectable(false);
            cat.addPreference(note);
            return;
        }

        customFolderPickPref = new Preference(this);
        customFolderPickPref.setTitle(R.string.custom_folder_pick);
        customFolderPickPref.setOnPreferenceClickListener(p -> {
            launchCustomFolderPicker();
            return true;
        });
        cat.addPreference(customFolderPickPref);

        customFolderDisablePref = new Preference(this);
        customFolderDisablePref.setTitle(R.string.custom_folder_disable);
        customFolderDisablePref.setSummary(R.string.custom_folder_disable_summary);
        customFolderDisablePref.setOnPreferenceClickListener(p -> {
            disableCustomFolder();
            return true;
        });
        cat.addPreference(customFolderDisablePref);

        refreshCustomFolderRowState();
    }

    // Update visibility of the picker/disable rows + the picker's summary
    // based on whether a custom folder is currently set. Called from
    // addCustomFolderPreferences and after a successful pick.
    private void refreshCustomFolderRowState() {
        if (customFolderPickPref == null)
            return;
        boolean active = Paths.isCustomMode(this);
        if (active) {
            String uri = Preferences.getCustomFolderUri();
            String shown = prettyFolderName(uri);
            customFolderPickPref.setSummary(
                    getString(R.string.custom_folder_active_summary, shown));
        } else {
            customFolderPickPref.setSummary(R.string.custom_folder_pick_summary);
        }
        if (customFolderDisablePref != null)
            customFolderDisablePref.setEnabled(active);
    }

    // Best-effort display name for a SAF tree URI. Falls back to the raw
    // URI string if the docId can't be parsed.
    private String prettyFolderName(String uriStr) {
        if (uriStr == null || uriStr.isEmpty())
            return "";
        try {
            Uri u = Uri.parse(uriStr);
            String docId = DocumentsContract.getTreeDocumentId(u);
            int colon = docId.indexOf(':');
            return colon >= 0 ? docId.substring(colon + 1) : docId;
        } catch (Exception e) {
            return uriStr;
        }
    }

    private void launchCustomFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_CUSTOM_FOLDER);
        } catch (Exception e) {
            Toast.makeText(this, R.string.custom_folder_pick_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    // Custom folder = a separate, switchable game instance. The local
    // staging tree is the working copy; the SAF folder is the sync bridge.
    // Staging persists across enable/disable, so re-enabling the same
    // folder resumes its saves with no transfer. A different folder
    // wipes staging so the two folders' data don't merge.
    //
    // Permission is taken BEFORE persisting the URI so a failed take
    // doesn't leave a stale pref pointing at a folder we can't read.
    private void onCustomFolderPicked(Uri uri) {
        ContentResolver cr = getContentResolver();
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            cr.takePersistableUriPermission(uri, flags);
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.custom_folder_pick_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
        String picked = uri.toString();
        if (!picked.equals(Preferences.getStagingOriginUri())) {
            // Switching to a different folder than staging was set up for.
            // Wipe so the new folder's pull starts from a clean slate
            // and doesn't merge with the previous folder's saves.
            CustomFolderSync.clearStaging(this);
            Preferences.setStagingOriginUri(picked);
        }
        Preferences.setCustomFolderUri(picked);
        killAndRelaunch(this);
    }

    // Disable is a pure UI toggle: clear the URI, drop the SAF claim,
    // restart. Staging is preserved so re-enabling the same folder
    // resumes its saves. Anything not yet pushed to SAF stays in staging.
    private void disableCustomFolder() {
        CustomFolderSync.releasePermission(this);
        Preferences.setCustomFolderUri("");
        killAndRelaunch(this);
    }

    // Hard kill + fresh launch. DCSS holds file handles into saves/ and
    // has the rc file parsed in memory; paths are wired once at startup
    // and can't switch live, so a clean restart is the only safe option.
    static void killAndRelaunch(android.app.Activity ctx) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(
                ctx.getPackageName());
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            ctx.startActivity(i);
        }
        ctx.finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // Wrap relevant prefs so taps surface the "not available in custom
    // mode" dialog instead of running their normal action. Applies whether
    // the pref's action is an intent (setIntent) or a click listener:
    // setting a click listener that returns true preempts both.
    private void applyCustomModeGuards() {
        if (!Paths.isCustomMode(this))
            return;
        String[] guardKeys = {
                "character_files",      // CharacterFilesActivity intent
        };
        for (String key : guardKeys) {
            Preference p = findPreference(key);
            if (p != null)
                guardPref(p);
        }

        // Backup/restore prefs and the config-file editor prefs were added
        // programmatically by addBackupRestorePreferences /
        // setConfigFilePreferences. Walk their categories and wrap every
        // child.
        guardCategoryChildren("configFiles");
        guardCategoryChildren("morgue");
        guardCategoryChildren("saveFiles");
    }

    private void guardCategoryChildren(String categoryKey) {
        PreferenceCategory cat = (PreferenceCategory) findPreference(categoryKey);
        if (cat == null)
            return;
        for (int i = 0; i < cat.getPreferenceCount(); i++)
            guardPref(cat.getPreference(i));
    }

    // setEnabled(false) greys out title and summary and blocks both
    // clicks and any attached intent, so we don't need to null the
    // intent or attach a click handler.
    private void guardPref(Preference p) {
        p.setEnabled(false);
        p.setSummary(R.string.unavailable_in_custom_mode_title);
    }

    // The unfolded category is only meaningful on foldables. Remove it
    // wholesale on everything else so the option never appears on phones or
    // tablets that can't split at a hinge.
    private void gateUnfoldedPrefs() {
        Preference cat = findPreference("unfolded");
        if (cat == null)
            return;
        if (!Preferences.getFoldableSeen())
            getPreferenceScreen().removePreference(cat);
    }

    private void setHelpIntent() {
        Preference helpPreference = findPreference("help");
        Intent helpIntent = new Intent(this, HelpActivity.class);
        helpPreference.setIntent(helpIntent);
    }

    private void setCharacterFilesIntent() {
        Preference characterFilesPreference = findPreference("character_files");
        Intent characterFilesIntent = new Intent(this,
                CharacterFilesActivity.class);
        characterFilesPreference.setIntent(characterFilesIntent);
    }

    private void setCustomizeKeyboardIntent() {
        Preference characterFilesPreference = findPreference("custom_keyboard");
        Intent characterFilesIntent = new Intent(this,
                CustomKeyboardActivity.class);
        characterFilesPreference.setIntent(characterFilesIntent);
    }

    // Returns to GameActivity, which enters the panel-repositioning mode on
    // seeing the extra (see GameActivity.onActivityResult). Disabled outside
    // a loaded game (extra set from crawl_state.need_save) — the forced
    // split layout would section the DCSS main menu.
    private void setRepositionUiClickListener() {
        Preference repositionPreference = findPreference("reposition_ui");
        if (!getIntent().getBooleanExtra("gameInProgress", false)) {
            repositionPreference.setEnabled(false);
            repositionPreference.setSummary(
                    R.string.preferences_reposition_ui_disabled_summary);
            return;
        }
        repositionPreference.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference pref) {
                        Intent result = new Intent();
                        result.putExtra("repositionUi", true);
                        setResult(RESULT_OK, result);
                        finish();
                        return true;
                    }
                });
    }

    // Returns to GameActivity, which enters the grid overlay editor on
    // seeing the extra (see GameActivity.onActivityResult). Always enabled —
    // the editor is a blank screen that doesn't touch the game panels.
    private void setRepositionGridClickListener() {
        findPreference("reposition_grid").setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference pref) {
                        Intent result = new Intent();
                        result.putExtra("repositionGrid", true);
                        setResult(RESULT_OK, result);
                        finish();
                        return true;
                    }
                });
    }

    private void setRepositionCenterlineClickListener() {
        findPreference("reposition_centerline").setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference pref) {
                        Intent result = new Intent();
                        result.putExtra("repositionCenterline", true);
                        setResult(RESULT_OK, result);
                        finish();
                        return true;
                    }
                });
    }

    private void setConfigFilePreferences() {
        PreferenceCategory configFilePreferences = (PreferenceCategory) findPreference("configFiles");
        String[] configFiles = getResources().getStringArray(
                R.array.config_files);
        for (int i = 0; i < configFiles.length; i++) {
            EditConfigFilePreference editConfigFilePreference = new EditConfigFilePreference(
                    this, configFiles[i]);
            configFilePreferences.addPreference(editConfigFilePreference);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        setSummaryAll(getPreferenceScreen());
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);

        SharedPreferences pref = getSharedPreferences(Preferences.NAME,
                MODE_PRIVATE);

        WindowCompatAdapter.applyFullscreen(this,
                pref.getBoolean(Preferences.KEY_FULLSCREEN, true));
    }

    @Override
    protected void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    private void setSummaryAll(PreferenceScreen pScreen) {
        for (int i = 0; i < pScreen.getPreferenceCount(); i++) {
            Preference pref = pScreen.getPreference(i);
            setSummaryPref(pref);
        }
    }

    public void setSummaryPref(Preference pref) {
        if (pref == null)
            return;

        String key = pref.getKey();
        if (key == null)
            key = "";

        if (pref instanceof KeyMapPreference) {
            KeyMapPreference kbPref = (KeyMapPreference) pref;
            String desc = kbPref.getDescription();
            pref.setSummary(desc);
        } else if (pref instanceof PreferenceCategory) {
            PreferenceCategory prefCat = (PreferenceCategory) pref;
            int count = prefCat.getPreferenceCount();
            for (int i = 0; i < count; i++) {
                setSummaryPref(prefCat.getPreference(i));
            }
        } else if (pref instanceof PreferenceScreen) {
            setSummaryAll((PreferenceScreen) pref);
        }
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key) {
        if (key.compareTo(Preferences.KEY_WORDWRAP) == 0) {
            // Word wrap changes DCSS options and the terminal layout, both
            // wired once at game boot — same reason custom-folder changes
            // hard-restart. The reload flag shows the "Reloading..." overlay
            // across the relaunch. The game was already saved by
            // GameActivity.onPause when this screen opened.
            Preferences.setReloadInProgressSync(true);
            killAndRelaunch(this);
            return;
        }
        if (key.compareTo(Preferences.KEY_ACTIVEPROFILE) == 0
                || key.compareTo(Preferences.KEY_PROFILES) == 0) {
            setSummaryAll(getPreferenceScreen());
        } else {
            Preference pref = findPreference(key);
            setSummaryPref(pref);
        }
    }

}
