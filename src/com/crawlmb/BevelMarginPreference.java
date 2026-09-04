package com.crawlmb;

import android.content.Context;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Slider preference for the edge margin setting. Discrete 4dp steps
 * from -8 to 32. Persists the dp value as a string (same format as
 * the old ListPreference) so existing user values migrate transparently.
 */
public class BevelMarginPreference extends DialogPreference
		implements SeekBar.OnSeekBarChangeListener
{
	// Slider stops in dp: -16, -12, …, 0, …, 32 → 13 positions (indices 0-12)
	private static final int MIN_DP = -16;
	private static final int MAX_DP = 32;
	private static final int STEP_DP = 4;
	private static final int STEPS = (MAX_DP - MIN_DP) / STEP_DP; // 9

	private SeekBar seekBar;
	private TextView label;
	private int currentDp;

	public BevelMarginPreference(Context context, AttributeSet attrs)
	{
		super(context, attrs);
		setDialogLayoutResource(R.layout.bevel_margin_slider);
	}

	@Override
	protected void onBindDialogView(View view)
	{
		super.onBindDialogView(view);
		label = view.findViewById(R.id.bevel_margin_label);
		seekBar = view.findViewById(R.id.bevel_margin_seekbar);

		currentDp = dpFromPersisted();
		seekBar.setMax(STEPS);
		seekBar.setProgress(dpToIndex(currentDp));
		seekBar.setOnSeekBarChangeListener(this);
		updateLabel(currentDp);
	}

	@Override
	public void onProgressChanged(SeekBar sb, int progress, boolean fromUser)
	{
		currentDp = indexToDp(progress);
		updateLabel(currentDp);
	}

	@Override public void onStartTrackingTouch(SeekBar sb) {}
	@Override public void onStopTrackingTouch(SeekBar sb) {}

	@Override
	protected void onDialogClosed(boolean positiveResult)
	{
		if (positiveResult)
		{
			String value = String.valueOf(currentDp);
			if (callChangeListener(value))
				persistString(value);
		}
	}

	private void updateLabel(int dp)
	{
		if (label == null)
			return;
		if (dp == 0)
			label.setText("Device default");
		else
			label.setText(String.format(
					getContext().getString(R.string.bevel_margin_slider_format), dp));
	}

	private int dpFromPersisted()
	{
		String v = getPersistedString("0");
		try { return clampDp(Integer.parseInt(v)); }
		catch (NumberFormatException e) { return 0; }
	}

	private static int clampDp(int dp)
	{
		return Math.max(MIN_DP, Math.min(MAX_DP, dp));
	}

	private static int dpToIndex(int dp)
	{
		return (clampDp(dp) - MIN_DP) / STEP_DP;
	}

	private static int indexToDp(int index)
	{
		return MIN_DP + index * STEP_DP;
	}

	@Override
	public CharSequence getSummary()
	{
		int dp = dpFromPersisted();
		if (dp == 0)
			return "Device default";
		return String.format(
				getContext().getString(R.string.bevel_margin_slider_format), dp);
	}
}
