package com.example.chatdemo.nativeui

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * 界面音效：发送 / 完成 / 失败三声，走系统提示音通道，音量跟随媒体音量。
 * 默认开启，可在设置里关闭（与网页版行为一致）。
 */
object SoundFeedback {

    private var tone: ToneGenerator? = null
    private var lastAt = 0L

    private fun generator(): ToneGenerator? {
        if (tone == null) {
            tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 35) }.getOrNull()
        }
        return tone
    }

    private fun play(type: Int) {
        if (!ChatEngine.soundEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastAt < 40) return
        lastAt = now
        runCatching { generator()?.startTone(type, 45) }
    }

    fun send() = play(ToneGenerator.TONE_PROP_ACK)

    fun done() = play(ToneGenerator.TONE_PROP_BEEP2)

    fun error() = play(ToneGenerator.TONE_PROP_NACK)
}
