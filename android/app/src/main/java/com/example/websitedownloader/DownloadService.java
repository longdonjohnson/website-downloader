package com.example.websitedownloader;

import android.app.Service;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.util.Pair;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.SSLException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class DownloadService extends Service {

    private static final String TAG = "DownloadService";

    public static final String ACTION_DOWNLOAD_STATUS_UPDATE = "com.example.websitedownloader.DOWNLOAD_STATUS_UPDATE";
    public static final String ACTION_CANCEL_DOWNLOAD = "com.example.websitedownloader.ACTION_CANCEL_DOWNLOAD";

    public static final String EXTRA_LOG_MESSAGE = "log_message";
    public static final String EXTRA_DOWNLOAD_STATUS = "download_status";
    public static final String EXTRA_ERROR_MESSAGE = "error_message";

    public static final String STATUS_PROGRESS = "PROGRESS";
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_STARTING = "STARTING";

    // Extra for includeSubdomains
    public static final String EXTRA_INCLUDE_SUBDOMAINS = "INCLUDE_SUBDOMAINS";


    private OkHttpClient httpClient;
    private Queue<Pair<String, Integer>> urlQueue;
    private Set<String> visitedUrls;
    private Map<String, String> downloadedResourcePaths;
    private volatile boolean isCancelled = false;
    private Thread downloadThread;
    private int currentStartId;
    private boolean currentIncludeSubdomainsFlag = false; // Store the flag for the current download session


    @Override
    public void onCreate() {
        super.onCreate();
        httpClient = new OkHttpClient.Builder().build();
        urlQueue = new LinkedList<>();
        visitedUrls = new HashSet<>();
        downloadedResourcePaths = new HashMap<>();
        Log.d(TAG, "Service Created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        this.currentStartId = startId;
        Log.d(TAG, "onStartCommand received: " + (intent != null ? intent.getAction() : "null intent"));

        if (intent != null && ACTION_CANCEL_DOWNLOAD.equals(intent.getAction())) {
            Log.d(TAG, "Cancel action received");
            isCancelled = true;
            if (httpClient != null) {
                httpClient.dispatcher().cancelAll();
            }
            if (downloadThread != null && downloadThread.isAlive()) {
                downloadThread.interrupt();
            }
            sendUpdateBroadcast("Cancellation signal processed by service.", STATUS_PROGRESS, null);
            return START_NOT_STICKY;
        }

        final String url = intent != null ? intent.getStringExtra("URL") : null;
        final int depth = intent != null ? intent.getIntExtra("DEPTH", 0) : 0;
        currentIncludeSubdomainsFlag = intent != null && intent.getBooleanExtra(EXTRA_INCLUDE_SUBDOMAINS, false); // Retrieve and store

        if (url == null || url.isEmpty()) {
            sendUpdateBroadcast("Error: URL is null or empty.", STATUS_ERROR, "URL missing");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        sendUpdateBroadcast("Download starting (Include Subdomains: " + currentIncludeSubdomainsFlag + ") for URL: " + url + " with depth: " + depth, STATUS_STARTING, null);

        isCancelled = false;
        visitedUrls.clear();
        urlQueue.clear();
        downloadedResourcePaths.clear();

        downloadThread = new Thread(() -> {
            String finalStatus = STATUS_COMPLETE;
            String finalErrorMessage = null;
            try {
                urlQueue.add(new Pair<>(url, 0));
                final File baseDownloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (baseDownloadDir == null) {
                    throw new IOException("External storage not available.");
                }

                String host;
                try {
                    host = new URL(url).getHost();
                } catch (MalformedURLException e) {
                    Log.e(TAG, "Initial URL malformed: " + url, e);
                    throw new IOException("Initial URL is malformed: " + e.getMessage());
                }

                final String siteSpecificDirName = host.replaceAll("[^a-zA-Z0-9.-]", "_");
                final File siteSpecificDirFile = new File(baseDownloadDir, siteSpecificDirName);
                if (!siteSpecificDirFile.exists() && !siteSpecificDirFile.mkdirs()) {
                     throw new IOException("Could not create site-specific directory: " + siteSpecificDirFile.getAbsolutePath());
                }
                sendUpdateBroadcast("Download path: " + siteSpecificDirFile.getAbsolutePath(), STATUS_PROGRESS, null);

                while (!urlQueue.isEmpty() && !isCancelled) {
                    Pair<String, Integer> currentEntry = urlQueue.poll();
                    String currentUrlString = currentEntry.first;
                    int currentDepth = currentEntry.second;

                    if (isCancelled) { finalStatus = STATUS_CANCELLED; break; }

                    if (currentDepth > depth || !visitedUrls.add(currentUrlString)) {
                        if (currentDepth > depth) sendUpdateBroadcast("Max depth (" + depth + ") reached for: " + currentUrlString, STATUS_PROGRESS, null);
                        continue;
                    }

                    URL currentUrlObj;
                    try {
                        currentUrlObj = new URL(currentUrlString);
                    } catch (MalformedURLException e) {
                        Log.w(TAG, "Malformed URL in queue: " + currentUrlString, e);
                        sendUpdateBroadcast("Skipping invalid URL: " + currentUrlString, STATUS_PROGRESS, null);
                        continue;
                    }

                    if (isCancelled) { finalStatus = STATUS_CANCELLED; break; }
                    sendUpdateBroadcast("Processing (Depth " + currentDepth + "/" + depth + "): " + currentUrlString, STATUS_PROGRESS, null);
                    Request request = new Request.Builder().url(currentUrlObj).build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (isCancelled) { finalStatus = STATUS_CANCELLED; break;}

                        if (!response.isSuccessful()) {
                            sendUpdateBroadcast("Failed: " + currentUrlString + " (" + response.code() + " " + response.message() + ")", STATUS_PROGRESS, null);
                            continue;
                        }
                        ResponseBody body = response.body();
                        if (body == null) {
                            sendUpdateBroadcast("Empty response for: " + currentUrlString, STATUS_PROGRESS, null);
                            continue;
                        }

                        String relativePath = extractRelativePath(currentUrlString, response);
                        if (relativePath == null) {
                            sendUpdateBroadcast("Could not determine save path for: " + currentUrlString, STATUS_PROGRESS, null);
                            continue;
                        }
                        File outputFile = new File(siteSpecificDirFile, relativePath);
                        File parentDir = outputFile.getParentFile();
                        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                            throw new IOException("Cannot create parent directory: " + parentDir.getAbsolutePath() + " for file " + outputFile.getName());
                        }

                        try (InputStream inputStream = body.byteStream();
                             FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = inputStream.read(buffer)) != -1) {
                                if (isCancelled) {
                                    outputStream.close();
                                    outputFile.delete();
                                    throw new IOException("Download cancelled during file write for " + currentUrlString);
                                }
                                outputStream.write(buffer, 0, bytesRead);
                            }
                        }

                        if (!isCancelled) {
                            downloadedResourcePaths.put(currentUrlString, relativePath);
                            sendUpdateBroadcast("Saved: " + relativePath + " (" + outputFile.length() + " bytes)", STATUS_PROGRESS, null);
                        }

                        String contentType = response.header("Content-Type");
                        if (!isCancelled && contentType != null && contentType.toLowerCase().contains("text/html")) {
                            String htmlContent = "";
                            try {
                                htmlContent = new String(Files.readAllBytes(outputFile.toPath()));
                            } catch (OutOfMemoryError oom) {
                                Log.e(TAG, "OutOfMemoryError reading HTML file for parsing: " + relativePath, oom);
                                sendUpdateBroadcast("File too large to parse for links: " + relativePath, STATUS_PROGRESS, null);
                                continue;
                            }

                            Document doc;
                            try {
                                doc = Jsoup.parse(htmlContent, currentUrlString);
                            } catch (Exception e_parse) {
                                Log.e(TAG, "Error parsing HTML for " + currentUrlString, e_parse);
                                sendUpdateBroadcast("Error parsing HTML (" + relativePath + "): " + e_parse.getMessage(), STATUS_PROGRESS, null);
                                continue;
                            }

                            Elements links = doc.select("a[href], img[src], link[href], script[src]");
                            Path currentHtmlFileDirPath = outputFile.getParentFile().toPath();
                            boolean modified = false;

                            for (Element link : links) {
                                if (isCancelled) { finalStatus = STATUS_CANCELLED; break; }
                                String attrToChange = link.hasAttr("href") ? "href" : (link.hasAttr("src") ? "src" : null);
                                if (attrToChange == null) continue;

                                String absoluteLinkUrl = link.absUrl(attrToChange);
                                if (downloadedResourcePaths.containsKey(absoluteLinkUrl)) {
                                    String targetLocalRelativePath = downloadedResourcePaths.get(absoluteLinkUrl);
                                    File targetFile = new File(siteSpecificDirFile, targetLocalRelativePath);
                                    Path relativePathToTarget = currentHtmlFileDirPath.relativize(targetFile.toPath());
                                    link.attr(attrToChange, relativePathToTarget.toString().replace(File.separatorChar, '/'));
                                    modified = true;
                                }
                            }
                            if (isCancelled) { finalStatus = STATUS_CANCELLED; break; }

                            if (modified) {
                                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                                    fos.write(doc.outerHtml().getBytes("UTF-8"));
                                    sendUpdateBroadcast("Rewrote links in: " + relativePath, STATUS_PROGRESS, null);
                                } catch (IOException e_rewrite) {
                                     if (isCancelled) { finalStatus = STATUS_CANCELLED; }
                                     else {
                                        Log.e(TAG, "Error rewriting HTML for " + relativePath, e_rewrite);
                                        sendUpdateBroadcast("Error saving rewritten HTML (" + relativePath + "): " + e_rewrite.getMessage(), STATUS_PROGRESS, null);
                                     }
                                }
                            }

                            if (currentDepth < depth) {
                                for (Element link : links) {
                                    if (isCancelled) { finalStatus = STATUS_CANCELLED; break; }
                                    String attr = link.hasAttr("href") ? "href" : (link.hasAttr("src") ? "src" : null);
                                    if (attr == null) continue;
                                    String originalAbsoluteUrl = link.absUrl(attr);
                                    // Pass currentIncludeSubdomainsFlag to isValidToFollow
                                    if (isValidToFollow(originalAbsoluteUrl, url, currentIncludeSubdomainsFlag) && !visitedUrls.contains(originalAbsoluteUrl) && !urlQueue.stream().anyMatch(p -> p.first.equals(originalAbsoluteUrl))) {
                                        urlQueue.add(new Pair<>(originalAbsoluteUrl, currentDepth + 1));
                                    }
                                }
                            }
                        }
                    } catch (UnknownHostException e_net) {
                        Log.e(TAG, "Network error for " + currentUrlString, e_net);
                        sendUpdateBroadcast("Unknown host or network unavailable for: " + currentUrlString, STATUS_PROGRESS, null);
                    } catch (SocketTimeoutException e_net) {
                        Log.e(TAG, "Network error for " + currentUrlString, e_net);
                        sendUpdateBroadcast("Connection timed out for: " + currentUrlString, STATUS_PROGRESS, null);
                    } catch (SSLException e_net) {
                        Log.e(TAG, "SSL error for " + currentUrlString, e_net);
                        sendUpdateBroadcast("SSL connection error for: " + currentUrlString, STATUS_PROGRESS, null);
                    } catch (IOException e_io) {
                        if (isCancelled) {
                            finalStatus = STATUS_CANCELLED;
                            Log.i(TAG, "Download cancelled for " + currentUrlString + " during IO: " + e_io.getMessage());
                            sendUpdateBroadcast("Cancelled during operation for: " + currentUrlString, STATUS_PROGRESS, null);
                        } else {
                            finalStatus = STATUS_ERROR;
                            finalErrorMessage = "File system or IO error: " + e_io.getMessage();
                            Log.e(TAG, "File/IO error for " + currentUrlString, e_io);
                            sendUpdateBroadcast(finalErrorMessage + " for " + currentUrlString, STATUS_PROGRESS, null);
                        }
                        if (finalStatus.equals(STATUS_ERROR) || finalStatus.equals(STATUS_CANCELLED)) break;
                    } catch (Exception e_general) {
                        Log.e(TAG, "Unexpected error processing " + currentUrlString, e_general);
                        sendUpdateBroadcast("Unexpected error (" + currentUrlString + "): " + e_general.getMessage(), STATUS_PROGRESS, null);
                    }
                     if (isCancelled) {finalStatus = STATUS_CANCELLED; break;}
                }
            } catch (IOException e_setup) {
                Log.e(TAG, "Error in download thread setup", e_setup);
                finalStatus = STATUS_ERROR;
                finalErrorMessage = e_setup.getMessage();
            } catch (Exception e_thread_general) {
                 Log.e(TAG, "General error in download thread", e_thread_general);
                finalStatus = STATUS_ERROR;
                finalErrorMessage = e_thread_general.getMessage();
            }
            finally {
                if (isCancelled && !finalStatus.equals(STATUS_CANCELLED)) {
                    finalStatus = STATUS_CANCELLED;
                }
                String endMessage = "Download process " + finalStatus.toLowerCase() + ".";
                if (finalStatus.equals(STATUS_ERROR) && finalErrorMessage != null) {
                    endMessage += " Error: " + finalErrorMessage;
                } else if (finalStatus.equals(STATUS_CANCELLED)){
                     endMessage = "Download explicitly cancelled by user.";
                }
                sendUpdateBroadcast(endMessage, finalStatus, finalErrorMessage);
                stopSelf(currentStartId);
            }
        });
        downloadThread.start();

        return START_NOT_STICKY;
    }

    private void sendUpdateBroadcast(String message, String status, @Nullable String errorMessage) {
        Intent intent = new Intent(ACTION_DOWNLOAD_STATUS_UPDATE);
        intent.putExtra(EXTRA_LOG_MESSAGE, message);
        intent.putExtra(EXTRA_DOWNLOAD_STATUS, status);
        if (STATUS_ERROR.equals(status) && errorMessage != null) {
            intent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage);
        }
        sendBroadcast(intent);
    }

    private String extractRelativePath(String urlString, Response response) {
        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            Log.e(TAG, "extractRelativePath: Malformed URL " + urlString, e);
            return "malformed_urls/" + UUID.randomUUID().toString() + ".html";
        }
        String path = url.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            path = "/index.html";
        } else if (path.endsWith("/")) {
            path += "index.html";
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        File f = new File(path);
        String name = f.getName();
        if (name.isEmpty()) {
            path = (f.getParent() != null ? f.getParent() + File.separator : "") + "index.html";
            name = "index.html";
        }

        if (!name.contains(".")) {
             String contentType = response.header("Content-Type");
             String extension = null;
             if (contentType != null) {
                 extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType.split(";")[0].trim());
             }
             if (extension != null) {
                 path += "." + extension;
             } else if (contentType != null && contentType.toLowerCase().contains("text/html") && !path.endsWith(".html")) {
                  path += ".html";
             }
        }

        String[] pathComponents = path.split("/");
        StringBuilder sanitizedPath = new StringBuilder();
        for (int i = 0; i < pathComponents.length; i++) {
            String component = pathComponents[i];
            component = component.replaceAll("[^a-zA-Z0-9._-]+", "_").trim();
            if (component.isEmpty()) {
                component = (i == pathComponents.length -1) ? "file" : "dir";
            }
            if (component.length() > 64) {
                component = component.substring(0, 64);
            }
            sanitizedPath.append(component);
            if (i < pathComponents.length - 1) {
                sanitizedPath.append(File.separatorChar);
            }
        }
        String resultPath = sanitizedPath.toString();
        if (resultPath.isEmpty() || resultPath.endsWith(File.separator)) {
            return "index.html";
        }
        return resultPath;
    }

    // Modified isValidToFollow
    private boolean isValidToFollow(String nextUrlString, String initialUrlString, boolean includeSubdomains) {
        if (nextUrlString == null || nextUrlString.trim().isEmpty()) return false;
        try {
            URL nextUrl = new URL(nextUrlString);
            URL initialUrl = new URL(initialUrlString);

            if (!nextUrl.getProtocol().matches("^https?$")) { // Only http/https
                return false;
            }

            String nextHost = nextUrl.getHost();
            String initialHost = initialUrl.getHost();

            if (nextHost == null || initialHost == null) {
                return false; // Should not happen with valid URLs
            }

            nextHost = nextHost.toLowerCase();
            initialHost = initialHost.toLowerCase();

            if (includeSubdomains) {
                return nextHost.equals(initialHost) || nextHost.endsWith("." + initialHost);
            } else {
                return nextHost.equals(initialHost);
            }
        } catch (MalformedURLException e) {
            Log.w(TAG, "isValidToFollow: Malformed URL encountered: " + nextUrlString + " or " + initialUrlString, e);
            return false;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service Destroyed");
        isCancelled = true;
        if (httpClient != null) {
            httpClient.dispatcher().cancelAll();
        }
        if (downloadThread != null) {
            downloadThread.interrupt();
        }
    }
}
