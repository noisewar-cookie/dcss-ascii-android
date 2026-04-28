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

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import com.crawlmb.EditConfigFilePreference;
import com.crawlmb.Paths;
import com.crawlmb.Preferences;
import com.crawlmb.R;
import com.crawlmb.keymap.KeyMapPreference;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class PreferencesActivity extends PreferenceActivity implements
        OnSharedPreferenceChangeListener {

    // Copies a directory tree between an internal File location and a
    // user-chosen Storage Access Framework tree (Uri). Used for save/morgue
    // backup and restore. The user-chosen tree is the *parent* — backup
    // creates a "saves" or "morgue" subdir under it; restore reads the same
    // subdir name back out. This mirrors the previous EditText UX where
    // users typed a parent path.
    // Enums live at the enclosing-class level because Java < 16 disallows
    // static (and thus implicitly-static) declarations inside non-static
    // inner classes. SafCopyTask is non-static so it can call showDialog /
    // removeDialog / setResult / finish on the activity directly.
    private enum CopyDirection { BACKUP, RESTORE }
    private enum CopyResult { SUCCESS, ERROR_IO, ERROR_NOT_FOUND }

    private final class SafCopyTask extends AsyncTask<Void, Void, CopyResult> {
        private static final String TAG = "SafCopyTask";

        private final CopyDirection direction;
        private final Uri treeUri;
        private final File localDir;
        private final String childName;
        private final boolean reloadCrawlOnSuccess;

        SafCopyTask(CopyDirection direction, Uri treeUri, File localDir,
                String childName, boolean reloadCrawlOnSuccess) {
            this.direction = direction;
            this.treeUri = treeUri;
            this.localDir = localDir;
            this.childName = childName;
            this.reloadCrawlOnSuccess = reloadCrawlOnSuccess;
        }

        @Override
        protected void onPreExecute() {
            showDialog(DIALOG_COPY_FILES_PROGRESS);
        }

        @Override
        protected CopyResult doInBackground(Void... v) {
            DocumentFile tree = DocumentFile.fromTreeUri(
                    PreferencesActivity.this, treeUri);
            if (tree == null)
                return CopyResult.ERROR_IO;
            try {
                if (direction == CopyDirection.BACKUP) {
                    DocumentFile existing = tree.findFile(childName);
                    if (existing != null && !existing.delete())
                        return CopyResult.ERROR_IO;
                    DocumentFile destChild = tree.createDirectory(childName);
                    if (destChild == null)
                        return CopyResult.ERROR_IO;
                    copyFileTreeToSaf(localDir, destChild);
                } else {
                    DocumentFile srcChild = tree.findFile(childName);
                    if (srcChild == null || !srcChild.isDirectory())
                        return CopyResult.ERROR_NOT_FOUND;
                    deleteRecursive(localDir);
                    if (!localDir.mkdirs() && !localDir.isDirectory())
                        return CopyResult.ERROR_IO;
                    copySafTreeToFile(srcChild, localDir);
                }
                return CopyResult.SUCCESS;
            } catch (IOException ex) {
                Log.e(TAG, "Copy failed: " + ex);
                return CopyResult.ERROR_IO;
            }
        }

        private void copyFileTreeToSaf(File src, DocumentFile destDir)
                throws IOException {
            File[] kids = src.listFiles();
            if (kids == null)
                return;
            for (File kid : kids) {
                if (kid.isDirectory()) {
                    DocumentFile sub = destDir.createDirectory(kid.getName());
                    if (sub == null)
                        throw new IOException(
                                "createDirectory failed: " + kid.getName());
                    copyFileTreeToSaf(kid, sub);
                } else {
                    DocumentFile out = destDir.createFile(
                            "application/octet-stream", kid.getName());
                    if (out == null)
                        throw new IOException(
                                "createFile failed: " + kid.getName());
                    InputStream in = null;
                    OutputStream os = null;
                    try {
                        in = new FileInputStream(kid);
                        os = getContentResolver().openOutputStream(out.getUri());
                        if (os == null)
                            throw new IOException("openOutputStream null");
                        streamCopy(in, os);
                    } finally {
                        closeQuietly(in);
                        closeQuietly(os);
                    }
                }
            }
        }

        private void copySafTreeToFile(DocumentFile srcDir, File destDir)
                throws IOException {
            for (DocumentFile kid : srcDir.listFiles()) {
                String name = kid.getName();
                if (name == null)
                    continue;
                File destChild = new File(destDir, name);
                if (kid.isDirectory()) {
                    if (!destChild.mkdirs() && !destChild.isDirectory())
                        throw new IOException("mkdir failed: " + name);
                    copySafTreeToFile(kid, destChild);
                } else {
                    InputStream in = null;
                    OutputStream os = null;
                    try {
                        in = getContentResolver().openInputStream(kid.getUri());
                        if (in == null)
                            throw new IOException("openInputStream null");
                        os = new FileOutputStream(destChild);
                        streamCopy(in, os);
                    } finally {
                        closeQuietly(in);
                        closeQuietly(os);
                    }
                }
            }
        }

        private void streamCopy(InputStream in, OutputStream out)
                throws IOException {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0)
                out.write(buf, 0, len);
        }

        private void closeQuietly(java.io.Closeable c) {
            if (c == null)
                return;
            try { c.close(); } catch (IOException ignored) {}
        }

        private void deleteRecursive(File f) {
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null)
                    for (File k : kids)
                        deleteRecursive(k);
            }
            f.delete();
        }

        @Override
        protected void onPostExecute(CopyResult r) {
            removeDialog(DIALOG_COPY_FILES_PROGRESS);
            int toastRes;
            switch (r) {
                case SUCCESS:
                    toastRes = R.string.files_copied_successfully;
                    break;
                case ERROR_NOT_FOUND:
                    toastRes = R.string.backup_not_found_in_folder;
                    break;
                default:
                    toastRes = R.string.error_copying_files;
            }
            Toast.makeText(PreferencesActivity.this, toastRes,
                    Toast.LENGTH_LONG).show();
            if (r == CopyResult.SUCCESS && reloadCrawlOnSuccess) {
                Intent resultIntent = new Intent();
                resultIntent.putExtra("reloadCrawl", true);
                setResult(RESULT_OK, resultIntent);
                finish();
            }
        }
    }

    private static final int DIALOG_COPY_FILES_PROGRESS = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getPreferenceManager().setSharedPreferencesName(Preferences.NAME);

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.preferences);

        setHelpIntent();

        setConfigFilePreferences();

        setCharacterFilesIntent();

        setCustomizeKeyboardIntent();

        addBackupRestorePreferences();
    }

    // Tap a backup/restore preference -> launch the system folder picker
    // (ACTION_OPEN_DOCUMENT_TREE). The chosen Uri comes back via
    // onActivityResult, identified by request code, and is handed to a
    // SafCopyTask. No storage permissions needed; the picker grant scopes
    // access to that one folder for this session.
    private static final int REQ_BACKUP_SAVES = 1001;
    private static final int REQ_RESTORE_SAVES = 1002;
    private static final int REQ_BACKUP_MORGUE = 1003;
    private static final int REQ_RESTORE_MORGUE = 1004;

    private void addBackupRestorePreferences() {
        addPickerPreference("morgue", R.string.backup_morgue_directory,
                REQ_BACKUP_MORGUE);
        addPickerPreference("morgue", R.string.restore_morgue_directory,
                REQ_RESTORE_MORGUE);
        addPickerPreference("saveFiles", R.string.backup_save_directory,
                REQ_BACKUP_SAVES);
        addPickerPreference("saveFiles", R.string.restore_save_directory,
                REQ_RESTORE_SAVES);
    }

    private void addPickerPreference(String categoryKey, int titleRes,
            final int requestCode) {
        Preference p = new Preference(this);
        p.setTitle(titleRes);
        p.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference pref) {
                launchFolderPicker(requestCode);
                return true;
            }
        });
        ((PreferenceCategory) findPreference(categoryKey)).addPreference(p);
    }

    private void launchFolderPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
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
        File savesDir = new File(getFilesDir(), "saves");
        File morgueDir = Paths.getMorgueDir(this);
        switch (requestCode) {
            case REQ_BACKUP_SAVES:
                new SafCopyTask(CopyDirection.BACKUP, uri,
                        savesDir, "saves", false).execute();
                break;
            case REQ_RESTORE_SAVES:
                // reload Crawl after restore so the running game picks up
                // the new save state.
                new SafCopyTask(CopyDirection.RESTORE, uri,
                        savesDir, "saves", true).execute();
                break;
            case REQ_BACKUP_MORGUE:
                new SafCopyTask(CopyDirection.BACKUP, uri,
                        morgueDir, "morgue", false).execute();
                break;
            case REQ_RESTORE_MORGUE:
                new SafCopyTask(CopyDirection.RESTORE, uri,
                        morgueDir, "morgue", false).execute();
                break;
        }
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

        if (pref.getBoolean(Preferences.KEY_FULLSCREEN, true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
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
        if (key.compareTo(Preferences.KEY_ACTIVEPROFILE) == 0
                || key.compareTo(Preferences.KEY_PROFILES) == 0) {
            setSummaryAll(getPreferenceScreen());
        } else {
            Preference pref = findPreference(key);
            setSummaryPref(pref);
        }
    }

}
