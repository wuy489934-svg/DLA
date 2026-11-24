package com.example.chatdemo.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OpenAI Chat Completions 方言适配。
 *
 * DLA 内部的消息一直按 Anthropic 的 block 造型（text / image / tool_use / tool_result），
 * 这里只在发请求前把它降级映射成 OpenAI 的形态：
 * - system 抽成 role=system；
 * - assistant 的 tool_use → tool_calls，参数是 JSON 字符串；
 * - user 的 tool_result → 独立的 role=tool 消息（必须紧跟 assistant）；
 * - image block → image_url(data URL)；
 * - Anthropic 工具 schema → {type:function, function:{name,description,parameters}}。
 *
 * 这样「加一个模型」只是换协议 + 换 baseURL，不用重写 loop。
 */
public final class OpenAiWire {

    private OpenAiWire() {}

    public static JSONArray messages(JSONArray anthropicMessages, String system) {
        JSONArray out = new JSONArray();
        try {
            if (system != null && !system.trim().isEmpty()) {
                out.put(new JSONObject().put("role", "system").put("content", system));
            }
            for (int i = 0; i < anthropicMessages.length(); i++) {
                JSONObject message = anthropicMessages.optJSONObject(i);
                if (message == null) {
                    continue;
                }
                String role = message.optString("role", "user");
                Object content = message.opt("content");
                if (content instanceof String) {
                    String text = (String) content;
                    if (!text.isEmpty()) {
                        out.put(new JSONObject().put("role", role).put("content", text));
                    }
                    continue;
                }
                if (!(content instanceof JSONArray)) {
                    continue;
                }
                JSONArray blocks = (JSONArray) content;
                if ("assistant".equals(role)) {
                    appendAssistant(out, blocks);
                } else {
                    appendUser(out, blocks);
                }
            }
        } catch (Exception ignored) {
            // org.json 只会在类型错误时抛；返回已组装的部分
        }
        return out;
    }

    private static void appendAssistant(JSONArray out, JSONArray blocks) throws Exception {
        StringBuilder text = new StringBuilder();
        JSONArray calls = new JSONArray();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) {
                continue;
            }
            String type = block.optString("type");
            if ("text".equals(type)) {
                text.append(block.optString("text"));
            } else if ("tool_use".equals(type)) {
                JSONObject input = block.optJSONObject("input");
                calls.put(new JSONObject()
                        .put("id", block.optString("id"))
                        .put("type", "function")
                        .put("function", new JSONObject()
                                .put("name", block.optString("name"))
                                .put("arguments", input == null ? "{}" : input.toString())));
            }
        }
        JSONObject message = new JSONObject().put("role", "assistant");
        message.put("content", text.length() > 0 ? text.toString() : JSONObject.NULL);
        if (calls.length() > 0) {
            message.put("tool_calls", calls);
        }
        out.put(message);
    }

    private static void appendUser(JSONArray out, JSONArray blocks) throws Exception {
        StringBuilder text = new StringBuilder();
        JSONArray parts = new JSONArray();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) {
                continue;
            }
            String type = block.optString("type");
            if ("tool_result".equals(type)) {
                out.put(new JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", block.optString("tool_use_id"))
                        .put("content", flatten(block.opt("content"))));
            } else if ("image".equals(type)) {
                JSONObject part = imagePart(block);
                if (part != null) {
                    parts.put(part);
                }
            } else if ("text".equals(type)) {
                text.append(block.optString("text"));
            }
        }
        if (parts.length() > 0) {
            if (text.length() > 0) {
                parts.put(new JSONObject().put("type", "text").put("text", text.toString()));
            }
            out.put(new JSONObject().put("role", "user").put("content", parts));
        } else if (text.length() > 0) {
            out.put(new JSONObject().put("role", "user").put("content", text.toString()));
        }
    }

    private static String flatten(Object content) {
        if (content instanceof String) {
            return (String) content;
        }
        if (!(content instanceof JSONArray)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        JSONArray parts = (JSONArray) content;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) {
                continue;
            }
            String type = part.optString("type");
            if ("text".equals(type)) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(part.optString("text"));
            } else if ("image".equals(type)) {
                text.append("\n（图片结果：当前 OpenAI 兼容协议下工具结果只能回传文本，图片没有随结果发出去。"
                        + "如果模型本身不支持视觉，它也看不到这张图；"
                        + "需要看图请让用户把图片作为附件发到对话里，或换支持视觉的模型）");
            }
        }
        return text.toString();
    }

    public static JSONObject imagePart(JSONObject block) throws Exception {
        JSONObject source = block.optJSONObject("source");
        if (source == null) {
            return null;
        }
        String media = source.optString("media_type", "image/jpeg");
        String data = source.optString("data", "");
        if (data.isEmpty()) {
            return null;
        }
        return new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject().put("url", "data:" + media + ";base64," + data));
    }

    public static JSONArray tools(JSONArray anthropicTools) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < anthropicTools.length(); i++) {
            JSONObject tool = anthropicTools.optJSONObject(i);
            if (tool == null) {
                continue;
            }
            String name = tool.optString("name");
            if (name.isEmpty()) {
                continue;
            }
            try {
                JSONObject parameters = tool.optJSONObject("input_schema");
                out.put(new JSONObject()
                        .put("type", "function")
                        .put("function", new JSONObject()
                                .put("name", name)
                                .put("description", tool.optString("description"))
                                .put("parameters", parameters == null
                                        ? new JSONObject().put("type", "object")
                                        : parameters)));
            } catch (Exception ignored) {
                // 单个工具 schema 异常就跳过，不影响其它工具
            }
        }
        return out;
    }

    public static String stopReason(String finishReason) {
        if (finishReason == null) {
            return "end_turn";
        }
        switch (finishReason) {
            case "tool_calls":
            case "function_call":
                return "tool_use";
            case "length":
                return "max_tokens";
            case "content_filter":
                return "refusal";
            default:
                return "end_turn";
        }
    }

    /** o 系列 / gpt-5 用 max_completion_tokens，其它仍用 max_tokens */
    public static String maxTokensField(String model) {
        String name = model == null ? "" : model.toLowerCase();
        if (name.startsWith("o1") || name.startsWith("o3") || name.startsWith("o4")
                || name.startsWith("gpt-5")) {
            return "max_completion_tokens";
        }
        return "max_tokens";
    }

    /** 只有确认支持的端点才带 stream_options，避免个别兼容层直接 400 */
    public static boolean supportsStreamOptions(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.toLowerCase();
        return url.contains("api.deepseek.com") || url.contains("api.openai.com");
    }
}
