package com.crawlmb.view;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

// A scroll container that pans its single child freely on BOTH axes at the
// same time. Nested ScrollView + HorizontalScrollView (wrapScrollable) lock to
// one axis per gesture; this one tracks the raw drag and moves both, so wide
// AND tall content (the info modal's monospace keyhelp) can be panned
// diagonally. Add exactly one child, sized WRAP_CONTENT.
public class TwoDScrollView extends FrameLayout
{
    private float lastX, lastY;
    private final int touchSlop;
    private boolean dragging;

    public TwoDScrollView(Context c)
    {
        super(c);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    // Measure the child at its natural (unconstrained) size on both axes so
    // there is overflow to scroll into. The container itself keeps the size
    // its own parent gives it (MATCH_PARENT within the padded card).
    @Override
    protected void measureChildWithMargins(View child, int parentWSpec,
            int wUsed, int parentHSpec, int hUsed)
    {
        child.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    private int maxScrollX()
    {
        if (getChildCount() == 0)
            return 0;
        int viewport = getWidth() - getPaddingLeft() - getPaddingRight();
        return Math.max(0, getChildAt(0).getMeasuredWidth() - viewport);
    }

    private int maxScrollY()
    {
        if (getChildCount() == 0)
            return 0;
        int viewport = getHeight() - getPaddingTop() - getPaddingBottom();
        return Math.max(0, getChildAt(0).getMeasuredHeight() - viewport);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev)
    {
        switch (ev.getActionMasked())
        {
        case MotionEvent.ACTION_DOWN:
            lastX = ev.getX();
            lastY = ev.getY();
            dragging = false;
            break;
        case MotionEvent.ACTION_MOVE:
            if (Math.abs(ev.getX() - lastX) > touchSlop
                    || Math.abs(ev.getY() - lastY) > touchSlop)
                dragging = true;
            break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev)
    {
        switch (ev.getActionMasked())
        {
        case MotionEvent.ACTION_DOWN:
            lastX = ev.getX();
            lastY = ev.getY();
            return true;
        case MotionEvent.ACTION_MOVE:
        {
            int nx = clamp(getScrollX() + Math.round(lastX - ev.getX()),
                    maxScrollX());
            int ny = clamp(getScrollY() + Math.round(lastY - ev.getY()),
                    maxScrollY());
            lastX = ev.getX();
            lastY = ev.getY();
            scrollTo(nx, ny);
            return true;
        }
        }
        return super.onTouchEvent(ev);
    }

    private static int clamp(int v, int max)
    {
        return Math.max(0, Math.min(max, v));
    }
}
