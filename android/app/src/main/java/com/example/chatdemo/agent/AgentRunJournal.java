package com.example.chatdemo.agent;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 单次运行的「日志账本」：跑在 :agent 进程里，把事件流落成一个小文件，
 * 让界面进程即使中途被杀，回来也能把这一轮的正文 / 思考 / 工具卡补回来。
 *
 * 为什么需要它：正文和工具卡原本只累积在界面进程的内存里（ChatEngine.Open），
 * 界面一死就没了；而任务本身还在 :agent 里继续跑，跑完只发一个 done 事件，
 * 没人接就等于这条回答从未存在过——用户看到的就是「任务完成，但 AI 消息消失」。
 *
 * 设计约束：
 * - 一个 run 一个文件（files/agent-runs/<requestId>.json），不与会话 JSON 抢写；
 * - 写盘按事件类型 + 时间节流，避免长任务里每来一个 token 就写一次盘；
 * - 终态（done / error / stopped）写完后发一条本地广播，界面收到就立刻认领；
 * - 界面认领后由界面调用 delete()，文件不存在即视为已处理。
 */
public final class AgentRunJournal {

    private static final String TAG = "DlaJournal";
    public static final String DIR = "agent-runs";
    public static final String ACTION = "com.example.chatdemo.action.AGENT_RUN_UPDATE";

    /** 单条工具结果最多存这么多字符，避免把文件写爆 */
    private static final int MAX_RESULT_CHARS = 20000;
    /** 两次写盘的最小间隔（毫秒）；关键事件不受此限制 */
    private static final long WRITE_INTERVAL_MS = 700;

    private final Context context;
    private final File file;
    private final String requestId;
    private final String sessionId;
    private final String mode;
    private final long startedAt;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final Map<String, JSONObject> tools = new LinkedHashMap<>();
    private JSONObject usage;
    private JSONObject taskState;
    private String status = "running";
    private String error = "";
    private long lastWriteAt;

    private AgentRunJournal(Context context, String requestId, String sessionId,
                            String mode, long startedAt) {
        this.context = context.getApplicationContext();
        this.requestId = requestId;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.mode = mode == null || mode.isEmpty() ? "agent" : mode;
        this.startedAt = startedAt;
        this.file = new File(dir(context), requestId + ".json");
    }

    public static File dir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create " + dir);
        }
        return dir;
    }

    /** 开始记录；会话 id 从 payload 里取，界面才知道该补到哪个会话 */
    public static AgentRunJournal begin(Context context, String requestId, JSONObject payload) {
        if (context == null || requestId == null) return null;
        try {
            return new AgentRunJournal(
                    context,
                    requestId,
                    payload == null ? "" : payload.optString("sessionId", ""),
                    payload == null ? "agent" : payload.optString("mode", "agent"),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            Log.w(TAG, "begin failed: " + error);
            return null;
        }
    }

    public String requestId() {
        return requestId;
    }

    public String sessionId() {
        return sessionId;
    }

    public synchronized String status() {
        return status;
    }

    /** 正文预览：通知栏里给一句人话（取第一段，压缩空白） */
    public synchronized String previewText(int limit) {
        String value = text.toString().trim().replaceAll("\\s+", " ");
        if (value.isEmpty()) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    public synchronized String errorText() {
        return error == null ? "" : error;
    }

    public synchronized int toolCount() {
        return tools.size();
    }

    public synchronized int rounds() {
        int max = 0;
        for (JSONObject tool : tools.values()) {
            int round = tool.optInt("round", 0);
            if (round > max) max = round;
        }
        return max;
    }

    public synchronized String lastToolName() {
        String name = "";
        for (JSONObject tool : tools.values()) {
            String current = tool.optString("name");
            if (!current.isEmpty()) name = current;
        }
        return name;
    }

    public synchronized long elapsedMs() {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    /** 累计一条运行时事件，并按需写盘 */
    public synchronized void onEvent(JSONObject event) {
        if (event == null) return;
        String type = event.optString("type");
        boolean critical = false;
        try {
            switch (type) {
                case "text":
                    text.append(deltaOf(event));
                    break;
                case "thinking":
                    thinking.append(deltaOf(event));
                    break;
                case "tool_start": {
                    String id = event.optString("id");
                    if (!id.isEmpty()) {
                        tools.put(id, new JSONObject()
                                .put("id", id)
                                .put("name", event.optString("name"))
                                .put("status", event.optBoolean("approval") ? "waiting" : "running")
                                .put("input", event.optString("input"))
                                .put("result", "")
                                .put("elapsedMs", 0L)
                                .put("round", event.optInt("round", 0)));
                        critical = true;
                    }
                    break;
                }
                case "tool_result": {
                    String id = event.optString("id");
                    JSONObject tool = tools.get(id);
                    if (tool != null) {
                        boolean isError = event.has("isError")
                                ? event.optBoolean("isError")
                                : event.optBoolean("is_error");
                        String result = event.optString("content");
                        if (result.isEmpty()) result = event.optString("text");
                        tool.put("status", isError ? "error" : "done");
                        tool.put("result", abbreviate(result));
                        tool.put("elapsedMs", event.optLong("elapsedMs", 0L));
                        critical = true;
                    }
                    break;
                }
                case "usage": {
                    usage = new JSONObject()
                            .put("inputTokens", event.optInt("inputTokens"))
                            .put("outputTokens", event.optInt("outputTokens"))
                            .put("cacheCreationInputTokens", event.optInt("cacheCreationInputTokens"))
                            .put("cacheReadInputTokens", event.optInt("cacheReadInputTokens"));
                    break;
                }
                case "task_state": {
                    taskState = new JSONObject()
                            .put("plan", event.optJSONArray("plan") == null
                                    ? new JSONArray() : event.optJSONArray("plan"))
                            .put("current", event.optInt("current", 1))
                            .put("notes", event.optJSONArray("notes") == null
                                    ? new JSONArray() : event.optJSONArray("notes"));
                    critical = true;
                    break;
                }
                case "done":
                    status = "done";
                    critical = true;
                    break;
                case "stopped":
                    status = "stopped";
                    critical = true;
                    break;
                case "error":
                    status = "error";
                    error = event.optString("message");
                    critical = true;
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
            // 记账永远不能影响任务本身
        }
        long now = System.currentTimeMillis();
        if (critical || now - lastWriteAt >= WRITE_INTERVAL_MS) {
            write();
        }
    }

    private static String deltaOf(JSONObject event) {
        String delta = event.optString("delta");
        return delta.isEmpty() ? event.optString("text") : delta;
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        if (value.length() <= MAX_RESULT_CHARS) return value;
        return value.substring(0, MAX_RESULT_CHARS) + "\n…（结果过长，日志已截断）";
    }

    /** 运行结束：落终态并广播，让界面立刻认领 */
    public synchronized void finish(String finalStatus) {
        if (finalStatus != null && !finalStatus.isEmpty()) {
            status = finalStatus;
        }
        write();
        try {
            Intent intent = new Intent(ACTION).setPackage(context.getPackageName());
            intent.putExtra("requestId", requestId);
            context.sendBroadcast(intent);
        } catch (Throwable error) {
            Log.w(TAG, "broadcast failed: " + error);
        }
    }

    /** 界面已经把这轮写进会话，日志可以删了 */
    public void delete() {
        try {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "cannot delete " + file);
            }
        } catch (Throwable ignored) {
            // 文件删不掉最坏只是下次重复认领，认领侧有 runId 去重
        }
    }

    private void write() {
        lastWriteAt = System.currentTimeMillis();
        JSONObject json = new JSONObject();
        try {
            json.put("requestId", requestId);
            json.put("sessionId", sessionId);
            json.put("mode", mode);
            json.put("status", status);
            json.put("startedAt", startedAt);
            json.put("updatedAt", System.currentTimeMillis());
            json.put("text", text.toString());
            json.put("thinking", thinking.toString());
            json.put("error", error == null ? "" : error);
            JSONArray array = new JSONArray();
            for (JSONObject tool : tools.values()) {
                array.put(tool);
            }
            json.put("tools", array);
            if (usage != null) json.put("usage", usage);
            if (taskState != null) json.put("taskState", taskState);
        } catch (Exception ignored) {
            return;
        }
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            writeText(temp, json.toString());
            if (!temp.renameTo(file)) {
                writeText(file, json.toString());
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        } catch (Throwable error) {
            Log.w(TAG, "write failed: " + error);
        }
    }

    private static void writeText(File target, String text) throws Exception {
        java.io.FileOutputStream out = new java.io.FileOutputStream(target, false);
        try {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } finally {
            out.close();
        }
    }

    // ---- 界面进程侧：认领 ----

    /** 列出所有还没被界面认领的运行日志（旧的在前） */
    public static List<File> pending(Context context) {
        File[] files = dir(context).listFiles((f) -> f.isFile() && f.getName().endsWith(".json"));
        List<File> out = new ArrayList<>();
        if (files == null) return out;
        for (File file : files) {
            out.add(file);
        }
        out.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        return out;
    }

    public static void consume(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
