package com.crawlmb.recording;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import com.crawlmb.NativeWrapper;
import com.crawlmb.view.TerminalRenderer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * App-level wiring: one RecWriterThread + GameRecorder per process, file
 * locations, and lifecycle helpers called from NativeWrapper/GameActivity.
 */
public final class Recordings
{
	private static RecWriterThread writer;
	private static GameRecorder recorder;

	private Recordings() {}

	public static File dir(Context ctx)
	{
		return new File(ctx.getFilesDir(), "recordings");
	}

	public static File importsDir(Context ctx)
	{
		return new File(dir(ctx), "imported");
	}

	public static File exportsDir(Context ctx)
	{
		// Shared via FileProvider; add <cache-path name="dcrec" path="dcrec-export/"/>.
		return new File(ctx.getCacheDir(), "dcrec-export");
	}

	public static synchronized GameRecorder recorder(Context ctx)
	{
		if (recorder == null)
		{
			final File d = dir(ctx.getApplicationContext());
			writer = new RecWriterThread(
					id -> SqliteRecordingStore.openForWriting(fileFor(d, id)),
					t -> android.util.Log.w("dcrec", "writer error", t));
			recorder = new GameRecorder(writer, new GameRecorder.NativeBridge()
			{
				@Override public byte[] gameIdentity() { return NativeWrapper.getGameIdentity(); }
				@Override public void resetSession() { NativeWrapper.recResetSession(); }
			}, System::currentTimeMillis, appVersion(ctx), TerminalRenderer.FRAME_ROWS,
					TerminalRenderer.FRAME_COLS);
			if ((ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
				recorder.setPerfLog(line -> android.util.Log.i("dcrec", line));
		}
		return recorder;
	}

	/** First name_seed_version[_n] file that is free or already this game's. */
	private static File fileFor(File dir, GameIdentity id)
	{
		for (int n = 1; ; n++)
		{
			File f = new File(dir, id.fileName(n));
			if (!f.exists())
				return f;
			try
			{
				String key = SqliteRecordingStore.identityKey(f);
				if (key == null || key.equals(id.key()))
					return f;
			}
			catch (RuntimeException e)
			{
				// unreadable: leave it alone, try the next suffix
			}
		}
	}

	/** Existing recorder or null (static callbacks with no Context). */
	public static synchronized GameRecorder peek()
	{
		return recorder;
	}

	public static void onGameSaved()
	{
		GameRecorder r = peek();
		if (r != null)
			r.onGameSaved();
	}

	/** GameActivity.onPause, after nativeSaveGame. */
	public static void flush()
	{
		GameRecorder r = peek();
		if (r != null)
			r.flush();
	}

	/** GameActivity.onStop: let queued writes land before the process can be frozen. */
	public static void drain(long timeoutMs)
	{
		RecWriterThread w;
		synchronized (Recordings.class)
		{
			w = writer;
		}
		if (w != null)
			w.drain(timeoutMs);
	}

	/** Own recordings, newest first (library screen). */
	public static List<File> list(Context ctx)
	{
		List<File> out = new ArrayList<>();
		for (File d : new File[] { dir(ctx), importsDir(ctx) })
		{
			File[] fs = d.listFiles((dir, name) -> name.endsWith(RecFormat.FILE_EXTENSION));
			if (fs != null)
				out.addAll(Arrays.asList(fs));
		}
		out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
		return out;
	}

	private static String appVersion(Context ctx)
	{
		try
		{
			PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
			return pi.versionName + " (" + pi.versionCode + ")";
		}
		catch (Exception e)
		{
			return "unknown";
		}
	}
}
