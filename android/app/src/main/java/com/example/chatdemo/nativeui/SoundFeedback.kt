package com.example.chatdemo.nativeui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream

/**
 * 界面音效：点击 / 发送 / 完成 / 失败。
 *
 * 分两种通道，各司其职：
 * - 点击、发送：`ToneGenerator` 的短音（轻、不打扰）；
 * - 完成、失败：**自己合成一段 WAV，用 MediaPlayer 走媒体音量播放**。
 *
 * 为什么完成音要单独做：旧实现也是 ToneGenerator，音量固定在 35、时长 45ms，
 * 前台跑任务时几乎听不见（用户实测「任务完成没提示音」）。现在合成一段
 * 880Hz→1318Hz 的上行双音（约 0.35s、带包络），走 `USAGE_MEDIA`，音量跟媒体音量，
 * 明显得多；播放失败才退回 ToneGenerator。
 *
 * 线程：全部在独立 HandlerThread 上执行，`ToneGenerator` 首次构造（建 AudioTrack）
 * 和 MediaPlayer.prepare 都不会压主线程；启动时预热一次，避免第一次点击卡顿。
 */
object SoundFeedback {

    private val thread = HandlerThread("dla-sound").apply { start() }
    private val handler = Handler(thread.looper)

    private var tone: ToneGenerator? = null
    private var lastAt = 0L
    private var appContext: Context? = null
    private var doneFile: File? = null
    private var errorFile: File? = null

    /** 正在播放的提示音：必须持强引用，否则 MediaPlayer 可能被 GC 提前回收 */
    private val active = java.util.Collections.synchronizedSet(mutableSetOf<MediaPlayer>())

    /** 完成：上行双音 */
    private val DONE_NOTES = listOf(880 to 110, 0 to 34, 1318 to 200)
    /** 失败：下行双音 */
    private val ERROR_NOTES = listOf(523 to 110, 0 to 34, 330 to 220)

    fun attach(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        warmUp()
    }

    private fun ensureTone(): ToneGenerator? {
        var current = tone
        if (current == null) {
            current = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 45) }.getOrNull()
            tone = current
        }
        return current
    }

    /** 启动时调一次：把 AudioTrack 与提示音文件都提前建好 */
    fun warmUp() {
        handler.post {
            runCatching { ensureTone() }
            runCatching {
                val context = appContext ?: return@runCatching
                if (doneFile == null) doneFile = chimeFile(context, "dla-done.wav", DONE_NOTES)
                if (errorFile == null) errorFile = chimeFile(context, "dla-error.wav", ERROR_NOTES)
            }
        }
    }

    fun syncEnabled() = Unit

    private fun enabled(): Boolean =
        runCatching { ChatEngine.soundEnabled }.getOrDefault(true)

    private fun playTone(type: Int, durationMs: Int, minGapMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastAt < minGapMs) return
        lastAt = now
        handler.post {
            if (!enabled()) return@post
            runCatching { ensureTone()?.startTone(type, durationMs) }
        }
    }

    /** 点击：破碎小方块的那一下（短促、轻） */
    fun tap() = playTone(ToneGenerator.TONE_PROP_BEEP, 16, 60)

    /** 发送：确认音 */
    fun send() = playTone(ToneGenerator.TONE_PROP_ACK, 40, 0)

    /** 完成：明显的上行双音（前台也一定听得到） */
    fun done() {
        handler.post {
            if (!enabled()) return@post
            val played = playChime(doneFile)
            if (!played) {
                runCatching { ensureTone()?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120) }
            }
        }
    }

    /** 失败：下行双音 */
    fun error() {
        handler.post {
            if (!enabled()) return@post
            val played = playChime(errorFile)
            if (!played) {
                runCatching { ensureTone()?.startTone(ToneGenerator.TONE_PROP_NACK, 160) }
            }
        }
    }

    private fun playChime(file: File?): Boolean {
        val target = file ?: return false
        if (!target.isFile) return false
        return runCatching {
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.setDataSource(target.absolutePath)
            player.setVolume(1f, 1f)
            // 必须握一个强引用：只给局部变量的话，播到一半可能被 GC 掉
            // （日志里就是 MediaPlayer finalized without being released）
            active += player
            player.setOnCompletionListener { finished ->
                active -= finished
                runCatching { finished.release() }
            }
            player.setOnErrorListener { broken, _, _ ->
                active -= broken
                runCatching { broken.release() }
                true
            }
            player.prepare()
            player.start()
            true
        }.getOrDefault(false)
    }

    /**
     * 合成一段 16bit / 44.1kHz 单声道 WAV。
     *
     * notes: (频率, 时长ms)，频率 0 表示静音间隔。每个音都带淡入淡出包络，
     * 否则起止会有「咔」的爆音。
     */
    private fun chimeFile(context: Context, name: String, notes: List<Pair<Int, Int>>): File {
        val file = File(context.cacheDir, name)
        if (file.isFile && file.length() > 1000L) return file
        val sampleRate = 44_100
        var totalSamples = 0
        notes.forEach { (_, ms) -> totalSamples += sampleRate * ms / 1000 }
        val data = ShortArray(totalSamples)
        var cursor = 0
        for ((frequency, ms) in notes) {
            val count = sampleRate * ms / 1000
            if (frequency <= 0) {
                cursor += count
                continue
            }
            val fade = (sampleRate * 0.006f).toInt().coerceAtLeast(1) // 6ms 包络
            for (i in 0 until count) {
                val t = i.toDouble() / sampleRate
                val envelope = when {
                    i < fade -> i.toDouble() / fade
                    i > count - fade -> (count - i).toDouble() / fade
                    else -> 1.0
                }
                val value = Math.sin(2.0 * Math.PI * frequency * t) * 0.55 * envelope
                data[cursor + i] = (value * Short.MAX_VALUE).toInt().toShort()
            }
            cursor += count
        }
        FileOutputStream(file).use { out ->
            out.write(wavHeader(totalSamples * 2, sampleRate))
            val bytes = ByteArray(data.size * 2)
            for (i in data.indices) {
                val v = data[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
        }
        return file
    }

    private fun wavHeader(dataBytes: Int, sampleRate: Int): ByteArray {
        val header = ByteArray(44)
        fun putString(offset: Int, value: String) {
            value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        putString(0, "RIFF")
        putInt(4, 36 + dataBytes)
        putString(8, "WAVE")
        putString(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, 1) // 单声道
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)
        putShort(32, 2)
        putShort(34, 16)
        putString(36, "data")
        putInt(40, dataBytes)
        return header
    }
}
