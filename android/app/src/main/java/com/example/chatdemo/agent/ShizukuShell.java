package com.example.chatdemo.agent;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

/**
 * Shizuku 的 shell 后端：无需 root，用 shell(uid 2000) 执行命令。
 *
 * 首次使用需要在 Shizuku 里授权；手机重启后 Shizuku 服务本身也要重新用 adb 启动。
 */
public final class ShizukuShell {

    public static final int PERMISSION_REQUEST_CODE = 4101;
    private static final String TAG = "DlaShizuku";

    /** 应用 Context，用于在非 provider 进程里主动向主进程要 binder */
    private static volatile Context appContext;
    /** 上次要 binder 的时间，避免每次探测都跨进程调用 */
    private static volatile long lastBinderRequest;

    private ShizukuShell() {}

    public static void init(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    /**
     * 补取 binder。
     *
     * Shizuku 只会把 binder 送到声明了 ShizukuProvider 的进程（主进程）。
     * Agent 跑在 :agent，如果不主动要一次，Shizuku.pingBinder() 永远是 false，
     * 于是「Shizuku 已授权却显示未就绪」。这里做两件事：
     * - 每次探测前若没有 binder，就向 provider 取一次（最快 2 秒一次）；
     * - Shizuku 中途启动/重启后，下一次探测能自动接上，不需要重启 App。
     */
    private static void ensureBinder() {
        try {
            if (Shizuku.pingBinder()) {
                return;
            }
            Context context = appContext;
            if (context == null) {
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastBinderRequest < 2000) {
                return;
            }
            lastBinderRequest = now;
            ShizukuProvider.requestBinderForNonProviderProcess(context);
            Log.d(TAG, "requested binder, ping=" + Shizuku.pingBinder());
        } catch (Throwable ignored) {
            // 没有 Shizuku 或版本不支持，按不可用处理
        }
    }

    public static boolean running() {
        try {
            ensureBinder();
            return Shizuku.pingBinder();
        } catch (Throwable error) {
            return false;
        }
    }

    public static boolean granted() {
        try {
            return running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable error) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
        } catch (Throwable ignored) {
            // Shizuku 未运行时忽略，界面会提示去启动
        }
    }

    /** 执行 shell 命令；stdout 与 stderr 合并返回。 */
    public static String exec(String command, int timeoutMs) throws Exception {
        if (command == null || command.trim().isEmpty()) {
            throw new IOException("命令为空");
        }
        if (!running()) {
            throw new IOException("Shizuku 未运行：请先打开 Shizuku，并用数据线执行启动命令");
        }
        if (!granted()) {
            throw new IOException("尚未授予 Shizuku 权限：请到 设置 → Agent → 手机能力 授权");
        }
        Process process = newProcess(new String[]{"sh", "-c", command});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread stdout = new Thread(() -> copy(process.getInputStream(), out), "shizuku-stdout");
        Thread stderr = new Thread(() -> copy(process.getErrorStream(), err), "shizuku-stderr");
        stdout.start();
        stderr.start();
        // ShizukuRemoteProcess 没有实现带超时的 waitFor，用独立线程等，避免 "process hasn't exited"
        Thread waiter = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {
                // 停止等待
            }
        }, "shizuku-wait");
        waiter.start();
        waiter.join(Math.max(timeoutMs, 1000));
        if (waiter.isAlive()) {
            process.destroy();
            throw new IOException("命令超时（" + timeoutMs + "ms）");
        }
        stdout.join(1000);
        stderr.join(1000);
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8)
                + new String(err.toByteArray(), StandardCharsets.UTF_8);
        return text;
    }

    private static final int MAX_OUTPUT = 8 * 1024 * 1024;

    private static void copy(InputStream input, ByteArrayOutputStream output) {
        byte[] buffer = new byte[8192];
        try (InputStream in = input) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (output.size() > MAX_OUTPUT) {
                    break;
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
            // 进程结束时读流会抛异常，忽略
        }
    }

    /**
     * Shizuku 13.x 的 newProcess 是 private 的，只能反射调用；
     * 返回的 ShizukuRemoteProcess 本身就是 Process。
     */
    private static Process newProcess(String[] command) throws Exception {
        java.lang.reflect.Method method = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        Object result = method.invoke(null, command, null, null);
        if (!(result instanceof Process)) {
            throw new IOException("Shizuku 没有返回可用的进程");
        }
        return (Process) result;
    }
}
