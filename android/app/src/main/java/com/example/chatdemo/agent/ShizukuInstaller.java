package com.example.chatdemo.agent;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.example.chatdemo.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 一键获取/安装 Shizuku。
 * 下载顺序：国内可访问的 GitHub 加速镜像 → GitHub 直连；全部失败才打开官方下载页。
 */
public final class ShizukuInstaller {

    private static final String PACKAGE = "moe.shizuku.privileged.api";
    private static final String API =
            "https://api.github.com/repos/RikkaApps/Shizuku/releases/latest";
    private static final String DOWNLOAD_PAGE = "https://shizuku.rikka.app/download/";
    /** 放在直连之前；镜像失效时会自动尝试下一个 */
    private static final String[] MIRROR_PREFIXES = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
    };
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ShizukuInstaller() {}

    public static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String version(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static void install(Context context) {
        Context app = context.getApplicationContext();
        if (installed(app)) {
            launch(app);
            return;
        }
        toast(app, "正在通过国内镜像获取 Shizuku…");
        EXECUTOR.execute(() -> {
            File apk = null;
            try {
                apk = downloadLatest(app);
            } catch (Throwable error) {
                Diagnostics.logThrowable("shizuku_download", error);
            }
            final File downloaded = apk;
            MAIN.post(() -> {
                if (downloaded == null || !downloaded.isFile()) {
                    toast(app, "镜像和直连均不可用，打开 Shizuku 官方下载页");
                    openDownloadPage(app);
                    return;
                }
                try {
                    toast(app, "下载完成，正在打开安装器");
                    installApk(app, downloaded);
                } catch (Throwable error) {
                    Diagnostics.logThrowable("shizuku_install", error);
                    toast(app, "无法调起安装器，请从浏览器安装");
                    openDownloadPage(app);
                }
            });
        });
    }

    private static void launch(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (intent == null) {
            toast(context, "Shizuku 已安装，但无法打开");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static File downloadLatest(Context context) throws Exception {
        Exception last = null;
        // 1. 先通过镜像取 release 元数据；2. 再直连 GitHub API
        for (String apiUrl : apiUrls()) {
            try {
                Asset asset = readAsset(apiUrl);
                return downloadAsset(context, asset);
            } catch (Throwable error) {
                last = error instanceof Exception ? (Exception) error : new Exception(error);
            }
        }
        throw last == null ? new IllegalStateException("无法获取 Shizuku 发布信息") : last;
    }

    private static String[] apiUrls() {
        String[] urls = new String[MIRROR_PREFIXES.length + 1];
        for (int i = 0; i < MIRROR_PREFIXES.length; i++) {
            urls[i] = MIRROR_PREFIXES[i] + API;
        }
        urls[urls.length - 1] = API;
        return urls;
    }

    private static Asset readAsset(String apiUrl) throws Exception {
        JSONObject root = new JSONObject(readUrl(apiUrl, 15_000, 30_000));
        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                if (name.endsWith(".apk") && url.startsWith("https://")) {
                    return new Asset(url, asset.optString("digest", ""));
                }
            }
        }
        throw new IllegalStateException("发布信息里没有 APK");
    }

    private static File downloadAsset(Context context, Asset asset) throws Exception {
        File dir = new File(context.getCacheDir(), "shizuku");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建下载目录");
        }
        File target = new File(dir, "Shizuku.apk");
        Exception last = null;
        // 国内镜像优先，直连最后
        Set<String> candidates = new LinkedHashSet<>();
        for (String prefix : MIRROR_PREFIXES) {
            candidates.add(prefix + asset.url);
        }
        candidates.add(asset.url);
        for (String candidate : candidates) {
            try {
                File temp = new File(dir, "Shizuku.apk.part");
                downloadFile(candidate, temp);
                if (temp.length() < 100_000) {
                    temp.delete();
                    throw new IllegalStateException("下载内容过小");
                }
                if (!hasZipHeader(temp)) {
                    temp.delete();
                    throw new IllegalStateException("下载内容不是 APK");
                }
                if (!asset.digest.isEmpty() && !matchesDigest(temp, asset.digest)) {
                    temp.delete();
                    throw new IllegalStateException("APK 校验失败");
                }
                if (!temp.renameTo(target)) {
                    temp.delete();
                    throw new IllegalStateException("无法保存下载文件");
                }
                Diagnostics.event("shizuku_download", "source=" + sourceName(candidate));
                return target;
            } catch (Throwable error) {
                last = error instanceof Exception ? (Exception) error : new Exception(error);
            }
        }
        throw last == null ? new IllegalStateException("Shizuku 下载失败") : last;
    }

    private static String sourceName(String url) {
        for (String prefix : MIRROR_PREFIXES) {
            if (url.startsWith(prefix)) {
                return prefix;
            }
        }
        return "github";
    }

    private static void downloadFile(String url, File target) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "DLA-Android");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readUrl(String url, int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "DLA-Android");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean hasZipHeader(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            return input.read() == 'P' && input.read() == 'K';
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean matchesDigest(File file, String digest) {
        String expected = digest.startsWith("sha256:")
                ? digest.substring("sha256:".length())
                : digest;
        if (expected.isEmpty()) {
            return true;
        }
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                sha.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().equalsIgnoreCase(expected);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void installApk(Context context, File apk) {
        Uri uri = FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + ".fileprovider",
                apk
        );
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    private static void openDownloadPage(Context context) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_PAGE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable error) {
            toast(context, "无法打开浏览器");
        }
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    private static final class Asset {
        private final String url;
        private final String digest;

        private Asset(String url, String digest) {
            this.url = url;
            this.digest = digest == null ? "" : digest;
        }
    }
}
