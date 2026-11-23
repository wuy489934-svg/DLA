package com.example.chatdemo.nativeui

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 朗读的统一入口：按用户选中的 TTS provider 分流。
 *
 * - 系统引擎：交给 [Speaker]（本机 TTS，免配置，默认）；
 * - 云端引擎：后台合成音频，再交给 [CloudTts] 播放。
 *
 * 两条路都只回调一次「结束」，UI 靠它复位喇叭按钮。
 */
object TtsEngine {

    sealed interface Status {
        data object Idle : Status
        data object Synthesizing : Status
        data object Ready : Status
        data class Unavailable(val reason: String) : Status
    }

    private const val CLOUD_MAX_CHARS = 3000
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun provider(context: Context): TtsProvider = TtsStore.selected(context)

    /** 试听/朗读：按当前 provider 播放一段文字。 */
    fun speak(context: Context, text: String, onDone: (() -> Unit)? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onDone?.invoke()
            return
        }
        val provider = provider(context)
        if (provider.isSystem()) {
            _status.value = Status.Ready
            Speaker.speakWhenReady(context, trimmed, onDone)
            return
        }
        val token = generation.incrementAndGet()
        _status.value = Status.Synthesizing
        Thread({
            try {
                val bytes = CloudTts.synthesize(context, provider, trimmed.take(CLOUD_MAX_CHARS))
                if (generation.get() != token) {
                    return@Thread
                }
                _status.value = Status.Ready
                CloudTts.play(context, bytes, provider.format, onDone)
            } catch (error: Throwable) {
                if (generation.get() == token) {
                    val message = error.message ?: error.javaClass.simpleName
                    _status.value = Status.Unavailable(message)
                    mainHandler.post { onDone?.invoke() }
                }
            }
        }, "dla-tts").start()
    }

    /** 试听指定 provider（不切换默认），结果回主线程。 */
    fun preview(context: Context, provider: TtsProvider, text: String, onResult: (Result<Unit>) -> Unit) {
        if (provider.isSystem()) {
            Speaker.ensure(context)
            Speaker.speakWhenReady(context, text) { mainHandler.post { onResult(Result.success(Unit)) } }
            return
        }
        Thread({
            val result = runCatching {
                val bytes = CloudTts.synthesize(context, provider, text)
                CloudTts.play(context, bytes, provider.format, null)
            }
            mainHandler.post { onResult(result) }
        }, "dla-tts-preview").start()
    }

    fun stop() {
        generation.incrementAndGet()
        Speaker.stop()
        CloudTts.stop()
        _status.value = Status.Idle
    }
}
