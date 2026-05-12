package com.crawlmb.view;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;

import java.util.HashSet;

// App-only static panel rendered below the main menu (between fullView and
// the virtual keyboard). Reuses RegionTermView's font auto-sizing, scaling,
// scroll, and bitmap pipeline, but its source of truth is a fixed String[]
// loaded from assets/quick_controls.txt — DCSS never writes into it via
// drawPoint. We re-render the stored content into the bitmap on every
// onMeasure pass so that font-scale changes (which recreate the bitmap
// blank in the super class) don't leave the panel empty.
public class QuickControlsView extends RegionTermView
{
    private static final String TAG = "QuickControlsView";

    private String[] lines;
    private int fontColor = Color.WHITE;
    private final HashSet<Character> loggedMissing = new HashSet<>();

    public QuickControlsView(Context context, int rows, int cols)
    {
        super(context, 0, 0, rows, cols);
    }

    public void setLines(String[] lines)
    {
        this.lines = lines;
        loggedMissing.clear();
        if (canvas != null)
            renderContent();
    }

    public void setFontColor(int color)
    {
        this.fontColor = color;
        if (canvas != null)
            renderContent();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
    {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // RegionTermView.onMeasure may have swapped in a fresh blank bitmap;
        // repaint the static content so the panel isn't empty after a
        // layout-driven resize or font scale change.
        if (lines != null)
            renderContent();
    }

    private void renderContent()
    {
        if (canvas == null || lines == null)
            return;
        clear();
        checkGlyphSupport();
        for (int r = 0; r < lines.length; r++)
        {
            String line = lines[r];
            int len = line.length();
            for (int c = 0; c < len; c++)
            {
                char ch = line.charAt(c);
                if (ch == ' ')
                    continue;
                drawPoint(r, c, ch, fontColor, Color.BLACK, false);
            }
        }
        postInvalidate();
    }

    // Paint.hasGlyph requires API 23. Below that we skip the check — the
    // worst case is the renderer drawing a tofu box for unsupported chars,
    // which is exactly the diagnostic the user wants surfaced anyway.
    private void checkGlyphSupport()
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return;
        if (lines == null || fore == null)
            return;
        for (String line : lines)
        {
            for (int i = 0; i < line.length(); i++)
            {
                char ch = line.charAt(i);
                if (ch < 0x80 || loggedMissing.contains(ch))
                    continue;
                if (!fore.hasGlyph(String.valueOf(ch)))
                {
                    Log.w(TAG, String.format(
                            "Glyph U+%04X not present in font", (int) ch));
                    loggedMissing.add(ch);
                }
            }
        }
    }
}
