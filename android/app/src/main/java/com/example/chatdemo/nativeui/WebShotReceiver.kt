package com.example.chatdemo.nativeui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 把「网页截图」请求从 :agent 进程转交给主进程的 Activity。
 *
 * 为什么要绕这一道：WebView 只有挂到一个真实窗口里才会真正渲染，
 * 在 :agent 的服务进程里 new 一个 WebView（没有窗口）draw 出来永远是黑的。
 * 主进程此时正好有 NativeShellActivity（用户就在 DLA 里），
 * 把 WebView 挂到它的窗口上渲染，再回传文件路径，既真实又不弹新界面。
 */
class WebShotReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra("requestId")
        if (requestId.isNullOrEmpty()) return
        val activity = NativeShellActivity.instance
        if (activity == null) {
            WebShotReply.send(
                context,
                requestId,
                false,
                null,
                "DLA 不在前台：网页截图需要打开 DLA 才能渲染（不会为了截图把你的界面抢到前台）",
            )
            return
        }
        activity.renderWebShot(
            requestId = requestId,
            path = intent.getStringExtra("path").orEmpty(),
            widthPx = intent.getIntExtra("widthPx", 1133),
            heightPx = intent.getIntExtra("heightPx", 2365),
            fullPage = intent.getBooleanExtra("fullPage", false),
            waitMs = intent.getIntExtra("waitMs", 900),
            maxHeightPx = intent.getIntExtra("maxHeightPx", 5500),
        )
    }
}

/** :agent 进程监听这个广播拿结果 */
object WebShotReply {
    const val ACTION = "com.example.chatdemo.action.WEB_SHOT_RESULT"
    const val ACTION_REQUEST = "com.example.chatdemo.action.WEB_SHOT"

    fun send(
        context: Context,
        requestId: String,
        ok: Boolean,
        filePath: String?,
        error: String?,
    ) {
        val intent = Intent(ACTION).setPackage(context.packageName)
        intent.putExtra("requestId", requestId)
        intent.putExtra("ok", ok)
        intent.putExtra("path", filePath.orEmpty())
        intent.putExtra("error", error.orEmpty())
        context.sendBroadcast(intent)
    }
}
