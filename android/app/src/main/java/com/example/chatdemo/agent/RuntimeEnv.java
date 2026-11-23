package com.example.chatdemo.agent;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * 内置运行时的路径与环境。
 *
 * 三个运行时都在 APK 里，不依赖 Termux：
 * - shell：Android 自带的 /system/bin/sh + toybox（零体积）；
 * - Node：libdshnode.so + libnode.so（nativeLibraryDir，可 exec）；
 * - ripgrep：librg.so（同上）；
 * - Python：Chaquopy 的 JNI 解释器（进程内，不产生可执行文件）。
 *
 * Android 10（API 29）起应用不能在可写目录 exec，只有 nativeLibraryDir 可以，
 * 所以 node/rg 以 lib*.so 命名随 APK 安装，再软链到 files/bin 供 shell 按名字调用。
 */
public final class RuntimeEnv {

    private static final String TAG = "DlaRuntime";

    private RuntimeEnv() {}

    public static File home(Context context) {
        return ensureDir(new File(context.getFilesDir(), "home"));
    }

    public static File binDir(Context context) {
        return ensureDir(new File(context.getFilesDir(), "bin"));
    }

    public static File nodeModules(Context context) {
        return ensureDir(new File(context.getFilesDir(), "node_modules"));
    }

    public static File runDir(Context context) {
        return ensureDir(new File(context.getCacheDir(), "dla-run"));
    }

    public static File nativeDir(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir);
    }

    public static File nodeFile(Context context) {
        return new File(nativeDir(context), "libdshnode.so");
    }

    public static File rgFile(Context context) {
        return new File(nativeDir(context), "librg.so");
    }

    public static boolean nodeReady(Context context) {
        return nodeFile(context).isFile();
    }

    public static boolean rgReady(Context context) {
        return rgFile(context).isFile();
    }

    /** 建软链：files/bin/node → nativeLibraryDir/libdshnode.so，files/bin/rg → …/librg.so */
    public static synchronized void ensureLinks(Context context) {
        File bin = binDir(context);
        link(rgFile(context), new File(bin, "rg"));
        link(nodeFile(context), new File(bin, "node"));
    }

    private static void link(File target, File link) {
        if (!target.isFile()) {
            Log.w(TAG, "runtime missing: " + target);
            return;
        }
        try {
            Path linkPath = link.toPath();
            Path targetPath = target.toPath();
            if (Files.isSymbolicLink(linkPath)) {
                if (Files.readSymbolicLink(linkPath).equals(targetPath)) {
                    return;
                }
                Files.delete(linkPath);
            } else if (link.exists() && !link.delete()) {
                Log.w(TAG, "cannot replace " + link);
                return;
            }
            Files.createSymbolicLink(linkPath, targetPath);
        } catch (Throwable error) {
            // 软链不可用时退化为「按绝对路径调用」，不影响工具本身
            Log.w(TAG, "link " + link.getName() + " failed: " + error);
        }
    }

    public static Map<String, String> environment(Context context) {
        Map<String, String> env = new HashMap<>();
        env.put("HOME", home(context).getAbsolutePath());
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        env.put("PATH", binDir(context).getAbsolutePath() + ":/system/bin:/system/xbin");
        env.put("TZ", TimeZone.getDefault().getID());
        env.put("LANG", "C.UTF-8");
        env.put("NODE_PATH", nodeModules(context).getAbsolutePath());
        env.put("NODE_NO_WARNINGS", "1");
        return env;
    }

    private static File ensureDir(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "mkdirs failed: " + dir);
        }
        return dir;
    }
}
