package com.crawlmb.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.Toast;

import com.crawlmb.Preferences;
import com.crawlmb.R;
import com.crawlmb.WindowCompatAdapter;
import com.crawlmb.keyboard.CrawlKeyboardWrapper;
import com.crawlmb.keyboard.CrawlKeyboardWrapper.SpecialKey;
import com.crawlmb.keyboard.KeyboardLayoutSpinnerAdapter;
import com.crawlmb.keylistener.KeyListener;

/**
 * Created by michael on 25/03/15.
 */
public class CustomKeyboardActivity extends Activity implements KeyListener, AdapterView.OnItemSelectedListener {

    private Spinner layoutSpinner;
    private Button deleteLayout;
    private CrawlKeyboardWrapper virtualKeyboard;
    private Button newLayout;
    private KeyboardLayoutSpinnerAdapter adapter;
    private int changingKey = -1;
    private int changingKeyIndex = -1;
    // Pending choices in the binding dialog, committed on Accept.
    // Re-tapping a field keeps them; only the display text clears.
    private int pendingSpecialCode = -1;    // -1 = none
    private String pendingMainChar = null;
    private String pendingLongpress = null;
    // Ctrl ticks; only letters can be Ctrl chords, always off on dialog open
    private boolean pendingMainCtrl = false;
    private boolean pendingLongpressCtrl = false;
    private boolean settingFieldText = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompatAdapter.applyEdgeToEdge(this);

        LayoutInflater layoutInflater = LayoutInflater.from(this);
        RelativeLayout parentLayout = (RelativeLayout) layoutInflater.inflate(R.layout.custom_keyboard, null);
        setContentView(parentLayout);
        WindowCompatAdapter.padRootForSystemBars(parentLayout);

        // Add keyboard
        virtualKeyboard = new CrawlKeyboardWrapper(this, this);
        // Show longpress alt assignments here even if the user disabled them
        virtualKeyboard.virtualKeyboardView.setForceAltEnabled(true);
        parentLayout.addView(virtualKeyboard.virtualKeyboardView);

        View buttons = layoutInflater.inflate(R.layout.custom_keyboard_options, null);

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.addRule(RelativeLayout.ABOVE, R.id.keyboard);
        parentLayout.addView(buttons, params);

        layoutSpinner = (Spinner) buttons.findViewById(R.id.layoutSpinner);
        adapter = new KeyboardLayoutSpinnerAdapter();
        layoutSpinner.setAdapter(adapter);

        layoutSpinner.setOnItemSelectedListener(this);

        deleteLayout = (Button) findViewById(R.id.deleteLayout);

        newLayout = (Button) findViewById(R.id.newLayout);
    }

    @Override
    protected void onResume() {
        super.onResume();

        setViews();
    }

    private void setViews() {
        adapter.notifyDataSetChanged();

        int currentKeyboardLayout = Preferences.getCurrentKeyboardLayout();
        int keyboardCount = Preferences.getLayoutCount();

        virtualKeyboard.virtualKeyboardView.invalidateAllKeys();

        layoutSpinner.setSelection(currentKeyboardLayout);

        deleteLayout.setEnabled(currentKeyboardLayout > 0);

        newLayout.setEnabled(keyboardCount == 0);
    }


    @Override
    public void addKey(int key, int keyIndex) {
        if (keyIndex < 0){
            return;
        }
        changingKey = key;
        changingKeyIndex = keyIndex;
        pendingSpecialCode = -1;
        pendingMainChar = null;
        pendingLongpress = null;
        pendingMainCtrl = false;
        pendingLongpressCtrl = false;
        Dialog characterBindingDialog = createCharacterBindingDialog();
        characterBindingDialog.show();
    }

    @Override
    public void addDirectionKey(int key) {
        // I don't think I need this right now
    }

    // We don't really need this right now, but might be useful if we want multiple layouts.
    // My ideal solution would involve storing string sets in preferences, but that's only possible
    // on API 11 and above, so maybe once people stop using Gingerbread
    public void onNewLayoutClick(View v){
        Preferences.addNewKeyboardLayout();
        setViews();
    }

    public void onDeleteLayoutClick(View v){
        final int currentKeyboardLayout = Preferences.getCurrentKeyboardLayout();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.delete_layout);
        builder.setMessage(R.string.delete_layout_message);
        builder.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                Preferences.deleteLayout(CustomKeyboardActivity.this, currentKeyboardLayout);
                setViews();
            }
        });
        builder.setNegativeButton(android.R.string.no, null);
        builder.show();
    }

    private Dialog createCharacterBindingDialog()
    {
        final Dialog characterBindingDialog = new Dialog(this);
        characterBindingDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        characterBindingDialog.setContentView(R.layout.character_binding_dialog);
        // Top-anchor the window and ignore the IME so opening/closing the
        // soft keyboard never shifts the dialog around
        Window window = characterBindingDialog.getWindow();
        if (window != null)
        {
            window.setGravity(Gravity.TOP);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.y = Math.round(40 * getResources().getDisplayMetrics().density);
            window.setAttributes(attrs);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        final EditText characterField = (EditText) characterBindingDialog.findViewById(R.id.character_field);
        final EditText longpressField = (EditText) characterBindingDialog.findViewById(R.id.longpress_field);
        final ImageView mainCtrlTick = (ImageView) characterBindingDialog.findViewById(R.id.character_ctrl_tick);
        final ImageView altCtrlTick = (ImageView) characterBindingDialog.findViewById(R.id.longpress_ctrl_tick);
        characterField.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count)
            {
                //Not needed
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after)
            {
                //Not needed
            }

            @Override
            public void afterTextChanged(Editable s)
            {
                if (settingFieldText)
                {
                    return;
                }
                // A typed character overrides any pending special key
                pendingSpecialCode = -1;
                pendingMainChar = s.length() > 0 ? s.toString() : null;
                if (pendingMainChar != null && pendingMainCtrl)
                {
                    if (isCtrlPairable(pendingMainChar.charAt(0)))
                    {
                        // Editing the Editable inside afterTextChanged fights
                        // the IME; post the "Ctrl + X" display rewrite instead
                        characterField.post(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                setFieldText(characterField, ctrlDisplay(pendingMainChar));
                            }
                        });
                    }
                    else
                    {
                        pendingMainCtrl = false;
                    }
                }
                updateCtrlTick(mainCtrlTick, pendingMainCtrl, isMainCtrlAllowed());
                updateFieldTextSize(characterField);
                // Focus parks on the focusable dialog root, so the next
                // field tap is a fresh focus gain
                characterField.clearFocus();
                InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(characterField.getWindowToken(), 0);
            }
        });
        characterField.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean isFocussed) {
                // Clear on tap so typing never replaces text (the IME's
                // composing state fights that); pending moves to the hint
                if (isFocussed){
                    clearFieldAndShowIme(characterField);
                }
            }
        });
        // Hints show the current bindings; typed text is the pending change
        SpecialKey currentSpecial = SpecialKey.getCodeToKeyMap().get(changingKey);
        characterField.setHint(currentSpecial != null
                ? currentSpecial.toString() : formatCode(changingKey));
        int currentLongpress = Preferences.getLongpressInLayout(this, virtualKeyboard.getCurrentKeyboardType(), changingKeyIndex);
        String altHint = "None";
        if (currentLongpress != -1){
            altHint = formatCode(currentLongpress);
        }else{
            CharSequence defaultAlt = virtualKeyboard.virtualKeyboardView.getKeyboard()
                    .getKeys().get(changingKeyIndex).popupCharacters;
            if (defaultAlt != null && defaultAlt.length() > 0){
                altHint = String.valueOf(defaultAlt.charAt(0));
            }
        }
        longpressField.setHint(altHint);
        updateFieldTextSize(characterField);
        updateFieldTextSize(longpressField);
        updateCtrlTick(mainCtrlTick, false, true);
        updateCtrlTick(altCtrlTick, false, true);
        mainCtrlTick.setOnClickListener(new OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                pendingMainCtrl = !pendingMainCtrl;
                updateCtrlTick(mainCtrlTick, pendingMainCtrl, isMainCtrlAllowed());
                if (pendingMainChar != null)
                {
                    showPendingDisplay(characterField, pendingMainCtrl
                            ? ctrlDisplay(pendingMainChar) : pendingMainChar);
                }
            }
        });
        altCtrlTick.setOnClickListener(new OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                pendingLongpressCtrl = !pendingLongpressCtrl;
                updateCtrlTick(altCtrlTick, pendingLongpressCtrl, isAltCtrlAllowed());
                if (pendingLongpress != null)
                {
                    showPendingDisplay(longpressField, pendingLongpressCtrl
                            ? ctrlDisplay(pendingLongpress) : pendingLongpress);
                }
            }
        });
        longpressField.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count)
            {
                //Not needed
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after)
            {
                //Not needed
            }

            @Override
            public void afterTextChanged(Editable s)
            {
                if (settingFieldText)
                {
                    return;
                }
                pendingLongpress = s.length() > 0 ? s.toString() : null;
                if (pendingLongpress != null && pendingLongpressCtrl)
                {
                    if (isCtrlPairable(pendingLongpress.charAt(0)))
                    {
                        longpressField.post(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                setFieldText(longpressField, ctrlDisplay(pendingLongpress));
                            }
                        });
                    }
                    else
                    {
                        pendingLongpressCtrl = false;
                    }
                }
                updateCtrlTick(altCtrlTick, pendingLongpressCtrl, isAltCtrlAllowed());
                updateFieldTextSize(longpressField);
                longpressField.clearFocus();
                InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(longpressField.getWindowToken(), 0);
            }
        });
        longpressField.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean isFocussed) {
                if (isFocussed){
                    clearFieldAndShowIme(longpressField);
                }
            }
        });
        Button positiveButton = (Button) characterBindingDialog.findViewById(R.id.positiveButton);
        positiveButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                boolean mainSet = pendingSpecialCode != -1 || pendingMainChar != null;
                if (!mainSet && pendingLongpress == null)
                {
                    Toast.makeText(CustomKeyboardActivity.this, R.string.please_select_a_character, Toast.LENGTH_SHORT).show();
                    return;
                }

                String label = null;
                if (pendingSpecialCode != -1)
                {
                    label = SpecialKey.getCodeToKeyMap().get(pendingSpecialCode).toString();
                    Preferences.addKeybindingToLayout(v.getContext(), virtualKeyboard.getCurrentKeyboardType(), changingKeyIndex, pendingSpecialCode, null);
                }
                else if (pendingMainChar != null)
                {
                    char mainChar = pendingMainChar.charAt(0);
                    if (pendingMainCtrl && isCtrlPairable(mainChar))
                    {
                        // Key caps use compact lowercase ^x (fits better);
                        // dialog fields keep "Ctrl + X"
                        label = ctrlDisplay(pendingMainChar);
                        Preferences.addKeybindingToLayout(v.getContext(), virtualKeyboard.getCurrentKeyboardType(), changingKeyIndex,
                                Character.toLowerCase(mainChar) - 'a' + 1,
                                "^" + Character.toLowerCase(mainChar));
                    }
                    else
                    {
                        label = pendingMainChar;
                        Preferences.addKeybindingToLayout(v.getContext(), virtualKeyboard.getCurrentKeyboardType(), changingKeyIndex, mainChar, pendingMainChar);
                    }
                }

                // No pending = no change; a longpress is only removed by Revert
                String longpressDisplay = null;
                if (pendingLongpress != null)
                {
                    char altChar = pendingLongpress.charAt(0);
                    int altCode = altChar;
                    longpressDisplay = pendingLongpress;
                    if (pendingLongpressCtrl && isCtrlPairable(altChar))
                    {
                        altCode = Character.toLowerCase(altChar) - 'a' + 1;
                        longpressDisplay = ctrlDisplay(pendingLongpress);
                    }
                    Preferences.setLongpressInLayout(v.getContext(), virtualKeyboard.getCurrentKeyboardType(), changingKeyIndex, altCode);
                }

                if (Preferences.getLayoutCount() == 0){
                    Preferences.setCustomLayoutCount(1);
                }
                Preferences.setCurrentKeyboardLayout(1);
                characterBindingDialog.dismiss();
                setViews();
                if (mainSet)
                {
                    Toast.makeText(v.getContext(), "Set character " + formatCode(changingKey) + " to " + label, Toast.LENGTH_SHORT).show();
                }
                else
                {
                    Toast.makeText(v.getContext(), "Set longpress for " + formatCode(changingKey) + " to " + longpressDisplay, Toast.LENGTH_SHORT).show();
                }

            }
        });
        Button revertButton = (Button) characterBindingDialog.findViewById(R.id.revertButton);
        revertButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Preferences.clearKeybindingInLayout(CustomKeyboardActivity.this, virtualKeyboard.getCurrentKeyboardType(), changingKeyIndex);
                characterBindingDialog.dismiss();
                Toast.makeText(view.getContext(), "Reverted character", Toast.LENGTH_LONG).show();
                setViews();
            }
        });
        Button negativeButton = (Button) characterBindingDialog.findViewById(R.id.negativeButton);
        negativeButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                characterBindingDialog.dismiss();
            }
        });
        Button specialButton = (Button) characterBindingDialog.findViewById(R.id.specialButton);
        specialButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showGetSpecialCharacterDialog(characterField, mainCtrlTick);
            }
        });
        //TODO: Have a "revert to default" button


        return characterBindingDialog;
    }

    private void showGetSpecialCharacterDialog(final EditText characterField, final ImageView mainCtrlTick)
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setAdapter(new ArrayAdapter<SpecialKey>(getBaseContext(), R.layout.simple_list_item_black, SpecialKey.values()), new DialogInterface.OnClickListener()
        {
            public void onClick(DialogInterface dialog, int which)
            {
                SpecialKey specialKey = SpecialKey.values()[which];
                pendingSpecialCode = specialKey.getCode();
                pendingMainChar = null;
                // Specials can't be Ctrl chords
                pendingMainCtrl = false;
                updateCtrlTick(mainCtrlTick, false, false);
                setFieldText(characterField, specialKey.toString());
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.setTitle("Custom Layout");
        builder.show();
    }

    // Clearing text during the focus-granting touch makes TextView ignore
    // the tap's ACTION_UP — the step that shows the IME — so defer the clear
    // and request the IME explicitly.
    private void clearFieldAndShowIme(final EditText field)
    {
        field.post(new Runnable() {
            @Override
            public void run() {
                // The pending choice stays visible as the hint
                if (field.getText().length() > 0){
                    field.setHint(field.getText().toString());
                }
                setFieldText(field, "");
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(field, 0);
            }
        });
    }

    // Set field text programmatically, bypassing the maxLength=1 filter
    // (special key names and "Ctrl + X" are longer) and the user-typing
    // watcher.
    private void setFieldText(EditText field, String text)
    {
        settingFieldText = true;
        InputFilter[] filters = field.getFilters();
        field.setFilters(new InputFilter[0]);
        field.setText(text);
        field.setFilters(filters);
        settingFieldText = false;
        updateFieldTextSize(field);
    }

    // Reflect the pending value on whichever surface is visible: rewrite
    // the text if present, otherwise the hint. Writing text into a
    // focused, cleared field fights the IME and strands characters.
    private void showPendingDisplay(EditText field, String display)
    {
        if (field.getText().length() > 0)
        {
            setFieldText(field, display);
        }
        else
        {
            field.setHint(display);
            updateFieldTextSize(field);
        }
    }

    // Multi-char content (special key names, "Ctrl + X") shrinks so the
    // fields stay compact
    private void updateFieldTextSize(EditText field)
    {
        CharSequence visible = field.getText().length() > 0 ? field.getText() : field.getHint();
        boolean small = visible != null && visible.length() > 1;
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, small ? 15 : 22);
    }

    private void updateCtrlTick(ImageView tick, boolean ticked, boolean enabled)
    {
        tick.setSelected(ticked && enabled);
        tick.setEnabled(enabled);
        tick.setAlpha(enabled ? 1f : 0.35f);
    }

    // Console crawl only defines Ctrl chords for letters
    private static boolean isCtrlPairable(char c)
    {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private boolean isMainCtrlAllowed()
    {
        if (pendingSpecialCode != -1)
        {
            return false;
        }
        return pendingMainChar == null || isCtrlPairable(pendingMainChar.charAt(0));
    }

    private boolean isAltCtrlAllowed()
    {
        return pendingLongpress == null || isCtrlPairable(pendingLongpress.charAt(0));
    }

    private static String ctrlDisplay(String ch)
    {
        return "Ctrl + " + Character.toUpperCase(ch.charAt(0));
    }

    // Display form of a stored key code: control codes 1-26 as "Ctrl + X"
    private static String formatCode(int code)
    {
        if (code >= 1 && code <= 26)
        {
            return "Ctrl + " + (char) ('A' + code - 1);
        }
        return String.valueOf((char) code);
    }

    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
        Preferences.setCurrentKeyboardLayout(position);
        setViews();
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {
        // I don't think we need to do anything here
    }
}
