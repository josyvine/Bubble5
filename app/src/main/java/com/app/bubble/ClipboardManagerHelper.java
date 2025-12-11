package com.app.bubble;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages clipboard history for the custom keyboard.
 * Stores the last 10 copied items.
 * UPDATED: Prevents deleted items from reappearing (Ghost Item Fix).
 */
public class ClipboardManagerHelper {

    private static ClipboardManagerHelper instance;
    private ClipboardManager systemClipboard;
    private SharedPreferences prefs;
    private List<String> clipHistory;
    private Context mContext; 
    
    // FIX: Variable to ignore the item we just deleted so it doesn't auto-add back
    private String lastDeletedText = null;
    
    private static final String PREFS_NAME = "BubbleClipboardPrefs";
    private static final String KEY_HISTORY = "ClipHistoryString";
    private static final int MAX_HISTORY_SIZE = 10;
    private static final String DELIMITER = "#####"; 

    private ClipboardManagerHelper(Context context) {
        this.mContext = context;
        systemClipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        clipHistory = new ArrayList<>();
        
        loadHistory();
        syncWithSystemClipboard();
    }

    public static synchronized ClipboardManagerHelper getInstance(Context context) {
        if (instance == null) {
            instance = new ClipboardManagerHelper(context);
        }
        return instance;
    }

    /**
     * Checks if the system clipboard has new text and adds it to our history.
     */
    public void syncWithSystemClipboard() {
        if (systemClipboard != null && systemClipboard.hasPrimaryClip()) {
            if (systemClipboard.getPrimaryClip().getItemCount() > 0) {
                ClipData.Item item = systemClipboard.getPrimaryClip().getItemAt(0);
                if (item != null && item.getText() != null) {
                    String currentText = item.getText().toString();
                    
                    // FIX: If this text matches what we just deleted, IGNORE IT.
                    if (currentText.equals(lastDeletedText)) {
                        return;
                    }
                    
                    addClip(currentText);
                }
            }
        }
    }

    /**
     * Adds a text to the history (Top of the list).
     * Removes duplicates and keeps size limited.
     */
    public void addClip(String text) {
        if (text == null || text.trim().isEmpty()) return;

        // Reset the ignored item since a new copy action happened
        lastDeletedText = null;

        if (clipHistory.contains(text)) {
            clipHistory.remove(text);
        }
        clipHistory.add(0, text);

        if (clipHistory.size() > MAX_HISTORY_SIZE) {
            clipHistory.remove(clipHistory.size() - 1);
        }

        saveHistory();

        // Learn Vocabulary from Clipboard
        String[] words = text.split("\\s+");
        PredictionEngine engine = PredictionEngine.getInstance(mContext);
        
        for (String word : words) {
            if (word.length() > 1) {
                String cleanWord = word.replaceAll("[^a-zA-Z0-9]", "");
                if (!cleanWord.isEmpty()) {
                    engine.learnWord(cleanWord);
                }
            }
        }
    }

    /**
     * Permanently delete an item (Swipe-to-Delete)
     */
    public void deleteItem(String text) {
        if (clipHistory.contains(text)) {
            // FIX: Mark this text as "Just Deleted" so sync() ignores it
            lastDeletedText = text;
            
            clipHistory.remove(text);
            saveHistory();
        }
    }

    /**
     * Restore an item (Undo Action)
     */
    public void restoreItem(String text, int position) {
        // If we restore it, we should allow it to be synced again
        if (text.equals(lastDeletedText)) {
            lastDeletedText = null;
        }

        if (position < 0) position = 0;
        if (position > clipHistory.size()) position = clipHistory.size();
        
        clipHistory.add(position, text);
        saveHistory();
    }

    public List<String> getHistory() {
        syncWithSystemClipboard(); 
        return new ArrayList<>(clipHistory);
    }

    public void clearHistory() {
        clipHistory.clear();
        saveHistory();
    }

    // --- Persistence Logic ---

    private void saveHistory() {
        String joined = TextUtils.join(DELIMITER, clipHistory);
        prefs.edit().putString(KEY_HISTORY, joined).apply();
    }

    private void loadHistory() {
        String saved = prefs.getString(KEY_HISTORY, "");
        if (!saved.isEmpty()) {
            String[] items = saved.split(DELIMITER);
            clipHistory.clear();
            clipHistory.addAll(Arrays.asList(items));
        }
    }
}