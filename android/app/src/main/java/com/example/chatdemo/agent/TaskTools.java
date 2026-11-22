package com.example.chatdemo.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 长任务记忆工具：计划与关键笔记。
 *
 * 这些内容由 AgentRunner 保存在内存里，并在每次请求时拼进 system，
 * 不受上下文裁剪影响；模型可以随时更新，避免长任务跑到后面忘记目标。
 */
public final class TaskTools {

    private TaskTools() {}

    public static boolean isTask(String name) {
        return "task_plan".equals(name) || "task_note".equals(name);
    }

    public static JSONArray specs() {
        JSONArray list = new JSONArray();
        list.put(planSpec());
        list.put(noteSpec());
        return list;
    }

    private static JSONObject planSpec() {
        JSONObject schema = new JSONObject();
        try {
            JSONObject properties = new JSONObject();
            JSONObject steps = new JSONObject();
            steps.put("type", "array");
            steps.put("description", "按顺序排列的步骤；每步一句话");
            JSONObject items = new JSONObject();
            items.put("type", "string");
            steps.put("items", items);
            properties.put("steps", steps);
            JSONObject current = new JSONObject();
            current.put("type", "number");
            current.put("description", "当前进行到第几步（从 1 开始），可选");
            properties.put("current", current);
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", new JSONArray().put("steps"));
        } catch (Exception ignored) {
            // 不会发生
        }
        JSONObject tool = new JSONObject();
        try {
            tool.put("name", "task_plan");
            tool.put("description", "维护长任务的分步计划；每完成一步就更新。计划会一直保留在 system 里，不丢。");
            tool.put("input_schema", schema);
        } catch (Exception ignored) {
            // 不会发生
        }
        return tool;
    }

    private static JSONObject noteSpec() {
        JSONObject schema = new JSONObject();
        try {
            JSONObject properties = new JSONObject();
            JSONObject text = new JSONObject();
            text.put("type", "string");
            text.put("description", "要长期记住的关键事实，例如路径、群名、已发送内容、当前状态");
            properties.put("text", text);
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", new JSONArray().put("text"));
        } catch (Exception ignored) {
            // 不会发生
        }
        JSONObject tool = new JSONObject();
        try {
            tool.put("name", "task_note");
            tool.put("description", "记录长任务必须记住的关键事实；最多 20 条，会一直保留在 system 里。");
            tool.put("input_schema", schema);
        } catch (Exception ignored) {
            // 不会发生
        }
        return tool;
    }
}
