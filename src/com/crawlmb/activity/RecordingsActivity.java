package com.crawlmb.activity;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import com.crawlmb.R;
import com.crawlmb.WindowCompatAdapter;
import com.crawlmb.recording.GameIdentity;
import com.crawlmb.recording.RecordingExporter;
import com.crawlmb.recording.RecordingImporter;
import com.crawlmb.recording.Recordings;
import com.crawlmb.recording.ReplayVideoEncoder;
import com.crawlmb.recording.SqliteRecordingStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Recordings library: list, watch, export as .dcrec or encoded .mp4 to a
 * picked file (SAF; several go as one .zip), import, delete; Select mode
 * applies export/delete to several. Opened from Preferences > Replays. Watch
 * and video export return to the caller (GameActivity plays the replay in
 * place, or plays it through while encoding).
 */
public class RecordingsActivity extends ListActivity
{
	private static final int REQ_IMPORT = 1;
	private static final int REQ_EXPORT_ONE = 2;
	private static final int REQ_VIDEO = 4;
	// kill_method_type KILLED_BY_WINNING (kill-method-type.h).
	private static final int KILLED_BY_WINNING = 11;
	private static final double[] VIDEO_SPEEDS = { 1, 2, 4, 8 };

	private final List<File> files = new ArrayList<>();
	private final List<Boolean> won = new ArrayList<>();
	private final List<String> names = new ArrayList<>();
	private final Handler main = new Handler(Looper.getMainLooper());
	private final Set<File> selected = new HashSet<>();
	private boolean selecting;
	// Awaiting the SAF picker: one finished file (and, for a zip, the names
	// in it), or originals and speed for a video export.
	private File exportedOne;
	private String exportedContents;
	private List<File> pendingVideo = Collections.emptyList();
	private double pendingVideoSpeed;

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
	protected void onResume()
	{
		super.onResume();
		reload();
		resumed = this;
		showReport();
	}

	@Override
	protected void onPause()
	{
		super.onPause();
		if (resumed == this)
			resumed = null;
	}

	// Video exports finish in GameActivity, so reports queue here until the
	// Replays list is showing. Main thread only.
	private static String[] pendingReport;
	private static RecordingsActivity resumed;

	static void report(String title, String body)
	{
		pendingReport = new String[] { title, body };
		if (resumed != null)
			resumed.showReport();
	}

	private void showReport()
	{
		if (pendingReport == null)
			return;
		String[] r = pendingReport;
		pendingReport = null;
		new AlertDialog.Builder(this)
				.setTitle(r[0])
				.setMessage(r[1])
				.setPositiveButton("OK", null)
				.show();
	}

	// contents: names inside a batch zip; extra: a closing line, e.g. speed.
	static void reportSaved(Context ctx, boolean ok, File f, Uri doc, String contents,
			String extra)
	{
		if (!ok)
		{
			report("Export failed", "Couldn't write " + f.getName() + ".");
			return;
		}
		DocumentFile d = DocumentFile.fromSingleUri(ctx, doc);
		StringBuilder b = new StringBuilder("Saved as ").append(d == null || d.getName() == null
				? f.getName() : d.getName()).append('.');
		if (contents != null)
			b.append("\n\nContains:\n").append(contents);
		if (extra != null)
			b.append("\n\n").append(extra);
		report("Export complete", b.toString());
	}

	static String names(List<File> files)
	{
		StringBuilder b = new StringBuilder();
		for (File f : files)
			b.append(b.length() == 0 ? "" : "\n").append(f.getName());
		return b.toString();
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

	// Never hand out the live file: WAL sidecars may hold recent writes.
	private void cleanCopies(final List<File> src)
	{
		if (src.isEmpty())
			return;
		Toast.makeText(this, "Preparing replay\u2026", Toast.LENGTH_SHORT).show();
		new Thread(() ->
		{
			try
			{
				List<File> out = copiesNow(src);
				String contents = out.size() > 1 ? names(out) : null;
				File one = out.size() > 1 ? zipAll(this, "replays", out) : out.get(0);
				main.post(() -> saveReady(one, contents));
			}
			catch (Exception e)
			{
				main.post(() -> report("Export failed", "Couldn't prepare the replay: "
						+ e.getMessage()));
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
						cleanCopies(src);
					else
						pickVideoSpeed(src);
				})
				.show();
	}

	// One clean copy (or zip of several) already built in exportsDir: save-as picker.
	private void saveReady(File f, String contents)
	{
		exportedOne = f;
		exportedContents = contents;
		Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
		i.addCategory(Intent.CATEGORY_OPENABLE);
		i.setType(mimeOf(exportedOne));
		i.putExtra(Intent.EXTRA_TITLE, exportedOne.getName());
		startActivityForResult(i, REQ_EXPORT_ONE);
	}

	// Destination first, like the GIF export: an .mp4, or a zip of several.
	private void pickVideoDestination(List<File> src, double speed)
	{
		pendingVideo = src;
		pendingVideoSpeed = speed;
		Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
		i.addCategory(Intent.CATEGORY_OPENABLE);
		i.setType(src.size() == 1 ? "video/mp4" : "application/zip");
		i.putExtra(Intent.EXTRA_TITLE, src.size() == 1
				? src.get(0).getName().replace(".dcrec", "") + ".mp4" : batchName("videos"));
		startActivityForResult(i, REQ_VIDEO);
	}

	private static String mimeOf(File f)
	{
		return f.getName().endsWith(".mp4") ? "video/mp4"
				: f.getName().endsWith(".zip") ? "application/zip" : "application/octet-stream";
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
						.setItems(items, (d, which) -> pickVideoDestination(src, VIDEO_SPEEDS[which]))
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

	// GameActivity plays each replay through on screen and encodes what it shows.
	private void exportVideos(List<File> src, double speed, Uri doc, boolean grant)
	{
		String[] paths = new String[src.size()];
		for (int i = 0; i < paths.length; i++)
			paths[i] = src.get(i).getPath();
		Intent i = new Intent();
		i.putExtra(GameActivity.EXTRA_VIDEO_PATHS, paths);
		i.putExtra(GameActivity.EXTRA_VIDEO_SPEED, speed);
		i.putExtra(GameActivity.EXTRA_VIDEO_DOC, doc);
		i.putExtra(GameActivity.EXTRA_VIDEO_GRANT, grant);
		setResult(RESULT_OK, i);
		finish();
	}

	// The picker's grant ends with this activity, before GameActivity writes.
	// False if already held (e.g. the custom sync folder): not ours to release.
	private boolean holdGrant(Uri uri, int flags)
	{
		for (UriPermission p : getContentResolver().getPersistedUriPermissions())
			if (p.getUri().equals(uri) && p.isWritePermission())
				return false;
		try
		{
			getContentResolver().takePersistableUriPermission(uri, flags
					& (Intent.FLAG_GRANT_READ_URI_PERMISSION
					| Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
			return true;
		}
		catch (SecurityException e)
		{
			return false;
		}
	}

	static void releaseGrant(Context ctx, Uri uri)
	{
		try
		{
			ctx.getContentResolver().releasePersistableUriPermission(uri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION
					| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		}
		catch (SecurityException e)
		{
			// Gone already, e.g. the document was deleted.
		}
	}

	static void deleteDocument(Context ctx, Uri doc)
	{
		if (doc == null)
			return;
		try
		{
			DocumentsContract.deleteDocument(ctx.getContentResolver(), doc);
		}
		catch (Exception e)
		{
			// Provider refused; an empty file is left behind.
		}
	}

	private void writeOut(final File f, final Uri doc, final String contents)
	{
		new Thread(() ->
		{
			final boolean ok = saveTo(this, f, doc);
			main.post(() -> reportSaved(this, ok, f, doc, contents, null));
		}, "dcrec-export-write").start();
	}

	// Background: copy f to doc, then drop it.
	static boolean saveTo(Context ctx, File f, Uri doc)
	{
		try
		{
			return copyTo(ctx, f, doc);
		}
		finally
		{
			f.delete();
		}
	}

	// Background: packs files into one zip in exportsDir and drops them. The
	// folder picker can't choose Download on Android 11+, so batches go as one file.
	static File zipAll(Context ctx, String what, List<File> files) throws IOException
	{
		File zip = new File(Recordings.exportsDir(ctx), batchName(what));
		boolean ok = false;
		try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip)))
		{
			byte[] buf = new byte[64 * 1024];
			for (File f : files)
			{
				out.putNextEntry(new ZipEntry(f.getName()));
				try (InputStream in = new FileInputStream(f))
				{
					int n;
					while ((n = in.read(buf)) > 0)
						out.write(buf, 0, n);
				}
				out.closeEntry();
			}
			ok = true;
		}
		finally
		{
			for (File f : files)
				f.delete();
			if (!ok)
				zip.delete();
		}
		return zip;
	}

	static String batchName(String what)
	{
		return "dcss-" + what + "_" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
				.format(new Date()) + ".zip";
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
				writeOut(exportedOne, picked, exportedContents);
			else if (exportedOne != null)
				exportedOne.delete();
			exportedOne = null;
			exportedContents = null;
			return;
		}
		if (requestCode == REQ_VIDEO)
		{
			if (picked != null && !pendingVideo.isEmpty())
				exportVideos(pendingVideo, pendingVideoSpeed, picked,
						holdGrant(picked, data.getFlags()));
			pendingVideo = Collections.emptyList();
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
