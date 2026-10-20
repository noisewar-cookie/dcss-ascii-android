package com.crawlmb;

import android.content.Context;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Slider for the unfolded compact-mode centerline margin: 0 (Default) to
 * 20dp in 4dp steps. Value is shown in the title, not a summary.
 */
public class CenterlineMarginPreference extends DialogPreference
		implements SeekBar.OnSeekBarChangeListener
{
	private static final int MAX_DP = 20;
	private static final int STEP_DP = 4;

	private TextView label;
	private int currentDp;

	public CenterlineMarginPreference(Context context, AttributeSet attrs)
	{
		super(context, attrs);
		setDialogLayoutResource(R.layout.bevel_margin_slider);
	}

	@Override
	protected void onBindDialogView(View view)
	{
		super.onBindDialogView(view);
		label = view.findViewById(R.id.bevel_margin_label);
		SeekBar seekBar = view.findViewById(R.id.bevel_margin_seekbar);

		currentDp = getDp();
		seekBar.setMax(MAX_DP / STEP_DP);
		seekBar.setProgress(currentDp / STEP_DP);
		seekBar.setOnSeekBarChangeListener(this);
		label.setText(valueLabel(getContext(), currentDp));
	}

	@Override
	public void onProgressChanged(SeekBar sb, int progress, boolean fromUser)
	{
		currentDp = progress * STEP_DP;
		label.setText(valueLabel(getContext(), currentDp));
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

	public int getDp()
	{
		try
		{
			int dp = Integer.parseInt(getPersistedString("0"));
			return Math.max(0, Math.min(MAX_DP, dp / STEP_DP * STEP_DP));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	public static String valueLabel(Context c, int dp)
	{
		return dp == 0 ? c.getString(R.string.centerline_margin_default)
				: c.getString(R.string.centerline_margin_slider_format, dp);
	}
}
