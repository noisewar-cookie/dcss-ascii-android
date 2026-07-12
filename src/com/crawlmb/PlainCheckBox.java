package com.crawlmb;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.CheckBox;

// CheckBox that ignores pressed/selected/activated. The ListView pushes those
// onto the widget on tap and the classic theme renders them orange; dropping
// them keeps the green check. The row handles taps, so this is harmless.
public class PlainCheckBox extends CheckBox
{
    public PlainCheckBox(Context context, AttributeSet attrs)
    {
        super(context, attrs);
    }

    @Override
    public void setPressed(boolean pressed)
    {
    }

    @Override
    public void setSelected(boolean selected)
    {
    }

    @Override
    public void setActivated(boolean activated)
    {
    }
}
