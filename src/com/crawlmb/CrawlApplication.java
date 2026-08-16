package com.crawlmb;

import android.app.Application;
import android.content.Context;

public class CrawlApplication extends Application
{
	private static Context appContext;

	@Override
	public void onCreate()
	{
		super.onCreate();

		appContext = getApplicationContext();
		Preferences.init(getResources(), getSharedPreferences(Preferences.NAME, MODE_PRIVATE));
		Preferences.migrateKeyboardRemaps(this);
		Preferences.unstickNoKeyboard();
	}

	// For native up-calls (NativeWrapper.notifyGameSaved) that arrive on
	// arbitrary threads with no Activity in hand.
	public static Context getAppContext()
	{
		return appContext;
	}
}
