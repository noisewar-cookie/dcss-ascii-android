package com.crawlmb.view;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.crawlmb.IconConfig;
import com.crawlmb.R;

// Settings cog docked to the message panel's bottom-right corner; opens the
// app preferences without the two-finger long-press. Same layering as
// HudButtonController: a full-bleed, non-clickable container added ABOVE
// DirectionalTouchView so the cog wins taps over the touch overlay.
public class SettingsButtonController
{
    public interface Callbacks
    {
        boolean isEnabled();
        // True while an overlay editor or modal owns the screen.
        boolean isOverlayActive();
        void onTapped();
    }

    private final RelativeLayout root;
    private final View msgView;
    private final Callbacks cb;
    private final FrameLayout container;
    private final ImageView button;
    private final int marginPx;

    public SettingsButtonController(Activity activity, RelativeLayout root,
            View msgView, IconConfig cfg, Callbacks cb)
    {
        this.root = root;
        this.msgView = msgView;
        this.cb = cb;

        float density = activity.getResources().getDisplayMetrics().density;
        // Default (size 0): 2/3 of one keyboard key, i.e. of the smaller of
        // key_height and the 10%-of-width keyWidth the keyboard layouts use.
        int sizePx = cfg.settingsButtonSizeDp > 0
                ? Math.round(cfg.settingsButtonSizeDp * density)
                : Math.min(activity.getResources()
                        .getDimensionPixelSize(R.dimen.key_height),
                        activity.getResources().getDisplayMetrics()
                                .widthPixels / 10) * 2 / 3;
        this.marginPx = Math.round(cfg.settingsButtonMarginDp * density);

        container = new FrameLayout(activity);
        container.setLayoutParams(new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT));

        button = new ImageView(activity);
        button.setImageResource(R.drawable.ic_settings_cog);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(0, 0, 0, 0);
        button.setScaleType(ImageView.ScaleType.FIT_XY);
        button.setAlpha(cfg.hudButtonOpacity);
        button.setContentDescription("Settings");
        button.setFocusable(false);
        button.setClickable(true);
        button.setVisibility(View.GONE);
        button.setOnClickListener(v -> cb.onTapped());
        container.addView(button, new FrameLayout.LayoutParams(sizePx, sizePx));

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

    private void layout()
    {
        if (msgView.getHeight() <= 0 || !msgView.isShown()
                || cb.isOverlayActive() || !cb.isEnabled())
        {
            button.setVisibility(View.GONE);
            return;
        }

        int[] msgLoc = new int[2];
        msgView.getLocationInWindow(msgLoc);
        int[] rootLoc = new int[2];
        root.getLocationInWindow(rootLoc);
        int right = msgLoc[0] - rootLoc[0] + msgView.getWidth();
        int bottom = msgLoc[1] - rootLoc[1] + msgView.getHeight();
        button.setX(right - marginPx - button.getLayoutParams().width);
        button.setY(bottom - marginPx - button.getLayoutParams().height);
        button.setVisibility(View.VISIBLE);
    }
}
