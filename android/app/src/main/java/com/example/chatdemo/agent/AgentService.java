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
    /** 结果通知走另一个渠道：任务完成 / 失败要能弹出来，不能被「低优先级」静音 */
    private static final String CHANNEL_RESULT = "agent-result-v2";
    private static final int NOTIFICATION_ID = 1001;
    private static final int NOTIFICATION_RESULT_ID = 1002;
    public static final String EXTRA_SESSION = "openSession";
    private static final String ACTION_STOP = "com.example.chatdemo.agent.STOP";
    private static final String ACTION_RESUME = "com.example.chatdemo.agent.RESUME";

    private final Set<Messenger> clients = new CopyOnWriteArraySet<>();

    /** 当前实例：任务结束时要用它把常驻通知撤掉（stopForeground 只能实例调用） */
    private static volatile AgentService current;

    /**
     * 一轮任务结束：撤掉「正在执行任务」的常驻通知。
     *
     * 为什么不能只 cancel：前台服务通知必须用 stopForeground(STOP_FOREGROUND_REMOVE) 才会真的消失，
     * 而服务因为客户端还 bind 着不会销毁，通知就会一直挂在通知栏上（曾经一直显示「Agent 正在执行任务」）。
     */
    public static void clearProgress(Context context) {
        AgentService service = current;
        try {
            if (service != null) {
                ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE);
            }
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {
            // 撤通知失败不影响任务结果
        }
    }

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
                new Intent(context, com.example.chatdemo.nativeui.NativeShellActivity.class),
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

    /**
     * 任务结束的「结果通知」：完成 / 失败 / 已停止。
     *
     * 和前台服务那条常驻通知分开：这条会响、会弹，点一下直接回到那个会话；
     * 正文放大（BigText），这样通知栏里就能看到「结论 + 轮/步/耗时 + 最后工具」，
     * 不必先点开应用。
     */
    public static void notifyResult(Context context, String sessionId, String status,
                                    String title, String body, String bigText) {
        try {
            if (context == null) return;
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences("dla-native", Context.MODE_PRIVATE);
            if (!prefs.getBoolean("notifyResult", true)) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "result notification skipped: no POST_NOTIFICATIONS");
                return;
            }
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            android.net.Uri sound = android.media.RingtoneManager.getDefaultUri(
                    android.media.RingtoneManager.TYPE_NOTIFICATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_RESULT,
                        context.getString(R.string.agent_result_channel_name),
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription(context.getString(R.string.agent_result_channel_description));
                channel.enableVibration(true);
                channel.setVibrationPattern(new long[]{0, 80, 60, 120});
                if (sound != null) {
                    android.media.AudioAttributes attributes =
                            new android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build();
                    channel.setSound(sound, attributes);
                }
                manager.createNotificationChannel(channel);
            }
            Intent open = new Intent(context, com.example.chatdemo.nativeui.NativeShellActivity.class);
            open.putExtra(EXTRA_SESSION, sessionId == null ? "" : sessionId);
            open.putExtra("resultStatus", status == null ? "" : status);
            // SINGLE_TOP + CLEAR_TOP：界面已经在前台时走 onNewIntent，不会叠出第二个界面实例
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(
                    context,
                    10,
                    open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_RESULT)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(R.drawable.ic_stat_agent)
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(false)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    // 渠道决定 O+ 的铃声；这两行兜住低版本和渠道被用户改静音的情况
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setSound(sound)
                    .setVibrate(new long[]{0, 80, 60, 120})
                    .setDefaults(NotificationCompat.DEFAULT_ALL);
            if (bigText != null && !bigText.isEmpty()) {
                builder.setStyle(new NotificationCompat.BigTextStyle().bigText(bigText));
            }
            manager.notify(NOTIFICATION_RESULT_ID, builder.build());
        } catch (Throwable error) {
            Log.w(TAG, "notifyResult failed: " + error);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
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
        // 只有「确实有活」的时候才挂前台通知。
        // 之前无论怎么创建都 startForeground，于是界面一 bind，通知栏就常驻一条
        // 「Agent 正在执行任务」——任务早就结束了，通知还在，很误导。
        if (hasWork()) {
            ensureForeground();
        }

        // 运行时事件 -> 广播给所有已连接的 UI
        AgentRuntime.get().setListener((requestId, event) -> broadcast(requestId, event));
    }

    /** 有未完成任务 / 正在跑 / 还有客户端连着 */
    private boolean hasWork() {
        return AgentCheckpoint.hasPending(this) || AgentRuntime.get().isRunning() || !clients.isEmpty();
    }

    /** 挂前台通知（多次调用无副作用） */
    private void ensureForeground() {
        Notification notification = build(this, getString(R.string.agent_notification_text));
        int type;
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+：specialUse 不受 dataSync 的 6 小时/24 小时限制
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        } else {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
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
                // 用 startForegroundService 拉起来的服务必须在 5 秒内挂前台，
                // 哪怕马上就停，也要先满足契约再 stopSelf，否则会抛 RemoteServiceException
                ensureForeground();
                stopSelf();
                return START_NOT_STICKY;
            }
            ensureForeground();
            return START_STICKY;
        }
        // 没有未完成任务就不要留下一个空的前台服务
        if (!AgentCheckpoint.hasPending(this) && !AgentRuntime.get().isRunning() && clients.isEmpty()) {
            ensureForeground();
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureForeground();
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
        if (current == this) current = null;
        // 服务没了，常驻通知也一起收掉，别让「Agent 正在执行任务」挂在通知栏里骗人
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {
            // 取消失败不影响任何逻辑
        }
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
                String payloadFile = data.getString(AgentIpc.KEY_PAYLOAD_FILE);
                if (payload == null && payloadFile != null) {
                    payload = readPayloadFile(payloadFile);
                }
                if (msg.replyTo != null) clients.add(msg.replyTo);
                if (id != null && payload != null) {
                    AgentRuntime.get().start(getApplicationContext(), id, payload);
                    // 运行时已把请求体放进内存（并在需要时写 checkpoint），临时文件可以删了
                    if (payloadFile != null) {
                        deleteQuietly(payloadFile);
                    }
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

    /**
     * 读 UI 进程落盘的大请求体（多图片的 base64 走 Binder 会超 1 MB）。
     * 读失败返回 null，交给调用方走「参数不是合法 JSON」的既有错误分支。
     */
    private String readPayloadFile(String path) {
        try {
            java.io.File file = new java.io.File(path);
            if (!file.isFile()) {
                Log.w(TAG, "payload file missing: " + path);
                return null;
            }
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int read = in.read(bytes, offset, bytes.length - offset);
                    if (read <= 0) break;
                    offset += read;
                }
            }
            Log.i(TAG, "payload read from file: " + bytes.length + " bytes");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable error) {
            Log.w(TAG, "read payload file failed: " + error.getMessage());
            return null;
        }
    }

    private void deleteQuietly(String path) {
        try {
            java.io.File file = new java.io.File(path);
            if (file.isFile() && !file.delete()) {
                Log.w(TAG, "payload file not deleted: " + path);
            }
        } catch (Throwable ignored) {
            // 删不掉也不影响本轮
        }
    }

    private void sendState(Messenger target) {        Bundle bundle = new Bundle();
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

