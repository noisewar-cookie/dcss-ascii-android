package com.crawlmb.view;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.crawlmb.IconConfig;
import com.crawlmb.R;

// Reusable modal window overlaid on the game UI: a scrim that swallows input,
// an inset card with a thin solid border and an "X" close button, and a
// pluggable content area. Dismissed by the X, by the Android BACK key (wired in
// GameActivity.onKeyDown), or programmatically. Both buttons (help / wiki) open
// their content through one instance of this shell.
public class ModalOverlayController
{
    public interface Callbacks
    {
        // Invoked after teardown, whatever the dismiss path.
        void onDismiss();
    }

    // Scroll modes for show(). NONE: content scrolls itself (e.g. a WebView).
    // BOTH: the shell pans the content on both axes at once (see TwoDScrollView).
    public static final int SCROLL_NONE = 0;
    public static final int SCROLL_BOTH = 2;

    private static final int SCRIM_COLOR = 0xB3000000; // ~70% black
    private static final int PANEL_BG = 0xFF0A0A0A;
    private static final int CLOSE_SIZE_DP = 18;
    private static final int CLOSE_MARGIN_DP = 2;
    private static final int CONTENT_PAD_DP = 8;

    private final Activity activity;
    private final RelativeLayout screenLayout;
    private final IconConfig cfg;
    private final Callbacks callbacks;
    private final float density;

    private FrameLayout modalRoot;
    private FrameLayout panel;
    private boolean active = false;

    public ModalOverlayController(Activity activity, RelativeLayout screenLayout,
            IconConfig cfg, Callbacks callbacks)
    {
        this.activity = activity;
        this.screenLayout = screenLayout;
        this.cfg = cfg;
        this.callbacks = callbacks;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    public boolean isActive()
    {
        return active;
    }

    // Show the given content in the modal. scrollMode selects how the content
    // pans inside the card: SCROLL_NONE (content scrolls itself, e.g. a
    // WebView) or SCROLL_BOTH (the shell pans both axes at once).
    public void show(View content, int scrollMode)
    {
        if (active)
            dismiss();
        active = true;
        hideSystemIme();
        buildUi(content, scrollMode);
    }

    public void dismiss()
    {
        if (!active)
            return;
        teardown();
        callbacks.onDismiss();
    }

    private void teardown()
    {
        active = false;
        if (modalRoot != null && modalRoot.getParent() instanceof ViewGroup)
            ((ViewGroup) modalRoot.getParent()).removeView(modalRoot);
        modalRoot = null;
        panel = null;
    }

    private void buildUi(View content, int scrollMode)
    {
        // Scrim: opaque enough to read the card, clickable+focusable so it
        // swallows any touch the card doesn't handle (reposition precedent).
        modalRoot = new FrameLayout(activity);
        modalRoot.setBackgroundColor(SCRIM_COLOR);
        modalRoot.setClickable(true);
        modalRoot.setFocusable(true);

        // Pad the card by the border width so the stroke stays visible on every
        // side even when the content (e.g. an opaque WebView) fills it.
        int borderPx = Math.max(1, Math.round(cfg.modalBorderWidthDp * density));
        panel = new FrameLayout(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL_BG);
        bg.setStroke(borderPx, cfg.modalBorderColor);
        panel.setBackground(bg);
        panel.setPadding(borderPx, borderPx, borderPx, borderPx);

        // Content fills the whole card. Scrollable (text) content gets a small
        // inset off the border; self-scrolling content (a WebView) brings its
        // own page padding, so it fills edge-to-edge.
        View body = scrollMode == SCROLL_BOTH
                ? wrapScrollable2D(content) : content;
        if (scrollMode != SCROLL_NONE)
        {
            int bpad = Math.round(CONTENT_PAD_DP * density);
            body.setPadding(bpad, bpad, bpad, bpad);
        }
        panel.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // The X overlays the content's top-right corner, added last so it draws
        // on top and wins the tap. A tight vector fills its box, so an equal
        // margin is an equal gap from the top and right borders.
        ImageView close = new ImageView(activity);
        close.setImageResource(R.drawable.ic_modal_close);
        close.setScaleType(ImageView.ScaleType.FIT_XY);
        close.setContentDescription("Close");
        close.setClickable(true);
        close.setOnClickListener(v -> dismiss());
        int closeSz = Math.round(CLOSE_SIZE_DP * density);
        int closeMargin = Math.round(CLOSE_MARGIN_DP * density);
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                closeSz, closeSz, Gravity.TOP | Gravity.END);
        closeLp.topMargin = closeMargin;
        closeLp.rightMargin = closeMargin;
        panel.addView(close, closeLp);

        modalRoot.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Inset the card by the configured percentage of the (already
        // safe-area-padded) scrim, on each layout pass.
        modalRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                applyInset());

        screenLayout.addView(modalRoot, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // Position the card inside the screen's safe area with equal margins on
    // every side. The scrim spans the full edge-to-edge window (the parent
    // consumes the dispatched insets, so query the root window directly). The
    // status bar is hidden app-wide, so the top inset is normally 0 and the card
    // reaches near the screen top; the nav bar — or its absence — sets the
    // bottom. Applying the same percentage of the safe-area size to all sides
    // keeps the top and bottom gaps symmetric on any screen ratio.
    private void applyInset()
    {
        if (panel == null || modalRoot == null)
            return;
        int w = modalRoot.getWidth();
        int h = modalRoot.getHeight();
        if (w <= 0 || h <= 0)
            return;

        int il = 0, it = 0, ir = 0, ib = 0;
        WindowInsetsCompat wi = ViewCompat.getRootWindowInsets(modalRoot);
        if (wi != null)
        {
            Insets bars = wi.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            il = bars.left;
            it = bars.top;
            ir = bars.right;
            ib = bars.bottom;
        }

        int usableW = w - il - ir;
        int usableH = h - it - ib;
        if (usableW <= 0 || usableH <= 0)
            return;
        int mx = Math.round(usableW * cfg.modalPaddingPercent / 100f);
        int my = Math.round(usableH * cfg.modalPaddingPercent / 100f);

        int left = il + mx, top = it + my, right = ir + mx, bottom = ib + my;
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (lp.leftMargin == left && lp.topMargin == top
                && lp.rightMargin == right && lp.bottomMargin == bottom)
            return;
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.rightMargin = right;
        lp.bottomMargin = bottom;
        panel.setLayoutParams(lp);
    }



    // Simultaneous vertical + horizontal panning (see TwoDScrollView), for wide
    // AND tall content that shouldn't be locked to one axis per gesture.
    private View wrapScrollable2D(View content)
    {
        TwoDScrollView t = new TwoDScrollView(activity);
        t.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    // The overlay can't cover the system soft keyboard (separate window), so
    // suppress it explicitly — same calls as the other overlay controllers.
    private void hideSystemIme()
    {
        activity.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null)
            imm.hideSoftInputFromWindow(screenLayout.getWindowToken(), 0);
    }
}
