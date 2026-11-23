package com.example.chatdemo.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 15 分钟健康检查：任务仍在但服务/进程不在了，尝试重新拉起 */
public class HealthReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AgentCheckpoint.hasPending(context)) {
            AgentKeepAlive.cancelHealth(context);
            return;
        }
        AgentService.requestResume(context);
        AgentKeepAlive.scheduleHealth(context);
    }
}
