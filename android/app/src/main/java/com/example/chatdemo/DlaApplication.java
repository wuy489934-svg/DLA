package com.example.chatdemo;

import android.app.Application;
import android.util.Log;

import com.example.chatdemo.agent.ShizukuShell;

/**
 * 进程入口。
 *
 * 两个必须在任何工具调用之前做完的初始化：
 * 1. Shizuku 的 binder 只会被送到「声明了 ShizukuProvider 的进程」（主进程）。
 *    Agent 跑在 :agent，必须主动向 provider 要一次 binder，否则
 *    Shizuku.pingBinder() 永远 false，界面上就会一直显示「Shizuku 未就绪」。
 * 2. 记下应用 Context，供 ShizukuShell 在 binder 丢失时按需重取。
 */
public class DlaApplication extends Application {

    private static final String TAG = "DlaShizuku";

    @Override
    public void onCreate() {
        super.onCreate();
        ShizukuShell.init(this);
        try {
            rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(this);
        } catch (Throwable error) {
            // 没有装 Shizuku / 版本不支持时保持安静，工具会按「不可用」处理
            Log.w(TAG, "request binder failed: " + error);
        }
    }
}
