package com.crawlmb.activity;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.crawlmb.R;
import com.crawlmb.WindowCompatAdapter;
import com.crawlmb.recording.GameIdentity;
import com.crawlmb.recording.RecordingExporter;
import com.crawlmb.recording.RecordingImporter;
import com.crawlmb.recording.Recordings;
import com.crawlmb.recording.SqliteRecordingStore;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Recordings library: list, watch, share (export copy via
 * FileProvider), import, delete. Opened from Preferences > Replays. Watch
 * returns the path to the caller (GameActivity plays it in place).
 */
public class RecordingsActivity extends ListActivity
{
	private static final int REQ_IMPORT = 1;
	// kill_method_type KILLED_BY_WINNING (kill-method-type.h).
	private static final int KILLED_BY_WINNING = 11;

	private final List<File> files = new ArrayList<>();
	private final List<Boolean> won = new ArrayList<>();
	private final List<String> names = new ArrayList<>();
	private final Handler main = new Handler(Looper.getMainLooper());

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		WindowCompatAdapter.applyEdgeToEdge(this);
		setContentView(R.layout.activity_recordings);
		WindowCompatAdapter.padRootForSystemBars(findViewById(R.id.recordings_root));
		findViewById(R.id.btn_import_replay).setOnClickListener(v -> pickImport());
		getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener()
		{
			@Override
			public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id)
			{
				if (position >= files.size())
					return false;
				actions(files.get(position));
				return true;
			}
		});
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		reload();
	}

	private void reload()
	{
		files.clear();
		files.addAll(Recordings.list(this));
		won.clear();
		names.clear();
		for (File f : files)
		{
			won.add(SqliteRecordingStore.deathType(f) == KILLED_BY_WINNING);
			names.add(displayName(f));
		}
		setListAdapter(new RecordingAdapter());
	}

	@Override
	protected void onListItemClick(ListView l, View v, int position, long id)
	{
		if (position < files.size())
			watch(files.get(position));
	}

	private final class RecordingAdapter extends BaseAdapter
	{
		@Override public int getCount() { return files.size(); }
		@Override public Object getItem(int position) { return files.get(position); }
		@Override public long getItemId(int position) { return position; }

		@Override
		public View getView(int position, View convertView, ViewGroup parent)
		{
			View row = convertView != null ? convertView
					: getLayoutInflater().inflate(R.layout.item_recording, parent, false);
			File f = files.get(position);
			((TextView) row.findViewById(R.id.rec_name)).setText(names.get(position)
					+ (f.getParentFile().equals(Recordings.importsDir(RecordingsActivity.this))
							? " [imported]" : ""));
			((TextView) row.findViewById(R.id.rec_size)).setText(size(f));
			((TextView) row.findViewById(R.id.rec_saved)).setText(won.get(position)
					? getString(R.string.recordings_win) : dateTime(lastSaved(f)));
			return row;
		}
	}

	// Character name + creation time; the filename's seed makes it too long.
	private static String displayName(File f)
	{
		GameIdentity id = SqliteRecordingStore.identity(f);
		if (id == null || id.name.isEmpty() || id.birth <= 0)
			return f.getName().replace(".dcrec", "");
		return id.name + " " + dateTime(id.birth * 1000);
	}

	private static String dateTime(long ms)
	{
		return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
	}

	private static String size(File f)
	{
		long b = f.length() + new File(f.getPath() + "-wal").length();
		return b < 1024 * 1024 ? Math.max(1, b / 1024) + " KB"
				: String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
	}

	// Writes land in the -wal first; the main file's time lags until checkpoint.
	private static long lastSaved(File f)
	{
		return Math.max(f.lastModified(), new File(f.getPath() + "-wal").lastModified());
	}

	private void watch(File f)
	{
		Intent i = new Intent();
		i.putExtra(GameActivity.EXTRA_REPLAY_PATH, f.getPath());
		setResult(RESULT_OK, i);
		finish();
	}

	private void actions(final File f)
	{
		new AlertDialog.Builder(this)
				.setTitle(f.getName())
				.setItems(new CharSequence[] { "Watch", "Share", "Delete" }, (d, which) ->
				{
					if (which == 0)
						watch(f);
					else if (which == 1)
						share(f);
					else
						confirmDelete(f);
				})
				.show();
	}

	private void share(final File f)
	{
		Toast.makeText(this, "Preparing replay\u2026", Toast.LENGTH_SHORT).show();
		new Thread(() ->
		{
			try
			{
				Recordings.flush();
				Recordings.drain(3000);
				File out = RecordingExporter.export(f, Recordings.exportsDir(this),
						new RecordingExporter.Options());
				main.post(() -> sendFile(out));
			}
			catch (Exception e)
			{
				main.post(() -> Toast.makeText(this, "Export failed: " + e.getMessage(),
						Toast.LENGTH_LONG).show());
			}
		}, "dcrec-export").start();
	}

	private void sendFile(File out)
	{
		Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
		Intent send = new Intent(Intent.ACTION_SEND);
		send.setType("application/octet-stream");
		send.putExtra(Intent.EXTRA_STREAM, uri);
		send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		startActivity(Intent.createChooser(send, "Share replay"));
	}

	private void confirmDelete(final File f)
	{
		new AlertDialog.Builder(this)
				.setMessage("Delete " + f.getName() + "?")
				.setPositiveButton("Delete", (d, w) ->
				{
					f.delete();
					new File(f.getPath() + "-wal").delete();
					new File(f.getPath() + "-shm").delete();
					reload();
				})
				.setNegativeButton("Cancel", null)
				.show();
	}

	private void pickImport()
	{
		Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		i.addCategory(Intent.CATEGORY_OPENABLE);
		i.setType("*/*");
		startActivityForResult(i, REQ_IMPORT);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data)
	{
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null)
			return;
		final Uri uri = data.getData();
		new Thread(() ->
		{
			RecordingImporter.Result r = RecordingImporter.importFrom(getContentResolver(), uri,
					Recordings.importsDir(this), RecordingImporter.DEFAULT_MAX_BYTES);
			main.post(() ->
			{
				Toast.makeText(this, r.ok() ? "Replay imported" : importError(r.error),
						Toast.LENGTH_LONG).show();
				reload();
			});
		}, "dcrec-import").start();
	}

	private static String importError(String code)
	{
		if ("newer_schema".equals(code))
			return "This replay needs a newer app version.";
		if ("not_a_recording".equals(code))
			return "That file isn't a DCSS replay.";
		if ("corrupt".equals(code))
			return "That replay file is damaged.";
		return "Couldn't import that file.";
	}
}
