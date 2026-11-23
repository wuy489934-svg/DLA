package com.example.chatdemo.nativeui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatdemo.agent.ChatDemoAccessibilityService
import com.example.chatdemo.agent.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机能力：无障碍（读屏/截图/点按）与 Shizuku（uid 2000 特权命令）。
 *
 * 两者互相独立：
 * - 无障碍是「屏幕类工具」的前提，不需要 Shizuku，开一次长期有效；
 * - Shizuku 只影响 pm/am/settings/dumpsys 这类特权命令，不影响读屏、点击、本机终端。
 *
 * 权限请求本身要在 Shizuku 里由用户确认；这里只负责展示状态、发起请求与执行探针。
 */
@Composable
fun PhoneCapabilityCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuGranted by remember { mutableStateOf(false) }
    var accessibilityOn by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    fun refresh() {
        shizukuRunning = runCatching { ShizukuShell.running() }.getOrDefault(false)
        shizukuGranted = runCatching { ShizukuShell.granted() }.getOrDefault(false)
        accessibilityOn = AccessibilityState.enabled(context)
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DlaColors.Surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(Lang.t("手机能力"), color = DlaColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                Lang.t("读屏、截图、点按走系统无障碍服务，不需要 Shizuku；Shizuku 只用于 pm/am/settings 这类特权命令。"),
                color = DlaColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )

            // ---- 无障碍 ----
            Text(
                Lang.t("无障碍服务：%s", if (accessibilityOn) Lang.t("已开启") else Lang.t("未开启")),
                color = if (accessibilityOn) DlaColors.Mint else DlaColors.Warm,
                fontSize = 12.sp,
            )
            if (!accessibilityOn) {
                Text(
                    Lang.t("未开启时读屏、截图、点按文字都不可用：打开系统无障碍设置，在「已下载的应用」里找到 DLA 并打开。"),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { AccessibilityState.openSettings(context) }) {
                    Text(Lang.t("打开无障碍设置"), fontSize = 12.sp)
                }
            }

            HorizontalDivider(color = DlaColors.Line)

            // ---- Shizuku ----
            Text(
                Lang.t("Shizuku：%s", when {
                    !shizukuRunning -> Lang.t("未运行")
                    !shizukuGranted -> Lang.t("已运行，未授权")
                    else -> Lang.t("已就绪")
                }),
                color = if (shizukuGranted) DlaColors.Mint else DlaColors.Warm,
                fontSize = 12.sp,
            )
            if (!shizukuRunning) {
                Text(
                    Lang.t("需要特权命令时：打开 Shizuku，用无线调试点「启动」（手机重启后要重新启动）。"),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    runCatching {
                        val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (launch != null) {
                            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }) {
                    Text(Lang.t("打开 Shizuku"), fontSize = 12.sp)
                }
                if (shizukuRunning && !shizukuGranted) {
                    OutlinedButton(onClick = {
                        runCatching { ShizukuShell.requestPermission() }
                    }) {
                        Text(Lang.t("请求授权"), fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = {
                        testing = true
                        result = ""
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                runCatching {
                                    ShizukuShell.exec("echo DLA_SHIZUKU_OK && id", 6000).trim()
                                }.getOrElse { it.message ?: Lang.t("执行失败") }
                            }
                            result = text.take(300)
                            testing = false
                            refresh()
                        }
                    },
                    enabled = !testing,
                ) { Text(if (testing) Lang.t("测试中…") else Lang.t("测试 shell"), fontSize = 12.sp) }
            }

            if (result.isNotEmpty()) {
                HorizontalDivider(color = DlaColors.Line)
                Text(
                    result,
                    color = if (result.contains("DLA_SHIZUKU_OK")) DlaColors.Mint else DlaColors.Error,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

/** 无障碍是否开启：UI 进程读系统设置，不依赖 :agent 里的服务实例。 */
object AccessibilityState {

    fun enabled(context: Context): Boolean {
        val value = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull() ?: return false
        val expected = context.packageName + "/" + ChatDemoAccessibilityService::class.java.name
        return value.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
