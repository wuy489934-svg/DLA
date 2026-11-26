package com.example.chatdemo.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 通用 HTTP 工具：GET / POST / PUT / PATCH / DELETE，带自定义头、JSON 或原始 body，
 * 响应可以直接存文件。
 *
 * 3.6.3 新增。以前模型要发一个带 Authorization 的 POST 或下载一个文件，只能：
 * 写一段 node/python 脚本 → phone_sh 跑 → 再 cat 结果，动辄 3~4 步；
 * 而且部分站点对 node 的 UA/TLS 指纹不友好。现在一次调用就能拿到状态码、头和正文。
 *
 * 与 web_fetch 的分工：web_fetch 是「读网页正文」（会去标签、限内网），
 * phone_http 是「调接口 / 下载」，原样返回，不做正文提取。
 */
public final class NetTools {

    private static final int MAX_TEXT = 12000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Mobile Safari/537.36";

    private NetTools() {}

    public static boolean handles(String name) {
        return "phone_http".equals(name);
    }

    public static JSONArray specs() {
        JSONArray list = new JSONArray();
        try {
            list.put(new JSONObject()
                    .put("name", "phone_http")
                    .put("description",
                            "发一个 HTTP 请求（GET/POST/PUT/PATCH/DELETE）：可带自定义头、JSON 或原始 body；"
                                    + "响应能直接存文件（save_to），适合调接口、上传/下载、带 Token 的请求。"
                                    + "只需要读网页正文时用 web_fetch。")
                    .put("input_schema", new JSONObject()
                            .put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("url", type("string", "完整 http/https 地址"))
                                    .put("method", type("string", "GET（默认）/ POST / PUT / PATCH / DELETE"))
                                    .put("headers", type("object", "请求头对象，例如 {\"Authorization\":\"Bearer …\"}"))
                                    .put("json", type("string", "JSON body（自动带 Content-Type: application/json）"))
                                    .put("body", type("string", "原始 body（非 JSON 时用）"))
                                    .put("save_to", type("string", "把响应体存到这个绝对路径（省略则返回文本）"))
                                    .put("timeout_ms", type("number", "超时毫秒，默认 20000"))
                                    .put("max_bytes", type("number", "最多读多少字节，默认 8MB")))
                            .put("required", new JSONArray().put("url"))));
        } catch (Exception ignored) {
            // 不会发生
        }
        return list;
    }

    private static JSONObject type(String name, String description) {
        try {
            return new JSONObject().put("type", name).put("description", description);
        } catch (Exception error) {
            return new JSONObject();
        }
    }

    public static LocalTools.Result execute(String name, JSONObject args) {
        if (!"phone_http".equals(name)) {
            return fail("未知网络工具：" + name);
        }
        String rawUrl = args.optString("url", "").trim();
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            return fail("url 必须是 http/https 完整地址");
        }
        int timeout = Math.min(Math.max(args.optInt("timeout_ms", 20000), 1000), 120000);
        int maxBytes = Math.min(Math.max(args.optInt("max_bytes", MAX_BYTES), 1024), MAX_BYTES);
        HttpURLConnection connection = null;
        try {
            URL url = new URL(rawUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(args.optString("method", "GET").trim().toUpperCase(Locale.US));
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            JSONObject headers = args.optJSONObject("headers");
            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    connection.setRequestProperty(key, headers.optString(key));
                }
            }
            String json = args.optString("json", "").trim();
            String body = args.optString("body", "");
            if (!json.isEmpty() || !body.isEmpty()) {
                if (!json.isEmpty() && !body.isEmpty()) {
                    return fail("json 和 body 只能二选一");
                }
                byte[] bytes;
                if (!json.isEmpty()) {
                    bytes = json.getBytes(StandardCharsets.UTF_8);
                    if (connection.getRequestProperty("Content-Type") == null) {
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    }
                } else {
                    bytes = body.getBytes(StandardCharsets.UTF_8);
                    if (connection.getRequestProperty("Content-Type") == null) {
                        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                    }
                }
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                return ok("HTTP " + status + "（没有响应体）");
            }
            String encoding = connection.getContentEncoding();
            if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
                stream = new GZIPInputStream(stream);
            }
            String saveTo = args.optString("save_to", "").trim();
            if (!saveTo.isEmpty()) {
                File target = new File(saveTo);
                File parent = target.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                long total = 0;
                try (InputStream in = stream; OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        total += read;
                        if (total > maxBytes) {
                            break;
                        }
                    }
                }
                return ok("HTTP " + status + "（" + connection.getContentType() + "）\n"
                        + "响应已保存：" + target.getAbsolutePath() + "\n大小：" + total + " B");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > maxBytes) {
                    break;
                }
            }
            stream.close();
            String text = buffer.toString(StandardCharsets.UTF_8.name());
            String contentType = connection.getContentType();
            StringBuilder result = new StringBuilder();
            result.append("HTTP ").append(status).append(" · ").append(contentType == null ? "未知类型" : contentType)
                    .append(" · ").append(text.length()).append(" 字符\n");
            result.append("\n").append(text);
            return ok(result.toString());
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return fail("请求失败：" + message);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static LocalTools.Result ok(String text) {
        return new LocalTools.Result(abbreviate(text), false);
    }

    private static LocalTools.Result fail(String text) {
        return new LocalTools.Result("错误：" + text, true);
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "\n…（已截断）";
    }
}
