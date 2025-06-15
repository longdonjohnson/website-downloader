package com.example.websitedownloader;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox; // Added
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private static final int STORAGE_PERMISSION_CODE = 101;
    private static final String TAG = "MainActivity";

    EditText urlEditText;
    EditText depthEditText;
    CheckBox includeSubdomainsCheckBox; // Added
    Button downloadButton;
    Button cancelButton;
    Button clearDownloadsButton;
    ProgressBar downloadProgressBar;
    TextView logTextView;
    ScrollView logScrollView;

    private DownloadBroadcastReceiver downloadReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlEditText = findViewById(R.id.urlEditText);
        depthEditText = findViewById(R.id.depthEditText);
        includeSubdomainsCheckBox = findViewById(R.id.includeSubdomainsCheckBox); // Added
        downloadButton = findViewById(R.id.downloadButton);
        cancelButton = findViewById(R.id.cancelButton);
        clearDownloadsButton = findViewById(R.id.clearDownloadsButton);
        downloadProgressBar = findViewById(R.id.downloadProgressBar);
        logTextView = findViewById(R.id.logTextView);
        logScrollView = findViewById(R.id.logScrollView);

        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownloadService();
            }
        });

        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent cancelIntent = new Intent(MainActivity.this, DownloadService.class);
                cancelIntent.setAction(DownloadService.ACTION_CANCEL_DOWNLOAD);
                startService(cancelIntent);
                appendToLog("Cancellation signal sent to service...");
                cancelButton.setEnabled(false);
            }
        });

        clearDownloadsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showClearConfirmationDialog();
            }
        });

        downloadReceiver = new DownloadBroadcastReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(DownloadService.ACTION_DOWNLOAD_STATUS_UPDATE);
        ContextCompat.registerReceiver(this, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void showClearConfirmationDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Confirm Clear")
            .setMessage("Are you sure you want to delete all downloaded websites? This action cannot be undone.")
            .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    performClearDownloads();
                }
            })
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show();
    }

    private void performClearDownloads() {
        File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir != null && downloadsDir.exists()) {
            if (deleteRecursive(downloadsDir)) {
                Toast.makeText(this, "All downloads cleared.", Toast.LENGTH_SHORT).show();
                appendToLog("All downloaded files have been cleared.");
            } else {
                Toast.makeText(this, "Failed to clear all downloads.", Toast.LENGTH_SHORT).show();
                appendToLog("Error: Failed to clear all downloaded files.");
            }
        } else {
            Toast.makeText(this, "No downloads directory found or accessible.", Toast.LENGTH_SHORT).show();
            appendToLog("Info: No downloads directory found to clear.");
        }
    }

    private boolean deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) {
                        Log.e(TAG, "Failed to delete: " + child.getAbsolutePath());
                    }
                }
            }
        }
        return fileOrDirectory.delete();
    }


    private void startDownloadService() {
        logTextView.setText("");

        final boolean includeSubdomains = includeSubdomainsCheckBox.isChecked(); // Read CheckBox state

        runOnUiThread(() -> {
            downloadProgressBar.setVisibility(View.VISIBLE);
            downloadProgressBar.setIndeterminate(true);
            downloadButton.setEnabled(false);
            cancelButton.setVisibility(View.VISIBLE);
            cancelButton.setEnabled(true);
            urlEditText.setEnabled(false);
            depthEditText.setEnabled(false);
            includeSubdomainsCheckBox.setEnabled(false); // Disable CheckBox
            clearDownloadsButton.setEnabled(false);
        });

        String initialUrlString = urlEditText.getText().toString().trim();
        String depthString = depthEditText.getText().toString().trim();
        int userMaxDepth;

        if (initialUrlString.isEmpty()) {
            appendToLog("Error: URL cannot be empty.");
            resetUiAfterDownload("URL_EMPTY");
            return;
        }

        try {
            userMaxDepth = Integer.parseInt(depthString);
            if (userMaxDepth < 0) {
                appendToLog("Info: Recursion depth cannot be negative. Using default depth of 0.");
                userMaxDepth = 0;
            } else if (userMaxDepth > 5) {
                appendToLog("Info: Recursion depth capped at 5 for safety. Using depth 5.");
                userMaxDepth = 5;
            }
        } catch (NumberFormatException e) {
            appendToLog("Info: Invalid depth input. Using default depth of 0.");
            userMaxDepth = 0;
        }

        if (checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, STORAGE_PERMISSION_CODE)) {
            Intent serviceIntent = new Intent(this, DownloadService.class);
            serviceIntent.putExtra("URL", initialUrlString);
            serviceIntent.putExtra("DEPTH", userMaxDepth);
            serviceIntent.putExtra("INCLUDE_SUBDOMAINS", includeSubdomains); // Add boolean extra
            startService(serviceIntent);
            appendToLog("Download service initiated (Include Subdomains: " + includeSubdomains + ")");
        } else {
            appendToLog("Storage permission pending...");
            runOnUiThread(() -> {
                downloadProgressBar.setVisibility(View.GONE);
                downloadButton.setEnabled(true);
                cancelButton.setVisibility(View.GONE);
                cancelButton.setEnabled(false);
                urlEditText.setEnabled(true);
                depthEditText.setEnabled(true);
                includeSubdomainsCheckBox.setEnabled(true); // Re-enable CheckBox
                clearDownloadsButton.setEnabled(true);
            });
        }
    }

    private void resetUiAfterDownload(String status) {
        runOnUiThread(() -> {
            downloadProgressBar.setVisibility(View.GONE);
            downloadButton.setEnabled(true);
            cancelButton.setVisibility(View.GONE);
            cancelButton.setEnabled(false);
            urlEditText.setEnabled(true);
            depthEditText.setEnabled(true);
            includeSubdomainsCheckBox.setEnabled(true); // Re-enable CheckBox
            clearDownloadsButton.setEnabled(true);
        });
    }

    private boolean checkPermission(String permission, int requestCode) {
        if (ContextCompat.checkSelfPermission(MainActivity.this, permission) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{permission}, requestCode);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendToLog("Storage Permission Granted. You can now start the download.");
            } else {
                appendToLog("Storage Permission Denied. Cannot start download or clear files effectively.");
                resetUiAfterDownload(DownloadService.STATUS_ERROR);
            }
        }
    }

    private void appendToLog(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logTextView.append(message + "\n");
                Log.d(TAG, message);
                if (logScrollView != null) {
                    logScrollView.post(new Runnable() {
                        @Override
                        public void run() {
                            logScrollView.fullScroll(View.FOCUS_DOWN);
                        }
                    });
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (downloadReceiver != null) {
            unregisterReceiver(downloadReceiver);
        }
    }

    private class DownloadBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String logMessage = intent.getStringExtra(DownloadService.EXTRA_LOG_MESSAGE);
            if (logMessage != null) {
                appendToLog(logMessage);
            }

            String status = intent.getStringExtra(DownloadService.EXTRA_DOWNLOAD_STATUS);
            if (status != null) {
                switch (status) {
                    case DownloadService.STATUS_COMPLETE:
                    case DownloadService.STATUS_ERROR:
                    case DownloadService.STATUS_CANCELLED:
                        resetUiAfterDownload(status);
                        break;
                    case DownloadService.STATUS_PROGRESS:
                    case DownloadService.STATUS_STARTING:
                        break;
                }
            }
        }
    }
}
