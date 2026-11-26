package com.example.chatdemo.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 进程内读屏：直接遍历 AccessibilityNodeInfo，不 fork uiautomator、不启动新 JVM。
 * 输出格式与 phone_screen 的 uiautomator compact 格式一致；300ms TTL 缓存。
 */
public class ChatDemoAccessibilityService extends AccessibilityService {

    private static volatile ChatDemoAccessibilityService instance;
    private static final long CACHE_TTL_MS = 300;

    private volatile long lastEventAt;
    private volatile long lastDumpAt;
    private volatile String lastDump;

    public static boolean ready() {
        return instance != null;
    }

    public static String dumpScreen() {
        ChatDemoAccessibilityService service = instance;
        if (service == null) {
            return null;
        }
        return service.dump();
    }

    /** 点击/输入/滑动后让下一次读屏绕过 300ms 缓存 */
    public static void invalidateCache() {
        ChatDemoAccessibilityService service = instance;
        if (service != null) {
            service.lastDump = null;
            service.lastDumpAt = 0;
            service.lastEventAt = SystemClock.elapsedRealtime();
        }
    }

    /** 原生点击：优先无障碍节点 ACTION_CLICK，不 fork input 命令。 */
    public static boolean clickText(String query) {
        ChatDemoAccessibilityService service = instance;
        if (service == null || query == null || query.trim().isEmpty()) {
            return false;
        }
        AccessibilityNodeInfo root;
        try {
            root = service.getRootInActiveWindow();
        } catch (Throwable error) {
            return false;
        }
        if (root == null) {
            return false;
        }
        try {
            return clickNode(root, query.trim().toLowerCase(), 0);
        } finally {
            try {
                root.recycle();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static boolean clickNode(AccessibilityNodeInfo node, String needle, int depth) {
        if (node == null || depth > 60) {
            return false;
        }
        CharSequence textValue = node.getText();
        CharSequence descValue = node.getContentDescription();
        String text = textValue == null ? "" : textValue.toString().toLowerCase();
        String desc = descValue == null ? "" : descValue.toString().toLowerCase();
        if ((text.contains(needle) || desc.contains(needle)) && node.isClickable()) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            try {
                if (clickNode(child, needle, depth + 1)) {
                    return true;
                }
            } finally {
                try {
                    child.recycle();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
        return false;
    }

    /** 原生坐标点击 GestureDescription；成功返回 true。 */
    public static boolean tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 1);
        return dispatch(stroke);
    }

    /** 原生滑动；成功返回 true。 */
    public static boolean swipe(int x1, int y1, int x2, int y2, int durationMs) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, Math.max(20, durationMs));
        return dispatch(stroke);
    }

    private static boolean dispatch(GestureDescription.StrokeDescription stroke) {
        ChatDemoAccessibilityService service = instance;
        if (service == null) {
            return false;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] ok = {false};
        try {
            GestureDescription description = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();
            boolean dispatched = service.dispatchGesture(
                    description,
                    new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            ok[0] = true;
                            latch.countDown();
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            latch.countDown();
                        }
                    },
                    null
            );
            if (!dispatched) {
                return false;
            }
            return latch.await(2, java.util.concurrent.TimeUnit.SECONDS) && ok[0];
        } catch (Throwable error) {
            return false;
        }
    }

    /** 向当前焦点输入框写文字；没有焦点就先找第一个可编辑节点、聚焦再写，成功返回 true。 */
    public static boolean setFocusedText(String text) {
        ChatDemoAccessibilityService service = instance;
        if (service == null) {
            return false;
        }
        AccessibilityNodeInfo root;
        try {
            root = service.getRootInActiveWindow();
        } catch (Throwable error) {
            return false;
        }
        if (root == null) {
            return false;
        }
        AccessibilityNodeInfo focus = null;
        try {
            focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus == null) {
                focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            }
            if (focus == null || !focus.isEditable()) {
                // 之前这里直接返回 false，调用方会退回 shell input：坐标落在非输入框上时
                // 命令「成功」但屏幕上什么都没出现。现在先自己把第一个输入框点开再写。
                AccessibilityNodeInfo editable = findEditableNode(root, 0);
                if (editable == null) {
                    return false;
                }
                editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                editable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                try {
                    Thread.sleep(120);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                Bundle args = new Bundle();
                args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                );
                boolean written = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                try {
                    editable.recycle();
                } catch (Throwable ignored) {
                    // ignore
                }
                return written;
            }
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
            );
            return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Throwable error) {
            return false;
        } finally {
            try {
                root.recycle();
            } catch (Throwable ignored) {
                // ignore
            }
            if (focus != null) {
                try {
                    focus.recycle();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    /** 深度优先找第一个可编辑（输入框）节点 */
    private static AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 25) {
            return null;
        }
        if (node.isEditable()) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Throwable ignored) {
                // ignore
            }
            if (child == null) {
                continue;
            }
            AccessibilityNodeInfo found = findEditableNode(child, depth + 1);
            if (found != null) {
                if (found != child) {
                    try {
                        child.recycle();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
                return found;
            }
            try {
                child.recycle();
            } catch (Throwable ignored) {
                // ignore
            }
        }
        return null;
    }

    /** 系统级按键；返回是否由无障碍直接处理。 */
    public static boolean globalAction(String key) {
        ChatDemoAccessibilityService service = instance;
        if (service == null || key == null) {
            return false;
        }
        int action;
        switch (key.toLowerCase()) {
            case "back":
                action = GLOBAL_ACTION_BACK;
                break;
            case "home":
                action = GLOBAL_ACTION_HOME;
                break;
            case "recents":
            case "recent":
                action = GLOBAL_ACTION_RECENTS;
                break;
            case "notifications":
                action = GLOBAL_ACTION_NOTIFICATIONS;
                break;
            default:
                return false;
        }
        try {
            return service.performGlobalAction(action);
        } catch (Throwable error) {
            return false;
        }
    }

    /** Android 11+ 原生截图；失败返回 null，由调用方降级。 */
    public static Bitmap screenshot() {
        ChatDemoAccessibilityService service = instance;
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final Bitmap[] result = {null};
        try {
            service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult screenshotResult) {
                            HardwareBuffer buffer = screenshotResult.getHardwareBuffer();
                            try {
                                Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                                        buffer,
                                        screenshotResult.getColorSpace()
                                );
                                if (bitmap != null) {
                                    result[0] = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                                }
                            } finally {
                                try {
                                    buffer.close();
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                            }
                            latch.countDown();
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            latch.countDown();
                        }
                    }
            );
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable error) {
            return null;
        }
        return result[0];
    }

    /** 按文字/描述找到控件中心；返回 null 表示当前树里没有。 */
    public static int[] findTextCenter(String query) {
        ChatDemoAccessibilityService service = instance;
        if (service == null || query == null || query.trim().isEmpty()) {
            return null;
        }
        AccessibilityNodeInfo root;
        try {
            root = service.getRootInActiveWindow();
        } catch (Throwable error) {
            return null;
        }
        if (root == null) {
            return null;
        }
        int[] first = new int[2];
        boolean[] foundFirst = {false};
        int[] clickable = new int[2];
        boolean[] foundClickable = {false};
        String needle = query.trim().toLowerCase();
        try {
            findNode(root, needle, 0, first, foundFirst, clickable, foundClickable);
        } finally {
            try {
                root.recycle();
            } catch (Throwable ignored) {
                // Android 14+ 不需要手动 recycle
            }
        }
        if (foundClickable[0]) {
            return clickable;
        }
        return foundFirst[0] ? first : null;
    }

    private static void findNode(
            AccessibilityNodeInfo node,
            String needle,
            int depth,
            int[] first,
            boolean[] foundFirst,
            int[] clickable,
            boolean[] foundClickable
    ) {
        if (node == null || depth > 60 || foundClickable[0]) {
            return;
        }
        CharSequence textValue = node.getText();
        CharSequence descValue = node.getContentDescription();
        String text = textValue == null ? "" : textValue.toString().toLowerCase();
        String desc = descValue == null ? "" : descValue.toString().toLowerCase();
        if (text.contains(needle) || desc.contains(needle)) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() > 0 && bounds.height() > 0) {
                int[] target = foundClickable[0] || node.isClickable() ? clickable : first;
                target[0] = bounds.centerX();
                target[1] = bounds.centerY();
                if (node.isClickable()) {
                    foundClickable[0] = true;
                    return;
                }
                foundFirst[0] = true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            try {
                findNode(child, needle, depth + 1, first, foundFirst, clickable, foundClickable);
            } finally {
                try {
                    child.recycle();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            if (foundClickable[0]) {
                return;
            }
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Diagnostics.event("a11y", "connected");
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        Diagnostics.event("a11y", "destroyed");
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        lastEventAt = SystemClock.elapsedRealtime();
    }

    @Override
    public void onInterrupt() {
        // 不需要中断处理
    }

    private String dump() {
        long now = SystemClock.elapsedRealtime();
        String cached = lastDump;
        if (cached != null && now - lastDumpAt < CACHE_TTL_MS && lastEventAt <= lastDumpAt) {
            return cached;
        }
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable error) {
            Diagnostics.logThrowable("a11y_root", error);
            return null;
        }
        if (root == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        int[] count = {0};
        try {
            appendNode(builder, root, count, 0);
        } finally {
            try {
                root.recycle();
            } catch (Throwable ignored) {
                // Android 14+ 不需要手动 recycle
            }
        }
        String result = builder.length() == 0 ? "（界面没有可读文本）" : builder.toString();
        lastDump = result;
        lastDumpAt = now;
        return result;
    }

    private void appendNode(
            StringBuilder builder,
            AccessibilityNodeInfo node,
            int[] count,
            int depth
    ) {
        if (node == null || count[0] >= 300 || depth > 60) {
            return;
        }
        try {
            CharSequence textValue = node.getText();
            CharSequence descValue = node.getContentDescription();
            String text = textValue == null ? "" : textValue.toString().trim();
            String desc = descValue == null ? "" : descValue.toString().trim();
            boolean clickable = node.isClickable();
            if (!text.isEmpty() || !desc.isEmpty() || clickable) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                builder.append("- ");
                if (!text.isEmpty()) {
                    builder.append('"').append(escape(text)).append('"');
                }
                if (!desc.isEmpty()) {
                    builder.append(" desc=").append(escape(desc));
                }
                builder.append(' ').append(simpleClass(node.getClassName()))
                        .append(" [")
                        .append(bounds.left).append(',').append(bounds.top)
                        .append("][")
                        .append(bounds.right).append(',').append(bounds.bottom)
                        .append(']');
                if (clickable) {
                    builder.append(" [click]");
                }
                CharSequence pkg = node.getPackageName();
                if (pkg != null && pkg.length() > 0) {
                    builder.append(" pkg=").append(pkg);
                }
                builder.append('\n');
                count[0]++;
            }
            for (int i = 0; i < node.getChildCount() && count[0] < 300; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) {
                    continue;
                }
                try {
                    appendNode(builder, child, count, depth + 1);
                } finally {
                    try {
                        child.recycle();
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }
        } catch (Throwable error) {
            // 单个节点异常不影响整棵树
        }
    }

    private static String simpleClass(CharSequence className) {
        if (className == null) {
            return "?";
        }
        String value = className.toString();
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    private static String escape(String value) {
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
