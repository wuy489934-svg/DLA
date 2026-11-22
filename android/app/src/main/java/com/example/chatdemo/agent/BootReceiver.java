package com.example.chatdemo.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机/应用升级后恢复未完成的 Agent 任务 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        boolean pending = AgentCheckpoint.hasPending(context);
        android.util.Log.i("BootReceiver", "action=" + action + " pending=" + pending);
        if (!pending) {
            AgentKeepAlive.cancelHealth(context);
            return;
        }
        if (AgentRuntime.get().resumeIfNeeded(context)) {
            AgentKeepAlive.scheduleHealth(context);
        }
    }
}
