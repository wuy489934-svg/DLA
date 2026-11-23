package com.example.chatdemo.nativeui

import android.content.Context
import android.os.Build
import com.example.chatdemo.agent.ProcessRunner
import com.example.chatdemo.agent.RuntimeEnv
import java.io.File

/**
 * 运行环境自检：DLA 的三个内置运行时都随 APK 安装，不依赖 Termux。
 *
 * - 本机终端：Android 自带 /system/bin/sh（toybox），应用身份；
 * - Python：Chaquopy 的 JNI 解释器，只检查 nativeLibraryDir 里的 libpython 是否存在
 *   （这里不启动解释器，避免在 UI 进程里加载整套 Python）；
 * - Node：libdshnode.so，跑一次 -v；
 * - ripgrep：librg.so，跑一次 --version。
 */
data class RuntimeSnapshot(
    val androidRelease: String = "",
    val shellCommands: Int = 0,
    val pythonVersion: String = "",
    val nodeVersion: String = "",
    val rgVersion: String = "",
    val home: String = "",
    val checked: Boolean = false,
)

object RuntimeStatus {

    fun probe(context: Context): RuntimeSnapshot {
        RuntimeEnv.ensureLinks(context)
        val env = RuntimeEnv.environment(context)
        val home = RuntimeEnv.home(context)
        val shellCount = exec("toybox | wc -w", env, home).trim().toIntOrNull() ?: 0
        val node = exec(quote(RuntimeEnv.nodeFile(context).absolutePath) + " -v", env, home).trim()
        val rgLine = exec(quote(RuntimeEnv.rgFile(context).absolutePath) + " --version", env, home)
            .lineSequence().firstOrNull()?.trim().orEmpty()
        val rg = Regex("[0-9]+\\.[0-9]+\\.[0-9]+").find(rgLine)?.value ?: rgLine
        return RuntimeSnapshot(
            androidRelease = Build.VERSION.RELEASE,
            shellCommands = shellCount,
            pythonVersion = pythonVersion(context),
            nodeVersion = node.ifBlank { "不可用" },
            rgVersion = rg.ifBlank { "不可用" },
            home = home.absolutePath,
            checked = true,
        )
    }

    /** library 名字形如 libpython3.12.so；只读文件名，不启动解释器。 */
    private fun pythonVersion(context: Context): String {
        // nativeLibraryDir 里同时有 libpython3.so 与 libpython3.12.so，必须精确匹配带小版本的那个
        val libs = File(context.applicationInfo.nativeLibraryDir).list()?.toList().orEmpty()
        val pattern = Regex("^libpython3\\.\\d+\\.so$")
        val found = libs.firstOrNull { pattern.matches(it) } ?: return "不可用"
        return found.removePrefix("libpython").removeSuffix(".so")
    }

    private fun exec(command: String, env: Map<String, String>, dir: File): String {
        return runCatching {
            ProcessRunner.run(command, dir, env, 8000, null).stdout
        }.getOrDefault("")
    }

    private fun quote(value: String): String = ProcessRunner.quote(value)
}


