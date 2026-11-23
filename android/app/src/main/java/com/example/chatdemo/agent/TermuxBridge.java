package com.example.chatdemo.agent;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Termux RUN_COMMAND 桥。
 *
 * 只负责执行一条命令并等待结果；不做工具列表注入。
 * Termux 需要：
 * 1. DLA 被授予 com.termux.permission.RUN_COMMAND；
 * 2. ~/.termux/termux.properties 里 allow-external-apps=true。
 *
 * <p><b>为什么用户点停止时不能只依靠 Java 侧的 cancelled：</b>
 * RUN_COMMAND 里的命令是 Termux 进程 fork 出来的 shell 在执行，它和 DLA 的
 * AgentRunner 线程、AgentService 进程都没有父子关系；即便杀掉 DLA 进程，
 * Termux 也只是失去一个客户端，shell 会继续跑。Java 侧把 cancelled 置 true
 * 只能让 AgentRunner 不再等待结果，正在跑的 npm/python/下载/写文件等子进程会
 * 脱离父进程继续执行。所以每次 run() 都生成唯一 runId，把命令包进一个带
 * runId 标记、且可精确定位的进程组里：
 * <ul>
 *   <li>首次执行：wrapper 用 bash 内建 job control 给命令开独立进程组，
 *       把 pgid 写进 ~/.dla_runs/&lt;runId&gt;.pid，并让 runId 出现在
 *       命令 cmdline 里（pkill -f 兜底）。</li>
 *   <li>取消：{@link #cancel}/{@link #cancelAll} 再发一条独立的 RUN_COMMAND，
 *       先 TERM 整个进程组 + pkill -f，2 秒后 KILL 整组 + pkill -f。</li>
 * </ul>
 * 即使 Termux 未安装、未授权、setsid/pkill 不存在，取消路径也只吞异常，不影响主流程。
 */
public final class TermuxBridge {

    private static final String TAG = "TermuxBridge";
    public static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";
    private static final String EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    private static final String EXTRA_RESULT_BUNDLE = "result";
    private static final String EXTRA_STDOUT = "stdout";
    private static final String EXTRA_STDERR = "stderr";
    private static final String EXTRA_EXIT_CODE = "exitCode";
    private static final String EXTRA_ERR = "err";
    private static final String EXTRA_ERRMSG = "errmsg";
    /**
     * 结果回传广播。
     *
     * <p>TermuxResultService 跑在 <code>:agent</code> 进程，而「设置 → 高级设置」和网页壳都在主进程：
     * 两边各有一份静态 PENDING，主进程放进去的 future 永远不会被 :agent 里的 service 完成，
     * 表现为「测试连通」等 8 秒后报「Termux 没有返回结果」，可 Agent 工具里又一切正常。
     * 所以 service 拿到结果后额外广播一次（只发给本应用），调用方用运行时 receiver 收。
     */
    public static final String ACTION_RESULT = "com.example.chatdemo.action.TERMUX_RESULT";
    private static final String EXTRA_REQUEST_ID = "dla_termux_request_id";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String HOME = "/data/data/com.termux/files/home";
    /** 出现在被包装命令 cmdline / 环境里的标记前缀；后面拼 runId。 */
    private static final String MARKER_PREFIX = "DLA_RUN_ID=";
    /** Termux home 下记录「runId -> 进程组 id」的目录。 */
    private static final String RUN_DIR = ".dla_runs";

    private static final ConcurrentHashMap<Integer, CompletableFuture<Result>> PENDING =
            new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1000);
    /**
     * 仍在 Termux 里跑的 runId。run() 开始时加入、结束时移除；
     * cancelAll() 遍历它逐个杀进程组。
     */
    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();

    private TermuxBridge() {}

    public static final class Result {
        public final String stdout;
        public final String stderr;
        public final int exitCode;
        public final String error;

        Result(String stdout, String stderr, int exitCode, String error) {
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.exitCode = exitCode;
            this.error = error == null ? "" : error;
        }

        public boolean isError() {
            return !error.isEmpty() || exitCode != 0;
        }

        public String text() {
            StringBuilder builder = new StringBuilder();
            if (!stdout.isEmpty()) {
                builder.append(stdout);
            }
            if (!stderr.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append("[stderr]\n").append(stderr);
            }
            if (!error.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append("[termux] ").append(error);
            }
            if (builder.length() == 0) {
                builder.append("(无输出，exit=").append(exitCode).append(')');
            }
            return builder.toString().trim();
        }
    }

    public static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    public static JSONObject status(Context context) {
        JSONObject status = new JSONObject();
        try {
            boolean installed = isInstalled(context);
            status.put("installed", installed);
            if (installed) {
                status.put("version", context.getPackageManager()
                        .getPackageInfo(TERMUX_PACKAGE, 0).versionName);
                status.put("package", TERMUX_PACKAGE);
            }
            status.put("granted", context.checkSelfPermission(
                    "com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED);
        } catch (Exception error) {
            try {
                status.put("installed", false);
                status.put("error", error.getMessage());
            } catch (Exception ignored) {
                // 不会发生
            }
        }
        return status;
    }

    /**
     * 执行一条命令并等待 Termux 回传结果。
     *
     * 命令会被包进一层 wrapper：wrapper 给真正的命令单开一个进程组，并把
     * 进程组 id 写到 Termux home 的 ~/.dla_runs/&lt;runId&gt;.pid。这样
     * {@link #cancel} 只要拿到 runId 就能按进程组把整棵进程树（不是只有
     * shell 本身）杀掉，见类注释。
     */
    public static Result run(Context context, String command, long timeoutMs) {
        if (!isInstalled(context)) {
            return new Result("", "", 1,
                    "未安装 Termux。请先安装 Termux，再在设置 → Agent → Termux 环境里完成配置。");
        }
        if (command == null || command.trim().isEmpty()) {
            return new Result("", "", 1, "命令为空");
        }
        long timeout = Math.max(2000L, Math.min(timeoutMs, 600_000L));
        String runId = UUID.randomUUID().toString();
        final int id = NEXT_ID.incrementAndGet();
        CompletableFuture<Result> future = new CompletableFuture<>();
        PENDING.put(id, future);
        ACTIVE.add(runId);
        // 跨进程唤醒：结果由 :agent 里的 TermuxResultService 广播回来（见 ACTION_RESULT 注释）。
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent receiverIntent) {
                if (receiverIntent == null) {
                    return;
                }
                if (receiverIntent.getIntExtra(EXTRA_REQUEST_ID, -1) != id) {
                    return;
                }
                complete(id, receiverIntent.getBundleExtra(EXTRA_RESULT_BUNDLE));
            }
        };
        boolean registered = false;
        try {
            ContextCompat.registerReceiver(
                    context,
                    receiver,
                    new IntentFilter(ACTION_RESULT),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            registered = true;

            Intent resultIntent = new Intent(context, TermuxResultService.class);
            resultIntent.putExtra(EXTRA_REQUEST_ID, id);
            int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getService(
                    context, id, resultIntent, flags);

            // 参数依次是：-lc <wrapper> <$0> <$1=原命令>。
            // 原命令只作为 $1 传给 wrapper，不在 Java 侧拼 shell 引号，避免转义/注入问题。
            Intent intent = new Intent();
            intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
            intent.setAction(ACTION_RUN_COMMAND);
            intent.putExtra(EXTRA_COMMAND_PATH, BASH);
            intent.putExtra(EXTRA_ARGUMENTS,
                    new String[]{"-lc", wrapperScript(runId), "dla-run", command});
            intent.putExtra(EXTRA_WORKDIR, HOME);
            intent.putExtra(EXTRA_BACKGROUND, true);
            intent.putExtra(EXTRA_COMMAND_LABEL, "DLA Termux command");
            intent.putExtra(EXTRA_PENDING_INTENT, pending);
            context.startService(intent);

            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            // 工具层已经不等了：不能让命令继续在 Termux 里裸跑，顺手杀掉本次 runId。
            cancel(context, runId);
            return new Result("", "", 124,
                    "Termux 没有返回结果（已尝试终止该命令）。请确认 Termux 已允许外部应用执行命令：\n"
                            + "在 Termux 执行 echo \"allow-external-apps=true\" >> ~/.termux/termux.properties "
                            + "&& termux-reload-settings；并给 DLA 授予“Run commands in Termux environment”权限。");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancel(context, runId);
            return new Result("", "", 130, "已取消");
        } catch (SecurityException error) {
            return new Result("", "", 126,
                    "没有 Termux RUN_COMMAND 权限。请到 Android 设置 → 应用 → DLA → 权限，"
                            + "授予“Run commands in Termux environment”。");
        } catch (Exception error) {
            return new Result("", "", 1,
                    error.getMessage() == null ? error.getClass().getSimpleName()
                            : error.getMessage());
        } finally {
            if (registered) {
                try {
                    context.unregisterReceiver(receiver);
                } catch (Throwable throwable) {
                    Log.w(TAG, "unregister termux receiver failed: " + throwable.getMessage());
                }
            }
            ACTIVE.remove(runId);
            PENDING.remove(id);
        }
    }

    /**
     * 杀掉当前所有在跑的 Termux 命令（先 TERM，2 秒后 KILL）。
     *
     * 只负责「发一条新的 RUN_COMMAND」就返回，不等待、不阻塞调用线程；
     * Termux 未安装 / 没有 RUN_COMMAND 权限 / 服务被禁用时静默失败，绝不抛异常。
     */
    public static void cancelAll(Context context) {
        try {
            List<String> runIds = new ArrayList<>(ACTIVE);
            if (!runIds.isEmpty()) {
                dispatchKill(context, runIds);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "cancelAll failed: " + throwable.getMessage());
        }
    }

    /** 按 runId 杀掉一条 Termux 命令；异步执行、绝不抛异常。 */
    public static void cancel(Context context, String runId) {
        if (runId == null || runId.isEmpty()) {
            return;
        }
        try {
            List<String> runIds = new ArrayList<>(1);
            runIds.add(runId);
            dispatchKill(context, runIds);
        } catch (Throwable throwable) {
            Log.w(TAG, "cancel failed: " + throwable.getMessage());
        }
    }

    /**
     * AgentRunner 结束（正常/异常/取消）时清空跟踪表。
     * TermuxBridge.run() 自己也会移除，这里兜住「工具线程被丢弃」等极端情况。
     */
    static void clearActive() {
        ACTIVE.clear();
    }

    /** 发一条只做 kill 的 RUN_COMMAND；不需要回传结果，所以不带 PendingIntent。 */
    private static void dispatchKill(Context context, List<String> runIds) {
        if (context == null || runIds == null || runIds.isEmpty()) {
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
            intent.setAction(ACTION_RUN_COMMAND);
            intent.putExtra(EXTRA_COMMAND_PATH, BASH);
            intent.putExtra(EXTRA_ARGUMENTS, new String[]{"-lc", killScript(runIds)});
            intent.putExtra(EXTRA_WORKDIR, HOME);
            intent.putExtra(EXTRA_BACKGROUND, true);
            intent.putExtra(EXTRA_COMMAND_LABEL, "DLA Termux cancel");
            context.startService(intent);
        } catch (Throwable throwable) {
            // Termux 没装 / 权限被撤 / 后台启动被系统拦：停止按钮走到这里也不能崩
            Log.w(TAG, "dispatch kill failed: " + throwable.getMessage());
        }
    }

    /**
     * 包装脚本。
     *
     * 关键点：
     * 1. 原命令放在 $1 里传入，只被展开一次，Java 侧不需要做任何 shell 转义；
     * 2. 用 bash 内建 job control（set -m）给命令开独立进程组，不依赖 setsid；
     * 3. 进程组创建成功才写 pid 文件（kill -0 -pgid 校验），避免误杀 Termux 自己的进程组；
     * 4. 命令 cmdline 里带 DLA_RUN_ID=&lt;runId&gt; 前缀，作为 pkill 兜底标记；
     * 5. set -m 期间的作业通知打到 /dev/null，命令自己的 stderr 走 fd 9 正常回传。
     */
    /**
     * 包装脚本：只做三件事——记标记、记子进程 pid、等子进程结束并回传退出码。
     *
     * 这里刻意不用 `set -m`/fd 重定向：Termux 的 RUN_COMMAND 通过 Runtime.exec 起 bash，
     * 没有控制终端，job control 在部分 Termux 版本上会让脚本卡在后台作业状态，
     * 表现为「Termux 没有返回结果」。改成最简单的后台 + wait，兼容性最好。
     */
    private static String wrapperScript(String runId) {
        return "DLA_RUN_ID='" + runId + "'\n"
                + "export DLA_RUN_ID\n"
                + "DLA_DIR=\"$HOME/" + RUN_DIR + "\"\n"
                + "mkdir -p \"$DLA_DIR\" 2>/dev/null || true\n"
                + "DLA_PID_FILE=\"$DLA_DIR/" + runId + ".pid\"\n"
                + "DLA_BASH='" + BASH + "'\n"
                + "\"$DLA_BASH\" -lc \": " + MARKER_PREFIX + runId + "; $1\" </dev/null &\n"
                + "DLA_CHILD=$!\n"
                + "echo \"$DLA_CHILD\" > \"$DLA_PID_FILE\" 2>/dev/null || true\n"
                + "wait \"$DLA_CHILD\"\n"
                + "DLA_STATUS=$?\n"
                + "rm -f \"$DLA_PID_FILE\" 2>/dev/null || true\n"
                + "exit \"$DLA_STATUS\"\n";
    }
    /**
     * 取消脚本：对每个 runId 先 TERM（进程组 + pkill -f 标记），sleep 2 秒后再 KILL。
     *
     * marker 必须通过 "DLA""_RUN_ID=..." 拼出来，不能写成连续字符串：否则这条
     * 取消命令自己的 cmdline 也会包含 marker，pkill -f 会在 TERM 阶段把执行取消
     * 的 shell 一起杀掉，后面的 KILL 就没机会跑了。
     */
    /**
     * 取消脚本：pid 文件里存的是命令所在的 bash 进程号（不再依赖进程组），
     * 先杀它和它的直接子进程，再用 marker 兜底扫一遍；2 秒后升级到 KILL。
     */
    private static String killScript(List<String> runIds) {
        StringBuilder script = new StringBuilder();
        script.append("DLA_DIR=\"$HOME/").append(RUN_DIR).append("\"\n");
        script.append("dla_stop() {\n");
        script.append("  DLA_ID=\"$1\"\n");
        script.append("  DLA_SIG=\"$2\"\n");
        script.append("  DLA_MARK=\"DLA\"\"_RUN_ID=$DLA_ID\"\n");
        script.append("  DLA_FILE=\"$DLA_DIR/$DLA_ID.pid\"\n");
        script.append("  DLA_PID=\"\"\n");
        script.append("  if [ -f \"$DLA_FILE\" ]; then\n");
        script.append("    DLA_PID=$(cat \"$DLA_FILE\" 2>/dev/null)\n");
        script.append("    case \"$DLA_PID\" in ''|*[!0-9]*) DLA_PID= ;; esac\n");
        script.append("  fi\n");
        script.append("  if [ -n \"$DLA_PID\" ]; then\n");
        script.append("    if command -v pkill >/dev/null 2>&1; then\n");
        script.append("      pkill -\"$DLA_SIG\" -P \"$DLA_PID\" 2>/dev/null || true\n");
        script.append("    fi\n");
        script.append("    kill -\"$DLA_SIG\" \"$DLA_PID\" 2>/dev/null || true\n");
        script.append("  fi\n");
        script.append("  if command -v pkill >/dev/null 2>&1; then\n");
        script.append("    pkill -\"$DLA_SIG\" -f \"$DLA_MARK\" 2>/dev/null || true\n");
        script.append("  fi\n");
        script.append("}\n");
        for (String runId : runIds) {
            script.append("dla_stop ").append(runId).append(" TERM\n");
        }
        script.append("sleep 2\n");
        for (String runId : runIds) {
            script.append("dla_stop ").append(runId).append(" KILL\n");
            script.append("rm -f \"$DLA_DIR/").append(runId).append(".pid\" 2>/dev/null || true\n");
        }
        script.append("exit 0\n");
        return script.toString();
    }
    /**
     * 把结果再广播一次（只发给本应用），供其它进程里的 {@link #run} 收。
     *
     * 调用方在 run() 里注册了同名 action 的运行时 receiver；广播不到就只会超时，
     * 所以这里失败也只记日志，不抛。
     */
    static void broadcastResult(Context context, int id, Bundle bundle) {
        if (context == null) {
            return;
        }
        try {
            Intent broadcast = new Intent(ACTION_RESULT);
            broadcast.setPackage(context.getPackageName());
            broadcast.putExtra(EXTRA_REQUEST_ID, id);
            broadcast.putExtra(EXTRA_RESULT_BUNDLE, bundle);
            context.sendBroadcast(broadcast);
        } catch (Throwable throwable) {
            Log.w(TAG, "broadcast termux result failed: " + throwable.getMessage());
        }
    }

    static void complete(int id, Bundle bundle) {
        CompletableFuture<Result> future = PENDING.remove(id);
        if (future == null) {
            return;
        }
        if (bundle == null) {
            future.complete(new Result("", "", 1, "Termux 没有返回结果 bundle"));
            return;
        }
        String stdout = bundle.getString(EXTRA_STDOUT, "");
        String stderr = bundle.getString(EXTRA_STDERR, "");
        int exitCode = bundle.getInt(EXTRA_EXIT_CODE, 1);
        int err = bundle.getInt(EXTRA_ERR, 0);
        String errmsg = bundle.getString(EXTRA_ERRMSG, "");
        String error = errmsg;
        if (err != 0 && err != -1 && error.isEmpty()) {
            error = "Termux internal error " + err;
        }
        future.complete(new Result(stdout, stderr, exitCode, error));
    }

    public static String setupScript(Context context) {
        try (InputStream in = context.getResources().openRawResource(
                com.example.chatdemo.R.raw.termux_setup)) {
            byte[] data = new byte[8192];
            StringBuilder builder = new StringBuilder();
            int read;
            while ((read = in.read(data)) != -1) {
                builder.append(new String(data, 0, read, StandardCharsets.UTF_8));
            }
            return builder.toString();
        } catch (Exception error) {
            return "";
        }
    }

    public static void open(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }
}
