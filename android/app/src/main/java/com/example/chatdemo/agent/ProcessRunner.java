package com.example.chatdemo.agent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 统一的子进程执行器：本机 shell、Node 脚本共用。
 *
 * 设计要点：
 * - 命令包进 `setsid sh -c` 里执行，shell 成为新会话/进程组组长，并把 `$$` 写进 pid 文件；
 *   超时或用户点停止时用 `pkill -9 -g <pid>` 杀掉整个进程组（sleep、子脚本都会一起死），
 *   再补一次 `-s` 覆盖自己 setsid 出去的孙进程。
 * - stdout / stderr 各用一个线程读，避免管道写满导致子进程卡死；
 * - 输出超过上限就继续读但只保留前 N 字节，并标记「已截断」；
 * - 所有活动进程登记在 ACTIVE，用户点停止时由 [cancelAll] 统一清理。
 */
public final class ProcessRunner {

    private static final int CAP_BYTES = 32 * 1024;
    private static final Set<Running> ACTIVE = ConcurrentHashMap.newKeySet();

    private ProcessRunner() {}

    public static final class Result {
        public int exit;
        public String stdout = "";
        public String stderr = "";
        public long ms;
        public boolean timedOut;
    }

    private static final class Running {
        final Process process;
        final File pidFile;

        Running(Process process, File pidFile) {
            this.process = process;
            this.pidFile = pidFile;
        }
    }

    /**
     * @param command 交给 /system/bin/sh -c 的脚本
     * @param pidFile shell 会把自己的 pid 写到这里，用于杀整棵树；可为 null
     */
    public static Result run(String command, File dir, Map<String, String> extraEnv,
                             int timeoutMs, File pidFile) throws IOException, InterruptedException {
        String prefix = pidFile == null
                ? ""
                : "echo $$ > " + quote(pidFile.getAbsolutePath()) + "; ";
        String script = prefix + command;
        String[] argv = hasSetsid()
                ? new String[]{"/system/bin/setsid", "/system/bin/sh", "-c", script}
                : new String[]{"/system/bin/sh", "-c", script};

        ProcessBuilder builder = new ProcessBuilder(argv);
        if (dir != null) {
            builder.directory(dir);
        }
        if (extraEnv != null) {
            builder.environment().putAll(extraEnv);
        }
        builder.redirectErrorStream(false);
        long start = System.currentTimeMillis();
        Process process = builder.start();
        Running running = new Running(process, pidFile);
        ACTIVE.add(running);
        try {
            process.getOutputStream().close();
            Drain out = new Drain(process.getInputStream());
            Drain err = new Drain(process.getErrorStream());
            Thread tOut = new Thread(out, "dla-exec-out");
            Thread tErr = new Thread(err, "dla-exec-err");
            tOut.setDaemon(true);
            tErr.setDaemon(true);
            tOut.start();
            tErr.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            Result result = new Result();
            result.timedOut = !finished;
            if (!finished) {
                killTree(running);
                process.waitFor(2, TimeUnit.SECONDS);
            }
            tOut.join(2000);
            tErr.join(2000);
            result.exit = finished ? process.exitValue() : -1;
            result.stdout = out.text();
            result.stderr = err.text();
            result.ms = System.currentTimeMillis() - start;
            return result;
        } finally {
            ACTIVE.remove(running);
            killTree(running);
            if (pidFile != null) {
                try {
                    Files.deleteIfExists(pidFile.toPath());
                } catch (Throwable ignored) {
                    // 删除失败不影响结果
                }
            }
        }
    }

    /** 用户点停止 / 长任务取消：把当前所有子进程树杀掉。 */
    public static void cancelAll() {
        Running[] running = ACTIVE.toArray(new Running[0]);
        android.util.Log.d("DlaRuntime", "cancelAll active=" + running.length);
        for (Running item : running) {
            killTree(item);
        }
        ACTIVE.clear();
    }

    private static void killTree(Running running) {
        if (running == null) {
            return;
        }
        Process process = running.process;
        long pid = readPid(running.pidFile);
        android.util.Log.d("DlaRuntime", "killTree pid=" + pid + " alive=" + process.isAlive());
        if (pid > 0) {
            // 进程组 + 会话各杀一次：覆盖普通子进程与脚本自己 setsid 出去的孙进程
            String script = "pkill -9 -g " + pid + " 2>/dev/null; pkill -9 -s " + pid + " 2>/dev/null";
            try {
                Process killer = new ProcessBuilder("/system/bin/sh", "-c", script).start();
                killer.waitFor(2, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
                // pkill 不可用时退回 destroyForcibly
            }
        }
        try {
            process.destroyForcibly();
        } catch (Throwable ignored) {
            // 进程可能已经退出
        }
    }

    private static long readPid(File pidFile) {
        if (pidFile == null || !pidFile.isFile()) {
            return -1;
        }
        try {
            String text = new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.UTF_8).trim();
            return Long.parseLong(text);
        } catch (Throwable error) {
            return -1;
        }
    }

    private static boolean hasSetsid() {
        return new File("/system/bin/setsid").canExecute();
    }

    /** POSIX 单引号转义，避免路径里的空格/引号破坏脚本。 */
    public static String quote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** 读一路流，最多保留 CAP_BYTES。 */
    private static final class Drain implements Runnable {
        private final InputStream in;
        private final byte[] head = new byte[CAP_BYTES];
        private int length;
        private boolean truncated;

        Drain(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    int room = CAP_BYTES - length;
                    if (room > 0) {
                        int copy = Math.min(room, n);
                        System.arraycopy(buffer, 0, head, length, copy);
                        length += copy;
                        if (copy < n) {
                            truncated = true;
                        }
                    } else {
                        truncated = true;
                    }
                }
            } catch (Throwable ignored) {
                // 进程被杀时 read 会抛 IO 异常，属于正常路径
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }

        String text() {
            String value = new String(head, 0, length, StandardCharsets.UTF_8);
            return truncated ? value + "\n…（输出已截断，只保留前 32 KB）" : value;
        }
    }
}


