// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import dev.juruc.pixelvoice.KeyboardDictation;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.settings.Settings;

/** Status and Stop/Cancel row for local dictation, shown above the strip while the keys stay usable. */
public final class DictationControlsView extends LinearLayout {
    private final Rect mVisibleRect = new Rect();
    private KeyboardDictation mDictation;
    private TextView mStatus;
    private boolean mAwaitingDraw;

    private final ViewTreeObserver.OnPreDrawListener mPreDrawListener = () -> {
        if (mAwaitingDraw && isVisibleOnScreen()) {
            mAwaitingDraw = false;
            // Posted so the frame containing this row is drawn before capture may start.
            post(mDictation::controlsDrawn);
        }
        return true;
    };

    public DictationControlsView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mStatus = findViewById(R.id.dictation_status);
    }

    void setDictation(@NonNull final KeyboardDictation dictation) {
        mDictation = dictation;
        findViewById(R.id.dictation_stop).setOnClickListener(view -> dictation.stop());
        findViewById(R.id.dictation_copy).setOnClickListener(view -> dictation.copy());
        findViewById(R.id.dictation_edit).setOnClickListener(view -> dictation.edit());
        findViewById(R.id.dictation_setup).setOnClickListener(view -> dictation.setup());
        findViewById(R.id.dictation_cancel).setOnClickListener(view -> dictation.cancel());
    }

    void render(@NonNull final KeyboardDictation.Controls controls) {
        final KeyboardDictation.Status status = controls.status();
        if (status == KeyboardDictation.Status.IDLE) {
            mAwaitingDraw = false;
            setVisibility(GONE);
            return;
        }
        mStatus.setText(switch (status) {
            case IDLE, PREPARING -> dev.juruc.pixelvoice.R.string.inline_preparing;
            case LISTENING -> dev.juruc.pixelvoice.R.string.inline_listening;
            case PROCESSING -> dev.juruc.pixelvoice.R.string.inline_processing;
            case NOT_INSERTED -> dev.juruc.pixelvoice.R.string.inline_not_sent;
            case CHECK_INSERTION -> dev.juruc.pixelvoice.R.string.inline_unknown;
            case SETUP_NEEDED -> dev.juruc.pixelvoice.R.string.inline_setup_needed;
        });
        final boolean recovery = status == KeyboardDictation.Status.NOT_INSERTED
                || status == KeyboardDictation.Status.CHECK_INSERTION;
        show(R.id.dictation_stop, status == KeyboardDictation.Status.LISTENING);
        show(R.id.dictation_copy, recovery);
        show(R.id.dictation_edit, recovery);
        show(R.id.dictation_setup, status == KeyboardDictation.Status.SETUP_NEEDED);
        if (status == KeyboardDictation.Status.PREPARING) {
            mAwaitingDraw = true;
            invalidate();
        }
        if (getVisibility() != VISIBLE) {
            final int textColor = Settings.getValues().mColors.get(ColorType.KEY_TEXT);
            for (int index = 0; index < getChildCount(); ++index) {
                ((TextView) getChildAt(index)).setTextColor(textColor);
            }
            mAwaitingDraw = true;
            setVisibility(VISIBLE);
        }
    }

    boolean isVisibleOnScreen() {
        return isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE
                && getGlobalVisibleRect(mVisibleRect) && !mVisibleRect.isEmpty();
    }

    private void show(final int id, final boolean visible) {
        findViewById(id).setVisibility(visible ? VISIBLE : GONE);
    }

    private void retireWhenHidden(final int visibility) {
        if (visibility != VISIBLE && mDictation != null) {
            mDictation.editorSurfaceChanged();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(mPreDrawListener);
        retireWhenHidden(GONE);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull final View changedView, final int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        retireWhenHidden(visibility);
    }

    @Override
    protected void onWindowVisibilityChanged(final int visibility) {
        super.onWindowVisibilityChanged(visibility);
        retireWhenHidden(visibility);
    }
}
