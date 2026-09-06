package com.crawlmb;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Manages user-imported custom fonts stored in getFilesDir()/fonts/.
 * Built-in fonts live in assets/ and are not touched here.
 */
public final class CustomFontManager
{
	private static final String TAG = "CustomFontManager";
	private static final String FONTS_DIR = "fonts";

	// Prefix stored in the fontface preference to distinguish custom fonts
	// from built-in asset fonts. RegionTermView checks this prefix to decide
	// whether to load via createFromAsset or createFromFile.
	public static final String CUSTOM_PREFIX = "custom:";

	private CustomFontManager() {}

	/** Returns the directory where custom fonts are stored, creating it if needed. */
	public static File getFontsDir(Context ctx)
	{
		File dir = new File(ctx.getFilesDir(), FONTS_DIR);
		if (!dir.exists())
			dir.mkdirs();
		return dir;
	}

	/** Lists custom font filenames (sorted alphabetically). */
	public static List<String> listCustomFonts(Context ctx)
	{
		File dir = getFontsDir(ctx);
		String[] files = dir.list();
		if (files == null || files.length == 0)
			return Collections.emptyList();
		List<String> result = new ArrayList<>();
		for (String f : files)
		{
			String lower = f.toLowerCase();
			if (lower.endsWith(".ttf") || lower.endsWith(".otf"))
				result.add(f);
		}
		Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
		return result;
	}

	/** Returns the absolute File for a custom font by filename. */
	public static File getCustomFontFile(Context ctx, String filename)
	{
		return new File(getFontsDir(ctx), filename);
	}

	/**
	 * Imports a font from a SAF Uri. Copies the file, validates it loads
	 * and is monospace, then returns the filename. Returns null on failure
	 * (caller should show a toast with the error message).
	 */
	public static ImportResult importFont(Context ctx, Uri uri)
	{
		// Derive filename from the URI's display name
		String displayName = queryDisplayName(ctx, uri);
		if (displayName == null || displayName.isEmpty())
			displayName = "imported_font.ttf";

		// Ensure .ttf or .otf extension
		String lower = displayName.toLowerCase();
		if (!lower.endsWith(".ttf") && !lower.endsWith(".otf"))
			return new ImportResult(null, "Only .ttf and .otf font files are supported.", false);

		File dest = new File(getFontsDir(ctx), displayName);
		if (dest.exists())
			return new ImportResult(null,
					"A font named \"" + displayName + "\" already exists.", false);

		// Copy from SAF to internal storage
		try
		{
			copyFromUri(ctx, uri, dest);
		}
		catch (IOException e)
		{
			Log.e(TAG, "Failed to copy font", e);
			dest.delete();
			return new ImportResult(null, "Failed to copy font file.", false);
		}

		// Validate: must load as a Typeface
		Typeface tf;
		try
		{
			tf = Typeface.createFromFile(dest);
		}
		catch (Exception e)
		{
			dest.delete();
			return new ImportResult(null, "Invalid font file — could not load.", false);
		}

		// Warn if not monospace — the file is kept, caller decides
		boolean mono = isMonospace(tf);
		return new ImportResult(displayName, null, !mono);
	}

	/** Deletes a custom font file. Returns true on success. */
	public static boolean deleteFont(Context ctx, String filename)
	{
		File f = getCustomFontFile(ctx, filename);
		boolean deleted = f.delete();

		// If the deleted font was the active selection, revert to default.
		String current = Preferences.getFontFace();
		if (current.equals(CUSTOM_PREFIX + filename))
			Preferences.setFontFace("VeraMoBd.ttf");

		return deleted;
	}

	/** Checks whether a Typeface is monospace by comparing glyph widths. */
	public static boolean isMonospace(Typeface tf)
	{
		Paint paint = new Paint();
		paint.setTypeface(tf);
		paint.setTextSize(100f);
		float wM = paint.measureText("M");
		float wi = paint.measureText("i");
		float wAt = paint.measureText("@");
		if (wM <= 0)
			return false;
		// 2% tolerance — covers sub-pixel rounding and hinting variance
		// while still catching proportional fonts (where M/i differ 40%+).
		float tol = wM * 0.02f;
		return Math.abs(wM - wi) < tol && Math.abs(wM - wAt) < tol;
	}

	/** Returns true if the preference value refers to a custom font. */
	public static boolean isCustomFont(String prefValue)
	{
		return prefValue != null && prefValue.startsWith(CUSTOM_PREFIX);
	}

	/** Extracts the filename from a custom font preference value. */
	public static String customFontFilename(String prefValue)
	{
		if (prefValue == null || !prefValue.startsWith(CUSTOM_PREFIX))
			return prefValue;
		return prefValue.substring(CUSTOM_PREFIX.length());
	}

	/** Builds the preference value for a custom font filename. */
	public static String customFontPrefValue(String filename)
	{
		return CUSTOM_PREFIX + filename;
	}

	// --- helpers ---

	private static String queryDisplayName(Context ctx, Uri uri)
	{
		String name = null;
		try (android.database.Cursor c = ctx.getContentResolver().query(
				uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
				null, null, null))
		{
			if (c != null && c.moveToFirst())
				name = c.getString(0);
		}
		catch (Exception ignored) {}
		return name;
	}

	private static void copyFromUri(Context ctx, Uri uri, File dest)
			throws IOException
	{
		ContentResolver cr = ctx.getContentResolver();
		try (InputStream is = cr.openInputStream(uri);
			 FileOutputStream fos = new FileOutputStream(dest))
		{
			if (is == null)
				throw new IOException("Could not open input stream");
			byte[] buf = new byte[8192];
			int n;
			while ((n = is.read(buf)) != -1)
				fos.write(buf, 0, n);
		}
	}

	/** Result of an import attempt. */
	public static final class ImportResult
	{
		/** Non-null on success: the filename stored in fonts/. */
		public final String filename;
		/** Non-null on failure: human-readable error message. */
		public final String error;
		/** True when the font loaded but failed the monospace check. The
		 *  file is kept — caller should warn and let the user decide. */
		public final boolean monoWarning;

		ImportResult(String filename, String error, boolean monoWarning)
		{
			this.filename = filename;
			this.error = error;
			this.monoWarning = monoWarning;
		}

		public boolean ok() { return filename != null; }
	}
}
