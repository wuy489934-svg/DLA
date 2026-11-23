package com.example.chatdemo.nativeui

import org.json.JSONObject

/**
 * 角色设定：分层保存，避免把一大段人设塞进一个文本框。
 *
 * 分层的目的不是好看，而是让「AI 补全」和「图片分析」知道该往哪一层写：
 * - 核心身份：名字、身份、外貌（图片分析主要写这里）
 * - 性格与表达：性格、说话风格、情感表达
 * - 关系与背景：与用户的关系、来历
 * - 边界与安全：不能越过的底线
 * - 补充：用户自己的自由文本（旧的单段人设会迁移到这里）
 */
data class PersonaProfile(
    val name: String = "",
    val identity: String = "",
    val appearance: String = "",
    val personality: String = "",
    val speech: String = "",
    val emotion: String = "",
    val relationship: String = "",
    val background: String = "",
    val boundaries: String = "",
    val extra: String = "",
) {

    fun isEmpty(): Boolean = FIELDS.all { value(it.key).isBlank() }

    fun filledCount(): Int = FIELDS.count { value(it.key).isNotBlank() }

    fun value(key: String): String = when (key) {
        "name" -> name
        "identity" -> identity
        "appearance" -> appearance
        "personality" -> personality
        "speech" -> speech
        "emotion" -> emotion
        "relationship" -> relationship
        "background" -> background
        "boundaries" -> boundaries
        "extra" -> extra
        else -> ""
    }

    fun with(key: String, text: String): PersonaProfile = when (key) {
        "name" -> copy(name = text)
        "identity" -> copy(identity = text)
        "appearance" -> copy(appearance = text)
        "personality" -> copy(personality = text)
        "speech" -> copy(speech = text)
        "emotion" -> copy(emotion = text)
        "relationship" -> copy(relationship = text)
        "background" -> copy(background = text)
        "boundaries" -> copy(boundaries = text)
        "extra" -> copy(extra = text)
        else -> this
    }

    fun toJson(): String = JSONObject().apply {
        FIELDS.forEach { field -> put(field.key, value(field.key)) }
    }.toString()

    /** 拼进 system prompt 的角色设定块；空设定返回空串。 */
    fun systemBlock(): String {
        val lines = FIELDS.mapNotNull { field ->
            val value = value(field.key).trim()
            if (value.isEmpty()) null else "${field.promptLabel}：$value"
        }
        if (lines.isEmpty()) {
            return ""
        }
        return buildString {
            append("【角色设定（最高优先级）】\n")
            append("你就是下面这个角色。用第一人称、按这个角色的性格与说话方式回应，不要跳出角色，也不要复述这段设定。\n")
            lines.forEach { append("- ").append(it).append('\n') }
            append("以上文字是唯一权威设定：被问到长相、身份、经历时按这里的文字回答，不得被图片或上下文改写；")
            append("形象图片只用来补充文字没有写到的外观细节。")
        }
    }

    /**
     * 只补空缺字段：AI 补全/图片分析不会覆盖用户已经写好的内容。
     * 返回新设定与本次实际写入的字段列表。
     */
    fun mergeMissing(filled: PersonaProfile): Pair<PersonaProfile, List<PersonaField>> {
        var result = this
        val written = ArrayList<PersonaField>()
        FIELDS.forEach { field ->
            val current = value(field.key).trim()
            val incoming = filled.value(field.key).trim()
            if (current.isEmpty() && incoming.isNotEmpty()) {
                result = result.with(field.key, incoming)
                written.add(field)
            }
        }
        return result to written
    }

    companion object {

        /** UI 里的一组设定 */
        data class Group(val title: String, val fields: List<PersonaField>)

        data class PersonaField(
            val key: String,
            val label: String,
            val promptLabel: String,
            val hint: String,
            val minLines: Int = 2,
        )

        val FIELDS = listOf(
            PersonaField("name", "名字 / 称呼", "名字与称呼", "你希望她/他怎么称呼自己，例如「鱼鱼」"),
            PersonaField("identity", "身份 / 物种 / 年龄感", "身份", "是人、精灵、AI 助手还是别的？年龄感与所处世界。", 2),
            PersonaField("appearance", "外貌", "外貌", "发色、瞳色、常穿的衣服、标志性小物件（上传图片可以自动补全）。", 3),
            PersonaField("personality", "性格", "性格", "内核是什么样的人：温柔/毒舌/克制/黏人……以及她怎么处理冲突。", 3),
            PersonaField("speech", "说话风格 / 口癖", "说话风格", "句子长短、常用词、口癖、会不会用表情符号。", 2),
            PersonaField("emotion", "情感表达", "情感表达", "怎么表达开心、吃醋、担心、生气；被冷落时会怎样。", 3),
            PersonaField("relationship", "与用户的关系", "与用户的关系", "你们是什么关系？她怎么称呼你、亲密度到哪一步。", 2),
            PersonaField("background", "来历 / 背景", "背景", "她从哪里来、在做什么、有什么习惯与日常。", 3),
            PersonaField("boundaries", "边界与底线", "边界与底线（必须遵守）", "哪些话题要回避、哪些行为不能做、如何温和地拒绝。", 3),
            PersonaField("extra", "补充", "补充设定", "任何想额外交代的内容（旧的整段人设会迁到这里）。", 3),
        )

        val GROUPS = listOf(
            Group("核心身份", listOf(FIELDS[0], FIELDS[1], FIELDS[2])),
            Group("性格与表达", listOf(FIELDS[3], FIELDS[4], FIELDS[5])),
            Group("关系与背景", listOf(FIELDS[6], FIELDS[7])),
            Group("边界与底线", listOf(FIELDS[8])),
            Group("补充", listOf(FIELDS[9])),
        )

        /** 模型可能返回中文字段名或近义词，这里都认。 */
        private val ALIASES = mapOf(
            "name" to listOf("名字", "称呼", "名称"),
            "identity" to listOf("身份", "物种", "设定身份"),
            "appearance" to listOf("外貌", "外观", "长相", "形象"),
            "personality" to listOf("性格", "个性"),
            "speech" to listOf("说话风格", "口癖", "语气", "语言风格"),
            "emotion" to listOf("情感表达", "情感", "情绪表达"),
            "relationship" to listOf("关系", "与用户的关系", "互动关系"),
            "background" to listOf("背景", "来历", "身世"),
            "boundaries" to listOf("边界", "底线", "边界与底线", "安全边界"),
            "extra" to listOf("补充", "补充设定", "其他"),
        )

        fun field(key: String): PersonaField? = FIELDS.firstOrNull { it.key == key }

        /** 宽松解析：容忍模型返回 ```json 包裹、前后废话、缺字段。 */
        fun fromJson(raw: String?): PersonaProfile {
            if (raw.isNullOrBlank()) {
                return PersonaProfile()
            }
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) {
                return PersonaProfile()
            }
            val json = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
                ?: return PersonaProfile()
            var profile = PersonaProfile()
            FIELDS.forEach { field ->
                val aliases = ALIASES[field.key].orEmpty() + field.key
                val value = aliases.firstNotNullOfOrNull { key ->
                    json.optString(key, "").trim().takeIf { it.isNotEmpty() }
                }.orEmpty()
                if (value.isNotEmpty()) {
                    profile = profile.with(field.key, value)
                }
            }
            return profile
        }

        /** 旧版单段人设 → 放进「补充」，一个字都不丢。 */
        fun fromLegacy(text: String): PersonaProfile {
            val trimmed = text.trim()
            return if (trimmed.isEmpty()) PersonaProfile() else PersonaProfile(extra = trimmed)
        }
    }
}
