package com.example.chatdemo.nativeui

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 朗读的统一入口：系统 TTS 或 Qwen3-TTS。
 *
 * 默认是系统 TTS（免配置）；选 Qwen3-TTS 时需要用户在设置里填百炼 API Key。
 * 两条路都只回调一次「结束」，UI 靠它复位喇叭按钮。
 */
object TtsEngine {

    sealed interface Status {
        data object Idle : Status
        data object Synthesizing : Status
        data object Ready : Status
        data class Unavailable(val reason: String) : Status
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun engine(context: Context): TtsEngineKind = TtsStore.engine(context)

    /** 按当前选中的引擎朗读一段文字。 */
    fun speak(context: Context, text: String, onDone: (() -> Unit)? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onDone?.invoke()
            return
        }
        when (TtsStore.engine(context)) {
            TtsEngineKind.SYSTEM -> {
                _status.value = Status.Ready
                Speaker.speakWhenReady(context, trimmed, onDone)
            }
            TtsEngineKind.QWEN -> speakQwen(context, TtsStore.qwen(context), trimmed, onDone)
        }
    }

    /** 试听指定引擎/配置（不落盘），结果回主线程。 */
    fun preview(
        context: Context,
        kind: TtsEngineKind,
        qwen: QwenTtsConfig,
        text: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        if (kind == TtsEngineKind.SYSTEM) {
            Speaker.ensure(context)
            Speaker.speakWhenReady(context, text) { mainHandler.post { onResult(Result.success(Unit)) } }
            return
        }
        Thread({
            val result = runCatching {
                val (audios, _) = QwenTts.synthesizeAll(qwen, text)
                QwenTts.playQueue(context, audios, null)
            }
            mainHandler.post { onResult(result) }
        }, "dla-qwen-preview").start()
    }

    private fun speakQwen(
        context: Context,
        config: QwenTtsConfig,
        text: String,
        onDone: (() -> Unit)?,
    ) {
        val token = generation.incrementAndGet()
        _status.value = Status.Synthesizing
        Thread({
            try {
                val (audios, _) = QwenTts.synthesizeAll(config, text)
                if (generation.get() != token) return@Thread
                _status.value = Status.Ready
                QwenTts.playQueue(context, audios, onDone)
            } catch (error: Throwable) {
                if (generation.get() == token) {
                    _status.value = Status.Unavailable(error.message ?: error.javaClass.simpleName)
                    mainHandler.post { onDone?.invoke() }
                }
            }
        }, "dla-qwen-tts").start()
    }

    fun stop() {
        generation.incrementAndGet()
        Speaker.stop()
        QwenTts.stop()
        _status.value = Status.Idle
    }
}
