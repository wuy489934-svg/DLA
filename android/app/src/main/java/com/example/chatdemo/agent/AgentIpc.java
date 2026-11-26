package com.example.chatdemo.agent;

/** Agent IPC 的协议常量：主进程 UI 与 :agent 进程服务共用 */
public final class AgentIpc {

    private AgentIpc() {
    }

    /** 客户端 -> 服务 */
    public static final int MSG_START = 1;
    public static final int MSG_STOP = 2;
    public static final int MSG_APPROVE = 3;
    public static final int MSG_ATTACH = 4;
    public static final int MSG_RESUME = 5;
    public static final int MSG_PING = 6;
    public static final int MSG_RENDERER_GONE = 7;

    /** 服务 -> 客户端 */
    public static final int MSG_EVENT = 20;
    public static final int MSG_STATE = 21;
    /** MSG_ATTACH（带 cursor）的回包，payload 是 AgentRuntime.attach() 的 JSON */
    public static final int MSG_ATTACH_REPLY = 22;

    public static final String KEY_ID = "id";
    public static final String KEY_PAYLOAD = "payload";
    /**
     * 大请求的落盘路径（UTF-8 JSON）。
     *
     * Binder 单次事务只有 ~1 MB，而 Agent 区带图片时请求体（base64）动辄几 MB，
     * 直接塞进 Bundle 会抛 TransactionTooLargeException：消息发不出去，界面却以为
     * 任务已开始，于是一直「生成中」、模型既不思考也不回复（用户说的「空转」）。
     * 超限的请求改为写进应用私有目录，只把这根路径过 Binder。
     */
    public static final String KEY_PAYLOAD_FILE = "payloadFile";
    public static final String KEY_TOOL_ID = "toolId";
    public static final String KEY_ALLOWED = "allowed";
    public static final String KEY_JSON = "json";
    public static final String KEY_RUNNING = "running";
    public static final String KEY_REQUEST_ID = "requestId";
    public static final String KEY_CURSOR = "cursor";
    /** attach 请求的关联号：客户端生成，服务原样带回，用于匹配 CountDownLatch */
    public static final String KEY_TOKEN = "token";
    /** MSG_ATTACH_REPLY 里的回放 JSON 字符串 */
    public static final String KEY_REPLY = "reply";

    /** 服务在 :agent 进程，UI 进程崩溃/被回收都不会带走任务 */
    public static final String PROCESS = ":agent";
}
