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
 * MCP 客户端：JSON-RPC 2.0 over streamable HTTP。
 *
 * 只实现 Agent 需要的 initialize / tools/list / tools/call；SSE 旧传输留给后续版本。
 * 每个实例对应一个 Server，持有 Mcp-Session-Id。
 */
public final class McpClient {

    public static final class ToolResult {
        public final String text;
        public final boolean isError;
        /** 图片结果（截图等），会作为图片块回给模型 */
        public final String imageBase64;
        public final String imageMime;

        ToolResult(String text, boolean isError) {
            this(text, isError, null, null);
        }

        ToolResult(String text, boolean isError, String imageBase64, String imageMime) {
            this.text = text;
            this.isError = isError;
            this.imageBase64 = imageBase64;
            this.imageMime = imageMime;
        }
    }

    private final String url;
    private final String token;
    private final int timeoutMs;
    private String sessionId;
    private int nextId;

    public McpClient(String url, String token, int timeoutMs) {
        this.url = url;
        this.token = token == null ? "" : token;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 15000;
    }

    public void initialize() throws IOException, org.json.JSONException {
        JSONObject params = new JSONObject();
        params.put("protocolVersion", "2025-06-18");
        params.put("capabilities", new JSONObject());
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "DLA");
        clientInfo.put("version", "1.0");
        params.put("clientInfo", clientInfo);
        request("initialize", params);
        notify("notifications/initialized", new JSONObject());
    }

    public JSONArray listTools() throws IOException, org.json.JSONException {
        JSONObject result = request("tools/list", new JSONObject());
        JSONArray tools = result.optJSONArray("tools");
        return tools == null ? new JSONArray() : tools;
    }

    public ToolResult callTool(String name, JSONObject arguments) throws IOException, org.json.JSONException {
        JSONObject params = new JSONObject();
        params.put("name", name);
        params.put("arguments", arguments == null ? new JSONObject() : arguments);
        JSONObject result = request("tools/call", params);
        boolean isError = result.optBoolean("isError", false);
        String text = contentToText(result.optJSONArray("content"));
        return new ToolResult(text, isError);
    }

    private JSONObject request(String method, JSONObject params) throws IOException, org.json.JSONException {
        JSONObject payload = new JSONObject();
        payload.put("jsonrpc", "2.0");
        payload.put("id", ++nextId);
        payload.put("method", method);
        payload.put("params", params == null ? new JSONObject() : params);

        HttpURLConnection connection = openConnection();
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        int status = connection.getResponseCode();
        String sid = connection.getHeaderField("Mcp-Session-Id");
        if (sid != null && !sid.isEmpty()) {
            sessionId = sid;
        }
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = readAll(stream);
        connection.disconnect();
        if (status >= 400) {
            throw new IOException("HTTP " + status + (text.isEmpty() ? "" : "：" + abbreviate(text)));
        }
        JSONObject response = extractJson(text);
        JSONObject error = response.optJSONObject("error");
        if (error != null) {
            throw new IOException(error.optString("message", "JSON-RPC 调用失败"));
        }
        JSONObject result = response.optJSONObject("result");
        return result == null ? new JSONObject() : result;
    }

    /** 通知没有响应体，部分 Server 会回 202/204；失败不影响后续调用 */
    private void notify(String method, JSONObject params) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("jsonrpc", "2.0");
            payload.put("method", method);
            payload.put("params", params == null ? new JSONObject() : params);
            HttpURLConnection connection = openConnection();
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            connection.getResponseCode();
            connection.disconnect();
        } catch (Exception ignored) {
            // 初始化通知失败时仍允许继续 tools/list
        }
    }

    private HttpURLConnection openConnection() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setRequestProperty("User-Agent", "ChatDemoAgent/0.2.0");
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (sessionId != null) {
            connection.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        return connection;
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

    /** 兼容 streamable HTTP 直接返回 JSON 和 SSE 包装两种情况 */
    private static JSONObject extractJson(String text) throws org.json.JSONException, IOException {
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            return new JSONObject(trimmed);
        }
        String last = null;
        for (String line : trimmed.split("\n")) {
            String value = line.trim();
            if (value.startsWith("data:")) {
                String data = value.substring(5).trim();
                if (data.startsWith("{")) {
                    last = data;
                }
            }
        }
        if (last == null) {
            throw new IOException("MCP 响应不是有效 JSON：" + abbreviate(trimmed));
        }
        return new JSONObject(last);
    }

    private static String contentToText(JSONArray content) {
        if (content == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String type = item.optString("type");
            if ("text".equals(type)) {
                builder.append(item.optString("text"));
            } else if ("image".equals(type)) {
                builder.append("[image ")
                        .append(item.optString("mimeType", "image"))
                        .append(", ")
                        .append(item.optString("data").length())
                        .append(" base64 chars]");
            } else if ("resource".equals(type)) {
                builder.append(item.optString("resource"));
            } else {
                builder.append(item.toString());
            }
            if (i < content.length() - 1) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    private static String abbreviate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
