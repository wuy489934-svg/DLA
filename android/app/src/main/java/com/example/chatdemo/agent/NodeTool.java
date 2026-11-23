package com.example.chatdemo.agent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

/**
 * phone_js：内置 Node.js 24 执行一段脚本。
 *
 * Node 是 APK 里的 libnode.so + libdshnode.so（nativeLibraryDir），
 * 以子进程方式运行：崩溃、死循环、超时都不会拖死 Agent，停止时整棵树被杀。
 */
public final class NodeTool {

    public static final String NAME = "phone_js";
    private static final int DEFAULT_TIMEOUT = 20_000;
    private static final int MAX_TIMEOUT = 120_000;

    private NodeTool() {}

    public static boolean isNode(String name) {
        return NAME.equals(name);
    }

    public static JSONObject spec() throws Exception {
        JSONObject properties = new JSONObject();
        properties.put("code", new JSONObject()
                .put("type", "string")
                .put("description", "要执行的 JavaScript 源码（CommonJS，可直接 require('node:fs') 等）"));
        properties.put("timeout_ms", new JSONObject()
                .put("type", "number")
                .put("description", "超时毫秒，默认 20000，最大 120000"));

        return new JSONObject()
                .put("name", NAME)
                .put("description",
                        "用内置的 Node.js 24 执行一段 JavaScript（不依赖 Termux）。"
                                + "支持 Node 内置模块（fs/path/os/http/crypto/child_process 等）和 files/node_modules 里的包；"
                                + "目前没有 npm 安装入口。返回退出码、stdout、stderr、耗时。"
                                + "适合计算、解析 JSON/CSV、批量改文件、生成脚本结果；"
                                + "脚本以应用身份运行，读写 /sdcard 需要「所有文件访问」权限。"
                                + "进程超时会被整棵树杀掉，所以不要在脚本里起常驻服务。")
                .put("input_schema", new JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", new JSONArray().put("code")));
    }

    public static McpClient.ToolResult execute(Context context, JSONObject arguments) {
        if (!RuntimeEnv.nodeReady(context)) {
            return new McpClient.ToolResult("内置 Node 不可用（APK 缺少 libdshnode.so），请重装应用", true);
        }
        String code = arguments.optString("code", "");
        if (code.trim().isEmpty()) {
            return new McpClient.ToolResult("code 不能为空", true);
        }
        int timeout = NativeShell.clampTimeout(arguments.optInt("timeout_ms", DEFAULT_TIMEOUT));
        RuntimeEnv.ensureLinks(context);
        File dir = RuntimeEnv.home(context);
        File script = new File(RuntimeEnv.runDir(context), "dla-" + UUID.randomUUID() + ".js");
        try {
            Files.write(script.toPath(), code.getBytes(StandardCharsets.UTF_8));
            Map<String, String> env = RuntimeEnv.environment(context);
            String command = quoted(RuntimeEnv.nodeFile(context).getAbsolutePath())
                    + " " + quoted(script.getAbsolutePath());
            File pidFile = new File(RuntimeEnv.runDir(context), "js-" + UUID.randomUUID() + ".pid");
            ProcessRunner.Result result = ProcessRunner.run(command, dir, env, timeout, pidFile);
            return new McpClient.ToolResult(format(result), result.timedOut || result.exit != 0);
        } catch (Throwable error) {
            return new McpClient.ToolResult("Node 执行失败：" + error, true);
        } finally {
            // 脚本文本里可能有用户数据，跑完就删
            try {
                Files.deleteIfExists(script.toPath());
            } catch (Throwable ignored) {
                // 删除失败不影响结果
            }
        }
    }

    private static String quoted(String value) {
        return ProcessRunner.quote(value);
    }

    private static String format(ProcessRunner.Result result) {
        StringBuilder text = new StringBuilder();
        if (result.timedOut) {
            text.append("脚本超时，已结束整棵进程树。\n");
        }
        text.append("退出码 ").append(result.exit)
                .append(" · ").append(result.ms).append(" ms")
                .append(" · Node 24\n");
        text.append("--- stdout ---\n")
                .append(result.stdout.isEmpty() ? "（无输出）" : result.stdout);
        if (!result.stderr.trim().isEmpty()) {
            text.append("\n--- stderr ---\n").append(result.stderr);
        }
        return text.toString();
    }
}

