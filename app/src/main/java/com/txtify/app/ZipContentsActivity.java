package com.txtify.app;

import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipContentsActivity extends AppCompatActivity {

    public static final String EXTRA_ZIP_URI = "extra_zip_uri";
    public static final String RESULT_EXTRA_SELECTED_ITEMS = "result_extra_selected_items";

    private RecyclerView recyclerView;
    private ZipContentsAdapter adapter;
    private List<ZipEntryItem> zipEntryItems = new ArrayList<>();
    private Uri zipUri;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_zip_contents);

        Toolbar toolbar = findViewById(R.id.toolbar_zip);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("ZIP File Contents");
        }

        recyclerView = findViewById(R.id.zip_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        Button convertButton = findViewById(R.id.button_convert_from_zip);
        convertButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					ArrayList<ZipEntryItem> selectedItems = adapter.getIncludedItems();
					if (selectedItems.isEmpty()) {
						Toast.makeText(ZipContentsActivity.this, "No files are selected for conversion.", Toast.LENGTH_SHORT).show();
						return;
					}

					Intent resultIntent = new Intent();
					resultIntent.putExtra(RESULT_EXTRA_SELECTED_ITEMS, selectedItems);
					resultIntent.setData(zipUri); 
					setResult(RESULT_OK, resultIntent);
					finish();
				}
			});

        zipUri = getIntent().getParcelableExtra(EXTRA_ZIP_URI);
        if (zipUri != null) {
            new ReadZipTask().execute(zipUri);
        } else {
            Toast.makeText(this, "Error: No ZIP file URI provided.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class ReadZipTask extends AsyncTask<Uri, Void, List<ZipEntryItem>> {
        @Override
        protected List<ZipEntryItem> doInBackground(Uri... uris) {
            List<ZipEntryItem> items = new ArrayList<>();
            Uri uri = uris[0];
            ZipInputStream zis = null;
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                zis = new ZipInputStream(is);
                ZipEntry zipEntry;
                while ((zipEntry = zis.getNextEntry()) != null) {
                    if (!zipEntry.isDirectory()) {
                        String fullPath = zipEntry.getName();
                        String fileName = getFileNameFromPath(fullPath);
                        String subfolder = getSubfolderFromPath(fullPath);
                        items.add(new ZipEntryItem(fullPath, fileName, subfolder));
                    }
                    zis.closeEntry();
                }
            } catch (Exception e) {
                return items;
            } finally {
                if (zis != null) {
                    try { zis.close(); } catch (Exception e) {}
                }
            }

            Collections.sort(items, new Comparator<ZipEntryItem>() {
					@Override
					public int compare(ZipEntryItem o1, ZipEntryItem o2) {
						return o1.getFullPath().compareTo(o2.getFullPath());
					}
				});

            return items;
        }

        @Override
        protected void onPostExecute(List<ZipEntryItem> result) {
            if (result.isEmpty()) {
                Toast.makeText(ZipContentsActivity.this, "Could not read ZIP file or it is empty.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            zipEntryItems.clear();
            zipEntryItems.addAll(result);

            adapter = new ZipContentsAdapter(ZipContentsActivity.this, zipEntryItems);
            recyclerView.setAdapter(adapter);
        }

        private String getFileNameFromPath(String path) {
            if (path == null || path.isEmpty()) return "";
            path = path.replace('\\', '/');
            int lastSlash = path.lastIndexOf('/');
            return (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;
        }

        private String getSubfolderFromPath(String path) {
            if (path == null || path.isEmpty()) return "";
            path = path.replace('\\', '/');
            int lastSlash = path.lastIndexOf('/');
            return (lastSlash >= 0) ? path.substring(0, lastSlash + 1) : "";
        }
    }
}

