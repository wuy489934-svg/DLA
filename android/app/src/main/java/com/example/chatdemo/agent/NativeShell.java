package com.example.chatdemo.agent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Map;

/**
 * phone_sh：应用自己身份的本机终端。
 *
 * 这是默认的命令执行入口，不需要 Termux、不需要 Shizuku：
 * 命令交给 Android 自带的 /system/bin/sh（toybox）执行，返回退出码、stdout、stderr。
 * 需要 uid 2000 的特权命令（pm/am/settings）时用 phone_shell（Shizuku）。
 */
public final class NativeShell {

    public static final String NAME = "phone_sh";
    private static final int DEFAULT_TIMEOUT = 20_000;
    private static final int MAX_TIMEOUT = 120_000;

    private NativeShell() {}

    public static boolean isNativeShell(String name) {
        return NAME.equals(name);
    }

    public static JSONObject spec() throws Exception {
        JSONObject properties = new JSONObject();
        properties.put("command", new JSONObject()
                .put("type", "string")
                .put("description", "要执行的 shell 命令，例如 `ls -la /sdcard/Download | head`"));
        properties.put("cwd", new JSONObject()
                .put("type", "string")
                .put("description", "工作目录，默认是 DLA 的应用目录；传绝对路径"));
        properties.put("timeout_ms", new JSONObject()
                .put("type", "number")
                .put("description", "超时毫秒，默认 20000，最大 120000"));

        return new JSONObject()
                .put("name", NAME)
                .put("description",
                        "在手机上以「应用身份」执行 shell 命令（不需要 Termux / Shizuku）。返回退出码、stdout、stderr。"
                                + "能：读自身目录与 /sdcard、grep/find/awk/sed、getprop、curl、node、rg。"
                                + "不能：pm / am / settings / dumpsys（SELinux 拒绝，典型报错 "
                                + "\"Failure calling service ...\" 或 \"Permission Denial\"），"
                                + "也看不到 /proc 全局、/data/app、别家应用私有目录。这些不是命令写错：需要就换 phone_shell（Shizuku，uid 2000）。"
                                + "不要用 ps -A 或端口探测判断别的进程/服务是否存在（这里只能看到自己）。"
                                + "command 可以包含多条语句（用 ; 或 && 连接）：互不依赖的只读检查请合并成一次调用；"
                                + "不要起交互式进程（top/vim），需要脚本能力时用 phone_py / phone_js。")
                .put("input_schema", new JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", new JSONArray().put("command")));
    }

    public static McpClient.ToolResult execute(Context context, JSONObject arguments) {
        String command = arguments.optString("command", "").trim();
        if (command.isEmpty()) {
            return new McpClient.ToolResult("command 不能为空", true);
        }
        if (command.length() > 12000) {
            return new McpClient.ToolResult("命令过长，请拆成更小的步骤", true);
        }
        String blocked = Hardline.blockedReason(command);
        if (blocked != null) {
            return new McpClient.ToolResult("已拒绝执行：" + blocked, true);
        }
        int timeout = clampTimeout(arguments.optInt("timeout_ms", DEFAULT_TIMEOUT));

        File dir = RuntimeEnv.home(context);
        String cwd = arguments.optString("cwd", "").trim();
        if (!cwd.isEmpty()) {
            File requested = new File(cwd);
            if (!requested.isAbsolute()) {
                requested = new File(dir, cwd);
            }
            if (!requested.isDirectory() && !requested.mkdirs()) {
                return new McpClient.ToolResult("工作目录不存在且创建失败：" + requested, true);
            }
            dir = requested;
        }

        try {
            ProcessRunner.Result result = runRaw(context, command, timeout, dir);
            return new McpClient.ToolResult(format(result, dir), result.timedOut || result.exit != 0);
        } catch (Throwable error) {
            return new McpClient.ToolResult("执行失败：" + error, true);
        }
    }

    /** 内部复用：LocalTools 在没有 Shizuku 时用它跑 app 身份的 shell，不要格式化成工具结果。 */
    static ProcessRunner.Result runRaw(Context context, String command, int timeoutMs) throws Exception {
        return runRaw(context, command, timeoutMs, RuntimeEnv.home(context));
    }

    static ProcessRunner.Result runRaw(Context context, String command, int timeoutMs, File dir)
            throws Exception {
        RuntimeEnv.ensureLinks(context);
        Map<String, String> env = RuntimeEnv.environment(context);
        File pidFile = new File(RuntimeEnv.runDir(context), "raw-" + java.util.UUID.randomUUID() + ".pid");
        return ProcessRunner.run(command, dir, env, timeoutMs, pidFile);
    }

    static String format(ProcessRunner.Result result, File dir) {
        StringBuilder text = new StringBuilder();
        if (result.timedOut) {
            text.append("命令超时，已结束整棵进程树。\n");
        }
        text.append("退出码 ").append(result.exit)
                .append(" · ").append(result.ms).append(" ms")
                .append(" · 目录 ").append(dir.getAbsolutePath()).append('\n');
        text.append("--- stdout ---\n").append(orEmpty(result.stdout));
        if (!result.stderr.trim().isEmpty()) {
            text.append("\n--- stderr ---\n").append(result.stderr);
        }
        return text.toString();
    }

    private static String orEmpty(String value) {
        return value == null || value.isEmpty() ? "（无输出）" : value;
    }

    static int clampTimeout(int value) {
        return Math.max(2000, Math.min(value, MAX_TIMEOUT));
    }
}

