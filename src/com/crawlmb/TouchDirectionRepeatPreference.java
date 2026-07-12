package com.crawlmb;

import android.content.Context;
import android.preference.EditTextPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

// Shows the current value as "<n>ms" in the widget column.
public class TouchDirectionRepeatPreference extends EditTextPreference
{
    public TouchDirectionRepeatPreference(Context context, AttributeSet attrs)
    {
        super(context, attrs);
        setWidgetLayoutResource(R.layout.preference_widget_value);
    }

    @Override
    protected void onBindView(View view)
    {
        super.onBindView(view);
        TextView valueView = (TextView) view.findViewById(R.id.pref_value);
        if (valueView != null)
        {
            String text = getText();
            valueView.setText(text == null || text.trim().isEmpty()
                    ? "" : text.trim() + "ms");
        }
    }

    // setText doesn't refresh the row; rebind so the widget updates.
    @Override
    public void setText(String text)
    {
        super.setText(text);
        notifyChanged();
    }
}
