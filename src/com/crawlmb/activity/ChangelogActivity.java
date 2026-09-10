package com.crawlmb.activity;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.crawlmb.Preferences;
import com.crawlmb.R;
import com.crawlmb.WindowCompatAdapter;

// Shows the compiled release notes. The page at
// assets/docs/changelogs.html is generated at build time by setup.sh from
// metadata/en-US/changelogs/*.txt, so it always reflects the bundled build.
public class ChangelogActivity extends Activity
{
	@Override
	public void onCreate(Bundle b)
	{
		super.onCreate(b);
		WindowCompatAdapter.applyEdgeToEdge(this);
		setContentView(R.layout.changelog);
		WindowCompatAdapter.padRootForSystemBars(
				findViewById(android.R.id.content));
		this.setTitle("Changelog");
		WebView changelog = (WebView) findViewById(R.id.changelog_text);

		WebSettings settings = changelog.getSettings();
		settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NARROW_COLUMNS);
		settings.setUseWideViewPort(false);
		changelog.loadUrl("file:///android_asset/docs/changelogs.html");
		changelog.computeScroll();
	}

	@Override
	protected void onResume()
	{
		super.onResume();

		SharedPreferences pref = getSharedPreferences(Preferences.NAME, MODE_PRIVATE);

		WindowCompatAdapter.applyFullscreen(this,
				pref.getBoolean(Preferences.KEY_FULLSCREEN, true));
	}
}
