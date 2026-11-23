package com.example.chatdemo.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 设置页「朗读」区块：开关、引擎选择、自定义 TTS 提供商。
 *
 * 默认是系统语音（免配置）；用户也可以添加 OpenAI 兼容的云端 TTS
 * （Base URL + Key + 模型 + 音色），随时切换、编辑、删除、试听。
 */
@Composable
fun TtsSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ttsOn by remember { mutableStateOf(ChatEngine.ttsEnabled) }
    var providers by remember { mutableStateOf(TtsStore.providers(context)) }
    var selected by remember { mutableStateOf(TtsStore.selected(context)) }
    var showPicker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TtsProvider?>(null) }
    var previewing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf(false) }
    val systemState by Speaker.state.collectAsState()
    val engineStatus by TtsEngine.status.collectAsState()

    fun reload() {
        providers = TtsStore.providers(context)
        selected = TtsStore.selected(context)
    }

    fun note(text: String, error: Boolean = false) {
        message = text
        messageError = error
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingSwitch(Lang.t("朗读回答（TTS）"), ttsOn) {
            ttsOn = it
            ChatEngine.ttsEnabled = it
            if (it) {
                if (selected.isSystem()) Speaker.ensure(context)
            } else {
                ChatEngine.stopSpeak()
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    Lang.t("朗读引擎：%s", selected.name.ifBlank { Lang.t("未命名") }),
                    color = DlaColors.Text,
                    fontSize = 12.sp,
                )
                Text(selected.summary(), color = DlaColors.TextDim, fontSize = 10.sp)
            }
            TextButton(onClick = { showPicker = true }) {
                Text(Lang.t("选择引擎"), color = DlaColors.Mint, fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { editing = TtsProvider.newCloud() }) {
                Text(Lang.t("添加语音提供商"), color = DlaColors.Warm, fontSize = 12.sp)
            }
            TextButton(
                enabled = previewing == null,
                onClick = {
                    previewing = selected.id
                    TtsEngine.preview(
                        context,
                        selected,
                        Lang.t("你好，我是你的伙伴，今天想聊点什么？"),
                    ) { result ->
                        previewing = null
                        result.onSuccess { note(Lang.t("试听已发送（若没有声音，检查音量或引擎设置）")) }
                            .onFailure { note(Lang.t("试听失败：%s", it.message ?: it.javaClass.simpleName), true) }
                    }
                },
            ) {
                Text(
                    if (previewing == selected.id) Lang.t("合成中…") else Lang.t("试听"),
                    color = DlaColors.Mint,
                    fontSize = 12.sp,
                )
            }
        }

        if (selected.isSystem()) {
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
                    Text(
                        Lang.t("也可以添加一个云端语音提供商，不依赖本机引擎。"),
                        color = DlaColors.TextDim,
                        fontSize = 11.sp,
                    )
                }
                is TtsState.Ready -> Text(
                    Lang.t("系统语音已就绪"),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                )
                is TtsState.Initializing -> Text(
                    Lang.t("正在初始化系统语音…"),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                )
                TtsState.Idle -> Text(
                    Lang.t("开启朗读或点「试听」时才会初始化系统语音。"),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                )
            }
        } else {
            when (val state = engineStatus) {
                is TtsEngine.Status.Unavailable -> Text(
                    Lang.t("云端语音不可用：%s", state.reason),
                    color = DlaColors.Warm,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                is TtsEngine.Status.Synthesizing -> Text(
                    Lang.t("正在合成语音…"),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                )
                is TtsEngine.Status.Ready -> Text(
                    Lang.t("云端语音可用"),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                )
                TtsEngine.Status.Idle -> Text(
                    Lang.t("点「试听」验证这个提供商（合成一次，播放一次）。"),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                )
            }
        }

        message?.let {
            Text(
                it,
                color = if (messageError) DlaColors.Error else DlaColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Text(
            Lang.t("默认使用本机系统语音；也可以添加 OpenAI 兼容的云端 TTS（如 /audio/speech）。密钥只存在本机。"),
            color = DlaColors.TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(Lang.t("选择朗读引擎")) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    providers.forEach { provider ->
                        val active = provider.id == selected.id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) DlaColors.Mint.copy(alpha = 0.10f) else DlaColors.Surface)
                                .clickable {
                                    TtsStore.select(context, provider.id)
                                    reload()
                                    showPicker = false
                                    note(Lang.t("已切换到「%s」", provider.name.ifBlank { Lang.t("未命名") }))
                                }
                                .padding(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    provider.name.ifBlank { Lang.t("未命名") },
                                    color = if (active) DlaColors.Mint else DlaColors.Text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (active) Text(Lang.t("使用中"), color = DlaColors.Mint, fontSize = 10.sp)
                            }
                            Text(provider.summary(), color = DlaColors.TextDim, fontSize = 10.sp)
                            if (!provider.isSystem()) {
                                Row {
                                    TextButton(onClick = {
                                        showPicker = false
                                        editing = provider
                                    }) { Text(Lang.t("编辑"), color = DlaColors.Warm, fontSize = 11.sp) }
                                    TextButton(onClick = {
                                        TtsStore.remove(context, provider.id)
                                        reload()
                                        selected = TtsStore.selected(context)
                                        note(Lang.t("已删除「%s」", provider.name.ifBlank { Lang.t("未命名") }))
                                    }) { Text(Lang.t("删除"), color = DlaColors.Error, fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text(Lang.t("关闭")) }
            },
        )
    }

    editing?.let { initial ->
        var draft by remember(initial.id) { mutableStateOf(initial) }
        var testing by remember(initial.id) { mutableStateOf(false) }
        var testMessage by remember(initial.id) { mutableStateOf<String?>(null) }
        var testFailed by remember(initial.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (initial.name.isBlank()) Lang.t("添加语音提供商") else Lang.t("编辑语音提供商")) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        label = { Text(Lang.t("名称（例如：我的云端语音）")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.baseUrl,
                        onValueChange = { draft = draft.copy(baseUrl = it) },
                        label = { Text(Lang.t("Base URL（例如 https://api.openai.com/v1）")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.apiKey,
                        onValueChange = { draft = draft.copy(apiKey = it) },
                        label = { Text(Lang.t("API Key（只存本机）")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.model,
                        onValueChange = { draft = draft.copy(model = it) },
                        label = { Text(Lang.t("模型（例如 tts-1 / FunAudioLLM/CosyVoice2-0.5B）")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.voice,
                        onValueChange = { draft = draft.copy(voice = it) },
                        label = { Text(Lang.t("音色（例如 alloy / 中文女）")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.format,
                            onValueChange = { draft = draft.copy(format = it) },
                            label = { Text(Lang.t("格式")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = draft.speed.toString(),
                            onValueChange = { text ->
                                text.toFloatOrNull()?.let { draft = draft.copy(speed = it.coerceIn(0.25f, 4f)) }
                            },
                            label = { Text(Lang.t("语速")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    testMessage?.let {
                        Text(
                            it,
                            color = if (testFailed) DlaColors.Error else DlaColors.TextDim,
                            fontSize = 11.sp,
                        )
                    }
                    Text(
                        Lang.t("协议：POST {Base URL}/audio/speech，Body 为 model / input / voice / response_format / speed。"),
                        color = DlaColors.TextDim,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            testMessage = null
                            TtsEngine.preview(
                                context,
                                draft,
                                Lang.t("你好，我是你的伙伴。"),
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
                    ) { Text(if (testing) Lang.t("测试中…") else Lang.t("测试"), color = DlaColors.Warm, fontSize = 12.sp) }
                    TextButton(onClick = {
                        if (draft.name.isBlank()) {
                            testFailed = true
                            testMessage = Lang.t("先填一个名称")
                            return@TextButton
                        }
                        TtsStore.upsert(context, draft)
                        TtsStore.select(context, draft.id)
                        reload()
                        selected = TtsStore.selected(context)
                        editing = null
                        note(Lang.t("已保存并切换到「%s」", draft.name))
                    }) { Text(Lang.t("保存"), color = DlaColors.Mint) }
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(Lang.t("取消")) }
            },
        )
    }
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
