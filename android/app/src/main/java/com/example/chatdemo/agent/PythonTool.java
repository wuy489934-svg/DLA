package com.example.chatdemo.agent;

import android.content.Context;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * phone_py：内置 CPython（Chaquopy）执行一段脚本。
 *
 * Python 解释器是 JNI 库，跑在 :agent 进程内，不产生可执行文件，也不需要 Termux。
 * 由于无法从外部强杀进程内的解释器，超时用「Python 侧 sys.settrace 检查截止时间」
 * 实现；Java 侧再加一层看门狗，保证 Agent 线程不会永远卡住。
 */
public final class PythonTool {

    public static final String NAME = "phone_py";
    private static final String TAG = "DlaPython";
    private static final int DEFAULT_TIMEOUT = 20_000;
    private static final int MAX_TIMEOUT = 120_000;

    private PythonTool() {}

    public static boolean isPython(String name) {
        return NAME.equals(name);
    }

    public static JSONObject spec() throws Exception {
        JSONObject properties = new JSONObject();
        properties.put("code", new JSONObject()
                .put("type", "string")
                .put("description", "要执行的 Python 源码（完整标准库可用）"));
        properties.put("timeout_ms", new JSONObject()
                .put("type", "number")
                .put("description", "超时毫秒，默认 20000，最大 120000"));

        return new JSONObject()
                .put("name", NAME)
                .put("description",
                        "用内置的 CPython 3.12 执行一段 Python（Chaquopy，不依赖 Termux）。"
                                + "完整标准库可用：json/csv/re/tempfile/datetime/zipfile/http/urllib 等；"
                                + "没有 pip，第三方包需要构建期打包。返回 stdout、stderr 与错误堆栈。"
                                + "适合数据处理、格式转换、批量文件操作；脚本以应用身份运行，"
                                + "读写 /sdcard 需要「所有文件访问」权限。"
                                + "print 的内容会作为 stdout 返回，结尾不要依赖交互式 input。")
                .put("input_schema", new JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", new JSONArray().put("code")));
    }

    public static McpClient.ToolResult execute(Context context, JSONObject arguments) {
        String code = arguments.optString("code", "");
        if (code.trim().isEmpty()) {
            return new McpClient.ToolResult("code 不能为空", true);
        }
        int timeout = NativeShell.clampTimeout(arguments.optInt("timeout_ms", DEFAULT_TIMEOUT));
        try {
            ensureStarted(context);
        } catch (Throwable error) {
            return new McpClient.ToolResult("Python 运行时启动失败：" + error, true);
        }

        String cwd = RuntimeEnv.home(context).getAbsolutePath();
        final String[] holder = new String[1];
        Thread worker = new Thread(() -> {
            try {
                PyObject module = Python.getInstance().getModule("dla_runner");
                holder[0] = module.callAttr("run", code, cwd, timeout).toString();
            } catch (Throwable error) {
                Log.w(TAG, "python run failed", error);
                try {
                    JSONObject json = new JSONObject();
                    json.put("ok", false);
                    json.put("stdout", "");
                    json.put("stderr", "");
                    json.put("error", String.valueOf(error));
                    json.put("ms", 0);
                    holder[0] = json.toString();
                } catch (Throwable ignored) {
                    holder[0] = "{\"ok\":false,\"error\":\"unknown\"}";
                }
            }
        }, "dla-python");
        // 看门狗兜底：Python 侧超时失效时也不让 Agent 线程永远等下去
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(timeout + 3000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            return new McpClient.ToolResult(
                    "Python 执行超过 " + timeout + " ms 仍未结束，已放弃等待（脚本可能仍在后台线程里跑）。"
                            + "请把任务拆小，或改用 phone_sh / phone_js。", true);
        }
        return format(holder[0]);
    }

    private static McpClient.ToolResult format(String raw) {
        try {
            JSONObject json = new JSONObject(raw == null ? "{}" : raw);
            boolean ok = json.optBoolean("ok", false);
            StringBuilder text = new StringBuilder();
            text.append(ok ? "执行完成" : "执行失败")
                    .append(" · ").append(json.optLong("ms", 0)).append(" ms · Python 3.12\n");
            String stdout = json.optString("stdout", "");
            String stderr = json.optString("stderr", "");
            String error = json.optString("error", "");
            text.append("--- stdout ---\n").append(stdout.isEmpty() ? "（无输出）" : stdout);
            if (!stderr.trim().isEmpty()) {
                text.append("\n--- stderr ---\n").append(stderr);
            }
            if (!error.trim().isEmpty()) {
                text.append("\n--- error ---\n").append(error);
            }
            return new McpClient.ToolResult(text.toString(), !ok);
        } catch (Throwable parseError) {
            return new McpClient.ToolResult("Python 返回结果无法解析：" + raw, true);
        }
    }

    private static void ensureStarted(Context context) {
        if (Python.isStarted()) {
            return;
        }
        synchronized (PythonTool.class) {
            if (Python.isStarted()) {
                return;
            }
            Python.start(new AndroidPlatform(context.getApplicationContext()));
            Log.i(TAG, "Python started");
        }
    }
}

