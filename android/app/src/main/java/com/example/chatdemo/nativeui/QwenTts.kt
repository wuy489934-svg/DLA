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
 * Qwen3-TTS（阿里云百炼 DashScope）合成与播放。
 *
 * 接口：`POST {endpoint}`，Body `{ model, input: { text, voice, language_type } }`，
 * 返回 `output.audio.url`（WAV，24 小时有效），需要再 GET 一次下载音频。
 * 单次文本上限 600 字：长回复按句子切成多段，依次合成、依次播放。
 */
object QwenTts {

    private const val MAX_CHUNK = 480
    private const val MAX_CHUNKS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var playingFile: File? = null

    class QwenTtsException(message: String) : Exception(message)

    /** 按句子切分，单段不超过 600 字（留余量）；超过 [MAX_CHUNKS] 段的部分截断。 */
    fun split(text: String): Pair<List<String>, Boolean> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return emptyList<String>() to false
        val sentences = ArrayList<String>()
        val buffer = StringBuilder()
        normalized.forEach { ch ->
            buffer.append(ch)
            if (ch in "。！？!?；;\n") {
                sentences.add(buffer.toString())
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) sentences.add(buffer.toString())

        val chunks = ArrayList<String>()
        val current = StringBuilder()
        var truncated = false
        for (sentence in sentences) {
            var rest = sentence
            while (rest.isNotEmpty()) {
                if (chunks.size >= MAX_CHUNKS) {
                    truncated = true
                    break
                }
                val room = MAX_CHUNK - current.length
                if (rest.length <= room) {
                    current.append(rest)
                    rest = ""
                } else {
                    if (current.isNotEmpty()) {
                        chunks.add(current.toString())
                        current.clear()
                        continue
                    }
                    chunks.add(rest.take(MAX_CHUNK))
                    rest = rest.drop(MAX_CHUNK)
                }
            }
            if (truncated) break
            if (current.length >= MAX_CHUNK - 40) {
                chunks.add(current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty() && chunks.size < MAX_CHUNKS) {
            chunks.add(current.toString())
        } else if (current.isNotEmpty()) {
            truncated = true
        }
        return chunks to truncated
    }

    /** 合成一段（≤600 字），返回 WAV 字节。调用方放在后台线程。 */
    fun synthesize(config: QwenTtsConfig, text: String): ByteArray {
        if (config.apiKey.isBlank()) throw QwenTtsException("还没有填 Qwen3-TTS 的 API Key")
        val payload = JSONObject().apply {
            put("model", config.model.ifBlank { "qwen3-tts-flash" })
            put(
                "input",
                JSONObject().apply {
                    put("text", text)
                    put("voice", config.voice.ifBlank { "Cherry" })
                    put("language_type", "Auto")
                },
            )
        }
        val connection = (URL(config.endpoint()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + config.apiKey.trim())
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = runCatching {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            if (code !in 200..299) {
                throw QwenTtsException("HTTP $code" + if (body.isBlank()) "" else "：" + body.take(200))
            }
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw QwenTtsException("返回不是合法 JSON")
            }
            val url = json.optJSONObject("output")
                ?.optJSONObject("audio")
                ?.optString("url")
                .orEmpty()
            if (url.isBlank()) {
                val message = json.optString("message").ifBlank { body.take(200) }
                throw QwenTtsException("没有拿到音频地址：" + message)
            }
            return download(url)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun download(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw QwenTtsException("下载音频失败：HTTP $code")
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size < 512) throw QwenTtsException("音频为空（" + bytes.size + " B）")
            return bytes
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** 长文本 → 多段音频（阻塞，后台线程调用）。 */
    fun synthesizeAll(config: QwenTtsConfig, text: String): Pair<List<ByteArray>, Boolean> {
        val (chunks, truncated) = split(text)
        val audios = ArrayList<ByteArray>(chunks.size)
        chunks.forEach { chunk ->
            audios.add(synthesize(config, chunk))
        }
        return audios to truncated
    }

    /** 主线程按顺序播放多段音频；全部放完或出错时回调一次。 */
    fun playQueue(context: Context, audios: List<ByteArray>, onDone: (() -> Unit)?) {
        if (audios.isEmpty()) {
            onDone?.invoke()
            return
        }
        mainHandler.post {
            stopLocked()
            playAt(context, audios, 0, onDone)
        }
    }

    private fun playAt(context: Context, audios: List<ByteArray>, index: Int, onDone: (() -> Unit)?) {
        if (index >= audios.size) {
            onDone?.invoke()
            return
        }
        val dir = File(context.cacheDir, "tts")
        if (!dir.isDirectory) dir.mkdirs()
        val file = File(dir, "qwen-" + System.currentTimeMillis() + "-" + index + ".wav")
        runCatching { file.writeBytes(audios[index]) }.onFailure {
            onDone?.invoke()
            return
        }
        playingFile = file
        val mp = MediaPlayer()
        player = mp
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            runCatching { mp.release() }
            if (player === mp) player = null
            runCatching { file.delete() }
            if (playingFile === file) playingFile = null
            if (index + 1 < audios.size) {
                playAt(context, audios, index + 1, onDone)
            } else {
                onDone?.invoke()
            }
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
        }.onFailure { finish() }
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
