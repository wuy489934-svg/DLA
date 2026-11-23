package com.example.chatdemo.agent;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.provider.MediaStore;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 手机内置工具：原生 Android API 优先，Shizuku shell 只用于强停、写 secure settings、
 * 通知读取等确实需要特权的场景。
 */
public final class LocalTools {

    public static final class Result {
        public final String text;
        public final boolean isError;
        /** 截图等图片结果，作为图片块回给模型；不做 UI 传输 */
        public final String imageBase64;
        public final String imageMime;

        Result(String text, boolean isError) {
            this(text, isError, null, null);
        }

        Result(String text, boolean isError, String imageBase64, String imageMime) {
            this.text = text;
            this.isError = isError;
            this.imageBase64 = imageBase64;
            this.imageMime = imageMime;
        }
    }

    private static final int MAX_TEXT = 24000;
    /** 最近一次截图的缩放信息，供 phone_tap_shot 自动换算坐标 */
    private static volatile int shotWidth;
    private static volatile int shotHeight;
    private static volatile int screenWidth;
    private static volatile int screenHeight;
    private static final int DEFAULT_TIMEOUT = 20000;
    /** 当前调用的 Context；run() 在没有 Shizuku 时要靠它跑 app 身份 shell */
    private static volatile Context currentContext;

    private LocalTools() {}

    public static boolean isLocal(String name) {
        return name != null && name.startsWith("phone_");
    }

    /** 会改动设备/文件/设置的工具，执行前需要用户确认 */
    /** 只读、彼此独立，可安全并行执行 */
    public static boolean isParallelSafe(String name) {
        if (name == null) {
            return false;
        }
        switch (name) {
            case "phone_screen":
            case "phone_apps":
            case "phone_device":
            case "phone_setting_get":
            case "phone_clipboard_get":
            case "phone_files":
            case "phone_read":
            case "phone_view_image":
            case "phone_file_info":
            case "phone_json_get":
            case "phone_zip_list":
            case "phone_docx_text":
            case "phone_wait":
                return true;
            default:
                return false;
        }
    }

    /** 短时间内重复调用可复用结果；动作类、截图、screen 不缓存 */
    public static boolean isCacheable(String name) {
        if (name == null) {
            return false;
        }
        switch (name) {
            case "phone_apps":
            case "phone_device":
            case "phone_setting_get":
            case "phone_clipboard_get":
            case "phone_file_info":
            case "phone_zip_list":
            case "phone_docx_text":
                return true;
            default:
                return false;
        }
    }

    public static boolean requiresApproval(String name) {        if (name == null) {
            return false;
        }
        switch (name) {
            case "phone_shell":
            case "phone_write":
            case "phone_append":
            case "phone_replace":
            case "phone_json_set":
            case "phone_setting_put":
            case "phone_stop":
            case "phone_zip_extract":
            case "phone_clipboard_set":
                return true;
            default:
                return false;
        }
    }

    public static JSONArray specs() {
        JSONArray list = new JSONArray();
        list.put(tool(
                "phone_shell",
                "在手机上以 shell(uid 2000) 执行命令，返回 stdout+stderr。可以用来做任何 am/pm/input/settings 操作。",
                schema(props("command", p("string", "要执行的 shell 命令"), "timeout_ms", p("number", "超时毫秒，默认 20000")), "command")
        ));
        list.put(tool(
                "phone_screen",
                "读取当前屏幕的控件树（文字、描述、坐标、是否可点）。操作前先看屏幕，再决定点哪里。",
                schema(props())
        ));
        list.put(tool(
                "phone_tap_text",
                "在当前屏幕按文字或描述查找并点击中心点；比 phone_screen + phone_tap 少一步，找不到时返回当前树提示。",
                schema(props("text", p("string", "要点击的文字或 contentDescription，支持包含匹配")), "text")
        ));
        list.put(tool(
                "phone_wait",
                "等待界面稳定/动画结束；可选等到某段文字出现。不要把 sleep 写进 phone_shell。",
                schema(props(
                        "ms", p("number", "最多等待毫秒，默认 800，上限 5000"),
                        "until_text", p("string", "可选：等到该文字/描述出现就提前返回")
                ))
        ));
        list.put(tool(
                "phone_tap",
                "点击真实屏幕坐标（1220x2712 这种实际分辨率），不是截图缩放后的坐标。坐标来自 phone_screen 的 bounds 中心，或截图坐标乘缩放比。",
                schema(props("x", p("number", "真实屏幕横坐标"), "y", p("number", "真实屏幕纵坐标")), "x", "y")
        ));
        list.put(tool(
                "phone_tap_shot",
                "按截图上的坐标点按，自动换算成真实屏幕坐标；必须先 phone_screenshot。",
                schema(props("x", p("number", "截图上的 x"), "y", p("number", "截图上的 y")), "x", "y")
        ));
        list.put(tool(
                "phone_swipe",
                "滑动屏幕，用于滚动列表或翻页。",
                schema(props(
                        "x1", p("number", "起点 x"), "y1", p("number", "起点 y"),
                        "x2", p("number", "终点 x"), "y2", p("number", "终点 y"),
                        "duration_ms", p("number", "持续时间，默认 300")
                ), "x1", "y1", "x2", "y2")
        ));
        list.put(tool(
                "phone_input",
                "向当前焦点输入框输入文本；会先点击屏幕确保焦点正确。",
                schema(props("text", p("string", "要输入的文字")), "text")
        ));
        list.put(tool(
                "phone_key",
                "按系统按键：back / home / recents / enter / delete / power / volume_up / volume_down / notifications。",
                schema(props("key", p("string", "按键名")), "key")
        ));
        list.put(tool(
                "phone_apps",
                "列出已安装应用包名。third_party 默认 true，只看用户安装的应用。",
                schema(props("third_party", p("boolean", "是否只看第三方应用，默认 true")))
        ));
        list.put(tool(
                "phone_open",
                "按包名打开应用。",
                schema(props("package", p("string", "应用包名")), "package")
        ));
        list.put(tool(
                "phone_stop",
                "强制停止应用（am force-stop）。",
                schema(props("package", p("string", "应用包名")), "package")
        ));
        list.put(tool(
                "phone_files",
                "列出目录内容（ls -la），默认 /sdcard。",
                schema(props("path", p("string", "目录路径，默认 /sdcard")))
        ));
        list.put(tool(
                "phone_read",
                "读取文件内容（默认从头读 16KB）。大文件用 offset/max_bytes 分段读。",
                schema(props(
                        "path", p("string", "文件路径"),
                        "offset", p("number", "起始字节，默认 0"),
                        "max_bytes", p("number", "最多读取字节，最大 24000")
                ), "path")
        ));
        list.put(tool(
                "phone_write",
                "覆盖写入文本文件；父目录必须已存在。",
                schema(props("path", p("string", "文件路径"), "content", p("string", "文件内容")), "path", "content")
        ));
        list.put(tool(
                "phone_append",
                "在文件末尾追加文本。",
                schema(props("path", p("string", "文件路径"), "content", p("string", "追加内容")), "path", "content")
        ));
        list.put(tool(
                "phone_replace",
                "在文本文件中做字面替换并保存（适合改配置/代码）。文件需小于 256KB。",
                schema(props(
                        "path", p("string", "文件路径"),
                        "old", p("string", "要替换的原文"),
                        "new", p("string", "替换后的文本"),
                        "count", p("number", "最多替换几处，默认全部")
                ), "path", "old", "new")
        ));
        list.put(tool(
                "phone_search_text",
                "在文件或目录中搜索文本（grep -RIn），返回匹配行与行号。",
                schema(props(
                        "pattern", p("string", "要搜索的文本"),
                        "path", p("string", "文件或目录，默认 /sdcard"),
                        "include", p("string", "可选，文件名通配，如 *.md")
                ), "pattern")
        ));
        list.put(tool(
                "phone_file_info",
                "查看文件/目录的大小、类型与修改时间。",
                schema(props("path", p("string", "路径")), "path")
        ));
        list.put(tool(
                "phone_json_get",
                "读取 JSON 文件；pointer 用点号路径（如 a.b.0.c），不传则返回格式化全文。",
                schema(props("path", p("string", "JSON 文件路径"), "pointer", p("string", "点号路径，可选")), "path")
        ));
        list.put(tool(
                "phone_json_set",
                "修改 JSON 文件中的某个值并保存；pointer 用点号路径，value 可为 JSON 字面量或字符串。",
                schema(props(
                        "path", p("string", "JSON 文件路径"),
                        "pointer", p("string", "点号路径，如 a.b.0.c"),
                        "value", p("string", "新值")
                ), "path", "pointer", "value")
        ));
        list.put(tool(
                "phone_zip_list",
                "列出 zip/apk 压缩包内容。",
                schema(props("path", p("string", "压缩包路径")), "path")
        ));
        list.put(tool(
                "phone_zip_extract",
                "解压 zip 到目标目录。",
                schema(props("path", p("string", "压缩包路径"), "dest", p("string", "目标目录")), "path", "dest")
        ));
        list.put(tool(
                "phone_docx_text",
                "提取 .docx 的正文纯文本（简单去标签，适合快速阅读）。",
                schema(props("path", p("string", "docx 路径")), "path")
        ));
        list.put(tool(
                "phone_screenshot",
                "用无障碍原生截图并保存到相册 Pictures/DLA，返回路径；同时回传压缩图。",
                schema(props())
        ));
        list.put(tool(
                "phone_view_image",
                "打开一张本机图片给你自己看（jpg/png/webp/gif，长边压到 1280 再回传）。"
                        + "用户提到的图片、相册文件、用 screencap 存的截图都可以用它读；"
                        + "看完图再回答，不要凭文件名猜内容。",
                schema(props("path", p("string", "图片绝对路径，例如 /sdcard/DCIM/Camera/a.jpg")), "path")
        ));
        list.put(tool(
                "phone_device",
                "读取设备型号、系统版本、屏幕分辨率、电池和存储信息。",
                schema(props())
        ));
        list.put(tool(
                "phone_setting_get",
                "读取系统设置项。namespace 为 system/secure/global。",
                schema(props("namespace", p("string", "system/secure/global"), "key", p("string", "设置项名")), "namespace", "key")
        ));
        list.put(tool(
                "phone_setting_put",
                "写入系统设置项。namespace 为 system/secure/global；改错可能影响系统，请谨慎。",
                schema(props(
                        "namespace", p("string", "system/secure/global"),
                        "key", p("string", "设置项名"),
                        "value", p("string", "设置值")
                ), "namespace", "key", "value")
        ));
        list.put(tool(
                "phone_clipboard_get",
                "读取剪贴板（应用需在前台）。",
                schema(props())
        ));
        list.put(tool(
                "phone_clipboard_set",
                "写入剪贴板。",
                schema(props("text", p("string", "要写入的文字")), "text")
        ));
        list.put(tool(
                "phone_notifications",
                "读取当前通知列表（dumpsys notification），可能较长。",
                schema(props())
        ));
        return list;
    }

    public static Result execute(Context context, String name, JSONObject args) {
        currentContext = context;
        JSONObject arguments = args == null ? new JSONObject() : args;
        try {
            switch (name) {
                case "phone_shell": {
                    String command = arguments.optString("command");
                    int timeout = arguments.optInt("timeout_ms", DEFAULT_TIMEOUT);
                    // 这个工具的名字就是「特权 shell」，不能悄悄退化成 app 身份
                    return ok(ShizukuShell.exec(command, Math.min(Math.max(timeout, 1000), 120000)));
                }
                case "phone_screen": {
                    String fast = ChatDemoAccessibilityService.dumpScreen();
                    if (fast != null && !fast.isEmpty()) {
                        return ok(fast);
                    }
                    String fallback = compactScreen(run(
                            "uiautomator dump /data/local/tmp/chatdemo_ui.xml >/dev/null 2>&1; "
                                    + "cat /data/local/tmp/chatdemo_ui.xml",
                            DEFAULT_TIMEOUT
                    ));
                    return ok("source: fallback\n" + fallback);
                }
                case "phone_tap_text": {
                    String query = arguments.optString("text").trim();
                    if (query.isEmpty()) {
                        return fail("text 不能为空");
                    }
                    if (ChatDemoAccessibilityService.clickText(query)) {
                        ChatDemoAccessibilityService.invalidateCache();
                        return ok("已点击「" + query + "」（无障碍 ACTION_CLICK）");
                    }
                    int[] center = ChatDemoAccessibilityService.findTextCenter(query);
                    if (center == null) {
                        return fail("当前屏幕没有找到「" + query + "」；请先 phone_screen 确认");
                    }
                    boolean direct = ChatDemoAccessibilityService.tap(center[0], center[1]);
                    if (!direct) {
                        run("input tap " + center[0] + " " + center[1], 10000);
                    }
                    ChatDemoAccessibilityService.invalidateCache();
                    return ok("已点击「" + query + "」(" + center[0] + "," + center[1] + ")"
                            + (direct ? "（无障碍手势）" : "（shell 回退）"));
                }
                case "phone_wait": {
                    int maxMs = Math.min(Math.max(arguments.optInt("ms", 800), 100), 5000);
                    String until = arguments.optString("until_text").trim();
                    long deadline = SystemClock.elapsedRealtime() + maxMs;
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (!until.isEmpty()
                                && ChatDemoAccessibilityService.findTextCenter(until) != null) {
                            return ok("已等到「" + until + "」");
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    return ok(until.isEmpty()
                            ? "已等待 " + maxMs + "ms"
                            : "等待超时，未看到「" + until + "」");
                }
                case "phone_tap": {
                    int x = arguments.getInt("x");
                    int y = arguments.getInt("y");
                    boolean direct = ChatDemoAccessibilityService.tap(x, y);
                    if (!direct) {
                        run("input tap " + x + " " + y, 10000);
                    }
                    ChatDemoAccessibilityService.invalidateCache();
                    return ok(direct ? "已点击（无障碍手势）" : "已点击（shell 回退）");
                }
                case "phone_tap_shot": {
                    if (shotWidth <= 0 || screenWidth <= 0) {
                        return fail("还没有截图，请先调用 phone_screenshot");
                    }
                    int x = arguments.getInt("x");
                    int y = arguments.getInt("y");
                    int realX = Math.round(x * (screenWidth / (float) shotWidth));
                    int realY = Math.round(y * (screenHeight / (float) shotHeight));
                    boolean direct = ChatDemoAccessibilityService.tap(realX, realY);
                    if (!direct) {
                        run("input tap " + realX + " " + realY, 10000);
                    }
                    ChatDemoAccessibilityService.invalidateCache();
                    return ok("截图坐标 (" + x + "," + y + ") → 真实 (" + realX + "," + realY + ")"
                            + (direct ? "（无障碍手势）" : "（shell 回退）"));
                }
                case "phone_swipe": {
                    int x1 = arguments.getInt("x1");
                    int y1 = arguments.getInt("y1");
                    int x2 = arguments.getInt("x2");
                    int y2 = arguments.getInt("y2");
                    int duration = arguments.optInt("duration_ms", 300);
                    boolean direct = ChatDemoAccessibilityService.swipe(x1, y1, x2, y2, duration);
                    if (!direct) {
                        run("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + duration, 15000);
                    }
                    ChatDemoAccessibilityService.invalidateCache();
                    return ok(direct ? "已滑动（无障碍手势）" : "已滑动（shell 回退）");
                }
                case "phone_input": {
                    String text = arguments.optString("text");
                    if (text.isEmpty()) {
                        return fail("text 不能为空");
                    }
                    if (ChatDemoAccessibilityService.setFocusedText(text)) {
                        ChatDemoAccessibilityService.invalidateCache();
                        return ok("已输入 " + text.length() + " 个字符（无障碍 ACTION_SET_TEXT）");
                    }
                    String[] lines = text.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (i > 0) {
                            run("input keyevent 66", 10000);
                        }
                        if (!lines[i].isEmpty()) {
                            run("input text " + quoteInput(lines[i]), 15000);
                        }
                    }
                    ChatDemoAccessibilityService.invalidateCache();
                    return ok("已输入 " + text.length() + " 个字符（shell 回退）");
                }
                case "phone_key": {
                    String key = arguments.optString("key");
                    if (ChatDemoAccessibilityService.globalAction(key)) {
                        ChatDemoAccessibilityService.invalidateCache();
                        return ok("已执行按键 " + key + "（无障碍全局动作）");
                    }
                    String output = run("input keyevent " + keyCode(key), 10000);
                    ChatDemoAccessibilityService.invalidateCache();
                    return ok(output);
                }
                case "phone_apps": {
                    boolean thirdParty = arguments.optBoolean("third_party", true);
                    String withLabels = listAppsWithLabels(context, thirdParty);
                    if (!withLabels.isEmpty()) {
                        return ok(withLabels);
                    }
                    String command = "pm list packages " + (thirdParty ? "-3" : "-e") + " | sort";
                    String out = run(command, DEFAULT_TIMEOUT);
                    String compact = out.replace("package:", "").trim();
                    if (compact.isEmpty()) {
                        return ok("（没有应用）");
                    }
                    String[] packages = compact.split("\n");
                    if (packages.length > 200) {
                        compact = String.join("\n", java.util.Arrays.copyOf(packages, 200))
                                + "\n…（共 " + packages.length + " 个，已截断）";
                    }
                    return ok(compact);
                }
                case "phone_open": {
                    String pkg = arguments.optString("package").trim();
                    if (pkg.isEmpty()) {
                        return fail("package 不能为空");
                    }
                    Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch == null) {
                        return fail("打开失败：找不到可启动的 Activity（" + pkg + "）");
                    }
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launch);
                    ChatDemoAccessibilityService.invalidateCache();
                    return ok("已打开 " + pkg + "（PackageManager 直连）");
                }
                case "phone_stop": {
                    String pkg = arguments.optString("package").trim();
                    if (pkg.isEmpty()) {
                        return fail("package 不能为空");
                    }
                    ChatDemoAccessibilityService.invalidateCache();
                    if (ShizukuShell.running() && ShizukuShell.granted()) {
                        return ok(run("am force-stop " + shq(pkg), 10000));
                    }
                    // 没有 Shizuku：只能结束该应用的后台进程，前台界面由系统决定
                    android.app.ActivityManager manager = (android.app.ActivityManager)
                            context.getSystemService(Context.ACTIVITY_SERVICE);
                    if (manager != null) {
                        manager.killBackgroundProcesses(pkg);
                    }
                    return ok("已请求结束后台进程（没有 Shizuku，只能杀后台；前台界面可能仍在）");
                }
                case "phone_files": {
                    String path = arguments.optString("path", "/sdcard").trim();
                    return ok(listDirectory(path));
                }
                case "phone_read": {
                    String path = arguments.optString("path").trim();
                    if (path.isEmpty()) {
                        return fail("path 不能为空");
                    }
                    int offset = Math.max(arguments.optInt("offset", 0), 0);
                    int max = Math.min(Math.max(arguments.optInt("max_bytes", MAX_TEXT), 1), MAX_TEXT);
                    return ok(readFileSlice(path, offset, max));
                }
                case "phone_write": {
                    String path = arguments.optString("path").trim();
                    String content = arguments.optString("content");
                    if (path.isEmpty()) {
                        return fail("path 不能为空");
                    }
                    writeText(path, content);
                    return ok("已写入 " + path + "（" + content.getBytes(StandardCharsets.UTF_8).length + " bytes）");
                }
                case "phone_append": {
                    String path = arguments.optString("path").trim();
                    String content = arguments.optString("content");
                    if (path.isEmpty()) {
                        return fail("path 不能为空");
                    }
                    appendText(path, content);
                    return ok("已追加到 " + path);
                }
                case "phone_replace": {
                    String path = arguments.optString("path").trim();
                    String oldText = arguments.optString("old");
                    String newText = arguments.optString("new");
                    if (path.isEmpty() || oldText.isEmpty()) {
                        return fail("path/old 不能为空");
                    }
                    String content = readText(path, 256 * 1024);
                    if (!content.contains(oldText)) {
                        return ok("文件里没有找到要替换的内容");
                    }
                    int limit = arguments.optInt("count", 0);
                    String next;
                    if (limit > 0) {
                        StringBuilder builder = new StringBuilder();
                        int from = 0;
                        int replaced = 0;
                        while (replaced < limit) {
                            int index = content.indexOf(oldText, from);
                            if (index < 0) {
                                break;
                            }
                            builder.append(content, from, index).append(newText);
                            from = index + oldText.length();
                            replaced++;
                        }
                        builder.append(content.substring(from));
                        next = builder.toString();
                    } else {
                        next = content.replace(oldText, newText);
                    }
                    writeText(path, next);
                    return ok("已替换并保存：" + path);
                }
                case "phone_search_text": {
                    String pattern = arguments.optString("pattern");
                    String path = arguments.optString("path", "/sdcard").trim();
                    String include = arguments.optString("include");
                    if (pattern.isEmpty()) {
                        return fail("pattern 不能为空");
                    }
                    String command = "grep -RIn"
                            + (include.isEmpty() ? "" : " --include=" + shq(include))
                            + " -e " + shq(pattern) + " " + shq(path)
                            + " 2>/dev/null | head -80";
                    String out = run(command, 30000);
                    return ok(out.isEmpty() ? "（没有匹配）" : out);
                }
                case "phone_file_info": {
                    String path = arguments.optString("path").trim();
                    return ok(fileInfo(path));
                }
                case "phone_json_get": {
                    String path = arguments.optString("path").trim();
                    String pointer = arguments.optString("pointer").trim();
                    JSONObject root = new JSONObject(readText(path, 512 * 1024));
                    if (pointer.isEmpty()) {
                        return ok(root.toString(2));
                    }
                    Object value = jsonGet(root, pointer);
                    if (value == null) {
                        return ok("（不存在：" + pointer + "）");
                    }
                    return ok(value instanceof JSONObject || value instanceof JSONArray
                            ? ((Object) value).toString()
                            : String.valueOf(value));
                }
                case "phone_json_set": {
                    String path = arguments.optString("path").trim();
                    String pointer = arguments.optString("pointer").trim();
                    if (path.isEmpty() || pointer.isEmpty()) {
                        return fail("path/pointer 不能为空");
                    }
                    JSONObject root = new JSONObject(readText(path, 512 * 1024));
                    Object value = parseJsonValue(arguments.optString("value"));
                    if (!jsonSet(root, pointer.split("\\."), 0, value)) {
                        return fail("无法写入路径：" + pointer);
                    }
                    writeText(path, root.toString(2));
                    return ok("已更新 " + pointer + " 并保存：" + path);
                }
                case "phone_zip_list": {
                    String path = arguments.optString("path").trim();
                    try {
                        return ok(zipList(path));
                    } catch (Throwable directError) {
                        return ok(run("unzip -l " + shq(path) + " | head -80", 30000));
                    }
                }
                case "phone_zip_extract": {
                    String path = arguments.optString("path").trim();
                    String dest = arguments.optString("dest").trim();
                    if (path.isEmpty() || dest.isEmpty()) {
                        return fail("path/dest 不能为空");
                    }
                    try {
                        zipExtract(path, dest);
                        return ok("已解压到 " + dest + "（原生 ZipFile）");
                    } catch (Throwable directError) {
                        run("mkdir -p " + shq(dest) + " && unzip -o " + shq(path) + " -d " + shq(dest), 120000);
                        return ok("已解压到 " + dest + "（shell 回退）");
                    }
                }
                case "phone_docx_text": {
                    String path = arguments.optString("path").trim();
                    try {
                        String text = docxText(path);
                        return ok(text.isEmpty() ? "（没有提取到文本）" : text);
                    } catch (Throwable directError) {
                        String out = run(
                                "unzip -p " + shq(path) + " word/document.xml 2>/dev/null"
                                        + " | sed -e 's/<[^>]*>/ /g' | tr -s ' '",
                                30000
                        );
                        return ok(out.isEmpty() ? "（没有提取到文本）" : out);
                    }
                }
                case "phone_screenshot": {
                    Bitmap bitmap = ChatDemoAccessibilityService.screenshot();
                    if (bitmap == null) {
                        return fail("截图失败：需要启用无障碍读屏（设置 → Agent → 手机能力）。");
                    }
                    try {
                        String path = saveScreenshot(context, bitmap);
                        return screenshotResult(bitmap, path);
                    } catch (Throwable error) {
                        bitmap.recycle();
                        return fail("截图保存失败：" + (error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage()));
                    }
                }
                case "phone_view_image": {
                    String path = arguments.optString("path").trim();
                    if (path.isEmpty()) {
                        return fail("path 不能为空");
                    }
                    return viewImage(path);
                }
                case "phone_device": {
                    java.util.Locale locale = java.util.Locale.US;
                    android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                    StatFs data = new StatFs(android.os.Environment.getDataDirectory().getPath());
                    BatteryManager battery = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                    int level = battery == null ? -1
                            : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    StringBuilder builder = new StringBuilder();
                    builder.append("model: ").append(Build.MODEL).append('\n');
                    builder.append("brand: ").append(Build.BRAND).append('\n');
                    builder.append("android: ").append(Build.VERSION.RELEASE).append('\n');
                    builder.append("sdk: ").append(Build.VERSION.SDK_INT).append('\n');
                    builder.append("screen: ").append(metrics.widthPixels).append('x')
                            .append(metrics.heightPixels).append(" @").append(metrics.densityDpi).append("dpi\n");
                    builder.append("battery: ").append(level >= 0 ? level + "%" : "unknown").append('\n');
                    builder.append("storage: ")
                            .append(String.format(locale, "%.1f/%.1f GB free",
                                    data.getAvailableBytes() / 1e9,
                                    data.getTotalBytes() / 1e9));
                    return ok(builder.toString());
                }
                case "phone_setting_get": {
                    String namespace = namespace(arguments.optString("namespace"));
                    String key = arguments.optString("key").trim();
                    if (key.isEmpty()) {
                        return fail("key 不能为空");
                    }
                    try {
                        String value;
                        if ("secure".equals(namespace)) {
                            value = Settings.Secure.getString(context.getContentResolver(), key);
                        } else if ("global".equals(namespace)) {
                            value = Settings.Global.getString(context.getContentResolver(), key);
                        } else {
                            value = Settings.System.getString(context.getContentResolver(), key);
                        }
                        return ok(value == null ? "（null）" : value);
                    } catch (Throwable directError) {
                        return ok(run("settings get " + namespace + " " + shq(key), 10000));
                    }
                }
                case "phone_setting_put": {
                    String namespace = namespace(arguments.optString("namespace"));
                    String key = arguments.optString("key").trim();
                    String value = arguments.optString("value");
                    if (key.isEmpty()) {
                        return fail("key 不能为空");
                    }
                    // 写系统设置需要特权；这里保留 shell 兜底
                    run("settings put " + namespace + " " + shq(key) + " " + shq(value), 10000);
                    return ok("已写入 " + namespace + "/" + key + " = " + value);
                }
                case "phone_clipboard_get": {
                    ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (manager == null || !manager.hasPrimaryClip()) {
                        return ok("（剪贴板为空）");
                    }
                    ClipData clip = manager.getPrimaryClip();
                    if (clip == null || clip.getItemCount() == 0) {
                        return ok("（剪贴板为空）");
                    }
                    CharSequence text = clip.getItemAt(0).coerceToText(context);
                    return ok(text == null ? "" : text.toString());
                }
                case "phone_clipboard_set": {
                    ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (manager == null) {
                        return fail("剪贴板不可用");
                    }
                    manager.setPrimaryClip(ClipData.newPlainText("agent", arguments.optString("text")));
                    return ok("已写入剪贴板");
                }
                case "phone_notifications": {
                    String out = run("dumpsys notification --noredact | head -c 12000", 20000);
                    return ok(out);
                }
                default:
                    return fail("未知手机工具：" + name);
            }
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return fail(message);
        }
    }

    /**
     * 执行一条需要 shell 的命令。
     *
     * 有 Shizuku 就用 uid 2000 的 shell（pm/am/settings 都能跑）；没有 Shizuku 时
     * 退回内置的 app 身份 shell——grep/stat/unzip/cat 这类文件文本命令照常可用，
     * pm/am/settings 会失败，由调用方转成明确错误或降级处理。
     */
    private static String run(String command, int timeoutMs) throws Exception {
        String text;
        if (ShizukuShell.running() && ShizukuShell.granted()) {
            text = ShizukuShell.exec(command, timeoutMs);
        } else {
            Context context = currentContext;
            if (context == null) {
                throw new IOException("没有可用的 shell 后端");
            }
            ProcessRunner.Result result = NativeShell.runRaw(context, command, timeoutMs);
            if (result.timedOut) {
                throw new IOException("命令超时（" + timeoutMs + "ms）");
            }
            if (result.exit != 0) {
                String detail = result.stderr == null ? "" : result.stderr.trim();
                throw new IOException(detail.isEmpty()
                        ? "命令失败（exit " + result.exit + "）"
                        : "命令失败（exit " + result.exit + "）：" + detail);
            }
            text = result.stdout;
        }
        return text == null ? "" : text.trim();
    }

    /** 用 PackageManager 列出「应用名 | 包名」，比纯包名更适合模型挑选 */
    private static String listAppsWithLabels(Context context, boolean thirdParty) {
        try {
            android.content.pm.PackageManager manager = context.getPackageManager();
            java.util.List<android.content.pm.ApplicationInfo> apps =
                    manager.getInstalledApplications(0);
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (android.content.pm.ApplicationInfo info : apps) {
                // MIUI 会把不少预装/更新应用也标成 FLAG_SYSTEM，用安装目录判断更可靠
                boolean system = info.sourceDir == null || !info.sourceDir.startsWith("/data/");
                if (thirdParty && system) {
                    continue;
                }
                String label = manager.getApplicationLabel(info).toString();
                lines.add(label + " | " + info.packageName);
            }
            java.util.Collections.sort(lines);
            if (lines.size() > 200) {
                lines = new java.util.ArrayList<>(lines.subList(0, 200));
                lines.add("…（仅显示前 200 个）");
            }
            return String.join("\n", lines);
        } catch (Throwable error) {
            return "";
        }
    }

    private static String saveScreenshot(Context context, Bitmap bitmap) throws Exception {
        String name = "DLA-" + System.currentTimeMillis() + ".png";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DLA");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
            );
            if (uri == null) {
                throw new IOException("无法写入相册");
            }
            try (java.io.OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("无法打开相册输出流");
                }
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            return "Pictures/DLA/" + name;
        }
        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "DLA");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建截图目录");
        }
        File target = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(target)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return target.getAbsolutePath();
    }

    private static Result screenshotResult(Bitmap bitmap, String path) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int longest = Math.max(width, height);
        Bitmap scaled = bitmap;
        if (longest > 1280) {
            float ratio = 1280f / longest;
            scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    Math.max(1, Math.round(width * ratio)),
                    Math.max(1, Math.round(height * ratio)),
                    true
            );
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 78, jpeg);
        String imageBase64 = Base64.getEncoder().encodeToString(jpeg.toByteArray());
        shotWidth = scaled.getWidth();
        shotHeight = scaled.getHeight();
        screenWidth = width;
        screenHeight = height;
        float scale = width / (float) Math.max(1, scaled.getWidth());
        String note = "（截图 " + scaled.getWidth() + "x" + scaled.getHeight()
                + "，实际屏幕 " + width + "x" + height
                + "；坐标换算：真实 = 截图坐标 × "
                + String.format(java.util.Locale.US, "%.3f", scale)
                + "，phone_tap 必须用真实坐标；JPEG " + jpeg.size() + " bytes）";
        if (scaled != bitmap) {
            scaled.recycle();
        }
        bitmap.recycle();
        return new Result(
                "截图已保存：" + path + "\n" + note,
                false,
                imageBase64,
                "image/jpeg"
        );
    }

    /**
     * 读本机图片给模型看（agent 的「读图」能力）。
     *
     * 参考 DSH 的 read_image：先按扩展名/魔数确认是图片，再压到长边 1280 的 JPEG，
     * 返回一段文字信封 + 图片块（AgentRunner 会把 imageBase64 转成 image block）。
     * 压缩是为了控制请求体积：原图动辄几 MB，一张就吃掉大量 token。
     */
    private static Result viewImage(String path) {
        File file = new File(path);
        if (!file.isFile()) {
            return fail("文件不存在或不是文件：" + path);
        }
        long size = file.length();
        if (size <= 0) {
            return fail("文件是空的：" + path);
        }
        if (size > 32L * 1024 * 1024) {
            return fail("图片太大（" + (size / 1024 / 1024) + "MB）。先用 phone_sh 里的工具压到 32MB 以内再读。");
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream probe = new FileInputStream(file)) {
                BitmapFactory.decodeStream(probe, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return fail("这不是能解码的图片（支持 jpg/png/webp/gif）：" + path);
            }
            int sample = 1;
            while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap decoded;
            try (InputStream input = new FileInputStream(file)) {
                decoded = BitmapFactory.decodeStream(input, null, options);
            }
            if (decoded == null) {
                return fail("图片解码失败：" + path);
            }
            int originalWidth = decoded.getWidth();
            int originalHeight = decoded.getHeight();
            int longest = Math.max(originalWidth, originalHeight);
            Bitmap scaled = decoded;
            if (longest > 1280) {
                float ratio = 1280f / longest;
                scaled = Bitmap.createScaledBitmap(
                        decoded,
                        Math.max(1, Math.round(originalWidth * ratio)),
                        Math.max(1, Math.round(originalHeight * ratio)),
                        true
                );
            }
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, jpeg);
            int width = scaled.getWidth();
            int height = scaled.getHeight();
            if (scaled != decoded) {
                scaled.recycle();
            }
            decoded.recycle();
            byte[] bytes = jpeg.toByteArray();
            if (bytes.length == 0) {
                return fail("图片压缩后为空：" + path);
            }
            String envelope = "<path>" + path + "</path>\n<type>image</type>\n<content>\n"
                    + "image/jpeg " + width + "x" + height + " px, " + bytes.length + " bytes"
                    + "（原图 " + originalWidth + "x" + originalHeight + "）\n</content>";
            return new Result(envelope, false, Base64.getEncoder().encodeToString(bytes), "image/jpeg");
        } catch (Throwable error) {
            return fail("读取图片失败：" + (error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    private static String zipList(String path) throws Exception {
        try (ZipFile zip = new ZipFile(path)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            StringBuilder builder = new StringBuilder();
            int count = 0;
            while (entries.hasMoreElements() && count < 80) {
                ZipEntry entry = entries.nextElement();
                builder.append(entry.isDirectory() ? "d " : "- ")
                        .append(entry.getName())
                        .append("  ")
                        .append(entry.getSize())
                        .append("B\n");
                count++;
            }
            return builder.length() == 0 ? "（空压缩包）" : builder.toString();
        }
    }

    private static void zipExtract(String path, String dest) throws Exception {
        File root = new File(dest);
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("无法创建目标目录");
        }
        String rootPath = root.getCanonicalPath() + File.separator;
        try (ZipFile zip = new ZipFile(path)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File target = new File(root, entry.getName());
                if (!target.getCanonicalPath().startsWith(rootPath)) {
                    throw new IOException("压缩包路径越界：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!target.exists() && !target.mkdirs()) {
                        throw new IOException("无法创建目录：" + target);
                    }
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录：" + parent);
                }
                try (InputStream input = zip.getInputStream(entry);
                     java.io.OutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static String docxText(String path) throws Exception {
        try (ZipFile zip = new ZipFile(path)) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) {
                throw new IOException("不是有效的 docx");
            }
            try (InputStream input = zip.getInputStream(entry)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                String xml = output.toString("UTF-8");
                String text = xml.replaceAll("<[^>]+>", " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace("&apos;", "'")
                        .replaceAll("\\s+", " ")
                        .trim();
                return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
            }
        }
    }

    private static String readText(String path, int maxBytes) throws Exception {
        File file = new File(path);
        if (file.isFile() && file.canRead()) {
            if (file.length() > maxBytes) {
                throw new IOException("文件过大（" + file.length() + " bytes，上限 " + maxBytes + "）");
            }
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        // 共享存储通常没有 App 直读权限；这里保留 shell 兜底
        String sizeText = run("stat -c %s " + shq(path), 10000).trim();
        String lower = sizeText.toLowerCase(java.util.Locale.US);
        if (lower.contains("no such file") || lower.contains("not found")
                || lower.contains("permission denied")) {
            throw new IOException("文件不存在或不可读：" + path);
        }
        long size;
        try {
            size = Long.parseLong(sizeText.replaceAll("[^0-9]", ""));
        } catch (Exception error) {
            throw new IOException("无法读取文件大小：" + sizeText);
        }
        if (size > maxBytes) {
            throw new IOException("文件过大（" + size + " bytes，上限 " + maxBytes + "）");
        }
        return ShizukuShell.exec("cat " + shq(path), 30000);
    }

    private static void writeText(String path, String content) throws Exception {
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                throw new IOException("父目录不存在");
            }
            java.nio.file.Files.write(
                    file.toPath(),
                    content.getBytes(StandardCharsets.UTF_8)
            );
            return;
        } catch (Throwable directError) {
            String encoded = Base64.getEncoder()
                    .encodeToString(content.getBytes(StandardCharsets.UTF_8));
            run("printf '%s' '" + encoded + "' | base64 -d > " + shq(path), 30000);
        }
    }

    private static void appendText(String path, String content) throws Exception {
        try {
            File file = new File(path);
            if (!file.exists()) {
                throw new IOException("文件不存在");
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return;
        } catch (Throwable directError) {
            String encoded = Base64.getEncoder()
                    .encodeToString(content.getBytes(StandardCharsets.UTF_8));
            run("printf '%s' '" + encoded + "' | base64 -d >> " + shq(path), DEFAULT_TIMEOUT);
        }
    }

    private static String listDirectory(String path) throws Exception {
        File dir = new File(path);
        File[] files = dir.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            StringBuilder builder = new StringBuilder();
            for (File file : files) {
                builder.append(file.isDirectory() ? "d " : "- ")
                        .append(file.getName())
                        .append("  ")
                        .append(file.isDirectory() ? "" : file.length() + "B  ")
                        .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                                .format(new java.util.Date(file.lastModified())))
                        .append('\n');
            }
            return builder.length() == 0 ? "（空目录）" : builder.toString();
        }
        // /sdcard 等共享目录可能没有直读权限
        return run("ls -la " + shq(path), DEFAULT_TIMEOUT);
    }

    private static String readFileSlice(String path, int offset, int maxBytes) throws Exception {
        File file = new File(path);
        if (file.isFile() && file.canRead()) {
            try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file, "r")) {
                long size = input.length();
                if (offset >= size) {
                    return "size=" + size + " bytes, offset=" + offset + "\n（offset 已超过文件末尾）";
                }
                input.seek(offset);
                byte[] buffer = new byte[(int) Math.min(maxBytes, size - offset)];
                int read = input.read(buffer);
                return "size=" + size + " bytes, offset=" + offset + "\n"
                        + new String(buffer, 0, Math.max(read, 0), StandardCharsets.UTF_8);
            }
        }
        String size = run("stat -c %s " + shq(path), 10000).trim();
        String lower = size.toLowerCase(java.util.Locale.US);
        if (lower.contains("no such file") || lower.contains("not found")
                || lower.contains("cannot") || lower.contains("permission denied")) {
            throw new IOException("文件不存在或不可读：" + path);
        }
        String command = offset > 0
                ? "tail -c +" + (offset + 1) + " " + shq(path) + " | head -c " + maxBytes
                : "head -c " + maxBytes + " " + shq(path);
        String content = run(command, DEFAULT_TIMEOUT);
        if (content.toLowerCase(java.util.Locale.US).contains("no such file")) {
            throw new IOException("文件不存在：" + path);
        }
        return "size=" + size + " bytes, offset=" + offset + "\n" + content;
    }

    private static String fileInfo(String path) throws Exception {
        File file = new File(path);
        if (file.exists()) {
            return file.getAbsolutePath()
                    + " | " + (file.isDirectory() ? "directory" : file.length() + " bytes")
                    + " | " + new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
            ).format(new java.util.Date(file.lastModified()));
        }
        return run("stat -c '%n | %s bytes | %F | %y' " + shq(path), 10000);    }

    private static Object jsonGet(Object node, String pointer) {
        Object current = node;
        for (String part : pointer.split("\\.")) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                if (current instanceof JSONObject) {
                    current = ((JSONObject) current).opt(part);
                } else if (current instanceof JSONArray) {
                    current = ((JSONArray) current).opt(Integer.parseInt(part));
                } else {
                    return null;
                }
            } catch (Exception error) {
                return null;
            }
        }
        return current;
    }

    private static boolean jsonSet(Object node, String[] parts, int index, Object value) {
        if (node == null || index >= parts.length) {
            return false;
        }
        String key = parts[index];
        boolean last = index == parts.length - 1;
        try {
            if (node instanceof JSONObject) {
                JSONObject object = (JSONObject) node;
                if (last) {
                    object.put(key, value);
                    return true;
                }
                Object child = object.opt(key);
                if (child == null || child == JSONObject.NULL) {
                    child = new JSONObject();
                    object.put(key, child);
                }
                return jsonSet(child, parts, index + 1, value);
            }
            if (node instanceof JSONArray) {
                JSONArray array = (JSONArray) node;
                int position = Integer.parseInt(key);
                if (last) {
                    array.put(position, value);
                    return true;
                }
                Object child = array.opt(position);
                if (child == null || child == JSONObject.NULL) {
                    child = new JSONObject();
                    array.put(position, child);
                }
                return jsonSet(child, parts, index + 1, value);
            }
        } catch (Exception ignored) {
            // 路径不合法
        }
        return false;
    }

    private static Object parseJsonValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        try {
            if (value.startsWith("{")) {
                return new JSONObject(value);
            }
            if (value.startsWith("[")) {
                return new JSONArray(value);
            }
        } catch (Exception ignored) {
            // 当作字符串
        }
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        if ("null".equals(value)) {
            return JSONObject.NULL;
        }
        if (value.matches("-?\\d+")) {
            return Long.parseLong(value);
        }
        if (value.matches("-?\\d+\\.\\d+")) {
            return Double.parseDouble(value);
        }
        return value;
    }

    private static String namespace(String value) {
        if ("system".equals(value) || "secure".equals(value) || "global".equals(value)) {
            return value;
        }
        return "system";
    }

    private static String keyCode(String key) {
        String value = key == null ? "" : key.trim().toLowerCase();
        switch (value) {
            case "back":
                return "KEYCODE_BACK";
            case "home":
                return "KEYCODE_HOME";
            case "recents":
            case "recent":
                return "KEYCODE_APP_SWITCH";
            case "enter":
                return "KEYCODE_ENTER";
            case "delete":
            case "del":
                return "KEYCODE_DEL";
            case "power":
                return "KEYCODE_POWER";
            case "volume_up":
                return "KEYCODE_VOLUME_UP";
            case "volume_down":
                return "KEYCODE_VOLUME_DOWN";
            case "notifications":
                return "KEYCODE_NOTIFICATION";
            default:
                return value.startsWith("KEYCODE_") ? value : "KEYCODE_BACK";
        }
    }

    private static String quoteInput(String text) {
        String escaped = text.replace(" ", "%s").replace("'", "'\\''");
        return "'" + escaped + "'";
    }

    private static String shq(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String compactScreen(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            return "（没有读到界面内容，可能当前页面禁止无障碍读取）";
        }
        StringBuilder builder = new StringBuilder();
        Matcher node = Pattern.compile("<node\\b[^>]*>").matcher(xml);
        int count = 0;
        while (node.find() && count < 300) {
            String tag = node.group();
            String text = attr(tag, "text");
            String desc = attr(tag, "content-desc");
            String cls = attr(tag, "class");
            String bounds = attr(tag, "bounds");
            boolean clickable = "true".equals(attr(tag, "clickable"));
            if (text.isEmpty() && desc.isEmpty() && !clickable) {
                continue;
            }
            builder.append("- ");
            if (!text.isEmpty()) {
                builder.append('"').append(text).append('"');
            }
            if (!desc.isEmpty()) {
                builder.append(" desc=").append(desc);
            }
            builder.append(' ').append(simpleClass(cls)).append(' ').append(bounds);
            if (clickable) {
                builder.append(" [click]");
            }
            builder.append('\n');
            count++;
        }
        return builder.length() == 0 ? "（界面没有可读文本）" : builder.toString();
    }

    private static String attr(String tag, String name) {
        Matcher matcher = Pattern.compile(name + "=\"([^\"]*)\"").matcher(tag);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String simpleClass(String value) {
        if (value == null || value.isEmpty()) {
            return "View";
        }
        int index = value.lastIndexOf('.');
        return index >= 0 ? value.substring(index + 1) : value;
    }

    private static JSONObject tool(String name, String description, JSONObject inputSchema) {
        JSONObject tool = new JSONObject();
        try {
            tool.put("name", name);
            tool.put("description", description);
            tool.put("input_schema", inputSchema);
        } catch (Exception ignored) {
            // 不会发生
        }
        return tool;
    }

    private static JSONObject schema(JSONObject properties, String... required) {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            schema.put("properties", properties);
            JSONArray array = new JSONArray();
            for (String item : required) {
                array.put(item);
            }
            if (array.length() > 0) {
                schema.put("required", array);
            }
        } catch (Exception ignored) {
            // 不会发生
        }
        return schema;
    }

    private static JSONObject props(Object... pairs) {
        JSONObject properties = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                properties.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
        } catch (Exception ignored) {
            // 不会发生
        }
        return properties;
    }

    private static JSONObject p(String type, String description) {
        JSONObject property = new JSONObject();
        try {
            property.put("type", type);
            property.put("description", description);
        } catch (Exception ignored) {
            // 不会发生
        }
        return property;
    }

    private static Result ok(String text) {
        String value = text == null ? "" : text;
        if (value.length() > MAX_TEXT) {
            value = value.substring(0, MAX_TEXT) + "\n…（已截断）";
        }
        return new Result(value, false);
    }

    private static Result fail(String message) {
        return new Result("错误：" + message, true);
    }
}

