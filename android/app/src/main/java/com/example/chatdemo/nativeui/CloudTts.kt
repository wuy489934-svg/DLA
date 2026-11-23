package com.example.chatdemo.nativeui

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端 TTS：OpenAI 兼容的 `POST {baseUrl}/audio/speech`。
 *
 * 请求体：`{ model, input, voice, response_format, speed }`，`Authorization: Bearer <key>`；
 * 响应是音频二进制（mp3/wav/opus…）。合成完写到 cache 里用 MediaPlayer 播放。
 *
 * 选择这种协议是因为它是事实标准：SiliconFlow、OpenAI、以及大量自建网关都提供
 * 兼容端点，用户只要填 Base URL / Key / 模型 / 音色就能接入。
 */
object CloudTts {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var playingFile: File? = null

    class TtsException(message: String) : Exception(message)

    /** 把 baseUrl 归一成 `/audio/speech` 的完整地址；用户直接贴完整地址也认。 */
    fun endpoint(provider: TtsProvider): String {
        val base = provider.baseUrl.trim().trimEnd('/')
        return when {
            base.isEmpty() -> throw TtsException("还没有填 Base URL")
            base.endsWith("/audio/speech") -> base
            else -> base + "/audio/speech"
        }
    }

    /** 阻塞合成，返回音频字节。调用方放在后台线程。 */
    fun synthesize(context: Context, provider: TtsProvider, text: String): ByteArray {
        val endpoint = endpoint(provider)
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            throw TtsException("Base URL 需要以 http(s):// 开头")
        }
        if (provider.model.isBlank()) throw TtsException("还没有填模型")
        if (provider.voice.isBlank()) throw TtsException("还没有填音色")
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw TtsException("没有可朗读的文字")

        val payload = JSONObject().apply {
            put("model", provider.model.trim())
            put("input", trimmed)
            put("voice", provider.voice.trim())
            put("response_format", provider.format.trim().ifBlank { "mp3" })
            put("speed", provider.speed.toDouble())
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/*")
            if (provider.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer " + provider.apiKey.trim())
            }
        }
        try {
            connection.outputStream.use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = runCatching {
                    (connection.errorStream ?: connection.inputStream)?.bufferedReader()?.readText().orEmpty()
                }.getOrDefault("")
                throw TtsException("HTTP $code" + if (body.isBlank()) "" else "：" + body.take(200))
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size < 512) {
                throw TtsException("返回的音频为空或过小（" + bytes.size + " B）")
            }
            return bytes
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** 在主线程播放一段音频；结束时回调（正常/出错都会回调一次）。 */
    fun play(context: Context, bytes: ByteArray, format: String, onDone: (() -> Unit)?) {
        mainHandler.post {
            stopLocked()
            val ext = format.trim().lowercase().ifBlank { "mp3" }.let {
                when (it) {
                    "mpeg" -> "mp3"
                    "wave" -> "wav"
                    else -> it.replace(Regex("[^a-z0-9]"), "").ifBlank { "mp3" }
                }
            }
            val dir = File(context.cacheDir, "tts")
            if (!dir.isDirectory) dir.mkdirs()
            val file = File(dir, "reply-" + System.currentTimeMillis() + "." + ext)
            runCatching { file.writeBytes(bytes) }.getOrElse { error ->
                onDone?.invoke()
                return@post
            }
            playingFile = file
            val mp = MediaPlayer()
            player = mp
            val finish = {
                runCatching { mp.release() }
                if (player === mp) player = null
                runCatching { file.delete() }
                if (playingFile === file) playingFile = null
                onDone?.invoke()
            }
            runCatching {
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { finish() }
                mp.setOnErrorListener { _, _, _ ->
                    finish()
                    true
                }
                mp.prepare()
                mp.start()
            }.onFailure {
                finish()
            }
        }
    }

    fun stop() {
        mainHandler.post { stopLocked() }
    }

    private fun stopLocked() {
        val mp = player
        player = null
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        playingFile?.let { runCatching { it.delete() } }
        playingFile = null
    }
}
