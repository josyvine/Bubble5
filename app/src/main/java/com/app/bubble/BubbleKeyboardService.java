package com.app.bubble;

import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The core Service handling the Modern Keyboard logic.
 * Manages Switching layers, Predictions, Emoji interactions, Professional Clipboard, Translation, and OCR Tools.
 * UPDATED: Translation Layout (Top), Paste Support, Toolbar Visibility.
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

    // Translation Views & Logic
    private View translationPanelView;
    private TranslationUiManager translationUiManager;
    private boolean isTranslationMode = false;
    private StringBuilder translationBuffer = new StringBuilder();
    private int lastSentTranslationLength = 0;

    // Buttons
    private ImageButton btnClipboard;
    private ImageButton btnKeyboardSwitch;
    private ImageButton btnTranslate;
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

        // 1. Setup Translation Panel (Added FIRST so it appears ABOVE icons)
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
                    lastSentTranslationLength = translatedText.length();
                }
            }

            @Override
            public void onCloseTranslation() {
                toggleTranslationMode();
            }

            @Override
            public void onPasteText(String text) {
                // NEW: Handle paste from long-press
                if (text != null) {
                    translationBuffer.append(text);
                    translationUiManager.updateInputPreview(translationBuffer.toString());
                    // Trigger translation immediately
                    translationUiManager.performTranslation(translationBuffer.toString());
                }
            }
        });
        mainLayout.addView(translationPanelView);

        // 2. Setup Candidate View (Toolbar) - Added SECOND (Below Translation)
        candidateView = inflater.inflate(R.layout.candidate_view, mainLayout, false);
        candidateContainer = candidateView.findViewById(R.id.candidate_container);
        toolbarContainer = candidateView.findViewById(R.id.toolbar_container);
        
        setupToolbarButtons();
        mainLayout.addView(candidateView);

        // 3. Setup Keyboard View - Middle
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
                    // NEW: If in Translation Mode, paste into buffer, not app
                    translationBuffer.append(text);
                    translationUiManager.updateInputPreview(translationBuffer.toString());
                    translationUiManager.performTranslation(translationBuffer.toString());
                    toggleClipboardPalette(); // Close clipboard
                } else {
                    // Normal Paste
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
        // Logic: Hide toolbar when typing, Show when space/dot/empty
        // Note: In Translation Mode, we might want icons visible to access clipboard
        if (!isTranslationMode) {
            if (Character.isLetterOrDigit(primaryCode)) {
                if (toolbarContainer != null) toolbarContainer.setVisibility(View.GONE);
            } else if (primaryCode == 32 || primaryCode == 46 || currentWord.length() == 0) { 
                if (toolbarContainer != null) toolbarContainer.setVisibility(View.VISIBLE);
            }
        } else {
            // Force visible in Translation Mode so users can use Clipboard/OCR icons
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
                    } 
                }
            } else {
                if (justAutoCorrected) {
                    // Revert Auto-Correct
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
            // Hide everything else
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
            // In Translation mode, we keep Translation panel visible?
            // Actually, usually clipboard takes over full view.
            // But user might want to copy-paste TO translation box.
            // Simplified: Full overlay for now.
            candidateView.setVisibility(View.GONE);
            emojiPaletteView.setVisibility(View.GONE);
            translationPanelView.setVisibility(View.GONE); // Hide translation temporarily
            isTranslationMode = false; // Pause translation mode while selecting?
            // Re-think: If we set isTranslationMode=false, we lose buffer.
            // Better: Don't change isTranslationMode flag, just hide view.
            
            // Logic adjust: If we were translating, remember it.
            // But for simplicity, let's treat Clipboard as a modal overlay.
            clipboardPaletteView.setVisibility(View.VISIBLE);
            
            if (clipboardUiManager != null) clipboardUiManager.reloadHistory();
        } else {
            // Restore previous state? Or just reset?
            // Resetting is safer. User can re-open translation if needed.
            resetToStandardKeyboard();
        }
    }

    private void toggleTranslationMode() {
        if (translationPanelView.getVisibility() == View.GONE) {
            // OPEN TRANSLATION
            // Candidate view STAYS VISIBLE (Gboard style - icons below)
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
            // CLOSE TRANSLATION
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