package com.example.chatdemo.agent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Termux 只暴露一个工具，不把 shell/文件/网络命令逐个注入。
 * 模型通过 action 自行选择：status / capabilities / run。
 */
public final class TermuxTools {

    public static final String NAME = "termux";

    private TermuxTools() {}

    public static boolean isTermux(String name) {
        return NAME.equals(name);
    }

    public static JSONObject spec() throws Exception {
        JSONObject properties = new JSONObject();
        JSONArray actions = new JSONArray();
        actions.put("status");
        actions.put("capabilities");
        actions.put("run");
        properties.put("action", new JSONObject()
                .put("type", "string")
                .put("enum", actions)
                .put("description", "status=检查 Termux；capabilities=查看可用能力；run=执行一条 shell/命令"));
        properties.put("command", new JSONObject()
                .put("type", "string")
                .put("description", "action=run 时要执行的命令；一次只做一件事"));
        properties.put("timeout_ms", new JSONObject()
                .put("type", "number")
                .put("description", "超时毫秒，默认 30000，最大 120000"));

        return new JSONObject()
                .put("name", NAME)
                .put("description",
                        "Termux 本地终端能力。DLA 不会把所有 Termux 命令注入工具列表；"
                                + "需要 shell、文件、Node/npm、Python、rg 或网络命令时，"
                                + "调用本工具并自行选择 action。run 只执行明确需要的一条命令，"
                                + "结果返回 stdout/stderr/exit code。"
                                + "Termux 环境可能未安装或未授权外部命令，先 status 检查。")
                .put("input_schema", new JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", new JSONArray().put("action")));
    }

    public static boolean requiresApproval(String name, JSONObject arguments) {
        return NAME.equals(name) && "run".equals(arguments.optString("action"));
    }

    public static McpClient.ToolResult execute(Context context, JSONObject arguments) {
        String action = arguments.optString("action", "status");
        if ("status".equals(action)) {
            JSONObject status = TermuxBridge.status(context);
            return new McpClient.ToolResult(
                    "Termux 状态：\n" + status.toString()
                            + "\n\n如果 installed=true 但 run 超时，检查 Termux 的 allow-external-apps "
                            + "以及 DLA 的 RUN_COMMAND 权限。",
                    !status.optBoolean("installed", false));
        }
        if ("capabilities".equals(action)) {
            return new McpClient.ToolResult(capabilities(context), false);
        }
        if (!"run".equals(action)) {
            return new McpClient.ToolResult(
                    "未知 action：" + action + "。可用：status / capabilities / run", true);
        }
        String command = arguments.optString("command", "").trim();
        if (command.isEmpty()) {
            return new McpClient.ToolResult("action=run 需要 command", true);
        }
        if (command.length() > 12000) {
            return new McpClient.ToolResult("命令过长，请拆成更小的步骤", true);
        }
        int timeout = Math.max(2000, Math.min(
                arguments.optInt("timeout_ms", 30000), 120000));
        TermuxBridge.Result result = TermuxBridge.run(context, command, timeout);
        String text = result.text();
        if (text.length() > 24000) {
            text = text.substring(0, 24000) + "\n…（输出已截断）";
        }
        return new McpClient.ToolResult(text, result.isError());
    }

    private static String capabilities(Context context) {
        JSONObject status = TermuxBridge.status(context);
        return "Termux 是单一工具，不预注入全部子命令。\n"
                + "可执行：shell、文件、Node/npm、Python、rg、网络和包管理。\n"
                + "调用方式：\n"
                + "- action=status：检查安装和版本\n"
                + "- action=run, command=\"…\"：执行一条命令\n"
                + "- action=capabilities：查看本说明\n"
                + "当前状态：" + status.toString() + "\n"
                + "环境安装入口：设置 → Agent → Termux 环境 → 一键安装。\n"
                + "如果命令需要安装包，先向用户说明，再执行 pkg/npm/pip。";
    }
}
