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

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FloatingTranslatorService extends Service {

    private static FloatingTranslatorService sInstance;
    private WindowManager windowManager;

    // --- BLUE BUBBLE UI ---
    private View floatingBubbleView;
    private WindowManager.LayoutParams bubbleParams;
    private CropSelectionView cropSelectionView;
    
    // --- POPUP UI ---
    private View popupView;
    private WindowManager.LayoutParams popupParams;
    private Spinner sourceSpinner;
    private Spinner targetSpinner;

    // --- CLOSE TARGET UI ---
    private View closeTargetView;
    private WindowManager.LayoutParams closeTargetParams;
    private boolean isBubbleOverCloseTarget = false;
    private int closeRegionHeight;

    // --- LOGIC VARS ---
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler handler = new Handler(Looper.getMainLooper());
    
    private String latestOcrText = ""; 
    private String latestTranslation = "";
    
    // Manual Copy Accumulator
    private StringBuilder globalTextAccumulator = new StringBuilder();

    // Languages
    private String[] languages = {"English", "Spanish", "French", "German", "Hindi", "Bengali", "Marathi", "Telugu", "Tamil", "Malayalam"};
    private String[] languageCodes = {"en", "es", "fr", "de", "hi", "bn", "mr", "te", "ta", "ml"};
    private String currentSourceLang = "English"; 
    private String currentTargetLang = "Malayalam"; 

    // Screen Capture
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight, screenDensity;

    // Legacy Burst Capture (Used by Blue Bubble internally)
    private List<Bitmap> capturedBitmaps = new ArrayList<>();
    private boolean isBurstMode = false;
    private long lastCaptureTime = 0;
    private static final long CAPTURE_INTERVAL_MS = 400; 
    private Rect currentCropRect;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static FloatingTranslatorService getInstance() { return sInstance; }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        startMyForeground();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MobileAds.initialize(this);

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        showFloatingBubble();
        setupCloseTarget();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            // 1. Permission Setup
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

            // 2. Handle Manual Copy Tool Actions
            String action = intent.getAction();
            if ("ACTION_ADD_PAGE".equals(action)) {
                Rect cropRect = intent.getParcelableExtra("RECT");
                if (cropRect != null) manualCaptureForAccumulator(cropRect);
            } 
            else if ("ACTION_DONE".equals(action)) {
                finishAndShowResult();
            }

            // 3. Handle Legacy Commands (if any external triggers)
            if (intent.hasExtra("RECT") && action == null) {
                Rect selectionRect = intent.getParcelableExtra("RECT");
                onCropFinished(selectionRect);
            }
        }
        return START_NOT_STICKY;
    }

    // =========================================================
    // PART 1: BLUE BUBBLE LOGIC (Original Restoration)
    // =========================================================

    // Triggered when user releases the Blue Selection Box
    public void onCropFinished(Rect selectedRect) {
        // Clear UI
        if (cropSelectionView != null) {
            windowManager.removeView(cropSelectionView);
            cropSelectionView = null;
        }
        if (floatingBubbleView != null) floatingBubbleView.setVisibility(View.VISIBLE);

        if (mediaProjection != null) {
            // Use Single Shot Mode
            isBurstMode = false;
            this.currentCropRect = selectedRect;
            capturedBitmaps.clear();
            startCapture(selectedRect); // Capture using original method
        } else {
            requestPermissionRestart();
        }
    }

    // Original Capture Logic (Restored)
    private void startCapture(final Rect cropRect) {
        if (imageReader != null) return;

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        try {
            virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null);
        } catch (Exception e) {
            e.printStackTrace();
            requestPermissionRestart();
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

                        // Crop to Blue Box
                        int left = Math.max(0, cropRect.left);
                        int top = Math.max(0, cropRect.top);
                        int width = Math.min(cropRect.width(), fullBitmap.getWidth() - left);
                        int height = Math.min(cropRect.height(), fullBitmap.getHeight() - top);

                        Bitmap capturedFrame = null;
                        if (width > 0 && height > 0) {
                            capturedFrame = Bitmap.createBitmap(fullBitmap, left, top, width, height);
                        }
                        fullBitmap.recycle();

                        if (capturedFrame != null) {
                            capturedBitmaps.add(capturedFrame);
                        }

                        stopCapture();
                        // Trigger Blue Bubble Processing
                        processBlueBubbleResult();
                        image.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    stopCapture();
                }
            }
        }, handler);
    }

    private void stopCapture() {
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
    }

    private void processBlueBubbleResult() {
        if (!capturedBitmaps.isEmpty()) {
            performTranslationOcr(capturedBitmaps.get(0));
        }
    }

    // OCR specifically for Translation (Restored Logic)
    private void performTranslationOcr(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        recognizer.process(image)
            .addOnSuccessListener(new OnSuccessListener<Text>() {
                @Override
                public void onSuccess(Text visionText) {
                    latestOcrText = visionText.getText();
                    if (latestOcrText != null && !latestOcrText.isEmpty()) {
                        // GO TO TRANSLATION, NOT DEBUG
                        translateText(latestOcrText);
                    } else {
                        Toast.makeText(FloatingTranslatorService.this, "No text found", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .addOnFailureListener(e -> Toast.makeText(FloatingTranslatorService.this, "OCR Failed", Toast.LENGTH_SHORT).show());
    }

    private void translateText(final String text) {
        // Find language codes
        int srcIndex = -1, targetIndex = -1;
        for (int i = 0; i < languages.length; i++) {
            if (languages[i].equals(currentSourceLang)) srcIndex = i;
            if (languages[i].equals(currentTargetLang)) targetIndex = i;
        }
        
        if (srcIndex == -1 || targetIndex == -1) return;
        
        final String srcCode = languageCodes[srcIndex];
        final String targetCode = languageCodes[targetIndex];

        // Background Thread for API
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final String result = TranslateApi.translate(srcCode, targetCode, text);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result != null) {
                            latestTranslation = result;
                            showResultPopup(); // SHOW THE POPUP
                        } else {
                            Toast.makeText(FloatingTranslatorService.this, "Translation Failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private void showResultPopup() {
        if (popupView != null) windowManager.removeView(popupView);

        LayoutInflater inflater = LayoutInflater.from(this);
        // Assuming your layout name is layout_popup_result or similar. 
        // Based on original code context, I will use a standard name.
        // If your original zip had 'layout_popup.xml', this is it.
        try {
            popupView = inflater.inflate(R.layout.layout_popup_result, null);
        } catch (Exception e) {
            // Fallback if name differs
            return; 
        }

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        popupParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        popupParams.gravity = Gravity.CENTER;

        // Populate Views
        TextView tvOriginal = popupView.findViewById(R.id.popup_original_text); // Check ID
        TextView tvTranslated = popupView.findViewById(R.id.popup_translated_text);
        
        if (tvOriginal != null) tvOriginal.setText(latestOcrText);
        if (tvTranslated != null) tvTranslated.setText(latestTranslation);

        windowManager.addView(popupView, popupParams);
        
        setupPopupListeners();
        setupLanguageSpinners();
    }

    // =========================================================
    // PART 2: MANUAL COPY TOOL LOGIC (New Feature)
    // =========================================================

    private void manualCaptureForAccumulator(final Rect cropRect) {
        if (mediaProjection == null) {
            Toast.makeText(this, "Permission missing", Toast.LENGTH_SHORT).show();
            return;
        }
        stopCapture(); // Ensure cleanup

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay("ManualCapture",
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
                        Image.Plane[] planes = image.getPlanes();
                        ByteBuffer buffer = planes[0].getBuffer();
                        int pixelStride = planes[0].getPixelStride();
                        int rowPadding = planes[0].getRowStride() - pixelStride * screenWidth;

                        Bitmap fullBitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
                        fullBitmap.copyPixelsFromBuffer(buffer);

                        // Strict Crop
                        int safeTop = Math.max(0, cropRect.top);
                        int safeHeight = Math.min(cropRect.height(), fullBitmap.getHeight() - safeTop);
                        
                        if (safeHeight > 0) {
                            Bitmap cropped = Bitmap.createBitmap(fullBitmap, 0, safeTop, screenWidth, safeHeight);
                            fullBitmap.recycle();
                            processAccumulatorOcr(cropped);
                        } else {
                            fullBitmap.recycle();
                        }
                        stopCapture();
                        image.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    stopCapture();
                }
            }
        }, handler);
    }

    private void processAccumulatorOcr(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        recognizer.process(image).addOnSuccessListener(visionText -> {
            StringBuilder pageText = new StringBuilder();
            for (Text.TextBlock block : visionText.getTextBlocks()) {
                String text = block.getText();
                if (text.contains("ADD PAGE") || text.contains("DONE")) continue;
                pageText.append(text).append("\n");
            }
            if (pageText.length() > 0) {
                globalTextAccumulator.append(pageText).append("\n\n");
                Toast.makeText(this, "Text Added", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No text found", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void finishAndShowResult() {
        String finalText = globalTextAccumulator.toString().trim();
        if (finalText.isEmpty()) {
            Toast.makeText(this, "No text captured.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Copy
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("Bubble Copy", finalText);
            clipboard.setPrimaryClip(clip);
        }

        // Show Debug Activity
        DebugActivity.sFilteredText = finalText;
        DebugActivity.sRawText = "Manual Capture Session";
        DebugActivity.sErrorLog = "";
        globalTextAccumulator.setLength(0); // Reset

        Intent intent = new Intent(this, DebugActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    // =========================================================
    // PART 3: UI & HELPERS (Common)
    // =========================================================

    private void startMyForeground() {
        String CHANNEL_ID = "bubble_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Service", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        
        startForeground(1337, builder.setContentTitle("Bubble Translator").setSmallIcon(android.R.drawable.ic_menu_search).setContentIntent(pIntent).build());
    }

    private void showFloatingBubble() {
        floatingBubbleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null);
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        bubbleParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.y = 100;
        windowManager.addView(floatingBubbleView, bubbleParams);
        
        floatingBubbleView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY; private float initialTouchX, initialTouchY; private long lastClickTime = 0;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch(event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = bubbleParams.x; initialY = bubbleParams.y;
                        initialTouchX = event.getRawX(); initialTouchY = event.getRawY();
                        lastClickTime = System.currentTimeMillis();
                        closeTargetView.setVisibility(View.VISIBLE);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        bubbleParams.x = initialX + (int)(event.getRawX()-initialTouchX);
                        bubbleParams.y = initialY + (int)(event.getRawY()-initialTouchY);
                        windowManager.updateViewLayout(floatingBubbleView, bubbleParams);
                        if (bubbleParams.y > screenHeight - closeRegionHeight) {
                            closeTargetView.setScaleX(1.3f); closeTargetView.setScaleY(1.3f);
                            isBubbleOverCloseTarget = true;
                        } else {
                            closeTargetView.setScaleX(1f); closeTargetView.setScaleY(1f);
                            isBubbleOverCloseTarget = false;
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        closeTargetView.setVisibility(View.GONE);
                        if (isBubbleOverCloseTarget) { stopSelf(); return true; }
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
        cropSelectionView = new CropSelectionView(this);
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        windowManager.addView(cropSelectionView, p);
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
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("AUTO_REQUEST_PERMISSION", true);
        startActivity(intent);
    }

    // --- SETUP POPUP LISTENERS (Restored from Original) ---
    private void setupPopupListeners() {
        popupView.findViewById(R.id.popup_back_arrow).setOnClickListener(v -> hideResultPopup());
        popupView.findViewById(R.id.popup_help).setOnClickListener(v -> {
            Intent intent = new Intent(FloatingTranslatorService.this, HelpActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            hideResultPopup();
        });
        
        ImageView menuIcon = popupView.findViewById(R.id.popup_menu);
        menuIcon.setOnClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(getApplicationContext(), menuIcon);
            popupMenu.getMenuInflater().inflate(R.menu.popup_menu, popupMenu.getMenu());
            popupMenu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.action_settings) {
                    Intent intent = new Intent(FloatingTranslatorService.this, SettingsActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    hideResultPopup();
                }
                return true;
            });
            popupMenu.show();
        });

        popupView.findViewById(R.id.popup_copy_icon).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("translation", latestTranslation);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });

        popupView.findViewById(R.id.popup_share_icon).setOnClickListener(v -> {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, latestTranslation);
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(shareIntent, "Share"));
        });

        popupView.findViewById(R.id.popup_refine_button).setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
            String apiKey = prefs.getString(SettingsActivity.KEY_API_KEY, "");
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "No API Key", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "Refining...", Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                String refined = GeminiApi.refine(latestTranslation, currentTargetLang, apiKey);
                handler.post(() -> {
                    if (refined != null) {
                        latestTranslation = refined;
                        TextView tv = popupView.findViewById(R.id.popup_translated_text);
                        tv.setText(latestTranslation);
                    } else {
                        Toast.makeText(this, "Refine Failed", Toast.LENGTH_SHORT).show();
                    }
                });
            });
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
                String s = (String) sourceSpinner.getSelectedItem();
                String t = (String) targetSpinner.getSelectedItem();
                if (!currentSourceLang.equals(s) || !currentTargetLang.equals(t)) {
                    currentSourceLang = s; currentTargetLang = t;
                    translateText(latestOcrText);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        sourceSpinner.setOnItemSelectedListener(listener);
        targetSpinner.setOnItemSelectedListener(listener);
    }

    private void hideResultPopup() {
        if (popupView != null) {
            windowManager.removeView(popupView);
            popupView = null;
            floatingBubbleView.setVisibility(View.VISIBLE);
        }
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