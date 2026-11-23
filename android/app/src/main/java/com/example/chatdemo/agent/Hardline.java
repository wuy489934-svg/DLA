package com.example.chatdemo.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 最小硬性拦截：只挡「不可逆且影响整个存储」的命令。
 *
 * 应用是 untrusted_app 身份，本来就做不了刷机/改系统，但能删用户文件，
 * 所以这里只保护少量根目录与分区设备，不做复杂的命令解析——
 * 越简单越不容易误伤（rm -rf /sdcard/Download/tmp 这类子目录删除必须放行）。
 */
public final class Hardline {

    /** 受保护的根：删到这一层就拒绝 */
    private static final String[] PROTECTED_ROOTS = {
            "/", "/sdcard", "/storage", "/storage/emulated/0", "/data", "/system",
            "/vendor", "/product", "/apex", "/proc", "/sys", "~", "$HOME"
    };

    private static final Pattern MKFS = Pattern.compile("(?i)\\bmkfs(\\.[a-z0-9]+)?\\b");
    private static final Pattern DD_BLOCK = Pattern.compile("(?i)\\bdd\\b[^\\n]*\\bof\\s*=\\s*/dev/(block|sd|mmcblk)");
    private static final Pattern REBOOT = Pattern.compile("(?i)(^|[;&|]\\s*)\\s*(reboot|shutdown|poweroff)\\b");
    private static final Pattern PM_UNINSTALL = Pattern.compile("(?i)\\bpm\\s+uninstall\\b");
    private static final Pattern WIPE = Pattern.compile("(?i)\\bwipe\\s+(data|system|all)\\b");

    private Hardline() {}

    /** 返回拒绝原因；放行返回 null。 */
    public static String blockedReason(String command) {
        if (command == null || command.trim().isEmpty()) {
            return null;
        }
        String lower = command.toLowerCase(Locale.ROOT);
        if (MKFS.matcher(lower).find()) {
            return "禁止格式化分区（mkfs）";
        }
        if (DD_BLOCK.matcher(lower).find()) {
            return "禁止直接写块设备（dd of=/dev/block…）";
        }
        if (REBOOT.matcher(lower).find()) {
            return "禁止在 shell 里重启/关机";
        }
        if (PM_UNINSTALL.matcher(lower).find()) {
            return "禁止卸载应用（pm uninstall）";
        }
        if (WIPE.matcher(lower).find()) {
            return "禁止擦除数据";
        }
        return rmRootReason(lower);
    }

    /** 找出 rm 递归删到受保护根的情况。 */
    private static String rmRootReason(String lower) {
        for (String segment : splitSegments(lower)) {
            String[] tokens = segment.trim().split("\\s+");
            if (tokens.length == 0 || !"rm".equals(tokens[0])) {
                continue;
            }
            List<String> args = new ArrayList<>();
            for (int i = 1; i < tokens.length; i++) {
                if (tokens[i].startsWith("-")) {
                    continue;
                }
                args.add(tokens[i]);
            }
            for (String arg : args) {
                for (String root : PROTECTED_ROOTS) {
                    if (isRootTarget(arg, root)) {
                        return "禁止删除受保护目录：" + arg;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isRootTarget(String arg, String root) {
        if (arg.equals(root)) {
            return true;
        }
        String base = root.endsWith("/") ? root : root + "/";
        if (arg.equals(base) || arg.equals(base + "*") || arg.equals(base + ".")) {
            return true;
        }
        // 也覆盖 /sdcard/../ 这种绕法
        return arg.startsWith(base) && arg.contains("..") && normalize(arg).equals(root);
    }

    private static String normalize(String path) {
        String[] parts = path.split("/");
        List<String> stack = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            } else {
                stack.add(part);
            }
        }
        return "/" + String.join("/", stack);
    }

    /** 按 ; && || | 换行 切成简单命令段，粗判即可。 */
    private static List<String> splitSegments(String command) {
        List<String> segments = new ArrayList<>();
        for (String line : command.split("\\n")) {
            for (String part : line.split("&&|\\|\\||;|\\|")) {
                if (!part.trim().isEmpty()) {
                    segments.add(part);
                }
            }
        }
        return segments;
    }
}
