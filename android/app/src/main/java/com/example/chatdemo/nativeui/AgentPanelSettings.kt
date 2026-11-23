package com.example.chatdemo.nativeui

import android.content.Intent
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
import com.example.chatdemo.agent.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shizuku 面板：状态、打开 Shizuku、测试 shell。
 *
 * 权限请求本身要在 Shizuku 里由用户确认；这里只负责展示状态与执行探针，
 * 避免在原生侧重复维护一套 requestPermission 结果回调。
 */
@Composable
fun ShizukuCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        running = runCatching { ShizukuShell.running() }.getOrDefault(false)
        granted = runCatching { ShizukuShell.granted() }.getOrDefault(false)
    }

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
            Text(Lang.t("手机能力（Shizuku）"), color = DlaColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                Lang.t("Shizuku 提供 uid 2000 的 shell，用于手机工具；和 Termux 二选一即可运行 Agent。"),
                color = DlaColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Text(
                when {
                    !running -> Lang.t("状态：未运行（手机重启后需要重新启动 Shizuku）")
                    !granted -> Lang.t("状态：已运行，未授权")
                    else -> Lang.t("状态：已就绪")
                },
                color = if (granted) DlaColors.Mint else DlaColors.Warm,
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    runCatching {
                        context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ?.let { context.startActivity(it) }
                    }
                }) { Text(Lang.t("打开 Shizuku"), fontSize = 12.sp) }

                OutlinedButton(
                    onClick = {
                        testing = true
                        result = ""
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                runCatching {
                                    val out = ShizukuShell.exec("echo DLA_SHIZUKU_OK && id", 6000)
                                    out.trim()
                                }.getOrElse { it.message ?: Lang.t("执行失败") }
                            }
                            result = text.take(300)
                            testing = false
                            running = runCatching { ShizukuShell.running() }.getOrDefault(false)
                            granted = runCatching { ShizukuShell.granted() }.getOrDefault(false)
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
