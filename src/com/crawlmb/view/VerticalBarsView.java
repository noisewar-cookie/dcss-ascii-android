package com.crawlmb.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

// Compact-mode vertical HP/MP bars docked to the map's bottom-right corner.
// Each bar is one column of block glyphs (font_config compact_hp_bar_glyph /
// compact_mp_bar_glyph, default U+2592) — ASCII, not drawn graphics — labelled
// H / M at the bottom and draining downward (the fill recedes toward the bottom
// as the value drops). Filled cells use the console colour_bar zone colours
// exactly; missing cells use the empty-track glyph in the empty-track colour.
// The payload is the 8-int array built by output.cc's compute_native_zones:
//   [0] def per-mille   [1] cur (sub) per-mille   [2] old per-mille
//   [3] argb default fill  [4] argb heal  [5] argb poison
//   [6] argb recent-damage [7] argb empty track
// mp is length 0 for species with no MP (Djinn), which hides the M column.
public class VerticalBarsView extends View
{
	// Gap between the two bar columns, in dp. The view is right-pinned to the
	// map, so a narrow inter-column gap slides the cluster right — pushing the
	// freed space to the HP bar's left (the map-facing side).
	private static final float BAR_GAP_DP = 2f;
	private final float barGapPx;

	// Per-column filled-cell / empty-track glyphs, set from font_config
	// (compact_hp_bar_glyph/_empty, compact_mp_bar_glyph/_empty); defaults
	// medium-shade block U+2592 and pipe U+007C. HP and MP are independent so
	// the config can distinguish the two bars.
	private String hpFilled = "▒";
	private String hpEmpty = "|";
	private String mpFilled = "▒";
	private String mpEmpty = "|";
	private int[] hp = new int[0];
	private int[] mp = new int[0];
	// ARGB override for the MP fill (default + change-up zones); 0 = use the
	// console zone colours from the payload unchanged.
	private int mpFillColor = 0;
	private float fontSizePx = 14f;
	// Map panel the bars overlay; used to clamp the bars to its drawn glyph
	// block so they never extend past the map's characters.
	private RegionTermView mapView;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

	public VerticalBarsView(Context context)
	{
		super(context);
		paint.setTextAlign(Paint.Align.LEFT);
		barGapPx = BAR_GAP_DP * context.getResources().getDisplayMetrics().density;
	}

	public void setTypeface(Typeface tf)
	{
		paint.setTypeface(tf);
	}

	public void setFontSizePx(float px)
	{
		fontSizePx = px;
		paint.setTextSize(px);
		requestLayout();
	}

	public void setMapView(RegionTermView view)
	{
		mapView = view;
		// We read the map's bounds at draw time, but it re-lays-out (font
		// auto-fit, centering) after our first paint. Redraw on its bounds
		// change so the H/M labels don't lag on stale geometry until a tap.
		if (view != null)
			view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
			{
				if (l != ol || t != ot || r != or || b != ob)
					invalidate();
			});
	}

	// HP column filled / empty glyphs as Unicode codepoints (font_config
	// compact_hp_bar_glyph, compact_hp_bar_empty_glyph).
	public void setHpGlyphs(int fillCp, int emptyCp)
	{
		hpFilled = glyph(fillCp, hpFilled);
		hpEmpty = glyph(emptyCp, hpEmpty);
		requestLayout();
	}

	// MP column filled / empty glyphs as Unicode codepoints (font_config
	// compact_mp_bar_glyph, compact_mp_bar_empty_glyph).
	public void setMpGlyphs(int fillCp, int emptyCp)
	{
		mpFilled = glyph(fillCp, mpFilled);
		mpEmpty = glyph(emptyCp, mpEmpty);
		requestLayout();
	}

	// Override the MP bar fill colour (font_config compact_mp_bar_color),
	// replacing the console BLUE default/change-up zones. Pass 0 to keep the
	// payload colours.
	public void setMpFillColor(int argb)
	{
		mpFillColor = argb;
		invalidate();
	}

	// Decode a codepoint to a glyph string, keeping the fallback on an invalid
	// codepoint (Character.toChars throws on out-of-range values).
	private static String glyph(int codepoint, String fallback)
	{
		try
		{
			return new String(Character.toChars(codepoint));
		}
		catch (IllegalArgumentException e)
		{
			return fallback;
		}
	}

	// Layout width of a column: the wider of its filled/empty glyphs so the
	// two glyphs never overlap the neighbouring column.
	private float colWidth(String filled, String empty)
	{
		return Math.max(paint.measureText(filled), paint.measureText(empty));
	}

	public void setBars(int[] newHp, int[] newMp)
	{
		hp = newHp != null ? newHp : new int[0];
		mp = newMp != null ? newMp : new int[0];
		invalidate();
	}

	// Width is stable at two columns (+ gap) so the map layout doesn't reflow
	// when MP appears/disappears; height comes from the parent (spans the map).
	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
	{
		paint.setTextSize(fontSizePx);
		float w = colWidth(hpFilled, hpEmpty) + colWidth(mpFilled, mpEmpty)
				+ barGapPx;
		int wPx = (int) Math.ceil(w) + getPaddingLeft() + getPaddingRight();
		int h = resolveSize((int) Math.ceil(paint.getFontSpacing() * 4),
				heightMeasureSpec);
		setMeasuredDimension(wPx, h);
	}

	@Override
	protected void onDraw(Canvas canvas)
	{
		super.onDraw(canvas);
		if (hp.length < 8)
			return;

		paint.setTextSize(fontSizePx);
		float cellH = paint.getFontSpacing();
		float top = paint.getFontMetrics().top;
		float hpColW = colWidth(hpFilled, hpEmpty);

		// Clamp the drawn block to the map's glyph extent so the bars line up
		// with the map characters (top label at the map's top row, cells no
		// taller than the map). Fall back to the full view if unset.
		float blockTop = getPaddingTop();
		float blockH = getHeight() - getPaddingTop() - getPaddingBottom();
		if (mapView != null && mapView.getContentBlockHeight() > 0)
		{
			int[] mapLoc = new int[2];
			int[] myLoc = new int[2];
			mapView.getLocationInWindow(mapLoc);
			getLocationInWindow(myLoc);
			blockTop = (mapLoc[1] - myLoc[1]) + mapView.getContentTopY();
			blockH = mapView.getContentBlockHeight();
		}

		// Reserve exactly the map's last row for the H/M labels so they sit on
		// that row (aligned with the map's last glyph line) rather than in the
		// spacer below. The fill cells occupy the block above it, bottom-aligned
		// so the stack abuts the label row.
		float mapRowH = (mapView != null && mapView.getContentRowHeight() > 0)
				? mapView.getContentRowHeight() : cellH;
		float fillH = blockH - mapRowH;
		int cells = (int) Math.floor(fillH / cellH);
		if (cells < 1)
			return;
		// Label baseline: vertically centered in the last map-row band.
		Paint.FontMetrics fm = paint.getFontMetrics();
		float labelBaseline = blockTop + blockH - mapRowH / 2f
				- (fm.ascent + fm.descent) / 2f;

		float x = getPaddingLeft();
		drawColumn(canvas, x, top, cellH, blockTop, cells, labelBaseline, 'H', hp,
				hpFilled, hpEmpty, 0);
		if (mp.length >= 8)
			drawColumn(canvas, x + hpColW + barGapPx, top, cellH, blockTop, cells,
					labelBaseline, 'M', mp, mpFilled, mpEmpty, mpFillColor);
	}

	// One column: `cells` rows draining down (cell 0 at the top empties first,
	// the bottom cell drains last), then the H/M label at labelBaseline (on the
	// map's last row). Filled cells draw the fill glyph in their zone colour;
	// missing cells draw the empty-track glyph in the empty-track colour.
	private void drawColumn(Canvas canvas, float x, float top, float cellH,
			float blockTop, int cells, float labelBaseline, char label, int[] z,
			String filledGlyph, String emptyGlyph, int fillOverride)
	{
		final int def = z[0], cur = z[1], old = z[2];
		for (int i = 0; i < cells; i++)
		{
			// fraction measured from the bottom so small fractions = the
			// filled/current end (the bottom cell is the last to drain).
			float f = (cells - i - 0.5f) / cells;
			int fpm = (int) (f * 1000);
			int argb;
			boolean isEmpty = false;
			boolean isFill = false;
			if (fpm < def && fpm < old)
			{
				argb = z[3];
				isFill = true;
			}
			else if (fpm < def)
			{
				argb = z[4];
				isFill = true;
			}
			else if (fpm < cur)
				argb = z[5];
			else if (fpm < old)
				argb = z[6];
			else
			{
				argb = z[7];
				isEmpty = true;
			}
			if (fillOverride != 0 && isFill)
				argb = fillOverride;
			paint.setColor(argb | 0xFF000000);
			// -top shifts the glyph down into its cell band; cell 0 sits at the
			// block top (map top row) and cell `cells-1` just above the label.
			float baseline = blockTop + i * cellH - top;
			canvas.drawText(isEmpty ? emptyGlyph : filledGlyph, x, baseline, paint);
		}

		// Label on the map's last row, in the bar's default fill colour so H/M
		// read as their bar.
		paint.setColor((fillOverride != 0 ? fillOverride : z[3]) | 0xFF000000);
		canvas.drawText(String.valueOf(label), x, labelBaseline, paint);
	}
}
