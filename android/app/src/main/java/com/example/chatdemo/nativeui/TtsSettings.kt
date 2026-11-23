package com.example.chatdemo.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置页「朗读」区块：开关 + 引擎（系统 / Qwen3-TTS）+ Qwen 配置。
 *
 * 朗读默认关闭；系统 TTS 免配置，Qwen3-TTS 需要用户自己的百炼 API Key。
 */
@Composable
fun TtsSettings() {
    val context = LocalContext.current
    var ttsOn by remember { mutableStateOf(ChatEngine.ttsEnabled) }
    var engine by remember { mutableStateOf(TtsStore.engine(context)) }
    var qwen by remember { mutableStateOf(TtsStore.qwen(context)) }
    var savedHint by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testFailed by remember { mutableStateOf(false) }
    val systemState by Speaker.state.collectAsState()
    val engineStatus by TtsEngine.status.collectAsState()

    fun selectEngine(kind: TtsEngineKind) {
        engine = kind
        TtsStore.selectEngine(context, kind)
        if (kind == TtsEngineKind.SYSTEM && ttsOn) Speaker.ensure(context)
    }

    fun saveQwen() {
        TtsStore.saveQwen(context, qwen)
        savedHint = Lang.t("已保存 Qwen3-TTS 设置")
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(Lang.t("朗读回答（TTS）"), color = DlaColors.Text, fontSize = 14.sp)
                Text(
                    Lang.t("默认关闭；打开后回复结束会自动朗读"),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                )
            }
            Switch(checked = ttsOn, onCheckedChange = {
                ttsOn = it
                ChatEngine.ttsEnabled = it
                if (!it) {
                    ChatEngine.stopSpeak()
                } else if (engine == TtsEngineKind.SYSTEM) {
                    Speaker.ensure(context)
                }
            })
        }

        Text(Lang.t("朗读引擎"), color = DlaColors.TextDim, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EngineChip(Lang.t("系统语音（本机）"), engine == TtsEngineKind.SYSTEM) {
                selectEngine(TtsEngineKind.SYSTEM)
            }
            EngineChip(Lang.t("Qwen3-TTS（百炼）"), engine == TtsEngineKind.QWEN) {
                selectEngine(TtsEngineKind.QWEN)
            }
        }

        if (engine == TtsEngineKind.SYSTEM) {
            when (val state = systemState) {
                is TtsState.Unavailable -> {
                    Text(
                        Lang.t("系统语音不可用：%s", state.reason),
                        color = DlaColors.Warm,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    TextButton(onClick = { openSystemTtsSettings(context) }) {
                        Text(Lang.t("打开语音设置"), color = DlaColors.Mint, fontSize = 12.sp)
                    }
                }
                is TtsState.Ready -> Text(Lang.t("系统语音已就绪"), color = DlaColors.TextDim, fontSize = 11.sp)
                is TtsState.Initializing -> Text(Lang.t("正在初始化系统语音…"), color = DlaColors.TextDim, fontSize = 11.sp)
                TtsState.Idle -> Text(
                    Lang.t("打开朗读或点「试听」时才会初始化系统语音。"),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                )
            }
        } else {
            OutlinedTextField(
                value = qwen.apiKey,
                onValueChange = { qwen = qwen.copy(apiKey = it) },
                label = { Text(Lang.t("百炼 API Key（只存本机）")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RegionChip(Lang.t("北京"), qwen.region == "beijing") {
                    qwen = qwen.copy(region = "beijing")
                }
                RegionChip(Lang.t("新加坡"), qwen.region == "singapore") {
                    qwen = qwen.copy(region = "singapore")
                }
            }
            Text(Lang.t("音色"), color = DlaColors.TextDim, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Cherry", "Serena", "Ethan", "Chelsie").forEach { voice ->
                    RegionChip(voice, qwen.voice == voice) {
                        qwen = qwen.copy(voice = voice)
                    }
                }
            }
            OutlinedTextField(
                value = qwen.voice,
                onValueChange = { qwen = qwen.copy(voice = it) },
                label = { Text(Lang.t("音色名（可自定义）")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = qwen.model,
                onValueChange = { qwen = qwen.copy(model = it) },
                label = { Text(Lang.t("模型（qwen3-tts-flash）")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { saveQwen() }) {
                    Text(Lang.t("保存"), color = DlaColors.Mint, fontSize = 12.sp)
                }
                TextButton(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        testMessage = null
                        TtsEngine.preview(
                            context,
                            TtsEngineKind.QWEN,
                            qwen,
                            Lang.t("你好，我是你的伙伴，今天想聊点什么？"),
                        ) { result ->
                            testing = false
                            result.onSuccess {
                                testFailed = false
                                testMessage = Lang.t("合成并播放成功")
                            }.onFailure {
                                testFailed = true
                                testMessage = Lang.t("失败：%s", it.message ?: it.javaClass.simpleName)
                            }
                        }
                    },
                ) { Text(if (testing) Lang.t("合成中…") else Lang.t("试听"), color = DlaColors.Warm, fontSize = 12.sp) }
                Text(qwen.summary(), color = DlaColors.TextDim, fontSize = 10.sp)
            }
            when (val state = engineStatus) {
                is TtsEngine.Status.Unavailable -> Text(
                    Lang.t("Qwen3-TTS 不可用：%s", state.reason),
                    color = DlaColors.Warm,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                is TtsEngine.Status.Synthesizing -> Text(
                    Lang.t("正在合成语音…"),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                )
                else -> Unit
            }
            testMessage?.let {
                Text(
                    it,
                    color = if (testFailed) DlaColors.Error else DlaColors.TextDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            savedHint?.let {
                Text(it, color = DlaColors.TextDim, fontSize = 11.sp)
            }
            Text(
                Lang.t("Qwen3-TTS 走百炼接口：单次最多 600 字，长回复按句子分段合成后连续播放；Key 只存本机。"),
                color = DlaColors.TextDim,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun EngineChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) DlaColors.Mint else DlaColors.TextDim,
        fontSize = 12.sp,
        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) DlaColors.Mint.copy(alpha = 0.14f) else DlaColors.Surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun RegionChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) DlaColors.Mint else DlaColors.TextDim,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) DlaColors.Mint.copy(alpha = 0.12f) else DlaColors.Surface)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** 系统 TTS 设置页；部分 ROM 用非标准 action，失败后退回系统设置首页。 */
fun openSystemTtsSettings(context: android.content.Context) {
    val opened = runCatching {
        context.startActivity(
            android.content.Intent("com.android.settings.TTS_SETTINGS")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
