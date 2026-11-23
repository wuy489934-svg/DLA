package com.example.chatdemo.nativeui

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.IOException

private const val RENDERER_BASE_URL = "https://dla.local/"
private const val RENDERER_HOST = "dla.local"
private const val RENDERER_PATH_PREFIX = "/renderer/"

/** mermaid / KaTeX 可能晚于 onPageFinished 完成，按固定延时补测 contentHeight */
private val RENDER_SETTLE_DELAYS_MS = longArrayOf(300L, 800L, 1500L)

/**
 * 隔离的 Markdown 渲染器：Compose 原生界面里唯一保留的 WebView。
 *
 * 离线与隔离保证：
 * - 页面完全由本文件生成，loadDataWithBaseURL 固定到 https://dla.local/；
 * - shouldInterceptRequest 只放行 https://dla.local/renderer/ 下的打包资源，
 *   其它请求（http、其它域名、file、content 等）一律返回空响应；
 * - blockNetworkLoads 为 true，文件访问与 content 访问关闭，不挂
 *   addJavascriptInterface，mixedContentMode 为 NEVER_ALLOW，因此页面即使执行
 *   脚本也无法联网、读取本地文件或调用原生；
 * - javaScriptEnabled 打开只是因为 KaTeX 与 mermaid 需要执行，脚本只能来自
 *   assets/renderer 下由 shouldInterceptRequest 返回的字节。
 *
 * 内置渲染资源（app/src/main/assets/renderer/，共 23 个文件 3,894,258 B）：
 * - KaTeX 0.16.11（MIT），下载自 https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/
 *   katex.min.js 275,414 B；katex.min.css 23,335 B；
 *   fonts 目录 20 个 woff2 共 259,792 B，逐个大小如下：
 *   KaTeX_AMS-Regular.woff2 28,076 B；KaTeX_Caligraphic-Bold.woff2 6,912 B；
 *   KaTeX_Caligraphic-Regular.woff2 6,908 B；KaTeX_Fraktur-Bold.woff2 11,348 B；
 *   KaTeX_Fraktur-Regular.woff2 11,316 B；KaTeX_Main-Bold.woff2 25,324 B；
 *   KaTeX_Main-BoldItalic.woff2 16,780 B；KaTeX_Main-Italic.woff2 16,988 B；
 *   KaTeX_Main-Regular.woff2 26,272 B；KaTeX_Math-BoldItalic.woff2 16,400 B；
 *   KaTeX_Math-Italic.woff2 16,440 B；KaTeX_SansSerif-Bold.woff2 12,216 B；
 *   KaTeX_SansSerif-Italic.woff2 12,028 B；KaTeX_SansSerif-Regular.woff2 10,344 B；
 *   KaTeX_Script-Regular.woff2 9,644 B；KaTeX_Size1-Regular.woff2 5,468 B；
 *   KaTeX_Size2-Regular.woff2 5,208 B；KaTeX_Size3-Regular.woff2 3,624 B；
 *   KaTeX_Size4-Regular.woff2 4,928 B；KaTeX_Typewriter-Regular.woff2 13,568 B。
 * - mermaid 10.9.1（MIT），下载自
 *   https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js 3,335,717 B；
 *   仅在正文里存在 mermaid 围栏时注入，不在每条消息里加载。
 *
 * 语法：$$...$$ 独占一行（或成对围栏）为块级公式；$...$ 为行内公式，行内代码与
 * 代码块中的美元符号不参与解析；mermaid 围栏代码块渲染成 SVG，失败时保留原始
 * 代码块。KaTeX 同步渲染、mermaid 异步渲染，高度由 native 在 onPageFinished 后
 * 按 300ms、800ms、1500ms 各补测一次。
 */
object Markdown {

    private val HR_RULE = Regex("^[-*_]{3,}$")
    private val BULLET_ITEM = Regex("^\\s*[-*+]\\s+")
    private val ORDERED_ITEM = Regex("^\\s*\\d+[.)]\\s+")
    private val INLINE_CODE = Regex("`([^`]+)`")
    private val INLINE_MATH = Regex("""(?<!\\)\$(?=\S)([^\n$]*?\S)\$(?![\d$])""")
    private val LINK = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
    private val BOLD_STAR = Regex("\\*\\*([^*]+)\\*\\*")
    private val BOLD_UNDERSCORE = Regex("__([^_]+)__")
    private val ITALIC_STAR = Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)")

    /** 解析过程中收集「页面是否真的需要某套资源」，head 只注入用得到的资源 */
    private class Features {
        var math = false
        var mermaid = false
    }

    fun toHtml(markdown: String, isDark: Boolean = true): String {
        val features = Features()
        val body = StringBuilder()
        val lines = markdown.replace("\r\n", "\n").split('\n')
        var index = 0
        var inCode = false
        var codeLang = ""
        val code = StringBuilder()
        var listOpen = false
        var ordered = false

        fun closeList() {
            if (listOpen) {
                body.append(if (ordered) "</ol>" else "</ul>")
                listOpen = false
            }
        }

        fun appendBlockMath(tex: String) {
            features.math = true
            body.append("<div class=\"math-block\">")
                .append(escape(tex.trim()))
                .append("</div>")
        }

        fun flushCode() {
            val language = codeLang.trim().lowercase()
                .substringBefore(' ')
                .substringBefore('\t')
            if (language == "mermaid") {
                // 源码放在 pre 里：mermaid 渲染成功才隐藏，失败就是普通代码块
                features.mermaid = true
                body.append("<div class=\"mermaid-block\">")
                    .append("<div class=\"mermaid-diagram\"></div>")
                    .append("<pre class=\"mermaid-src\"><code>")
                    .append(escape(code.toString().trimEnd('\n')))
                    .append("</code></pre></div>")
            } else {
                body.append("<pre><code>")
                    .append(escape(code.toString()))
                    .append("</code></pre>")
            }
            code.setLength(0)
        }

        while (index < lines.size) {
            val raw = lines[index]
            val line = raw.trimEnd()

            if (line.startsWith("```")) {
                if (inCode) {
                    flushCode()
                    inCode = false
                    codeLang = ""
                } else {
                    closeList()
                    inCode = true
                    codeLang = line.removePrefix("```").trim()
                }
                index++
                continue
            }
            if (inCode) {
                code.append(raw).append('\n')
                index++
                continue
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                closeList()
                index++
                continue
            }

            if (trimmed.startsWith("$$")) {
                closeList()
                val afterOpen = trimmed.substring(2)
                val sameLineClose = afterOpen.indexOf("$$")
                if (sameLineClose >= 0) {
                    appendBlockMath(afterOpen.substring(0, sameLineClose))
                    val tail = afterOpen.substring(sameLineClose + 2).trim()
                    if (tail.isNotEmpty()) {
                        body.append("<p>").append(inline(tail, features)).append("</p>")
                    }
                    index++
                } else {
                    // 成对围栏：一直收集到出现 $$ 的那一行（闭符号可以出现在行尾）
                    val tex = StringBuilder()
                    var tail = ""
                    if (afterOpen.isNotEmpty()) tex.append(afterOpen)
                    index++
                    while (index < lines.size) {
                        val candidate = lines[index]
                        val close = candidate.indexOf("$$")
                        if (close >= 0) {
                            val head = candidate.substring(0, close)
                            if (head.isNotEmpty()) {
                                if (tex.isNotEmpty()) tex.append('\n')
                                tex.append(head)
                            }
                            tail = candidate.substring(close + 2).trim()
                            index++
                            break
                        }
                        if (tex.isNotEmpty()) tex.append('\n')
                        tex.append(candidate)
                        index++
                    }
                    appendBlockMath(tex.toString())
                    if (tail.isNotEmpty()) {
                        body.append("<p>").append(inline(tail, features)).append("</p>")
                    }
                }
                continue
            }

            when {
                line.startsWith("#") -> {
                    closeList()
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    body.append("<h$level>")
                        .append(inline(line.drop(level).trim(), features))
                        .append("</h$level>")
                }
                line.matches(HR_RULE) -> {
                    closeList()
                    body.append("<hr/>")
                }
                line.startsWith(">") -> {
                    closeList()
                    body.append("<blockquote>")
                        .append(inline(line.removePrefix(">").trim(), features))
                        .append("</blockquote>")
                }
                BULLET_ITEM.containsMatchIn(line) -> {
                    if (!listOpen || ordered) {
                        closeList()
                        body.append("<ul>")
                        listOpen = true
                        ordered = false
                    }
                    body.append("<li>")
                        .append(inline(line.replaceFirst(BULLET_ITEM, ""), features))
                        .append("</li>")
                }
                ORDERED_ITEM.containsMatchIn(line) -> {
                    if (!listOpen || !ordered) {
                        closeList()
                        body.append("<ol>")
                        listOpen = true
                        ordered = true
                    }
                    body.append("<li>")
                        .append(inline(line.replaceFirst(ORDERED_ITEM, ""), features))
                        .append("</li>")
                }
                else -> {
                    closeList()
                    body.append("<p>").append(inline(line, features)).append("</p>")
                }
            }
            index++
        }
        if (inCode && code.isNotEmpty()) {
            flushCode()
        }
        closeList()

        val colors = if (isDark) {
            """
            :root { color-scheme: dark; }
            body { color:#E6F1ED; }
            h1,h2,h3,h4,h5,h6 { color:#F2FBF8; }
            a { color:#7FE7C4; }
            strong { color:#FFFFFF; }
            em { color:#D7E7E2; }
            code { background:#162623; color:#B9F3E0; }
            pre { background:#0E1B19; border:1px solid #1E3230; }
            pre code { color:#D6F5EA; }
            blockquote { border-left:3px solid #2E4A45; color:#B9CCC6; background:#101C1A; }
            hr { border-top:1px solid #1E3230; }
            th,td { border:1px solid #1E3230; }
            th { background:#162623; }
            """.trimIndent()
        } else {
            """
            :root { color-scheme: light; }
            body { color:#10201D; }
            h1,h2,h3,h4,h5,h6 { color:#0B1A17; }
            a { color:#11886B; }
            strong { color:#0B1A17; }
            em { color:#33433F; }
            code { background:#E9F0EE; color:#0F5C48; }
            pre { background:#F0F4F3; border:1px solid #DCE5E2; }
            pre code { color:#173A32; }
            blockquote { border-left:3px solid #BFD4CE; color:#41544F; background:#F0F4F3; }
            hr { border-top:1px solid #DCE5E2; }
            th,td { border:1px solid #DCE5E2; }
            th { background:#E9F0EE; }
            """.trimIndent()
        }

        // 只有页面里真的出现公式/图表时才注入对应资源，避免每条消息都加载大文件
        val katexHead = if (features.math) {
            "<link rel=\"stylesheet\" href=\"${RENDERER_BASE_URL}renderer/katex.min.css\">"
        } else {
            ""
        }
        val scripts = StringBuilder()
        if (features.math) {
            scripts.append("<script src=\"")
                .append(RENDERER_BASE_URL)
                .append("renderer/katex.min.js\"></script>")
            scripts.append(KATEX_RENDER_SCRIPT)
        }
        if (features.mermaid) {
            scripts.append("<div id=\"mermaid-sandbox\" aria-hidden=\"true\"></div>")
            scripts.append("<script src=\"")
                .append(RENDERER_BASE_URL)
                .append("renderer/mermaid.min.js\"></script>")
            scripts.append(mermaidRenderScript(if (isDark) "dark" else "default"))
        }

        return """<!doctype html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
$katexHead
<style>
html,body { margin:0; padding:0; background:transparent; }
body {
  font: 15px/1.65 -apple-system, "Noto Sans CJK SC", "Source Han Sans", sans-serif;
  word-wrap: break-word; overflow-wrap: anywhere; -webkit-text-size-adjust: 100%;
}
$colors
p { margin: 0 0 10px; }
h1,h2,h3,h4,h5,h6 { margin: 14px 0 8px; line-height: 1.35; }
h1 { font-size: 20px; } h2 { font-size: 18px; } h3 { font-size: 16px; }
h4,h5,h6 { font-size: 15px; }
a { text-decoration:none; }
strong { font-weight:600; }
code { font-family: ui-monospace, "JetBrains Mono", monospace; font-size: 13px;
  padding:1px 5px; border-radius:5px; }
pre { margin: 10px 0; padding: 12px; border-radius:10px; overflow-x:auto; }
pre code { background:transparent; padding:0; font-size:12.5px; line-height:1.55; }
ul,ol { margin: 6px 0 10px; padding-left: 22px; }
li { margin: 3px 0; }
blockquote { margin: 8px 0; padding: 6px 12px; border-radius:0 8px 8px 0; }
hr { border:0; margin: 14px 0; }
table { border-collapse: collapse; margin: 10px 0; width:100%; }
th,td { padding:6px 8px; font-size:13px; }
.math-block { margin: 12px 0; overflow-x:auto; overflow-y:hidden; }
.math-block .katex-display { margin: 0; }
.katex { color: inherit; }
.mermaid-block { margin: 12px 0; }
.mermaid-block svg { display:block; max-width:100%; height:auto; margin:0 auto; }
.mermaid-src { margin: 0; }
#mermaid-sandbox { position: fixed; top: 0; left: -10000px; width: 360px;
  opacity: 0; pointer-events: none; }
</style></head><body>$body$scripts</body></html>"""
    }

    private val KATEX_RENDER_SCRIPT = """
    <script>
    (function () {
      if (typeof katex === 'undefined') return;
      var nodes = document.querySelectorAll('.math-inline, .math-block');
      for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        var display = node.className.indexOf('math-block') >= 0;
        try {
          katex.render(node.textContent, node, {
            displayMode: display,
            throwOnError: true,
            strict: false,
            trust: false
          });
        } catch (err) {
          // 保留原始 TeX 文本
        }
      }
    })();
    </script>
    """.trimIndent()

    private fun mermaidRenderScript(theme: String): String = """
    <script>
    (async function () {
      try {
        if (typeof mermaid === 'undefined') return;
        var sandbox = document.getElementById('mermaid-sandbox');
        var blocks = document.querySelectorAll('.mermaid-block');
        if (!sandbox || blocks.length === 0) return;
        mermaid.initialize({
          startOnLoad: false,
          theme: '$theme',
          securityLevel: 'strict',
          logLevel: 'fatal',
          fontFamily: '-apple-system, "Noto Sans CJK SC", "Source Han Sans", sans-serif'
        });
        for (var i = 0; i < blocks.length; i++) {
          var block = blocks[i];
          var source = block.querySelector('.mermaid-src');
          var host = block.querySelector('.mermaid-diagram');
          if (!source || !host) continue;
          var code = source.textContent || '';
          sandbox.style.width = (document.body.clientWidth || 360) + 'px';
          try {
            var parsed = await mermaid.parse(code, { suppressErrors: true });
            if (!parsed) continue;
            sandbox.innerHTML = '';
            var result = await mermaid.render('mermaid-svg-' + i, code, sandbox);
            host.innerHTML = result.svg;
            source.style.display = 'none';
            if (result.bindFunctions) result.bindFunctions(host);
          } catch (err) {
            // 保留 mermaid-src 里的原始代码块作为降级展示
          } finally {
            sandbox.innerHTML = '';
          }
        }
      } catch (err) {
        // mermaid 初始化失败时所有代码块保持原样
      }
    })();
    </script>
    """.trimIndent()

    private fun escape(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }

    /**
     * 判断 $...$ 中间的内容是否更像公式而不是普通美元文本。
     * 数字开头且带空格的片段必须有数学符号（例如 $5 + 3$），
     * 句末标点后跟空格的片段按自然语言处理，避免把
     * 「$5 and US$ 20」这类价格文本渲染成公式。
     *
     * 同时被 MarkdownCompose.kt 的 needsRichRenderer 复用：两边对
     * 「这段 $...$ 是不是公式」的判断必须一致，否则会出现原生按字面量
     * 显示、WebView 却能渲染的割裂。
     */
    internal fun looksLikeInlineMath(tex: String): Boolean {
        if (tex.isBlank()) return false
        val hasMathSymbol = tex.any { ch ->
            ch == '\\' || ch in "=+-*/^_<>(){}[]|&"
        }
        if (tex.first().isDigit() && tex.any { it.isWhitespace() } && !hasMathSymbol) {
            return false
        }
        return listOf(". ", "; ", ": ", "! ", "? ").none { tex.contains(it) }
    }

    private fun inline(text: String, features: Features): String {
        val tokens = ArrayList<String>()

        fun stash(html: String): String {
            tokens += html
            return "\u0000${tokens.size - 1}\u0000"
        }

        // 行内代码先摘出：代码里的 $ 与 * 不参与公式/强调解析
        var rest = INLINE_CODE.replace(text) { match ->
            stash("<code>${escape(match.groupValues[1])}</code>")
        }
        // \$ 是字面量美元，不参与 $...$ 匹配
        rest = rest.replace("\\$", stash("$"))
        // 行内公式：开头紧跟非空白，结尾为非空白且后面不是数字或另一个 $，
        // 这样「价格 $5 和 $10」这类普通美元文本不会被误判
        rest = INLINE_MATH.replace(rest) { match ->
            val tex = match.groupValues[1]
            if (!looksLikeInlineMath(tex)) {
                match.value
            } else {
                features.math = true
                stash("<span class=\"math-inline\">${escape(tex)}</span>")
            }
        }

        var out = escape(rest)
        out = LINK.replace(out) { match ->
            val label = match.groupValues[1]
            val href = match.groupValues[2]
            if (href.startsWith("http://") || href.startsWith("https://")) {
                "<a href=\"$href\">$label</a>"
            } else {
                label
            }
        }
        out = BOLD_STAR.replace(out, "<strong>$1</strong>")
        out = BOLD_UNDERSCORE.replace(out, "<strong>$1</strong>")
        out = ITALIC_STAR.replace(out, "<em>$1</em>")
        // 倒序还原：后摘出的公式片段里可能嵌有更早的字面量临时标记
        for (tokenIndex in tokens.indices.reversed()) {
            out = out.replace("\u0000$tokenIndex\u0000", tokens[tokenIndex])
        }
        return out
    }
}

/** 只允许从 assets/renderer/ 读出的白名单资源，其余请求一律不提供内容 */
private fun openRendererAsset(
    assets: AssetManager,
    request: WebResourceRequest?,
): WebResourceResponse? {
    val uri: Uri = request?.url ?: return null
    if (!"https".equals(uri.scheme, ignoreCase = true)) return null
    if (!RENDERER_HOST.equals(uri.host, ignoreCase = true)) return null
    val path = uri.path ?: return null
    if (!path.startsWith(RENDERER_PATH_PREFIX)) return null
    if (path.contains("..") || path.contains('\\')) return null
    val assetPath = path.substring(1)
    val mime = when {
        assetPath.endsWith(".js") -> "application/javascript"
        assetPath.endsWith(".css") -> "text/css"
        assetPath.endsWith(".woff2") -> "font/woff2"
        assetPath.endsWith(".woff") -> "font/woff"
        assetPath.endsWith(".ttf") -> "font/ttf"
        else -> return null
    }
    val stream = try {
        assets.open(assetPath)
    } catch (e: IOException) {
        return null
    }
    val encoding = if (mime.startsWith("font/")) null else "utf-8"
    return WebResourceResponse(mime, encoding, stream)
}

private fun emptyResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

/** 连续两次测量：首次布局与折行完成后的高度可能不同 */
private fun remeasure(view: android.view.View, apply: (Int) -> Unit) {
    val web = view as? WebView ?: return
    val first = web.contentHeight.coerceAtLeast(16)
    apply(first)
    web.postDelayed({
        val second = web.contentHeight.coerceAtLeast(16)
        if (second != first) apply(second + 2)
    }, 120)
}

/** KaTeX 是同步渲染，mermaid 是异步渲染；渲染完成后页面高度会变，补测几次 */
private fun remeasureWhenRenderSettles(view: android.view.View, apply: (Int) -> Unit) {
    val web = view as? WebView ?: return
    RENDER_SETTLE_DELAYS_MS.forEach { delay ->
        web.postDelayed({
            if (web.isAttachedToWindow) remeasure(web, apply)
        }, delay)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val dark = DlaColors.isDark
    // 编译结果只放在 Compose 的内存缓存里，按 markdown 内容与主题复用，不做磁盘缓存
    val html = remember(markdown, dark) { Markdown.toHtml(markdown, dark) }
    var heightDp by remember(markdown) { mutableIntStateOf(24) }
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
        factory = { context ->
            val assets = context.applicationContext.assets
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                // 隔离容器：JS 仅因 KaTeX / mermaid 渲染而打开。没有 addJavascriptInterface，
                // 没有 file 访问，blockNetworkLoads 禁网，mixedContentMode 禁混合内容；
                // 页面里所有脚本/样式/字体只能来自 shouldInterceptRequest 从
                // assets/renderer 读出的字节，其它 URL 一律空响应。
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.setGeolocationEnabled(false)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse =
                        openRendererAsset(assets, request) ?: emptyResponse()

                    override fun onPageFinished(view: WebView?, url: String?) {
                        view ?: return
                        remeasure(view) { heightDp = it }
                        remeasureWhenRenderSettles(view) { heightDp = it }
                    }
                }
                // 宽度变化会让正文重新折行，必须重新量高度，否则会被裁掉
                addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
                    if (right - left != oldRight - oldLeft) {
                        view.post { remeasure(view) { heightDp = it } }
                    }
                }
                loadDataWithBaseURL(RENDERER_BASE_URL, html, "text/html", "utf-8", null)
            }
        },
    )
}
