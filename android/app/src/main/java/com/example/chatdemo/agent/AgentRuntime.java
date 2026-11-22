package com.example.chatdemo.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.example.chatdemo.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 进程内 Agent 运行状态：同一时间只跑一轮。
 * 事件先进缓冲供网页重连回放，再推给当前监听者（WebView）。
 */
public final class AgentRuntime {

    public interface Listener {
        void onEvent(String requestId, JSONObject event);
    }

    private static final int MAX_BUFFER = 2000;
    private static final AgentRuntime INSTANCE = new AgentRuntime();

    public static AgentRuntime get() {
        return INSTANCE;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<JSONObject> buffer = new ArrayList<>();

    private String requestId;
    private boolean running;
    private boolean cancelled;
    private Thread worker;
    private AgentRunner runner;
    private Listener listener;
    private Context appContext;
    private PowerManager.WakeLock wakeLock;
    private boolean preserveCheckpoint;
    private static final long WAKE_IDLE_MS = BuildConfig.DEBUG ? 20_000L : 5 * 60 * 1000L;
    private final Runnable wakeLockRelease = this::releaseWakeLock;

    private AgentRuntime() {}

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized String currentRequestId() {
        return requestId;
    }

    public void start(Context context, String id, String payloadJson) {
        synchronized (this) {
            if (running) {
                emitNow(id, errorEvent("Agent 正在运行，请先停止当前任务"));
                return;
            }
            if (!ShizukuShell.running() || !ShizukuShell.granted()) {
                emitNow(id, errorEvent(
                        "Agent 需要 Shizuku 运行且已授权；请到 设置 → Agent → 手机能力 启动并授权后重试"
                ));
                return;
            }
            running = true;
            cancelled = false;
            preserveCheckpoint = false;
            requestId = id;
            buffer.clear();
        }
        JSONObject payload;
        try {
            payload = new JSONObject(payloadJson);
        } catch (Exception error) {
            emitNow(id, errorEvent("参数不是合法 JSON"));
            finish();
            return;
        }
        appContext = context.getApplicationContext();
        if (!payload.optBoolean("resumeTaskState", false)) {
            AgentCheckpoint.clearTaskState(appContext);
        }
        AgentCheckpoint.save(appContext, id, payloadJson);
        AgentKeepAlive.scheduleHealth(appContext);
        try {
            AgentService.start(context);
        } catch (Throwable error) {
            preserveCheckpoint = true;
            emitNow(id, errorEvent("无法启动前台服务："
                    + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())));
            finish();
            return;
        }
        touchWakeLock(context);
        runner = new AgentRunner(context, payload, event -> emit(id, event));
        worker = new Thread(() -> {
            try {
                runner.run();
            } catch (Exception error) {
                emit(id, errorEvent(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            } finally {
                finish();
                AgentService.stop(context);
            }
        }, "dla-agent");
        worker.start();
    }

    public void stop(String id) {
        AgentRunner current;
        Thread thread;
        synchronized (this) {
            if (!running || (id != null && !id.equals(requestId))) {
                return;
            }
            cancelled = true;
            current = runner;
            thread = worker;
        }
        if (current != null) {
            current.cancel();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** 前台服务超时兜底：停掉当前循环，保留 checkpoint 供恢复 */
    public void onServiceTimeout() {
        android.util.Log.e("AgentRuntime", "foreground service timeout; stopping current run");
        Diagnostics.event("service_timeout", "foreground service timeout");
        preserveCheckpoint = true;
        stop(currentRequestId());
    }

    /** WebView 渲染进程崩溃：停止当前任务，避免主进程被 RemoteServiceException 拖死 */
    public void onRendererGone() {
        Diagnostics.event("renderer_gone", "stopping active agent run");
        if (appContext != null) {
            AgentService.update(appContext, "渲染进程崩溃，任务已停止");
        }
        stop(currentRequestId());
    }

    /** 开机/健康检查时恢复未完成任务（从原始请求重新执行） */
    public boolean resumeIfNeeded(Context context) {
        synchronized (this) {
            if (running) {
                return false;
            }
        }
        if (!AgentCheckpoint.hasPending(context)) {
            return false;
        }
        String id = AgentCheckpoint.requestId(context);
        String payload = AgentCheckpoint.payload(context);
        if (id == null || payload == null) {
            AgentCheckpoint.clear(context);
            return false;
        }
        try {
            new JSONObject(payload);
        } catch (Exception error) {
            AgentCheckpoint.clear(context);
            return false;
        }
        try {
            JSONObject resume = new JSONObject(payload);
            JSONObject state = AgentCheckpoint.taskState(context);
            if (state != null) {
                resume.put("resumeTaskState", true);
                if (state.optJSONArray("plan") != null) {
                    resume.put("resumePlan", state.optJSONArray("plan"));
                }
                resume.put("resumePlanCurrent", state.optInt("current", 1));
                if (state.optJSONArray("notes") != null) {
                    resume.put("resumeNotes", state.optJSONArray("notes"));
                }
            }
            start(context, id, resume.toString());
        } catch (Exception error) {
            AgentCheckpoint.clear(context);
            return false;
        }
        return true;
    }

    /** 网页审批：把结果交给正在等待的工具调用 */
    public void approve(String id, String toolId, boolean allowed) {
        AgentRunner current;
        synchronized (this) {
            if (!running || (id != null && !id.equals(requestId))) {
                return;
            }
            current = runner;
        }
        if (current != null) {
            current.approve(toolId, allowed);
        }
    }

    /** 网页恢复时按游标回放；游标为已消费的事件数 */
    public String attach(String id, int cursor) {
        JSONObject out = new JSONObject();
        JSONArray events = new JSONArray();
        synchronized (this) {
            if (id == null || id.equals(requestId)) {
                for (int i = Math.max(0, cursor); i < buffer.size(); i++) {
                    events.put(buffer.get(i));
                }
            }
            try {
                out.put("cursor", buffer.size());
                out.put("running", running);
                out.put("events", events);
            } catch (Exception ignored) {
                // 不会发生
            }
        }
        return out.toString();
    }

    private void emit(String id, JSONObject event) {
        if ("task_state".equals(event.optString("type")) && appContext != null) {
            AgentCheckpoint.saveTaskState(appContext, event);
        }
        synchronized (this) {
            buffer.add(event);
            while (buffer.size() > MAX_BUFFER) {
                buffer.remove(0);
            }
        }
        // 有事件就续命；空闲 5 分钟自动释放，避免长期持有 wakelock
        touchWakeLock(appContext);
        updateNotification(event);
        mainHandler.post(() -> {
            Listener current;
            synchronized (AgentRuntime.this) {
                current = listener;
            }
            if (current != null) {
                current.onEvent(id, event);
            }
        });
    }

    /** 前台服务通知栏显示当前工具/状态，切到别的 App 也能看到进度 */
    private void updateNotification(JSONObject event) {
        Context context = appContext;
        if (context == null) {
            return;
        }
        String type = event.optString("type");
        if ("tool_start".equals(type)) {
            AgentService.update(context, "执行：" + event.optString("name"));
        } else if ("status".equals(type)) {
            AgentService.update(context, event.optString("text"));
        } else if ("done".equals(type)) {
            AgentService.update(context, "任务完成");
        } else if ("error".equals(type)) {
            AgentService.update(context, "任务失败：" + event.optString("message"));
        }
    }

    private void emitNow(String id, JSONObject event) {
        emit(id, event);
    }

    private void finish() {
        releaseWakeLock();
        if (appContext != null) {
            if (preserveCheckpoint) {
                AgentKeepAlive.scheduleHealth(appContext);
            } else {
                AgentCheckpoint.clear(appContext);
                AgentKeepAlive.cancelHealth(appContext);
            }
        }
        synchronized (this) {
            running = false;
            cancelled = false;
            requestId = null;
            runner = null;
            worker = null;
        }
    }

    /** 有事件/任务时持有唤醒锁；5 分钟无事件自动释放，系统硬超时 6 分钟兜底 */
    private void touchWakeLock(Context context) {
        if (context == null) {
            return;
        }
        try {
            synchronized (this) {
                if (wakeLock == null) {
                    PowerManager manager =
                            (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (manager == null) {
                        return;
                    }
                    wakeLock = manager.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "chatdemo:agent"
                    );
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(WAKE_IDLE_MS + 60 * 1000L);
                }
            }
            mainHandler.removeCallbacks(wakeLockRelease);
            mainHandler.postDelayed(wakeLockRelease, WAKE_IDLE_MS);
        } catch (Throwable ignored) {
            // 唤醒锁失败不影响任务
        }
    }

    private void releaseWakeLock() {
        mainHandler.removeCallbacks(wakeLockRelease);
        try {
            synchronized (this) {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
                wakeLock = null;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static JSONObject errorEvent(String message) {
        JSONObject event = new JSONObject();
        try {
            event.put("type", "error");
            event.put("message", message);
        } catch (Exception ignored) {
            // 不会发生
        }
        return event;
    }
}
