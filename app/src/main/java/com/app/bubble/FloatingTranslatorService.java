package com.app.bubble;

import android.app.Activity;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import android.util.SparseArray;
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

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.text.TextBlock;
import com.google.android.gms.vision.text.TextRecognizer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FloatingTranslatorService extends Service {

    private static FloatingTranslatorService sInstance;

    private WindowManager windowManager;

    // Bubble Views
    private View floatingBubbleView;
    private WindowManager.LayoutParams bubbleParams;

    // Crop Tool View
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
    private String latestOcrText = ""; // Holds the original text from OCR
    private String latestTranslation = "";
    private boolean isTranslationReady = false;

    // Language Management
    private String[] languages = {"English", "Spanish", "French", "German", "Hindi", "Bengali", "Marathi", "Telugu", "Tamil", "Malayalam"};
    private String[] languageCodes = {"en", "es", "fr", "de", "hi", "bn", "mr", "te", "ta", "ml"};
    private String currentSourceLang = "English"; // Default source
    private String currentTargetLang = "Malayalam"; // Default target

    // Screen Capture components
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight, screenDensity;

    // Burst / Continuous Capture Logic
    private List<Bitmap> capturedBitmaps = new ArrayList<>();
    private boolean isBurstMode = false;
    private long lastCaptureTime = 0;
    // Capture roughly every 150ms to avoid OOM but capture scrolling text
    private static final long CAPTURE_INTERVAL_MS = 150; 
    
    // Logic flags
    private boolean shouldCopyToClipboard = false;
    private Rect currentCropRect;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static FloatingTranslatorService getInstance() {
        return sInstance;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            // Handle MediaProjection Setup
            if (intent.hasExtra("resultCode")) {
                int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
                Intent data = intent.getParcelableExtra("data");
                if (mediaProjectionManager != null && resultCode == Activity.RESULT_OK && data != null) {
                    mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
                }
            }

            // Handle commands from TwoLineOverlayService
            if (intent.hasExtra("RECT")) {
                Rect selectionRect = intent.getParcelableExtra("RECT");
                boolean copyToClip = intent.getBooleanExtra("COPY_TO_CLIPBOARD", false);
                
                this.shouldCopyToClipboard = copyToClip;
                
                // CHECK: Do we have burst images waiting from the manual scroll?
                if (!capturedBitmaps.isEmpty()) {
                    // Yes, this is the end of the Two-Line flow.
                    processTwoLineResult(selectionRect);
                } else {
                    // No, this is likely a standard single capture or fallback
                    onCropFinished(selectionRect);
                }
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
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

    private void setupCloseTarget() {
        closeTargetView = LayoutInflater.from(this).inflate(R.layout.layout_close_target, null);
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        closeTargetParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        closeTargetParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        closeTargetParams.y = 50;
        windowManager.addView(closeTargetView, closeTargetParams);
        closeTargetView.setVisibility(View.GONE);
        closeRegionHeight = screenHeight / 5;
    }

    // Called by TwoLineOverlayService to start recording
    public void startBurstCapture() {
        if (mediaProjection != null) {
            isBurstMode = true;
            capturedBitmaps.clear();
            
            // Start capturing full screen continuously
            if (imageReader == null) {
                // Full screen rect
                currentCropRect = new Rect(0, 0, screenWidth, screenHeight);
                startCapture(currentCropRect);
            }
        } else {
            Toast.makeText(this, "Permission missing. Restart app.", Toast.LENGTH_SHORT).show();
        }
    }

    // Called by TwoLineOverlayService to pause/stop recording (but keep images in memory)
    public void stopBurstCapture() {
        isBurstMode = false;
        stopCapture(); // This closes the reader, but capturedBitmaps list remains full
    }

    // Called for Single-Box selection (Original Feature)
    public void onCropFinished(Rect selectedRect) {
        if (cropSelectionView != null) {
            windowManager.removeView(cropSelectionView);
            cropSelectionView = null;
        }
        if (floatingBubbleView != null) floatingBubbleView.setVisibility(View.VISIBLE);

        if (mediaProjection != null) {
            this.currentCropRect = selectedRect;
            // Clear previous captures for single mode
            capturedBitmaps.clear();
            startCapture(selectedRect);
        } else {
            Toast.makeText(this, "Permission not available.", Toast.LENGTH_SHORT).show();
        }
    }

    private void startCapture(final Rect cropRect) {
        if (imageReader != null) {
            return;
        }

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                                                              screenWidth, screenHeight, screenDensity,
                                                              DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                                              imageReader.getSurface(), null, null);

        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            
                            // Throttling for Burst Mode
                            if (isBurstMode) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastCaptureTime < CAPTURE_INTERVAL_MS) {
                                    image.close();
                                    return;
                                }
                                lastCaptureTime = currentTime;
                            }

                            Image.Plane[] planes = image.getPlanes();
                            ByteBuffer buffer = planes[0].getBuffer();
                            int pixelStride = planes[0].getPixelStride();
                            int rowStride = planes[0].getRowStride();
                            int rowPadding = rowStride - pixelStride * screenWidth;

                            Bitmap fullBitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
                            fullBitmap.copyPixelsFromBuffer(buffer);

                            // Safely crop based on mode
                            Bitmap capturedFrame;
                            if (isBurstMode) {
                                // In burst mode, we capture the full screen. We will crop later based on lines.
                                capturedFrame = fullBitmap; // Keep full
                            } else {
                                // Single mode: Crop immediately
                                int left = Math.max(0, cropRect.left);
                                int top = Math.max(0, cropRect.top);
                                int width = Math.min(cropRect.width(), fullBitmap.getWidth() - left);
                                int height = Math.min(cropRect.height(), fullBitmap.getHeight() - top);
                                
                                if (width > 0 && height > 0) {
                                    capturedFrame = Bitmap.createBitmap(fullBitmap, left, top, width, height);
                                    fullBitmap.recycle();
                                } else {
                                    fullBitmap.recycle();
                                    capturedFrame = null;
                                }
                            }

                            if (capturedFrame != null) {
                                capturedBitmaps.add(capturedFrame);
                            }

                            // If NOT in burst mode, this is a single shot, so we finish immediately.
                            if (!isBurstMode) {
                                stopCapture();
                                processSingleShotResult();
                            }
                            
                            image.close();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        stopCapture();
                    } finally {
                        // Image closed in try block
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

    // Logic for Standard Blue Box
    private void processSingleShotResult() {
        if (!capturedBitmaps.isEmpty()) {
            performOcr(capturedBitmaps.get(0));
        }
    }

    // Logic for Two-Line Scrolling Flow
    private void processTwoLineResult(Rect limitRect) {
        if (capturedBitmaps.isEmpty()) {
            Toast.makeText(this, "No content captured.", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. Stitch all captured full-screen frames into one tall image
        Bitmap longBitmap = ImageStitcher.stitchImages(capturedBitmaps);

        if (longBitmap == null) {
            Toast.makeText(this, "Stitching failed.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 2. Apply the Green Line (Top) and Red Line (Bottom) Crop
            // limitRect.top = Y position of Green Line (Start)
            // limitRect.bottom = Y position of Red Line (End) relative to the screen height
            
            // Logic:
            // The content starts at 'limitRect.top' pixels from the top of the stitched image.
            // The content ends at 'limitRect.bottom' pixels from the bottom of the stitched image?
            // NO. The user placed the Red Line on the LAST screen.
            // So we want to keep up to: TotalHeight - (ScreenHeight - RedLineY).
            
            int greenLineY = limitRect.top;
            int redLineY = limitRect.bottom;
            
            int pixelsFromBottomToSkip = screenHeight - redLineY;
            int finalHeight = longBitmap.getHeight() - greenLineY - pixelsFromBottomToSkip;
            
            // Safety check
            if (finalHeight <= 0) finalHeight = 100;
            if (greenLineY + finalHeight > longBitmap.getHeight()) finalHeight = longBitmap.getHeight() - greenLineY;

            Bitmap finalCropped = Bitmap.createBitmap(longBitmap, 0, greenLineY, longBitmap.getWidth(), finalHeight);
            
            // Clean up long bitmap
            // longBitmap.recycle(); // Optional, risky if OOM

            performOcr(finalCropped);

        } catch (Exception e) {
            e.printStackTrace();
            // Fallback: Just OCR the stitched image without cropping
            performOcr(longBitmap);
        }
    }

    private void performOcr(Bitmap bitmap) {
        TextRecognizer recognizer = new TextRecognizer.Builder(getApplicationContext()).build();
        if (!recognizer.isOperational()) {
            Toast.makeText(this, "OCR dependencies not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        Frame frame = new Frame.Builder().setBitmap(bitmap).build();
        SparseArray<TextBlock> items = recognizer.detect(frame);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); ++i) {
            TextBlock item = items.valueAt(i);
            sb.append(item.getValue()).append("\n");
        }

        String extractedText = sb.toString().trim();
        if (!extractedText.isEmpty()) {
            latestOcrText = extractedText;
            
            if (shouldCopyToClipboard) {
                copyToClipboard(latestOcrText);
                shouldCopyToClipboard = false; 
            }
            
            translateText(latestOcrText);
        } else {
            Toast.makeText(FloatingTranslatorService.this, "No text found", Toast.LENGTH_SHORT).show();
        }
        recognizer.release();
        
        // Clean up
        capturedBitmaps.clear();
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("Bubble Copy", text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Text copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void translateText(final String text) {
        if (text == null || text.isEmpty()) return;

        final String sourceLangCode = languageCodes[Arrays.asList(languages).indexOf(currentSourceLang)];
        final String targetLangCode = languageCodes[Arrays.asList(languages).indexOf(currentTargetLang)];

        executor.execute(new Runnable() {
                @Override
                public void run() {
                    final String result = TranslateApi.translate(sourceLangCode, targetLangCode, text);
                    handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (result != null && !result.isEmpty()) {
                                    latestTranslation = result;
                                    isTranslationReady = true;

                                    if (popupView != null && popupView.isShown()) {
                                        TextView translatedTextView = popupView.findViewById(R.id.popup_translated_text);
                                        translatedTextView.setText(latestTranslation);
                                        Toast.makeText(FloatingTranslatorService.this, "Translation updated", Toast.LENGTH_SHORT).show();
                                    } else {
                                        showResultPopup();
                                    }
                                } else {
                                    Toast.makeText(FloatingTranslatorService.this, "Translation failed", Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                }
            });
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

    private void showFloatingBubble() {
        floatingBubbleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null);
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        bubbleParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 0;
        bubbleParams.y = 100;
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
                            if (isBubbleOverCloseTarget) {
                                stopSelf();
                                return true;
                            }

                            if (System.currentTimeMillis() - lastClickTime < 200) {
                                showCropSelectionTool();
                            }
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

    private void showResultPopup() {
        if (popupView != null) return;
        floatingBubbleView.setVisibility(View.GONE);

        popupView = LayoutInflater.from(this).inflate(R.layout.layout_result_popup, null);
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        popupParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        popupParams.gravity = Gravity.CENTER;

        TextView translatedTextView = popupView.findViewById(R.id.popup_translated_text);
        translatedTextView.setText(latestTranslation);

        setupPopupListeners();
        setupLanguageSpinners();

        AdView adView = popupView.findViewById(R.id.adView);
        AdRequest adRequest = new AdRequest.Builder().build();
        adView.loadAd(adRequest);

        windowManager.addView(popupView, popupParams);
    }

    private void hideResultPopup() {
        if (popupView != null) {
            windowManager.removeView(popupView);
            popupView = null;
            isTranslationReady = false;
            latestTranslation = "";
            latestOcrText = "";
            floatingBubbleView.setVisibility(View.VISIBLE);
        }
    }

    private void setupPopupListeners() {
        popupView.findViewById(R.id.popup_back_arrow).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideResultPopup();
                }
            });

        popupView.findViewById(R.id.popup_help).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(FloatingTranslatorService.this, HelpActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    hideResultPopup();
                }
            });

        final ImageView menuIcon = popupView.findViewById(R.id.popup_menu);
        menuIcon.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    PopupMenu popupMenu = new PopupMenu(getApplicationContext(), menuIcon);
                    popupMenu.getMenuInflater().inflate(R.menu.popup_menu, popupMenu.getMenu());
                    popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                            @Override
                            public boolean onMenuItemClick(MenuItem item) {
                                if (item.getItemId() == R.id.action_settings) {
                                    Intent intent = new Intent(FloatingTranslatorService.this, SettingsActivity.class);
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(intent);
                                    hideResultPopup();
                                }
                                return true;
                            }
                        });
                    popupMenu.show();
                }
            });

        popupView.findViewById(R.id.popup_copy_icon).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("translation", latestTranslation);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(FloatingTranslatorService.this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
                    }
                }
            });

        popupView.findViewById(R.id.popup_share_icon).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, latestTranslation);
                    shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    Intent chooser = Intent.createChooser(shareIntent, "Share translation via");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(chooser);
                    hideResultPopup();
                }
            });

        popupView.findViewById(R.id.popup_refine_button).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
                    final String apiKey = prefs.getString(SettingsActivity.KEY_API_KEY, "");

                    if (apiKey == null || apiKey.trim().isEmpty()) {
                        Toast.makeText(FloatingTranslatorService.this, "Please add Gemini API key in Settings", Toast.LENGTH_LONG).show();
                        return;
                    }

                    Toast.makeText(FloatingTranslatorService.this, "Refining with AI...", Toast.LENGTH_SHORT).show();

                    executor.execute(new Runnable() {
                            @Override
                            public void run() {
                                final String refinedResult = GeminiApi.refine(latestTranslation, currentTargetLang, apiKey);
                                handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (refinedResult != null && !refinedResult.isEmpty()) {
                                                latestTranslation = refinedResult;
                                                if (popupView != null) {
                                                    TextView translatedTextView = popupView.findViewById(R.id.popup_translated_text);
                                                    translatedTextView.setText(latestTranslation);
                                                }
                                                Toast.makeText(FloatingTranslatorService.this, "Refinement complete", Toast.LENGTH_SHORT).show();
                                            } else {
                                                Toast.makeText(FloatingTranslatorService.this, "Refinement failed. Check API key or connection.", Toast.LENGTH_LONG).show();
                                            }
                                        }
                                    });
                            }
                        });
                }
            });
    }

    private void setupLanguageSpinners() {
        sourceSpinner = popupView.findViewById(R.id.popup_source_language_spinner);
        targetSpinner = popupView.findViewById(R.id.popup_target_language_spinner);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, languages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        sourceSpinner.setAdapter(adapter);
        targetSpinner.setAdapter(adapter);

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
                    Toast.makeText(FloatingTranslatorService.this, "Translating to " + currentTargetLang, Toast.LENGTH_SHORT).show();
                    translateText(latestOcrText);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        };

        sourceSpinner.setOnItemSelectedListener(listener);
        targetSpinner.setOnItemSelectedListener(listener);
    }
}