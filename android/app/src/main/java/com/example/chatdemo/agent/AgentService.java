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
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.example.chatdemo.MainActivity;
import com.example.chatdemo.R;

/** 前台服务：只负责在 Agent 运行期间保住进程，循环本身在 AgentRuntime 的线程里 */
public class AgentService extends Service {

    private static final String CHANNEL = "agent-run";
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_STOP = "com.example.chatdemo.agent.STOP";

    public static void start(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, AgentService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, AgentService.class));
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AgentRuntime.get().stop(null);
            stopSelf();
            return START_NOT_STICKY;
        }
        // 没有未完成任务就不要留下一个空的前台服务
        if (!AgentCheckpoint.hasPending(this) && !AgentRuntime.get().isRunning()) {
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
        android.util.Log.e("AgentService", "onTimeout startId=" + startId + " type=" + fgsType);
        AgentRuntime.get().onServiceTimeout();
        stopSelf(startId);
    }

    @Override
    public void onTimeout(int startId) {
        android.util.Log.e("AgentService", "onTimeout(legacy) startId=" + startId);
        AgentRuntime.get().onServiceTimeout();
        stopSelf(startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
