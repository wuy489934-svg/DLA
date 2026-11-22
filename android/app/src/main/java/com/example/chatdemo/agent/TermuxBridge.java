package com.example.chatdemo.agent;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Termux RUN_COMMAND 桥。
 *
 * 只负责执行一条命令并等待结果；不做工具列表注入。
 * Termux 需要：
 * 1. DLA 被授予 com.termux.permission.RUN_COMMAND；
 * 2. ~/.termux/termux.properties 里 allow-external-apps=true。
 */
public final class TermuxBridge {

    public static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";
    private static final String EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    private static final String EXTRA_RESULT_BUNDLE = "result";
    private static final String EXTRA_STDOUT = "stdout";
    private static final String EXTRA_STDERR = "stderr";
    private static final String EXTRA_EXIT_CODE = "exitCode";
    private static final String EXTRA_ERR = "err";
    private static final String EXTRA_ERRMSG = "errmsg";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String HOME = "/data/data/com.termux/files/home";

    private static final ConcurrentHashMap<Integer, CompletableFuture<Result>> PENDING =
            new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1000);

    private TermuxBridge() {}

    public static final class Result {
        public final String stdout;
        public final String stderr;
        public final int exitCode;
        public final String error;

        Result(String stdout, String stderr, int exitCode, String error) {
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.exitCode = exitCode;
            this.error = error == null ? "" : error;
        }

        public boolean isError() {
            return !error.isEmpty() || exitCode != 0;
        }

        public String text() {
            StringBuilder builder = new StringBuilder();
            if (!stdout.isEmpty()) {
                builder.append(stdout);
            }
            if (!stderr.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append("[stderr]\n").append(stderr);
            }
            if (!error.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append("[termux] ").append(error);
            }
            if (builder.length() == 0) {
                builder.append("(无输出，exit=").append(exitCode).append(')');
            }
            return builder.toString().trim();
        }
    }

    public static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    public static JSONObject status(Context context) {
        JSONObject status = new JSONObject();
        try {
            boolean installed = isInstalled(context);
            status.put("installed", installed);
            if (installed) {
                status.put("version", context.getPackageManager()
                        .getPackageInfo(TERMUX_PACKAGE, 0).versionName);
                status.put("package", TERMUX_PACKAGE);
            }
            status.put("granted", context.checkSelfPermission(
                    "com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED);
        } catch (Exception error) {
            try {
                status.put("installed", false);
                status.put("error", error.getMessage());
            } catch (Exception ignored) {
                // 不会发生
            }
        }
        return status;
    }

    public static Result run(Context context, String command, long timeoutMs) {
        if (!isInstalled(context)) {
            return new Result("", "", 1,
                    "未安装 Termux。请先安装 Termux，再在设置 → Agent → Termux 环境里完成配置。");
        }
        if (command == null || command.trim().isEmpty()) {
            return new Result("", "", 1, "命令为空");
        }
        long timeout = Math.max(2000L, Math.min(timeoutMs, 600_000L));
        int id = NEXT_ID.incrementAndGet();
        CompletableFuture<Result> future = new CompletableFuture<>();
        PENDING.put(id, future);
        try {
            Intent resultIntent = new Intent(context, TermuxResultService.class);
            resultIntent.putExtra("dla_termux_request_id", id);
            int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getService(
                    context, id, resultIntent, flags);

            Intent intent = new Intent();
            intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
            intent.setAction(ACTION_RUN_COMMAND);
            intent.putExtra(EXTRA_COMMAND_PATH, BASH);
            intent.putExtra(EXTRA_ARGUMENTS, new String[]{"-lc", command});
            intent.putExtra(EXTRA_WORKDIR, HOME);
            intent.putExtra(EXTRA_BACKGROUND, true);
            intent.putExtra(EXTRA_COMMAND_LABEL, "DLA Termux command");
            intent.putExtra(EXTRA_PENDING_INTENT, pending);
            context.startService(intent);

            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            return new Result("", "", 124,
                    "Termux 没有返回结果。请确认 Termux 已允许外部应用执行命令：\n"
                            + "在 Termux 执行 echo \"allow-external-apps=true\" >> ~/.termux/termux.properties "
                            + "&& termux-reload-settings；并给 DLA 授予“Run commands in Termux environment”权限。");
        } catch (SecurityException error) {
            return new Result("", "", 126,
                    "没有 Termux RUN_COMMAND 权限。请到 Android 设置 → 应用 → DLA → 权限，"
                            + "授予“Run commands in Termux environment”。");
        } catch (Exception error) {
            return new Result("", "", 1,
                    error.getMessage() == null ? error.getClass().getSimpleName()
                            : error.getMessage());
        } finally {
            PENDING.remove(id);
        }
    }

    static void complete(int id, Bundle bundle) {
        CompletableFuture<Result> future = PENDING.remove(id);
        if (future == null) {
            return;
        }
        if (bundle == null) {
            future.complete(new Result("", "", 1, "Termux 没有返回结果 bundle"));
            return;
        }
        String stdout = bundle.getString(EXTRA_STDOUT, "");
        String stderr = bundle.getString(EXTRA_STDERR, "");
        int exitCode = bundle.getInt(EXTRA_EXIT_CODE, 1);
        int err = bundle.getInt(EXTRA_ERR, 0);
        String errmsg = bundle.getString(EXTRA_ERRMSG, "");
        String error = errmsg;
        if (err != 0 && err != -1 && error.isEmpty()) {
            error = "Termux internal error " + err;
        }
        future.complete(new Result(stdout, stderr, exitCode, error));
    }

    public static String setupScript(Context context) {
        try (InputStream in = context.getResources().openRawResource(
                com.example.chatdemo.R.raw.termux_setup)) {
            byte[] data = new byte[8192];
            StringBuilder builder = new StringBuilder();
            int read;
            while ((read = in.read(data)) != -1) {
                builder.append(new String(data, 0, read, StandardCharsets.UTF_8));
            }
            return builder.toString();
        } catch (Exception error) {
            return "";
        }
    }

    public static void open(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }
}
