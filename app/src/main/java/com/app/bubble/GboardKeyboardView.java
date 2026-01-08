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
        // 1. Let the standard keyboard process the touch (highlighting, selecting key)
        boolean result = super.onTouchEvent(me);

        // 2. INTERCEPT: If the user lifts their finger (ACTION_UP)
        if (me.getAction() == MotionEvent.ACTION_UP) {
            // FIX: We use 'post' to delay the closing slightly. 
            // This ensures the "Key Pressed" event fires BEFORE we kill the popup.
            post(new Runnable() {
                @Override
                public void run() {
                    closing(); 
                }
            });
        }

        return result;
    }
}