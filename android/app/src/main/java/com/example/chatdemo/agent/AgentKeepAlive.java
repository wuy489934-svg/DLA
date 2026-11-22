package com.example.chatdemo.agent;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/** 失败后的复活调度：任务划掉后快速重拉 + 15 分钟健康检查 */
final class AgentKeepAlive {

    private static final int HEALTH_REQUEST = 4102;
    private static final int RESTART_REQUEST = 4103;
    private static final long HEALTH_INTERVAL = 15 * 60 * 1000L;

    private AgentKeepAlive() {}

    static void scheduleHealth(Context context) {
        try {
            AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (manager == null) {
                return;
            }
            PendingIntent pending = healthIntent(context);
            manager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + HEALTH_INTERVAL,
                    HEALTH_INTERVAL,
                    pending
            );
        } catch (Throwable ignored) {
            // 调度失败不影响当前任务
        }
    }

    static void cancelHealth(Context context) {
        try {
            AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (manager != null) {
                manager.cancel(healthIntent(context));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    static void scheduleRestart(Context context) {
        try {
            AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (manager == null) {
                return;
            }
            Intent intent = new Intent(context, AgentService.class);
            PendingIntent pending = PendingIntent.getForegroundService(
                    context,
                    RESTART_REQUEST,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            manager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 5000,
                    pending
            );
        } catch (Throwable ignored) {
            // Android 12+ 后台启动限制下可能被系统拒绝
        }
    }

    private static PendingIntent healthIntent(Context context) {
        Intent intent = new Intent(context, HealthReceiver.class);
        return PendingIntent.getBroadcast(
                context,
                HEALTH_REQUEST,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }
}
