package com.example.websitedownloader;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MainActivity extends AppCompatActivity {

    private static final int STORAGE_PERMISSION_CODE = 101;
    private static final String TAG = "MainActivity";
    private static final int MAX_DEPTH = 1; // Max recursion depth for downloading linked resources

    EditText urlEditText;
    Button downloadButton;
    TextView logTextView;

    private OkHttpClient httpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlEditText = findViewById(R.id.urlEditText);
        downloadButton = findViewById(R.id.downloadButton);
        logTextView = findViewById(R.id.logTextView);

        httpClient = new OkHttpClient();

        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownloadProcess();
            }
        });
    }

    private void startDownloadProcess() {
        logTextView.setText(""); // Clear previous logs
        String initialUrlString = urlEditText.getText().toString().trim();

        if (initialUrlString.isEmpty()) {
            appendToLog("Error: URL cannot be empty.");
            return;
        }

        URL validatedInitialUrl;
        try {
            validatedInitialUrl = new URL(initialUrlString); // Validate URL format
            if (!validatedInitialUrl.getProtocol().startsWith("http")) {
                 appendToLog("Error: URL must start with http:// or https://");
                 return;
            }
        } catch (MalformedURLException e) {
            appendToLog("Error: Invalid URL format. Please include http:// or https://");
            Log.e(TAG, "Invalid URL format", e);
            return;
        }

        if (checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, STORAGE_PERMISSION_CODE)) {
            executeDownload(validatedInitialUrl.toString());
        }
    }

    private boolean checkPermission(String permission, int requestCode) {
        if (ContextCompat.checkSelfPermission(MainActivity.this, permission) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{permission}, requestCode);
            appendToLog("Storage permission requested...");
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendToLog("Storage Permission Granted. Starting download...");
                String urlString = urlEditText.getText().toString().trim();
                if (!urlString.isEmpty()) {
                    try {
                        URL validatedUrl = new URL(urlString);
                         executeDownload(validatedUrl.toString());
                    } catch (MalformedURLException e) {
                         appendToLog("Error: Invalid URL format after permission grant.");
                    }
                } else {
                    appendToLog("Error: URL was empty after permission grant.");
                }
            } else {
                appendToLog("Storage Permission Denied. Cannot download files.");
            }
        }
    }

    private void executeDownload(final String initialUrl) {
        appendToLog("Preparing to download from: " + initialUrl);

        final File baseDownloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (baseDownloadDir == null) {
            appendToLog("Error: External storage for downloads is not available.");
            return;
        }
        if (!baseDownloadDir.exists() && !baseDownloadDir.mkdirs()) {
            appendToLog("Error: Could not create base download directory: " + baseDownloadDir.getAbsolutePath());
            return;
        }

        // Create a unique subdirectory for this download session based on initial URL's host
        String host;
        try {
            host = new URL(initialUrl).getHost();
        } catch (MalformedURLException e) {
            host = "unknown_host";
        }
        final File siteSpecificDir = new File(baseDownloadDir, host.replaceAll("[^a-zA-Z0-9.-]", "_"));
        if (!siteSpecificDir.exists() && !siteSpecificDir.mkdirs()) {
            appendToLog("Error: Could not create site-specific download directory: " + siteSpecificDir.getAbsolutePath());
            return;
        }
        appendToLog("Download path: " + siteSpecificDir.getAbsolutePath());


        new Thread(new Runnable() {
            @Override
            public void run() {
                Set<String> visitedUrls = new HashSet<>();
                Queue<Pair<String, Integer>> urlQueue = new LinkedList<>();

                urlQueue.add(new Pair<>(initialUrl, 0));

                while (!urlQueue.isEmpty()) {
                    Pair<String, Integer> currentEntry = urlQueue.poll();
                    String currentUrlString = currentEntry.first;
                    int currentDepth = currentEntry.second;

                    if (currentDepth > MAX_DEPTH || !visitedUrls.add(currentUrlString)) {
                        if (currentDepth > MAX_DEPTH) appendToLog("Max depth reached for: " + currentUrlString);
                        else appendToLog("Already visited: " + currentUrlString);
                        continue;
                    }

                    URL currentUrlObj;
                    try {
                        currentUrlObj = new URL(currentUrlString);
                    } catch (MalformedURLException e) {
                        appendToLog("Skipping invalid URL in queue: " + currentUrlString);
                        continue;
                    }

                    appendToLog("Processing (Depth " + currentDepth + "): " + currentUrlString);
                    Request request = new Request.Builder().url(currentUrlObj).build();

                    try (Response response = httpClient.newCall(request).execute()) { // Synchronous call in background thread
                        if (!response.isSuccessful()) {
                            appendToLog("Failed: " + currentUrlString + " (" + response.code() + " " + response.message() + ")");
                            continue;
                        }

                        ResponseBody body = response.body();
                        if (body == null) {
                            appendToLog("Empty response body: " + currentUrlString);
                            continue;
                        }

                        String filename = extractFilename(currentUrlString, response);
                        File outputFile = new File(siteSpecificDir, filename);

                        // Ensure parent directories for nested resources exist
                        File parentDir = outputFile.getParentFile();
                        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                            appendToLog("Error creating parent directory for: " + outputFile.getAbsolutePath());
                            continue;
                        }


                        try (InputStream inputStream = body.byteStream();
                             FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = inputStream.read(buffer)) != -1) {
                                outputStream.write(buffer, 0, bytesRead);
                            }
                        }
                        appendToLog("Saved: " + outputFile.getName() + " (Size: " + outputFile.length() + " bytes)");

                        String contentType = response.header("Content-Type");
                        if (contentType != null && contentType.toLowerCase().contains("text/html") && currentDepth < MAX_DEPTH) {
                            // Re-read the saved file to parse with Jsoup
                            String htmlContent;
                            try {
                                htmlContent = new String(Files.readAllBytes(outputFile.toPath()));
                            } catch (OutOfMemoryError oom) {
                                appendToLog("File too large to parse for links: " + outputFile.getName());
                                continue;
                            }

                            Document doc = Jsoup.parse(htmlContent, currentUrlString);
                            Elements links = doc.select("a[href], img[src], link[href], script[src]");

                            for (Element link : links) {
                                String attr = link.hasAttr("href") ? "href" : "src";
                                String absoluteUrl = link.absUrl(attr); // Jsoup handles relative to absolute conversion

                                if (isValidToFollow(absoluteUrl, initialUrl)) {
                                    urlQueue.add(new Pair<>(absoluteUrl, currentDepth + 1));
                                    // appendToLog("Queued: " + absoluteUrl);
                                }
                            }
                        }
                    } catch (IOException e) {
                        appendToLog("Error processing " + currentUrlString + ": " + e.getMessage());
                        Log.e(TAG, "IOException for " + currentUrlString, e);
                    } catch (Exception e) {
                        appendToLog("Unexpected error for " + currentUrlString + ": " + e.getMessage());
                        Log.e(TAG, "Unexpected error for " + currentUrlString, e);
                    }
                } // End while loop
                appendToLog("Download process finished.");
            }
        }).start();
    }

    private String extractFilename(String urlString, Response response) {
        String filename = null;
        String contentDisposition = response.header("Content-Disposition");
        if (contentDisposition != null) {
            String[] parts = contentDisposition.split("filename=");
            if (parts.length > 1) {
                filename = parts[1].replace("\"", "").trim();
            }
        }

        if (filename == null) {
            try {
                URL url = new URL(urlString);
                String path = url.getPath();
                if (path.endsWith("/")) { // Handle directory-like URLs
                    filename = "index.html"; // Default for directory-like URLs
                } else {
                    filename = new File(path).getName();
                    if (filename.isEmpty()) filename = "index.html"; // if path was just "/"
                }
            } catch (MalformedURLException e) {
                filename = UUID.randomUUID().toString(); // Fallback for malformed URLs
            }
        }

        // Ensure filename has an extension if possible, otherwise use a default or derived one
        if (!filename.contains(".")) {
            String contentType = response.header("Content-Type");
            String extension = null;
            if (contentType != null) {
                extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType.split(";")[0].trim());
            }
            if (extension != null) {
                filename += "." + extension;
            } else if (contentType != null && contentType.toLowerCase().contains("text/html")) {
                 filename += ".html"; // Default for HTML if MIME type is generic
            } else {
                // If no extension can be derived, and it's not index.html already
                // (e.g. from a path like /about/), and it's likely HTML, append .html
                if (filename.equals(new File(urlString).getName()) && (urlString.endsWith("/") || !urlString.substring(urlString.lastIndexOf("/") + 1).contains("."))) {
                     // This logic might be too aggressive, but aims to save "domain.com/about" as "about.html"
                     // filename += ".html";
                }
            }
        }
        // Sanitize filename (basic)
        filename = filename.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (filename.length() > 100) filename = filename.substring(0,100); // Max length

        return filename;
    }

    private boolean isValidToFollow(String nextUrl, String initialUrl) {
        if (nextUrl == null || nextUrl.trim().isEmpty()) return false;
        try {
            URL next = new URL(nextUrl);
            URL initial = new URL(initialUrl);
            // Only follow http/https and same host
            return next.getProtocol().matches("^https?$" ) && next.getHost().equalsIgnoreCase(initial.getHost());
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private void appendToLog(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logTextView.append(message + "\n");
                Log.d(TAG, message);
            }
        });
    }
}
