package com.example.chatdemo.agent;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * 原生 Agent 循环：Anthropic Messages 流式请求 + MCP 工具调用。
 *
 * 放在原生是为了切后台、锁屏也能继续跑；网页只负责渲染事件。
 * 每轮把 tool_use 和 tool_result 追加回 messages 再发下一轮。
 */
public final class AgentRunner {

    public interface Emitter {
        void emit(JSONObject event);
    }

    private static final int MAX_TOOL_RESULT_CHARS = 24000;
    private static final int MAX_ROUNDS = 120;
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_STEPS_IN_PLAN = 30;
    private static final int MAX_NOTES = 20;
    private static final int MAX_NOTE_CHARS = 4000;
    private static final int TOOL_PARALLELISM = 3;
    private static final long TOOL_CACHE_MS = 3000;

    private static final ExecutorService TOOL_POOL = Executors.newFixedThreadPool(
            TOOL_PARALLELISM,
            runnable -> {
                Thread thread = new Thread(runnable, "dla-tool");
                thread.setDaemon(true);
                return thread;
            }
    );

    private static final class ToolRef {
        final String serverId;
        final String toolName;

        ToolRef(String serverId, String toolName) {
            this.serverId = serverId;
            this.toolName = toolName;
        }
    }

    private static final class ToolCall {
        final String id;
        final String name;
        final StringBuilder arguments = new StringBuilder();

        ToolCall(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class RoundResult {
        final StringBuilder text = new StringBuilder();
        final List<ToolCall> toolCalls = new ArrayList<>();
        final long startedAt = SystemClock.elapsedRealtime();
        long firstTokenAt;
        String stopReason = "end_turn";
        int inputTokens;
        int outputTokens;
        int cacheCreationInputTokens;
        int cacheReadInputTokens;
    }

    private static final class CancelledException extends Exception {}

    private static final class ToolOutcome {
        final String text;
        final boolean isError;
        final String imageBase64;
        final String imageMime;
        final long elapsedMs;
        final boolean cached;

        ToolOutcome(String text, boolean isError, String imageBase64, String imageMime,
                    long elapsedMs, boolean cached) {
            this.text = text == null || text.isEmpty() ? "（工具未返回内容）" : text;
            this.isError = isError;
            this.imageBase64 = imageBase64;
            this.imageMime = imageMime;
            this.elapsedMs = elapsedMs;
            this.cached = cached;
        }
    }

    private static final class CachedTool {
        final ToolOutcome outcome;
        final long expiresAt;

        CachedTool(ToolOutcome outcome, long expiresAt) {
            this.outcome = outcome;
            this.expiresAt = expiresAt;
        }
    }

    private final Context context;
    private final JSONObject payload;
    private final Emitter emitter;
    /** 完全权限：跳过危险工具审批 */
    private final boolean autoApprove;
    /** 长任务计划与笔记，会拼进 system，不受上下文裁剪影响 */
    private JSONArray plan = new JSONArray();
    private int planCurrent = 0;
    private JSONArray notes = new JSONArray();
    /** 防循环：连续相同工具 + 参数 */
    private String lastToolSignature = "";
    private int repeatCount = 0;
    private final Map<String, ToolRef> toolMap = new HashMap<>();
    /** 全部本地工具目录；只有核心工具会直接下发，其余由 tool_search 按需发现。 */
    private final Map<String, JSONObject> localCatalog = new LinkedHashMap<>();
    private final Set<String> discoveredLocal = new HashSet<>();
    /**
     * 核心工具：直接下发的读屏/点击/输入类操作 + 本机终端。
     * phone_sh 是默认执行入口（应用身份、无需任何外部依赖），所以放进核心；
     * phone_py / phone_js 与文件、设置等能力仍由 tool_search 按需发现。
     */
    private static final Set<String> CORE_LOCAL = new HashSet<>(Arrays.asList(
            "phone_screen", "phone_tap_text", "phone_wait",
            "phone_tap", "phone_swipe", "phone_input", "phone_key",
            "phone_screenshot", "phone_view_image", "phone_open", "phone_apps", "phone_device",
            NativeShell.NAME
    ));
    /** 必须有 uid 2000 shell 才能做的事（其余工具都不该被 Shizuku 卡住）。 */
    private static final Set<String> NEEDS_SHIZUKU = new HashSet<>(Arrays.asList(
            "phone_shell", "phone_stop", "phone_setting_put", "phone_notifications"
    ));
    /** 必须有无障碍连接才能读屏/截图。 */
    private static final Set<String> NEEDS_ACCESSIBILITY = new HashSet<>(Arrays.asList(
            "phone_screen", "phone_wait", "phone_screenshot", "phone_tap_text"
    ));
    /** 无障碍优先；没有无障碍时 Shizuku 的 input 命令可以兜底。 */
    private static final Set<String> ACCESSIBILITY_OR_SHIZUKU = new HashSet<>(Arrays.asList(
            "phone_tap", "phone_tap_shot", "phone_swipe", "phone_input", "phone_key"
    ));

    /** 按真实后端决定工具是否出现，而不是「没有 Shizuku 就全部砍掉」。 */
    private static boolean toolAvailable(String name, boolean accessibility, boolean shizuku) {
        if (NEEDS_SHIZUKU.contains(name)) {
            return shizuku;
        }
        if (NEEDS_ACCESSIBILITY.contains(name)) {
            return accessibility;
        }
        if (ACCESSIBILITY_OR_SHIZUKU.contains(name)) {
            return accessibility || shizuku;
        }
        return true;
    }

    /**
     * 调用指南：身份边界、查应用的正确工具、负证据规则、先测再断言、批量执行。
     *
     * 3.1.2 重写的原因：模型曾把 phone_shell（Shizuku, uid 2000）跑通的 pm 结论说成
     * 「应用身份也能用」，又拿 ps -A / 端口探测去判断 Shizuku 是否在运行。
     * 这两类错误的共同点是「身份」和「证据」没对齐，所以指南按这两条主轴组织。
     */
    private static final String TOOL_MAP =
            "【调用指南（先看这里，别靠猜）】\n"
            + "1) 三种身份，不要混：\n"
            + "   · phone_sh / phone_py / phone_js = 应用身份（untrusted_app）。能读自身目录与 /sdcard、"
            + "跑 grep/find/awk/sed、getprop、curl、node/python；不能跑 pm、am、settings、dumpsys，"
            + "也看不到 /proc 全局、/data/app、别家应用私有目录（会被 SELinux 拒绝，报错形如 "
            + "\"Failure calling service ...\" 或 \"Permission Denial\"）。\n"
            + "   · phone_shell = Shizuku（uid 2000 系统 shell）。pm / am / settings / dumpsys / 全系统 ps "
            + "只在这里可用；它不在工具列表里就说明 Shizuku 没就绪。\n"
            + "   · phone_screen / phone_screenshot / phone_tap* / phone_swipe / phone_input / phone_key = 无障碍，"
            + "只做界面操作，不提供系统信息。\n"
            + "   铁律：phone_shell 跑通不等于「应用身份可用」；phone_sh 失败也不等于「本机做不到」——先换对身份再下结论。\n"
            + "2) 查应用：包名/应用名用 phone_apps（PackageManager 接口，任何身份都能用）；versionCode、"
            + "APK 路径、安装来源、签名、targetSdk 只有 phone_shell 的 pm / dumpsys 拿得到；没有 Shizuku 就直说拿不到，"
            + "并给出去「设置 → 手机能力」开启的路径。\n"
            + "3) 负证据无效：应用身份的 ps -A 只有自己那 3~4 个进程；/proc/net/tcp、ss、netstat、/data/app、"
            + "/proc/<别人的 pid> 都读不到。所以「没看到」不等于「没在跑」，端口探测失败不等于服务没开。"
            + "判断 Shizuku 只看【本机运行时】，或直接 phone_shell 跑 id。\n"
            + "4) 先测再断言：说「不可用 / 不支持 / 没权限」之前，用最小命令实测一次（如 id、pm list packages | head -1）；"
            + "没测就写「未验证」，不要写「应该可用 / 大概不行」。\n"
            + "5) 批量：互不依赖的只读检查合并——phone_sh 一条命令用 ; / && 串联多条，或同一轮同时发多个工具调用。\n"
            + "6) 其他：屏幕类走无障碍；文件/设置/剪贴板/通知/zip/docx 先 tool_search；网络用 phone_sh + curl；"
            + "脚本用 phone_py / phone_js（无 pip/npm）。\n"
            + "7) 工具缺失：只说明缺哪个后端、怎么开启，不要反复换关键词试探同一能力。";

    /**
     * 放在 system prompt 最末尾的自检清单。
     * 模型对末尾指令更敏感，所以把最容易错的四条（身份 / 证据 / 负证据 / 体验）放最后。
     */
    private static final String SELF_CHECK =
            "【回答前自检（逐条过，再输出）】\n"
            + "1) 身份：这条结论来自哪个工具？phone_sh/phone_py/phone_js 是应用身份，phone_shell 是 Shizuku；有没有说混？\n"
            + "2) 证据：每个「可用/不可用」都实测过吗？没测的写「未验证」，不要用「应该」「大概」。\n"
            + "3) 负证据：有没有拿 ps -A、/proc/net、ss、端口探测这类在应用身份下看不到全局的指标当证明？有就撤掉。\n"
            + "4) 体验：用户能不能直接看懂结论和下一步？缺能力时有没有给出开启路径（设置 → 手机能力）？";
    private final Map<String, McpClient> clientByServer = new HashMap<>();
    /** 需要用户确认的 MCP 工具（按函数名） */
    private final Set<String> riskyTools = new HashSet<>();
    /** 等待网页审批的工具调用 */
    private final Map<String, SynchronousQueue<Boolean>> approvals = new ConcurrentHashMap<>();
    private final Map<String, CachedTool> toolCache = new HashMap<>();

    private volatile boolean cancelled;
    private volatile HttpURLConnection activeConnection;

    /**
     * 批量执行的计数：连续多少轮都只发一个同名工具调用。
     * 轮数是有限资源，模型如果一条命令一轮地跑，这里会在第 6/12/18… 轮提醒它合并。
     */
    private String lastSingleToolName = "";
    private int sameToolRounds = 0;
    private int lastBatchHintRound = 0;

    /** 运行开始时实测一次的能力快照，拼进 system prompt（每轮复用，保持 prompt 稳定） */
    private String runtimeText = "";

    public AgentRunner(Context context, JSONObject payload, Emitter emitter) {
        this.context = context.getApplicationContext();
        this.payload = payload;
        this.emitter = emitter;
        this.autoApprove = payload.optBoolean("autoApprove", false);
        if (payload.optBoolean("resumeTaskState", false)) {
            JSONArray savedPlan = payload.optJSONArray("resumePlan");
            if (savedPlan != null) {
                plan = savedPlan;
            }
            planCurrent = Math.min(Math.max(payload.optInt("resumePlanCurrent", 1), 1),
                    Math.max(1, plan.length()));
            JSONArray savedNotes = payload.optJSONArray("resumeNotes");
            if (savedNotes != null) {
                notes = savedNotes;
            }
        }
    }

    public void cancel() {
        cancelled = true;
        android.util.Log.d("DlaAgent", "cancel requested");
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {
                // 连接可能已关闭
            }
        }
        // cancelled 只是让本线程不再等待。shell / Node 脚本可能是本进程的子进程，
        // 也可能 fork 出整棵树，光置标志它们会继续跑（写文件、下载都可能还在进行），
        // 所以必须主动把整棵进程树杀掉。
        try {
            RuntimeTools.cancelAll();
        } catch (Throwable ignored) {
            // cancelAll 自身已兜底，这里再防一层，绝不打断停止流程
        }
    }

    /** 网页审批结果；toolId 对应 tool_start 事件里的 id */
    public void approve(String toolId, boolean allowed) {
        SynchronousQueue<Boolean> queue = approvals.get(toolId);
        if (queue != null) {
            queue.offer(allowed);
        }
    }

    /** 等待网页审批，超时或取消返回 null */
    private Boolean awaitApproval(String toolId) throws InterruptedException {
        SynchronousQueue<Boolean> queue = new SynchronousQueue<>();
        approvals.put(toolId, queue);
        long deadline = SystemClock.elapsedRealtime() + 120_000;
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                if (cancelled) {
                    return null;
                }
                Boolean value = queue.poll(500, TimeUnit.MILLISECONDS);
                if (value != null) {
                    return value;
                }
            }
            return null;
        } finally {
            approvals.remove(toolId);
        }
    }

    private static boolean isRiskyTool(JSONObject tool, String name) {
        JSONObject annotations = tool.optJSONObject("annotations");
        if (annotations != null && annotations.optBoolean("destructiveHint", false)) {
            return true;
        }
        String lower = name == null ? "" : name.toLowerCase();
        for (String word : new String[]{"delete", "remove", "uninstall", "exec", "shell",
                "write", "kill", "freeze", "force-stop"}) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    public void run() {
        try {
            // 先把「这台机器现在到底有什么能力」实测一次，模型不必再用 ps/端口去猜
            runtimeText = runtimeBlock();
            JSONArray tools = connectTools();
            JSONArray messages = buildMessages();
            int maxRounds = Math.min(Math.max(payload.optInt("maxRounds", 120), 1), MAX_ROUNDS);
            for (int round = 0; round < maxRounds; round++) {
                if (cancelled) {
                    throw new CancelledException();
                }
                appendDiscoveredTools(tools);
                trimMessages(messages);
                RoundResult result = streamRound(messages, tools);
                if (result.toolCalls.isEmpty()) {
                    emitDone(result.stopReason);
                    return;
                }
                trackBatch(result.toolCalls);
                messages.put(assistantMessage(result));
                if (canRunParallel(result.toolCalls)) {
                    executeParallelRound(round + 1, result, messages);
                    continue;
                }
                JSONArray results = new JSONArray();
                for (ToolCall call : result.toolCalls) {
                    if (cancelled) {
                        throw new CancelledException();
                    }
                    String signature = call.name + "|"
                            + (call.arguments.length() == 0 ? "{}" : call.arguments.toString());
                    if (signature.equals(lastToolSignature)) {
                        repeatCount++;
                    } else {
                        lastToolSignature = signature;
                        repeatCount = 1;
                    }
                    boolean repeated = repeatCount >= 5;
                    boolean repeatedWarning = repeatCount == 3;

                    JSONObject start = new JSONObject();
                    start.put("type", "tool_start");
                    start.put("id", call.id);
                    start.put("name", call.name);
                    start.put("input", call.arguments.length() == 0 ? "{}" : call.arguments.toString());
                    boolean needsApproval = !repeated && !autoApprove
                            && (LocalTools.requiresApproval(call.name)
                            || RuntimeTools.requiresApproval(call.name)
                            || riskyTools.contains(call.name));
                    start.put("approval", needsApproval);
                    start.put("round", round + 1);
                    start.put("parallelGroup", 0);
                    emit(start);
                    Diagnostics.event("tool_start", call.name);

                    String text = null;
                    boolean isError = false;
                    String imageBase64 = null;
                    String imageMime = null;
                    long elapsed = 0;
                    boolean denied = false;
                    boolean cachedHit = false;
                    if (repeated) {
                        text = "检测到连续 " + repeatCount + " 次完全相同的调用，已阻止。"
                                + "请换一种方法、先读结果，或向用户说明卡点。";
                        isError = true;
                        denied = true;
                    }
                    if (needsApproval) {
                        Boolean allowed = awaitApproval(call.id);
                        if (allowed == null) {
                            text = "审批超时，未执行";
                            isError = true;
                            denied = true;
                        } else if (!allowed) {
                            text = "用户拒绝执行";
                            isError = true;
                            denied = true;
                        }
                    }
                    if (!denied) {
                        CachedTool hit = cached(call);
                        if (hit != null) {
                            text = hit.outcome.text;
                            isError = hit.outcome.isError;
                            imageBase64 = hit.outcome.imageBase64;
                            imageMime = hit.outcome.imageMime;
                            cachedHit = true;
                        } else {
                            long startedAt = SystemClock.elapsedRealtime();
                            try {
                                McpClient.ToolResult toolResult = executeTool(call);
                                text = toolResult.text;
                                isError = toolResult.isError;
                                imageBase64 = toolResult.imageBase64;
                                imageMime = toolResult.imageMime;
                            } catch (Exception error) {
                                text = "错误：" + (error.getMessage() == null
                                        ? error.getClass().getSimpleName() : error.getMessage());
                                isError = true;
                            }
                            elapsed = SystemClock.elapsedRealtime() - startedAt;
                            outCache(call, new ToolOutcome(
                                    text, isError, imageBase64, imageMime, elapsed, false
                            ));
                        }
                        if (!LocalTools.isParallelSafe(call.name)) {
                            toolCache.clear();
                        }
                    }
                    if (text == null || text.isEmpty()) {
                        text = "（工具未返回内容）";
                    }
                    if (repeatedWarning && !isError) {
                        text = text + "\n\n（提示：相同调用已重复 3 次，可能卡住了，请检查上一步结果或换方法。）";
                    }
                    if (sameToolRounds >= 6 && (round + 1) - lastBatchHintRound >= 6) {
                        lastBatchHintRound = round + 1;
                        text = text + "\n\n（提示：已连续 " + sameToolRounds + " 轮只调用 " + call.name
                                + "。互不依赖的命令请合并进一次调用"
                                + "（phone_sh 的 command 支持用 ; 或 && 串联多条），"
                                + "或在同一轮里同时发出多个工具调用，避免把轮数耗在来回往返上。）";
                    }
                    JSONObject event = new JSONObject();
                    event.put("type", "tool_result");
                    event.put("id", call.id);
                    event.put("name", call.name);
                    event.put("content", abbreviate(text, MAX_TOOL_RESULT_CHARS));
                    event.put("isError", isError);
                    event.put("elapsedMs", elapsed);
                    event.put("round", round + 1);
                    event.put("parallelGroup", 0);
                    event.put("cached", cachedHit);
                    if (imageBase64 != null) {
                        event.put("image", true);
                    }
                    emit(event);
                    Diagnostics.event("tool_result", call.name + " error=" + isError);

                    JSONObject block = new JSONObject();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", call.id);
                    if (imageBase64 != null && imageMime != null) {
                        JSONArray content = new JSONArray();
                        JSONObject textBlock = new JSONObject();
                        textBlock.put("type", "text");
                        textBlock.put("text", abbreviate(text, MAX_TOOL_RESULT_CHARS));
                        content.put(textBlock);
                        JSONObject imageBlock = new JSONObject();
                        imageBlock.put("type", "image");
                        imageBlock.put("source", new JSONObject()
                                .put("type", "base64")
                                .put("media_type", imageMime)
                                .put("data", imageBase64));
                        content.put(imageBlock);
                        block.put("content", content);
                    } else {
                        block.put("content", abbreviate(text, MAX_TOOL_RESULT_CHARS));
                    }
                    if (isError) {
                        block.put("is_error", true);
                    }
                    results.put(block);
                }
                JSONObject user = new JSONObject();
                user.put("role", "user");
                user.put("content", results);
                messages.put(user);
            }
            JSONObject notice = new JSONObject();
            notice.put("type", "text");
            notice.put("delta", "\n\n（已达到本轮 " + maxRounds
                    + " 轮上限。发送「继续」可以接着做：上一轮的对话内容、计划与笔记会一起带过去。）");
            emit(notice);
            emitDone("max_tokens");
        } catch (CancelledException error) {
            JSONObject event = new JSONObject();
            try {
                event.put("type", "stopped");
            } catch (Exception ignored) {
                // 不会发生
            }
            emit(event);
        } catch (Exception error) {
            JSONObject event = new JSONObject();
            try {
                event.put("type", "error");
                event.put("message", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            } catch (Exception ignored) {
                // 不会发生
            }
            emit(event);
        } finally {
            activeConnection = null;
            // 正常结束/异常/取消统一收尾：还在跑的子进程（shell / node）整棵杀掉
            try {
                RuntimeTools.cancelAll();
            } catch (Throwable ignored) {
                // cancelAll 自身已兜底
            }
        }
    }

    private void emit(JSONObject event) {
        emitter.emit(event);
    }

    private boolean isDeepseekProvider() {
        return "deepseek".equals(payload.optString("apiProvider", "deepseek"));
    }

    private String anthropicBaseUrl() {
        String configured = payload.optString("apiBaseUrl", "").trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        String origin = payload.optString("apiOrigin", "https://api.deepseek.com").trim();
        while (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length() - 1);
        }
        return origin + "/anthropic/v1";
    }

    /** 统计「连续单工具轮」，给批次提示用；同一轮发了多个工具就清零 */
    private void trackBatch(List<ToolCall> calls) {
        if (calls.size() == 1) {
            String name = calls.get(0).name;
            if (name.equals(lastSingleToolName)) {
                sameToolRounds++;
            } else {
                lastSingleToolName = name;
                sameToolRounds = 1;
            }
        } else {
            lastSingleToolName = "";
            sameToolRounds = 0;
        }
    }

    private boolean canRunParallel(List<ToolCall> calls) {
        if (cancelled || calls.size() < 2) {
            return false;
        }
        Set<String> signatures = new HashSet<>();
        for (ToolCall call : calls) {
            if (!LocalTools.isParallelSafe(call.name)) {
                return false;
            }
            String signature = call.name + "|" + call.arguments;
            if (!signatures.add(signature)) {
                return false;
            }
        }
        return true;
    }

    private void executeParallelRound(int round, RoundResult result, JSONArray messages)
            throws Exception {
        JSONArray results = new JSONArray();
        List<Future<ToolOutcome>> futures = new ArrayList<>();
        List<ToolOutcome> predetermined = new ArrayList<>();
        for (ToolCall call : result.toolCalls) {
            JSONObject start = new JSONObject();
            start.put("type", "tool_start");
            start.put("id", call.id);
            start.put("name", call.name);
            start.put("input", call.arguments.length() == 0 ? "{}" : call.arguments.toString());
            start.put("approval", false);
            start.put("round", round);
            start.put("parallelGroup", round);
            start.put("parallel", true);
            emit(start);
            Diagnostics.event("tool_start", call.name + " parallel");

            CachedTool cached = cached(call);
            if (cached != null) {
                ToolOutcome hit = cached.outcome;
                predetermined.add(new ToolOutcome(
                        hit.text, hit.isError, hit.imageBase64, hit.imageMime, 0, true
                ));
                futures.add(null);
            } else {
                predetermined.add(null);
                futures.add(TOOL_POOL.submit(() -> executeOutcome(call)));
            }
        }

        for (int i = 0; i < result.toolCalls.size(); i++) {
            if (cancelled) {
                throw new CancelledException();
            }
            ToolOutcome outcome;
            Future<ToolOutcome> future = futures.get(i);
            if (future == null) {
                outcome = predetermined.get(i);
            } else {
                try {
                    outcome = future.get(120, TimeUnit.SECONDS);
                } catch (Exception error) {
                    future.cancel(true);
                    outcome = new ToolOutcome(
                            "工具执行失败：" + (error.getMessage() == null
                                    ? error.getClass().getSimpleName() : error.getMessage()),
                            true, null, null, 0, false
                    );
                }
            }
            ToolCall call = result.toolCalls.get(i);
            if (outcome == null) {
                outcome = new ToolOutcome("（工具未返回内容）", true, null, null, 0, false);
            }
            outCache(call, outcome);
            emitAndAppendResult(results, call, outcome, round, round, true);
        }

        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", results);
        messages.put(user);
    }

    private ToolOutcome executeOutcome(ToolCall call) {
        long startedAt = SystemClock.elapsedRealtime();
        try {
            McpClient.ToolResult result = executeTool(call);
            return new ToolOutcome(
                    result.text,
                    result.isError,
                    result.imageBase64,
                    result.imageMime,
                    SystemClock.elapsedRealtime() - startedAt,
                    false
            );
        } catch (Exception error) {
            return new ToolOutcome(
                    "错误：" + (error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage()),
                    true, null, null, SystemClock.elapsedRealtime() - startedAt, false
            );
        }
    }

    private CachedTool cached(ToolCall call) {
        if (!LocalTools.isCacheable(call.name)) {
            return null;
        }
        String key = call.name + "|" + call.arguments;
        CachedTool cached = toolCache.get(key);
        if (cached == null) {
            return null;
        }
        if (cached.expiresAt < SystemClock.elapsedRealtime()) {
            toolCache.remove(key);
            return null;
        }
        return cached;
    }

    private void outCache(ToolCall call, ToolOutcome outcome) {
        if (!LocalTools.isCacheable(call.name) || outcome.isError) {
            return;
        }
        String key = call.name + "|" + call.arguments;
        toolCache.put(key, new CachedTool(outcome, SystemClock.elapsedRealtime() + TOOL_CACHE_MS));
    }

    private void emitAndAppendResult(
            JSONArray results,
            ToolCall call,
            ToolOutcome outcome,
            int round,
            int parallelGroup,
            boolean parallel
    ) throws Exception {
        JSONObject event = new JSONObject();
        event.put("type", "tool_result");
        event.put("id", call.id);
        event.put("name", call.name);
        event.put("content", abbreviate(outcome.text, MAX_TOOL_RESULT_CHARS));
        event.put("isError", outcome.isError);
        event.put("elapsedMs", outcome.elapsedMs);
        event.put("round", round);
        event.put("parallelGroup", parallelGroup);
        event.put("parallel", parallel);
        event.put("cached", outcome.cached);
        if (outcome.imageBase64 != null) {
            event.put("image", true);
        }
        emit(event);
        Diagnostics.event("tool_result", call.name + " error=" + outcome.isError);

        JSONObject block = new JSONObject();
        block.put("type", "tool_result");
        block.put("tool_use_id", call.id);
        if (outcome.imageBase64 != null && outcome.imageMime != null) {
            JSONArray content = new JSONArray();
            JSONObject textBlock = new JSONObject();
            textBlock.put("type", "text");
            textBlock.put("text", abbreviate(outcome.text, MAX_TOOL_RESULT_CHARS));
            content.put(textBlock);
            JSONObject imageBlock = new JSONObject();
            imageBlock.put("type", "image");
            imageBlock.put("source", new JSONObject()
                    .put("type", "base64")
                    .put("media_type", outcome.imageMime)
                    .put("data", outcome.imageBase64));
            content.put(imageBlock);
            block.put("content", content);
        } else {
            block.put("content", abbreviate(outcome.text, MAX_TOOL_RESULT_CHARS));
        }
        if (outcome.isError) {
            block.put("is_error", true);
        }
        results.put(block);
    }

    private void emitDone(String stopReason) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "done");
            event.put("stopReason", stopReason);
            emit(event);
        } catch (Exception ignored) {
            // 不会发生
        }
    }

    private JSONArray buildMessages() throws Exception {
        JSONArray messages = new JSONArray();
        JSONArray source = payload.optJSONArray("messages");
        if (source == null) {
            return messages;
        }
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String role = item.optString("role");
            Object content = item.opt("content");
            JSONObject message = new JSONObject();
            message.put("role", "assistant".equals(role) ? "assistant" : "user");
            if (content instanceof JSONArray) {
                if (((JSONArray) content).length() == 0) {
                    continue;
                }
                message.put("content", content);
            } else {
                String text = item.optString("content");
                if (text.isEmpty()) {
                    continue;
                }
                message.put("content", text);
            }
            messages.put(message);
        }
        return messages;
    }

    /** 连接配置里启用的 MCP Server，拉工具并建立 函数名 → 真实工具 的映射 */
    private JSONArray connectTools() throws Exception {
        JSONArray tools = new JSONArray();
        JSONArray serverIds = payload.optJSONArray("mcpServerIds");
        if (serverIds == null) {
            serverIds = new JSONArray();
        }
        for (int i = 0; i < serverIds.length(); i++) {
            String serverId = serverIds.optString(i);
            JSONObject server = McpStore.find(context, serverId);
            if (server == null) {
                continue;
            }
            String url = server.optString("url");
            if (url.isEmpty()) {
                continue;
            }
            try {
                McpClient client = new McpClient(url, server.optString("token"), 20000);
                client.initialize();
                clientByServer.put(serverId, client);
                JSONArray list = client.listTools();
                String serverName = server.optString("name", serverId);
                for (int index = 0; index < list.length(); index++) {
                    JSONObject tool = list.optJSONObject(index);
                    if (tool == null) {
                        continue;
                    }
                    String toolName = tool.optString("name");
                    if (toolName.isEmpty()) {
                        continue;
                    }
                    String functionName = uniqueFunctionName(serverId, toolName);
                    toolMap.put(functionName, new ToolRef(serverId, toolName));
                    if (isRiskyTool(tool, toolName)) {
                        riskyTools.add(functionName);
                    }
                    JSONObject schema = tool.optJSONObject("inputSchema");
                    if (schema == null) {
                        schema = new JSONObject();
                    }
                    if (!schema.has("type")) {
                        schema.put("type", "object");
                    }
                    JSONObject spec = new JSONObject();
                    spec.put("name", functionName);
                    String description = tool.optString("description", "");
                    spec.put("description", description + "\n\n（来自 MCP Server「" + serverName + "」的工具 " + toolName + "）");
                    spec.put("input_schema", schema);
                    tools.put(spec);
                }
            } catch (Exception error) {
                JSONObject status = new JSONObject();
                status.put("type", "status");
                status.put("text", "MCP「" + server.optString("name", serverId) + "」连接失败：" + error.getMessage());
                emit(status);
            }
        }
        if (tools.length() > 0) {
            JSONObject status = new JSONObject();
            status.put("type", "status");
            status.put("text", "已连接 " + clientByServer.size() + " 个 MCP Server，共 " + tools.length() + " 个工具");
            emit(status);
        }
        // 能力探测：三条后端互相独立——
        // - 本机终端 phone_sh 永远可用（app 身份）；
        // - 无障碍决定读屏/截图/点按文字；
        // - Shizuku 只决定 pm/am/settings 这类特权命令。
        ShizukuShell.init(context);
        boolean shizukuReady = ShizukuShell.running() && ShizukuShell.granted();
        boolean accessibilityReady = ChatDemoAccessibilityService.ready();
        RuntimeEnv.ensureLinks(context);
        int coreCount = 0;
        // 本地工具按可用性裁剪：
        // - 需要无障碍/Shizuku 的只在 Shizuku 就绪时出现；
        // - 纯文件类（读图/读文本）不依赖 Shizuku，没装 Shizuku 的手机也要能用，
        //   否则「看看这张图」会直接变成「没有可用工具」。
        JSONArray local = LocalTools.specs();
        for (int i = 0; i < local.length(); i++) {
            JSONObject spec = local.optJSONObject(i);
            if (spec == null) {
                continue;
            }
            String name = spec.optString("name");
            if (name.isEmpty()) {
                continue;
            }
            if (!toolAvailable(name, accessibilityReady, shizukuReady)) {
                continue;
            }
            localCatalog.put(name, spec);
            if (CORE_LOCAL.contains(name)) {
                tools.put(spec);
                coreCount++;
            }
        }
        // 内置运行时（shell / Python / Node）：不依赖任何外部应用，始终注册。
        // phone_sh 在核心工具里，phone_py / phone_js 由 tool_search 按需发现。
        JSONArray runtime = RuntimeTools.specs();
        for (int i = 0; i < runtime.length(); i++) {
            JSONObject spec = runtime.optJSONObject(i);
            if (spec == null) {
                continue;
            }
            String name = spec.optString("name");
            if (name.isEmpty()) {
                continue;
            }
            localCatalog.put(name, spec);
            if (CORE_LOCAL.contains(name)) {
                tools.put(spec);
                coreCount++;
            }
        }
        tools.put(toolSearchSpec());
        JSONObject localStatus = new JSONObject();
        localStatus.put("type", "status");
        StringBuilder statusText = new StringBuilder("核心工具 ").append(coreCount).append(" 个已加载");
        statusText.append(accessibilityReady
                ? "；无障碍已开启（读屏/截图可用）"
                : "；无障碍未开启（读屏/截图不可用，需在系统设置 → 无障碍里开启 DLA）");
        statusText.append(shizukuReady
                ? "；Shizuku 已就绪（特权命令可用）"
                : "；Shizuku 未就绪（pm/am/settings 不可用，不影响读屏/点击/命令）");
        statusText.append("；").append(RuntimeTools.statusLine(context));
        statusText.append("；文件、设置、剪贴板、zip、docx 与 Python/Node 脚本按需 tool_search 发现");
        localStatus.put("text", statusText.toString());
        emit(localStatus);
        // 官方联网：/v1/tools/search 与 /v1/tools/fetch
        JSONArray web = WebTools.specs();
        for (int i = 0; i < web.length(); i++) {
            tools.put(web.optJSONObject(i));
        }
        // 长任务记忆
        JSONArray task = TaskTools.specs();
        for (int i = 0; i < task.length(); i++) {
            tools.put(task.optJSONObject(i));
        }
        return tools;
    }

    /** 只暴露工具发现入口；工具 schema 不在初始上下文里全部注入。 */
    private JSONObject toolSearchSpec() throws Exception {
        JSONObject properties = new JSONObject();
        properties.put("query", new JSONObject()
                .put("type", "string")
                .put("description", "能力关键词，例如 文件/zip/设置/剪贴板/通知/截图/文档"));
        properties.put("limit", new JSONObject()
                .put("type", "number")
                .put("description", "最多返回几个工具，默认 5，最大 8"));
        return new JSONObject()
                .put("name", "tool_search")
                .put("description",
                        "搜索 DLA 的扩展工具目录。核心工具（读屏/截图/点击/输入/看图/本机终端）已经在列表里；"
                                + "需要文件读写、设置、剪贴板、通知、zip、docx，或 Python/Node 脚本时先搜索"
                                + "（关键词示例：文件/目录/读取/写入、应用、设置、剪贴板、通知、zip、docx、"
                                + "python/脚本、js/node、搜索/rg）。"
                                + "返回匹配工具的说明和参数，下一轮这些工具会加入可用列表。"
                                + "搜索不执行任何操作；搜不到就说明当前后端不可用，直接告诉用户缺什么。")
                .put("input_schema", new JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", new JSONArray().put("query")));
    }

    private void appendDiscoveredTools(JSONArray tools) throws Exception {
        if (discoveredLocal.isEmpty()) {
            return;
        }
        Set<String> present = new HashSet<>();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject item = tools.optJSONObject(i);
            if (item != null) {
                present.add(item.optString("name"));
            }
        }
        for (String name : discoveredLocal) {
            if (present.contains(name)) {
                continue;
            }
            JSONObject spec = localCatalog.get(name);
            if (spec != null) {
                tools.put(spec);
                present.add(name);
            }
        }
    }

    private McpClient.ToolResult executeToolSearch(JSONObject arguments) throws Exception {
        String query = arguments.optString("query", "").trim().toLowerCase();
        if (query.isEmpty()) {
            return new McpClient.ToolResult(
                    "请给出关键词，例如：文件、zip、设置、剪贴板、通知、截图、文档。", true);
        }
        int limit = Math.max(1, Math.min(arguments.optInt("limit", 5), 8));
        String[] words = query.split("\\s+");
        List<JSONObject> matches = new ArrayList<>();
        for (JSONObject spec : localCatalog.values()) {
            String name = spec.optString("name", "").toLowerCase();
            String description = spec.optString("description", "").toLowerCase();
            boolean matched = false;
            for (String word : words) {
                if (!word.isEmpty() && (name.contains(word) || description.contains(word))) {
                    matched = true;
                    break;
                }
            }
            if (matched) {
                matches.add(spec);
            }
        }
        if (matches.isEmpty()) {
            return new McpClient.ToolResult(
                    "没有匹配「" + query + "」的扩展工具。可试：命令/终端、python、js、文件、目录、读取、写入、"
                            + "替换、应用、设置、剪贴板、通知、zip、docx、截图。", false);
        }
        StringBuilder text = new StringBuilder("匹配 " + matches.size()
                + " 个工具（已在下一轮加入可用列表）：\n");
        int count = 0;
        for (JSONObject spec : matches) {
            if (count >= limit) {
                break;
            }
            String name = spec.optString("name");
            if (CORE_LOCAL.contains(name)) {
                continue;
            }
            discoveredLocal.add(name);
            count++;
            text.append("- ").append(name).append("：")
                    .append(spec.optString("description", "")).append('\n');
            JSONObject schema = spec.optJSONObject("input_schema");
            JSONObject properties = schema == null ? null : schema.optJSONObject("properties");
            if (properties != null) {
                JSONArray names = properties.names();
                if (names != null) {
                    text.append("  参数：");
                    for (int i = 0; i < names.length(); i++) {
                        if (i > 0) {
                            text.append(", ");
                        }
                        text.append(names.optString(i));
                    }
                    text.append('\n');
                }
            }
        }
        if (count == 0) {
            return new McpClient.ToolResult("匹配到的工具已经是核心工具，可直接调用。", false);
        }
        return new McpClient.ToolResult(text.toString().trim(), false);
    }

    private String uniqueFunctionName(String serverId, String toolName) {
        String base = sanitize(serverId) + "_" + sanitize(toolName);
        if (base.length() > 56) {
            base = base.substring(0, 56);
        }
        String name = base;
        int suffix = 2;
        while (toolMap.containsKey(name)) {
            name = base + "_" + suffix++;
        }
        return name;
    }

    private static String sanitize(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            builder.append((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') ? ch : '_');
        }
        return builder.length() == 0 ? "tool" : builder.toString();
    }

    /** 运行开始时实测一次：给模型权威的能力状态，省得它用 ps / 端口去猜 */
    private String runtimeBlock() {
        boolean accessibility = false;
        try {
            accessibility = AccessibilityStatus.isEnabled(context);
        } catch (Throwable ignored) {
            // 读不到就按未开启处理
        }
        boolean shizuku = false;
        try {
            shizuku = ShizukuShell.running() && ShizukuShell.granted();
        } catch (Throwable ignored) {
            // 没有 Shizuku 或版本不支持
        }
        StringBuilder text = new StringBuilder("【本机运行时（每次运行开始时实测，可直接采信）】\n");
        text.append("- 无障碍服务：")
                .append(accessibility
                        ? "已开启（屏幕 / 点按类工具可用）"
                        : "未开启（屏幕 / 点按类工具不可用，需要用户去 系统设置 → 无障碍 开启 DLA）")
                .append('\n');
        text.append("- Shizuku：")
                .append(shizuku
                        ? "已就绪（phone_shell 可用，uid 2000）"
                        : "未就绪（phone_shell 不在工具列表；pm / am / settings / dumpsys 不可用）")
                .append('\n');
        text.append("- 内置运行时：phone_sh / phone_py / phone_js 都是应用身份（无特权）");
        return text.toString();
    }

    /** 长任务状态注入 system：计划与关键笔记不会被上下文裁剪掉 */
    private String buildSystem() {
        String base = payload.optString("system", "");
        if (base.trim().isEmpty()) {
            base = "你是 DLA（DeepSeek LLM Agent），运行在用户 Android 手机上的本地智能体。"
                    + "你是 AI，不是人类，也没有脱离工具的真实感知；只能通过工具结果了解设备与环境。"
                    + "具体工具以当前 tools 列表为准，不要臆造操作结果。\n";
        }
        StringBuilder builder = new StringBuilder(base);
        builder.append("\n\n").append(TOOL_MAP);
        if (!runtimeText.isEmpty()) {
            builder.append("\n\n").append(runtimeText);
        }
        if (plan.length() > 0) {
            builder.append("\n\n【当前任务计划】\n");
            for (int i = 0; i < plan.length(); i++) {
                int step = i + 1;
                builder.append(step).append(". ");
                if (step < planCurrent) {
                    builder.append("[已完成] ");
                } else if (step == planCurrent) {
                    builder.append("[进行中] ");
                }
                builder.append(plan.optString(i));
                builder.append('\n');
            }
        }
        if (notes.length() > 0) {
            builder.append("\n【任务笔记】\n");
            for (int i = 0; i < notes.length(); i++) {
                builder.append("- ").append(notes.optString(i)).append('\n');
            }
        }
        // 放最后：模型对末尾指令更敏感
        builder.append("\n\n").append(SELF_CHECK);
        return builder.toString();
    }

    private McpClient.ToolResult executeTaskTool(String name, JSONObject arguments) throws Exception {
        if ("task_plan".equals(name)) {
            JSONArray steps = arguments.optJSONArray("steps");
            if (steps == null) {
                return new McpClient.ToolResult("task_plan 需要 steps 数组", true);
            }
            JSONArray next = new JSONArray();
            for (int i = 0; i < steps.length() && next.length() < MAX_STEPS_IN_PLAN; i++) {
                String step = steps.optString(i).trim();
                if (!step.isEmpty()) {
                    next.put(step);
                }
            }
            plan = next;
            planCurrent = Math.min(Math.max(arguments.optInt("current", 1), 1),
                    Math.max(1, plan.length()));
            emitTaskState();
            StringBuilder text = new StringBuilder("计划已更新（共 " + plan.length() + " 步）：\n");
            for (int i = 0; i < plan.length(); i++) {
                text.append(i + 1).append(". ").append(plan.optString(i));
                if (i + 1 == planCurrent) {
                    text.append("  ← 当前");
                }
                text.append('\n');
            }
            return new McpClient.ToolResult(text.toString(), false);
        }
        String note = arguments.optString("text").trim();
        if (note.isEmpty()) {
            return new McpClient.ToolResult("task_note 需要 text", true);
        }
        if (note.length() > MAX_NOTE_CHARS) {
            note = note.substring(0, MAX_NOTE_CHARS);
        }
        JSONArray next = new JSONArray();
        for (int i = 0; i < notes.length(); i++) {
            next.put(notes.optString(i));
        }
        next.put(note);
        while (next.length() > MAX_NOTES || totalChars(next) > 12000) {
            JSONArray trimmed = new JSONArray();
            for (int i = 1; i < next.length(); i++) {
                trimmed.put(next.optString(i));
            }
            next = trimmed;
        }
        notes = next;
        emitTaskState();
        return new McpClient.ToolResult("已记录笔记（共 " + notes.length() + " 条）", false);
    }

    private static int totalChars(JSONArray array) {
        int total = 0;
        for (int i = 0; i < array.length(); i++) {
            total += array.optString(i).length();
        }
        return total;
    }

    private void emitTaskState() throws Exception {
        JSONObject event = new JSONObject();
        event.put("type", "task_state");
        event.put("plan", plan);
        event.put("current", planCurrent);
        event.put("notes", notes);
        emit(event);
    }

    private McpClient.ToolResult executeTool(ToolCall call) throws Exception {
        if ("tool_search".equals(call.name)) {
            JSONObject arguments = call.arguments.length() == 0
                    ? new JSONObject() : new JSONObject(call.arguments.toString());
            return executeToolSearch(arguments);
        }
        if (RuntimeTools.isRuntime(call.name)) {
            JSONObject arguments = call.arguments.length() == 0
                    ? new JSONObject() : new JSONObject(call.arguments.toString());
            return RuntimeTools.execute(context, call.name, arguments);
        }
        if (TaskTools.isTask(call.name)) {
            JSONObject arguments = call.arguments.length() == 0
                    ? new JSONObject()
                    : new JSONObject(call.arguments.toString());
            return executeTaskTool(call.name, arguments);
        }
        if (LocalTools.isLocal(call.name)) {
            JSONObject arguments = call.arguments.length() == 0
                    ? new JSONObject()
                    : new JSONObject(call.arguments.toString());
            LocalTools.Result result = LocalTools.execute(context, call.name, arguments);
            return new McpClient.ToolResult(
                    result.text,
                    result.isError,
                    result.imageBase64,
                    result.imageMime
            );
        }
        if (WebTools.isWeb(call.name)) {
            JSONObject arguments = call.arguments.length() == 0
                    ? new JSONObject()
                    : new JSONObject(call.arguments.toString());
            LocalTools.Result result = WebTools.execute(
                    payload.optString("apiKey"),
                    payload.optString("apiOrigin"),
                    call.name,
                    arguments
            );
            return new McpClient.ToolResult(result.text, result.isError);
        }
        ToolRef reference = toolMap.get(call.name);
        if (reference == null) {
            throw new IOException("没有名为 " + call.name + " 的工具");
        }
        McpClient client = clientByServer.get(reference.serverId);
        if (client == null) {
            throw new IOException("MCP Server 未连接：" + reference.serverId);
        }
        JSONObject arguments = call.arguments.length() == 0 ? new JSONObject() : new JSONObject(call.arguments.toString());
        return client.callTool(reference.toolName, arguments);
    }

    private JSONObject assistantMessage(RoundResult result) throws Exception {
        JSONArray content = new JSONArray();
        if (result.text.toString().trim().length() > 0) {
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", result.text.toString());
            content.put(text);
        }
        for (ToolCall call : result.toolCalls) {
            JSONObject block = new JSONObject();
            block.put("type", "tool_use");
            block.put("id", call.id);
            block.put("name", call.name);
            block.put("input", call.arguments.length() == 0 ? new JSONObject() : new JSONObject(call.arguments.toString()));
            content.put(block);
        }
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", content);
        return message;
    }

    /**
     * 控制上下文体积：只保留最近 2 张截图，较早的工具结果截断到 2000 字符。
     * 这样把轮数上限提上去后，长任务不会因为工具结果/截图堆积而超出上下文。
     */
    private void trimMessages(JSONArray messages) throws Exception {
        int toolResults = 0;
        int[] images = {0};
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            Object content = message.opt("content");
            if (!(content instanceof JSONArray)) {
                continue;
            }
            JSONArray blocks = (JSONArray) content;
            for (int j = blocks.length() - 1; j >= 0; j--) {
                JSONObject block = blocks.optJSONObject(j);
                if (block == null || !"tool_result".equals(block.optString("type"))) {
                    continue;
                }
                toolResults++;
                trimToolResult(block, toolResults > 8, images);
            }
        }
    }

    private void trimToolResult(JSONObject block, boolean old, int[] images) throws Exception {
        Object content = block.opt("content");
        if (content instanceof String) {
            String text = (String) content;
            if (old && text.length() > 2000) {
                block.put("content", text.substring(0, 2000) + "\n…（较早结果已截断）");
            }
            return;
        }
        if (!(content instanceof JSONArray)) {
            return;
        }
        JSONArray parts = (JSONArray) content;
        for (int i = parts.length() - 1; i >= 0; i--) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) {
                continue;
            }
            String type = part.optString("type");
            if ("image".equals(type)) {
                images[0]++;
                if (images[0] > 2) {
                    JSONObject note = new JSONObject();
                    note.put("type", "text");
                    note.put("text", "（较早的截图已省略，必要时重新截图）");
                    parts.put(i, note);
                }
            } else if ("text".equals(type) && old) {
                String text = part.optString("text");
                if (text.length() > 2000) {
                    part.put("text", text.substring(0, 2000) + "\n…（较早结果已截断）");
                }
            }
        }
    }

    private RoundResult streamRound(JSONArray messages, JSONArray tools) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", payload.optString("model", "deepseek-flash"));
        body.put("max_tokens", 8192);
        body.put("system", buildSystem());
        body.put("messages", messages);
        body.put("stream", true);
        body.put("temperature", 1.0);
        if (tools.length() > 0) {
            body.put("tools", tools);
        }

        String baseUrl = anthropicBaseUrl();
        URL url = new URL(baseUrl + "/messages");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(300000);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("x-api-key", payload.optString("apiKey"));
        connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
        if (isDeepseekProvider() && body.toString().contains("\"file_id\"")) {
            connection.setRequestProperty("anthropic-beta", "files-api-2025-04-14");
        }
        activeConnection = connection;

        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        if (status >= 400) {
            String text = readAll(connection.getErrorStream());
            throw new IOException("HTTP " + status + (text.isEmpty() ? "" : "：" + abbreviate(text, 800)));
        }
        String contentType = connection.getContentType();
        if (contentType == null || !contentType.contains("text/event-stream")) {
            String text = readAll(connection.getInputStream());
            throw new IOException("服务端没有返回 SSE（" + contentType + "）：" + abbreviate(text, 500));
        }

        RoundResult result = new RoundResult();
        Map<Integer, ToolCall> callsByIndex = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            String eventName = "message";
            StringBuilder data = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (cancelled) {
                    throw new CancelledException();
                }
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        handleEvent(eventName, data.toString(), result, callsByIndex);
                    }
                    data.setLength(0);
                    eventName = "message";
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).trim());
                }
            }
            if (data.length() > 0) {
                handleEvent(eventName, data.toString(), result, callsByIndex);
            }
        } catch (IOException streamError) {
            if (cancelled) {
                throw new CancelledException();
            }
            throw streamError;
        } finally {
            connection.disconnect();
            activeConnection = null;
        }
        return result;
    }

    private void handleEvent(String eventName, String data, RoundResult result, Map<Integer, ToolCall> callsByIndex) throws Exception {
        JSONObject root;
        try {
            root = new JSONObject(data);
        } catch (Exception error) {
            return;
        }
        String type = root.optString("type", eventName);
        if ("error".equals(type)) {
            JSONObject error = root.optJSONObject("error");
            throw new IOException(error == null ? data : error.optString("message", data));
        }
        switch (type) {
            case "message_start": {
                JSONObject usage = root.optJSONObject("message") == null ? null : root.getJSONObject("message").optJSONObject("usage");
                if (usage != null) {
                    result.inputTokens = usage.optInt("input_tokens", 0);
                    result.cacheCreationInputTokens = usage.optInt("cache_creation_input_tokens", 0);
                    result.cacheReadInputTokens = usage.optInt("cache_read_input_tokens", 0);
                }
                break;
            }
            case "content_block_start": {
                JSONObject block = root.optJSONObject("content_block");
                if (block != null && "tool_use".equals(block.optString("type"))) {
                    ToolCall call = new ToolCall(block.optString("id"), block.optString("name"));
                    callsByIndex.put(root.optInt("index", result.toolCalls.size()), call);
                    result.toolCalls.add(call);
                }
                break;
            }
            case "content_block_delta": {
                JSONObject delta = root.optJSONObject("delta");
                if (delta == null) {
                    break;
                }
                String deltaType = delta.optString("type");
                if ("text_delta".equals(deltaType)) {
                    String text = delta.optString("text");
                    if (result.firstTokenAt == 0 && !text.isEmpty()) {
                        result.firstTokenAt = SystemClock.elapsedRealtime();
                    }
                    result.text.append(text);
                    JSONObject event = new JSONObject();
                    event.put("type", "text");
                    event.put("delta", text);
                    emit(event);
                } else if ("thinking_delta".equals(deltaType)) {
                    JSONObject event = new JSONObject();
                    event.put("type", "thinking");
                    event.put("delta", delta.optString("thinking"));
                    emit(event);
                } else if ("input_json_delta".equals(deltaType)) {
                    ToolCall call = callsByIndex.get(root.optInt("index", -1));
                    if (call != null) {
                        call.arguments.append(delta.optString("partial_json"));
                    }
                }
                break;
            }
            case "message_delta": {
                JSONObject delta = root.optJSONObject("delta");
                if (delta != null) {
                    String stop = delta.optString("stop_reason", "");
                    if (!stop.isEmpty()) {
                        result.stopReason = stop;
                    }
                }
                JSONObject usage = root.optJSONObject("usage");
                if (usage != null) {
                    result.outputTokens = usage.optInt("output_tokens", result.outputTokens);
                    result.cacheCreationInputTokens = usage.optInt(
                            "cache_creation_input_tokens",
                            result.cacheCreationInputTokens
                    );
                    result.cacheReadInputTokens = usage.optInt(
                            "cache_read_input_tokens",
                            result.cacheReadInputTokens
                    );
                }
                break;
            }
            case "message_stop": {
                JSONObject event = new JSONObject();
                event.put("type", "usage");
                event.put("inputTokens", result.inputTokens);
                event.put("outputTokens", result.outputTokens);
                event.put("cacheCreationInputTokens", result.cacheCreationInputTokens);
                event.put("cacheReadInputTokens", result.cacheReadInputTokens);
                event.put("elapsedMs", SystemClock.elapsedRealtime() - result.startedAt);
                event.put("ttftMs", result.firstTokenAt > 0
                        ? result.firstTokenAt - result.startedAt : 0);
                emit(event);
                break;
            }
            default:
                break;
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String abbreviate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "\n…（已截断）";
    }
}

