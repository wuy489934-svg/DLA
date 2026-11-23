package com.example.chatdemo.agent;

/** 接收 Termux RUN_COMMAND 的结果并唤醒等待线程。 */
public class TermuxResultService extends android.app.IntentService {

    public TermuxResultService() {
        super("dla-termux-result");
    }

    @Override
    protected void onHandleIntent(android.content.Intent intent) {
        if (intent == null) {
            return;
        }
        int id = intent.getIntExtra("dla_termux_request_id", -1);
        if (id < 0) {
            return;
        }
        android.os.Bundle bundle = intent.getBundleExtra("result");
        // 同一进程内（AgentRunner 也跑在 :agent）走静态 future 快路径
        TermuxBridge.complete(id, bundle);
        // 其它进程（设置页、网页壳）只能靠广播拿到结果
        TermuxBridge.broadcastResult(getApplicationContext(), id, bundle);
    }
}
