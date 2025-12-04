package com.txtify.app;

import android.Manifest;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FileBrowserDialogFragment extends DialogFragment {

    // MODIFIED: Updated callback interface
    public interface FileBrowserCallbacks {
        void onFilesAdded(ArrayList<File> files);
        void onBrowserClosed();
    }

    private static final int PERMISSIONS_REQUEST_READ_STORAGE = 201;

    private RecyclerView recyclerView;
    private TextView currentPathText;
    private TextView emptyFolderText;
    private ImageButton upButton;
    private ImageButton addSelectedButton; // NEW
    private Button selectAllButton;
    private Button clearSelectionButton;
    private Button closeButton; // NEW

    private File currentDirectory;
    private FileBrowserCallbacks callbacks;
    private FileBrowserAdapter adapter;

    // NEW: The fragment now owns the master set of selected files.
    private Set<File> selectedFiles = new HashSet<>();

    public static FileBrowserDialogFragment newInstance() {
        return new FileBrowserDialogFragment();
    }

    @Override
    public void onAttach(@NonNull android.content.Context context) {
        super.onAttach(context);
        if (context instanceof FileBrowserCallbacks) {
            callbacks = (FileBrowserCallbacks) context;
        } else {
            throw new RuntimeException(context.toString() + " must implement FileBrowserCallbacks");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.95);
            int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.85);
            dialog.getWindow().setLayout(width, height);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_file_browser, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        getDialog().setTitle("Select and Drag Files");

        recyclerView = view.findViewById(R.id.recycler_view_files);
        currentPathText = view.findViewById(R.id.text_current_path);
        emptyFolderText = view.findViewById(R.id.text_empty_folder);
        upButton = view.findViewById(R.id.button_up_directory);
        addSelectedButton = view.findViewById(R.id.button_add_selected); // NEW
        selectAllButton = view.findViewById(R.id.button_select_all);
        clearSelectionButton = view.findViewById(R.id.button_clear_selection);
        closeButton = view.findViewById(R.id.button_close_browser); // NEW

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        upButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (currentDirectory != null && currentDirectory.getParentFile() != null) {
						navigateTo(currentDirectory.getParentFile());
					}
				}
			});

        // NEW: Listener for the "Add Selected" tick button
        addSelectedButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (callbacks != null && !selectedFiles.isEmpty()) {
						callbacks.onFilesAdded(new ArrayList<>(selectedFiles));
						if (adapter != null) {
							adapter.clearSelection();
						}
					} else {
						Toast.makeText(getContext(), "No files selected.", Toast.LENGTH_SHORT).show();
					}
				}
			});

        // NEW: Listener for the "Close" button
        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dismiss();
				}
			});

        selectAllButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (adapter != null) {
						adapter.selectAllFiles();
					}
				}
			});

        clearSelectionButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (adapter != null) {
						adapter.clearSelection();
					}
				}
			});

        checkAndRequestPermissions();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        // MODIFIED: Use the new callback method
        if (callbacks != null) {
            callbacks.onBrowserClosed();
        }
    }

    private void navigateTo(File directory) {
        if (!directory.isDirectory() || !directory.canRead()) {
            Toast.makeText(getContext(), "Cannot access this folder.", Toast.LENGTH_SHORT).show();
            return;
        }

        currentDirectory = directory;
        currentPathText.setText(directory.getAbsolutePath());

        File[] filesArray = directory.listFiles();
        List<File> files;
        if (filesArray != null) {
            files = new ArrayList<>(Arrays.asList(filesArray));
        } else {
            files = new ArrayList<>();
        }

        if (files.isEmpty()) {
            emptyFolderText.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyFolderText.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }

        // MODIFIED: Pass the master selectedFiles set to the adapter's constructor.
        adapter = new FileBrowserAdapter(getContext(), files, selectedFiles,
            new FileBrowserAdapter.OnItemClickListener() {
                @Override
                public void onItemClick(File file) {
                    if (file.isDirectory()) {
                        navigateTo(file);
                    }
                    // A short click on a file now toggles selection (handled in adapter)
                }
            },
            new FileBrowserAdapter.OnDragStartListener() {
                @Override
                public void onDragStarted() {
                    dismiss(); // Dismiss the dialog if a drag is started.
                }
            }
        );

        recyclerView.setAdapter(adapter);
    }

    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
			!= PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
							   PERMISSIONS_REQUEST_READ_STORAGE);
        } else {
            initializeBrowser();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_READ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeBrowser();
            } else {
                Toast.makeText(getContext(), "Read permission is required to browse files.", Toast.LENGTH_LONG).show();
                dismiss();
            }
        }
    }

    private void initializeBrowser() {
        File root = Environment.getExternalStorageDirectory();
        navigateTo(root);
    }
}

