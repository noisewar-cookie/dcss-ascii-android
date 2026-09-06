package com.crawlmb.view;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;

// Sizes fonts against VeraMoBd as the reference.
//   1. widthFitTextSize — largest textSize where VeraMoBd fits cols in maxWidth.
//   2. matchReferenceLineHeight — scale textSize so face's line height = VeraMoBd's.
//   3. widthClamp — if the face is too wide or narrow after (2), adjust via
//      textSize↓ + scaleX↓ (wide) or scaleX↑ only (narrow), each ≥/≤ 85%.
public final class GameFontShaper
{
	public static final String REFERENCE_ASSET = "VeraMoBd.ttf";
	private static final float MIN_FACTOR = 0.85f;
	private static final float MAX_FACTOR = 1.0f / MIN_FACTOR; // ~1.176

	public static final class WidthClampResult
	{
		public final int textSize;
		public final float scaleX;

		WidthClampResult(int textSize, float scaleX)
		{
			this.textSize = textSize;
			this.scaleX = scaleX;
		}
	}

	private static Typeface referenceTypeface = null;

	private GameFontShaper() { }

	public static Typeface referenceTypeface(Context ctx)
	{
		if (referenceTypeface == null)
		{
			referenceTypeface = Typeface.createFromAsset(
					ctx.getAssets(), REFERENCE_ASSET);
		}
		return referenceTypeface;
	}

	// Largest textSize where VeraMoBd fits cols in maxWidth.
	public static int widthFitTextSize(Context ctx, int cols, int maxWidth,
			int minSize, int maxSize)
	{
		Paint ref = new Paint();
		ref.setTypeface(referenceTypeface(ctx));
		int size = minSize;
		do
		{
			size += 1;
			ref.setTextSize(size);
		}
		while (ref.measureText("X") * cols <= maxWidth && size < maxSize);
		return Math.max(minSize, size - 1);
	}

	// Scale textSize so face's fontSpacing matches VeraMoBd's.
	public static float matchReferenceLineHeight(Context ctx, Typeface face,
			float referenceTextSize)
	{
		if (face == null || referenceTextSize <= 0f)
			return referenceTextSize;

		Paint ref = new Paint();
		ref.setTypeface(referenceTypeface(ctx));
		ref.setTextSize(referenceTextSize);
		float target = ref.getFontSpacing();

		Paint probe = new Paint();
		probe.setTypeface(face);
		probe.setTextSize(referenceTextSize);
		float actual = probe.getFontSpacing();

		if (actual <= 0f)
			return referenceTextSize;
		return referenceTextSize * (target / actual);
	}

	// Fit face to cols in maxWidth. Wide: textSize↓ + scaleX↓ (≥ 85%).
	// Narrow: scaleX↑ only (textSize↑ would overflow vertically).
	public static WidthClampResult widthClamp(Typeface face,
			int matchedSize, int cols, int maxWidth)
	{
		Paint probe = new Paint();
		probe.setTypeface(face);
		probe.setTextSize(matchedSize);
		float charW = probe.measureText("X");
		float needed = charW * cols;

		float ratio = maxWidth / needed;

		if (ratio >= 1.0f && ratio <= 1.03f)
			return new WidthClampResult(matchedSize, 1.0f);

		if (ratio > 1.03f)
		{
			float scaleXFactor = Math.min(ratio, MAX_FACTOR);
			return new WidthClampResult(matchedSize, scaleXFactor);
		}

		// Wide: split evenly across size and scaleX
		float half = (float) Math.sqrt(ratio);
		float sizeFactor = Math.max(half, MIN_FACTOR);
		float scaleXFactor = Math.max(ratio / sizeFactor, MIN_FACTOR);

		int adjusted = Math.round(matchedSize * sizeFactor);
		return new WidthClampResult(adjusted, scaleXFactor);
	}
}
