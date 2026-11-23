package com.example.chatdemo.nativeui

import android.speech.tts.Voice
import java.util.Locale

// 只做音色目录的纯逻辑：语言过滤、展示顺序、中文标签。
// 可用性探测和 TextToSpeech 调用留在 Speaker.kt，避免这里持有引擎引用。
internal object TtsVoiceCatalog {

    fun supports(locale: Locale): Boolean {
        val language = locale.language
        return language.equals("zh", ignoreCase = true) ||
            language.equals("en", ignoreCase = true)
    }

    fun ordered(voices: Collection<Voice>, defaultName: String?): List<Voice> {
        return voices
            .filter { supports(it.locale) && !it.name.isNullOrBlank() }
            .distinctBy { it.name }
            .sortedWith(
                compareByDescending<Voice> { defaultName != null && it.name == defaultName }
                    .thenBy { languageRank(it.locale) }
                    .thenByDescending { it.quality }
                    .thenBy { it.name },
            )
    }

    fun build(voices: Collection<Voice>, defaultName: String?): List<TtsVoice> {
        val labelCounts = HashMap<String, Int>()
        return ordered(voices, defaultName).map { voice ->
            val base = label(voice)
            val count = (labelCounts[base] ?: 0) + 1
            labelCounts[base] = count
            TtsVoice(
                id = voice.name,
                label = if (count == 1) base else "$base（$count）",
                locale = voice.locale.toLanguageTag(),
                isDefault = defaultName != null && voice.name == defaultName,
            )
        }
    }

    private fun languageRank(locale: Locale): Int {
        return if (locale.language.equals("zh", ignoreCase = true)) 0 else 1
    }

    private fun label(voice: Voice): String {
        val localeLabel = voice.locale
            .getDisplayName(Locale.SIMPLIFIED_CHINESE)
            .ifBlank { voice.locale.toLanguageTag() }
        val online = if (voice.isNetworkConnectionRequired) " · 在线" else ""
        return "$localeLabel · ${qualityLabel(voice.quality)}$online"
    }

    private fun qualityLabel(quality: Int): String {
        return when {
            quality >= Voice.QUALITY_VERY_HIGH -> "极高音质"
            quality >= Voice.QUALITY_HIGH -> "高音质"
            quality >= Voice.QUALITY_NORMAL -> "标准音质"
            else -> "基础音质"
        }
    }
}
