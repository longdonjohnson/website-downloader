package com.example.websitedownloader;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private static final int STORAGE_PERMISSION_CODE = 101;
    private static final String TAG = "MainActivity";

    EditText urlEditText;
    Button downloadButton;
    TextView logTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlEditText = findViewById(R.id.urlEditText);
        downloadButton = findViewById(R.id.downloadButton);
        logTextView = findViewById(R.id.logTextView);

        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownloadProcess();
            }
        });
    }

    private void startDownloadProcess() {
        String urlString = urlEditText.getText().toString().trim();

        if (urlString.isEmpty()) {
            appendToLog("Error: URL cannot be empty.");
            return;
        }

        try {
            new URL(urlString); // Validate URL format
        } catch (MalformedURLException e) {
            appendToLog("Error: Invalid URL format. Please include http:// or https://");
            Log.e(TAG, "Invalid URL format", e);
            return;
        }

        if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
            appendToLog("Error: URL must start with http:// or https://");
            return;
        }

        if (checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, STORAGE_PERMISSION_CODE)) {
            executeDownload(urlString);
        }
        // If permission is not granted, checkPermission would have requested it.
        // The actual download will be triggered from onRequestPermissionsResult if granted.
    }

    private boolean checkPermission(String permission, int requestCode) {
        if (ContextCompat.checkSelfPermission(MainActivity.this, permission) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{permission}, requestCode);
            appendToLog("Storage permission requested...");
            return false;
        } else {
            // appendToLog("Storage permission already granted.");
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendToLog("Storage Permission Granted. Starting download...");
                String urlString = urlEditText.getText().toString().trim();
                 if (!urlString.isEmpty()) {
                    executeDownload(urlString);
                } else {
                    appendToLog("Error: URL was empty after permission grant.");
                }
            } else {
                appendToLog("Storage Permission Denied. Cannot download files.");
            }
        }
    }

    private void executeDownload(final String url) {
        appendToLog("Starting download for URL: " + url);

        File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir == null) {
            appendToLog("Error: External storage for downloads is not available.");
            return;
        }

        String outputPath = downloadsDir.getAbsolutePath();
        File outputDirFile = new File(outputPath);
        if (!outputDirFile.exists()) {
            if (!outputDirFile.mkdirs()) {
                appendToLog("Error: Could not create download directory: " + outputPath);
                return;
            }
        }
        appendToLog("Download path: " + outputPath);


        new Thread(new Runnable() {
            @Override
            public void run() {
                String wgetExecutable = null;
                try {
                    // Using nativeLibraryDir which points to the location of jniLibs
                    wgetExecutable = getApplicationInfo().nativeLibraryDir + File.separator + "wget";
                    appendToLog("Wget path: " + wgetExecutable);

                    File wgetFile = new File(wgetExecutable);
                    if (!wgetFile.exists()) {
                        appendToLog("Error: wget executable not found at " + wgetExecutable);
                        return;
                    }

                    // Make wget executable
                    if (!wgetFile.canExecute()) {
                        if (wgetFile.setExecutable(true)) {
                            appendToLog("Made wget executable.");
                        } else {
                            appendToLog("Error: Failed to make wget executable. Check permissions or if the file is on a noexec mount.");
                            // Attempt to copy to a directory where execution might be allowed
                            File internalDir = getFilesDir();
                            File internalWget = new File(internalDir, "wget");
                            try {
                                java.nio.file.Files.copy(wgetFile.toPath(), internalWget.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                if (internalWget.setExecutable(true)) {
                                    wgetExecutable = internalWget.getAbsolutePath();
                                    appendToLog("Copied wget to internal storage and made executable: " + wgetExecutable);
                                } else {
                                    appendToLog("Error: Still failed to make wget executable in internal storage.");
                                    return;
                                }
                            } catch (IOException e) {
                                appendToLog("Error copying wget to internal storage: " + e.getMessage());
                                Log.e(TAG, "Error copying wget", e);
                                return;
                            }
                        }
                    } else {
                         // appendToLog("wget is already executable.");
                    }

                    // Basic wget command: --page-requisites, --html-extension, --convert-links, --domains (restrict to initial domain), --no-parent
                    // --recursive for full site, --level=1 for single page and immediate resources
                    // -P specifies the download directory
                    // Example: wget --recursive --no-parent --page-requisites --html-extension --convert-links --domains example.com -P /path/to/download http://example.com/
                    // For simplicity, let's start with a less aggressive download for testing
                    // String[] command = {wgetExecutable, "--page-requisites", "-P", outputPath, url};

                    // From original script:
                    // WGET_CMD="wget --mirror --convert-links --adjust-extension --page-requisites --no-parent -P \"${OUTPUT_DIR}\" \"${URL}\""
                    // Translating to array:
                    String[] command = {
                        wgetExecutable,
                        "--mirror", // Creates a mirror of the site. Includes --recursive, --timestamping, --level inf.
                        "--convert-links", // Convert links for local viewing.
                        "--adjust-extension", // Add .html or .css to files if they are of that type.
                        "--page-requisites", // Download all files necessary to display a given HTML page.
                        "--no-parent", // Do not ascend to the parent directory when retrieving recursively.
                        "-P", outputPath, // Set directory prefix to specified directory.
                        url
                    };

                    appendToLog("Executing command: " + String.join(" ", command));

                    ProcessBuilder processBuilder = new ProcessBuilder(command);
                    // processBuilder.directory(new File(outputPath)); // Not strictly needed as -P is used
                    Process process = processBuilder.start();

                    // Read output stream
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        appendToLog(line);
                    }
                    reader.close();

                    // Read error stream
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                    while ((line = errorReader.readLine()) != null) {
                        appendToLog("Error: " + line);
                    }
                    errorReader.close();

                    int exitCode = process.waitFor();
                    if (exitCode == 0) {
                        appendToLog("Download completed successfully. Exit code: " + exitCode);
                    } else {
                        appendToLog("Download failed. Exit code: " + exitCode);
                    }

                } catch (IOException e) {
                    appendToLog("IOException during download: " + e.getMessage());
                    Log.e(TAG, "IOException during download", e);
                } catch (InterruptedException e) {
                    appendToLog("Download process interrupted: " + e.getMessage());
                    Log.e(TAG, "Download process interrupted", e);
                    Thread.currentThread().interrupt(); // Restore interrupted status
                } catch (Exception e) {
                    appendToLog("An unexpected error occurred: " + e.getMessage());
                    Log.e(TAG, "Unexpected error", e);
                }
            }
        }).start();
    }

    private void appendToLog(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logTextView.append(message + "\n");
                Log.d(TAG, message); // Also log to Logcat
            }
        });
    }
}
