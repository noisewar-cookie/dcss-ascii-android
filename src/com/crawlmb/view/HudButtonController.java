package com.crawlmb.view;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.crawlmb.IconConfig;
import com.crawlmb.R;

// Two on-screen shortcut buttons (help / wiki) overlaid on the right edge of
// the HUD stats panel. The container is a full-bleed, non-clickable FrameLayout
// added ABOVE DirectionalTouchView so the buttons win input over the touch
// overlay, while empty container area passes touches straight through to the
// game controls below.
//
// The buttons are re-anchored to the HUD's on-screen rect on every layout pass,
// so they travel with the HUD when it is repositioned (a reposition save
// rebuilds the whole screen, recreating this controller against the HUD's new
// slot). They hide when the HUD is not visible (menus), when either overlay
// editor is active, or when their pref toggle is off.
public class HudButtonController
{
    public interface Callbacks
    {
        boolean isHelpEnabled();
        boolean isWikiEnabled();
        // True while the reposition / grid overlay editors own the screen.
        boolean isOverlayActive();
        void onHelpTapped();
        void onWikiTapped();
    }

    // HUD region height in terminal rows (RegionRouter.HUD_START_ROW..HUD_END_ROW).
    private static final float HUD_ROWS = 9f;
    // Vertical anchors as a fraction of HUD height: the info button centers on
    // the Health/Magic bars (rows 2-3), the wiki button on the Noise/XL stat
    // rows below them (rows 4-5).
    private static final float HELP_CENTER_FRAC = 3f / 9f;
    private static final float WIKI_CENTER_FRAC = 5f / 9f;
    // Width/height of each drawable's tightened viewport (help 20x20, wiki
    // 24x20), so the box matches the glyph's aspect.
    private static final float HELP_ASPECT = 20f / 20f;
    private static final float WIKI_ASPECT = 24f / 20f;

    private final RelativeLayout root;
    private final RegionTermView hudView;
    private final IconConfig cfg;
    private final Callbacks cb;

    private final FrameLayout container;
    private final ImageView helpButton;
    private final ImageView wikiButton;
    private final int marginPx;
    private int lastSize = -1;

    public HudButtonController(Activity activity, RelativeLayout root,
            RegionTermView hudView, IconConfig cfg, Callbacks cb)
    {
        this.root = root;
        this.hudView = hudView;
        this.cfg = cfg;
        this.cb = cb;

        float density = activity.getResources().getDisplayMetrics().density;
        this.marginPx = Math.round(cfg.hudButtonMarginDp * density);

        container = new FrameLayout(activity);
        container.setLayoutParams(new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT));

        helpButton = makeButton(activity, R.drawable.ic_hud_help,
                "Help", v -> cb.onHelpTapped());
        wikiButton = makeButton(activity, R.drawable.ic_hud_wiki,
                "Wiki", v -> cb.onWikiTapped());
        container.addView(helpButton);
        container.addView(wikiButton);

        root.addView(container);

        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener()
                {
                    @Override
                    public void onGlobalLayout()
                    {
                        // Bail once our container has been detached by a rebuild.
                        if (container.getParent() == null)
                        {
                            root.getViewTreeObserver()
                                    .removeOnGlobalLayoutListener(this);
                            return;
                        }
                        layout();
                    }
                });
    }

    // Plain ImageView, not ImageButton: ImageButton keeps its default button
    // background's padding even after the background is cleared, which insets
    // the glyph well inside the box. FIT_XY (not FIT_CENTER) with a box sized to
    // the drawable's own aspect fills it exactly, so the touch box hugs the
    // glyph with no transparent dead margin.
    private ImageView makeButton(Activity a, int drawableRes, String desc,
            View.OnClickListener onClick)
    {
        ImageView b = new ImageView(a);
        b.setImageResource(drawableRes);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(0, 0, 0, 0);
        b.setScaleType(ImageView.ScaleType.FIT_XY);
        b.setAlpha(cfg.hudButtonOpacity);
        b.setContentDescription(desc);
        b.setFocusable(false);
        b.setClickable(true);
        b.setVisibility(View.GONE);
        b.setOnClickListener(onClick);
        return b;
    }

    private void layout()
    {
        int h = hudView.getHeight();
        boolean overlay = cb.isOverlayActive();
        boolean base = h > 0 && hudView.isShown() && !overlay;
        if (!base)
        {
            helpButton.setVisibility(View.GONE);
            wikiButton.setVisibility(View.GONE);
            return;
        }

        int[] hudLoc = new int[2];
        hudView.getLocationInWindow(hudLoc);
        int[] rootLoc = new int[2];
        root.getLocationInWindow(rootLoc);
        int hudLeft = hudLoc[0] - rootLoc[0];
        int hudTop = hudLoc[1] - rootLoc[1];
        // Anchor to where the HUD text ends, not the MATCH_PARENT view edge, so
        // the buttons sit in the gutter beside the stats.
        int contentRight = hudLeft + hudView.getContentRightX();
        int viewRight = hudLeft + hudView.getWidth();

        float rowH = h / HUD_ROWS;
        // Icon height = configured HUD-row span; width follows each drawable's
        // aspect so the box is tight to the glyph. Clamp height to the band.
        int size = Math.min(Math.round(cfg.hudButtonRowSpan * rowH), h / 2);
        // The original icons filled 20 of 24 viewport units inside their box;
        // the viewports are now tightened, so scale by the same ratio to keep
        // the visible glyph at its established size.
        size = Math.round(size * 20f / 24f);
        int helpW = Math.round(size * HELP_ASPECT);
        int wikiW = Math.round(size * WIKI_ASPECT);
        if (size != lastSize)
        {
            applySize(helpButton, helpW, size);
            applySize(wikiButton, wikiW, size);
            lastSize = size;
        }

        // Anchor the widest icon just right of the content, clamped inside the
        // screen's right safe edge, then horizontally center both on that
        // column so the narrower icon aligns with the wider one.
        int maxW = Math.max(helpW, wikiW);
        int leftAnchor = Math.min(contentRight + marginPx,
                viewRight - marginPx - maxW);
        int centerX = leftAnchor + maxW / 2;
        place(helpButton, centerX - helpW / 2,
                Math.round(hudTop + h * HELP_CENTER_FRAC - size / 2f),
                cb.isHelpEnabled());
        place(wikiButton, centerX - wikiW / 2,
                Math.round(hudTop + h * WIKI_CENTER_FRAC - size / 2f),
                cb.isWikiEnabled());
    }

    private static void applySize(View v, int w, int h)
    {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null)
            lp = new FrameLayout.LayoutParams(w, h);
        else
        {
            lp.width = w;
            lp.height = h;
        }
        v.setLayoutParams(lp);
    }

    private static void place(View v, int x, int y, boolean enabled)
    {
        if (!enabled)
        {
            v.setVisibility(View.GONE);
            return;
        }
        v.setX(x);
        v.setY(y);
        v.setVisibility(View.VISIBLE);
    }
}
