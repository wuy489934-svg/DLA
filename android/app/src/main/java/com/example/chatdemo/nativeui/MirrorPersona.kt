package com.example.chatdemo.nativeui

import android.content.Context
import android.util.Base64

/**
 * 镜子模式的内置人设与立绘（可选资产）。
 *
 * 开源版**不内置**任何人设文本与立绘：`assets/mirror_persona.txt` 与
 * `assets/mirror_portrait.webp` 由使用者自行放入，缺失时这里一律返回空，
 * 镜子模式开关仍然可用，只是设置页会显示「未内置人设」。
 *
 * 放在 `assets/` 而不是源码里，是为了避免把长文本和 base64 塞进 Kotlin 文件。
 */
object MirrorPersona {

    /** 人设文本；文件不存在时为空串 */
    private const val PERSONA_ASSET = "mirror_persona.txt"

    /** 立绘图；文件不存在时为 null */
    private const val PORTRAIT_ASSET = "mirror_portrait.webp"

    private var personaCache: String? = null
    private var imageCache: String? = null

    fun persona(context: Context): String {
        personaCache?.let { return it }
        val text = runCatching {
            context.assets.open(PERSONA_ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        personaCache = text
        return text
    }

    /** 是否内置了人设（开源版无资产时为 false，设置页据此显示「未内置人设」） */
    fun hasBuiltIn(context: Context): Boolean = persona(context).isNotBlank()

    /** data URL，可直接作为 Anthropic image source 的 base64 数据 */
    fun imageDataUrl(context: Context): String? {
        imageCache?.let { return it }
        val url = runCatching {
            val bytes = context.assets.open(PORTRAIT_ASSET).use { it.readBytes() }
            "data:image/webp;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
        imageCache = url
        return url
    }
}
