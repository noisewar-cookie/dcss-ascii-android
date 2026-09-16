package com.crawlmb;

import android.content.Context;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Slider preference for a relative font-size adjustment (points). Discrete
 * 1pt steps from FONTSIZE_MIN to FONTSIZE_MAX; 0 = "Default". Persists the
 * value as a string. Used for both the list-screen and description-popup
 * font-size settings (the key differs per XML entry).
 */
public class FontSizeDeltaPreference extends DialogPreference
		implements SeekBar.OnSeekBarChangeListener
{
	private static final int MIN_PT = Preferences.FONTSIZE_MIN;
	private static final int MAX_PT = Preferences.FONTSIZE_MAX;
	private static final int STEPS = MAX_PT - MIN_PT;

	private SeekBar seekBar;
	private TextView label;
	private int currentPt;

	public FontSizeDeltaPreference(Context context, AttributeSet attrs)
	{
		super(context, attrs);
		setDialogLayoutResource(R.layout.font_size_slider);
	}

	@Override
	protected void onBindDialogView(View view)
	{
		super.onBindDialogView(view);
		label = view.findViewById(R.id.font_size_label);
		seekBar = view.findViewById(R.id.font_size_seekbar);

		currentPt = ptFromPersisted();
		seekBar.setMax(STEPS);
		seekBar.setProgress(currentPt - MIN_PT);
		seekBar.setOnSeekBarChangeListener(this);
		updateLabel(currentPt);
	}

	@Override
	public void onProgressChanged(SeekBar sb, int progress, boolean fromUser)
	{
		currentPt = progress + MIN_PT;
		updateLabel(currentPt);
	}

	@Override public void onStartTrackingTouch(SeekBar sb) {}
	@Override public void onStopTrackingTouch(SeekBar sb) {}

	@Override
	protected void onDialogClosed(boolean positiveResult)
	{
		if (positiveResult)
		{
			String value = String.valueOf(currentPt);
			if (callChangeListener(value))
				persistString(value);
		}
	}

	private void updateLabel(int pt)
	{
		if (label != null)
			label.setText(format(pt));
	}

	private CharSequence format(int pt)
	{
		if (pt == 0)
			return getContext().getString(R.string.font_size_slider_default);
		return String.format(
				getContext().getString(R.string.font_size_slider_format), pt);
	}

	private int ptFromPersisted()
	{
		try { return clampPt(Integer.parseInt(getPersistedString("0"))); }
		catch (NumberFormatException e) { return 0; }
	}

	private static int clampPt(int pt)
	{
		return Math.max(MIN_PT, Math.min(MAX_PT, pt));
	}

	@Override
	public CharSequence getSummary()
	{
		return format(ptFromPersisted());
	}
}
