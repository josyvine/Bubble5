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

    // Accumulator for Endless Scrolling
    private StringBuilder continuousOcrBuilder = new StringBuilder();
    private boolean isFirstChunk = true;

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

                    // Listener to handle if the projection stops unexpectedly
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

            // Handle commands from TwoLineOverlayService
            if (intent.hasExtra("RECT")) {
                Rect selectionRect = intent.getParcelableExtra("RECT");
                boolean copyToClip = intent.getBooleanExtra("COPY_TO_CLIPBOARD", false);

                this.shouldCopyToClipboard = copyToClip;

                // Trigger final processing (Flush the remaining bitmaps)
                if (isBurstMode || !capturedBitmaps.isEmpty()) {
                    processTwoLineResult(selectionRect);
                } else {
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

        // Start Foreground Service
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

    // Creates the Notification to keep service alive
    private void startMyForeground() {
        String CHANNEL_ID = "bubble_translator_channel";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Bubble Translator Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Bubble Translator is Running")
                    .setContentText("Tap to open settings")
                    .setSmallIcon(android.R.drawable.ic_menu_search) 
                    .setContentIntent(pendingIntent)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("Bubble Translator is Running")
                    .setContentText("Tap to open settings")
                    .setSmallIcon(android.R.drawable.ic_menu_search)
                    .setContentIntent(pendingIntent)
                    .build();
        }

        startForeground(1337, notification);
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

    private void requestPermissionRestart() {
        Toast.makeText(this, "Permission lost. Please allow again.", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("AUTO_REQUEST_PERMISSION", true);
        startActivity(intent);
    }

    // Called by TwoLineOverlayService to start recording
    public void startBurstCapture() {
        if (mediaProjection != null) {
            isBurstMode = true;
            capturedBitmaps.clear();
            continuousOcrBuilder.setLength(0); // Clear previous text
            isFirstChunk = true; // Reset chunk flag

            if (imageReader == null) {
                currentCropRect = new Rect(0, 0, screenWidth, screenHeight);
                startCapture(currentCropRect);
            }
        } else {
            requestPermissionRestart();
        }
    }

    public void stopBurstCapture() {
        isBurstMode = false;
        stopCapture(); 
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
            capturedBitmaps.clear();
            startCapture(selectedRect);
        } else {
            requestPermissionRestart();
        }
    }

    private void startCapture(final Rect cropRect) {
        if (imageReader != null) {
            return;
        }

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

                            Bitmap capturedFrame;
                            if (isBurstMode) {
                                // Raw Capture (No Crop)
                                capturedFrame = fullBitmap;
                            } else {
                                // Normal Single Shot Logic (Crop Immediately)
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

                            // Batch Processing: 3 frames allows unlimited scrolling safely.
                            if (isBurstMode && capturedBitmaps.size() >= 3) {
                                processIntermediateChunk();
                            }

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

    // --- Processes a chunk of images in the background ---
    private void processIntermediateChunk() {
        final List<Bitmap> chunkToProcess = new ArrayList<>(capturedBitmaps);
        
        // Keep the last frame to ensure overlap for the next chunk
        Bitmap lastFrame = capturedBitmaps.get(capturedBitmaps.size() - 1);
        capturedBitmaps.clear();
        capturedBitmaps.add(lastFrame);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                // Cut Status Bar (70px) to prevent repeats
                List<Bitmap> bitmapsForStitching = new ArrayList<>();
                int statusBarCut = 70; 

                for (Bitmap original : chunkToProcess) {
                    if (original.getHeight() > statusBarCut) {
                        Bitmap cropped = Bitmap.createBitmap(original, 0, statusBarCut, original.getWidth(), original.getHeight() - statusBarCut);
                        bitmapsForStitching.add(cropped);
                    } else {
                        bitmapsForStitching.add(original);
                    }
                }

                Bitmap stitched = ImageStitcher.stitchImages(bitmapsForStitching);
                
                // Cleanup temp bitmaps
                for (Bitmap b : bitmapsForStitching) {
                   if (b != null && !chunkToProcess.contains(b)) b.recycle();
                }

                if (stitched != null) {
                    // Pass to ML Kit
                    performOcrWithFilter(stitched, -1, -1);
                    isFirstChunk = false;
                }
                
                // Cleanup
                for (Bitmap b : chunkToProcess) {
                    if (b != lastFrame) b.recycle(); 
                }
            }
        });
    }

    private void processSingleShotResult() {
        if (!capturedBitmaps.isEmpty()) {
            performOcr(capturedBitmaps.get(0));
        }
    }

    // --- FINAL Processing when User clicks STOP/COPY ---
    private void processTwoLineResult(Rect limitRect) {
        final List<Bitmap> remainingFrames = new ArrayList<>(capturedBitmaps);
        capturedBitmaps.clear();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean isSingleScreen = (remainingFrames.size() <= 2 && isFirstChunk);
                Bitmap stitched;

                if (isSingleScreen) {
                    // Single Sentence Mode: No cuts.
                    stitched = ImageStitcher.stitchImages(remainingFrames);
                } else {
                    // Scrolling Mode: Cut status bar.
                    List<Bitmap> bitmapsForStitching = new ArrayList<>();
                    int statusBarCut = 70; 

                    for (Bitmap original : remainingFrames) {
                        if (original.getHeight() > statusBarCut) {
                            Bitmap cropped = Bitmap.createBitmap(original, 0, statusBarCut, original.getWidth(), original.getHeight() - statusBarCut);
                            bitmapsForStitching.add(cropped);
                        } else {
                            bitmapsForStitching.add(original);
                        }
                    }
                    stitched = ImageStitcher.stitchImages(bitmapsForStitching);
                    for (Bitmap b : bitmapsForStitching) {
                       if (b != null && !remainingFrames.contains(b)) b.recycle();
                    }
                }
                
                if (stitched == null && continuousOcrBuilder.length() == 0) {
                    handler.post(() -> Toast.makeText(FloatingTranslatorService.this, "Capture failed.", Toast.LENGTH_SHORT).show());
                    return;
                }

                if (stitched != null) {
                    // Use Center-Point Filtering
                    if (isSingleScreen) {
                        performOcrWithFilter(stitched, limitRect.top, limitRect.bottom);
                    } else {
                        performOcrWithFilter(stitched, -1, -1);
                    }
                }
            }
        });
    }

    // FIX: Method using ML Kit with Debug Reporting
    private void performOcrWithFilter(Bitmap bitmap, final int minY, final int maxY) {
        if (bitmap == null) return;
        
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        try {
            // Force Synchronous execution
            Text visionText = Tasks.await(recognizer.process(image));
            
            StringBuilder sb = new StringBuilder();
            StringBuilder debugRawLog = new StringBuilder(); // For Debug Screen
            
            for (Text.TextBlock block : visionText.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    Rect box = line.getBoundingBox();
                    if (box != null) {
                        int centerY = box.centerY();
                        
                        // Log for Debug
                        debugRawLog.append("Y=").append(centerY).append(": ").append(line.getText()).append("\n");

                        // FILTER LOGIC: Check Center Point
                        boolean isInside = (minY == -1) || (centerY >= minY && centerY <= maxY);
                        
                        if (isInside) {
                            sb.append(line.getText()).append(" ");
                        }
                    }
                }
                sb.append("\n");
            }
            
            String chunkResult = sb.toString().replace("\n", " ");
            
            // Append result to global
            synchronized (continuousOcrBuilder) {
                continuousOcrBuilder.append(chunkResult).append(" ");
            }

            // === DEBUG: Launch Debug Activity if this is the final processing ===
            // Only launch if we have coordinate data (Single Screen) or it's the end of a scroll
            if (minY != -1 || !isFirstChunk) {
                
                // Prepare Data for Debug Screen
                DebugActivity.sLastBitmap = bitmap;
                DebugActivity.sLastRect = new Rect(0, minY, bitmap.getWidth(), maxY);
                DebugActivity.sRawText = debugRawLog.toString();
                DebugActivity.sFilteredText = continuousOcrBuilder.toString().trim();
                DebugActivity.sErrorLog = "Captured successfully. Check lines.";

                // Launch Screen
                Intent debugIntent = new Intent(this, DebugActivity.class);
                debugIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(debugIntent);
                
                // We do NOT show the normal popup in Debug Mode
                return;
            }

        } catch (Exception e) {
            e.printStackTrace();
            // Launch Debug Screen on Error
            DebugActivity.sErrorLog = "CRASH: " + e.getMessage();
            Intent debugIntent = new Intent(this, DebugActivity.class);
            debugIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(debugIntent);
        }
    }

    // Standard Normal Bubble OCR
    private void performOcr(Bitmap bitmap) {
        executor.execute(() -> {
            // Use -1 to read everything (Normal Bubble is already cropped)
            performOcrWithFilter(bitmap, -1, -1);
        });
    }

    // ... (Existing Clipboard/Translate methods kept for structure, but Debug Mode skips them) ...
    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("Bubble Copy", text);
            clipboard.setPrimaryClip(clip);
        }
    }

    private void translateText(final String text) {
       // Debug Mode skips this
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
        // Not used in Debug Mode
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

    private void setupPopupListeners() { }
    private void setupLanguageSpinners() { }
}