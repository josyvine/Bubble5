package com.app.bubble;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

// AdMob
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;

// ML Kit Imports
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FloatingTranslatorService extends Service {

    private static FloatingTranslatorService sInstance;
    private WindowManager windowManager;

    // Bubble Views
    private View floatingBubbleView;
    private WindowManager.LayoutParams bubbleParams;
    private CropSelectionView cropSelectionView;

    // Pop-up Window Views
    private View popupView;
    private WindowManager.LayoutParams popupParams;
    private Spinner sourceSpinner;
    private Spinner targetSpinner;

    // Drag-to-Close Views
    private View closeTargetView;
    private WindowManager.LayoutParams closeTargetParams;
    private boolean isBubbleOverCloseTarget = false;
    private int closeRegionHeight;

    // State Management
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler handler = new Handler(Looper.getMainLooper());
    private String latestOcrText = ""; 
    private String latestTranslation = "";
    
    // --- MODE SWITCH ---
    private boolean isManualCopyMode = false;

    // Accumulator for Manual Multi-Page Screenshot
    private StringBuilder accumulatedTextBuilder = new StringBuilder();

    // Screen Capture components
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight, screenDensity;

    // Languages
    private String[] languages = {"English", "Spanish", "French", "German", "Hindi", "Bengali", "Marathi", "Telugu", "Tamil", "Malayalam"};
    private String[] languageCodes = {"en", "es", "fr", "de", "hi", "bn", "mr", "te", "ta", "ml"};
    private String currentSourceLang = "English"; 
    private String currentTargetLang = "Malayalam"; 

    // Current Capture Logic
    private Rect currentCropRect;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static FloatingTranslatorService getInstance() {
        return sInstance;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            // 1. Handle MediaProjection Setup
            if (intent.hasExtra("resultCode")) {
                int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
                Intent data = intent.getParcelableExtra("data");
                if (mediaProjectionManager != null && resultCode == Activity.RESULT_OK && data != null) {
                    mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
                    mediaProjection.registerCallback(new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            super.onStop();
                            mediaProjection = null;
                            if (imageReader != null) imageReader.close();
                        }
                    }, handler);
                }
            }

            // 2. Handle Manual Screenshot Logic (Two Line Overlay)
            if (intent.hasExtra("COMMAND")) {
                String command = intent.getStringExtra("COMMAND");
                Rect selectionRect = intent.getParcelableExtra("RECT");

                if ("CAPTURE".equals(command)) {
                    isManualCopyMode = true; // Set Mode: COPY
                    this.currentCropRect = selectionRect;
                    performSingleCapture(selectionRect);
                } 
                else if ("FINISH".equals(command)) {
                    finishAndCopy();
                }
            }
            // 3. Handle Normal Crop (Bubble Translate)
            else if (intent.hasExtra("RECT")) {
                Rect selectionRect = intent.getParcelableExtra("RECT");
                isManualCopyMode = false; // Set Mode: TRANSLATE
                this.currentCropRect = selectionRect;
                performSingleCapture(selectionRect);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        startMyForeground();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MobileAds.initialize(this);

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        screenDensity = metrics.densityDpi;
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        showFloatingBubble();
        setupCloseTarget();
    }

    private void startMyForeground() {
        String CHANNEL_ID = "bubble_translator_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Bubble Service", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Bubble Translator Active")
                .setSmallIcon(android.R.drawable.ic_menu_search) 
                .build();
        startForeground(1337, notification);
    }

    // --- CAPTURE LOGIC ---
    private void performSingleCapture(final Rect cropRect) {
        if (mediaProjection == null) {
            Toast.makeText(this, "Permission lost. Restart app.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (imageReader != null) imageReader.close();

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

        try {
            virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        Image.Plane[] planes = image.getPlanes();
                        ByteBuffer buffer = planes[0].getBuffer();
                        int pixelStride = planes[0].getPixelStride();
                        int rowStride = planes[0].getRowStride();
                        int rowPadding = rowStride - pixelStride * screenWidth;

                        Bitmap fullBitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
                        fullBitmap.copyPixelsFromBuffer(buffer);

                        // CROP
                        int left = Math.max(0, cropRect.left);
                        int top = Math.max(0, cropRect.top);
                        int width = Math.min(cropRect.width(), fullBitmap.getWidth() - left);
                        int height = Math.min(cropRect.height(), fullBitmap.getHeight() - top);

                        if (width > 0 && height > 0) {
                            Bitmap cropped = Bitmap.createBitmap(fullBitmap, left, top, width, height);
                            // Process based on mode
                            if (isManualCopyMode) {
                                processCapturedBitmapForCopy(cropped);
                            } else {
                                performOcr(cropped); // Normal Translate
                            }
                        }

                        fullBitmap.recycle();
                        image.close();
                        stopCapture();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    stopCapture();
                }
            }
        }, handler);
    }

    private void stopCapture() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    // --- PATH A: COPY MODE LOGIC ---
    private void processCapturedBitmapForCopy(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    StringBuilder pageText = new StringBuilder();
                    for (Text.TextBlock block : visionText.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            String txt = line.getText();
                            if (isGarbageText(txt)) continue; // Filter UI buttons
                            pageText.append(txt).append("\n");
                        }
                    }
                    synchronized (accumulatedTextBuilder) {
                        accumulatedTextBuilder.append(pageText.toString()).append("\n");
                    }
                    Toast.makeText(FloatingTranslatorService.this, "Text Added! Scroll & Add More.", Toast.LENGTH_SHORT).show();
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                })
                .addOnFailureListener(e -> Toast.makeText(this, "Error reading text", Toast.LENGTH_SHORT).show());
    }

    private void finishAndCopy() {
        String finalText = accumulatedTextBuilder.toString().trim();
        if (finalText.isEmpty()) {
            Toast.makeText(this, "No text captured.", Toast.LENGTH_SHORT).show();
            return;
        }
        // Copy
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("Bubble Copy", finalText);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Copied to Clipboard!", Toast.LENGTH_LONG).show();
        }
        // Show Debug
        DebugActivity.sFilteredText = finalText;
        DebugActivity.sRawText = "Multi-Page Capture Completed.";
        DebugActivity.sErrorLog = "Clipboard Updated.";
        
        Intent debugIntent = new Intent(this, DebugActivity.class);
        debugIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(debugIntent);
        
        accumulatedTextBuilder.setLength(0);
    }

    private boolean isGarbageText(String text) {
        if (text == null) return true;
        String t = text.trim();
        return t.equalsIgnoreCase("ADD") || t.equalsIgnoreCase("DONE") || 
               t.equalsIgnoreCase("Bubble") || t.contains("Place Green Line");
    }

    // --- PATH B: TRANSLATE MODE LOGIC (Restored!) ---
    private void performOcr(Bitmap bitmap) {
        if (bitmap == null) return;
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    String extractedText = visionText.getText();
                    if (extractedText != null && !extractedText.isEmpty()) {
                        latestOcrText = extractedText;
                        translateText(latestOcrText); // Call Translation
                    } else {
                        Toast.makeText(this, "No text found", Toast.LENGTH_SHORT).show();
                    }
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                })
                .addOnFailureListener(e -> Toast.makeText(this, "OCR Failed", Toast.LENGTH_SHORT).show());
    }

    // RESTORED: Actual Translation Logic
    private void translateText(final String text) {
        int srcIndex = -1, targetIndex = -1;
        for(int i=0; i<languages.length; i++) {
            if(languages[i].equals(currentSourceLang)) srcIndex = i;
            if(languages[i].equals(currentTargetLang)) targetIndex = i;
        }
        if(srcIndex == -1 || targetIndex == -1) return;

        final String srcCode = languageCodes[srcIndex];
        final String targetCode = languageCodes[targetIndex];

        executor.execute(() -> {
            // Using TranslateApi
            final String result = TranslateApi.translate(srcCode, targetCode, text);
            handler.post(() -> {
                if (result != null) {
                    latestTranslation = result;
                    showResultPopup(); // Show the Window
                } else {
                    Toast.makeText(FloatingTranslatorService.this, "Translation Failed", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // RESTORED: Result Popup Window (MATCHING YOUR XML)
    private void showResultPopup() {
        if (popupView != null) {
            windowManager.removeView(popupView);
        }
        
        // This MUST match your XML file name. Assuming "layout_popup_result.xml" based on our previous conversation.
        // If your XML file is named "popup_layout.xml" or something else, CHANGE THIS LINE.
        popupView = LayoutInflater.from(this).inflate(R.layout.layout_popup_result, null);
        
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? 
                   WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : 
                   WindowManager.LayoutParams.TYPE_PHONE;
        
        popupParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        popupParams.gravity = Gravity.CENTER;

        // Populate Text - ONLY Target text because your XML has no Source text view
        TextView tvTarget = popupView.findViewById(R.id.popup_translated_text);
        
        if (tvTarget != null) tvTarget.setText(latestTranslation);

        // Setup AdMob (Because your XML has it)
        AdView adView = popupView.findViewById(R.id.adView);
        if (adView != null) {
            adView.loadAd(new AdRequest.Builder().build());
        }

        // Add to Screen
        windowManager.addView(popupView, popupParams);
        
        // Setup Listeners (Close, Copy, Spinners)
        setupPopupListeners();
        setupLanguageSpinners();
        
        // Hide bubble while popup is open
        floatingBubbleView.setVisibility(View.GONE);
    }

    private void hideResultPopup() {
        if (popupView != null) {
            windowManager.removeView(popupView);
            popupView = null;
            floatingBubbleView.setVisibility(View.VISIBLE);
        }
    }

    // --- UI Listeners (Matching YOUR XML IDs) ---
    private void setupPopupListeners() {
        View backBtn = popupView.findViewById(R.id.popup_back_arrow);
        if(backBtn != null) backBtn.setOnClickListener(v -> hideResultPopup());
        
        View copyBtn = popupView.findViewById(R.id.popup_copy_icon);
        if(copyBtn != null) copyBtn.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("translation", latestTranslation);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        
        View shareBtn = popupView.findViewById(R.id.popup_share_icon);
        if (shareBtn != null) shareBtn.setOnClickListener(v -> {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, latestTranslation);
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(shareIntent, "Share via"));
        });
        
        // Note: Your XML has an ImageView, but OnClick works on Views.
        View refineBtn = popupView.findViewById(R.id.popup_refine_button);
        if(refineBtn != null) refineBtn.setOnClickListener(v -> {
             // Gemini Refine Logic
             SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
             final String apiKey = prefs.getString(SettingsActivity.KEY_API_KEY, "");
             if (apiKey.isEmpty()) {
                 Toast.makeText(this, "No API Key", Toast.LENGTH_SHORT).show();
                 return;
             }
             Toast.makeText(this, "Refining...", Toast.LENGTH_SHORT).show();
             executor.execute(() -> {
                 String refined = GeminiApi.refine(latestTranslation, currentTargetLang, apiKey);
                 handler.post(() -> {
                     if(refined != null) {
                         latestTranslation = refined;
                         TextView tv = popupView.findViewById(R.id.popup_translated_text);
                         if(tv != null) tv.setText(refined);
                     }
                 });
             });
        });
    }

    private void setupLanguageSpinners() {
        sourceSpinner = popupView.findViewById(R.id.popup_source_language_spinner);
        targetSpinner = popupView.findViewById(R.id.popup_target_language_spinner);
        if (sourceSpinner == null || targetSpinner == null) return;

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, languages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourceSpinner.setAdapter(adapter);
        targetSpinner.setAdapter(adapter);

        // Set selections based on currentLang vars
        sourceSpinner.setSelection(Arrays.asList(languages).indexOf(currentSourceLang));
        targetSpinner.setSelection(Arrays.asList(languages).indexOf(currentTargetLang));

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedSource = (String) sourceSpinner.getSelectedItem();
                String selectedTarget = (String) targetSpinner.getSelectedItem();
                if (!currentSourceLang.equals(selectedSource) || !currentTargetLang.equals(selectedTarget)) {
                    currentSourceLang = selectedSource;
                    currentTargetLang = selectedTarget;
                    // Re-translate if language changed
                    translateText(latestOcrText);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        };
        sourceSpinner.setOnItemSelectedListener(listener);
        targetSpinner.setOnItemSelectedListener(listener);
    }

    private void showFloatingBubble() {
        floatingBubbleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null);
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        bubbleParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 0; bubbleParams.y = 100;
        windowManager.addView(floatingBubbleView, bubbleParams);
        setupBubbleTouchListener();
    }

    private void setupBubbleTouchListener() {
        floatingBubbleView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY; private float initialTouchX, initialTouchY; private long lastClickTime = 0;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = bubbleParams.x; initialY = bubbleParams.y; initialTouchX = event.getRawX(); initialTouchY = event.getRawY(); lastClickTime = System.currentTimeMillis();
                        closeTargetView.setVisibility(View.VISIBLE);
                        isBubbleOverCloseTarget = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        bubbleParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        bubbleParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingBubbleView, bubbleParams);
                        if (bubbleParams.y > (screenHeight - closeRegionHeight)) {
                            isBubbleOverCloseTarget = true;
                            closeTargetView.setScaleX(1.3f); closeTargetView.setScaleY(1.3f);
                        } else {
                            isBubbleOverCloseTarget = false;
                            closeTargetView.setScaleX(1.0f); closeTargetView.setScaleY(1.0f);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        closeTargetView.setVisibility(View.GONE);
                        if (isBubbleOverCloseTarget) { stopSelf(); return true; }
                        if (System.currentTimeMillis() - lastClickTime < 200) { showCropSelectionTool(); }
                        return true;
                }
                return false;
            }
        });
    }

    private void showCropSelectionTool() {
        if (floatingBubbleView != null) floatingBubbleView.setVisibility(View.GONE);
        if (cropSelectionView != null) return;
        cropSelectionView = new CropSelectionView(this);
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams cropParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        windowManager.addView(cropSelectionView, cropParams);
    }

    public void onCropFinished(Rect selectedRect) {
        // Remove Crop View
        if (cropSelectionView != null) {
            windowManager.removeView(cropSelectionView);
            cropSelectionView = null;
        }
        
        if (mediaProjection != null) {
            // Normal Translate Mode
            isManualCopyMode = false;
            this.currentCropRect = selectedRect;
            performSingleCapture(selectedRect);
        } else {
            requestPermissionRestart();
        }
    }

    private void setupCloseTarget() {
        closeTargetView = LayoutInflater.from(this).inflate(R.layout.layout_close_target, null);
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        closeTargetParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        closeTargetParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        closeTargetParams.y = 50;
        windowManager.addView(closeTargetView, closeTargetParams);
        closeTargetView.setVisibility(View.GONE);
        closeRegionHeight = screenHeight / 5;
    }

    private void requestPermissionRestart() {
        Toast.makeText(this, "Permission lost. Restarting...", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("AUTO_REQUEST_PERMISSION", true);
        startActivity(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        if (mediaProjection != null) mediaProjection.stop();
        if (floatingBubbleView != null) windowManager.removeView(floatingBubbleView);
        if (popupView != null) windowManager.removeView(popupView);
        if (closeTargetView != null) windowManager.removeView(closeTargetView);
    }
}