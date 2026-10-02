package dev.juruc.pixelvoice;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

public final class KeyboardTestActivity extends Activity {
    static final String FIRST_EDITOR = "Keyboard test first editor";
    static final String SECOND_EDITOR = "Keyboard test second editor";
    static final String ORIGINAL_TEXT = "Original: ";
    static final String RESTART_EDITOR = "Restart keyboard test editor";

    private EditText firstEditor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout fields = new LinearLayout(this);
        fields.setBackgroundColor(Color.WHITE);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(24, 24, 24, 24);
        firstEditor = editor(FIRST_EDITOR);
        fields.addView(firstEditor);
        fields.addView(editor(SECOND_EDITOR));
        Button restart = new Button(this);
        restart.setText(RESTART_EDITOR);
        restart.setContentDescription(RESTART_EDITOR);
        restart.setFocusable(false);
        restart.setOnClickListener(view -> getSystemService(InputMethodManager.class).restartInput(firstEditor));
        fields.addView(restart);
        setContentView(fields);
        firstEditor.requestFocus();
        firstEditor.setSelection(firstEditor.length());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && firstEditor != null) {
            View focused = getCurrentFocus();
            if (focused != null) {
                focused.post(() -> getSystemService(InputMethodManager.class).showSoftInput(focused, 0));
            }
        }
    }

    private EditText editor(String description) {
        EditText editor = new EditText(this);
        editor.setContentDescription(description);
        editor.setSingleLine(false);
        editor.setText(ORIGINAL_TEXT);
        editor.setTextColor(Color.BLACK);
        editor.setTextSize(20);
        editor.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return editor;
    }
}
