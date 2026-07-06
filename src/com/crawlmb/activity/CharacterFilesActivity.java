package com.crawlmb.activity;

import java.io.File;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.crawlmb.CharFileViewer;

public class CharacterFilesActivity extends ListActivity
{
	private String[] charFiles;

	@Override
	public void onCreate(Bundle bundle)
	{
		super.onCreate(bundle);
		
		File morgueDirFile = com.crawlmb.Paths.getMorgueDir(this);
		charFiles = morgueDirFile.list();
		if (charFiles == null || charFiles.length == 0)
		{
			//Maybe should show a dialog here or something?
			Toast.makeText(this, "No character files stored", Toast.LENGTH_LONG).show();
			return;
		}
		
		ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, charFiles);
		setListAdapter(adapter);
	}
	
	@Override
	protected void onListItemClick (ListView l, View v, int position, long id)
	{
		String charFileName = charFiles[position];
		File file = new File(com.crawlmb.Paths.getMorgueDir(this), charFileName);
		Intent intent = new Intent(this, CharFileViewer.class);
		// String extra, not a file:// data Uri — the latter throws
		// FileUriExposedException on targetSdk >= 24.
		intent.putExtra(CharFileViewer.EXTRA_FILE_PATH, file.getAbsolutePath());
		startActivity(intent);
	}

}
