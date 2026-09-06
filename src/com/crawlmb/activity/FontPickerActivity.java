package com.crawlmb.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.crawlmb.CustomFontManager;
import com.crawlmb.Preferences;
import com.crawlmb.R;
import com.crawlmb.WindowCompatAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen dark font picker. A shared preview at the top shows the
 * pending font's sample text. Tapping a row previews it; the font isn't
 * saved until the user presses Apply. Exit leaves without saving.
 */
public class FontPickerActivity extends Activity
{
	private static final int REQ_IMPORT_FONT = 2001;

	private static final String PREVIEW_TEXT = "The @, a goblin, and 3 potions.";

	private static final String[] BUILTIN_FILES = {
		"VeraMoBd.ttf",
		"8514oemreg.ttf",
		"Flexi_IBM_VGA_False.ttf",
		"Flexi_IBM_VGA_True.ttf",
		"Hack-Bold.ttf",
		"Hack-Regular.ttf",
		"IBMPlexMono-Bold.ttf",
		"IBMPlexMono-Regular.ttf",
		"JetBrainsMonoNL-ExtraBold.ttf",
		"JetBrainsMonoNL-Regular.ttf",
		"Px437_Acer710_Mono.ttf",
		"Px437_HP_100LX_6x8.ttf",
		"Px437_IBM_EGA_9x14.ttf",
	};

	private static final String[] BUILTIN_LABELS = {
		"VeraMoBd (default)",
		"8514oemreg",
		"Flexi_IBM_VGA_False",
		"Flexi_IBM_VGA_True",
		"Hack Bold",
		"Hack Regular",
		"IBM Plex Mono Bold",
		"IBM Plex Mono Regular",
		"JetBrains Mono ExtraBold",
		"JetBrains Mono Regular",
		"Px437 Acer710 Mono",
		"Px437 HP 100LX 6x8",
		"Px437 IBM EGA 9x14",
	};

	private ListView listView;
	private TextView previewView;
	private View bottomBar;
	private FontAdapter adapter;

	private final List<FontEntry> entries = new ArrayList<>();

	// The font saved in preferences (what the game is using right now).
	private String savedPrefValue;
	// The font the user has highlighted but not yet applied.
	private String pendingPrefValue;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		WindowCompatAdapter.applyEdgeToEdge(this);
		setContentView(R.layout.activity_font_picker);

		WindowCompatAdapter.padRootForSystemBars(
				findViewById(R.id.font_picker_root));

		previewView = findViewById(R.id.font_preview);
		previewView.setText(PREVIEW_TEXT);

		bottomBar = findViewById(R.id.bottom_bar);

		listView = findViewById(R.id.font_list);
		adapter = new FontAdapter();
		listView.setAdapter(adapter);

		listView.setOnItemClickListener((parent, view, position, id) -> {
			if (position < 0 || position >= entries.size())
				return;
			FontEntry entry = entries.get(position);
			pendingPrefValue = entry.prefValue;
			updatePreview(entry);
			refreshBottomBar();
			adapter.notifyDataSetChanged();
		});

		findViewById(R.id.btn_import_font).setOnClickListener(v -> launchImport());
		findViewById(R.id.btn_exit).setOnClickListener(v -> finish());
		findViewById(R.id.btn_apply).setOnClickListener(v -> {
			Preferences.setFontFace(pendingPrefValue);
			finish();
		});

		savedPrefValue = Preferences.getFontFace();
		pendingPrefValue = savedPrefValue;

		rebuildList();
	}

	private void rebuildList()
	{
		entries.clear();

		for (int i = 0; i < BUILTIN_FILES.length; i++)
			entries.add(new FontEntry(BUILTIN_LABELS[i], BUILTIN_FILES[i], true));

		List<String> custom = CustomFontManager.listCustomFonts(this);
		for (String filename : custom)
		{
			String label = stripExtension(filename);
			String prefValue = CustomFontManager.customFontPrefValue(filename);
			entries.add(new FontEntry(label, prefValue, false));
		}

		// If the pending font was deleted, reset to saved
		if (!hasEntry(pendingPrefValue))
			pendingPrefValue = savedPrefValue;

		if (adapter != null)
			adapter.notifyDataSetChanged();

		updatePreviewFromPending();
		refreshBottomBar();
	}

	private boolean hasEntry(String prefValue)
	{
		for (FontEntry e : entries)
			if (e.prefValue.equals(prefValue))
				return true;
		return false;
	}

	private void updatePreviewFromPending()
	{
		for (FontEntry e : entries)
		{
			if (e.prefValue.equals(pendingPrefValue))
			{
				updatePreview(e);
				return;
			}
		}
		if (!entries.isEmpty())
			updatePreview(entries.get(0));
	}

	private void updatePreview(FontEntry entry)
	{
		Typeface tf = loadTypeface(entry);
		if (tf != null)
			previewView.setTypeface(tf);
	}

	private void refreshBottomBar()
	{
		boolean changed = !pendingPrefValue.equals(savedPrefValue);
		bottomBar.setVisibility(changed ? View.VISIBLE : View.GONE);
	}

	private static String stripExtension(String filename)
	{
		int dot = filename.lastIndexOf('.');
		return dot > 0 ? filename.substring(0, dot) : filename;
	}

	// --- Import flow ---

	private void launchImport()
	{
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		// No MIME filter — font MIME types vary wildly across providers
		// (font/ttf, font/sfnt, application/x-font-ttf, octet-stream,
		// or none at all). We validate extension + monospace after copy.
		intent.setType("*/*");
		try
		{
			startActivityForResult(intent, REQ_IMPORT_FONT);
		}
		catch (Exception e)
		{
			Toast.makeText(this, R.string.font_import_failed, Toast.LENGTH_LONG).show();
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data)
	{
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode != REQ_IMPORT_FONT || resultCode != RESULT_OK || data == null)
			return;
		Uri uri = data.getData();
		if (uri == null)
			return;

		CustomFontManager.ImportResult result = CustomFontManager.importFont(this, uri);
		if (!result.ok())
		{
			Toast.makeText(this, result.error, Toast.LENGTH_LONG).show();
			return;
		}

		if (result.monoWarning)
		{
			new AlertDialog.Builder(this)
					.setTitle(R.string.font_mono_warning_title)
					.setMessage(getString(R.string.font_mono_warning_msg,
							result.filename))
					.setPositiveButton(R.string.font_mono_warning_keep, (d, w) -> {
						Toast.makeText(this,
								getString(R.string.font_imported, result.filename),
								Toast.LENGTH_SHORT).show();
						rebuildList();
					})
					.setNegativeButton(R.string.font_mono_warning_discard, (d, w) -> {
						CustomFontManager.deleteFont(this, result.filename);
					})
					.setCancelable(false)
					.show();
		}
		else
		{
			Toast.makeText(this, getString(R.string.font_imported, result.filename),
					Toast.LENGTH_SHORT).show();
			rebuildList();
		}
	}

	// --- Delete flow ---

	private void confirmDelete(FontEntry entry)
	{
		String filename = CustomFontManager.customFontFilename(entry.prefValue);
		new AlertDialog.Builder(this)
				.setTitle(R.string.font_delete_confirm_title)
				.setMessage(getString(R.string.font_delete_confirm_msg, filename))
				.setPositiveButton(R.string.font_delete_yes, (d, w) -> {
					CustomFontManager.deleteFont(this, filename);
					rebuildList();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	// --- Typeface loading ---

	private Typeface loadTypeface(FontEntry entry)
	{
		try
		{
			if (entry.builtIn)
				return Typeface.createFromAsset(getAssets(), entry.prefValue);

			String filename = CustomFontManager.customFontFilename(entry.prefValue);
			File f = CustomFontManager.getCustomFontFile(this, filename);
			if (f.exists())
				return Typeface.createFromFile(f);
		}
		catch (Exception ignored) {}
		return Typeface.MONOSPACE;
	}

	// --- Data model ---

	private static final class FontEntry
	{
		final String label;
		final String prefValue;
		final boolean builtIn;

		FontEntry(String label, String prefValue, boolean builtIn)
		{
			this.label = label;
			this.prefValue = prefValue;
			this.builtIn = builtIn;
		}
	}

	// --- Adapter ---

	private final class FontAdapter extends BaseAdapter
	{
		@Override
		public int getCount() { return entries.size(); }

		@Override
		public FontEntry getItem(int position) { return entries.get(position); }

		@Override
		public long getItemId(int position) { return position; }

		@Override
		public View getView(int position, View convertView, ViewGroup parent)
		{
			View row = convertView;
			if (row == null)
				row = getLayoutInflater().inflate(R.layout.item_font, parent, false);

			FontEntry entry = entries.get(position);
			boolean selected = entry.prefValue.equals(pendingPrefValue);

			TextView nameView = row.findViewById(R.id.font_name);
			nameView.setText(entry.label);
			nameView.setTextColor(entry.builtIn ? 0xFF888888 : 0xFFDDDDDD);

			TextView selectedView = row.findViewById(R.id.font_selected);
			selectedView.setVisibility(selected ? View.VISIBLE : View.GONE);

			row.setBackgroundColor(selected ? 0xFF1A2A1A : 0x00000000);

			ImageButton deleteBtn = row.findViewById(R.id.btn_delete_font);
			if (entry.builtIn)
			{
				deleteBtn.setVisibility(View.GONE);
			}
			else
			{
				deleteBtn.setVisibility(View.VISIBLE);
				deleteBtn.setImageResource(R.drawable.ic_delete_font);
				deleteBtn.setOnClickListener(v -> confirmDelete(entry));
			}

			return row;
		}
	}
}
