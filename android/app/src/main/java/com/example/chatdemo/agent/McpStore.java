package com.example.chatdemo.agent;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** MCP Server 配置：存 SharedPreferences，字段与网页侧 McpServer 一致 */
public final class McpStore {

    private static final String PREFS = "chat-demo-agent";
    private static final String KEY = "mcp-servers";

    private McpStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized JSONArray load(Context context) {
        String raw = prefs(context).getString(KEY, "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception error) {
            return new JSONArray();
        }
    }

    private static synchronized void persist(Context context, JSONArray servers) {
        prefs(context).edit().putString(KEY, servers.toString()).apply();
    }

    public static synchronized void upsert(Context context, String json) {
        try {
            JSONObject server = new JSONObject(json);
            String id = server.optString("id");
            if (id.isEmpty()) {
                return;
            }
            JSONArray servers = load(context);
            JSONArray next = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < servers.length(); i++) {
                JSONObject item = servers.optJSONObject(i);
                if (item != null && id.equals(item.optString("id"))) {
                    next.put(server);
                    replaced = true;
                } else if (item != null) {
                    next.put(item);
                }
            }
            if (!replaced) {
                next.put(server);
            }
            persist(context, next);
        } catch (Exception ignored) {
            // 非法配置直接丢弃
        }
    }

    public static synchronized void remove(Context context, String id) {
        JSONArray servers = load(context);
        JSONArray next = new JSONArray();
        for (int i = 0; i < servers.length(); i++) {
            JSONObject item = servers.optJSONObject(i);
            if (item != null && !id.equals(item.optString("id"))) {
                next.put(item);
            }
        }
        persist(context, next);
    }

    public static List<JSONObject> enabled(Context context) {
        List<JSONObject> result = new ArrayList<>();
        JSONArray servers = load(context);
        for (int i = 0; i < servers.length(); i++) {
            JSONObject item = servers.optJSONObject(i);
            if (item != null && item.optBoolean("enabled", true)) {
                result.add(item);
            }
        }
        return result;
    }

    public static JSONObject find(Context context, String id) {
        JSONArray servers = load(context);
        for (int i = 0; i < servers.length(); i++) {
            JSONObject item = servers.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) {
                return item;
            }
        }
        return null;
    }
}
