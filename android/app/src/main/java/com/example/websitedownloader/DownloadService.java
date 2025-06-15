package com.example.websitedownloader;

import android.app.Service;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull; // Added for Interceptor
import androidx.annotation.Nullable;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

import okhttp3.Interceptor; // Added
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

    public static final String EXTRA_INCLUDE_SUBDOMAINS = "INCLUDE_SUBDOMAINS";

    // Define User-Agent String
    private static final String COMMON_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36";

    private OkHttpClient httpClient;
    private Queue<Pair<String, Integer>> urlQueue;
    private Set<String> visitedUrls;
    private Map<String, String> downloadedResourcePaths;
    private volatile boolean isCancelled = false;
    private Thread downloadThread;
    private int currentStartId;
    private boolean currentIncludeSubdomainsFlag = false;

    private static final Pattern ILLEGAL_FILENAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]");
    private static final int MAX_FILENAME_LENGTH = 150;
    private static final int MAX_PATH_SEGMENT_LENGTH = 100;


    @Override
    public void onCreate() {
        super.onCreate();
        // Modify OkHttpClient Initialization to add User-Agent Interceptor
        httpClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @NonNull
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request originalRequest = chain.request();
                        Request requestWithUserAgent = originalRequest.newBuilder()
                                .header("User-Agent", COMMON_USER_AGENT)
                                .build();
                        return chain.proceed(requestWithUserAgent);
                    }
                })
                .build();
        urlQueue = new LinkedList<>();
        visitedUrls = new HashSet<>();
        downloadedResourcePaths = new HashMap<>();
        Log.d(TAG, "Service Created with User-Agent Interceptor");
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
        currentIncludeSubdomainsFlag = intent != null && intent.getBooleanExtra(EXTRA_INCLUDE_SUBDOMAINS, false);

        if (url == null || url.isEmpty()) {
            sendUpdateBroadcast("Error: URL is null or empty.", STATUS_ERROR, "URL missing");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // Log User-Agent once per new download session
        sendUpdateBroadcast("Using User-Agent: " + COMMON_USER_AGENT, STATUS_PROGRESS, null);
        sendUpdateBroadcast("Download starting (Subdomains: " + (currentIncludeSubdomainsFlag?"Yes":"No") + ", Depth: "+depth+") for: " + url, STATUS_STARTING, null);

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

                final String siteSpecificDirName = sanitizePathComponent(host);
                final File siteSpecificDirFile = new File(baseDownloadDir, siteSpecificDirName);
                if (!siteSpecificDirFile.exists() && !siteSpecificDirFile.mkdirs()) {
                     throw new IOException("Could not create site-specific directory: " + siteSpecificDirFile.getAbsolutePath());
                }
                sendUpdateBroadcast("Download path: " + siteSpecificDirFile.getAbsolutePath(), STATUS_PROGRESS, null);

                while (!urlQueue.isEmpty() && !isCancelled) {
                    Pair<String, Integer> currentEntry = urlQueue.poll();
                    String currentUrlString = currentEntry.first;
                    int currentDepth = currentEntry.second;

                    sendUpdateBroadcast("Dequeued (Depth " + currentDepth + "/" + depth + "): " + currentUrlString, STATUS_PROGRESS, null);


                    if (isCancelled) { finalStatus = STATUS_CANCELLED; break; }

                    if (visitedUrls.contains(currentUrlString)) {
                        sendUpdateBroadcast("Skipping (already visited): " + currentUrlString, STATUS_PROGRESS, null);
                        continue;
                    }
                    if (currentDepth > depth) {
                        sendUpdateBroadcast("Skipping (max depth " + depth + " exceeded): " + currentUrlString, STATUS_PROGRESS, null);
                        continue;
                    }
                    visitedUrls.add(currentUrlString);


                    URL currentUrlObj;
                    try {
                        currentUrlObj = new URL(currentUrlString);
                    } catch (MalformedURLException e) {
                        Log.w(TAG, "Malformed URL in queue: " + currentUrlString, e);
                        sendUpdateBroadcast("Skipping (malformed URL): " + currentUrlString, STATUS_PROGRESS, null);
                        continue;
                    }

                    if (isCancelled) { finalStatus = STATUS_CANCELLED; break; }
                    sendUpdateBroadcast("Attempting to download: " + currentUrlString, STATUS_PROGRESS, null);
                    // Request will now include User-Agent via the interceptor
                    Request request = new Request.Builder().url(currentUrlObj).build();


                    try (Response response = httpClient.newCall(request).execute()) {
                        if (isCancelled) { finalStatus = STATUS_CANCELLED; break;}

                        if (!response.isSuccessful()) {
                            sendUpdateBroadcast("Failed download: " + currentUrlString + " (" + response.code() + " " + response.message() + ")", STATUS_PROGRESS, null);
                            continue;
                        }
                        ResponseBody body = response.body();
                        if (body == null) {
                            sendUpdateBroadcast("Empty response body for: " + currentUrlString, STATUS_PROGRESS, null);
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
                            sendUpdateBroadcast("Saved: " + outputFile.getAbsolutePath() + " (" + formatFileSize(outputFile.length()) + ")", STATUS_PROGRESS, null);
                        }

                        String contentType = response.header("Content-Type");
                        if (!isCancelled && contentType != null && contentType.toLowerCase().contains("text/html")) {
                            sendUpdateBroadcast("Parsing HTML for links: " + relativePath, STATUS_PROGRESS, null);
                            String htmlContent = "";
                            try {
                                htmlContent = new String(Files.readAllBytes(outputFile.toPath()), "UTF-8");
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

                            String selector = "a[href], link[href], script[src], img[src], source[src], track[src], iframe[src], object[data], embed[src]";
                            Elements links = doc.select(selector);
                            Path currentHtmlFileDirPath = outputFile.getParentFile().toPath();
                            boolean modified = false;
                            int linksRewrittenCount = 0;
                            int newLinksAddedCount = 0;

                            sendUpdateBroadcast("Rewriting links in: " + relativePath, STATUS_PROGRESS, null);
                            for (Element link : links) {
                                if (isCancelled) { finalStatus = STATUS_CANCELLED; break; }

                                String attrToChange = "";
                                if (link.hasAttr("href")) {
                                    attrToChange = "href";
                                } else if (link.hasAttr("src")) {
                                    attrToChange = "src";
                                } else if (link.tagName().equals("object") && link.hasAttr("data")) {
                                    attrToChange = "data";
                                } else {
                                    continue;
                                }

                                String absoluteLinkUrl = link.absUrl(attrToChange);
                                if (absoluteLinkUrl.isEmpty()) {
                                    continue;
                                }

                                if (downloadedResourcePaths.containsKey(absoluteLinkUrl)) {
                                    String targetLocalRelativePath = downloadedResourcePaths.get(absoluteLinkUrl);
                                    File targetFile = new File(siteSpecificDirFile, targetLocalRelativePath);
                                    try {
                                        Path relativePathToTarget = currentHtmlFileDirPath.relativize(targetFile.toPath());
                                        String finalRelPath = relativePathToTarget.toString().replace(File.separatorChar, '/');
                                        link.attr(attrToChange, finalRelPath);
                                        modified = true;
                                        linksRewrittenCount++;
                                    } catch (IllegalArgumentException e_rel) {
                                        Log.e(TAG, "Could not relativize path for link: " + absoluteLinkUrl + " in " + currentUrlString + " (target: " + targetFile.getAbsolutePath() + ", base: "+ currentHtmlFileDirPath.toString() +")", e_rel);
                                        sendUpdateBroadcast("Error relativizing link " + absoluteLinkUrl.substring(0, Math.min(absoluteLinkUrl.length(), 50)) +"... in " + relativePath, STATUS_PROGRESS, null);
                                    }
                                }
                            }
                             if (modified && linksRewrittenCount > 0) {
                                sendUpdateBroadcast("Finished rewriting " + linksRewrittenCount + " links in: " + relativePath, STATUS_PROGRESS, null);
                            }


                            if (isCancelled) { finalStatus = STATUS_CANCELLED; break; }

                            if (modified) {
                                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                                    fos.write(doc.outerHtml().getBytes("UTF-8"));
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
                                    String attrKey = "";
                                     if (link.hasAttr("href")) {
                                        attrKey = "href";
                                    } else if (link.hasAttr("src")) {
                                        attrKey = "src";
                                    } else if (link.tagName().equals("object") && link.hasAttr("data")) {
                                        attrKey = "data";
                                    } else {
                                        continue;
                                    }
                                    String originalAbsoluteUrl = link.absUrl(attrKey);
                                    if (originalAbsoluteUrl.isEmpty()) continue;

                                    if (isValidToFollow(originalAbsoluteUrl, url, currentIncludeSubdomainsFlag)) {
                                        if (!visitedUrls.contains(originalAbsoluteUrl) && !urlQueue.stream().anyMatch(p -> p.first.equals(originalAbsoluteUrl))) {
                                            urlQueue.add(new Pair<>(originalAbsoluteUrl, currentDepth + 1));
                                            newLinksAddedCount++;
                                        }
                                    } else {
                                        sendUpdateBroadcast("Skipping (domain/subdomain constraint): " + originalAbsoluteUrl, STATUS_PROGRESS, null);
                                    }
                                }
                                if (newLinksAddedCount > 0) {
                                    sendUpdateBroadcast("Found " + newLinksAddedCount + " new links in " + relativePath, STATUS_PROGRESS, null);
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
                    endMessage = "Download Error: " + finalErrorMessage;
                } else if (finalStatus.equals(STATUS_CANCELLED)){
                     endMessage = "Download cancelled by user.";
                } else if (finalStatus.equals(STATUS_COMPLETE)) {
                    endMessage = "Download completed successfully.";
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

    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[] { "B", "KB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private String sanitizePathComponent(String component) {
        if (component == null || component.isEmpty()) {
            return "_";
        }
        String sanitized = ILLEGAL_FILENAME_CHARS.matcher(component).replaceAll("_");
        sanitized = sanitized.trim();
        while (sanitized.startsWith(".")) {
            sanitized = sanitized.substring(1).trim();
        }
        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        }
        if (sanitized.isEmpty()) {
            return "_";
        }
        if (sanitized.length() > MAX_PATH_SEGMENT_LENGTH) {
            sanitized = sanitized.substring(0, MAX_PATH_SEGMENT_LENGTH);
        }
        return sanitized;
    }

    private String sanitizeFilename(String filename, String extension) {
        String nameWithoutExtension = filename;
        if (!TextUtils.isEmpty(extension) && filename.toLowerCase().endsWith("." + extension.toLowerCase())) {
            nameWithoutExtension = filename.substring(0, filename.length() - (extension.length() + 1));
        }

        String sanitizedName = ILLEGAL_FILENAME_CHARS.matcher(nameWithoutExtension).replaceAll("_");

        sanitizedName = sanitizedName.trim();
        while (sanitizedName.startsWith(".")) {
            sanitizedName = sanitizedName.substring(1).trim();
        }
        while (sanitizedName.endsWith(".")) {
            sanitizedName = sanitizedName.substring(0, sanitizedName.length() - 1).trim();
        }

        if (sanitizedName.isEmpty()) {
            sanitizedName = UUID.randomUUID().toString();
        }

        if (sanitizedName.length() > MAX_FILENAME_LENGTH) {
            sanitizedName = sanitizedName.substring(0, MAX_FILENAME_LENGTH);
        }
        return TextUtils.isEmpty(extension) ? sanitizedName : sanitizedName + "." + extension;
    }

    private String extractRelativePath(String urlString, Response response) {
        URL url;
        String decodedUrlString = urlString;
        try {
            decodedUrlString = URLDecoder.decode(urlString, "UTF-8");
            url = new URL(decodedUrlString);
        } catch (MalformedURLException | UnsupportedEncodingException e) {
            Log.e(TAG, "extractRelativePath: Malformed or un-decodable URL " + urlString, e);
            try {
                url = new URL(urlString);
            } catch (MalformedURLException e2) {
                 Log.e(TAG, "extractRelativePath: Double Malformed URL " + urlString, e2);
                 return "malformed_urls/" + sanitizeFilename(UUID.randomUUID().toString(), "html");
            }
        }

        String path = url.getPath();
        if (path == null) path = "";

        if (path.isEmpty() || path.equals("/")) {
            path = "/index.html";
        } else if (path.endsWith("/")) {
            path += "index.html";
        }

        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        String[] pathComponents = path.split("/");
        StringBuilder sanitizedRelativePath = new StringBuilder();

        for (int i = 0; i < pathComponents.length; i++) {
            String component = pathComponents[i];
            if (TextUtils.isEmpty(component)) {
                if (i < pathComponents.length -1 && pathComponents.length > 1) continue;
                else component = (i == pathComponents.length - 1 && path.endsWith("index.html")) ? "index.html" : "_empty_segment_";
            }

            if (i < pathComponents.length - 1) {
                sanitizedRelativePath.append(sanitizePathComponent(component));
            } else {
                String filename = component;
                String extension = "";
                int dotIndex = filename.lastIndexOf('.');
                if (dotIndex > 0 && dotIndex < filename.length() - 1) {
                    extension = filename.substring(dotIndex + 1);
                    filename = filename.substring(0, dotIndex);
                }

                if (TextUtils.isEmpty(extension)) {
                    String contentType = response.header("Content-Type");
                    if (contentType != null) {
                        String guessedExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType.split(";")[0].trim());
                        if (guessedExtension != null) {
                            extension = guessedExtension;
                        } else if (contentType.toLowerCase().contains("text/html")) {
                             extension = "html";
                        }
                    }
                }
                if (TextUtils.isEmpty(extension) && (path.endsWith("index.html") || !component.contains("."))) {
                    String contentType = response.header("Content-Type", "");
                    if(contentType.toLowerCase().contains("text/html")) extension = "html";
                }
                filename = sanitizeFilename(filename, extension);
                sanitizedRelativePath.append(filename);
            }

            if (i < pathComponents.length - 1) {
                sanitizedRelativePath.append(File.separatorChar);
            }
        }

        String resultPath = sanitizedRelativePath.toString();
        if (resultPath.isEmpty() || resultPath.equals(File.separator)) {
            return sanitizeFilename("default_page", "html");
        }
        return resultPath;
    }

    private boolean isValidToFollow(String nextUrlString, String initialUrlString, boolean includeSubdomains) {
        if (nextUrlString == null || nextUrlString.trim().isEmpty()) return false;
        try {
            URL nextUrl = new URL(nextUrlString);
            URL initialUrl = new URL(initialUrlString);

            if (!nextUrl.getProtocol().matches("^https?$")) {
                return false;
            }

            String nextHost = nextUrl.getHost();
            String initialHost = initialUrl.getHost();

            if (nextHost == null || initialHost == null) {
                return false;
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
