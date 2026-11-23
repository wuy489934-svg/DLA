package com.example.chatdemo.agent;

import android.content.Context;
import android.provider.Settings;

/**
 * 无障碍服务是否已开启。
 *
 * 读的是 Settings.Secure，跨进程也有效；不能依赖 :agent 进程里的单例，
 * 因为 UI 进程拿不到那边的实例。
 */
public final class AccessibilityStatus {

    private AccessibilityStatus() {
    }

    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        String expected = context.getPackageName() + "/" + ChatDemoAccessibilityService.class.getName();
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabled == null) return false;
        for (String item : enabled.split(":")) {
            if (item.equalsIgnoreCase(expected)) return true;
        }
        return false;
    }
}
