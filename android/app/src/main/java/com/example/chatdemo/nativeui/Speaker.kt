package com.example.chatdemo.nativeui

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed interface TtsState {
    data object Idle : TtsState
    data object Initializing : TtsState
    data object Ready : TtsState
    data class Unavailable(val reason: String) : TtsState
}

data class TtsVoice(
    val id: String,
    val label: String,
    val locale: String,
    val isDefault: Boolean,
)

object Speaker {

    private const val TAG = "DlaTts"
    private const val PREFS = "dla-native"
    private const val KEY_VOICE = "ttsVoice"
    private const val MAX_SPEAK_CHARS = 4000

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    private var engine: TextToSpeech? = null
    private var voices: List<TtsVoice> = emptyList()
    /** 引擎还在初始化时用户就点了朗读：先记下来，就绪后立刻念 */
    private var pendingSpeak: Pair<String, (() -> Unit)?>? = null
    /** 当前这条朗读结束（正常/出错/被停止）时回调，用来复位 UI 的喇叭状态 */
    private var speakDone: (() -> Unit)? = null
    /** 每条朗读用独立 utteranceId：QUEUE_FLUSH 停掉的旧语句不会误触新语句的完成回调 */
    private var utteranceSeq = 0L
    private var currentUtterance: String? = null
    private var engineVoices: Map<String, Voice> = emptyMap()
    private var session = 0L

    // 直接读写 dla-native/ttsVoice；null 表示沿用引擎自动选择的默认音色。
    val selectedVoiceId: String?
        get() = prefs()?.getString(KEY_VOICE, null)?.takeIf { it.isNotBlank() }

    // 触发一次异步初始化。可在主线程的 ChatEngine.attach 中调用，也可重复调用做重试。
    fun ensure(context: Context) {
        val app = context.applicationContext
        appContext = app

        var token: Long? = null
        synchronized(lock) {
            val current = _state.value
            if (current is TtsState.Initializing) return
            if (current is TtsState.Ready && engine != null) return

            session += 1L
            token = session
            engine = null
            voices = emptyList()
            engineVoices = emptyMap()
            _state.value = TtsState.Initializing
        }

        val activeToken = token ?: return
        mainHandler.post { startEngine(activeToken, app) }
    }

    fun availableVoices(): List<TtsVoice> {
        return synchronized(lock) {
            if (_state.value is TtsState.Ready) voices.toList() else emptyList()
        }
    }

    fun setVoice(context: Context, id: String): Boolean {
        val app = context.applicationContext
        appContext = app

        val snapshot = synchronized(lock) {
            if (_state.value is TtsState.Ready) {
                engine?.let { tts -> tts to engineVoices }
            } else {
                null
            }
        }
        if (snapshot == null) {
            Log.w(TAG, "setVoice($id) rejected: TTS is not ready")
            return false
        }

        val tts = snapshot.first
        val byId = snapshot.second
        val target = byId[id]
        if (target == null) {
            clearSelectedVoice()
            val fallback = applyFallback(tts, byId)
            Log.w(
                TAG,
                "setVoice($id) failed: voice is not in the available zh/en list; " +
                    if (fallback) "fallback voice applied" else "voice fallback also failed",
            )
            return false
        }

        if (applyVoice(tts, target)) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VOICE, id)
                .apply()
            Log.d(TAG, "selected voice=$id")
            return true
        }

        clearSelectedVoice()
        val fallback = applyFallback(tts, byId)
        Log.w(
            TAG,
            "setVoice($id) failed: setLanguage/setVoice was rejected; " +
                if (fallback) "fallback voice applied" else "voice fallback also failed",
        )
        return false
    }

    /**
     * 点一下喇叭就开始朗读：引擎没就绪时先排队，就绪后自动念；
     * 引擎不可用时不会卡住 UI。
     */
    fun speakWhenReady(context: Context, text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) return
        ensure(context)
        val state = synchronized(lock) { _state.value }
        when (state) {
            is TtsState.Ready -> postSpeak(text, onDone)
            is TtsState.Unavailable -> {
                Log.w(TAG, "speak skipped: ${state.reason}")
                onDone?.invoke()
            }
            else -> synchronized(lock) { pendingSpeak = text to onDone }
        }
    }

    // TextToSpeech.speak 只在主线程调用；Compose 与 ChatEngine 的回调都会落到主线程。
    fun speak(text: String) {
        if (text.isBlank()) return
        postSpeak(text, null)
    }

    private fun postSpeak(text: String, onDone: (() -> Unit)?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doSpeak(text, onDone)
        } else {
            mainHandler.post { doSpeak(text, onDone) }
        }
    }

    private fun doSpeak(text: String, onDone: (() -> Unit)?) {
        val tts = synchronized(lock) {
            if (_state.value is TtsState.Ready) engine else null
        } ?: run {
            onDone?.invoke()
            return
        }
        speakDone = onDone
        utteranceSeq += 1
        val utteranceId = "dla-" + utteranceSeq
        currentUtterance = utteranceId
        try {
            val result = tts.speak(
                text.take(MAX_SPEAK_CHARS),
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId,
            )
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "speak returned $result")
                speakDone = null
                currentUtterance = null
                onDone?.invoke()
            }
        } catch (t: Exception) {
            Log.e(TAG, "speak failed", t)
            speakDone = null
            onDone?.invoke()
        }
    }

    fun stop() {
        val engine = synchronized(lock) { engine }
        try {
            engine?.stop()
        } catch (t: Exception) {
            Log.w(TAG, "stop failed", t)
        }
        synchronized(lock) {
            pendingSpeak = null
            speakDone = null
            currentUtterance = null
        }
    }

    fun shutdown() {
        var engine: TextToSpeech? = null
        synchronized(lock) {
            session += 1L
            engine = this.engine
            this.engine = null
            voices = emptyList()
            engineVoices = emptyMap()
            _state.value = TtsState.Idle
        }
        releaseEngine(engine)
        Log.d(TAG, "shutdown")
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                val callback = synchronized(lock) {
                    if (utteranceId == null || utteranceId != currentUtterance) {
                        return@post
                    }
                    currentUtterance = null
                    val current = speakDone
                    speakDone = null
                    current
                }
                callback?.invoke()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onDone(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = onDone(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = onDone(utteranceId)
    }

    private fun startEngine(token: Long, app: Context) {
        if (!isInitializing(token)) return

        val created = try {
            TextToSpeech(app) { status ->
                mainHandler.post { finishInitialization(token, status) }
            }
        } catch (t: Exception) {
            markUnavailable(token, "无法创建 TextToSpeech：${t.message ?: t.javaClass.simpleName}", null)
            return
        }

        var release: TextToSpeech? = null
        synchronized(lock) {
            if (token == session && _state.value is TtsState.Initializing) {
                engine = created
            } else {
                release = created
            }
        }
        if (release != null) releaseEngine(release)
    }

    private fun finishInitialization(token: Long, status: Int) {
        val tts = synchronized(lock) {
            if (token == session && _state.value is TtsState.Initializing) engine else null
        } ?: return

        if (status != TextToSpeech.SUCCESS) {
            markUnavailable(token, initializationFailureReason(status), tts)
            return
        }

        val prepared = try {
            prepare(tts)
        } catch (t: Exception) {
            Prepared(
                voices = emptyList(),
                engineVoices = emptyMap(),
                failure = "TTS 已初始化，但读取音色失败：${t.message ?: t.javaClass.simpleName}",
            )
        }

        val failure = prepared.failure
        if (failure != null) {
            markUnavailable(token, failure, tts)
            return
        }

        var release: TextToSpeech? = null
        var ready = false
        synchronized(lock) {
            if (token == session && _state.value is TtsState.Initializing) {
                voices = prepared.voices
                engineVoices = prepared.engineVoices
                _state.value = TtsState.Ready
                ready = true
            } else {
                release = tts
            }
        }
        if (release != null) {
            releaseEngine(release)
        } else if (ready) {
            Log.d(TAG, "ready, voices=${prepared.voices.size}")
            runCatching { tts.setOnUtteranceProgressListener(progressListener) }
            val queued = synchronized(lock) {
                val item = pendingSpeak
                pendingSpeak = null
                item
            }
            if (queued != null) {
                postSpeak(queued.first, queued.second)
            }
        }
    }

    private fun prepare(tts: TextToSpeech): Prepared {
        val raw = readEngineVoices(tts)
        val defaultName = readDefaultVoice(tts)?.name?.takeIf { it.isNotBlank() }
        val languageStatuses = HashMap<String, Int>()

        val usable = raw.filter { voice ->
            !voice.name.isNullOrBlank() &&
                TtsVoiceCatalog.supports(voice.locale) &&
                languageStatus(tts, voice.locale, languageStatuses) >= TextToSpeech.LANG_AVAILABLE
        }
        val entries = TtsVoiceCatalog.build(usable, defaultName)
        val byId = usable.associateBy { it.name }

        if (entries.isEmpty()) {
            Log.w(TAG, "engine exposes no usable zh/en voices; default=$defaultName")
        }

        val savedId = selectedVoiceId
        if (!savedId.isNullOrBlank()) {
            val saved = byId[savedId]
            if (saved != null && applyVoice(tts, saved)) {
                Log.d(TAG, "restored saved voice=$savedId")
                return Prepared(entries, byId)
            }
            Log.w(TAG, "saved voice=$savedId is unavailable; clearing it and using the default")
            clearSelectedVoice()
        }

        if (applyAutomatic(tts, byId, defaultName)) {
            return Prepared(entries, byId)
        }

        return Prepared(entries, byId, languageFailureReason(tts))
    }

    private fun applyAutomatic(
        tts: TextToSpeech,
        byId: Map<String, Voice>,
        defaultName: String?,
    ): Boolean {
        // 中文优先；中文数据缺失时退到英文，避免整个朗读功能不可用。
        if (applyLanguage(tts, Locale.SIMPLIFIED_CHINESE)) return true
        if (applyLanguage(tts, Locale.US)) return true

        for (voice in TtsVoiceCatalog.ordered(byId.values, defaultName)) {
            if (applyVoice(tts, voice)) return true
        }
        return false
    }

    private fun applyFallback(tts: TextToSpeech, byId: Map<String, Voice>): Boolean {
        val default = readDefaultVoice(tts)
        if (default != null && TtsVoiceCatalog.supports(default.locale) && applyVoice(tts, default)) {
            Log.w(TAG, "using system default voice=${default.name}")
            return true
        }
        if (applyAutomatic(tts, byId, default?.name)) {
            Log.w(TAG, "using preferred zh/en language after default voice failed")
            return true
        }
        return false
    }

    private fun applyVoice(tts: TextToSpeech, voice: Voice): Boolean {
        val locale = voice.locale
        val languageStatus = try {
            tts.setLanguage(locale)
        } catch (t: Exception) {
            Log.w(TAG, "setLanguage(${locale.toLanguageTag()}) failed", t)
            return false
        }
        if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
            Log.w(
                TAG,
                "setLanguage(${locale.toLanguageTag()}) -> ${languageStatusLabel(languageStatus)}",
            )
            return false
        }

        val result = try {
            tts.setVoice(voice)
        } catch (t: Exception) {
            Log.w(TAG, "setVoice(${voice.name}) failed", t)
            return false
        }
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "setVoice(${voice.name}) returned $result")
            return false
        }
        return true
    }

    private fun applyLanguage(tts: TextToSpeech, locale: Locale): Boolean {
        val status = try {
            tts.setLanguage(locale)
        } catch (t: Exception) {
            Log.w(TAG, "setLanguage(${locale.toLanguageTag()}) failed", t)
            return false
        }
        if (status < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "setLanguage(${locale.toLanguageTag()}) -> ${languageStatusLabel(status)}")
            return false
        }
        return true
    }

    private fun readEngineVoices(tts: TextToSpeech): List<Voice> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()
        return try {
            tts.voices.toList()
        } catch (t: Exception) {
            Log.w(TAG, "getVoices failed", t)
            emptyList()
        }
    }

    private fun readDefaultVoice(tts: TextToSpeech): Voice? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            tts.defaultVoice
        } catch (t: Exception) {
            Log.w(TAG, "getDefaultVoice failed", t)
            null
        }
    }

    private fun languageStatus(
        tts: TextToSpeech,
        locale: Locale,
        cache: MutableMap<String, Int>,
    ): Int {
        val key = locale.toLanguageTag()
        return cache.getOrPut(key) {
            try {
                tts.isLanguageAvailable(locale)
            } catch (t: Exception) {
                Log.w(TAG, "isLanguageAvailable($key) failed", t)
                TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    private fun languageFailureReason(tts: TextToSpeech): String {
        val cache = HashMap<String, Int>()
        val zh = languageStatus(tts, Locale.SIMPLIFIED_CHINESE, cache)
        val en = languageStatus(tts, Locale.US, cache)
        return "没有可用的中文或英文音色（中文=${languageStatusLabel(zh)}，" +
            "英文=${languageStatusLabel(en)}）。请在系统设置中安装语音数据，或更换 TTS 引擎"
    }

    private fun initializationFailureReason(status: Int): String {
        return if (status == TextToSpeech.ERROR) {
            "系统 TTS 引擎缺失或未就绪（TextToSpeech.ERROR=$status）。请安装或启用系统语音引擎后重试"
        } else {
            "TTS 初始化失败（status=$status，TextToSpeech.SUCCESS=${TextToSpeech.SUCCESS}）"
        }
    }

    private fun languageStatusLabel(status: Int): String {
        return when (status) {
            TextToSpeech.LANG_MISSING_DATA -> "LANG_MISSING_DATA($status)"
            TextToSpeech.LANG_NOT_SUPPORTED -> "LANG_NOT_SUPPORTED($status)"
            TextToSpeech.LANG_AVAILABLE -> "LANG_AVAILABLE($status)"
            TextToSpeech.LANG_COUNTRY_AVAILABLE -> "LANG_COUNTRY_AVAILABLE($status)"
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "LANG_COUNTRY_VAR_AVAILABLE($status)"
            else -> "status=$status"
        }
    }

    private fun isInitializing(token: Long): Boolean {
        return synchronized(lock) {
            token == session && _state.value is TtsState.Initializing
        }
    }

    private fun markUnavailable(token: Long, reason: String, fallbackEngine: TextToSpeech?) {
        var release: TextToSpeech? = null
        synchronized(lock) {
            if (token != session) return
            session += 1L
            release = engine ?: fallbackEngine
            engine = null
            voices = emptyList()
            engineVoices = emptyMap()
            _state.value = TtsState.Unavailable(reason)
        }
        releaseEngine(release)
        Log.w(TAG, "unavailable: $reason")
    }

    private fun releaseEngine(engine: TextToSpeech?) {
        if (engine == null) return
        try {
            engine.stop()
        } catch (t: Exception) {
            Log.w(TAG, "stop during release failed", t)
        }
        try {
            engine.shutdown()
        } catch (t: Exception) {
            Log.w(TAG, "shutdown failed", t)
        }
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clearSelectedVoice() {
        prefs()?.edit()?.remove(KEY_VOICE)?.apply()
    }

    private data class Prepared(
        val voices: List<TtsVoice>,
        val engineVoices: Map<String, Voice>,
        val failure: String? = null,
    )
}
