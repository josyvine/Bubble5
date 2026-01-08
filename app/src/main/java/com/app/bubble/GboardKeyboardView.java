package com.app.bubble;

import android.content.Context;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.view.MotionEvent;

public class GboardKeyboardView extends KeyboardView {

    public GboardKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GboardKeyboardView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public boolean onTouchEvent(MotionEvent me) {
        // 1. Let the standard keyboard handle the touch (drawing, highlighting, etc.)
        boolean result = super.onTouchEvent(me);

        // 2. INTERCEPT: If the user lifts their finger (ACTION_UP), force the popup to close.
        // This ensures the popup never stays open after release.
        if (me.getAction() == MotionEvent.ACTION_UP) {
            closing(); // This method is built-in to dismiss the popup
        }

        return result;
    }
}