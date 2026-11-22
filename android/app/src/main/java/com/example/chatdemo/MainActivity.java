package com.example.chatdemo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import com.example.chatdemo.agent.AgentRuntime;
import com.example.chatdemo.agent.LocalTools;
import com.example.chatdemo.agent.McpClient;
import com.example.chatdemo.agent.McpStore;
import com.example.chatdemo.agent.ShizukuShell;
import com.example.chatdemo.agent.TermuxBridge;

import rikka.shizuku.Shizuku;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把 DLA Web UI 的静态产物装进 WebView。
 *
 * 关键点：
 * 1. 页面通过 WebViewAssetLoader 从 https://appassets.androidplatform.net 加载，属于安全上下文，
 *    getUserMedia / AudioContext / clipboard 与 https 页面一致；localStorage、IndexedDB 按该源持久化。
 * 2. API 请求仍然直连 https://api.deepseek.com，该服务会反射任意 Origin，因此不需要原生转发。
 * 3. 补齐 WebView 缺失的能力：文件选择、麦克风授权、blob 下载、target=_blank 打开图片/PDF、外部链接。
 */
public class MainActivity extends Activity {

    private static final String TAG = "ChatDemo";
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + APP_HOST + "/assets/public/index.html";
    private static final int REQUEST_FILE_CHOOSER = 1001;
    private static final int REQUEST_MICROPHONE = 1002;
    private static final int REQUEST_IMAGE_CAPTURE = 1003;
    private static final int REQUEST_NOTIFICATIONS = 1004;
    private static final String FILE_PROVIDER_SUFFIX = ".fileprovider";
    /** 网页 accept 里出现这些扩展名时，额外提供「拍照 / 从相册选择」入口 */
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "jpe", "jfif", "webp", "gif", "bmp", "svg", "ico",
            "heic", "heif", "avif", "apng", "tif", "tiff"));

    private FrameLayout root;
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private ValueCallback<Uri[]> pendingFileCallback;
    private PermissionRequest pendingMicRequest;
    private String bridgeScript;
    private Uri cameraOutputUri;
    private File cameraOutputFile;
    private int background;

    /** Shizuku 授权结果回调 */
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> runOnUiThread(() -> Toast.makeText(
                    MainActivity.this,
                    grantResult == PackageManager.PERMISSION_GRANTED
                            ? getString(R.string.shizuku_granted)
                            : getString(R.string.shizuku_denied),
                    Toast.LENGTH_SHORT
            ).show());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        boolean dark = isSystemDark();
        int background = dark ? 0xFF151517 : 0xFFFFFFFF;
        this.background = background;
        com.example.chatdemo.agent.Diagnostics.install(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(background);
        // 页面不需要知道安全区：由系统栏和输入法的 inset 直接收缩 WebView
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return windowInsets;
        });

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = createWebView();
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        syncSystemBars(dark);
        AgentRuntime.get().setListener(this::postAgentEvent);
        // 如果是被系统/开机中断的任务，回到 App 时自动恢复
        AgentRuntime.get().resumeIfNeeded(this);
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
            // Shizuku 未安装或未运行时忽略
        }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(START_URL);
        }
    }

    private WebView createWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(background);
        configureWebView(view);
        return view;
    }

    /** 渲染进程崩溃后重建实例；历史在 WebView 的 IndexedDB 中，不随 renderer 进程消失 */
    private void recreateWebView() {
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
        webView = createWebView();
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.loadUrl(START_URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView view) {
        // 空闲时允许系统回收 renderer；Agent 运行期间会切换成保留
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setTextZoom(100);
        // 聊天正文允许双指缩放；不显示旧式 +/- 缩放按钮
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // window.open / target=_blank 走 onCreateWindow，由原生决定怎么打开
        settings.setSupportMultipleWindows(true);

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        view.addJavascriptInterface(new HostBridge(), "AndroidHost");
        view.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                openExternal(Uri.parse(url)));

        view.setWebViewClient(new WebViewClient() {
            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (APP_HOST.equals(uri.getHost())) {
                    return false;
                }
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                injectHostBridge(v);
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                com.example.chatdemo.agent.Diagnostics.event(
                        "renderer_gone",
                        "didCrash=" + detail.didCrash()
                );
                AgentRuntime.get().onRendererGone();
                runOnUiThread(() -> {
                    if (v == webView) {
                        recreateWebView();
                    } else {
                        v.destroy();
                    }
                });
                return true;
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;
                // 网页 accept 里包含图片时，多给一个「拍照 / 相册」入口
                if (acceptsImages(params.getAcceptTypes())) {
                    showMediaSourceDialog(params);
                    return true;
                }
                return openFilePicker(params.createIntent());
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> grantOrRequestAudio(request));
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                // 新窗口只用来截获 URL，真正的打开动作交给系统浏览器或图片查看器
                WebView transport = new WebView(MainActivity.this);
                transport.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView popup, WebResourceRequest request) {
                        handleNewWindowUrl(request.getUrl().toString());
                        return true;
                    }

                    @Override
                    public boolean onRenderProcessGone(WebView popup, RenderProcessGoneDetail detail) {
                        popup.destroy();
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(transport);
                resultMsg.sendToTarget();
                webView.postDelayed(transport::destroy, 1000);
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, message.message() + " @" + message.lineNumber());
                    return true;
                }
                return super.onConsoleMessage(message);
            }
        });
    }

    private void injectHostBridge(WebView view) {
        if (bridgeScript == null) {
            bridgeScript = readRawText(R.raw.host_bridge);
        }
        if (!bridgeScript.isEmpty()) {
            view.evaluateJavascript(bridgeScript, null);
        }
    }

    private void handleNewWindowUrl(String url) {
        if (url == null) {
            return;
        }
        if (url.startsWith("blob:")) {
            // blob 只在页面源内有效，让页面自己 fetch 后把字节交给原生
            webView.evaluateJavascript("window.__hostOpenBlob(" + JSONObject.quote(url) + ")", null);
            return;
        }
        openExternal(Uri.parse(url));
    }

    private void openExternal(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException error) {
            toast(getString(R.string.no_browser));
        }
    }

    private boolean acceptsImages(String[] acceptTypes) {
        if (acceptTypes == null) {
            return false;
        }
        for (String accept : acceptTypes) {
            if (accept == null) {
                continue;
            }
            for (String token : accept.split(",")) {
                String value = token.trim().toLowerCase(Locale.ROOT);
                if (value.isEmpty()) {
                    continue;
                }
                if (value.startsWith("image/")) {
                    return true;
                }
                String extension = value.startsWith(".") ? value.substring(1) : value;
                if (IMAGE_EXTENSIONS.contains(extension)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void showMediaSourceDialog(final WebChromeClient.FileChooserParams params) {
        String[] options = {
                getString(R.string.action_camera),
                getString(R.string.action_gallery),
                getString(R.string.action_files),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_attachment)
                .setItems(options, (dialog, which) -> {
                    if (pendingFileCallback == null) {
                        return;
                    }
                    if (which == 0) {
                        takePhoto();
                    } else if (which == 1) {
                        openGallery(params);
                    } else {
                        openFilePicker(params.createIntent());
                    }
                })
                .setOnCancelListener(dialog -> finishFileChooser(null))
                .show();
    }

    private void openGallery(WebChromeClient.FileChooserParams params) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(intent, REQUEST_FILE_CHOOSER);
        } catch (ActivityNotFoundException error) {
            finishFileChooser(null);
            toast(getString(R.string.open_failed));
        }
    }

    private boolean openFilePicker(Intent intent) {
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_FILE_CHOOSER);
            return true;
        } catch (ActivityNotFoundException error) {
            finishFileChooser(null);
            toast(getString(R.string.open_failed));
            return false;
        }
    }

    private void takePhoto() {
        File dir = new File(getCacheDir(), "attachments");
        if (!dir.exists() && !dir.mkdirs()) {
            finishFileChooser(null);
            toast(getString(R.string.camera_unavailable));
            return;
        }
        cameraOutputFile = new File(dir, "camera-" + System.currentTimeMillis() + ".jpg");
        try {
            cameraOutputUri = FileProvider.getUriForFile(this,
                    getPackageName() + FILE_PROVIDER_SUFFIX, cameraOutputFile);
        } catch (IllegalArgumentException error) {
            cameraOutputFile = null;
            finishFileChooser(null);
            toast(getString(R.string.camera_unavailable));
            return;
        }
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_IMAGE_CAPTURE);
        } catch (ActivityNotFoundException error) {
            deleteCameraOutput();
            finishFileChooser(null);
            toast(getString(R.string.camera_unavailable));
        }
    }

    private void deleteCameraOutput() {
        if (cameraOutputFile != null) {
            //noinspection ResultOfMethodCallIgnored
            cameraOutputFile.delete();
        }
        cameraOutputFile = null;
        cameraOutputUri = null;
    }

    private void finishFileChooser(Uri[] result) {
        ValueCallback<Uri[]> callback = pendingFileCallback;
        pendingFileCallback = null;
        if (callback != null) {
            callback.onReceiveValue(result);
        }
    }

    private void grantOrRequestAudio(PermissionRequest request) {
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{resource});
                } else {
                    pendingMicRequest = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                            REQUEST_MICROPHONE);
                }
                return;
            }
        }
        request.deny();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MICROPHONE || pendingMicRequest == null) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingMicRequest.grant(pendingMicRequest.getResources());
        } else {
            pendingMicRequest.deny();
            toast(getString(R.string.microphone_denied));
        }
        pendingMicRequest = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_IMAGE_CAPTURE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && cameraOutputFile != null
                    && cameraOutputFile.length() > 0) {
                result = new Uri[]{cameraOutputUri};
                // 文件要留给网页读取，不能删；只清掉本地的引用
                cameraOutputFile = null;
                cameraOutputUri = null;
            } else {
                deleteCameraOutput();
            }
            finishFileChooser(result);
            return;
        }
        if (requestCode != REQUEST_FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (pendingFileCallback == null) {
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    result[i] = clip.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        finishFileChooser(result);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        AgentRuntime.get().setListener(null);
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
            // Shizuku 未安装或未运行时忽略
        }
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = null;
        }
        if (webView != null) {
            root.removeView(webView);
            webView.removeJavascriptInterface("AndroidHost");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private boolean isSystemDark() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 首次启动 Agent 时请求忽略电池优化，避免厂商省电策略清掉前台服务 */
    private void requestBatteryOptimizationOnce() {
        try {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            if (manager == null || manager.isIgnoringBatteryOptimizations(getPackageName())) {
                return;
            }
            android.content.SharedPreferences prefs =
                    getSharedPreferences("agent-keepalive", MODE_PRIVATE);
            if (prefs.getBoolean("battery-asked", false)) {
                return;
            }
            prefs.edit().putBoolean("battery-asked", true).apply();
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable ignored) {
            // 部分 ROM 不支持该入口；不影响任务继续
        }
    }

    private void syncSystemBars(boolean dark) {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);
    }

    private void postAgentEvent(String requestId, JSONObject event) {
        runOnUiThread(() -> {
            if (webView == null) {
                return;
            }
            String type = event.optString("type");
            if ("done".equals(type) || "error".equals(type) || "stopped".equals(type)) {
                setRendererKeepAlive(false);
            }
            webView.evaluateJavascript(
                    "window.__agentEvent(" + JSONObject.quote(requestId) + ","
                            + JSONObject.quote(event.toString()) + ")",
                    null
            );
        });
    }

    /** Agent 运行期间保留 renderer，结束后恢复为可回收 */
    private void setRendererKeepAlive(boolean keep) {
        if (webView == null) {
            return;
        }
        webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_BOUND,
                !keep
        );
    }

    private void postMcpResult(String requestId, JSONObject result) {
        runOnUiThread(() -> {
            if (webView == null) {
                return;
            }
            webView.evaluateJavascript(
                    "window.__mcpResult(" + JSONObject.quote(requestId) + ","
                            + JSONObject.quote(result.toString()) + ")",
                    null
            );
        });
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private String safeFileName(String name, String fallback) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static String extensionFor(String mime) {
        if (mime == null) {
            return "";
        }
        switch (mime) {
            case "application/pdf":
                return ".pdf";
            case "image/png":
                return ".png";
            case "image/jpeg":
                return ".jpg";
            case "image/webp":
                return ".webp";
            case "image/gif":
                return ".gif";
            case "image/svg+xml":
                return ".svg";
            case "text/plain":
                return ".txt";
            default:
                return "";
        }
    }

    @Nullable
    private File writeCacheFile(String name, String mime, byte[] data) {
        File dir = new File(getCacheDir(), "attachments");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String fileName = safeFileName(name, "attachment");
        if (!fileName.contains(".")) {
            fileName += extensionFor(mime);
        }
        File file = new File(dir, fileName);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
            return file;
        } catch (IOException error) {
            Log.w(TAG, "write cache file failed", error);
            return null;
        }
    }

    @Nullable
    private Uri saveToDownloads(String name, String mime, byte[] data) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, safeFileName(name, "download"));
        values.put(MediaStore.Downloads.MIME_TYPE,
                mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return null;
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("no output stream");
            }
            out.write(data);
        } catch (IOException error) {
            Log.w(TAG, "save download failed", error);
            getContentResolver().delete(uri, null, null);
            return null;
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        getContentResolver().update(uri, values, null, null);
        return uri;
    }

    private String readRawText(int resourceId) {
        try (InputStream in = getResources().openRawResource(resourceId)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            Log.w(TAG, "read raw resource failed", error);
            return "";
        }
    }

    private static String readHttpBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private final AtomicBoolean termuxSetupRunning = new AtomicBoolean(false);
    private volatile String lastTermuxResult = "";

    /** 暴露给网页的桥；只在主页面注入，且只开放这几个方法。 */
    private final class HostBridge {

        @JavascriptInterface
        public void toast(String message) {
            MainActivity.this.toast(message);
        }

        /** 导出历史等 blob: 文件下载到系统「下载」目录 */
        @JavascriptInterface
        public void saveDownload(final String name, final String mime, final String base64) {
            runOnUiThread(() -> {
                try {
                    byte[] data = Base64.decode(base64, Base64.DEFAULT);
                    String fileName = safeFileName(name, "download");
                    boolean saved = saveToDownloads(fileName, mime, data) != null;
                    toast(saved ? getString(R.string.saved_to_downloads, fileName)
                            : getString(R.string.save_failed));
                } catch (IllegalArgumentException error) {
                    toast(getString(R.string.save_failed));
                }
            });
        }

        /** 图片用内置查看器打开，PDF 等交给系统应用 */
        @JavascriptInterface
        public void viewBlob(final String name, final String mime, final String base64) {
            runOnUiThread(() -> {
                try {
                    byte[] data = Base64.decode(base64, Base64.DEFAULT);
                    String type = mime == null || mime.isEmpty()
                            ? "application/octet-stream" : mime;
                    File file = writeCacheFile(name, type, data);
                    if (file == null) {
                        toast(getString(R.string.open_failed));
                        return;
                    }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this,
                            getPackageName() + FILE_PROVIDER_SUFFIX, file);
                    if (type.startsWith("image/")) {
                        startActivity(new Intent(MainActivity.this, ImageViewerActivity.class)
                                .setData(uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                        return;
                    }
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, type)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (ActivityNotFoundException error) {
                        String fileName = safeFileName(name, "document");
                        boolean saved = saveToDownloads(fileName, type, data) != null;
                        toast(saved ? getString(R.string.saved_to_downloads, fileName)
                                : getString(R.string.open_failed));
                    }
                } catch (IllegalArgumentException error) {
                    toast(getString(R.string.open_failed));
                }
            });
        }

        /** 外部链接交给系统浏览器 */
        @JavascriptInterface
        public void openUrl(String url) {
            try {
                openExternal(Uri.parse(url));
            } catch (IllegalArgumentException ignored) {
                // 非法 URL 直接忽略
            }
        }

        /** 应用内切换深浅色时同步系统栏图标和窗口底色 */
        @JavascriptInterface
        public void setDarkMode(final boolean dark) {
            runOnUiThread(() -> {
                int background = dark ? 0xFF151517 : 0xFFFFFFFF;
                root.setBackgroundColor(background);
                webView.setBackgroundColor(background);
                syncSystemBars(dark);
            });
        }

        /** MCP 服务器列表（JSON 数组字符串） */
        @JavascriptInterface
        public String mcpServers() {
            return McpStore.load(MainActivity.this).toString();
        }

        @JavascriptInterface
        public void mcpSaveServer(String json) {
            McpStore.upsert(MainActivity.this, json);
        }

        @JavascriptInterface
        public void mcpDeleteServer(String id) {
            McpStore.remove(MainActivity.this, id);
        }

        /** Termux 状态：只返回安装信息，不执行命令 */
        @JavascriptInterface
        public String termuxStatus() {
            return TermuxBridge.status(MainActivity.this).toString();
        }

        @JavascriptInterface
        public String termuxLastResult() {
            return lastTermuxResult;
        }

        @JavascriptInterface
        public void termuxOpen() {
            try {
                TermuxBridge.open(MainActivity.this);
            } catch (Exception error) {
                toast("无法打开 Termux：" + error.getMessage());
            }
        }

        @JavascriptInterface
        public void termuxOpenSettings() {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception error) {
                toast("无法打开应用设置");
            }
        }

        /** 一键安装 Termux 环境；结果通过 Toast 提示，不在 UI 线程执行。 */
        @JavascriptInterface
        public void termuxSetup() {
            if (!termuxSetupRunning.compareAndSet(false, true)) {
                toast("Termux 环境安装正在进行，请稍候");
                return;
            }
            new Thread(() -> {
                try {
                    String script = TermuxBridge.setupScript(MainActivity.this);
                    if (script.isEmpty()) {
                        lastTermuxResult = "Termux 安装脚本缺失";
                        toast(lastTermuxResult);
                        return;
                    }
                    // MIUI 会拦截后台跨应用启动 RunCommandService；先把 Termux 拉到前台。
                    runOnUiThread(() -> TermuxBridge.open(MainActivity.this));
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    // 先做 8 秒探针，避免未开启 allow-external-apps 时白等 10 分钟。
                    TermuxBridge.Result probe = TermuxBridge.run(
                            MainActivity.this, "echo DLA_TERMUX_READY", 8000L);
                    if (probe.isError() || !probe.text().contains("DLA_TERMUX_READY")) {
                        lastTermuxResult = probe.text()
                                + "\n\n请先在 Termux 执行：\n"
                                + "echo \"allow-external-apps=true\" >> ~/.termux/termux.properties "
                                + "&& termux-reload-settings";
                        toast("Termux 尚未允许外部命令，请先复制并执行设置 → Termux 授权命令");
                        return;
                    }
                    TermuxBridge.Result result = TermuxBridge.run(
                            MainActivity.this, script, 600_000L);
                    String text = result.text();
                    lastTermuxResult = text;
                    if (!result.isError() && text.contains("DLA_TERMUX_SETUP_DONE")) {
                        toast("Termux 通用环境安装完成，可以直接使用 Agent 的 termux 工具");
                    } else {
                        String shortText = text.length() > 300 ? text.substring(0, 300) + "…" : text;
                        toast("Termux 安装未完成：" + shortText);
                    }
                } finally {
                    termuxSetupRunning.set(false);
                }
            }, "dla-termux-setup").start();
        }

        /** 测试 Termux 外部命令链路。 */
        @JavascriptInterface
        public void termuxTest() {
            new Thread(() -> {
                runOnUiThread(() -> TermuxBridge.open(MainActivity.this));
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                TermuxBridge.Result result = TermuxBridge.run(
                        MainActivity.this,
                        "echo DLA_TERMUX_OK; node -v 2>/dev/null; npm -v 2>/dev/null; "
                                + "python --version 2>&1; rg --version 2>/dev/null | head -1",
                        12_000L);
                String text = result.text();
                lastTermuxResult = text;
                String shortText = text.length() > 300 ? text.substring(0, 300) + "…" : text;
                toast(result.isError() ? "Termux 连接失败：" + shortText
                        : "Termux 连接成功：" + shortText);
            }, "dla-termux-test").start();
        }

        /** 测试连接并拉工具列表；结果通过 __mcpResult 异步回传 */
        @JavascriptInterface
        public void mcpTestServer(final String requestId, final String json) {
            new Thread(() -> {
                JSONObject result = new JSONObject();
                try {
                    JSONObject server = new JSONObject(json);
                    McpClient client = new McpClient(
                            server.optString("url"),
                            server.optString("token"),
                            15000
                    );
                    client.initialize();
                    JSONArray tools = client.listTools();
                    result.put("ok", true);
                    result.put("tools", tools.length());
                } catch (Exception error) {
                    try {
                        result.put("ok", false);
                        result.put("message", error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage());
                    } catch (Exception ignored) {
                        // 不会发生
                    }
                }
                postMcpResult(requestId, result);
            }, "mcp-test").start();
        }

        /** 原生 HTTP：Key 校验/模型列表/连接测试绕开 WebView CORS。 */
        @JavascriptInterface
        public String httpRequest(String method, String url, String headersJson, String requestBody) {
            JSONObject result = new JSONObject();
            HttpURLConnection connection = null;
            try {
                URL target = new URL(url);
                String protocol = target.getProtocol();
                if (!"https".equals(protocol) && !"http".equals(protocol)) {
                    throw new IOException("unsupported protocol");
                }
                connection = (HttpURLConnection) target.openConnection();
                connection.setRequestMethod(method == null || method.isEmpty() ? "GET" : method);
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("Accept", "application/json");
                JSONObject headers = headersJson == null || headersJson.isEmpty()
                        ? new JSONObject() : new JSONObject(headersJson);
                java.util.Iterator<String> names = headers.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    String lower = name.toLowerCase(Locale.US);
                    if ("host".equals(lower) || "content-length".equals(lower)
                            || "connection".equals(lower)) {
                        continue;
                    }
                    connection.setRequestProperty(name, headers.optString(name));
                }
                if (requestBody != null && !requestBody.isEmpty()) {
                    connection.setDoOutput(true);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(requestBody.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int status = connection.getResponseCode();
                InputStream input = status >= 400
                        ? connection.getErrorStream() : connection.getInputStream();
                result.put("ok", status >= 200 && status < 300);
                result.put("status", status);
                result.put("body", readHttpBody(input));
            } catch (Exception error) {
                try {
                    result.put("ok", false);
                    result.put("status", 0);
                    result.put("error", error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage());
                } catch (Exception ignored) {
                    // 不会发生
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            return result.toString();
        }

        /** 启动原生 Agent 循环；事件通过 __agentEvent 流式回传 */
        @JavascriptInterface
        public void agentStart(final String requestId, final String payload) {
            runOnUiThread(() -> {
                requestBatteryOptimizationOnce();
                setRendererKeepAlive(true);
                if (Build.VERSION.SDK_INT >= 33
                        && ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            REQUEST_NOTIFICATIONS);
                }
            });
            AgentRuntime.get().start(getApplicationContext(), requestId, payload);
        }

        @JavascriptInterface
        public void agentStop(String requestId) {
            AgentRuntime.get().stop(requestId);
        }

        @JavascriptInterface
        public void agentApprove(String requestId, String toolId, boolean allowed) {
            AgentRuntime.get().approve(requestId, toolId, allowed);
        }

        /** 页面恢复时按游标回放事件 */
        @JavascriptInterface
        public String agentAttach(String requestId, int cursor) {
            return AgentRuntime.get().attach(requestId, cursor);
        }

        private final Map<String, FileOutputStream> attachmentOutputs = new HashMap<>();
        private final Map<String, File> attachmentFiles = new HashMap<>();

        /** Agent 本地附件：开始写入 app 专属目录（APK/ZIP 等不上传 Files API） */
        @JavascriptInterface
        public String attachmentBegin(String name, String mime) {
            try {
                File dir = new File(getExternalFilesDir(null), "agent-files");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("无法创建附件目录");
                }
                String safe = name == null ? "file" : name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
                if (safe.length() > 80) {
                    safe = safe.substring(safe.length() - 80);
                }
                File file = new File(dir, System.nanoTime() + "-" + safe);
                FileOutputStream out = new FileOutputStream(file);
                String id = Long.toHexString(System.nanoTime());
                synchronized (attachmentOutputs) {
                    attachmentOutputs.put(id, out);
                    attachmentFiles.put(id, file);
                }
                return new JSONObject().put("ok", true).put("id", id).toString();
            } catch (Exception error) {
                return errorJson(error);
            }
        }

        @JavascriptInterface
        public boolean attachmentChunk(String id, String base64) {
            FileOutputStream out;
            synchronized (attachmentOutputs) {
                out = attachmentOutputs.get(id);
            }
            if (out == null) {
                return false;
            }
            try {
                out.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception error) {
                return false;
            }
        }

        @JavascriptInterface
        public String attachmentEnd(String id) {
            FileOutputStream out;
            File file;
            synchronized (attachmentOutputs) {
                out = attachmentOutputs.remove(id);
                file = attachmentFiles.remove(id);
            }
            if (out == null || file == null) {
                return errorJson(new IOException("附件写入任务不存在"));
            }
            try {
                out.flush();
                out.close();
                return new JSONObject()
                        .put("ok", true)
                        .put("path", file.getAbsolutePath())
                        .put("size", file.length())
                        .toString();
            } catch (Exception error) {
                return errorJson(error);
            }
        }

        private static String errorJson(Exception error) {
            try {
                return new JSONObject()
                        .put("ok", false)
                        .put("error", error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage())
                        .toString();
            } catch (Exception ignored) {
                return "{\"ok\":false}";
            }
        }

        @JavascriptInterface
        public String accessibilityStatus() {
            return com.example.chatdemo.agent.ChatDemoAccessibilityService.ready()
                    ? "ready" : "off";
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable error) {
                    toast("无法打开无障碍设置");
                }
            });
        }

        @JavascriptInterface
        public String exportDiagnostics() {
            try {
                String path = com.example.chatdemo.agent.Diagnostics.export(MainActivity.this);
                toast("诊断日志已导出：" + path);
                return path;
            } catch (Throwable error) {
                toast("导出失败：" + error.getMessage());
                return "";
            }
        }

        private File hostSessionsDir() {
            File dir = new File(getFilesDir(), "host-sessions");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        }

        /** 宿主侧会话镜像；只用于崩溃/存储异常恢复，不替代 IndexedDB */
        @JavascriptInterface
        public void saveSessionSnapshot(String json) {
            try {
                JSONObject object = new JSONObject(json);
                String id = object.optString("id");
                if (id.isEmpty()) {
                    return;
                }
                File target = new File(hostSessionsDir(), id + ".json");
                File temp = new File(hostSessionsDir(), id + ".tmp");
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                if (!temp.renameTo(target)) {
                    temp.delete();
                }
            } catch (Throwable ignored) {
                // 镜像失败不影响 IndexedDB 主存储
            }
        }

        @JavascriptInterface
        public String loadSessionSnapshots() {
            JSONArray array = new JSONArray();
            File[] files = hostSessionsDir().listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File file : files) {
                    try {
                        byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
                        array.put(new JSONObject(new String(data, StandardCharsets.UTF_8)));
                    } catch (Throwable ignored) {
                        // 单个损坏文件跳过
                    }
                }
            }
            return array.toString();
        }

        /** Shizuku 运行/授权状态 */
        @JavascriptInterface
        public String shizukuStatus() {
            JSONObject status = new JSONObject();
            try {
                status.put("running", ShizukuShell.running());
                status.put("granted", ShizukuShell.granted());
                status.put("installed", com.example.chatdemo.agent.ShizukuInstaller.installed(MainActivity.this));
                status.put("version", com.example.chatdemo.agent.ShizukuInstaller.version(MainActivity.this));
            } catch (Throwable error) {
                try {
                    status.put("running", false);
                    status.put("granted", false);
                    status.put("installed", false);
                    status.put("version", "");
                } catch (Exception ignored) {
                    // 不会发生
                }
            }
            return status.toString();
        }

        /** 一键下载并调起 Shizuku 官方安装包 */
        @JavascriptInterface
        public void shizukuInstall() {
            com.example.chatdemo.agent.ShizukuInstaller.install(MainActivity.this);
        }

        @JavascriptInterface
        public void shizukuRequest() {
            runOnUiThread(ShizukuShell::requestPermission);
        }

        /** 打开 Shizuku 应用，方便用户查看启动状态 */
        @JavascriptInterface
        public void shizukuOpen() {
            runOnUiThread(() -> {
                Intent intent = getPackageManager()
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (intent != null) {
                    startActivity(intent);
                } else {
                    toast(getString(R.string.shizuku_not_installed));
                }
            });
        }

        /** 同步执行一条 shell 命令，设置页自检用 */
        @JavascriptInterface
        public String shellTest(String command) {
            try {
                if (!ShizukuShell.running()) {
                    return "❌ Shell 测试失败：Shizuku 未运行。\n请先在 Shizuku 里启动服务，再回到这里测试。";
                }
                if (!ShizukuShell.granted()) {
                    return "❌ Shell 测试失败：尚未授权。\n请点上方「授权」，在 Shizuku 弹窗里允许 DLA。";
                }
                String output = ShizukuShell.exec(command, 15000);
                output = output == null ? "" : output.trim();
                if (output.isEmpty()) {
                    return "✅ Shell 测试成功：命令已执行（无输出）。";
                }
                return "✅ Shell 测试成功：\n" + output;
            } catch (Exception error) {
                String message = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                return "❌ Shell 测试失败：" + message
                        + "\n请检查 Shizuku 是否正在运行、是否已授权。";
            }
        }

        /** 调试用：直接执行一个手机工具，返回文本结果 */
        @JavascriptInterface
        public String debugTool(String name, String argsJson) {
            try {
                JSONObject arguments = argsJson == null || argsJson.isEmpty()
                        ? new JSONObject()
                        : new JSONObject(argsJson);
                LocalTools.Result result = LocalTools.execute(MainActivity.this, name, arguments);
                return result.text;
            } catch (Exception error) {
                return "错误：" + (error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage());
            }
        }
    }
}
