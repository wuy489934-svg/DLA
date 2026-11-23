package com.example.chatdemo.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.example.chatdemo.MainActivity;
import com.example.chatdemo.R;

import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Agent 宿主服务：跑在独立进程 {@code :agent}。
 *
 * 职责：
 * 1. 在前台通知里维持长任务；
 * 2. 持有唯一的 {@link AgentRuntime}，通过 {@link Messenger} 接受 UI 进程的
 *    start / stop / approve / resume，并把运行时事件广播回去。
 *
 * 这样即使 UI 进程崩溃或被系统回收，任务仍然继续，重新绑定即可接上。
 */
public class AgentService extends Service {

    private static final String TAG = "AgentService";
    private static final String CHANNEL = "agent-run";
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_STOP = "com.example.chatdemo.agent.STOP";
    private static final String ACTION_RESUME = "com.example.chatdemo.agent.RESUME";

    private final Set<Messenger> clients = new CopyOnWriteArraySet<>();

    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            handleClientMessage(msg);
        }
    });

    public static void start(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, AgentService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, AgentService.class));
    }

    /** 冷启动恢复：由 BootReceiver / HealthReceiver 调用 */
    public static void requestResume(Context context) {
        try {
            ContextCompat.startForegroundService(
                    context,
                    new Intent(context, AgentService.class).setAction(ACTION_RESUME)
            );
        } catch (Throwable throwable) {
            Log.w(TAG, "requestResume failed: " + throwable.getMessage());
        }
    }

    /** 长任务进度：切到别的 App 时也能从通知栏看到当前在做什么 */
    public static void update(Context context, String text) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, build(context, text));
            }
        } catch (Throwable ignored) {
            // 通知失败不影响任务
        }
    }

    private static Notification build(Context context, String text) {
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        PendingIntent stopIntent = PendingIntent.getService(
                context,
                2,
                new Intent(context, AgentService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        return new NotificationCompat.Builder(context, CHANNEL)
                .setContentTitle(context.getString(R.string.agent_notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(
                        R.drawable.ic_stat_agent,
                        context.getString(R.string.agent_notification_stop),
                        stopIntent
                )
                .setContentIntent(pendingIntent)
                .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL,
                    getString(R.string.agent_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.agent_channel_description));
            manager.createNotificationChannel(channel);
        }
        Notification notification = build(this, getString(R.string.agent_notification_text));
        int type;
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+：specialUse 不受 dataSync 的 6 小时/24 小时限制
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        } else {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);

        // 运行时事件 -> 广播给所有已连接的 UI
        AgentRuntime.get().setListener((requestId, event) -> broadcast(requestId, event));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            AgentRuntime.get().stop(null);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            boolean resumed = AgentRuntime.get().resumeIfNeeded(this);
            Log.i(TAG, "resume requested -> " + resumed);
            if (!resumed && !AgentRuntime.get().isRunning() && clients.isEmpty()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            return START_STICKY;
        }
        // 没有未完成任务就不要留下一个空的前台服务
        if (!AgentCheckpoint.hasPending(this) && !AgentRuntime.get().isRunning() && clients.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (AgentCheckpoint.hasPending(this)) {
            AgentKeepAlive.scheduleRestart(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    /** Android 15+ 的 dataSync 超时兜底；specialUse 正常不会触发 */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.e(TAG, "onTimeout startId=" + startId + " type=" + fgsType);
        AgentRuntime.get().onServiceTimeout();
        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        clients.clear();
        super.onDestroy();
    }

    private void handleClientMessage(Message msg) {
        Bundle data = msg.getData();
        switch (msg.what) {
            case AgentIpc.MSG_ATTACH:
                if (msg.replyTo != null) {
                    clients.add(msg.replyTo);
                    sendState(msg.replyTo);
                }
                // 带 cursor 的是新协议：额外把 [cursor, 末尾] 的事件回放给请求方。
                // 不带 cursor 的旧 MSG_ATTACH 保持原样，只登记 client + 同步状态。
                if (msg.replyTo != null && data.containsKey(AgentIpc.KEY_CURSOR)) {
                    String id = data.getString(AgentIpc.KEY_ID);
                    int cursor = data.getInt(AgentIpc.KEY_CURSOR, 0);
                    int token = data.getInt(AgentIpc.KEY_TOKEN, 0);
                    sendAttachReply(msg.replyTo, AgentRuntime.get().attach(id, cursor), token);
                }
                break;
            case AgentIpc.MSG_START: {
                String id = data.getString(AgentIpc.KEY_ID);
                String payload = data.getString(AgentIpc.KEY_PAYLOAD);
                if (msg.replyTo != null) clients.add(msg.replyTo);
                if (id != null && payload != null) {
                    AgentRuntime.get().start(getApplicationContext(), id, payload);
                }
                break;
            }
            case AgentIpc.MSG_STOP: {
                String id = data.getString(AgentIpc.KEY_ID);
                AgentRuntime.get().stop(id);
                break;
            }
            case AgentIpc.MSG_APPROVE: {
                String id = data.getString(AgentIpc.KEY_ID);
                String toolId = data.getString(AgentIpc.KEY_TOOL_ID);
                boolean allowed = data.getBoolean(AgentIpc.KEY_ALLOWED, false);
                AgentRuntime.get().approve(id, toolId, allowed);
                break;
            }
            case AgentIpc.MSG_RESUME:
                AgentRuntime.get().resumeIfNeeded(getApplicationContext());
                break;
            case AgentIpc.MSG_RENDERER_GONE:
                AgentRuntime.get().onRendererGone();
                break;
            case AgentIpc.MSG_PING:
                if (msg.replyTo != null) sendState(msg.replyTo);
                break;
            default:
                break;
        }
    }

    private void sendState(Messenger target) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(AgentIpc.KEY_RUNNING, AgentRuntime.get().isRunning());
        String current = AgentRuntime.get().currentRequestId();
        if (current != null) bundle.putString(AgentIpc.KEY_REQUEST_ID, current);
        Message message = Message.obtain(null, AgentIpc.MSG_STATE);
        message.setData(bundle);
        try {
            target.send(message);
        } catch (RemoteException ignored) {
            clients.remove(target);
        }
    }

    /** 把 AgentRuntime.attach() 的回放 JSON 回给发起 attach 的客户端 */
    private void sendAttachReply(Messenger target, String replay, int token) {
        Bundle bundle = new Bundle();
        bundle.putInt(AgentIpc.KEY_TOKEN, token);
        bundle.putString(AgentIpc.KEY_REPLY, replay == null ? "" : replay);
        Message message = Message.obtain(null, AgentIpc.MSG_ATTACH_REPLY);
        message.setData(bundle);
        try {
            target.send(message);
        } catch (RemoteException ignored) {
            clients.remove(target);
        }
    }

    private void broadcast(String requestId, JSONObject event) {
        if (event == null) return;
        Bundle bundle = new Bundle();
        if (requestId != null) bundle.putString(AgentIpc.KEY_REQUEST_ID, requestId);
        bundle.putString(AgentIpc.KEY_JSON, event.toString());
        Message message = Message.obtain(null, AgentIpc.MSG_EVENT);
        message.setData(bundle);
        for (Messenger client : clients) {
            try {
                client.send(message);
            } catch (RemoteException exception) {
                clients.remove(client);
            }
        }
    }
}
