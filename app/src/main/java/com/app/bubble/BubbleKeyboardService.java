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
 * Manages Switching layers, Predictions, Emoji interactions, Professional Clipboard, and Translation.
 */
public class BubbleKeyboardService extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    // Main Views
    private LinearLayout mainLayout;
    private KeyboardView kv;
    private View candidateView;
    private LinearLayout candidateContainer;
    private View emojiPaletteView;
    
    // Professional Clipboard Views
    private View clipboardPaletteView;
    private ClipboardUiManager clipboardUiManager;

    // Translation Views & Logic
    private View translationPanelView;
    private TranslationUiManager translationUiManager;
    private boolean isTranslationMode = false;
    private StringBuilder translationBuffer = new StringBuilder();

    // Buttons
    private ImageButton btnClipboard;
    private ImageButton btnKeyboardSwitch;
    private ImageButton btnTranslate;

    // Keyboards
    private Keyboard keyboardQwerty;
    private Keyboard keyboardSymbols;

    // State
    private boolean isCaps = false;
    private boolean isEmojiVisible = false;
    private StringBuilder currentWord = new StringBuilder(); // Track typing for predictions

    // Long Press Logic for Space Key (kept as backup)
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
        // 1. Create the Main Container (Vertical)
        mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);

        LayoutInflater inflater = getLayoutInflater();

        // 2. Add Candidate View (Predictions + Toolbar)
        candidateView = inflater.inflate(R.layout.candidate_view, mainLayout, false);
        candidateContainer = candidateView.findViewById(R.id.candidate_container);
        
        // Setup Toolbar Buttons
        setupToolbarButtons();
        
        mainLayout.addView(candidateView);

        // 3. Add Translation Panel (Hidden by default)
        // This replaces the candidate view when active
        translationPanelView = inflater.inflate(R.layout.layout_translation_panel, mainLayout, false);
        translationPanelView.setVisibility(View.GONE);
        
        translationUiManager = new TranslationUiManager(this, translationPanelView, new TranslationUiManager.TranslationListener() {
            @Override
            public void onTranslationResult(String translatedText) {
                // Commit the TRANSLATED text to the app
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    ic.commitText(translatedText, 1);
                }
                // Clear buffer after sending
                translationBuffer.setLength(0);
            }

            @Override
            public void onCloseTranslation() {
                toggleTranslationMode();
            }
        });
        mainLayout.addView(translationPanelView);

        // 4. Add Keyboard View - Middle
        kv = (KeyboardView) inflater.inflate(R.layout.layout_real_keyboard, mainLayout, false);
        keyboardQwerty = new Keyboard(this, R.xml.qwerty);
        keyboardSymbols = new Keyboard(this, R.xml.symbols);
        kv.setKeyboard(keyboardQwerty);
        kv.setOnKeyboardActionListener(this);
        kv.setPreviewEnabled(false); 
        mainLayout.addView(kv);

        // 5. Add Emoji Palette (Hidden by default)
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

        // 6. Add Professional Clipboard Palette (Hidden by default)
        clipboardPaletteView = inflater.inflate(R.layout.layout_clipboard_palette, mainLayout, false);
        clipboardPaletteView.setVisibility(View.GONE);
        
        clipboardUiManager = new ClipboardUiManager(this, clipboardPaletteView, new ClipboardUiManager.ClipboardListener() {
            @Override
            public void onPasteItem(String text) {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    ic.commitText(text, 1);
                    PredictionEngine.getInstance(BubbleKeyboardService.this).learnWord(text);
                }
                toggleClipboardPalette(); 
                updateCandidates(currentWord.toString());
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
        // Clipboard Button
        btnClipboard = candidateView.findViewById(R.id.btn_clipboard);
        if (btnClipboard != null) {
            btnClipboard.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleClipboardPalette();
                }
            });
        }

        // Keyboard Switcher Button
        btnKeyboardSwitch = candidateView.findViewById(R.id.btn_keyboard_switch);
        if (btnKeyboardSwitch != null) {
            btnKeyboardSwitch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    InputMethodManager ime = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (ime != null) {
                        ime.showInputMethodPicker();
                    }
                }
            });
        }

        // Translate Button
        btnTranslate = candidateView.findViewById(R.id.btn_translate);
        if (btnTranslate != null) {
            btnTranslate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleTranslationMode();
                }
            });
        }
    }

    private void setupEmojiControlButtons() {
        View btnBack = emojiPaletteView.findViewById(R.id.btn_back_to_abc);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleEmojiPalette();
                }
            });
        }
        View btnDel = emojiPaletteView.findViewById(R.id.btn_emoji_backspace);
        if (btnDel != null) {
            btnDel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    handleBackspace();
                }
            });
        }
    }

    // =========================================================
    // KEY HANDLING (UPDATED for Translation)
    // =========================================================

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        // 1. Handle Special Keys regardless of mode
        if (primaryCode == Keyboard.KEYCODE_DELETE) {
            if (isTranslationMode) {
                // Delete from Translation Buffer
                if (translationBuffer.length() > 0) {
                    translationBuffer.deleteCharAt(translationBuffer.length() - 1);
                    translationUiManager.updateInputPreview(translationBuffer.toString());
                }
            } else {
                handleBackspace();
            }
            return;
        }

        if (primaryCode == Keyboard.KEYCODE_SHIFT) {
            isCaps = !isCaps;
            keyboardQwerty.setShifted(isCaps);
            kv.invalidateAllKeys();
            return;
        }

        if (primaryCode == Keyboard.KEYCODE_DONE) { // Enter Key
            if (isTranslationMode) {
                // Trigger Translation
                translationUiManager.performTranslation(translationBuffer.toString());
            } else {
                // Standard Enter
                PredictionEngine.getInstance(this).learnWord(currentWord.toString());
                currentWord.setLength(0); 
                updateCandidates("");
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            }
            return;
        }

        if (primaryCode == -2) { // Mode Switch ?123
            if (kv.getKeyboard() == keyboardQwerty) {
                kv.setKeyboard(keyboardSymbols);
            } else {
                kv.setKeyboard(keyboardQwerty);
            }
            return;
        }

        if (primaryCode == -100) { // Emoji Toggle
            toggleEmojiPalette();
            return;
        }

        if (primaryCode == -10) { // Copy Tool
            requestHideSelf(0);
            Intent intent = new Intent(BubbleKeyboardService.this, TwoLineOverlayService.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startService(intent);
            return;
        }

        // 2. Handle Text Input
        if (primaryCode == 32) { // Space
            if (!isSpaceLongPressed) {
                if (isTranslationMode) {
                    translationBuffer.append(" ");
                    translationUiManager.updateInputPreview(translationBuffer.toString());
                } else {
                    ic.commitText(" ", 1);
                    PredictionEngine.getInstance(this).learnWord(currentWord.toString());
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
            // Add to buffer, update UI, DO NOT commit to app yet
            translationBuffer.append(code);
            translationUiManager.updateInputPreview(translationBuffer.toString());
        } else {
            // Standard Typing
            ic.commitText(String.valueOf(code), 1);
            if (Character.isLetterOrDigit(code)) {
                currentWord.append(code);
                updateCandidates(currentWord.toString());
            } else {
                PredictionEngine.getInstance(this).learnWord(currentWord.toString());
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
                updateCandidates("");
            }
        }
    }

    // =========================================================
    // VIEW SWITCHING MANAGEMENT
    // =========================================================

    private void toggleEmojiPalette() {
        if (emojiPaletteView.getVisibility() == View.GONE) {
            // Show Emojis
            kv.setVisibility(View.GONE);
            candidateView.setVisibility(View.GONE);
            clipboardPaletteView.setVisibility(View.GONE);
            translationPanelView.setVisibility(View.GONE); // Ensure translation is closed
            isTranslationMode = false;
            emojiPaletteView.setVisibility(View.VISIBLE);
        } else {
            // Show Keyboard
            resetToStandardKeyboard();
        }
    }

    private void toggleClipboardPalette() {
        if (clipboardPaletteView.getVisibility() == View.GONE) {
            // Show Clipboard
            kv.setVisibility(View.GONE);
            candidateView.setVisibility(View.GONE);
            emojiPaletteView.setVisibility(View.GONE);
            translationPanelView.setVisibility(View.GONE); // Ensure translation is closed
            isTranslationMode = false;
            clipboardPaletteView.setVisibility(View.VISIBLE);
            
            if (clipboardUiManager != null) {
                clipboardUiManager.reloadHistory();
            }
        } else {
            resetToStandardKeyboard();
        }
    }

    private void toggleTranslationMode() {
        if (translationPanelView.getVisibility() == View.GONE) {
            // Show Translation Panel
            candidateView.setVisibility(View.GONE); // Hide suggestions
            clipboardPaletteView.setVisibility(View.GONE);
            emojiPaletteView.setVisibility(View.GONE);
            
            translationPanelView.setVisibility(View.VISIBLE);
            kv.setVisibility(View.VISIBLE); // Keyboard MUST remain visible to type!
            
            isTranslationMode = true;
            translationBuffer.setLength(0); // Clear buffer
            translationUiManager.updateInputPreview("");
        } else {
            resetToStandardKeyboard();
        }
    }

    private void resetToStandardKeyboard() {
        emojiPaletteView.setVisibility(View.GONE);
        clipboardPaletteView.setVisibility(View.GONE);
        translationPanelView.setVisibility(View.GONE);
        candidateView.setVisibility(View.VISIBLE); // Show suggestions again
        kv.setVisibility(View.VISIBLE);
        isTranslationMode = false;
    }

    // =========================================================
    // TOUCH EVENTS
    // =========================================================

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

    // =========================================================
    // PREDICTION
    // =========================================================

    private void updateCandidates(String wordBeingTyped) {
        if (candidateContainer == null) return;
        
        candidateContainer.removeAllViews();
        
        List<String> suggestions = PredictionEngine.getInstance(this).getSuggestions(wordBeingTyped);

        if (suggestions.isEmpty() && wordBeingTyped.isEmpty()) {
            suggestions = PredictionEngine.getInstance(this).getSuggestions(""); 
        }

        for (final String word : suggestions) {
            TextView tv = new TextView(this);
            tv.setText(word);
            tv.setTextSize(18);
            tv.setPadding(40, 20, 40, 20);
            tv.setBackgroundResource(android.R.drawable.list_selector_background);
            
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        if (currentWord.length() > 0) {
                            ic.deleteSurroundingText(currentWord.length(), 0);
                        }
                        ic.commitText(word + " ", 1);
                        PredictionEngine.getInstance(BubbleKeyboardService.this).learnWord(word);
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