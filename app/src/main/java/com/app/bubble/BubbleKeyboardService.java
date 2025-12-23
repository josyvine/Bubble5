--- START OF FILE BubbleKeyboardService.java ---

package com.app.bubble;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The core Service handling the Modern Keyboard logic.
 * Manages Switching layers, Predictions, Emoji interactions, Professional Clipboard, 
 * Panel Translation, and the NEW Direct (Globe) Translation.
 * UPDATED: Fixed Invisible Popup Menu & Globe Toggle Logic.
 */
public class BubbleKeyboardService extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    // Main Views
    private LinearLayout mainLayout;
    private KeyboardView kv;
    private View candidateView;
    private LinearLayout candidateContainer;
    private LinearLayout toolbarContainer; 
    private View emojiPaletteView;
    
    // Professional Clipboard Views
    private View clipboardPaletteView;
    private ClipboardUiManager clipboardUiManager;

    // Translation Views & Logic (Panel Mode)
    private View translationPanelView;
    private TranslationUiManager translationUiManager;
    private boolean isTranslationMode = false;
    private StringBuilder translationBuffer = new StringBuilder();
    private int lastSentTranslationLength = 0;

    // --- DIRECT TRANSLATION VARIABLES (Globe) ---
    private boolean isDirectTranslateEnabled = false;
    private String directTargetLangCode = "es"; // Default Spanish
    private StringBuilder directBuffer = new StringBuilder();
    private int lastDirectOutputLength = 0;
    private long lastGlobeClickTime = 0;
    private Handler directHandler = new Handler(Looper.getMainLooper());
    private Runnable directTranslateRunnable;
    private ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    // Buttons
    private ImageButton btnClipboard;
    private ImageButton btnKeyboardSwitch;
    private ImageButton btnTranslate;
    private ImageButton btnDirectTranslate; // Globe
    private ImageButton btnBubbleLauncher; 
    private ImageButton btnOcrCopy;       

    // Keyboards
    private Keyboard keyboardQwerty;
    private Keyboard keyboardSymbols;

    // State
    private boolean isCaps = false;
    private boolean isEmojiVisible = false;
    private StringBuilder currentWord = new StringBuilder(); 
    private String lastCommittedWord = null; 

    // Auto-Correct Undo State
    private boolean justAutoCorrected = false;
    private String lastOriginalWord = "";
    private String lastCorrectedWord = "";
    private boolean ignoreNextCorrection = false;

    // Long Press Logic 
    private Handler longPressHandler = new Handler(Looper.getMainLooper());
    private boolean isSpaceLongPressed = false;
    private static final int LONG_PRESS_DELAY = 500; 

    private Runnable spaceLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            isSpaceLongPressed = true;
            InputMethodManager ime = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (ime != null) {
                ime.showInputMethodPicker();
            }
        }
    };

    @Override
    public View onCreateInputView() {
        mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);

        LayoutInflater inflater = getLayoutInflater();

        // 1. Setup Translation Panel 
        translationPanelView = inflater.inflate(R.layout.layout_translation_panel, mainLayout, false);
        translationPanelView.setVisibility(View.GONE);
        
        translationUiManager = new TranslationUiManager(this, translationPanelView, new TranslationUiManager.TranslationListener() {
            @Override
            public void onTranslationResult(String translatedText) {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    if (lastSentTranslationLength > 0) {
                        ic.deleteSurroundingText(lastSentTranslationLength, 0);
                    }
                    ic.commitText(translatedText, 1);
                    // FIX 1: Clear fields after sending
                    lastSentTranslationLength = 0; 
                    translationBuffer.setLength(0);
                    translationUiManager.updateInputPreview("");
                    updateCandidates(""); // Reset suggestions
                }
            }

            @Override
            public void onCloseTranslation() {
                toggleTranslationMode();
            }

            @Override
            public void onPasteText(String text) {
                if (text != null) {
                    translationBuffer.append(text);
                    translationUiManager.updateInputPreview(translationBuffer.toString());
                    translationUiManager.performTranslation(translationBuffer.toString());
                    // Update suggestions for pasted text
                    updateTranslationCandidates();
                }
            }
        });
        mainLayout.addView(translationPanelView);

        // 2. Setup Candidate View 
        candidateView = inflater.inflate(R.layout.candidate_view, mainLayout, false);
        candidateContainer = candidateView.findViewById(R.id.candidate_container);
        toolbarContainer = candidateView.findViewById(R.id.toolbar_container);
        
        setupToolbarButtons();
        mainLayout.addView(candidateView);

        // 3. Setup Keyboard View
        kv = (KeyboardView) inflater.inflate(R.layout.layout_real_keyboard, mainLayout, false);
        keyboardQwerty = new Keyboard(this, R.xml.qwerty);
        keyboardSymbols = new Keyboard(this, R.xml.symbols);
        kv.setKeyboard(keyboardQwerty);
        kv.setOnKeyboardActionListener(this);
        kv.setPreviewEnabled(false); 
        mainLayout.addView(kv);

        // 4. Setup Emoji Palette
        emojiPaletteView = inflater.inflate(R.layout.layout_emoji_palette, mainLayout, false);
        emojiPaletteView.setVisibility(View.GONE);
        EmojiUtils.setupEmojiGrid(this, emojiPaletteView, new EmojiUtils.EmojiListener() {
            @Override
            public void onEmojiClick(String emoji) {
                getCurrentInputConnection().commitText(emoji, 1);
            }
        });
        setupEmojiControlButtons();
        mainLayout.addView(emojiPaletteView);

        // 5. Setup Clipboard Palette
        clipboardPaletteView = inflater.inflate(R.layout.layout_clipboard_palette, mainLayout, false);
        clipboardPaletteView.setVisibility(View.GONE);
        
        clipboardUiManager = new ClipboardUiManager(this, clipboardPaletteView, new ClipboardUiManager.ClipboardListener() {
            @Override
            public void onPasteItem(String text) {
                if (isTranslationMode) {
                    translationBuffer.append(text);
                    translationUiManager.updateInputPreview(translationBuffer.toString());
                    translationUiManager.performTranslation(translationBuffer.toString());
                    toggleClipboardPalette(); 
                } else if (isDirectTranslateEnabled) {
                    directBuffer.append(text);
                    performDirectTranslation(directBuffer.toString());
                    toggleClipboardPalette();
                } else {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(text, 1);
                        PredictionEngine.getInstance(BubbleKeyboardService.this).learnWord(text);
                        lastCommittedWord = text.trim(); 
                    }
                    toggleClipboardPalette(); 
                    updateCandidates("");
                }
            }

            @Override
            public void onCloseClipboard() {
                toggleClipboardPalette();
            }
        });
        mainLayout.addView(clipboardPaletteView);

        return mainLayout;
    }

    private void setupToolbarButtons() {
        btnClipboard = candidateView.findViewById(R.id.btn_clipboard);
        if (btnClipboard != null) btnClipboard.setOnClickListener(v -> toggleClipboardPalette());
        
        btnKeyboardSwitch = candidateView.findViewById(R.id.btn_keyboard_switch);
        if (btnKeyboardSwitch != null) {
            btnKeyboardSwitch.setOnClickListener(v -> {
                InputMethodManager ime = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (ime != null) ime.showInputMethodPicker();
            });
        }
        
        btnTranslate = candidateView.findViewById(R.id.btn_translate);
        if (btnTranslate != null) btnTranslate.setOnClickListener(v -> toggleTranslationMode());
        
        // DIRECT TRANSLATION (Globe)
        btnDirectTranslate = candidateView.findViewById(R.id.btn_direct_translate);
        if (btnDirectTranslate != null) {
            // Long Press: Select Language
            btnDirectTranslate.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showDirectLanguagePopup(v);
                    return true;
                }
            });

            // FIX: Single Tap Disable / Double Tap Enable
            btnDirectTranslate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isDirectTranslateEnabled) {
                        // If ON -> Single Tap turns it OFF
                        toggleDirectTranslationMode();
                    } else {
                        // If OFF -> Require Double Tap to turn ON
                        long clickTime = System.currentTimeMillis();
                        if (clickTime - lastGlobeClickTime < 500) {
                            toggleDirectTranslationMode();
                        } else {
                            Toast.makeText(BubbleKeyboardService.this, "Double tap to Enable", Toast.LENGTH_SHORT).show();
                        }
                        lastGlobeClickTime = clickTime;
                    }
                }
            });
        }

        btnBubbleLauncher = candidateView.findViewById(R.id.btn_bubble_launcher);
        if (btnBubbleLauncher != null) {
            btnBubbleLauncher.setOnClickListener(v -> {
                Intent intent = new Intent(BubbleKeyboardService.this, FloatingTranslatorService.class);
                intent.setAction("ACTION_SHOW_BUBBLE");
                startService(intent);
            });
        }
        
        btnOcrCopy = candidateView.findViewById(R.id.btn_ocr_copy);
        if (btnOcrCopy != null) {
            btnOcrCopy.setOnClickListener(v -> {
                requestHideSelf(0);
                Intent intent = new Intent(BubbleKeyboardService.this, FloatingTranslatorService.class);
                intent.setAction("ACTION_TRIGGER_COPY_ONLY");
                startService(intent);
            });
        }
    }

    // FIX 3: Use AlertDialog for language selection instead of PopupMenu for visibility
    private void showDirectLanguagePopup(View v) {
        AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light));
        builder.setTitle("Select Target Language");
        builder.setItems(LanguageUtils.LANGUAGE_NAMES, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int index = which;
                directTargetLangCode = LanguageUtils.getCode(index);
                Toast.makeText(BubbleKeyboardService.this, "Target: " + LanguageUtils.LANGUAGE_NAMES[index], Toast.LENGTH_SHORT).show();
            }
        });
        
        AlertDialog dialog = builder.create();
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.token = mainLayout.getWindowToken();
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }
        dialog.show();
    }

    private void toggleDirectTranslationMode() {
        isDirectTranslateEnabled = !isDirectTranslateEnabled;
        if (isDirectTranslateEnabled) {
            // Enable: Blue Tint
            btnDirectTranslate.setColorFilter(Color.parseColor("#2196F3"), PorterDuff.Mode.SRC_IN);
            Toast.makeText(this, "Live Translation ON (Auto -> " + directTargetLangCode + ")", Toast.LENGTH_SHORT).show();
            // Clear buffer to start fresh
            directBuffer.setLength(0);
            lastDirectOutputLength = 0;
        } else {
            // Disable: Reset Color
            btnDirectTranslate.clearColorFilter();
            Toast.makeText(this, "Live Translation OFF", Toast.LENGTH_SHORT).show();
        }
    }

    private void performDirectTranslation(final String text) {
        if (text.trim().isEmpty()) return;

        // Cancel pending
        if (directTranslateRunnable != null) directHandler.removeCallbacks(directTranslateRunnable);

        // Debounce (Wait 500ms before API call)
        directTranslateRunnable = new Runnable() {
            @Override
            public void run() {
                bgExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // "auto" for source detection
                        final String result = TranslateApi.translate("auto", directTargetLangCode, text);
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (result != null) {
                                    InputConnection ic = getCurrentInputConnection();
                                    if (ic != null) {
                                        // Delete previous translated chunk
                                        if (lastDirectOutputLength > 0) {
                                            ic.deleteSurroundingText(lastDirectOutputLength, 0);
                                        }
                                        // Write new translation
                                        ic.commitText(result, 1);
                                        lastDirectOutputLength = result.length();
                                    }
                                }
                            }
                        });
                    }
                });
            }
        };
        directHandler.postDelayed(directTranslateRunnable, 500);
    }

    private void setupEmojiControlButtons() {
        View btnBack = emojiPaletteView.findViewById(R.id.btn_back_to_abc);
        if (btnBack != null) btnBack.setOnClickListener(v -> toggleEmojiPalette());
        
        View btnDel = emojiPaletteView.findViewById(R.id.btn_emoji_backspace);
        if (btnDel != null) btnDel.setOnClickListener(v -> handleBackspace());
    }

    // =========================================================
    // KEY HANDLING
    // =========================================================

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        // Icon Visibility
        if (!isTranslationMode && !isDirectTranslateEnabled) {
            if (Character.isLetterOrDigit(primaryCode)) {
                if (toolbarContainer != null) toolbarContainer.setVisibility(View.GONE);
            } else if (primaryCode == 32 || primaryCode == 46 || currentWord.length() == 0) { 
                if (toolbarContainer != null) toolbarContainer.setVisibility(View.VISIBLE);
            }
        } else {
            // Keep icons visible in modes
            if (toolbarContainer != null) toolbarContainer.setVisibility(View.VISIBLE);
        }

        // --- DELETE KEY LOGIC ---
        if (primaryCode == Keyboard.KEYCODE_DELETE) {
            if (isTranslationMode) {
                if (translationBuffer.length() > 0) {
                    translationBuffer.deleteCharAt(translationBuffer.length() - 1);
                    translationUiManager.updateInputPreview(translationBuffer.toString());
                    if (translationBuffer.length() == 0) {
                        ic.deleteSurroundingText(lastSentTranslationLength, 0);
                        lastSentTranslationLength = 0;
                        updateCandidates("");
                    } else {
                        // Update suggestions after delete
                        updateTranslationCandidates();
                    }
                }
            } else if (isDirectTranslateEnabled) {
                // Direct Mode Delete
                if (directBuffer.length() > 0) {
                    directBuffer.deleteCharAt(directBuffer.length() - 1);
                    // Re-translate based on new buffer
                    performDirectTranslation(directBuffer.toString());
                    if (directBuffer.length() == 0) {
                        ic.deleteSurroundingText(lastDirectOutputLength, 0);
                        lastDirectOutputLength = 0;
                    }
                } else {
                    // Buffer empty, standard backspace
                    handleBackspace();
                }
            } else {
                if (justAutoCorrected) {
                    int lengthToDelete = lastCorrectedWord.length() + 1;
                    ic.deleteSurroundingText(lengthToDelete, 0);
                    ic.commitText(lastOriginalWord, 1);
                    currentWord.setLength(0);
                    currentWord.append(lastOriginalWord);
                    ignoreNextCorrection = true;
                    justAutoCorrected = false;
                } else {
                    handleBackspace();
                }
            }
            return;
        }

        if (primaryCode == Keyboard.KEYCODE_SHIFT) {
            isCaps = !isCaps;
            keyboardQwerty.setShifted(isCaps);
            kv.invalidateAllKeys();
            return;
        }

        if (primaryCode == Keyboard.KEYCODE_DONE) { 
            if (isTranslationMode) {
                translationUiManager.performTranslation(translationBuffer.toString());
            } else if (isDirectTranslateEnabled) {
                directBuffer.setLength(0);
                lastDirectOutputLength = 0;
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            } else {
                PredictionEngine.getInstance(this).learnWord(currentWord.toString());
                lastCommittedWord = currentWord.toString();
                currentWord.setLength(0); 
                updateCandidates("");
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            }
            return;
        }

        if (primaryCode == -2) { 
            if (kv.getKeyboard() == keyboardQwerty) kv.setKeyboard(keyboardSymbols);
            else kv.setKeyboard(keyboardQwerty);
            return;
        }

        if (primaryCode == -100) { toggleEmojiPalette(); return; }

        if (primaryCode == -10) { 
            requestHideSelf(0);
            Intent intent = new Intent(BubbleKeyboardService.this, TwoLineOverlayService.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startService(intent);
            return;
        }

        // --- SPACE KEY LOGIC ---
        if (primaryCode == 32) { 
            if (!isSpaceLongPressed) {
                if (isTranslationMode) {
                    translationBuffer.append(" ");
                    translationUiManager.updateInputPreview(translationBuffer.toString());
                    updateTranslationCandidates();
                } else if (isDirectTranslateEnabled) {
                    directBuffer.append(" ");
                    performDirectTranslation(directBuffer.toString());
                } else {
                    String typo = currentWord.toString();
                    boolean correctionApplied = false;
                    
                    if (!ignoreNextCorrection && typo.length() > 1) {
                        String correction = PredictionEngine.getInstance(this).getBestMatch(typo);
                        if (correction != null && !correction.equals(typo)) {
                            lastOriginalWord = typo;
                            lastCorrectedWord = correction;
                            justAutoCorrected = true;
                            ic.deleteSurroundingText(typo.length(), 0);
                            ic.commitText(correction, 1);
                            currentWord.setLength(0);
                            currentWord.append(correction);
                            correctionApplied = true;
                        }
                    } 
                    if (!correctionApplied) {
                        justAutoCorrected = false;
                        ignoreNextCorrection = false;
                    }
                    ic.commitText(" ", 1);
                    String justTyped = currentWord.toString();
                    PredictionEngine.getInstance(this).learnWord(justTyped);
                    if (lastCommittedWord != null && !lastCommittedWord.isEmpty()) {
                        PredictionEngine.getInstance(this).learnNextWord(lastCommittedWord, justTyped);
                    }
                    lastCommittedWord = justTyped;
                    currentWord.setLength(0); 
                    updateCandidates("");
                }
            }
            return;
        }

        // Normal Characters
        char code = (char) primaryCode;
        if (Character.isLetter(code) && isCaps) {
            code = Character.toUpperCase(code);
        }

        if (isTranslationMode) {
            translationBuffer.append(code);
            translationUiManager.updateInputPreview(translationBuffer.toString());
            // FIX 2: Show candidates while typing in translation field
            updateTranslationCandidates();
        } else if (isDirectTranslateEnabled) {
            directBuffer.append(code);
            performDirectTranslation(directBuffer.toString());
        } else {
            ic.commitText(String.valueOf(code), 1);
            justAutoCorrected = false; 
            
            if (Character.isLetterOrDigit(code)) {
                currentWord.append(code);
                updateCandidates(currentWord.toString());
            } else {
                PredictionEngine.getInstance(this).learnWord(currentWord.toString());
                lastCommittedWord = currentWord.toString();
                currentWord.setLength(0);
                updateCandidates("");
            }
        }
    }

    private void handleBackspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.deleteSurroundingText(1, 0);
            if (currentWord.length() > 0) {
                currentWord.deleteCharAt(currentWord.length() - 1);
                updateCandidates(currentWord.toString());
            } else {
                if (toolbarContainer != null) toolbarContainer.setVisibility(View.VISIBLE);
                updateCandidates("");
            }
        }
    }

    private void toggleEmojiPalette() {
        if (emojiPaletteView.getVisibility() == View.GONE) {
            kv.setVisibility(View.GONE);
            candidateView.setVisibility(View.GONE);
            clipboardPaletteView.setVisibility(View.GONE);
            translationPanelView.setVisibility(View.GONE);
            isTranslationMode = false;
            emojiPaletteView.setVisibility(View.VISIBLE);
        } else {
            resetToStandardKeyboard();
        }
    }

    private void toggleClipboardPalette() {
        if (clipboardPaletteView.getVisibility() == View.GONE) {
            kv.setVisibility(View.GONE);
            if (isTranslationMode) {
                translationPanelView.setVisibility(View.VISIBLE);
            } else {
                translationPanelView.setVisibility(View.GONE);
            }
            candidateView.setVisibility(View.GONE);
            emojiPaletteView.setVisibility(View.GONE);
            clipboardPaletteView.setVisibility(View.VISIBLE);
            if (clipboardUiManager != null) clipboardUiManager.reloadHistory();
        } else {
            clipboardPaletteView.setVisibility(View.GONE);
            if (isTranslationMode) {
                translationPanelView.setVisibility(View.VISIBLE);
                candidateView.setVisibility(View.VISIBLE); 
                kv.setVisibility(View.VISIBLE);
            } else {
                resetToStandardKeyboard();
            }
        }
    }

    private void toggleTranslationMode() {
        if (translationPanelView.getVisibility() == View.GONE) {
            candidateView.setVisibility(View.VISIBLE); 
            clipboardPaletteView.setVisibility(View.GONE);
            emojiPaletteView.setVisibility(View.GONE);
            translationPanelView.setVisibility(View.VISIBLE);
            kv.setVisibility(View.VISIBLE);
            isTranslationMode = true;
            translationBuffer.setLength(0); 
            lastSentTranslationLength = 0; 
            translationUiManager.updateInputPreview("");
        } else {
            resetToStandardKeyboard();
        }
    }

    private void resetToStandardKeyboard() {
        emojiPaletteView.setVisibility(View.GONE);
        clipboardPaletteView.setVisibility(View.GONE);
        translationPanelView.setVisibility(View.GONE);
        candidateView.setVisibility(View.VISIBLE);
        kv.setVisibility(View.VISIBLE);
        isTranslationMode = false;
    }

    @Override
    public void onPress(int primaryCode) {
        if (primaryCode == 32) {
            isSpaceLongPressed = false; 
            longPressHandler.postDelayed(spaceLongPressRunnable, LONG_PRESS_DELAY);
        }
    }

    @Override
    public void onRelease(int primaryCode) {
        if (primaryCode == 32) {
            longPressHandler.removeCallbacks(spaceLongPressRunnable);
        }
    }

    // Helper to extract the last word in the translation buffer for predictions
    private void updateTranslationCandidates() {
        String text = translationBuffer.toString();
        String word = "";
        if (!text.isEmpty()) {
            if (text.endsWith(" ")) {
                word = ""; // Show next word suggestions?
            } else {
                int lastSpace = text.lastIndexOf(' ');
                if (lastSpace != -1) {
                    word = text.substring(lastSpace + 1);
                } else {
                    word = text;
                }
            }
        }
        updateCandidates(word);
    }

    private void updateCandidates(String wordBeingTyped) {
        if (candidateContainer == null) return;
        
        candidateContainer.removeAllViews();
        List<String> suggestions;

        if (wordBeingTyped.isEmpty()) {
            if (lastCommittedWord != null) {
                suggestions = PredictionEngine.getInstance(this).getNextWordSuggestions(lastCommittedWord);
            } else {
                suggestions = PredictionEngine.getInstance(this).getSuggestions(""); 
            }
        } else {
            suggestions = PredictionEngine.getInstance(this).getSuggestions(wordBeingTyped);
        }

        for (final String word : suggestions) {
            TextView tv = new TextView(this);
            tv.setText(word);
            tv.setTextSize(18);
            tv.setPadding(40, 20, 40, 20);
            tv.setBackgroundResource(android.R.drawable.list_selector_background);
            
            tv.setOnClickListener(v -> {
                if (isTranslationMode) {
                    // Logic to insert word into translation buffer
                    String text = translationBuffer.toString();
                    int lastSpace = text.lastIndexOf(' ');
                    String prefix = (lastSpace == -1) ? "" : text.substring(0, lastSpace + 1);
                    translationBuffer.setLength(0);
                    translationBuffer.append(prefix).append(word).append(" ");
                    
                    translationUiManager.updateInputPreview(translationBuffer.toString());
                    translationUiManager.performTranslation(translationBuffer.toString());
                    updateCandidates("");
                } else {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        if (currentWord.length() > 0) {
                            ic.deleteSurroundingText(currentWord.length(), 0);
                        }
                        ic.commitText(word + " ", 1);
                        PredictionEngine.getInstance(this).learnWord(word);
                        if (lastCommittedWord != null) {
                            PredictionEngine.getInstance(this).learnNextWord(lastCommittedWord, word);
                        }
                        lastCommittedWord = word;
                        currentWord.setLength(0);
                        updateCandidates("");
                    }
                }
            });
            candidateContainer.addView(tv);
        }
    }

    @Override public void onText(CharSequence text) {}
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}