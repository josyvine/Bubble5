package com.txtify.app;

import android.content.ClipData;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FileBrowserAdapter extends RecyclerView.Adapter<FileBrowserAdapter.ViewHolder> {

    private final List<File> files;
    private final Context context;
    private OnItemClickListener clickListener;
    private OnDragStartListener dragListener;

    // MODIFIED: This is now a reference to the master set from the Fragment.
    private final Set<File> selectedFiles;
    private final int selectionColor;


    public interface OnItemClickListener {
        void onItemClick(File file);
    }

    public interface OnDragStartListener {
        void onDragStarted();
    }

    // MODIFIED: Constructor now accepts the master selection set.
    public FileBrowserAdapter(Context context, List<File> files, Set<File> selectedFiles, OnItemClickListener clickListener, OnDragStartListener dragListener) {
        this.context = context;
        this.files = files;
        this.selectedFiles = selectedFiles; // Use the passed-in master set.
        this.clickListener = clickListener;
        this.dragListener = dragListener;

        // A semi-transparent version of the accent color for highlighting selections.
        this.selectionColor = 0x5000ACC1;

        Collections.sort(this.files, new Comparator<File>() {
				@Override
				public int compare(File f1, File f2) {
					if (f1.isDirectory() && !f2.isDirectory()) {
						return -1;
					} else if (!f1.isDirectory() && f2.isDirectory()) {
						return 1;
					} else {
						return f1.getName().compareToIgnoreCase(f2.getName());
					}
				}
			});
    }

    // This method adds all files (not directories) from the CURRENT list to the master set.
    public void selectAllFiles() {
        for (File file : files) {
            if (!file.isDirectory()) {
                selectedFiles.add(file);
            }
        }
        notifyDataSetChanged(); // Redraw the entire list to show selections.
    }

    // This method clears the ENTIRE master selection set.
    public void clearSelection() {
        selectedFiles.clear();
        notifyDataSetChanged();
    }


    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.list_item_file, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File file = files.get(position);
        holder.bind(file, clickListener, dragListener);
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    // The ViewHolder class needs to be an inner class to access adapter members like selectedFiles.
    class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon_file_type);
            name = itemView.findViewById(R.id.text_file_name);
        }

        public void bind(final File file, final OnItemClickListener clickListener, final OnDragStartListener dragListener) {
            name.setText(file.getName());

            if (file.isDirectory()) {
                icon.setImageResource(R.drawable.ic_menu_browse);
            } else {
                icon.setImageResource(R.drawable.ic_file_generic);
            }

            // Set the background color based on selection state (using the master set).
            if (selectedFiles.contains(file)) {
                itemView.setBackgroundColor(selectionColor);
            } else {
                itemView.setBackgroundColor(Color.TRANSPARENT);
            }

            // Short click handles selection for files. This now modifies the master set.
            itemView.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (file.isDirectory()) {
							// Clicking a directory still navigates.
							clickListener.onItemClick(file);
						} else {
							// Clicking a file toggles its selection in the master set.
							if (selectedFiles.contains(file)) {
								selectedFiles.remove(file);
							} else {
								selectedFiles.add(file);
							}
							// Redraw this specific item to update its background.
							notifyItemChanged(getAdapterPosition());
						}
					}
				});

            // Long click initiates a drag. This functionality is preserved.
            if (!file.isDirectory()) {
                itemView.setOnLongClickListener(new View.OnLongClickListener() {
						@Override
						public boolean onLongClick(View v) {
							List<File> filesToDrag = new ArrayList<>();

							// If other files are selected, drag the whole selection.
							// Otherwise, just drag the file that was long-pressed.
							if (!selectedFiles.isEmpty() && selectedFiles.contains(file)) {
								filesToDrag.addAll(selectedFiles);
							} else {
								filesToDrag.add(file);
							}

							// If no files are ready to be dragged, do nothing.
							if (filesToDrag.isEmpty()) {
								return false;
							}

							// Build a single string with all file paths, separated by a newline.
							StringBuilder paths = new StringBuilder();
							for (int i = 0; i < filesToDrag.size(); i++) {
								paths.append(filesToDrag.get(i).getAbsolutePath());
								if (i < filesToDrag.size() - 1) {
									paths.append("\n");
								}
							}

							ClipData.Item item = new ClipData.Item(paths.toString());
							ClipData dragData = new ClipData(
								"file_paths", // Note plural label
								new String[]{"text/plain"},
								item
							);

							View.DragShadowBuilder myShadow = new View.DragShadowBuilder(itemView);

							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
								int dragFlags = View.DRAG_FLAG_GLOBAL;
								v.startDragAndDrop(dragData, myShadow, null, dragFlags);
							} else {
								v.startDrag(dragData, myShadow, null, 0);
							}

							if (dragListener != null) {
								dragListener.onDragStarted();
							}

							return true; 
						}
					});
            } else {
                itemView.setOnLongClickListener(null);
            }
        }
    }
}

