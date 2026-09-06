package com.crawlmb.view;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;

// VeraMoBd is the sizing reference for all user-selectable fonts. Panels are
// tuned against it — VeraMoBd fills the full vertical height of the map panel
// at the auto-fit width. For any other face:
//   1. widthFitTextSize(cols, maxWidth) picks the textSize where VeraMoBd's
//      advance width * cols == maxWidth (fills width).
//   2. matchReferenceLineHeight(face, T) scales that textSize so the chosen
//      face's fontSpacing equals VeraMoBd's at T (fills the same vertical
//      height, since rowCount * fontSpacing == panel height).
//   3. widthClamp(face, matchedSize, cols, maxWidth) checks whether the
//      chosen face overflows at that size. If so, it splits the needed
//      reduction evenly across font-size shrink and horizontal scaleX,
//      each clamped at MIN_FACTOR (85%).
public final class GameFontShaper
{
	public static final String REFERENCE_ASSET = "VeraMoBd.ttf";
	private static final float MIN_FACTOR = 0.85f;

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

	// Grow textSize on a VeraMoBd probe until refCharWidth * cols would
	// exceed maxWidth, then back off one step. Returns the last size that
	// still fit. Bounded by [minSize, maxSize].
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

	// Scale the reference textSize so `face` renders at VeraMoBd's line
	// height (fontSpacing). Different faces have different vertical metrics
	// per em; without this a face with a shorter metric would leave
	// vertical slack in a panel tuned for VeraMoBd.
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

	// If the chosen face at matchedSize overflows cols * maxWidth, split
	// the reduction across font-size shrink and horizontal scaleX (each
	// clamped at MIN_FACTOR = 85%). Returns the adjusted size and scaleX.
	public static WidthClampResult widthClamp(Typeface face,
			int matchedSize, int cols, int maxWidth)
	{
		Paint probe = new Paint();
		probe.setTypeface(face);
		probe.setTextSize(matchedSize);
		float charW = probe.measureText("X");
		float needed = charW * cols;

		if (needed <= maxWidth)
			return new WidthClampResult(matchedSize, 1.0f);

		float ratio = maxWidth / needed;
		// Split evenly: sizeFactor * scaleXFactor = ratio
		float half = (float) Math.sqrt(ratio);

		float sizeFactor = Math.max(half, MIN_FACTOR);
		float scaleXFactor = Math.max(ratio / sizeFactor, MIN_FACTOR);

		int adjusted = Math.round(matchedSize * sizeFactor);
		return new WidthClampResult(adjusted, scaleXFactor);
	}
}
