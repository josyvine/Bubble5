package com.app.bubble;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class TwoLineOverlayService extends Service {

    private WindowManager windowManager;

    // Window 1: The Lines (Full Screen)
    private View linesView;
    private WindowManager.LayoutParams linesParams;
    private View lineTop, lineBottom;
    private ImageView handleTop, handleBottom;
    private TextView helperText;

    // Window 2: The Controls (Bottom Only)
    private View controlsView;
    private WindowManager.LayoutParams controlsParams;
    private Button btnAdd, btnDone;
    private ImageButton btnClose;

    private int screenHeight;

    // For Touch Logic
    private View activeDragView = null;
    private View activeHandleView = null;
    private float initialTouchY, initialViewY;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        screenHeight = getResources().getDisplayMetrics().heightPixels;

        setupWindows();
    }

    private void setupWindows() {
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;

        LayoutInflater inflater = LayoutInflater.from(this);

        // --- SETUP LINES WINDOW (Full Screen) ---
        // This window handles the lines and the touch gestures to move them.
        linesView = inflater.inflate(R.layout.layout_two_line_overlay, null);
        
        // Hide the control buttons in this layer (they are in the controls window)
        linesView.findViewById(R.id.btn_add).setVisibility(View.GONE);
        linesView.findViewById(R.id.btn_done).setVisibility(View.GONE);
        linesView.findViewById(R.id.btn_close).setVisibility(View.GONE);
        
        lineTop = linesView.findViewById(R.id.line_top);
        handleTop = linesView.findViewById(R.id.handle_top);
        lineBottom = linesView.findViewById(R.id.line_bottom);
        handleBottom = linesView.findViewById(R.id.handle_bottom);
        helperText = linesView.findViewById(R.id.helper_text);

        linesParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                // FIX: REMOVED FLAG_SECURE (Fixes Black Screen)
                // FLAG_NOT_FOCUSABLE allow keys to go to app behind
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, 
                PixelFormat.TRANSLUCENT
        );
        windowManager.addView(linesView, linesParams);

        // --- SETUP CONTROLS WINDOW (Bottom) ---
        // This window holds the buttons. It is small so it doesn't block touches.
        controlsView = inflater.inflate(R.layout.layout_two_line_overlay, null);
        
        // Hide the lines in this layer
        controlsView.findViewById(R.id.line_top).setVisibility(View.GONE);
        controlsView.findViewById(R.id.handle_top).setVisibility(View.GONE);
        controlsView.findViewById(R.id.line_bottom).setVisibility(View.GONE);
        controlsView.findViewById(R.id.handle_bottom).setVisibility(View.GONE);
        controlsView.findViewById(R.id.helper_text).setVisibility(View.GONE);

        btnAdd = controlsView.findViewById(R.id.btn_add);
        btnDone = controlsView.findViewById(R.id.btn_done);
        btnClose = controlsView.findViewById(R.id.btn_close);

        controlsParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // FIX: REMOVED FLAG_SECURE
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, 
                PixelFormat.TRANSLUCENT
        );
        controlsParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        controlsParams.y = 50; // Margin from bottom
        windowManager.addView(controlsView, controlsParams);

        setupTouchListeners();
        setupControlLogic();
    }

    private void setupControlLogic() {
        // CLOSE BUTTON
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Just close everything
                stopSelf();
            }
        });

        // ADD BUTTON (Capture Current Screen)
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerCapture("CAPTURE");
            }
        });

        // DONE BUTTON (Finish and Copy)
        btnDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerCapture("FINISH");
                stopSelf(); // Close overlay after clicking Done
            }
        });
    }

    private void triggerCapture(String command) {
        // Calculate the current positions of the Green and Red lines
        int[] topLocation = new int[2];
        lineTop.getLocationOnScreen(topLocation);
        int topY = topLocation[1];

        int[] bottomLocation = new int[2];
        lineBottom.getLocationOnScreen(bottomLocation);
        int bottomY = bottomLocation[1];

        if (topY >= bottomY) {
            Toast.makeText(this, "Green line must be above Red line", Toast.LENGTH_SHORT).show();
            return;
        }

        Rect selectionRect = new Rect(0, topY, getResources().getDisplayMetrics().widthPixels, bottomY);

        // Send Command to Main Service
        Intent intent = new Intent(this, FloatingTranslatorService.class);
        intent.putExtra("RECT", selectionRect);
        intent.putExtra("COMMAND", command); // "CAPTURE" or "FINISH"
        startService(intent);
        
        if (command.equals("CAPTURE")) {
            Toast.makeText(this, "Page Added. Scroll & Click Add again.", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupTouchListeners() {
        // Allow dragging the lines anywhere on the screen
        linesView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                float rawY = event.getRawY();
                int threshold = 150; // Touch sensitivity

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        float distTop = Math.abs(rawY - getCenterY(lineTop));
                        float distBottom = Math.abs(rawY - getCenterY(lineBottom));

                        // Check which line is closer
                        if (distTop < threshold && distTop < distBottom) {
                            activeDragView = lineTop;
                            activeHandleView = handleTop;
                            initialTouchY = rawY;
                            initialViewY = lineTop.getY();
                            return true;
                        } else if (distBottom < threshold) {
                            activeDragView = lineBottom;
                            activeHandleView = handleBottom;
                            initialTouchY = rawY;
                            initialViewY = lineBottom.getY();
                            return true;
                        }
                        return false; // Pass touch if not near lines (allows some interaction behind)

                    case MotionEvent.ACTION_MOVE:
                        if (activeDragView != null) {
                            float dY = rawY - initialTouchY;
                            float newY = initialViewY + dY;

                            // Keep lines within screen bounds
                            if (newY < 0) newY = 0;
                            if (newY > screenHeight - activeDragView.getHeight()) newY = screenHeight - activeDragView.getHeight();

                            activeDragView.setY(newY);
                            
                            // Move handle along with the line
                            if (activeHandleView != null) {
                                activeHandleView.setY(newY - 40); // Offset for handle visibility
                            }
                            return true;
                        }
                        return false;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        activeDragView = null;
                        activeHandleView = null;
                        return true;
                }
                return false;
            }
        });
    }

    private float getCenterY(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return location[1] + (view.getHeight() / 2f);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (linesView != null) windowManager.removeView(linesView);
        if (controlsView != null) windowManager.removeView(controlsView);
    }
}