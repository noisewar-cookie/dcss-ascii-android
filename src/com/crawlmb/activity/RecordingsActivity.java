package com.crawlmb.activity;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import com.crawlmb.CustomFontManager;
import com.crawlmb.Preferences;
import com.crawlmb.R;
import com.crawlmb.WindowCompatAdapter;
import com.crawlmb.recording.GameIdentity;
import com.crawlmb.recording.RecordingExporter;
import com.crawlmb.recording.RecordingImporter;
import com.crawlmb.recording.Recordings;
import com.crawlmb.recording.ReplayVideoEncoder;
import com.crawlmb.recording.SqliteRecordingStore;
import com.crawlmb.view.GameFontShaper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Recordings library: list, watch, export as .dcrec or encoded .mp4 to a
 * picked file/folder (SAF), import, delete; Select mode applies export/delete
 * to several. Opened from Preferences > Replays. Watch returns the path to the
 * caller (GameActivity plays it in place).
 */
public class RecordingsActivity extends ListActivity
{
	private static final int REQ_IMPORT = 1;
	private static final int REQ_EXPORT_ONE = 2;
	private static final int REQ_EXPORT_DIR = 3;
	// kill_method_type KILLED_BY_WINNING (kill-method-type.h).
	private static final int KILLED_BY_WINNING = 11;
	private static final double[] VIDEO_SPEEDS = { 1, 2, 4, 8 };

	private final List<File> files = new ArrayList<>();
	private final List<Boolean> won = new ArrayList<>();
	private final List<String> names = new ArrayList<>();
	private final Handler main = new Handler(Looper.getMainLooper());
	private final Set<File> selected = new HashSet<>();
	private boolean selecting;
	// Awaiting the SAF picker: originals for a folder export, finished files
	// (encoded videos) for a folder export, or one finished file.
	private List<File> pendingExport = Collections.emptyList();
	private List<File> pendingReady = Collections.emptyList();
	private File exportedOne;
	private AtomicBoolean encodeCancel;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		WindowCompatAdapter.applyEdgeToEdge(this);
		setContentView(R.layout.activity_recordings);
		WindowCompatAdapter.padRootForSystemBars(findViewById(R.id.recordings_root));
		findViewById(R.id.btn_import_replay).setOnClickListener(v -> pickImport());
		findViewById(R.id.btn_select_replays).setOnClickListener(v -> setSelecting(!selecting));
		findViewById(R.id.btn_export_replays).setOnClickListener(v -> export(selectedFiles()));
		findViewById(R.id.btn_delete_replays).setOnClickListener(v -> confirmDelete(selectedFiles()));
		getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener()
		{
			@Override
			public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id)
			{
				if (position >= files.size())
					return false;
				if (selecting)
					toggle(position);
				else
					actions(files.get(position));
				return true;
			}
		});
	}

	@Override
	protected void onDestroy()
	{
		if (encodeCancel != null)
			encodeCancel.set(true);
		super.onDestroy();
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
		selected.retainAll(files);
		setListAdapter(new RecordingAdapter());
		updateSelectUi();
	}

	@Override
	protected void onListItemClick(ListView l, View v, int position, long id)
	{
		if (position >= files.size())
			return;
		if (selecting)
			toggle(position);
		else
			watch(files.get(position));
	}

	@Override
	public void onBackPressed()
	{
		if (selecting)
			setSelecting(false);
		else
			super.onBackPressed();
	}

	private void setSelecting(boolean on)
	{
		selecting = on;
		selected.clear();
		((BaseAdapter) getListAdapter()).notifyDataSetChanged();
		updateSelectUi();
	}

	private void toggle(int position)
	{
		File f = files.get(position);
		if (!selected.remove(f))
			selected.add(f);
		((BaseAdapter) getListAdapter()).notifyDataSetChanged();
		updateSelectUi();
	}

	private void updateSelectUi()
	{
		findViewById(R.id.recordings_select_bar).setVisibility(selecting ? View.VISIBLE : View.GONE);
		((TextView) findViewById(R.id.recordings_title)).setText(selecting
				? selected.size() + " selected" : getString(R.string.recordings_title));
		((Button) findViewById(R.id.btn_select_replays)).setText(selecting
				? R.string.recordings_done : R.string.recordings_select);
		for (int id : new int[] { R.id.btn_export_replays, R.id.btn_delete_replays })
			findViewById(id).setEnabled(!selected.isEmpty());
	}

	// List order, so multi-exports match the screen.
	private List<File> selectedFiles()
	{
		List<File> out = new ArrayList<>();
		for (File f : files)
			if (selected.contains(f))
				out.add(f);
		return out;
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
			CheckBox check = row.findViewById(R.id.rec_check);
			check.setVisibility(selecting ? View.VISIBLE : View.GONE);
			check.setChecked(selected.contains(f));
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
				.setItems(new CharSequence[] { "Watch", "Export\u2026", "Delete" },
						(d, which) ->
				{
					List<File> one = Collections.singletonList(f);
					if (which == 0)
						watch(f);
					else if (which == 1)
						export(one);
					else
						confirmDelete(one);
				})
				.show();
	}

	private interface CopiesReady
	{
		void run(List<File> copies);
	}

	// Never hand out the live file: WAL sidecars may hold recent writes.
	private void cleanCopies(final List<File> src, final CopiesReady done)
	{
		if (src.isEmpty())
			return;
		Toast.makeText(this, "Preparing replay\u2026", Toast.LENGTH_SHORT).show();
		new Thread(() ->
		{
			try
			{
				List<File> out = copiesNow(src);
				main.post(() -> done.run(out));
			}
			catch (Exception e)
			{
				main.post(() -> Toast.makeText(this, "Export failed: " + e.getMessage(),
						Toast.LENGTH_LONG).show());
			}
		}, "dcrec-export").start();
	}

	// Background thread only.
	private List<File> copiesNow(List<File> src) throws IOException
	{
		Recordings.flush();
		Recordings.drain(3000);
		List<File> out = new ArrayList<>();
		for (File f : src)
			out.add(RecordingExporter.export(f, Recordings.exportsDir(this),
					new RecordingExporter.Options()));
		return out;
	}

	private void export(final List<File> src)
	{
		if (src.isEmpty())
			return;
		new AlertDialog.Builder(this)
				.setTitle("Export as")
				.setItems(new CharSequence[] { "Replay (.dcrec)", "Video (.mp4)" }, (d, which) ->
				{
					if (which == 0)
						exportReplays(src);
					else
						pickVideoSpeed(src);
				})
				.show();
	}

	// One file: save-as picker named after the clean copy. Several: pick a folder.
	private void exportReplays(List<File> src)
	{
		if (src.size() == 1)
			cleanCopies(src, this::saveReady);
		else
		{
			pendingExport = src;
			startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_EXPORT_DIR);
		}
	}

	// Files already built in exportsDir: save-as for one, folder picker for several.
	private void saveReady(List<File> ready)
	{
		if (ready.size() == 1)
		{
			exportedOne = ready.get(0);
			Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
			i.addCategory(Intent.CATEGORY_OPENABLE);
			i.setType(mimeOf(exportedOne));
			i.putExtra(Intent.EXTRA_TITLE, exportedOne.getName());
			startActivityForResult(i, REQ_EXPORT_ONE);
		}
		else
		{
			pendingReady = ready;
			startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_EXPORT_DIR);
		}
	}

	private static String mimeOf(File f)
	{
		return f.getName().endsWith(".mp4") ? "video/mp4" : "application/octet-stream";
	}

	private void pickVideoSpeed(final List<File> src)
	{
		new Thread(() ->
		{
			long[] total = new long[VIDEO_SPEEDS.length];
			try
			{
				for (File f : src)
				{
					long[] est = ReplayVideoEncoder.estimateMs(f, VIDEO_SPEEDS);
					for (int i = 0; i < total.length; i++)
						total[i] += est[i];
				}
			}
			catch (RuntimeException e)
			{
				total = null;
			}
			final long[] est = total;
			main.post(() ->
			{
				if (isDestroyed())
					return;
				CharSequence[] items = new CharSequence[VIDEO_SPEEDS.length];
				for (int i = 0; i < items.length; i++)
					items[i] = (i == 0 ? "As played" : (int) VIDEO_SPEEDS[i] + "x speed")
							+ (est == null ? "" : "  (" + duration(est[i]) + ")");
				new AlertDialog.Builder(this)
						.setTitle("Video speed")
						.setItems(items, (d, which) -> encodeVideos(src, VIDEO_SPEEDS[which]))
						.show();
			});
		}, "dcrec-estimate").start();
	}

	private static String duration(long ms)
	{
		long s = ms / 1000;
		return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
				: String.format(Locale.US, "%d:%02d", s / 60, s % 60);
	}

	// Encode first, then pick the destination: the picker gets a finished file.
	private void encodeVideos(final List<File> src, final double speed)
	{
		final Typeface tf = videoTypeface(this);
		final AtomicBoolean cancel = new AtomicBoolean();
		encodeCancel = cancel;
		final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		bar.setMax(1000);
		int pad = Math.round(24 * getResources().getDisplayMetrics().density);
		bar.setPadding(pad, pad, pad, pad);
		final AlertDialog dlg = new AlertDialog.Builder(this)
				.setTitle(src.size() == 1 ? "Encoding video\u2026" : "Encoding videos\u2026")
				.setView(bar)
				.setCancelable(false)
				.setNegativeButton("Cancel", (d, w) -> cancel.set(true))
				.show();
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		new Thread(() ->
		{
			List<File> videos = new ArrayList<>();
			String error = null;
			try
			{
				List<File> copies = copiesNow(src);
				for (int n = 0; n < copies.size(); n++)
				{
					File c = copies.get(n);
					try
					{
						if (error != null || cancel.get())
							continue;
						File v = new File(c.getParentFile(),
								c.getName().replace(".dcrec", "") + ".mp4");
						final int idx = n, count = copies.size();
						ReplayVideoEncoder.encode(c, v, speed, tf, new ReplayVideoEncoder.Progress()
						{
							@Override
							public void update(int done, int total)
							{
								int p = (int) ((1000L * idx + 1000L * done / Math.max(1, total)) / count);
								main.post(() -> bar.setProgress(p));
							}

							@Override
							public boolean cancelled()
							{
								return cancel.get();
							}
						});
						videos.add(v);
					}
					catch (IOException | RuntimeException e)
					{
						error = e.getMessage();
					}
					finally
					{
						c.delete();
					}
				}
			}
			catch (IOException | RuntimeException e)
			{
				error = e.getMessage();
			}
			final String err = error;
			main.post(() ->
			{
				if (encodeCancel == cancel)
					encodeCancel = null;
				boolean failed = isDestroyed() || cancel.get() || err != null;
				if (failed)
					for (File v : videos)
						v.delete();
				if (isDestroyed())
					return;
				getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
				dlg.dismiss();
				if (failed)
					Toast.makeText(this, cancel.get() ? "Export cancelled"
							: "Video export failed: " + err, Toast.LENGTH_LONG).show();
				else
					saveReady(videos);
			});
		}, "dcrec-video").start();
	}

	// The game's font, so videos and GIFs look like the user's console.
	static Typeface videoTypeface(Context ctx)
	{
		try
		{
			String key = Preferences.getFontFace();
			if (CustomFontManager.isCustomFont(key))
				return Typeface.createFromFile(CustomFontManager.getCustomFontFile(ctx,
						CustomFontManager.customFontFilename(key)));
			return Typeface.createFromAsset(ctx.getAssets(), key);
		}
		catch (RuntimeException e)
		{
			return GameFontShaper.referenceTypeface(ctx);
		}
	}

	// Background: write each copy to doc (single) or a new file in tree, then drop it.
	private void writeOut(final List<File> copies, final Uri doc, final Uri tree)
	{
		new Thread(() ->
		{
			int ok = 0;
			DocumentFile dir = tree == null ? null : DocumentFile.fromTreeUri(this, tree);
			for (File c : copies)
			{
				try
				{
					Uri dst = doc;
					if (dir != null)
					{
						DocumentFile d = dir.createFile(mimeOf(c), c.getName());
						dst = d == null ? null : d.getUri();
					}
					if (dst != null && copyTo(this, c, dst))
						ok++;
				}
				finally
				{
					c.delete();
				}
			}
			final int n = ok;
			final String noun = copies.get(0).getName().endsWith(".mp4") ? " video" : " replay";
			main.post(() -> Toast.makeText(this, n == copies.size()
					? "Exported " + n + noun + (n == 1 ? "" : "s")
					: "Exported " + n + " of " + copies.size() + noun + "s", Toast.LENGTH_LONG).show());
		}, "dcrec-export-write").start();
	}

	static boolean copyTo(Context ctx, File src, Uri dst)
	{
		try (InputStream in = new FileInputStream(src);
				OutputStream out = ctx.getContentResolver().openOutputStream(dst, "w"))
		{
			if (out == null)
				return false;
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) > 0)
				out.write(buf, 0, n);
			return true;
		}
		catch (IOException | RuntimeException e)
		{
			return false;
		}
	}

	private void confirmDelete(final List<File> del)
	{
		if (del.isEmpty())
			return;
		String what = del.size() == 1 ? del.get(0).getName() : del.size() + " replays";
		new AlertDialog.Builder(this)
				.setMessage("Delete " + what + "? This can't be undone.")
				.setPositiveButton("Delete", (d, w) ->
				{
					for (File f : del)
					{
						f.delete();
						new File(f.getPath() + "-wal").delete();
						new File(f.getPath() + "-shm").delete();
					}
					if (selecting)
						setSelecting(false);
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
		Uri picked = resultCode == RESULT_OK && data != null ? data.getData() : null;
		if (requestCode == REQ_EXPORT_ONE)
		{
			if (exportedOne != null && picked != null)
				writeOut(Collections.singletonList(exportedOne), picked, null);
			else if (exportedOne != null)
				exportedOne.delete();
			exportedOne = null;
			return;
		}
		if (requestCode == REQ_EXPORT_DIR && !pendingReady.isEmpty())
		{
			if (picked != null)
				writeOut(pendingReady, null, picked);
			else
				for (File f : pendingReady)
					f.delete();
			pendingReady = Collections.emptyList();
			return;
		}
		if (requestCode == REQ_EXPORT_DIR)
		{
			if (picked != null)
				cleanCopies(pendingExport, copies -> writeOut(copies, null, picked));
			pendingExport = Collections.emptyList();
			return;
		}
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
