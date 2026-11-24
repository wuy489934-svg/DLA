package com.example.chatdemo.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Base64;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * phone_web_shot：把本地 HTML 渲染成截图，给模型自检自己生成的网页。
 *
 * 实现分两段：
 * - 本类跑在 :agent 进程，只负责校验参数、发广播请求、等结果；
 * - 真正的 WebView 渲染在主进程的 NativeShellActivity 里做（{@code renderWebShot}），
 *   因为「没有挂到窗口上的 WebView」不会真正绘制，draw 出来是纯黑。
 *
 * 这样不需要新开界面，也不会把用户当前的应用抢到前台；代价是 DLA 必须在打开状态，
 * 否则返回明确错误（不会偷偷拉起界面）。
 */
public final class WebShot {

    public static final String NAME = "phone_web_shot";

    /** 与 nativeui/WebShotReceiver.kt 里的常量保持一致 */
    public static final String ACTION_REQUEST = "com.example.chatdemo.action.WEB_SHOT";
    public static final String ACTION_RESULT = "com.example.chatdemo.action.WEB_SHOT_RESULT";

    private static final String TAG = "DLAWebShot";
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_FULL_PAGE_DP = 2000;
    private static final int TIMEOUT_SECONDS = 45;

    private WebShot() {}

    public static JSONObject spec() {
        try {
            JSONObject properties = new JSONObject();
            properties.put("path", new JSONObject()
                    .put("type", "string")
                    .put("description", "本地 HTML 文件路径，例如 /sdcard/Download/motion-lab.html"));
            properties.put("width", new JSONObject()
                    .put("type", "number")
                    .put("description", "视口宽度（dp），默认 412"));
            properties.put("height", new JSONObject()
                    .put("type", "number")
                    .put("description", "视口高度（dp），默认 860；full_page=true 时作为最小高度"));
            properties.put("full_page", new JSONObject()
                    .put("type", "boolean")
                    .put("description", "是否按整页内容高度截图，默认 false（只截首屏）"));
            properties.put("wait_ms", new JSONObject()
                    .put("type", "number")
                    .put("description", "页面加载完成后再等多久（毫秒），给动画/字体留时间，默认 900"));

            return new JSONObject()
                    .put("name", NAME)
                    .put("description",
                            "把本地 HTML 文件渲染成截图并返回图片与保存路径，用来检查自己生成的网页"
                                    + "（排版 / CSS / Canvas 动画）。不会打开浏览器、不会切换前台应用；"
                                    + "DLA 必须在前台（用户正开着 DLA），否则会返回明确错误。"
                                    + "截图同时保存到 /sdcard/Download/DLA-web-shots/。")
                    .put("input_schema", new JSONObject()
                            .put("type", "object")
                            .put("properties", properties)
                            .put("required", new JSONArray().put("path")));
        } catch (Exception error) {
            return new JSONObject();
        }
    }

    public static LocalTools.Result execute(Context context, JSONObject arguments) {
        String path = arguments.optString("path", "").trim();
        if (path.isEmpty()) {
            return fail("path 不能为空");
        }
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            return fail("文件不存在：" + path);
        }
        if (file.length() > MAX_FILE_BYTES) {
            return fail("HTML 文件太大（" + file.length() / 1024 + " KB），请把页面拆小或减少内联资源");
        }

        int widthDp = clamp(arguments.optInt("width", 412), 240, 1200);
        int heightDp = clamp(arguments.optInt("height", 860), 320, MAX_FULL_PAGE_DP);
        boolean fullPage = arguments.optBoolean("full_page", false);
        int waitMs = clamp(arguments.optInt("wait_ms", 900), 0, 8000);

        float density = context.getResources().getDisplayMetrics().density;
        if (density <= 0f) {
            density = 1f;
        }
        int widthPx = Math.max(1, Math.round(widthDp * density));
        int heightPx = Math.max(1, Math.round(heightDp * density));
        int maxHeightPx = Math.round(MAX_FULL_PAGE_DP * density);

        String requestId = UUID.randomUUID().toString();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> fileRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || !requestId.equals(intent.getStringExtra("requestId"))) {
                    return;
                }
                if (intent.getBooleanExtra("ok", false)) {
                    fileRef.set(intent.getStringExtra("path"));
                } else {
                    String error = intent.getStringExtra("error");
                    errorRef.set(error == null || error.isEmpty() ? "渲染失败" : error);
                }
                latch.countDown();
            }
        };

        ContextCompat.registerReceiver(
                context,
                receiver,
                new IntentFilter(ACTION_RESULT),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        try {
            Intent request = new Intent(ACTION_REQUEST).setPackage(context.getPackageName());
            request.putExtra("requestId", requestId);
            request.putExtra("path", file.getAbsolutePath());
            request.putExtra("widthPx", widthPx);
            request.putExtra("heightPx", heightPx);
            request.putExtra("fullPage", fullPage);
            request.putExtra("waitMs", waitMs);
            request.putExtra("maxHeightPx", maxHeightPx);
            Log.d(TAG, "request " + file.getAbsolutePath() + " " + widthPx + "x" + heightPx
                    + " full=" + fullPage);
            context.sendBroadcast(request);

            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return fail("渲染超时（" + TIMEOUT_SECONDS + " 秒）。DLA 可能不在前台，"
                        + "或者页面里有不会停的脚本；请先切回 DLA 再试");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return fail("截图被中断");
        } finally {
            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
                // 已注销
            }
        }

        String saved = fileRef.get();
        if (saved == null || saved.isEmpty()) {
            return fail(errorRef.get() == null ? "渲染失败" : errorRef.get());
        }
        File shot = new File(saved);
        byte[] jpeg = readAll(shot);
        if (jpeg.length == 0) {
            return fail("截图文件为空：" + saved);
        }
        Log.d(TAG, "got " + jpeg.length + " bytes -> " + saved);
        String text = "已渲染 " + file.getName() + (fullPage ? "（整页）" : "（首屏）")
                + "\n截图文件：" + saved;
        return new LocalTools.Result(
                text,
                false,
                Base64.encodeToString(jpeg, Base64.NO_WRAP),
                "image/jpeg"
        );
    }

    private static byte[] readAll(File file) {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Throwable error) {
            Log.d(TAG, "read failed " + error);
            return new byte[0];
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static LocalTools.Result fail(String message) {
        return new LocalTools.Result(message, true);
    }
}
