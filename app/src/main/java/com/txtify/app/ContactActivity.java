package com.txtify.app;

import android.app.ProgressDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Patterns;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ContactActivity extends AppCompatActivity {

    private Toolbar toolbar;
    private EditText nameInput, emailInput, messageInput;
    private Button sendButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contact);

        toolbar = findViewById(R.id.toolbar_contact);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.contact_title);
        }

        nameInput = findViewById(R.id.edit_text_name);
        emailInput = findViewById(R.id.edit_text_email);
        messageInput = findViewById(R.id.edit_text_message);
        sendButton = findViewById(R.id.button_send);

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                validateAndSubmitForm();
            }
        });
    }

    private void validateAndSubmitForm() {
        String name = nameInput.getText().toString().trim();
        String email = emailInput.getText().toString().trim();
        String message = messageInput.getText().toString().trim();

        // Reset previous errors
        nameInput.setError(null);
        emailInput.setError(null);
        messageInput.setError(null);

        boolean isValid = true;

        if (name.isEmpty()) {
            nameInput.setError(getString(R.string.contact_validation_empty));
            isValid = false;
        }

        if (email.isEmpty()) {
            emailInput.setError(getString(R.string.contact_validation_empty));
            isValid = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.setError(getString(R.string.contact_validation_email));
            isValid = false;
        }

        if (message.isEmpty()) {
            messageInput.setError(getString(R.string.contact_validation_empty));
            isValid = false;
        }

        if (isValid) {
            new SendFormTask().execute(name, email, message);
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

    private class SendFormTask extends AsyncTask<String, Void, Boolean> {
        private ProgressDialog progressDialog;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            progressDialog = new ProgressDialog(ContactActivity.this);
            progressDialog.setMessage(getString(R.string.contact_sending_message));
            progressDialog.setCancelable(false);
            progressDialog.show();
        }

        @Override
        protected Boolean doInBackground(String... params) {
            String contactName = params[0];
            String contactEmail = params[1];
            String contactMessage = params[2];
            String formspreeUrl = "https://formspree.io/f/xyzenlao";

            try {
                URL url = new URL(formspreeUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000); // 10 seconds
                conn.setReadTimeout(10000);    // 10 seconds

                JSONObject jsonPayload = new JSONObject();
                jsonPayload.put("contactName", contactName);
                jsonPayload.put("contactEmail", contactEmail);
                jsonPayload.put("contactMessage", contactMessage);

                String jsonInputString = jsonPayload.toString();

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                conn.disconnect();

                return responseCode == HttpURLConnection.HTTP_OK;

            } catch (Exception e) {
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            if (progressDialog.isShowing()) {
                progressDialog.dismiss();
            }

            if (success) {
                Toast.makeText(ContactActivity.this, R.string.contact_success, Toast.LENGTH_LONG).show();
                finish();
            } else {
                Toast.makeText(ContactActivity.this, R.string.contact_error, Toast.LENGTH_LONG).show();
            }
        }
    }
}
