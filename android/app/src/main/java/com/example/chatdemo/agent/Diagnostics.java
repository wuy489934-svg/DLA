package com.example.chatdemo.agent;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 本地环形诊断日志：只记录事件/异常栈，不记录用户消息正文。
 * 文件位于 files/diagnostics/events.log，超过 256KB 自动截断。
 */
public final class Diagnostics {

    private static final String TAG = "ChatDemoDiag";
    private static final long MAX_BYTES = 256 * 1024L;
    private static final Object LOCK = new Object();
    private static Context appContext;
    private static volatile boolean handlerInstalled;

    private Diagnostics() {}

    public static void install(Context context) {
        appContext = context.getApplicationContext();
        if (handlerInstalled) {
            return;
        }
        handlerInstalled = true;
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            logThrowable("uncaught", error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    public static void event(String tag, String detail) {
        append(format("E") + " " + tag + " " + sanitize(detail));
    }

    public static void logThrowable(String tag, Throwable error) {
        if (error == null) {
            append(format("E") + " " + tag + " null");
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(format("E")).append(' ').append(tag).append(' ')
                .append(error.getClass().getName());
        String message = error.getMessage();
        if (message != null) {
            builder.append(": ").append(sanitize(message));
        }
        builder.append('\n');
        for (StackTraceElement element : error.getStackTrace()) {
            builder.append("  at ").append(element.toString()).append('\n');
        }
        append(builder.toString());
        Log.e(TAG, tag, error);
    }

    public static File file(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "diagnostics");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "events.log");
    }

    /** 导出 zip 到系统 Downloads；只含诊断日志/版本/设备信息，不含聊天正文 */
    public static String export(Context context) throws Exception {
        Context app = context.getApplicationContext();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String name = "chatdemo-diagnostics-" + stamp + ".zip";
        File cache = new File(app.getCacheDir(), name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(cache))) {
            zip.putNextEntry(new ZipEntry("events.log"));
            File log = file(app);
            if (log.exists()) {
                try (InputStream in = new java.io.FileInputStream(log)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        zip.write(buffer, 0, read);
                    }
                }
            }
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("version.txt"));
            String version;
            try {
                android.content.pm.PackageInfo info = app.getPackageManager()
                        .getPackageInfo(app.getPackageName(), 0);
                version = "package=" + app.getPackageName()
                        + "\nversionName=" + info.versionName
                        + "\nversionCode=" + info.getLongVersionCode()
                        + "\nandroid=" + Build.VERSION.RELEASE
                        + "\nsdk=" + Build.VERSION.SDK_INT
                        + "\ndevice=" + Build.MANUFACTURER + " " + Build.MODEL;
            } catch (Exception error) {
                version = "unknown";
            }
            zip.write(version.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/ChatDemo");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = app.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
            );
            if (uri == null) {
                throw new IOException("无法写入 Downloads");
            }
            try (InputStream in = new java.io.FileInputStream(cache);
                 OutputStream out = app.getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("无法打开 Downloads 输出流");
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            app.getContentResolver().update(uri, values, null, null);
            cache.delete();
            return "Download/ChatDemo/" + name;
        }
        File downloads = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "ChatDemo");
        if (!downloads.exists()) {
            downloads.mkdirs();
        }
        File target = new File(downloads, name);
        try (InputStream in = new java.io.FileInputStream(cache);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        cache.delete();
        return target.getAbsolutePath();
    }

    private static String format(String level) {
        String wall = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
        return wall + " +" + SystemClock.elapsedRealtime() + "ms " + level;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace('\n', ' ').replace('\r', ' ');
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private static void append(String line) {
        Context context = appContext;
        if (context == null) {
            Log.i(TAG, line);
            return;
        }
        synchronized (LOCK) {
            try {
                File target = file(context);
                if (target.length() > MAX_BYTES) {
                    try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
                        long keep = MAX_BYTES / 2;
                        byte[] tail = new byte[(int) keep];
                        raf.seek(target.length() - keep);
                        raf.readFully(tail);
                        raf.setLength(0);
                        raf.write(tail);
                    }
                }
                try (FileOutputStream out = new FileOutputStream(target, true)) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException error) {
                Log.e(TAG, "write diagnostics failed", error);
            }
        }
    }
}
