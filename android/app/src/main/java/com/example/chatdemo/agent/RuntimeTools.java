package com.example.chatdemo.agent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 内置运行时的统一入口：本机 shell、Python、Node。
 *
 * 三个工具都要求审批（除非用户开了「完全权限」），都应用身份运行，
 * 都不依赖 Termux / Shizuku。
 */
public final class RuntimeTools {

    private RuntimeTools() {}

    public static JSONArray specs() throws Exception {
        JSONArray array = new JSONArray();
        array.put(NativeShell.spec());
        array.put(PythonTool.spec());
        array.put(NodeTool.spec());
        return array;
    }

    public static boolean isRuntime(String name) {
        return NativeShell.isNativeShell(name)
                || PythonTool.isPython(name)
                || NodeTool.isNode(name);
    }

    public static boolean requiresApproval(String name) {
        return isRuntime(name);
    }

    public static McpClient.ToolResult execute(Context context, String name, JSONObject arguments) {
        if (NativeShell.isNativeShell(name)) {
            return NativeShell.execute(context, arguments);
        }
        if (PythonTool.isPython(name)) {
            return PythonTool.execute(context, arguments);
        }
        if (NodeTool.isNode(name)) {
            return NodeTool.execute(context, arguments);
        }
        return new McpClient.ToolResult("未知的运行时工具：" + name, true);
    }

    /** 用户点停止：把还在跑的子进程（shell / node）整棵杀掉。 */
    public static void cancelAll() {
        ProcessRunner.cancelAll();
    }

    /** 启动时告诉模型本机有什么执行能力，避免它去猜 Termux / Shizuku。 */
    public static String statusLine(Context context) {
        StringBuilder text = new StringBuilder();
        text.append("本机终端可用（/system/bin/sh，应用身份）");
        text.append("；Python 3.12 可用");
        text.append("；Node 24 ").append(RuntimeEnv.nodeReady(context) ? "可用" : "不可用");
        text.append("；ripgrep ").append(RuntimeEnv.rgReady(context) ? "可用" : "不可用");
        return text.toString();
    }
}

