package com.crawlmb.view;

import android.graphics.Paint;
import android.graphics.Typeface;

import com.crawlmb.FontConfig;

// Normalizes user-selectable game fonts toward a target cell w/h aspect
// (VeraMoBd's ~0.5 by default) so panel layouts tuned against VeraMoBd
// stay consistent. Behavior is driven by font_config.txt:
//   font_target_aspect  (0 disables)
//   font_scalex_min/max (clamp on auto-computed scaleX)
//   font_scalex_<file>  (per-font override, bypasses auto+clamp)
// If configure() hasn't been called, uses hardcoded defaults matching
// the config file defaults.
public final class GameFontShaper
{
	private static final float DEFAULT_TARGET_ASPECT = 0.5f;
	private static final float DEFAULT_MIN = 0.85f;
	private static final float DEFAULT_MAX = 1.15f;
	private static final float PROBE_TEXT_SIZE = 64f;

	private static FontConfig cfg = null;

	private GameFontShaper() { }

	public static void configure(FontConfig c)
	{
		cfg = c;
	}

	public static float scaleXFor(Typeface tf, String assetName)
	{
		if (tf == null)
			return 1f;

		if (cfg != null && assetName != null)
		{
			Float override = cfg.fontScaleXOverrides.get(assetName);
			if (override != null)
				return override;
		}

		float target = (cfg != null) ? cfg.fontTargetAspect : DEFAULT_TARGET_ASPECT;
		if (target <= 0f)
			return 1f;

		Paint p = new Paint();
		p.setTypeface(tf);
		p.setTextSize(PROBE_TEXT_SIZE);
		float w = p.measureText("X");
		float h = p.getFontSpacing();
		if (w <= 0f || h <= 0f)
			return 1f;

		float raw = target * h / w;
		float min = (cfg != null) ? cfg.fontScaleXMin : DEFAULT_MIN;
		float max = (cfg != null) ? cfg.fontScaleXMax : DEFAULT_MAX;
		return Math.max(min, Math.min(max, raw));
	}
}
