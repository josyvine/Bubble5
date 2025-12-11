package com.app.bubble;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the Professional Gboard-style Clipboard UI.
 * Handles Grid Layout, Swipe-to-Delete, and Undo logic.
 */
public class ClipboardUiManager {

    private Context context;
    private View rootView;
    private ClipboardListener listener;
    private RecyclerView recyclerView;
    private ClipboardAdapter adapter;
    private View undoContainer;
    private Handler handler = new Handler(Looper.getMainLooper());

    // Undo State
    private Runnable hideUndoRunnable;
    private String lastDeletedItem;
    private int lastDeletedPos;

    public interface ClipboardListener {
        void onPasteItem(String text);
        void onCloseClipboard();
    }

    public ClipboardUiManager(Context context, View rootView, ClipboardListener listener) {
        this.context = context;
        this.rootView = rootView;
        this.listener = listener;
        setupViews();
    }

    private void setupViews() {
        recyclerView = rootView.findViewById(R.id.clipboard_recycler);
        undoContainer = rootView.findViewById(R.id.undo_container);
        Button btnUndo = rootView.findViewById(R.id.btn_undo);
        ImageButton btnBack = rootView.findViewById(R.id.btn_back_keyboard);

        // 1. Setup RecyclerView (Staggered Grid for Masonry/Card look)
        StaggeredGridLayoutManager layoutManager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);

        adapter = new ClipboardAdapter();
        recyclerView.setAdapter(adapter);

        // 2. Setup Swipe-to-Delete
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                deleteItem(position);
            }
        });
        itemTouchHelper.attachToRecyclerView(recyclerView);

        // 3. Button Listeners
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onCloseClipboard();
            }
        });

        btnUndo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performUndo();
            }
        });
        
        // Initial Load
        reloadHistory();
    }

    public void reloadHistory() {
        List<String> history = ClipboardManagerHelper.getInstance(context).getHistory();
        adapter.setData(history);
    }

    private void deleteItem(int position) {
        // 1. Get Item
        lastDeletedItem = adapter.getData().get(position);
        lastDeletedPos = position;

        // 2. Remove from Adapter (Visual)
        adapter.removeItem(position);

        // 3. Remove from Permanent Storage
        ClipboardManagerHelper.getInstance(context).deleteItem(lastDeletedItem);

        // 4. Show Undo Bar
        showUndoBar();
    }

    private void showUndoBar() {
        undoContainer.setVisibility(View.VISIBLE);
        // Hide after 3 seconds
        if (hideUndoRunnable != null) handler.removeCallbacks(hideUndoRunnable);
        hideUndoRunnable = new Runnable() {
            @Override
            public void run() {
                undoContainer.setVisibility(View.GONE);
                lastDeletedItem = null; // Clear undo cache
            }
        };
        handler.postDelayed(hideUndoRunnable, 3000);
    }

    private void performUndo() {
        if (lastDeletedItem != null) {
            // Restore to Storage
            ClipboardManagerHelper.getInstance(context).restoreItem(lastDeletedItem, lastDeletedPos);
            
            // Restore to Adapter
            adapter.restoreItem(lastDeletedItem, lastDeletedPos);
            
            // Hide Undo Bar
            undoContainer.setVisibility(View.GONE);
            if (hideUndoRunnable != null) handler.removeCallbacks(hideUndoRunnable);
        }
    }

    // --- Adapter Class ---
    private class ClipboardAdapter extends RecyclerView.Adapter<ClipboardAdapter.ClipViewHolder> {
        private List<String> data = new ArrayList<>();

        public void setData(List<String> newData) {
            this.data = new ArrayList<>(newData);
            notifyDataSetChanged();
        }

        public List<String> getData() {
            return data;
        }

        public void removeItem(int position) {
            data.remove(position);
            notifyItemRemoved(position);
        }

        public void restoreItem(String item, int position) {
            if (position >= 0 && position <= data.size()) {
                data.add(position, item);
                notifyItemInserted(position);
            } else {
                data.add(0, item);
                notifyItemInserted(0);
            }
        }

        @NonNull
        @Override
        public ClipViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_clipboard_card, parent, false);
            return new ClipViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ClipViewHolder holder, int position) {
            final String text = data.get(position);
            holder.textContent.setText(text);
            
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onPasteItem(text);
                }
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class ClipViewHolder extends RecyclerView.ViewHolder {
            TextView textContent;

            ClipViewHolder(View itemView) {
                super(itemView);
                textContent = itemView.findViewById(R.id.text_content);
            }
        }
    }
}