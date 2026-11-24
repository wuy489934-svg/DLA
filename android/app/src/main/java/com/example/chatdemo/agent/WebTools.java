package com.example.chatdemo.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 联网工具：web_search / web_fetch。
 *
 * 历史问题：这两个工具原来是 DeepSeek 专属（`POST {origin}/v1/tools/search`、`/v1/tools/fetch`），
 * 但工具是无条件注入的。用户把端点换成千问 / GLM 这类兼容网关后，请求打到网关根路径上，
 * 对端只认 `model` 参数，于是稳定返回 `HTTP 400: Required parameter "model" missing`——
 * 模型看到的就是「联网工具坏了」。根因不在手机，也不在模型，而是「工具后端没跟着供应商走」。
 *
 * 现在的策略：
 * - web_fetch：**原生抓取**，不依赖任何厂商接口——HTTP GET + 去标签 + 转正文，任何供应商都能用；
 * - web_search：DeepSeek 端点仍走官方 `/v1/tools/search`；其它供应商走免费 HTML 搜索后端
 *   （Bing → DuckDuckGo），解析标题/链接/摘要，失败时返回可读的原因和替代做法。
 */
public final class WebTools {

    private static final int MAX_TEXT = 24000;
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    /**
     * 用桌面 Chrome 的 UA：Bing / 百度对手机 UA 会返回完全不同的版式（甚至只给 JS 版），
     * 抓结果页时桌面版结构才稳定。
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    private WebTools() {}

    public static boolean isWeb(String name) {
        return "web_search".equals(name) || "web_fetch".equals(name);
    }

    public static JSONArray specs() {
        JSONArray list = new JSONArray();
        list.put(tool(
                "web_search",
                "联网搜索：返回标题、链接与摘要。DeepSeek 端点用官方搜索；其它供应商用免费网页搜索兜底。"
                        + "需要正文时对结果里的链接调用 web_fetch。",
                schema(props(
                        "query", p("string", "搜索词，支持 site: 等语法，1-128 字符"),
                        "count", p("number", "返回条数 1-20，默认 8"),
                        "need_content", p("boolean", "是否顺带抓前 2 条的正文，默认 false")
                ), "query")
        ));
        list.put(tool(
                "web_fetch",
                "抓取单个网页并转成正文文本（原生 HTTP，不依赖厂商接口）。用于阅读用户给的链接或搜索结果的详情页。",
                schema(props("url", p("string", "HTTP/HTTPS 网页地址")), "url")
        ));
        return list;
    }

    /**
     * @param provider 供应商 id（deepseek / qwen / zhipu / …），决定搜索走哪个后端
     */
    public static LocalTools.Result execute(String apiKey, String apiOrigin, String provider,
                                            String name, JSONObject args) {
        try {
            if ("web_fetch".equals(name)) {
                return fetch(args.optString("url"));
            }
            if ("web_search".equals(name)) {
                String query = args.optString("query").trim();
                if (query.isEmpty()) {
                    return fail("query 不能为空");
                }
                int count = Math.min(Math.max(args.optInt("count", 8), 1), 20);
                boolean needContent = args.optBoolean("need_content", false);
                if (ProviderCompat.DEEPSEEK.equals(provider)) {
                    LocalTools.Result official = searchDeepSeek(apiKey, apiOrigin, query, count, needContent);
                    if (!official.isError) {
                        return official;
                    }
                    // 官方端点不通（Key 不是 DeepSeek 的、或端点被换掉）就退到免费后端
                    LocalTools.Result fallback = searchHtml(query, count, needContent);
                    if (!fallback.isError) {
                        return new LocalTools.Result(
                                "（官方搜索不可用：" + official.text.replace("错误：", "") + "，已改用网页搜索）\n\n"
                                        + fallback.text,
                                false);
                    }
                    return fail(official.text.replace("错误：", "") + "；网页搜索也没成功：" + fallback.text.replace("错误：", ""));
                }
                return searchHtml(query, count, needContent);
            }
            return fail("未知联网工具：" + name);
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return fail(message);
        }
    }

    // ---------- web_fetch ----------

    static LocalTools.Result fetch(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.isEmpty()) {
            return fail("url 不能为空");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return fail("只支持 http/https 链接（收到：" + abbreviate(url, 60) + "）");
        }
        String guard = guardUrl(url);
        if (guard != null) {
            return fail(guard);
        }
        try {
            Response response = get(url);
            String type = response.contentType.toLowerCase();
            String text;
            if (type.contains("json")) {
                text = "```json\n" + abbreviate(pretty(response.text), MAX_TEXT) + "\n```";
            } else if (type.contains("html") || looksLikeHtml(response.text)) {
                text = htmlToText(response.text);
            } else {
                text = response.text;
            }
            if (text.trim().isEmpty()) {
                return fail("页面没有可读正文（可能是纯脚本渲染的页面，试试让用户换一个链接）");
            }
            String title = response.title.isEmpty() ? "" : "# " + response.title + "\n\n";
            String head = "（" + response.finalUrl + "）\n\n";
            return ok(title + head + abbreviate(text.trim(), MAX_TEXT));
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return fail("抓取失败：" + message);
        }
    }

    /** 拒绝本机/内网地址，避免被当成内网探测工具 */
    private static String guardUrl(String url) {
        String lower = url.toLowerCase();
        int start = lower.indexOf("://");
        String rest = start < 0 ? lower : lower.substring(start + 3);
        int slash = rest.indexOf('/');
        String host = (slash < 0 ? rest : rest.substring(0, slash));
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        if (host.isEmpty()) {
            return "链接里没有主机名";
        }
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("0.0.0.0")
                || host.startsWith("10.") || host.startsWith("192.168.")
                || host.startsWith("169.254.") || host.equals("[::1]")) {
            return "不允许抓取本机/内网地址（" + host + "）";
        }
        return null;
    }

    // ---------- web_search：DeepSeek 官方 ----------

    private static LocalTools.Result searchDeepSeek(String apiKey, String apiOrigin,
                                                    String query, int count, boolean needContent) {
        try {
            if (apiKey == null || apiKey.isEmpty()) {
                return fail("缺少 API Key");
            }
            String origin = normalizeOrigin(apiOrigin);
            JSONObject body = new JSONObject();
            body.put("query", query);
            body.put("count", count);
            body.put("need_content", needContent);
            JSONObject response = postJson(origin + "/v1/tools/search", apiKey, body);
            return ok(formatOfficial(response));
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return fail(message);
        }
    }

    private static String formatOfficial(JSONObject response) {
        JSONArray results = response.optJSONArray("results");
        if (results == null || results.length() == 0) {
            return "（没有搜索结果）";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) {
                continue;
            }
            builder.append(i + 1).append(". ").append(item.optString("title")).append('\n');
            builder.append("   ").append(item.optString("url")).append('\n');
            String summary = item.optString("summary");
            if (!summary.isEmpty()) {
                builder.append("   ").append(summary).append('\n');
            }
            String content = item.optString("content");
            if (!content.isEmpty()) {
                builder.append("   正文：").append(abbreviate(content, 1200)).append('\n');
            }
            String site = item.optString("site_name");
            String date = item.optString("date");
            if (!site.isEmpty() || !date.isEmpty()) {
                builder.append("   ").append(site);
                if (!date.isEmpty()) {
                    builder.append(" · ").append(date);
                }
                builder.append('\n');
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    // ---------- web_search：免费 HTML 后端 ----------

    private static final class Hit {
        final String title;
        final String url;
        final String snippet;

        Hit(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }

    private static LocalTools.Result searchHtml(String query, int count, boolean needContent) {
        StringBuilder errors = new StringBuilder();
        String encoded;
        boolean cjk = false;
        try {
            encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20");
            for (int i = 0; i < query.length(); i++) {
                if (query.charAt(i) > 0x2E80) {
                    cjk = true;
                    break;
                }
            }
        } catch (Exception error) {
            return fail("搜索词编码失败");
        }
        // 顺序有讲究：
        // 1) HTML 结果页相关性最好（RSS 端点结构稳但会丢查询里的数字/修饰词，只适合兜底）；
        // 2) 英文查询先去 www，中文查询先去 cn（国内 IP 会被重定向，两个都试一遍）；
        // 3) 最后是百度 / DuckDuckGo。
        java.util.List<String[]> ordered = new java.util.ArrayList<>();
        if (cjk) {
            // 中文查询：百度相关性最好，Bing 国内版常常把关键词吃掉（只给泛泛的首页）
            ordered.add(new String[]{"baidu", "https://www.baidu.com/s?ie=utf-8&wd="});
            ordered.add(new String[]{"bing-cn", "https://cn.bing.com/search?mkt=zh-CN&setlang=zh-CN&q="});
            ordered.add(new String[]{"bing", "https://www.bing.com/search?mkt=zh-CN&setlang=zh-CN&q="});
        } else {
            ordered.add(new String[]{"bing", "https://www.bing.com/search?mkt=en-US&setlang=en&q="});
            ordered.add(new String[]{"bing-cn", "https://cn.bing.com/search?mkt=en-US&setlang=en&q="});
            ordered.add(new String[]{"baidu", "https://www.baidu.com/s?ie=utf-8&wd="});
        }
        ordered.add(new String[]{"bing-rss", "https://cn.bing.com/search?format=rss&q="});
        ordered.add(new String[]{"bing-rss-intl", "https://www.bing.com/search?format=rss&q="});
        ordered.add(new String[]{"ddg", "https://html.duckduckgo.com/html/?q="});
        for (String[] backend : ordered) {
            String name = backend[0];
            try {
                Response response = get(backend[1] + encoded);
                java.util.List<Hit> hits;
                if (name.startsWith("bing-rss")) {
                    hits = parseRss(response.text);
                } else if ("baidu".equals(name)) {
                    hits = parseBaidu(response.text);
                } else if ("ddg".equals(name)) {
                    hits = parseDdg(response.text);
                } else {
                    hits = parseBingHtml(response.text);
                }
                if (hits.isEmpty()) {
                    // 诊断信息要留在错误里：下次再坏，一眼能看出是「没拿到结果页」还是「版式变了」
                    String sample = abbreviate(stripTags(response.text), 70);
                    errors.append(name).append("：200 但没解析到结果（")
                            .append(response.text.length() / 1024).append("KB，")
                            .append(abbreviate(response.contentType, 40))
                            .append(response.text.contains("b_algo") ? "，含 b_algo" : "，无 b_algo")
                            .append("，开头：").append(sample).append("）；");
                    continue;
                }
                return formatHits(query, name, hits, count, needContent);
            } catch (Exception error) {
                String message = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                errors.append(name).append("：").append(abbreviate(message, 120)).append("；");
            }
        }
        return fail("免费搜索后端都没成功（" + abbreviate(errors.toString(), 400) + "）。"
                + "可以改用：web_fetch 打开 https://cn.bing.com/search?q=关键词 读结果页，"
                + "或直接 web_fetch 一个已知链接；也可以让用户给出具体网址。");
    }

    private static LocalTools.Result formatHits(String query, String backend,
                                                java.util.List<Hit> hits, int count,
                                                boolean needContent) {
        StringBuilder builder = new StringBuilder();
        builder.append("搜索：").append(query)
                .append("（后端 ").append(backend).append("，").append(hits.size()).append(" 条）\n\n");
        int limit = Math.min(count, hits.size());
        for (int i = 0; i < limit; i++) {
            Hit hit = hits.get(i);
            builder.append(i + 1).append(". ").append(blank(hit.title, "（无标题）")).append('\n');
            builder.append("   ").append(hit.url).append('\n');
            if (!hit.snippet.isEmpty()) {
                builder.append("   ").append(abbreviate(hit.snippet, 300)).append('\n');
            }
            builder.append('\n');
        }
        if (needContent) {
            builder.append("—— 正文摘要 ——\n\n");
            int fetched = 0;
            for (int i = 0; i < limit && fetched < 2; i++) {
                LocalTools.Result page = fetch(hits.get(i).url);
                if (page.isError) {
                    continue;
                }
                fetched++;
                builder.append("【").append(blank(hits.get(i).title, hits.get(i).url)).append("】\n")
                        .append(abbreviate(page.text.replace("错误：", ""), 2400))
                        .append("\n\n");
            }
        }
        builder.append("（要读正文就用 web_fetch 打开上面的链接）");
        return ok(builder.toString());
    }

    /**
     * Bing 的 RSS 版结果（`search?format=rss`）：结构稳定、体积小，不随结果页改版失效。
     * 只有 title / link / description 三个字段，够模型判断要不要点进去。
     */
    private static java.util.List<Hit> parseRss(String xml) {
        java.util.List<Hit> hits = new java.util.ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (xml == null) return hits;
        Matcher item = Pattern.compile("<item>([\\s\\S]*?)</item>").matcher(xml);
        while (item.find() && hits.size() < 30) {
            String chunk = item.group(1);
            String title = firstGroup(chunk, "<title>([\\s\\S]*?)</title>");
            String link = firstGroup(chunk, "<link>([\\s\\S]*?)</link>");
            String description = firstGroup(chunk, "<description>([\\s\\S]*?)</description>");
            title = stripTags(decodeEntities(cdata(title))).trim();
            link = cdata(link).trim();
            description = stripTags(decodeEntities(cdata(description))).trim();
            if (link.isEmpty() || !link.startsWith("http") || !seen.add(link)) {
                continue;
            }
            hits.add(new Hit(title, link, description));
        }
        // 个别实现把整个 feed 塞在一行里，退化成按 <item 切分
        if (hits.isEmpty() && xml.contains("<item")) {
            String[] parts = xml.split("<item");
            for (int i = 1; i < parts.length && hits.size() < 30; i++) {
                String chunk = parts[i];
                String title = stripTags(decodeEntities(cdata(
                        firstGroup(chunk, "<title>([\\s\\S]*?)</title>")))).trim();
                String link = cdata(firstGroup(chunk, "<link>([\\s\\S]*?)</link>")).trim();
                String description = stripTags(decodeEntities(cdata(
                        firstGroup(chunk, "<description>([\\s\\S]*?)</description>")))).trim();
                if (!link.startsWith("http") || !seen.add(link)) continue;
                hits.add(new Hit(title, link, description));
            }
        }
        return hits;
    }

    private static String firstGroup(String text, String pattern) {
        if (text == null) return "";
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String cdata(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.startsWith("<![CDATA[")) {
            trimmed = trimmed.substring(9);
            int end = trimmed.indexOf("]]>");
            if (end >= 0) trimmed = trimmed.substring(0, end);
        }
        return trimmed;
    }

    /** Bing 结果页：新老两套版式都认（<a><h2>…</h2></a> 与 <h2><a>…</a></h2>） */
    private static java.util.List<Hit> parseBingHtml(String html) {
        java.util.List<Hit> hits = new java.util.ArrayList<>();
        if (html == null) return hits;
        Set<String> seen = new LinkedHashSet<>();
        int index = 0;
        while (hits.size() < 20) {
            int start = html.indexOf("class=\"b_algo", index);
            if (start < 0) break;
            int end = Math.min(html.length(), start + 16000);
            String chunk = html.substring(start, end);
            index = start + 12;

            String url = "";
            String title = "";
            // 新版：<div class="b_algoheader"><a href="URL" …><h2>TITLE</h2></a></div>
            Matcher modern = Pattern.compile(
                    "b_algoheader\"><a[^>]*href=\"([^\"]+)\"[^>]*>\\s*<h2[^>]*>([\\s\\S]*?)</h2>")
                    .matcher(chunk);
            if (modern.find()) {
                url = modern.group(1);
                title = modern.group(2);
            } else {
                // 老版：<h2 …><a href="URL" …>TITLE</a></h2>
                Matcher classic = Pattern.compile(
                        "<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>").matcher(chunk);
                if (classic.find()) {
                    url = classic.group(1);
                    title = classic.group(2);
                } else {
                    // 再退一步：块里第一个指向站外的链接 + 第一个 h2 文本
                    Matcher loose = Pattern.compile(
                            "<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>").matcher(chunk);
                    String h2 = firstGroup(chunk, "<h2[^>]*>([\\s\\S]*?)</h2>");
                    if (loose.find() && !h2.isEmpty()) {
                        url = loose.group(1);
                        title = h2;
                    }
                }
            }
            String cleanUrlValue = cleanUrl(decodeEntities(url));
            if (cleanUrlValue.isEmpty() || !cleanUrlValue.startsWith("http")
                    || !seen.add(cleanUrlValue)) {
                continue;
            }
            String snippet = firstGroup(chunk, "<p[^>]*>([\\s\\S]*?)</p>");
            hits.add(new Hit(
                    stripTags(decodeEntities(title)).trim(),
                    cleanUrlValue,
                    stripTags(decodeEntities(snippet)).trim()));
        }
        return hits;
    }

    /** 百度结果页：<h3 class="c-title…">…</h3>，链接要么在它前面那个 <a>，要么在后面 */
    private static java.util.List<Hit> parseBaidu(String html) {
        java.util.List<Hit> hits = new java.util.ArrayList<>();
        if (html == null) return hits;
        Set<String> seen = new LinkedHashSet<>();
        Matcher heading = Pattern.compile(
                "<h3[^>]*class=\"[^\"]*c-title[^\"]*\"[^>]*>([\\s\\S]*?)</h3>").matcher(html);
        while (heading.find() && hits.size() < 20) {
            String title = stripTags(decodeEntities(heading.group(1))).trim();
            if (title.length() < 4) continue;
            // 链接在 h3 外层 <a> 上：先往前找最近一个 href，再往后兜一次
            int from = Math.max(0, heading.start() - 8000);
            String before = html.substring(from, heading.start());
            String url = lastHref(before);
            if (url.isEmpty()) {
                String after = html.substring(heading.end(),
                        Math.min(html.length(), heading.end() + 1500));
                url = firstHref(after);
            }
            url = cleanUrl(url);
            if (url.isEmpty() || !url.startsWith("http") || isBaiduInternal(url) || !seen.add(url)) {
                continue;
            }
            String after = html.substring(heading.end(),
                    Math.min(html.length(), heading.end() + 2000));
            String snippet = stripTags(decodeEntities(after)).trim();
            if (snippet.length() > 220) snippet = snippet.substring(0, 220);
            hits.add(new Hit(title, url, snippet));
        }
        return hits;
    }

    private static boolean isBaiduInternal(String url) {
        String lower = url.toLowerCase();
        // baidu.com/link?url=… 是跳转链接，可以跟随；这里只挡真正没用的站内页
        return (lower.contains("baidu.com/s?") || lower.contains("baidu.com/?")
                || lower.contains("www.baidu.com/home"));
    }

    private static String lastHref(String text) {
        Matcher matcher = Pattern.compile("href=\"(https?://[^\"]+)\"").matcher(text);
        String last = "";
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    private static String firstHref(String text) {
        Matcher matcher = Pattern.compile("href=\"(https?://[^\"]+)\"").matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** DuckDuckGo HTML 版 */
    private static java.util.List<Hit> parseDdg(String html) {
        java.util.List<Hit> hits = new java.util.ArrayList<>();
        if (html == null) return hits;
        Set<String> seen = new LinkedHashSet<>();
        Matcher link = Pattern.compile(
                "class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>").matcher(html);
        java.util.List<String> snippets = new java.util.ArrayList<>();
        Matcher snippet = Pattern.compile(
                "class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>").matcher(html);
        while (snippet.find()) {
            snippets.add(stripTags(decodeEntities(snippet.group(1))).trim());
        }
        int index = 0;
        while (link.find() && hits.size() < 20) {
            String url = cleanUrl(decodeEntities(link.group(1)));
            String title = stripTags(decodeEntities(link.group(2))).trim();
            if (url.isEmpty() || !url.startsWith("http") || !seen.add(url)) continue;
            String text = index < snippets.size() ? snippets.get(index) : "";
            index++;
            hits.add(new Hit(title, url, text));
        }
        return hits;
    }


    /** 去掉 Bing 的跳转包装、跟踪参数 */
    private static String cleanUrl(String raw) {
        String url = raw.trim();
        if (url.startsWith("//")) {
            url = "https:" + url;
        }
        if (url.contains("bing.com/ck/a")) {
            Matcher inner = Pattern.compile("[?&]u=a1([^&]+)").matcher(url);
            if (inner.find()) {
                try {
                    String decoded = java.net.URLDecoder.decode(inner.group(1), "UTF-8");
                    byte[] bytes = android.util.Base64.decode(decoded.replace('-', '+').replace('_', '/'),
                            android.util.Base64.DEFAULT);
                    String real = new String(bytes, StandardCharsets.UTF_8);
                    if (real.startsWith("http")) {
                        url = real;
                    }
                } catch (Throwable ignored) {
                    // 解不开就用原链接
                }
            }
        }
        if (url.contains("uddg=")) {
            Matcher inner = Pattern.compile("[?&]uddg=([^&]+)").matcher(url);
            if (inner.find()) {
                try {
                    String real = java.net.URLDecoder.decode(inner.group(1), "UTF-8");
                    if (real.startsWith("http")) {
                        url = real;
                    }
                } catch (Throwable ignored) {
                    // 同上
                }
            }
        }
        return url;
    }

    // ---------- HTTP ----------

    private static final class Response {
        String text = "";
        String title = "";
        String contentType = "";
        String finalUrl = "";
    }

    private static Response get(String url) throws IOException {
        Response response = new Response();
        String current = url;
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(40000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("HTTP " + status + " 没有 Location");
                }
                current = location.startsWith("http") ? location : new URL(new URL(current), location).toString();
                String guard = guardUrl(current);
                if (guard != null) {
                    throw new IOException(guard);
                }
                continue;
            }
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                throw new IOException("HTTP " + status + "：没有响应体");
            }
            String encoding = connection.getContentEncoding();
            if (encoding != null && encoding.toLowerCase().contains("gzip")) {
                stream = new GZIPInputStream(stream);
            }
            byte[] bytes = readBytes(stream, MAX_BYTES);
            response.contentType = connection.getContentType() == null ? "" : connection.getContentType();
            response.finalUrl = connection.getURL().toString();
            response.text = decode(bytes, response.contentType);
            connection.disconnect();
            if (status >= 400) {
                throw new IOException("HTTP " + status + "：" + abbreviate(stripTags(response.text), 200));
            }
            if (response.contentType.contains("html") || looksLikeHtml(response.text)) {
                Matcher title = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>",
                        Pattern.CASE_INSENSITIVE).matcher(response.text);
                if (title.find()) {
                    response.title = stripTags(decodeEntities(title.group(1))).trim();
                }
            }
            return response;
        }
        throw new IOException("重定向层数过多");
    }

    private static JSONObject postJson(String url, String apiKey, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("User-Agent", "ChatDemoAgent/0.3.0");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = readAll(stream);
        connection.disconnect();
        if (status >= 400) {
            throw new IOException("HTTP " + status + (text.isEmpty() ? "" : "：" + abbreviate(text, 300)));
        }
        return new JSONObject(text);
    }

    private static String normalizeOrigin(String apiOrigin) {
        String origin = apiOrigin == null || apiOrigin.isEmpty()
                ? "https://api.deepseek.com" : apiOrigin;
        while (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length() - 1);
        }
        return origin;
    }

    private static byte[] readBytes(InputStream stream, int limit) throws IOException {
        if (stream == null) {
            return new byte[0];
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    out.write(buffer, 0, Math.max(0, read - (total - limit)));
                    break;
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        byte[] bytes = readBytes(stream, MAX_BYTES);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 先按 Content-Type 的 charset，再看 <meta charset>，最后按 UTF-8 */
    private static String decode(byte[] bytes, String contentType) {
        String charset = null;
        Matcher header = Pattern.compile("charset=([\\w\\-]+)", Pattern.CASE_INSENSITIVE)
                .matcher(contentType == null ? "" : contentType);
        if (header.find()) {
            charset = header.group(1);
        }
        if (charset == null) {
            String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
            Matcher meta = Pattern.compile("<meta[^>]+charset=[\"']?([\\w\\-]+)",
                    Pattern.CASE_INSENSITIVE).matcher(head);
            if (meta.find()) {
                charset = meta.group(1);
            }
        }
        try {
            return new String(bytes, charset == null ? "UTF-8" : charset);
        } catch (Exception ignored) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    // ---------- HTML → 文本 ----------

    private static boolean looksLikeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String head = text.substring(0, Math.min(text.length(), 600)).toLowerCase();
        return head.contains("<html") || head.contains("<!doctype html") || head.contains("<body")
                || head.contains("<div") || head.contains("<p>");
    }

    static String htmlToText(String html) {
        String text = html;
        text = text.replaceAll("(?is)<script[^>]*>[\\s\\S]*?</script>", " ");
        text = text.replaceAll("(?is)<style[^>]*>[\\s\\S]*?</style>", " ");
        text = text.replaceAll("(?is)<noscript[^>]*>[\\s\\S]*?</noscript>", " ");
        text = text.replaceAll("(?is)<svg[^>]*>[\\s\\S]*?</svg>", " ");
        text = text.replaceAll("(?is)<head[^>]*>[\\s\\S]*?</head>", " ");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</(p|div|li|tr|h[1-6]|section|article|blockquote|pre)>", "\n");
        text = text.replaceAll("(?i)<li[^>]*>", "\n- ");
        text = text.replaceAll("(?s)<[^>]+>", " ");
        text = decodeEntities(text);
        text = text.replace('\u00a0', ' ');
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        text = text.replaceAll(" *\\n *", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    private static String stripTags(String text) {
        return text == null ? "" : text.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String decodeEntities(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        String value = text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&hellip;", "…")
                .replace("&ldquo;", "“")
                .replace("&rdquo;", "”");
        Matcher hex = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (hex.find()) {
            try {
                hex.appendReplacement(buffer, Matcher.quoteReplacement(
                        new String(Character.toChars(Integer.parseInt(hex.group(1), 16)))));
            } catch (Exception ignored) {
                hex.appendReplacement(buffer, "");
            }
        }
        hex.appendTail(buffer);
        value = buffer.toString();
        Matcher dec = Pattern.compile("&#(\\d+);").matcher(value);
        buffer = new StringBuffer();
        while (dec.find()) {
            try {
                dec.appendReplacement(buffer, Matcher.quoteReplacement(
                        new String(Character.toChars(Integer.parseInt(dec.group(1))))));
            } catch (Exception ignored) {
                dec.appendReplacement(buffer, "");
            }
        }
        dec.appendTail(buffer);
        return buffer.toString();
    }

    // ---------- 小工具 ----------

    private static String pretty(String json) {
        try {
            return new JSONObject(json).toString(2);
        } catch (Exception ignored) {
            return json;
        }
    }

    private static String blank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String abbreviate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    private static JSONObject tool(String name, String description, JSONObject schema) {
        JSONObject tool = new JSONObject();
        try {
            tool.put("name", name);
            tool.put("description", description);
            tool.put("input_schema", schema);
        } catch (Exception ignored) {
            // 不会发生
        }
        return tool;
    }

    private static JSONObject schema(JSONObject properties, String... required) {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            schema.put("properties", properties);
            JSONArray array = new JSONArray();
            for (String item : required) {
                array.put(item);
            }
            if (array.length() > 0) {
                schema.put("required", array);
            }
        } catch (Exception ignored) {
            // 不会发生
        }
        return schema;
    }

    private static JSONObject props(Object... pairs) {
        JSONObject properties = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                properties.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
        } catch (Exception ignored) {
            // 不会发生
        }
        return properties;
    }

    private static JSONObject p(String type, String description) {
        JSONObject property = new JSONObject();
        try {
            property.put("type", type);
            property.put("description", description);
        } catch (Exception ignored) {
            // 不会发生
        }
        return property;
    }

    private static LocalTools.Result ok(String text) {
        String value = text == null ? "" : text;
        if (value.length() > MAX_TEXT) {
            value = value.substring(0, MAX_TEXT) + "\n…（已截断）";
        }
        return new LocalTools.Result(value, false);
    }

    private static LocalTools.Result fail(String message) {
        return new LocalTools.Result("错误：" + message, true);
    }
}
