package com.example.chatdemo.agent;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 的客户端门面。
 *
 * 真正的 {@link AgentRuntime} 跑在 {@code :agent} 进程的 {@link AgentService} 里；
 * UI 进程（原生界面或网页壳）通过这里发指令、收事件。UI 崩溃/重建后重新 bind 即可继续。
 */
public final class AgentClient {

    private static final String TAG = "AgentClient";
    /** attach 同步等待回包的上限；超时返回空串，网页按“暂无回放”处理 */
    private static final long ATTACH_TIMEOUT_MS = 600L;
    /**
     * 请求体超过这个字符数就改走文件。
     *
     * Binder 一次事务只有 ~1 MB，且 Parcel 写字符串按 UTF-16（2 字节/字符），
     * 所以 12 万字符已经接近 240 KB，留足余量；图片 base64 单张就有 30 万字符左右，
     * 两张就会撞上限——这正是「多图空转」的根。
     */
    private static final int INLINE_PAYLOAD_LIMIT = 120_000;

    public interface Listener {
        void onEvent(String requestId, JSONObject event);
    }

    private static volatile AgentClient instance;

    public static AgentClient get() {
        if (instance == null) {
            synchronized (AgentClient.class) {
                if (instance == null) instance = new AgentClient();
            }
        }
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Deque<Message> pending = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    /** attach 关联号 -> 等待者；同一个客户端可能并发发起多次回放请求 */
    private final AtomicInteger attachToken = new AtomicInteger(100);
    private final ConcurrentHashMap<Integer, AttachWait> attachWaits = new ConcurrentHashMap<>();
    /** 专门收服务回包的线程，避免 attach 阻塞调用线程时把回包也堵住 */
    private final HandlerThread ipcThread;
    private final Messenger incoming;

    private volatile Context appContext;
    private volatile Messenger remote;
    private volatile boolean binding;

    private volatile boolean running;
    private volatile String requestId;

    private static final class AttachWait {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String reply = "";
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binding = false;
            remote = new Messenger(service);
            flush();
            send(AgentIpc.MSG_ATTACH, new Bundle());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
        }
    };

    private AgentClient() {
        // 回包走独立线程：attach() 会同步等 CountDownLatch，若回包也挤在主线程，
        // 主线程一阻塞就永远收不到回包。事件回调仍然 post 回主线程。
        ipcThread = new HandlerThread("dla-agent-ipc");
        ipcThread.start();
        incoming = new Messenger(new Handler(ipcThread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                handleService(msg);
            }
        });
    }

    /** 是否有未完成的 Agent 任务（供 UI 决定是否需要恢复） */
    public static boolean hasPendingWork(Context context) {
        return AgentCheckpoint.hasPending(context);
    }

    public void connect(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        if (remote != null || binding) return;
        binding = true;
        Intent intent = new Intent(appContext, AgentService.class);
        try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable throwable) {
            binding = false;
            Log.w(TAG, "bind failed: " + throwable.getMessage());
        }
    }

    public void setListener(Listener listener) {
        listeners.clear();
        if (listener != null) listeners.add(listener);
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isRunning() {
        return running;
    }

    public String currentRequestId() {
        return requestId;
    }

    public void start(Context context, String id, String payloadJson) {
        connect(context);
        Bundle data = new Bundle();
        data.putString(AgentIpc.KEY_ID, id);
        // 小请求直接过 Binder；带图片的大请求先落盘，只传路径（详见 AgentIpc.KEY_PAYLOAD_FILE）
        String inline = payloadJson;
        if (payloadJson != null && payloadJson.length() > INLINE_PAYLOAD_LIMIT) {
            String path = spillPayload(context, id, payloadJson);
            if (path != null) {
                data.putString(AgentIpc.KEY_PAYLOAD_FILE, path);
                inline = null;
            }
        }
        if (inline != null) {
            data.putString(AgentIpc.KEY_PAYLOAD, inline);
        }
        requestId = id;
        running = true;
        if (!send(AgentIpc.MSG_START, data)) {
            notifyFailure(id, "Agent 进程没有接收这次请求（请求体过大或进程不可用）。"
                    + "请减少图片/附件后重试。");
        }
    }

    /** 私有 files 目录里的临时请求体：两个进程同 UID，:agent 直接读得到 */
    private String spillPayload(Context context, String id, String payloadJson) {
        if (context == null) return null;
        try {
            java.io.File dir = new java.io.File(
                    context.getApplicationContext().getFilesDir(), "agent-payload");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            prune(dir);
            java.io.File file = new java.io.File(dir, id + ".json");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                out.write(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            }
            Log.i(TAG, "payload spilled to file: " + payloadJson.length() + " chars");
            return file.getAbsolutePath();
        } catch (Throwable error) {
            Log.w(TAG, "spill payload failed: " + error.getMessage());
            return null;
        }
    }

    /** 只留最近几个请求体，避免目录无限长（每个可能几 MB） */
    private void prune(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files == null || files.length < 6) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 4; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    public void stop(String id) {
        Bundle data = new Bundle();
        data.putString(AgentIpc.KEY_ID, id);
        send(AgentIpc.MSG_STOP, data);
    }

    public void approve(String id, String toolId, boolean allowed) {
        Bundle data = new Bundle();
        data.putString(AgentIpc.KEY_ID, id);
        data.putString(AgentIpc.KEY_TOOL_ID, toolId);
        data.putBoolean(AgentIpc.KEY_ALLOWED, allowed);
        send(AgentIpc.MSG_APPROVE, data);
    }

    /** 网页渲染进程没了：让运行时按「渲染端消失」处理（保留检查点） */
    public void rendererGone() {
        send(AgentIpc.MSG_RENDERER_GONE, new Bundle());
    }

    /** 冷启动恢复未完成任务（由 BootReceiver / HealthReceiver 触发） */
    public void resume(Context context) {
        connect(context);
        send(AgentIpc.MSG_RESUME, new Bundle());
    }

    /**
     * 事件回放：向 :agent 进程要 [cursor, 末尾] 的增量并同步等待回包。
     *
     * 时序：登记 token → 发 MSG_ATTACH（带 cursor/token）→ 服务侧照旧登记 client、
     * 回 MSG_STATE，并额外回 MSG_ATTACH_REPLY → 这里被 CountDownLatch 唤醒。
     * 最长等 600ms；服务没连上、回包丢失或超时都返回 ""（网页按“暂无回放”处理）。
     * 调用线程是 WebView 的 JS 桥线程，短暂阻塞可以接受。
     */
    public String attach(String id, int cursor) {
        int token = attachToken.incrementAndGet();
        AttachWait wait = new AttachWait();
        attachWaits.put(token, wait);
        Bundle data = new Bundle();
        data.putString(AgentIpc.KEY_ID, id);
        data.putInt(AgentIpc.KEY_CURSOR, cursor);
        data.putInt(AgentIpc.KEY_TOKEN, token);
        send(AgentIpc.MSG_ATTACH, data);
        try {
            if (!wait.latch.await(ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "attach timeout id=" + id + " cursor=" + cursor);
                return "";
            }
            return wait.reply;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            // 超时后迟到的回包会因 token 已被移除而被丢弃
            attachWaits.remove(token);
        }
    }

    private boolean send(int what, Bundle data) {
        Message message = Message.obtain(null, what);
        message.setData(data);
        message.replyTo = incoming;
        Messenger target = remote;
        if (target == null) {
            synchronized (pending) {
                if (pending.size() > 64) pending.pollFirst();
                pending.addLast(message);
            }
            connect(appContext);
            return true;
        }
        try {
            target.send(message);
            return true;
        } catch (Throwable exception) {
            // TransactionTooLargeException / DeadObjectException 都是 RemoteException 子类；
            // 以前只 catch RemoteException 后静默置空 remote，界面就永远等不到任何事件。
            remote = null;
            Log.w(TAG, "send failed: " + exception);
            return false;
        }
    }

    /** Binder 发不出去时补一条本地 error 事件，界面才不会一直「生成中」 */
    private void notifyFailure(String id, String message) {
        running = false;
        JSONObject event = new JSONObject();
        try {
            event.put("type", "error");
            event.put("message", message);
            event.put("requestId", id);
        } catch (Exception ignored) {
            // 不会发生
        }
        final JSONObject payload = event;
        main.post(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onEvent(id, payload);
                } catch (Throwable throwable) {
                    Log.w(TAG, "listener failed: " + throwable.getMessage());
                }
            }
        });
    }

    private void flush() {
        Messenger target = remote;
        if (target == null) return;
        synchronized (pending) {
            while (!pending.isEmpty()) {
                Message message = pending.pollFirst();
                if (message == null) break;
                message.replyTo = incoming;
                try {
                    target.send(message);
                } catch (Throwable exception) {
                    remote = null;
                    String id = message.getData() == null
                            ? null : message.getData().getString(AgentIpc.KEY_ID);
                    Log.w(TAG, "flush failed: " + exception);
                    if (message.what == AgentIpc.MSG_START && id != null) {
                        notifyFailure(id, "Agent 进程没有接收这次请求（请求体过大或进程不可用）。"
                                + "请减少图片/附件后重试。");
                    }
                    break;
                }
            }
        }
    }

    private void handleService(Message msg) {
        if (msg.what == AgentIpc.MSG_ATTACH_REPLY) {
            Bundle data = msg.getData();
            int token = data.getInt(AgentIpc.KEY_TOKEN, 0);
            AttachWait wait = attachWaits.remove(token);
            if (wait == null) {
                return;
            }
            String reply = data.getString(AgentIpc.KEY_REPLY);
            wait.reply = reply == null ? "" : reply;
            wait.latch.countDown();
            return;
        }
        if (msg.what == AgentIpc.MSG_STATE) {
            Bundle data = msg.getData();
            running = data.getBoolean(AgentIpc.KEY_RUNNING, running);
            String current = data.getString(AgentIpc.KEY_REQUEST_ID);
            if (current != null) requestId = current;
            return;
        }
        if (msg.what != AgentIpc.MSG_EVENT) return;
        Bundle data = msg.getData();
        String id = data.getString(AgentIpc.KEY_REQUEST_ID);
        String json = data.getString(AgentIpc.KEY_JSON);
        if (json == null) return;
        JSONObject event;
        try {
            event = new JSONObject(json);
        } catch (Exception parseError) {
            return;
        }
        String type = event.optString("type");
        if ("start".equals(type)) {
            running = true;
            if (id != null) requestId = id;
        } else if ("finish".equals(type) || "error".equals(type) || "cancelled".equals(type)) {
            running = false;
        }
        final String eventRequestId = id;
        final JSONObject payload = event;
        main.post(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onEvent(eventRequestId, payload);
                } catch (Throwable throwable) {
                    Log.w(TAG, "listener failed: " + throwable.getMessage());
                }
            }
        });
    }
}
