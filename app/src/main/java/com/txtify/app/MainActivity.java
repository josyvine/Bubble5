package com.txtify.app;

import android.Manifest;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.util.Log;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.documentfile.provider.DocumentFile;
import com.google.android.material.navigation.NavigationView;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;
// FIX: Changed from play.core.tasks to gms.tasks
import com.google.android.gms.tasks.Task;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener, FileBrowserDialogFragment.FileBrowserCallbacks {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int FILE_PICKER_REQUEST_CODE = 101;
    private static final int FOLDER_PICKER_REQUEST_CODE = 102;
    private static final int MULTI_FILE_PICKER_REQUEST_CODE = 103;
    private static final int ZIP_PICKER_REQUEST_CODE = 104;
    private static final int ZIP_CONTENTS_REQUEST_CODE = 105;

    // NEW: Request code for the In-App Update flow
    private static final int APP_UPDATE_REQUEST_CODE = 200;

    private static final String PREFS_NAME = "TxtifyPrefs";
    private static final String KEY_SAVE_FOLDER_URI = "saveFolderUri";

    private DrawerLayout drawerLayout;
    private TextView toolbarSubtitle;
    private boolean shouldCreateCopies = true;
    private Uri customSaveFolderUri = null;

    private ListView projectListView;
    private TextView emptyListText;
    private Map<String, List<Uri>> projectsMap = new HashMap<>();
    private ArrayAdapter<String> projectListAdapter;
    private List<String> projectNames = new ArrayList<>();

    private String nextAvailableFolderName = null;

    private FrameLayout mainContentFrame;

    private LinearLayout junctionBox;
    private ArrayList<Uri> collectedUris = new ArrayList<>();

    // NEW: AdMob Banner View
    private AdView mAdView;
    // NEW: App Update Manager
    private AppUpdateManager appUpdateManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        toolbarSubtitle = findViewById(R.id.toolbar_subtitle);
        drawerLayout = findViewById(R.id.drawer_layout);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUriString = prefs.getString(KEY_SAVE_FOLDER_URI, null);

        if (savedUriString != null) {
            customSaveFolderUri = Uri.parse(savedUriString);
            DocumentFile dir = DocumentFile.fromTreeUri(this, customSaveFolderUri);
            if (dir != null && dir.canWrite()) {
                prepareNextUnknownFolder();
            } else {
                customSaveFolderUri = null;
                prefs.edit().remove(KEY_SAVE_FOLDER_URI).apply();
                Toast.makeText(this, "Permission to save folder lost. Please select it again.", Toast.LENGTH_LONG).show();
            }
        }

        setupUI();

        // NEW: Load Banner Ad
        mAdView = findViewById(R.id.adView);
        AdRequest adRequest = new AdRequest.Builder().build();
        mAdView.loadAd(adRequest);

        // NEW: Check for immediate updates
        appUpdateManager = AppUpdateManagerFactory.create(this);
        checkForAppUpdate();
    }

    // NEW: Method to check for Google Play updates
    private void checkForAppUpdate() {
        Task<AppUpdateInfo> appUpdateInfoTask = appUpdateManager.getAppUpdateInfo();

        appUpdateInfoTask.addOnSuccessListener(appUpdateInfo -> {
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    // This example applies an immediate update. To apply a flexible update
                    // instead, pass in AppUpdateType.FLEXIBLE
                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {

                try {
                    appUpdateManager.startUpdateFlowForResult(
                            appUpdateInfo,
                            AppUpdateType.IMMEDIATE,
                            this,
                            APP_UPDATE_REQUEST_CODE);
                } catch (IntentSender.SendIntentException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // NEW: Check on resume if the update is in progress (for Immediate updates)
    @Override
    protected void onResume() {
        super.onResume();
        if (mAdView != null) {
            mAdView.resume();
        }

        appUpdateManager
            .getAppUpdateInfo()
            .addOnSuccessListener(appUpdateInfo -> {
                if (appUpdateInfo.updateAvailability()
                        == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    // If an in-app update is already in progress, resume the update.
                    try {
                        appUpdateManager.startUpdateFlowForResult(
                                appUpdateInfo,
                                AppUpdateType.IMMEDIATE,
                                this,
                                APP_UPDATE_REQUEST_CODE);
                    } catch (IntentSender.SendIntentException e) {
                        e.printStackTrace();
                    }
                }
            });
    }

    @Override
    protected void onPause() {
        if (mAdView != null) {
            mAdView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mAdView != null) {
            mAdView.destroy();
        }
        super.onDestroy();
    }

    private void setupUI() {
        NavigationView navigationView = findViewById(R.id.navigation_view);
        navigationView.setNavigationItemSelectedListener(this);

        projectListView = findViewById(R.id.project_list_view);
        emptyListText = findViewById(R.id.empty_list_text);

        projectListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, projectNames);
        projectListView.setAdapter(projectListAdapter);

        projectListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                                @Override
                                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                                        showConversionDialog(projectNames.get(position));
                                }
                        });

        MenuItem switchItem = navigationView.getMenu().findItem(R.id.nav_create_copies_toggle);
        SwitchCompat createCopySwitch = (SwitchCompat) switchItem.getActionView();
        createCopySwitch.setChecked(shouldCreateCopies);

        createCopySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                                @Override
                                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                                        shouldCreateCopies = isChecked;
                                }
                        });

        mainContentFrame = findViewById(R.id.main_content_frame);
        junctionBox = findViewById(R.id.junction_box_drop_zone);
        junctionBox.setOnDragListener(new JunctionBoxDragListener());

        checkPermissions();
        updateProjectListUI();
    }

    // NEW: Implemented from FileBrowserCallbacks
    @Override
    public void onFilesAdded(ArrayList<File> files) {
        int count = 0;
        for (File file : files) {
            collectedUris.add(Uri.fromFile(file));
            count++;
        }
        Toast.makeText(this, count + " file(s) collected.", Toast.LENGTH_SHORT).show();
    }

    // MODIFIED: Replaces onBrowserDismissed()
    @Override
    public void onBrowserClosed() {
        junctionBox.setVisibility(View.GONE);

        if (!collectedUris.isEmpty()) {
            addUrisToProjectMap(new ArrayList<>(collectedUris));
        }

        collectedUris.clear();
    }

    class JunctionBoxDragListener implements View.OnDragListener {
        private final int highlightColor = 0xFF00ACC1;
        private final int originalColor = 0xDD263238;

        @Override
        public boolean onDrag(View v, DragEvent event) {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    if (event.getClipDescription().hasMimeType("text/plain")) {
                        return true;
                    }
                    return false;

                case DragEvent.ACTION_DROP:
                    ClipData.Item item = event.getClipData().getItemAt(0);
                    String allPaths = item.getText().toString();

                    String[] pathsArray = allPaths.split("\n");
                    int count = 0;

                    for(String path : pathsArray){
                        if(path != null && !path.trim().isEmpty()){
                            File droppedFile = new File(path);
                            Uri fileUri = Uri.fromFile(droppedFile);
                            collectedUris.add(fileUri);
                            count++;
                        }
                    }

                    Toast.makeText(MainActivity.this, count + " file(s) collected", Toast.LENGTH_SHORT).show();

                    return true;

                case DragEvent.ACTION_DRAG_ENDED:
                    v.setBackgroundColor(originalColor);
                    return true;

                case DragEvent.ACTION_DRAG_ENTERED:
                                        v.setBackgroundColor(highlightColor);
                                        return true;

                case DragEvent.ACTION_DRAG_EXITED:
                                        v.setBackgroundColor(originalColor);
                                        return true;

                case DragEvent.ACTION_DRAG_LOCATION:
                    return true;

                default:
                    break;
            }
            return false;
        }
    }

    private void showConversionDialog(final String projectName) {
        if (customSaveFolderUri == null) {
            Toast.makeText(this, "Please choose a save folder first!", Toast.LENGTH_LONG).show();
            return;
        }
        if (nextAvailableFolderName == null) {
            Toast.makeText(this, "Folder is being prepared, please wait...", Toast.LENGTH_SHORT).show();
            prepareNextUnknownFolder();
            return;
        }

        final List<Uri> projectFiles = projectsMap.get(projectName);
        if (projectFiles == null || projectFiles.isEmpty()) {
            Toast.makeText(this, "No files in this project.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> fileNames = new ArrayList<>();
        for (Uri uri : projectFiles) {
            fileNames.add(getFileNameFromUri(uri));
        }
        Collections.sort(fileNames);

        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_file_list, null);
        ListView dialogListView = dialogView.findViewById(R.id.file_list_view_dialog);
        ArrayAdapter<String> dialogAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, fileNames);
        dialogListView.setAdapter(dialogAdapter);

        new AlertDialog.Builder(this)
            .setTitle("Confirm Conversion")
            .setView(dialogView)
            .setMessage("Ready to save " + fileNames.size() + " files.")
            .setPositiveButton("Save to '" + nextAvailableFolderName + "'", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    new ProjectConversionTask().execute(projectName, projectFiles, nextAvailableFolderName);
                }
            })
            .setNeutralButton("Rename Folder...", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    showRenameDialogForProject(projectName, projectFiles);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showRenameDialogForProject(final String projectName, final List<Uri> projectFiles) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Folder");

        View view = getLayoutInflater().inflate(R.layout.dialog_rename_folder, null);
        final EditText input = view.findViewById(R.id.edit_text_folder_name);
        input.setHint(nextAvailableFolderName);
        builder.setView(view);

        builder.setPositiveButton("Save", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                        String newFolderName = input.getText().toString().trim();
                                        if (newFolderName.isEmpty()) {
                                                new ProjectConversionTask().execute(projectName, projectFiles, nextAvailableFolderName);
                                                return;
                                        }

                                        DocumentFile baseDir = DocumentFile.fromTreeUri(getApplicationContext(), customSaveFolderUri);
                                        DocumentFile folderToRename = baseDir.findFile(nextAvailableFolderName);

                                        if (folderToRename != null && folderToRename.renameTo(newFolderName)) {
                                                Toast.makeText(MainActivity.this, "Folder renamed to " + newFolderName, Toast.LENGTH_SHORT).show();
                                                new ProjectConversionTask().execute(projectName, projectFiles, newFolderName);
                                        } else {
                                                Toast.makeText(MainActivity.this, "Could not rename. Saving to default folder.", Toast.LENGTH_LONG).show();
                                                new ProjectConversionTask().execute(projectName, projectFiles, nextAvailableFolderName);
                                        }
                                }
                        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showZipConversionDialog(final Uri zipUri, final ArrayList<ZipEntryItem> selectedItems) {
        if (nextAvailableFolderName == null) {
            Toast.makeText(this, "Folder is being prepared, please wait.", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Confirm ZIP Conversion")
            .setMessage("Ready to save " + selectedItems.size() + " files from ZIP.")
            .setPositiveButton("Save to '" + nextAvailableFolderName + "'", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    new ZipConversionTask().execute(nextAvailableFolderName, zipUri, selectedItems);
                }
            })
            .setNeutralButton("Rename Folder...", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    showRenameDialogForZip(zipUri, selectedItems);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showRenameDialogForZip(final Uri zipUri, final ArrayList<ZipEntryItem> selectedItems) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Folder");

        View view = getLayoutInflater().inflate(R.layout.dialog_rename_folder, null);
        final EditText input = view.findViewById(R.id.edit_text_folder_name);
        input.setHint(nextAvailableFolderName);
        builder.setView(view);

        builder.setPositiveButton("Save", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                        String newFolderName = input.getText().toString().trim();
                                        if (newFolderName.isEmpty()) {
                                                new ZipConversionTask().execute(nextAvailableFolderName, zipUri, selectedItems);
                                                return;
                                        }

                                        DocumentFile baseDir = DocumentFile.fromTreeUri(getApplicationContext(), customSaveFolderUri);
                                        DocumentFile folderToRename = baseDir.findFile(nextAvailableFolderName);

                                        if (folderToRename != null && folderToRename.renameTo(newFolderName)) {
                                                Toast.makeText(MainActivity.this, "Folder renamed to " + newFolderName, Toast.LENGTH_SHORT).show();
                                                new ZipConversionTask().execute(newFolderName, zipUri, selectedItems);
                                        } else {
                                                Toast.makeText(MainActivity.this, "Could not rename. Saving to default folder.", Toast.LENGTH_LONG).show();
                                                new ZipConversionTask().execute(nextAvailableFolderName, zipUri, selectedItems);
                                        }
                                }
                        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void prepareNextUnknownFolder() {
        if (customSaveFolderUri != null) {
            new PrepareFolderTask().execute(customSaveFolderUri);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // NEW: Handle the result of the In-App Update
        if (requestCode == APP_UPDATE_REQUEST_CODE) {
            if (resultCode != RESULT_OK) {
                // If the update is cancelled or fails, you can request to start the update again.
                // Since this is a forced update, we might want to close the app if they refuse.
                Toast.makeText(this, "Update is required to continue.", Toast.LENGTH_SHORT).show();
                finish(); 
                return;
            }
        }

        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == FOLDER_PICKER_REQUEST_CODE && data.getData() != null) {
            Uri folderUri = data.getData();
            getContentResolver().takePersistableUriPermission(folderUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            customSaveFolderUri = folderUri;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_SAVE_FOLDER_URI, folderUri.toString()).apply();
            Toast.makeText(this, "Save folder set!", Toast.LENGTH_SHORT).show();
            prepareNextUnknownFolder();
            return;
        }

        if (requestCode == ZIP_PICKER_REQUEST_CODE && data.getData() != null) {
            Uri zipUri = data.getData();
            Intent intent = new Intent(this, ZipContentsActivity.class);
            intent.putExtra(ZipContentsActivity.EXTRA_ZIP_URI, zipUri);
            startActivityForResult(intent, ZIP_CONTENTS_REQUEST_CODE);
            return;
        }

        if (requestCode == ZIP_CONTENTS_REQUEST_CODE) {
            Serializable serializableExtra = data.getSerializableExtra(ZipContentsActivity.RESULT_EXTRA_SELECTED_ITEMS);
            if (serializableExtra instanceof ArrayList) {
                ArrayList<ZipEntryItem> selectedItems = (ArrayList<ZipEntryItem>) serializableExtra;
                Uri zipUri = data.getData();
                if (zipUri != null && !selectedItems.isEmpty()) {
                    showZipConversionDialog(zipUri, selectedItems);
                }
            }
            return;
        }

        List<Uri> selectedUris = new ArrayList<>();
        if (requestCode == FILE_PICKER_REQUEST_CODE && data.getData() != null) {
            selectedUris.add(data.getData());
        } else if (requestCode == MULTI_FILE_PICKER_REQUEST_CODE) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    selectedUris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                selectedUris.add(data.getData());
            }
        }

        if (!selectedUris.isEmpty()) {
            addUrisToProjectMap(selectedUris);
        }
    }

    private class PrepareFolderTask extends AsyncTask<Uri, String, String> {
        @Override
        protected String doInBackground(Uri... uris) {
            Uri saveFolderUri = uris[0];
            DocumentFile baseDir = DocumentFile.fromTreeUri(getApplicationContext(), saveFolderUri);
            if (baseDir == null) return null;

            String baseName = "unknown_project";
            String nameToCheck = baseName;
            int counter = 1;

            while (true) {
                publishProgress("Checking for '" + nameToCheck + "'...");
                DocumentFile existingFolder = baseDir.findFile(nameToCheck);

                if (existingFolder != null && existingFolder.isDirectory()) {
                    publishProgress("'" + nameToCheck + "' exists.");
                    nameToCheck = baseName + counter;
                    counter++;
                } else {
                    publishProgress("Creating folder: '" + nameToCheck + "'");
                    DocumentFile newDir = baseDir.createDirectory(nameToCheck);
                    return (newDir != null) ? newDir.getName() : null;
                }
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
        }

        @Override
        protected void onPostExecute(String result) {
            if (result != null) {
                nextAvailableFolderName = result;
                Toast.makeText(MainActivity.this, "Ready. Default save folder is: " + result, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(MainActivity.this, "Error: Could not create new folder.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private class ProjectConversionTask extends AsyncTask<Object, Void, Integer> {
        private String projectMapKey;
        private String folderToSaveIn;

        private class FileToSort {
            Uri uri;
            String name;
            FileToSort(Uri u, String n) { this.uri = u; this.name = n; }
        }

        @Override
        protected Integer doInBackground(Object... params) {
            this.projectMapKey = (String) params[0];
            List<Uri> urisToConvert = (List<Uri>) params[1];
            this.folderToSaveIn = (String) params[2];
            int successCount = 0;

            if (folderToSaveIn == null) return -1;

            DocumentFile baseDir = DocumentFile.fromTreeUri(getApplicationContext(), customSaveFolderUri);
            if (baseDir == null) return -1;

            DocumentFile projectDir = baseDir.findFile(folderToSaveIn);
            if (projectDir == null || !projectDir.isDirectory()) {
				return -1;
            }

            List<FileToSort> sortedFiles = new ArrayList<>();
            for (Uri uri : urisToConvert) {
                sortedFiles.add(new FileToSort(uri, getFileNameFromUri(uri)));
            }
            Collections.sort(sortedFiles, new Comparator<FileToSort>() {
					@Override
					public int compare(FileToSort f1, FileToSort f2) {
						return f1.name.compareTo(f2.name);
					}
				});

            for (FileToSort file : sortedFiles) {
                String content = readFileContent(file.uri);
                if (content != null) {
                    if (saveFileInDirectory(projectDir, file.name + ".txt", content)) {
                        successCount++;
                    }
                }
            }
            return successCount;
        }

        @Override
        protected void onPostExecute(Integer successCount) {
            String message;
            if (successCount >= 0) {
                message = String.format("Success. %d files saved to folder '%s'.", successCount, this.folderToSaveIn);
            } else {
                message = "Error: Could not access the destination folder '" + this.folderToSaveIn + "'.";
            }
            new AlertDialog.Builder(MainActivity.this)
                .setTitle("Conversion Complete")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();

            if (this.projectMapKey != null) {
                projectsMap.remove(this.projectMapKey);
            }
            updateProjectListUI();
            prepareNextUnknownFolder();
        }
    }

    private class ZipConversionTask extends AsyncTask<Object, Void, Integer> {
        private String folderToSaveIn;
        private Uri zipFileUri;
        private List<ZipEntryItem> itemsToConvert;

        @Override
        protected Integer doInBackground(Object... params) {
            this.folderToSaveIn = (String) params[0];
            this.zipFileUri = (Uri) params[1];
            this.itemsToConvert = (List<ZipEntryItem>) params[2];
            int successCount = 0;

            if (folderToSaveIn == null) return -1;

            DocumentFile baseDir = DocumentFile.fromTreeUri(getApplicationContext(), customSaveFolderUri);
            if (baseDir == null) return -1;

            DocumentFile projectDir = baseDir.findFile(folderToSaveIn);
            if (projectDir == null || !projectDir.isDirectory()) {
                return -1;
            }

            ZipInputStream zis = null;
            try {
                InputStream is = getContentResolver().openInputStream(zipFileUri);
                zis = new ZipInputStream(is);
                ZipEntry zipEntry;
                while ((zipEntry = zis.getNextEntry()) != null) {
                    if (!zipEntry.isDirectory() && isEntryInList(zipEntry.getName(), itemsToConvert)) {
                        String content = readContentFromZipEntry(zis);
                        String fileName = getFileNameFromUri(Uri.parse(zipEntry.getName()));
                        if (saveFileInDirectory(projectDir, fileName + ".txt", content)) {
                            successCount++;
                        }
                    }
                    zis.closeEntry();
                }
            } catch (IOException e) {
                return successCount;
            } finally {
                if (zis != null) {
                    try {
                        zis.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
            return successCount;
        }

        @Override
        protected void onPostExecute(Integer successCount) {
            String message;
            if (successCount >= 0) {
                message = String.format("Success. %d files from ZIP saved to folder '%s'.", successCount, this.folderToSaveIn);
            } else {
                message = "Error converting files from ZIP. Could not access destination folder.";
            }
            new AlertDialog.Builder(MainActivity.this)
                .setTitle("ZIP Conversion Complete")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();

            prepareNextUnknownFolder();
        }

        private boolean isEntryInList(String entryPath, List<ZipEntryItem> list) {
            for (ZipEntryItem item : list) {
                if (item.getFullPath().equals(entryPath)) {
                    return true;
                }
            }
            return false;
        }

        private String readContentFromZipEntry(ZipInputStream zis) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = zis.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            return baos.toString("UTF-8");
        }
    }


    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
    }

    private void openMultiFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, MULTI_FILE_PICKER_REQUEST_CODE);
    }

    private void openZipPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        startActivityForResult(intent, ZIP_PICKER_REQUEST_CODE);
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, FOLDER_PICKER_REQUEST_CODE);
    }

    private String getFileNameFromUri(Uri uri) {
        String result = "unknown_file";
        if ("content".equals(uri.getScheme())) {
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(0);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        } else if ("file".equals(uri.getScheme())) {
			result = new File(uri.getPath()).getName();
        }

        if (result.equals("unknown_file")) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }

    private void addUrisToProjectMap(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        String projectName = getProjectFolderNameForBatch(uris);
        if (!projectsMap.containsKey(projectName)) {
            projectsMap.put(projectName, new ArrayList<Uri>());
        }
        projectsMap.get(projectName).addAll(uris);
        Toast.makeText(this, uris.size() + " file(s) added to project '" + projectName + "'.", Toast.LENGTH_SHORT).show();
        updateProjectListUI();
    }

    private void updateProjectListUI() {
        projectNames.clear();
        projectNames.addAll(projectsMap.keySet());
        Collections.sort(projectNames);
        projectListAdapter.notifyDataSetChanged();
        projectListView.setVisibility(projectNames.isEmpty() ? View.GONE : View.VISIBLE);
        emptyListText.setVisibility(projectNames.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String getProjectFolderNameForBatch(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return "unknown_project";
        }

        Uri firstUri = uris.get(0);
        if ("file".equals(firstUri.getScheme())) {
			File parent = new File(firstUri.getPath()).getParentFile();
			return parent != null ? parent.getName() : "unknown_project";
        }

        DocumentFile firstParent = DocumentFile.fromSingleUri(getApplicationContext(), firstUri).getParentFile();
        if (uris.size() == 1 || firstParent == null) {
            return firstParent != null ? firstParent.getName() : "unknown_project";
        }
        DocumentFile commonAncestor = firstParent;
        for (int i = 1; i < uris.size(); i++) {
            DocumentFile currentParent = DocumentFile.fromSingleUri(getApplicationContext(), uris.get(i)).getParentFile();
            commonAncestor = findCommonAncestor(commonAncestor, currentParent);
            if (commonAncestor == null) {
                return "unknown_project";
            }
        }
        return commonAncestor.getName();
    }

    private DocumentFile findCommonAncestor(DocumentFile df1, DocumentFile df2) {
        if (df1 == null || df2 == null) return null;
        List<DocumentFile> parentsOfDf1 = new ArrayList<>();
        DocumentFile current = df1;
        while (current != null) {
            parentsOfDf1.add(current);
            current = current.getParentFile();
        }
        current = df2;
        while (current != null) {
            if (parentsOfDf1.contains(current)) {
                return current;
            }
            current = current.getParentFile();
        }
        return null;
    }

    private String readFileContent(Uri uri) {
        StringBuilder sb = new StringBuilder();
        InputStream is = null;
        BufferedReader reader = null;
        try {
            is = getContentResolver().openInputStream(uri);
            reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            return null;
        } finally {
            try {
                if (reader != null) reader.close();
                if (is != null) is.close();
            } catch (IOException e) {
                // ignore
            }
        }
        return sb.toString();
    }

    private boolean saveFileInDirectory(DocumentFile dir, String fileName, String content) {
        String finalFileName = fileName;
        if (shouldCreateCopies) {
            int copy = 1;
            while (dir.findFile(finalFileName) != null) {
                String base = fileName.substring(0, fileName.lastIndexOf('.'));
                String extension = fileName.substring(fileName.lastIndexOf('.'));
                finalFileName = base + "-" + (copy++) + extension;
            }
        } else {
            DocumentFile existingFile = dir.findFile(finalFileName);
            if (existingFile != null) existingFile.delete();
        }
        OutputStream out = null;
        try {
            DocumentFile newFile = dir.createFile("text/plain", finalFileName);
            if (newFile != null) {
                out = getContentResolver().openOutputStream(newFile.getUri());
                if (out != null) {
                    out.write(content.getBytes("UTF-8"));
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        } finally {
            try {
                if (out != null) out.close();
            } catch (IOException e) {
                // ignore
            }
        }
        return false;
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.nav_browse_device) {
            openFilePicker();
        } else if (itemId == R.id.nav_batch_convert) {
            openMultiFilePicker();
        } else if (itemId == R.id.nav_process_zip) {
            openZipPicker();
        } else if (itemId == R.id.nav_in_app_upload) {
            collectedUris.clear();
            junctionBox.setVisibility(View.VISIBLE);
            FileBrowserDialogFragment dialogFragment = FileBrowserDialogFragment.newInstance();
            dialogFragment.show(getSupportFragmentManager(), "file_browser_dialog");
        } else if (itemId == R.id.nav_set_save_folder) {
            openFolderPicker();
        } else if (itemId == R.id.nav_help) {
            startActivity(new Intent(this, HelpActivity.class));
        }
        drawerLayout.closeDrawer(GravityCompat.END);
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_open_drawer) {
            drawerLayout.openDrawer(GravityCompat.END);
            return true;
        }
        else if (id == R.id.action_clear_all) {
            projectsMap.clear();
            updateProjectListUI();
            Toast.makeText(this, "Project list cleared.", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
        }
    }
}