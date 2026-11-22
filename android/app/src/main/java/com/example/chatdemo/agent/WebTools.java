package com.example.chatdemo.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * DeepSeek 官方联网能力：
 * - POST {apiOrigin}/v1/tools/search
 * - POST {apiOrigin}/v1/tools/fetch
 *
 * 直接用 API Key 调用，结果作为工具结果回给模型，不依赖 Messages 的 server tool。
 */
public final class WebTools {

    private static final int MAX_TEXT = 24000;

    private WebTools() {}

    public static boolean isWeb(String name) {
        return "web_search".equals(name) || "web_fetch".equals(name);
    }

    public static JSONArray specs() {
        JSONArray list = new JSONArray();
        list.put(tool(
                "web_search",
                "联网搜索。返回标题、链接、摘要、站点与时间；需要正文时可把 need_content 设为 true。",
                schema(props(
                        "query", p("string", "搜索词，支持 site: 等语法，1-128 字符"),
                        "count", p("number", "返回条数 1-50，默认 8"),
                        "need_content", p("boolean", "是否同时返回网页正文，默认 false")
                ), "query")
        ));
        list.put(tool(
                "web_fetch",
                "抓取单个网页，返回标题和 Markdown 正文。用于阅读用户给出的链接。",
                schema(props("url", p("string", "HTTP/HTTPS 网页地址")), "url")
        ));
        return list;
    }

    public static LocalTools.Result execute(String apiKey, String apiOrigin, String name, JSONObject args) {
        try {
            if (apiKey == null || apiKey.isEmpty()) {
                return fail("缺少 API Key");
            }
            String origin = apiOrigin == null || apiOrigin.isEmpty()
                    ? "https://api.deepseek.com" : apiOrigin;
            while (origin.endsWith("/")) {
                origin = origin.substring(0, origin.length() - 1);
            }
            if ("web_search".equals(name)) {
                JSONObject body = new JSONObject();
                body.put("query", args.optString("query"));
                body.put("count", Math.min(Math.max(args.optInt("count", 8), 1), 50));
                body.put("need_content", args.optBoolean("need_content", false));
                JSONObject response = post(origin + "/v1/tools/search", apiKey, body);
                return ok(formatSearch(response));
            }
            if ("web_fetch".equals(name)) {
                JSONObject body = new JSONObject();
                body.put("url", args.optString("url"));
                JSONObject response = post(origin + "/v1/tools/fetch", apiKey, body);
                String title = response.optString("title");
                String content = response.optString("content");
                return ok((title.isEmpty() ? "" : "# " + title + "\n\n") + content);
            }
            return fail("未知联网工具：" + name);
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return fail(message);
        }
    }

    private static String formatSearch(JSONObject response) {
        JSONArray results = response.optJSONArray("results");
        if (results == null || results.length() == 0) {
            return "（没有搜索结果）";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) {
                continue;
            }
            builder.append(i + 1).append(". ").append(item.optString("title")).append('\n');
            builder.append("   ").append(item.optString("url")).append('\n');
            String summary = item.optString("summary");
            if (!summary.isEmpty()) {
                builder.append("   ").append(summary).append('\n');
            }
            String content = item.optString("content");
            if (!content.isEmpty()) {
                builder.append("   正文：").append(abbreviate(content, 1200)).append('\n');
            }
            String site = item.optString("site_name");
            String date = item.optString("date");
            if (!site.isEmpty() || !date.isEmpty()) {
                builder.append("   ").append(site);
                if (!date.isEmpty()) {
                    builder.append(" · ").append(date);
                }
                builder.append('\n');
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private static JSONObject post(String url, String apiKey, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("User-Agent", "ChatDemoAgent/0.3.0");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = readAll(stream);
        connection.disconnect();
        if (status >= 400) {
            throw new IOException("HTTP " + status + (text.isEmpty() ? "" : "：" + abbreviate(text, 500)));
        }
        return new JSONObject(text);
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String abbreviate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    private static JSONObject tool(String name, String description, JSONObject schema) {
        JSONObject tool = new JSONObject();
        try {
            tool.put("name", name);
            tool.put("description", description);
            tool.put("input_schema", schema);
        } catch (Exception ignored) {
            // 不会发生
        }
        return tool;
    }

    private static JSONObject schema(JSONObject properties, String... required) {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            schema.put("properties", properties);
            JSONArray array = new JSONArray();
            for (String item : required) {
                array.put(item);
            }
            if (array.length() > 0) {
                schema.put("required", array);
            }
        } catch (Exception ignored) {
            // 不会发生
        }
        return schema;
    }

    private static JSONObject props(Object... pairs) {
        JSONObject properties = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                properties.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
        } catch (Exception ignored) {
            // 不会发生
        }
        return properties;
    }

    private static JSONObject p(String type, String description) {
        JSONObject property = new JSONObject();
        try {
            property.put("type", type);
            property.put("description", description);
        } catch (Exception ignored) {
            // 不会发生
        }
        return property;
    }

    private static LocalTools.Result ok(String text) {
        String value = text == null ? "" : text;
        if (value.length() > MAX_TEXT) {
            value = value.substring(0, MAX_TEXT) + "\n…（已截断）";
        }
        return new LocalTools.Result(value, false);
    }

    private static LocalTools.Result fail(String message) {
        return new LocalTools.Result("错误：" + message, true);
    }
}
