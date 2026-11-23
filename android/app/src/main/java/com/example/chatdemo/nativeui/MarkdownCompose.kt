package com.example.chatdemo.nativeui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 普通 Markdown 的原生 Compose 渲染。
 *
 * 聊天列表是反向 LazyColumn，条目会随快速滑动被回收/重建。旧实现每条助手消息
 * 都是一个独立 WebView，条目重新组合时要重新 loadDataWithBaseURL：加载前高度
 * 只有 24dp，加载后跳到真实高度，于是出现「文字啪地出现/消失 + 高度跳变」。
 * 原生渲染是纯 Compose 文本，首帧就能同步测出高度，回收重建也不会再加载网页。
 *
 * 公式（KaTeX）与 mermaid 流程图仍然交给 Markdown.kt 里的 WebView 渲染器，
 * 是否走 WebView 由 [needsRichRenderer] 决定。
 */

private val BODY_FONT_SIZE = 15.sp
private val BODY_LINE_HEIGHT = 24.sp

/** 围栏代码块行（允许 0~3 个前导空格）；判断公式与解析器共用同一份规则。 */
private val FENCE_LINE = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
private val CODE_FONT_SIZE = 13.sp
private val CODE_LINE_HEIGHT = 19.sp
private val TABLE_FONT_SIZE = 13.sp
private val TABLE_LINE_HEIGHT = 19.sp

/**
 * 这条文本是否必须用 WebView（KaTeX / mermaid）渲染。
 *
 * 原因：
 * 1. `$...$`、行首 `$$...$$` 需要 KaTeX 排版，Compose 没有公式排版能力，
 *    强行原生渲染只能显示 TeX 源码，属于功能回退；
 * 2. ```mermaid 围栏需要执行 mermaid.js 并做 SVG 布局，同样只有 WebView 能做。
 *
 * 其余普通 Markdown 一律走 [NativeMarkdown]：高度同步可测，LazyColumn
 * 快速滑动回收条目时不会重建 WebView，也就没有文字忽隐忽现和加载后跳高。
 *
 * 扫描时跳过围栏代码块和行内代码，因为里面的 `$` 只是普通字符，现有 WebView
 * 渲染器也不会把它们当公式。判断公式时复用 [Markdown.looksLikeInlineMath]，
 * 保证「原生 / WebView」两边的判断一致，避免出现原生显示字面 TeX、WebView
 * 又能渲染的割裂。宁可把少量价格文本误判成公式（多走一次 WebView），也不能
 * 漏判真正的公式导致数学内容显示成源码。
 */
fun needsRichRenderer(text: String): Boolean {
    if (text.isEmpty()) return false
    var inFence = false
    var fenceChar = ' '
    var fenceLength = 0
    var lineStart = 0
    while (lineStart <= text.length) {
        var lineEnd = text.indexOf('\n', lineStart)
        if (lineEnd < 0) lineEnd = text.length
        val line = text.substring(lineStart, lineEnd)
        val fence = FENCE_LINE.find(line)
        if (fence != null) {
            val marker = fence.groupValues[1]
            val info = fence.groupValues[2].trim()
            if (!inFence) {
                // 与 Markdown.toHtml 一致：只有反引号围栏 + mermaid 语言才注入 mermaid。
                if (marker[0] == '`' &&
                    info.substringBefore(' ').substringBefore('\t').equals("mermaid", ignoreCase = true)
                ) {
                    return true
                }
                inFence = true
                fenceChar = marker[0]
                fenceLength = marker.length
            } else if (marker[0] == fenceChar && marker.length >= fenceLength && info.isEmpty()) {
                inFence = false
            }
        } else if (!inFence) {
            // 与 Markdown.toHtml 一致：块级公式认「去掉行首空白后以 $$ 开头」的行。
            if (line.trimStart().startsWith("$$")) return true
            if (lineHasInlineMath(line)) return true
        }
        if (lineEnd >= text.length) break
        lineStart = lineEnd + 1
    }
    return false
}

/** 单行内的 $...$ 检测；跳过反引号代码与转义美元。 */
private fun lineHasInlineMath(line: String): Boolean {
    val n = line.length
    var i = 0
    while (i < n) {
        val c = line[i]
        if (c == '\\') {
            i += 2
            continue
        }
        if (c == '`') {
            var run = 1
            while (i + run < n && line[i + run] == '`') run++
            val close = findBacktickClose(line, i + run, n, run)
            i = if (close >= 0) close + run else i + run
            continue
        }
        if (c != '$') {
            i++
            continue
        }
        var j = i + 1
        while (j < n) {
            if (line[j] == '\\') {
                j += 2
                continue
            }
            if (line[j] == '$') break
            j++
        }
        if (j < n) {
            val tex = line.substring(i + 1, j)
            val after = if (j + 1 < n) line[j + 1] else '\u0000'
            if (tex.isNotEmpty() &&
                !tex.first().isWhitespace() &&
                !tex.last().isWhitespace() &&
                after != '$' &&
                !after.isDigit() &&
                Markdown.looksLikeInlineMath(tex)
            ) {
                return true
            }
            i = j + 1
            continue
        }
        i++
    }
    return false
}

// ---------------------------------------------------------------------------
// 块模型与解析
// ---------------------------------------------------------------------------

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MdItem>) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val aligns: List<TextAlign>,
    ) : MdBlock

    object Divider : MdBlock
}

private data class MdItem(val level: Int, val text: String)

/**
 * 自己的小块解析器。设计目标是「流式安全」：
 * - 所有分支都保证向下推进，外层还有步数上限兜底，不会死循环；
 * - 未闭合的围栏代码块会一直收集到文本末尾，未闭合的强调/链接按字面量输出；
 * - 任何意料之外的异常都由 [NativeMarkdown] 兜底成纯文本，不会让聊天页崩溃。
 */
private object MdParser {

    private val HEADING = Regex("""^(#{1,6})(?!#)\s*(.+)$""")
    private val TRAILING_HASHES = Regex("""\s+#+\s*$""")
    private val HR = Regex("""^([-*_])(?:\s*\1){2,}\s*$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val ORDERED = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
    private val TABLE_SEPARATOR_CELL = Regex("""^:?-+:?$""")

    fun parse(source: String): List<MdBlock> {
        if (source.isBlank()) return emptyList()
        val text = source.replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        val blocks = ArrayList<MdBlock>()
        var i = 0
        // 防御性上限：正常路径每轮至少消费一行；即使以后改坏某个分支也不会卡死。
        var steps = 0
        val maxSteps = lines.size * 2 + 8
        while (i < lines.size) {
            if (++steps > maxSteps) break
            val raw = lines[i]
            if (raw.isBlank()) {
                i++
                continue
            }

            val fence = FENCE_LINE.find(raw)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val info = fence.groupValues[2].trim()
                val language = info.substringBefore(' ').substringBefore('\t')
                val code = StringBuilder()
                i++
                while (i < lines.size) {
                    if (isClosingFence(lines[i], marker[0], marker.length)) {
                        i++
                        break
                    }
                    code.append(lines[i]).append('\n')
                    i++
                }
                blocks += MdBlock.Code(
                    language = language,
                    code = code.toString().trimEnd('\n').replace("\t", "    "),
                )
                continue
            }

            val table = tableAt(lines, i)
            if (table != null) {
                blocks += table.block
                i = table.next
                continue
            }

            val heading = headingAt(raw)
            if (heading != null) {
                blocks += heading
                i++
                continue
            }

            if (HR.matches(raw.trimEnd())) {
                blocks += MdBlock.Divider
                i++
                continue
            }

            if (raw.trimStart().startsWith(">")) {
                val quote = StringBuilder()
                while (i < lines.size) {
                    val line = lines[i].trimStart()
                    if (!line.startsWith(">")) break
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(line.removePrefix(">").removePrefix(" ").trimEnd())
                    i++
                }
                blocks += MdBlock.Quote(quote.toString())
                continue
            }

            val bullet = BULLET.find(raw)
            val ordered = ORDERED.find(raw)
            if (bullet != null || ordered != null) {
                val list = buildListBlock(lines, i, ordered != null)
                blocks += list.block
                i = list.next
                continue
            }

            val paragraph = StringBuilder(raw.trim())
            i++
            while (i < lines.size) {
                val next = lines[i]
                if (next.isBlank()) break
                if (startsBlock(lines, i)) break
                paragraph.append('\n').append(next.trim())
                i++
            }
            blocks += MdBlock.Paragraph(paragraph.toString())
        }
        return blocks
    }

    private fun headingAt(line: String): MdBlock.Heading? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("#")) return null
        val match = HEADING.find(trimmed) ?: return null
        val content = match.groupValues[2].replace(TRAILING_HASHES, "").trim()
        if (content.isEmpty()) return null
        return MdBlock.Heading(match.groupValues[1].length, content)
    }

    private fun isClosingFence(line: String, ch: Char, minRun: Int): Boolean {
        val trimmed = line.trimStart()
        var run = 0
        while (run < trimmed.length && trimmed[run] == ch) run++
        if (run < minRun) return false
        return trimmed.substring(run).isBlank()
    }

    private class TableHit(val block: MdBlock.Table, val next: Int)

    /** 只识别「表头行 + |---| 分隔行」这种最稳的表格，避免把普通竖线文本误判成表格。 */
    private fun tableAt(lines: List<String>, index: Int): TableHit? {
        if (index + 1 >= lines.size) return null
        val headerLine = lines[index]
        if (!headerLine.contains('|')) return null
        val separatorLine = lines[index + 1]
        if (!separatorLine.contains('-')) return null
        val header = splitRow(headerLine)
        val separator = splitRow(separatorLine)
        if (header.isEmpty() || separator.size != header.size) return null
        if (!separator.all { TABLE_SEPARATOR_CELL.matches(it.trim()) }) return null

        val aligns = separator.map { cell ->
            val c = cell.trim()
            when {
                c.startsWith(":") && c.endsWith(":") -> TextAlign.Center
                c.endsWith(":") -> TextAlign.End
                else -> TextAlign.Start
            }
        }
        val rows = ArrayList<List<String>>()
        var i = index + 2
        while (i < lines.size) {
            val rowLine = lines[i]
            if (rowLine.isBlank() || !rowLine.contains('|')) break
            val cells = splitRow(rowLine)
            rows += List(header.size) { column -> cells.getOrElse(column) { "" } }
            i++
        }
        return TableHit(MdBlock.Table(header, rows, aligns), i)
    }

    /** 按未转义的 | 切分，保留转义 \| 为普通字符。 */
    private fun splitRow(line: String): List<String> {
        var text = line.trim()
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|") && !text.endsWith("\\|")) text = text.dropLast(1)
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && text[i + 1] == '|') {
                cell.append('|')
                i += 2
                continue
            }
            if (c == '|') {
                cells += cell.toString().trim()
                cell.setLength(0)
                i++
                continue
            }
            cell.append(c)
            i++
        }
        cells += cell.toString().trim()
        return cells
    }

    private class ListHit(val block: MdBlock.ListBlock, val next: Int)

    private fun buildListBlock(lines: List<String>, start: Int, ordered: Boolean): ListHit {
        val items = ArrayList<MdItem>()
        var i = start
        var startNumber = 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) break
            if (ordered) {
                val match = ORDERED.find(line)
                if (match != null) {
                    if (items.isEmpty()) {
                        startNumber = match.groupValues[2].toIntOrNull()?.coerceIn(1, 999_999) ?: 1
                    }
                    items += MdItem(levelFor(match.groupValues[1].length), stripTaskBox(match.groupValues[3]).trim())
                    i++
                    continue
                }
                if (BULLET.containsMatchIn(line)) break
            } else {
                val match = BULLET.find(line)
                if (match != null) {
                    items += MdItem(levelFor(match.groupValues[1].length), stripTaskBox(match.groupValues[2]).trim())
                    i++
                    continue
                }
                if (ORDERED.containsMatchIn(line)) break
            }
            // 列表项的续行：只有缩进行才并入上一条，避免把后面的普通段落吞进列表。
            if (line.firstOrNull()?.isWhitespace() == true && items.isNotEmpty()) {
                val last = items.removeAt(items.size - 1)
                items += last.copy(text = last.text + "\n" + line.trim())
                i++
                continue
            }
            break
        }
        return ListHit(MdBlock.ListBlock(ordered, startNumber, items), i)
    }

    private fun levelFor(indent: Int): Int = when {
        indent >= 4 -> 2
        indent >= 2 -> 1
        else -> 0
    }

    /** 顺带支持任务列表：- [ ] / - [x] */
    private fun stripTaskBox(content: String): String {
        val text = content.trimStart()
        if (text.length >= 3 && text[0] == '[' && text[2] == ']' &&
            (text[1] == ' ' || text[1] == 'x' || text[1] == 'X')
        ) {
            return (if (text[1] == ' ') "☐ " else "☑ ") + text.substring(3).trimStart()
        }
        return content
    }

    private fun startsBlock(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        if (line.isBlank()) return true
        if (FENCE_LINE.find(line) != null) return true
        if (headingAt(line) != null) return true
        if (HR.matches(line.trimEnd())) return true
        if (line.trimStart().startsWith(">")) return true
        if (BULLET.containsMatchIn(line) || ORDERED.containsMatchIn(line)) return true
        if (tableAt(lines, index) != null) return true
        return false
    }
}

// ---------------------------------------------------------------------------
// 行内解析：All in one AnnotatedString
// ---------------------------------------------------------------------------

private const val MAX_INLINE_DEPTH = 12

private fun buildInlineAnnotated(
    text: String,
    palette: DlaPalette,
    linkListener: LinkInteractionListener,
): AnnotatedString {
    val builder = AnnotatedString.Builder(text.length)
    appendInline(builder, text, 0, text.length, palette, linkListener, 0)
    return builder.toAnnotatedString()
}

private fun appendInline(
    builder: AnnotatedString.Builder,
    text: String,
    start: Int,
    end: Int,
    palette: DlaPalette,
    linkListener: LinkInteractionListener,
    depth: Int,
) {
    if (start >= end) return
    if (depth > MAX_INLINE_DEPTH) {
        builder.append(text, start, end)
        return
    }
    var i = start
    while (i < end) {
        val c = text[i]
        if (c == '\\') {
            if (i + 1 < end) {
                builder.append(text[i + 1])
                i += 2
            } else {
                builder.append('\\')
                i++
            }
            continue
        }
        if (c == '\n') {
            builder.append('\n')
            i++
            continue
        }
        if (c == '`') {
            var run = 1
            while (i + run < end && text[i + run] == '`') run++
            val close = findBacktickClose(text, i + run, end, run)
            if (close >= 0) {
                builder.pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = CODE_FONT_SIZE,
                        color = palette.mint,
                        background = palette.surfaceHigh,
                    ),
                )
                builder.append(text, i + run, close)
                builder.pop()
                i = close + run
            } else {
                builder.append(text, i, i + run)
                i += run
            }
            continue
        }
        if (c == '[') {
            val link = findLink(text, i, end)
            if (link != null) {
                val url = normalizeUrl(text, link.urlStart, link.urlEnd)
                if (url != null && link.labelEnd > link.labelStart) {
                    val linkBegin = builder.length
                    builder.pushStyle(SpanStyle(color = palette.mint))
                    appendInline(builder, text, link.labelStart, link.labelEnd, palette, linkListener, depth + 1)
                    builder.pop()
                    val linkEnd = builder.length
                    if (linkEnd > linkBegin) {
                        builder.addLink(
                            LinkAnnotation.Url(
                                url,
                                TextLinkStyles(SpanStyle(color = palette.mint)),
                                linkListener,
                            ),
                            linkBegin,
                            linkEnd,
                        )
                    }
                } else {
                    appendInline(builder, text, link.labelStart, link.labelEnd, palette, linkListener, depth + 1)
                }
                i = link.end
            } else {
                builder.append('[')
                i++
            }
            continue
        }
        if (c == '*' || c == '_') {
            i = appendEmphasis(builder, text, i, end, palette, linkListener, depth)
            continue
        }
        if (c == '~' && i + 1 < end && text[i + 1] == '~') {
            val close = findDelimiter(text, "~~", i + 2, end)
            if (close > i + 2) {
                builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                appendInline(builder, text, i + 2, close, palette, linkListener, depth + 1)
                builder.pop()
                i = close + 2
            } else {
                builder.append('~')
                i++
            }
            continue
        }
        builder.append(c)
        i++
    }
}

private fun appendEmphasis(
    builder: AnnotatedString.Builder,
    text: String,
    index: Int,
    end: Int,
    palette: DlaPalette,
    linkListener: LinkInteractionListener,
    depth: Int,
): Int {
    val ch = text[index]
    var run = 1
    while (index + run < end && text[index + run] == ch) run++

    if (run >= 3) {
        val delimiter = "$ch$ch$ch"
        val close = findDelimiter(text, delimiter, index + 3, end)
        if (close > index + 3) {
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic))
            appendInline(builder, text, index + 3, close, palette, linkListener, depth + 1)
            builder.pop()
            return close + 3
        }
    }
    if (run >= 2) {
        val delimiter = "$ch$ch"
        val close = findDelimiter(text, delimiter, index + 2, end)
        if (close > index + 2 && (ch != '_' || underscoreBoundary(text, index, close + 2, end))) {
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
            appendInline(builder, text, index + 2, close, palette, linkListener, depth + 1)
            builder.pop()
            return close + 2
        }
    }
    if (run >= 1) {
        val close = findSingleDelimiter(text, ch, index + 1, end)
        if (close > index + 1 && (ch != '_' || underscoreBoundary(text, index, close + 1, end))) {
            builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
            appendInline(builder, text, index + 1, close, palette, linkListener, depth + 1)
            builder.pop()
            return close + 1
        }
    }
    builder.append(text, index, index + run)
    return index + run
}

/** 单个 * 或 _ 的闭合位置；跳过成对的 ** / __，避免把加粗闭合误当成斜体闭合。 */
private fun findSingleDelimiter(text: String, ch: Char, from: Int, end: Int): Int {
    var i = from
    while (i < end) {
        val c = text[i]
        if (c == '\n') return -1
        if (c == '\\') {
            i += 2
            continue
        }
        if (c == ch) {
            val nextSame = i + 1 < end && text[i + 1] == ch
            val prevSame = i > from && text[i - 1] == ch
            if (!nextSame && !prevSame) return i
            if (nextSame) {
                i += 2
                continue
            }
        }
        i++
    }
    return -1
}

/** 查找成对分隔符（**、__、***、~~）；不跨行，避免流式过程中的字面量误配到很远。 */
private fun findDelimiter(text: String, delimiter: String, from: Int, end: Int): Int {
    var i = from
    while (i + delimiter.length <= end) {
        if (text[i] == '\n') return -1
        if (text.startsWith(delimiter, i) && (i == 0 || text[i - 1] != '\\')) return i
        i++
    }
    return -1
}

/** 反引号代码：必须用相同长度的反引号串闭合，未闭合时按字面量输出。 */
private fun findBacktickClose(text: String, from: Int, end: Int, run: Int): Int {
    var i = from
    while (i < end) {
        if (text[i] == '\n') return -1
        if (text[i] == '`') {
            var j = i
            while (j < end && text[j] == '`') j++
            if (j - i == run) return i
            i = j
        } else {
            i++
        }
    }
    return -1
}

private fun underscoreBoundary(text: String, open: Int, afterClose: Int, end: Int): Boolean {
    val before = if (open > 0) text[open - 1] else ' '
    val after = if (afterClose < end) text[afterClose] else ' '
    return !before.isLetterOrDigit() && !after.isLetterOrDigit()
}

private class LinkMatch(
    val labelStart: Int,
    val labelEnd: Int,
    val urlStart: Int,
    val urlEnd: Int,
    val end: Int,
)

/** 解析 [label](url)，允许 label 里再嵌套一对方括号，url 里最多一层括号。 */
private fun findLink(text: String, start: Int, end: Int): LinkMatch? {
    var depth = 0
    var i = start
    while (i < end) {
        when (text[i]) {
            '\\' -> i += 2
            '\n' -> return null
            '[' -> {
                depth++
                i++
            }
            ']' -> {
                depth--
                if (depth == 0) {
                    if (i + 1 < end && text[i + 1] == '(') {
                        var j = i + 2
                        var parens = 0
                        while (j < end) {
                            when (text[j]) {
                                '\\' -> j += 2
                                '\n' -> return null
                                '(' -> {
                                    parens++
                                    j++
                                }
                                ')' -> {
                                    if (parens == 0) {
                                        return LinkMatch(start + 1, i, i + 2, j, j + 1)
                                    }
                                    parens--
                                    j++
                                }
                                else -> j++
                            }
                        }
                    }
                    return null
                }
                i++
            }
            else -> i++
        }
    }
    return null
}

private fun normalizeUrl(text: String, start: Int, end: Int): String? {
    var s = start
    var e = end
    while (s < e && text[s].isWhitespace()) s++
    while (e > s && text[e - 1].isWhitespace()) e--
    if (s >= e) return null
    if (text[s] == '<' && text[e - 1] == '>') {
        s++
        e--
    }
    // [x](url "title")：去掉标题，只保留 URL。
    var i = s
    while (i < e) {
        if (text[i].isWhitespace()) {
            e = i
            break
        }
        i++
    }
    if (s >= e) return null
    val url = text.substring(s, e)
    val lower = url.lowercase()
    return if (lower.startsWith("http://") ||
        lower.startsWith("https://") ||
        lower.startsWith("mailto:")
    ) {
        url
    } else {
        null
    }
}

// ---------------------------------------------------------------------------
// Compose 渲染
// ---------------------------------------------------------------------------

/**
 * 普通 Markdown 的原生渲染入口。
 *
 * 首帧就把全部文本量出来（同步 measure），外层另有 120ms 的淡入：
 * 这是绘制层动画，不改变布局高度，因此不会出现「先 0 高再跳高」。
 * 注意不要用 AnimatedVisibility / animateContentSize 包这个条目，
 * 那会跟 LazyColumn 的回收复用打架，反而制造高度动画抖动。
 */
@Composable
fun NativeMarkdown(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) {
        try {
            MdParser.parse(markdown)
        } catch (e: Exception) {
            // 解析器自身应当是安全的；这里只是最后一道兜底，保证聊天页不崩。
            listOf(MdBlock.Paragraph(markdown))
        }
    }

    // 只做 alpha 淡入，不做高度动画。动画结束后移除 layer，滑动时不常驻渲染层。
    val fade = remember { Animatable(0f) }
    var fadeDone by remember { mutableStateOf(false) }
    LaunchedEffect(fade) {
        fade.animateTo(1f, animationSpec = tween(durationMillis = 120))
        fadeDone = true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (fadeDone) {
                    Modifier
                } else {
                    Modifier.graphicsLayer { alpha = fade.value }
                },
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block -> MdBlockView(block) }
    }
}

@Composable
private fun MdBlockView(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> HeadingBlock(block)
        is MdBlock.Paragraph -> InlineMarkdown(
            text = block.text,
            modifier = Modifier.fillMaxWidth(),
        )
        is MdBlock.ListBlock -> ListBlockView(block)
        is MdBlock.Code -> CodeBlockView(block)
        is MdBlock.Quote -> QuoteBlockView(block)
        is MdBlock.Table -> TableBlockView(block)
        MdBlock.Divider -> HorizontalDivider(color = DlaColors.Line)
    }
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading) {
    val fontSize = when (block.level) {
        1 -> 20.sp
        2 -> 18.sp
        3 -> 16.sp
        else -> 15.sp
    }
    val lineHeight = when (block.level) {
        1 -> 27.sp
        2 -> 25.sp
        3 -> 23.sp
        else -> 22.sp
    }
    InlineMarkdown(
        text = block.text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ListBlockView(block: MdBlock.ListBlock) {
    val markers = remember(block) { listMarkers(block) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        block.items.forEachIndexed { index, item ->
            val level = item.level.coerceIn(0, 2)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (level * 16).dp),
            ) {
                Text(
                    text = markers.getOrElse(index) { "•" },
                    color = DlaColors.TextDim,
                    fontSize = BODY_FONT_SIZE,
                    lineHeight = BODY_LINE_HEIGHT,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .widthIn(min = 24.dp)
                        .padding(end = 6.dp),
                )
                InlineMarkdown(
                    text = item.text,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun listMarkers(block: MdBlock.ListBlock): List<String> {
    val counters = IntArray(3)
    return block.items.map { item ->
        val level = item.level.coerceIn(0, 2)
        if (block.ordered) {
            val start = if (level == 0) block.start else 1
            val n = counters[level]
            counters[level] = n + 1
            "${start + n}."
        } else {
            if (level == 0) "•" else "◦"
        }
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.Code) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DlaColors.SurfaceHigh)
            .border(1.dp, DlaColors.Line, shape)
            .padding(vertical = 8.dp),
    ) {
        if (block.language.isNotBlank()) {
            Text(
                text = block.language,
                color = DlaColors.TextDim,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = block.code,
                color = DlaColors.Text,
                fontSize = CODE_FONT_SIZE,
                lineHeight = CODE_LINE_HEIGHT,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun QuoteBlockView(block: MdBlock.Quote) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(DlaColors.Surface),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(DlaColors.Mint.copy(alpha = 0.45f)),
        )
        InlineMarkdown(
            text = block.text,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = DlaColors.TextDim,
        )
    }
}

@Composable
private fun TableBlockView(block: MdBlock.Table) {
    val shape = RoundedCornerShape(8.dp)
    val columns = block.header.size.coerceAtLeast(1)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DlaColors.Surface)
            .border(1.dp, DlaColors.Line, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DlaColors.SurfaceHigh),
        ) {
            for (column in 0 until columns) {
                TableCell(
                    text = block.header.getOrElse(column) { "" },
                    align = block.aligns.getOrNull(column) ?: TextAlign.Start,
                    header = true,
                )
            }
        }
        block.rows.forEach { row ->
            HorizontalDivider(color = DlaColors.Line)
            Row(modifier = Modifier.fillMaxWidth()) {
                for (column in 0 until columns) {
                    TableCell(
                        text = row.getOrElse(column) { "" },
                        align = block.aligns.getOrNull(column) ?: TextAlign.Start,
                        header = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.TableCell(text: String, align: TextAlign, header: Boolean) {
    InlineMarkdown(
        text = text,
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        fontSize = TABLE_FONT_SIZE,
        lineHeight = TABLE_LINE_HEIGHT,
        fontWeight = if (header) FontWeight.SemiBold else null,
        textAlign = align,
    )
}

/**
 * 行内 Markdown 的统一入口：文本 + 强调/代码/链接样式。
 * 链接通过 LinkAnnotation.Url 注册，点击时交给 LocalUriHandler 打开。
 */
@Composable
private fun InlineMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    fontSize: TextUnit = BODY_FONT_SIZE,
    lineHeight: TextUnit = BODY_LINE_HEIGHT,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    textAlign: TextAlign? = null,
) {
    val palette = LocalPalette.current
    val uriHandler = LocalUriHandler.current
    val linkListener = remember(uriHandler) {
        object : LinkInteractionListener {
            override fun onClick(link: LinkAnnotation) {
                val url = (link as? LinkAnnotation.Url)?.url ?: return
                runCatching { uriHandler.openUri(url) }
            }
        }
    }
    val annotated = remember(text, palette, linkListener) {
        try {
            buildInlineAnnotated(text, palette, linkListener)
        } catch (e: Exception) {
            // 行内解析失败时退回纯文本，宁可少一点样式也不能崩。
            AnnotatedString(text)
        }
    }
    Text(
        text = annotated,
        modifier = modifier,
        color = color ?: DlaColors.Text,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        textAlign = textAlign,
        lineHeight = lineHeight,
    )
}
