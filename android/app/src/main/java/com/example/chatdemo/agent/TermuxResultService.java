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
        TermuxBridge.complete(id, bundle);
    }
}
