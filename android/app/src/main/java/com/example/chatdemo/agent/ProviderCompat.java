package com.example.chatdemo.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 模型适配器：把「换一家模型 / 换一套协议」收敛成一张兼容表。
 *
 * 背景：DLA 内部消息一直是 Anthropic block 造型，请求层只有两种方言
 * （anthropic Messages / openai Chat Completions）。各家兼容实现的差别不在
 * 消息结构，而在这些零碎开关上：
 * - 输出上限字段叫 max_tokens 还是 max_completion_tokens；
 * - 能不能带 stream_options.include_usage；
 * - 温度参数能不能设（o 系列 / gpt-5 只接受默认值）；
 * - 开思考用哪种写法（enable_thinking / thinking 对象 / reasoning_effort）；
 * - 联网搜索、读网页这类「服务端工具」是不是只有 DeepSeek 有。
 *
 * 这里只放纯逻辑（不依赖 Android），nativeui 的设置页和 :agent 的 loop 共用同一份，
 * 避免 UI 说支持、请求里却发不出去。
 */
public final class ProviderCompat {

    private ProviderCompat() {}

    // ---- 供应商 id ----
    public static final String DEEPSEEK = "deepseek";
    public static final String OPENAI = "openai";
    public static final String ANTHROPIC = "anthropic";
    public static final String QWEN = "qwen";
    public static final String MOONSHOT = "moonshot";
    public static final String ZHIPU = "zhipu";
    public static final String OPENROUTER = "openrouter";
    public static final String SILICONFLOW = "siliconflow";
    public static final String GROQ = "groq";
    public static final String OLLAMA = "ollama";
    public static final String CUSTOM = "custom";

    // ---- 思考参数写法 ----
    /** Anthropic 的 thinking: {type: enabled/disabled} */
    public static final String THINKING_ANTHROPIC = "anthropic";
    /** OpenAI 兼容层常见的 enable_thinking: true（通义千问 / 百炼） */
    public static final String THINKING_ENABLE = "enable_thinking";
    /** thinking: {type: enabled}（智谱 GLM、月之暗面 Kimi） */
    public static final String THINKING_OBJECT = "thinking_object";
    /** reasoning_effort: medium（OpenAI o 系列 / gpt-5） */
    public static final String THINKING_EFFORT = "reasoning_effort";
    public static final String THINKING_NONE = "none";

    /**
     * LLM 区统一的「中等 / 平衡」思考预算（tokens）。
     *
     * 各家写法不同：Claude 用 thinking.budget_tokens，千问/百炼用 thinking_budget；
     * DeepSeek 端点保持原协议（不下发预算）。数值取 4096：够做多步推理，又不至于把
     * 延迟和费用拉满。
     */
    public static final int THINKING_BUDGET_MEDIUM = 4096;

    /**
     * 供应商预设：给设置页「一键填好协议 + Base URL + 默认模型」用。
     *
     * 字段：id / name / protocol / baseUrl / model / note
     * 说明只写「这家最常踩的坑」，不写营销话术。
     */
    public static JSONArray presets() {
        JSONArray out = new JSONArray();
        put(out, DEEPSEEK, "DeepSeek", "anthropic",
                "https://api.deepseek.com/anthropic/v1", "deepseek-flash",
                "官方 Anthropic 兼容端点；也可切 OpenAI 协议用 /v1");
        put(out, "deepseek-openai", "DeepSeek（OpenAI 协议）", "openai",
                "https://api.deepseek.com/v1", "deepseek-chat",
                "OpenAI 方言下没有联网搜索 / 深度思考服务端工具");
        put(out, QWEN, "通义千问 / 百炼", "openai",
                "https://token-plan.maas.qianwenaiapi.com/compatible-mode/v1", "qwen-plus",
                "token-plan 专属地址；标准百炼控制台用 https://dashscope.aliyuncs.com/compatible-mode/v1；思考开关是 enable_thinking");
        put(out, MOONSHOT, "月之暗面 Kimi", "openai",
                "https://api.moonshot.cn/v1", "kimi-k2-0905-preview",
                "长上下文；工具调用与 OpenAI 一致");
        put(out, ZHIPU, "智谱 GLM", "openai",
                "https://open.bigmodel.cn/api/paas/v4", "glm-4.6",
                "Base URL 是 /api/paas/v4，不是 /v1");
        put(out, OPENAI, "OpenAI", "openai",
                "https://api.openai.com/v1", "gpt-4o-mini",
                "o 系列 / gpt-5 用 max_completion_tokens，且不能设温度");
        put(out, ANTHROPIC, "Anthropic Claude", "anthropic",
                "https://api.anthropic.com/v1", "claude-sonnet-4-5",
                "原生 Messages 协议；需要 x-api-key + anthropic-version");
        put(out, OPENROUTER, "OpenRouter", "openai",
                "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4.5",
                "一个 Key 转接多家模型，模型名带厂商前缀");
        put(out, SILICONFLOW, "硅基流动 SiliconFlow", "openai",
                "https://api.siliconflow.cn/v1", "Qwen/Qwen3-8B",
                "国内直连；模型名区分大小写");
        put(out, GROQ, "Groq", "openai",
                "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile",
                "Base URL 里带 /openai/v1");
        put(out, OLLAMA, "Ollama（本机）", "openai",
                "http://localhost:11434/v1", "qwen3",
                "本机 11434 端口；需要开启 cleartext 才能用 http");
        put(out, CUSTOM, "自定义 / 中转站", "openai", "", "",
                "直接填服务商给的 Base URL，模型从拉取结果里挑");
        return out;
    }

    private static void put(JSONArray out, String id, String name, String protocol,
                            String baseUrl, String model, String note) {
        try {
            out.put(new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("protocol", protocol)
                    .put("baseUrl", baseUrl)
                    .put("model", model)
                    .put("note", note));
        } catch (Exception ignored) {
            // 不会发生
        }
    }

    /** 预设协议（切供应商时顺手切协议） */
    public static String presetProtocol(String presetId) {
        JSONArray presets = presets();
        for (int i = 0; i < presets.length(); i++) {
            JSONObject item = presets.optJSONObject(i);
            if (item != null && presetId.equals(item.optString("id"))) {
                return item.optString("protocol", "openai");
            }
        }
        return "";
    }

    /**
     * 自动识别供应方：先看 Base URL（最可靠），再看模型名前缀，最后按协议兜底。
     *
     * 识别结果只影响「兼容开关」和「默认身份文案」，不影响请求能不能发出去——
     * 认错了最多是少带一个可选字段，不会把请求写坏。
     */
    public static String detect(String baseUrl, String model, String protocol) {
        String url = baseUrl == null ? "" : baseUrl.toLowerCase();
        if (!url.isEmpty()) {
            if (url.contains("api.deepseek.com")) return DEEPSEEK;
            if (url.contains("api.openai.com")) return OPENAI;
            if (url.contains("api.anthropic.com")) return ANTHROPIC;
            if (url.contains("dashscope") || url.contains("aliyuncs")
                    || url.contains("qianwen") || url.contains("bailian")) {
                return QWEN;
            }
            if (url.contains("moonshot")) return MOONSHOT;
            if (url.contains("bigmodel.cn") || url.contains("zhipu")) return ZHIPU;
            if (url.contains("openrouter")) return OPENROUTER;
            if (url.contains("siliconflow")) return SILICONFLOW;
            if (url.contains("api.groq.com")) return GROQ;
            if (url.contains("localhost") || url.contains("127.0.0.1")
                    || url.contains("0.0.0.0") || url.contains(":11434")) {
                return OLLAMA;
            }
        }
        String name = model == null ? "" : model.toLowerCase();
        if (name.startsWith("deepseek")) return DEEPSEEK;
        if (name.startsWith("claude")) return ANTHROPIC;
        if (name.startsWith("gpt") || name.startsWith("o1") || name.startsWith("o3")
                || name.startsWith("o4") || name.startsWith("chatgpt")) {
            return OPENAI;
        }
        if (name.startsWith("qwen") || name.startsWith("qvq") || name.startsWith("qwq")) {
            return QWEN;
        }
        if (name.startsWith("glm") || name.startsWith("charglm")) return ZHIPU;
        if (name.startsWith("kimi") || name.startsWith("moonshot")) return MOONSHOT;
        if (name.contains("/") && !url.isEmpty() && url.contains("openrouter")) return OPENROUTER;
        return CUSTOM;
    }

    /** 展示用的中文名 */
    public static String displayName(String provider) {
        JSONArray presets = presets();
        for (int i = 0; i < presets.length(); i++) {
            JSONObject item = presets.optJSONObject(i);
            if (item != null && provider != null && provider.equals(item.optString("id"))) {
                return item.optString("name", provider);
            }
        }
        return provider == null || provider.isEmpty() ? "自定义" : provider;
    }

    /** 输出上限字段名：o1/o3/o4/gpt-5 只认 max_completion_tokens */
    public static String maxTokensField(String provider, String model) {
        String name = model == null ? "" : model.toLowerCase();
        if (name.startsWith("o1") || name.startsWith("o3") || name.startsWith("o4")
                || name.startsWith("gpt-5")) {
            return "max_completion_tokens";
        }
        return "max_tokens";
    }

    /** 只有确认支持的端点才带 stream_options.include_usage，避免个别兼容层直接 400 */
    public static boolean supportsStreamOptions(String provider) {
        // 千问 / 百炼的 OpenAI 兼容模式支持 include_usage；之前没带，导致 Qwen 的
        // token 用量与缓存字段整个拿不到（界面只能显示「缓存 —」）
        return DEEPSEEK.equals(provider) || OPENAI.equals(provider) || QWEN.equals(provider);
    }

    /** 这些模型不接受自定义温度（传了会 400） */
    public static boolean omitTemperature(String provider, String model) {
        String name = model == null ? "" : model.toLowerCase();
        if (name.startsWith("o1") || name.startsWith("o3") || name.startsWith("o4")
                || name.startsWith("gpt-5")) {
            return true;
        }
        return false;
    }

    /** DeepSeek 的服务端工具（联网搜索 / 读网页）只有它自己有 */
    public static boolean supportsServerTools(String provider) {
        return DEEPSEEK.equals(provider);
    }

    /** 开思考时的参数写法；协议不同写法不同 */
    public static String thinkingStyle(String provider, String protocol) {
        if ("anthropic".equals(protocol)) {
            // Anthropic 原生字段；只对 DeepSeek / Claude 这类确定支持的端点下发
            if (DEEPSEEK.equals(provider) || ANTHROPIC.equals(provider)) {
                return THINKING_ANTHROPIC;
            }
            return THINKING_NONE;
        }
        if (QWEN.equals(provider)) return THINKING_ENABLE;
        if (ZHIPU.equals(provider) || MOONSHOT.equals(provider)) return THINKING_OBJECT;
        if (OPENAI.equals(provider)) return THINKING_EFFORT;
        if (DEEPSEEK.equals(provider)) return THINKING_OBJECT;
        return THINKING_NONE;
    }

    /**
     * 换供应商时要换掉的身份文案。
     *
     * DeepSeek 端点保留官方口径；其它端点用中性身份，避免对着 Qwen / GPT 说
     * 「该助手为 DeepSeek」。返回 null 表示不用替换。
     */
    public static String identityOverride(String provider) {
        if (DEEPSEEK.equals(provider)) return null;
        return "你是一个乐于助人的 AI 助手。";
    }

    /** 协议默认 Base URL（设置页留空时用） */
    public static String defaultBaseUrl(String provider, String protocol) {
        JSONArray presets = presets();
        for (int i = 0; i < presets.length(); i++) {
            JSONObject item = presets.optJSONObject(i);
            if (item != null && provider != null && provider.equals(item.optString("id"))) {
                String url = item.optString("baseUrl", "");
                if (!url.isEmpty()) return url;
            }
        }
        return "anthropic".equals(protocol)
                ? "https://api.deepseek.com/anthropic/v1"
                : "https://api.deepseek.com/v1";
    }
}
