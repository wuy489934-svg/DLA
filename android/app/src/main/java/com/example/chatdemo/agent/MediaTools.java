package com.example.chatdemo.agent;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * 媒体类内置工具：图片、音视频、录屏、分享。
 *
 * 3.6.3 新增。起因：用户让 Agent「做一个数学演变动画，导出成微信能直接看的视频」，
 * 当时没有 ffmpeg、也没有录屏/转码封装工具，模型只能一圈圈绕（40 轮 43 步）。
 * 这里把「读媒体信息、抽帧看画面、剪一段、去掉音轨、录屏、压缩图片、分享文件」
 * 做成原生工具，一步能完成的事不再拆成多条 shell。
 *
 * 设计口径（和 DLA 其他工具一致）：能原生 API 就不拼 shell；需要特权的只有录屏
 * （screenrecord 是 uid 2000 的活），其余都是应用身份 + 公开目录。
 */
public final class MediaTools {

    private static final int MAX_TEXT = 6000;
    private static final int FRAME_MAX_SIDE = 1280;

    private MediaTools() {}

    public static boolean handles(String name) {
        return "phone_media".equals(name)
                || "phone_image".equals(name)
                || "phone_record".equals(name)
                || "phone_share".equals(name);
    }

    public static JSONArray specs() {
        JSONArray list = new JSONArray();
        list.put(tool(
                "phone_image",
                "图片的查看/缩放/压缩/旋转一步完成（原生 Bitmap，不需要 ImageMagick）。"
                        + "op=info 只看尺寸与体积；resize 按宽或高（只给一边按比例）；compress 按质量和格式；"
                        + "rotate 按角度。输出默认写到同目录，可指定 out。需要自己看效果时 return_image=true。",
                schema(props(
                        "op", p("string", "info / resize / compress / rotate"),
                        "path", p("string", "源图片绝对路径"),
                        "out", p("string", "输出路径，可省略"),
                        "width", p("number", "目标宽（resize 用，0=按比例）"),
                        "height", p("number", "目标高（resize 用，0=按比例）"),
                        "percent", p("number", "按百分比缩放，例如 50"),
                        "quality", p("number", "JPEG/WEBP 质量 1-100，默认 85"),
                        "format", p("string", "jpeg / png / webp，默认沿用源格式"),
                        "rotate", p("number", "旋转角度 90/180/270"),
                        "return_image", p("boolean", "把结果图回传给自己看，默认 false")
                ), "op", "path")
        ));
        list.put(tool(
                "phone_media",
                "音视频一步处理（原生 MediaExtractor/MediaMuxer，不需要 ffmpeg）。"
                        + "info 看时长/分辨率/码率/有没有音轨；frame 抽某一帧（图会回传给你看）；"
                        + "trim 无损剪一段（按毫秒，不重编码，很快）；mute 去掉音轨另存。"
                        + "微信直接能播放的就是 MP4/H.264+AAC，剪完不用再转码。",
                schema(props(
                        "op", p("string", "info / frame / trim / mute"),
                        "path", p("string", "源文件绝对路径（mp4 / m4a / mp3 等）"),
                        "out", p("string", "输出路径，可省略"),
                        "start_ms", p("number", "trim 起点（毫秒）"),
                        "end_ms", p("number", "trim 终点（毫秒），省略=到结尾"),
                        "at_ms", p("number", "frame 取哪一帧（毫秒），默认 0"),
                        "return_image", p("boolean", "frame 时把图回传，默认 true")
                ), "op", "path")
        ));
        list.put(tool(
                "phone_record",
                "录屏（走 Shizuku 的 screenrecord，不弹系统授权框）。"
                        + "op=start 开始录（seconds 默认 20，最大 180；bitrate_mbps 默认 8）；"
                        + "op=stop 停止并落盘（SIGINT 让 screenrecord 写完 MP4）；op=status 看当前状态。"
                        + "录的是整块屏幕：先 start，再切到要录的画面，最后 stop。没有音频。",
                schema(props(
                        "op", p("string", "start / stop / status"),
                        "seconds", p("number", "录制时长上限，默认 20，最大 180"),
                        "bitrate_mbps", p("number", "码率 Mbps，默认 8"),
                        "path", p("string", "输出路径，可省略（默认 /sdcard/Movies/DLA/rec-<时间>.mp4）")
                ), "op")
        ));
        list.put(tool(
                "phone_share",
                "把本机文件交给别的应用（分享面板）：微信、QQ、邮件等由用户点一下目标应用。"
                        + "文件会先复制到应用缓存再用 FileProvider 授权，不会暴露私有目录。",
                schema(props(
                        "path", p("string", "要分享的文件绝对路径"),
                        "mime", p("string", "MIME，可省略；mp4 默认 video/mp4，图片按扩展名"),
                        "text", p("string", "附带一段文字，可省略")
                ), "path")
        ));
        return list;
    }

    public static LocalTools.Result execute(Context context, String name, JSONObject args) {
        try {
            switch (name) {
                case "phone_image":
                    return image(args);
                case "phone_media":
                    return media(args);
                case "phone_record":
                    return record(args);
                case "phone_share":
                    return share(context, args);
                default:
                    return fail("未知媒体工具：" + name);
            }
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return fail(message);
        }
    }

    // ---------------- 图片 ----------------

    private static LocalTools.Result image(JSONObject args) throws Exception {
        String op = args.optString("op", "info").trim();
        String path = args.optString("path").trim();
        File source = new File(path);
        if (!source.isFile()) {
            return fail("文件不存在：" + path);
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int srcWidth = bounds.outWidth;
        int srcHeight = bounds.outHeight;
        if (srcWidth <= 0 || srcHeight <= 0) {
            return fail("不是可解码的图片：" + path);
        }
        if ("info".equals(op)) {
            return ok("图片：" + source.getName()
                    + "\n尺寸：" + srcWidth + "×" + srcHeight
                    + "\n体积：" + human(source.length())
                    + "\n格式：" + blank(bounds.outMimeType, "未知")
                    + "\n路径：" + source.getAbsolutePath());
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(srcWidth, srcHeight,
                args.optInt("width", 0), args.optInt("height", 0));
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap == null) {
            return fail("解码失败：" + path);
        }
        if ("rotate".equals(op)) {
            int degrees = args.optInt("rotate", 90);
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle();
                bitmap = rotated;
            }
        } else {
            int targetW = args.optInt("width", 0);
            int targetH = args.optInt("height", 0);
            int percent = args.optInt("percent", 0);
            long scaleW = 0;
            long scaleH = 0;
            if (percent > 0) {
                scaleW = Math.max(1, bitmap.getWidth() * percent / 100);
                scaleH = Math.max(1, bitmap.getHeight() * percent / 100);
            } else if (targetW > 0 && targetH > 0) {
                scaleW = targetW;
                scaleH = targetH;
            } else if (targetW > 0) {
                scaleW = targetW;
                scaleH = Math.max(1, (long) bitmap.getHeight() * targetW / bitmap.getWidth());
            } else if (targetH > 0) {
                scaleH = targetH;
                scaleW = Math.max(1, (long) bitmap.getWidth() * targetH / bitmap.getHeight());
            }
            if (scaleW > 0 && scaleH > 0
                    && (scaleW != bitmap.getWidth() || scaleH != bitmap.getHeight())) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, (int) scaleW, (int) scaleH, true);
                bitmap.recycle();
                bitmap = scaled;
            }
        }
        String format = args.optString("format", "").trim().toLowerCase(Locale.US);
        if (format.isEmpty()) {
            format = "png".equalsIgnoreCase(bounds.outMimeType == null ? "" : bounds.outMimeType.replace("image/", ""))
                    ? "png" : "jpeg";
        }
        File out = outputFile(args.optString("out"), source, "-" + op, format);
        int quality = Math.min(Math.max(args.optInt("quality", 85), 1), 100);
        try (FileOutputStream stream = new FileOutputStream(out)) {
            Bitmap.CompressFormat compress = "png".equals(format)
                    ? Bitmap.CompressFormat.PNG
                    : ("webp".equals(format) ? Bitmap.CompressFormat.WEBP : Bitmap.CompressFormat.JPEG);
            bitmap.compress(compress, "png".equals(format) ? 100 : quality, stream);
        }
        int outWidth = bitmap.getWidth();
        int outHeight = bitmap.getHeight();
        if (args.optBoolean("return_image", false)) {
            String base64 = jpegBase64(bitmap, 82);
            bitmap.recycle();
            return new LocalTools.Result(
                    "已生成：" + out.getAbsolutePath() + "\n" + outWidth + "×" + outHeight
                            + " · " + human(out.length()) + "（图已附上）",
                    false, base64, "image/jpeg");
        }
        bitmap.recycle();
        return ok("已生成：" + out.getAbsolutePath() + "\n" + outWidth + "×" + outHeight
                + " · " + human(out.length()));
    }

    private static int sampleSize(int width, int height, int targetW, int targetH) {
        int sample = 1;
        int maxTarget = Math.max(targetW, targetH);
        if (maxTarget <= 0) {
            // 只做压缩时也别一次解出 8000 万像素
            while ((long) width / sample * (height / sample) > 16_000_000L) {
                sample *= 2;
            }
            return sample;
        }
        while ((long) width / sample > maxTarget * 2L || (long) height / sample > maxTarget * 2L) {
            sample *= 2;
        }
        return sample;
    }

    // ---------------- 音视频 ----------------

    private static LocalTools.Result media(JSONObject args) throws Exception {
        String op = args.optString("op", "info").trim();
        String path = args.optString("path").trim();
        File source = new File(path);
        if (!source.isFile()) {
            return fail("文件不存在：" + path);
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(source.getAbsolutePath());
            long durationMs = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            String mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            if ("info".equals(op)) {
                StringBuilder builder = new StringBuilder();
                builder.append("文件：").append(source.getName()).append('\n');
                builder.append("时长：").append(durationMs).append(" ms\n");
                if (width != null && height != null) {
                    builder.append("画面：").append(width).append('×').append(height);
                    if (rotation != null && !"0".equals(rotation)) {
                        builder.append("（旋转 ").append(rotation).append("°）");
                    }
                    builder.append('\n');
                }
                if (bitrate != null) {
                    builder.append("码率：").append(Integer.parseInt(bitrate) / 1000).append(" kbps\n");
                }
                builder.append("音轨：").append(hasAudio(source) ? "有" : "无").append('\n');
                builder.append("体积：").append(human(source.length())).append('\n');
                builder.append("格式：").append(blank(mime, "未知")).append('\n');
                builder.append("路径：").append(source.getAbsolutePath());
                return ok(builder.toString());
            }
            if ("frame".equals(op)) {
                long atMs = Math.min(Math.max(args.optLong("at_ms", 0), 0), Math.max(durationMs, 0));
                Bitmap frame = retriever.getFrameAtTime(atMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) {
                    return fail("抽帧失败（这个时间点没有可解码帧）");
                }
                File out = outputFile(args.optString("out"), source,
                        "-frame-" + atMs, "jpeg");
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    frame.compress(Bitmap.CompressFormat.JPEG, 88, stream);
                }
                int outW = frame.getWidth();
                int outH = frame.getHeight();
                String base64 = null;
                if (args.optBoolean("return_image", true)) {
                    base64 = jpegBase64(frame, 82);
                }
                frame.recycle();
                return new LocalTools.Result(
                        "已抽帧 " + atMs + "ms → " + out.getAbsolutePath()
                                + "\n" + outW + "×" + outH + " · " + human(out.length()),
                        false, base64, base64 == null ? null : "image/jpeg");
            }
            if ("trim".equals(op) || "mute".equals(op)) {
                long startMs = Math.max(0, args.optLong("start_ms", 0));
                long endMs = args.optLong("end_ms", durationMs);
                if (endMs <= startMs) {
                    endMs = durationMs;
                }
                boolean videoOnly = "mute".equals(op);
                File out = outputFile(args.optString("out"), source,
                        videoOnly ? "-mute" : "-trim", "mp4");
                long written = copyTracks(source, out, startMs, endMs, videoOnly);
                if (written <= 0) {
                    out.delete();
                    return fail("没写出有效内容（检查起止时间是否落在有画面的区间）");
                }
                return ok("已生成：" + out.getAbsolutePath()
                        + "\n区间：" + startMs + " → " + endMs + " ms"
                        + "\n体积：" + human(out.length())
                        + (videoOnly ? "\n（已去掉音轨）" : "\n（无损剪切，未重编码）"));
            }
            return fail("未知 op：" + op);
        } finally {
            runCatching(() -> retriever.release());
        }
    }

    /** 无损复制轨道：MediaExtractor 读包 → MediaMuxer 写包，不重新编码 */
    private static long copyTracks(File source, File out, long startMs, long endMs, boolean videoOnly)
            throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int tracks = extractor.getTrackCount();
            int[] muxerTrack = new int[tracks];
            Arrays.fill(muxerTrack, -1);
            int videoTracks = 0;
            for (int i = 0; i < tracks; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    continue;
                }
                if (videoOnly && mime.startsWith("audio/")) {
                    continue;
                }
                if (mime.startsWith("video/")) {
                    videoTracks++;
                }
                muxerTrack[i] = muxer.addTrack(format);
            }
            if (muxerTrack.length == 0 || videoTracks == 0) {
                return 0;
            }
            muxer.start();
            long startUs = startMs * 1000L;
            long endUs = endMs * 1000L;
            ByteBuffer buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastWrittenUs = 0;
            long writtenBytes = 0;
            for (int i = 0; i < tracks; i++) {
                if (muxerTrack[i] < 0) {
                    continue;
                }
                extractor.selectTrack(i);
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                while (true) {
                    int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) {
                        break;
                    }
                    long timeUs = extractor.getSampleTime();
                    if (timeUs > endUs) {
                        break;
                    }
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = Math.max(0, timeUs - startUs);
                    info.flags = extractor.getSampleFlags();
                    muxer.writeSampleData(muxerTrack[i], buffer, info);
                    lastWrittenUs = Math.max(lastWrittenUs, info.presentationTimeUs);
                    writtenBytes += size;
                    extractor.advance();
                }
                extractor.unselectTrack(i);
            }
            return writtenBytes;
        } finally {
            if (muxer != null) {
                runCatching(muxer::stop);
                runCatching(muxer::release);
            }
            runCatching(extractor::release);
        }
    }

    private static boolean hasAudio(File file) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 读不了就按没有音轨处理
        } finally {
            runCatching(extractor::release);
        }
        return false;
    }

    // ---------------- 录屏 ----------------

    private static volatile Thread recordThread;
    private static volatile String recordPath;
    private static volatile long recordStartedAt;
    private static volatile boolean recording;

    private static LocalTools.Result record(JSONObject args) throws Exception {
        String op = args.optString("op", "status").trim();
        if (!ShizukuShell.granted()) {
            return fail("录屏需要 Shizuku：请到「设置 → 更多 → 手机能力」确认 Shizuku 已启动并授权。"
                    + "screenrecord 只有 shell(uid 2000) 身份才能跑。");
        }
        if ("start".equals(op)) {
            if (recording) {
                return fail("已经在录了：" + recordPath + "（先 stop 或等它自己结束）");
            }
            int seconds = Math.min(Math.max(args.optInt("seconds", 20), 1), 180);
            int bitrateMbps = Math.min(Math.max(args.optInt("bitrate_mbps", 8), 1), 40);
            String path = args.optString("path", "").trim();
            if (path.isEmpty()) {
                path = "/sdcard/Movies/DLA/rec-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                        + ".mp4";
            }
            final String target = path;
            ShizukuShell.exec("mkdir -p " + quote(parentOf(target)), 8000);
            String command = "screenrecord --time-limit " + seconds
                    + " --bit-rate " + (bitrateMbps * 1_000_000L)
                    + " " + quote(target);
            recordPath = target;
            recordStartedAt = System.currentTimeMillis();
            recording = true;
            Thread thread = new Thread(() -> {
                try {
                    ShizukuShell.exec(command, (seconds + 15) * 1000);
                } catch (Exception ignored) {
                    // 到时间或失败都会走到这里，文件状态由 stop/status 判断
                } finally {
                    recording = false;
                }
            }, "dla-record");
            thread.setDaemon(true);
            recordThread = thread;
            thread.start();
            return ok("已开始录屏（最多 " + seconds + " 秒，码率 " + bitrateMbps + " Mbps）\n"
                    + "输出：" + target + "\n"
                    + "录完用 phone_record op=stop 停止；期间可以用其他工具切画面。");
        }
        if ("stop".equals(op)) {
            if (!recording && recordPath == null) {
                return fail("当前没有在录屏");
            }
            ShizukuShell.exec("pkill -2 screenrecord || killall -2 screenrecord", 8000);
            Thread thread = recordThread;
            if (thread != null) {
                thread.join(5000);
            }
            recording = false;
            File file = recordPath == null ? null : new File(recordPath);
            if (file == null || !file.isFile() || file.length() == 0) {
                return fail("录屏文件没生成或为空：" + recordPath);
            }
            long tookMs = System.currentTimeMillis() - recordStartedAt;
            return ok("录屏已保存：" + file.getAbsolutePath()
                    + "\n时长约 " + (tookMs / 1000) + " 秒 · 体积 " + human(file.length())
                    + "\n需要剪短/去音轨可继续用 phone_media。");
        }
        File file = recordPath == null ? null : new File(recordPath);
        return ok("录制中：" + recording
                + "\n路径：" + (recordPath == null ? "（无）" : recordPath)
                + "\n已录 " + (recordStartedAt == 0 ? 0 : (System.currentTimeMillis() - recordStartedAt) / 1000) + " 秒"
                + "\n文件：" + (file != null && file.isFile() ? human(file.length()) : "尚未生成"));
    }

    // ---------------- 分享 ----------------

    private static LocalTools.Result share(Context context, JSONObject args) throws Exception {
        String path = args.optString("path", "").trim();
        File source = new File(path);
        if (!source.isFile()) {
            return fail("文件不存在：" + path);
        }
        File shareDir = new File(context.getCacheDir(), "attachments");
        if (!shareDir.exists() && !shareDir.mkdirs()) {
            return fail("无法创建分享缓存目录");
        }
        File copy = new File(shareDir, "share-" + System.currentTimeMillis() + "-" + source.getName());
        copyFile(source, copy);
        String authority = context.getPackageName() + ".fileprovider";
        Uri uri = FileProvider.getUriForFile(context, authority, copy);
        String mime = args.optString("mime", "").trim();
        if (mime.isEmpty()) {
            mime = guessMime(source.getName());
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        String text = args.optString("text", "").trim();
        if (!text.isEmpty()) {
            intent.putExtra(Intent.EXTRA_TEXT, text);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent chooser = Intent.createChooser(intent, "分享到");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
        return ok("已弹出分享面板（" + mime + "）：" + source.getName()
                + "\n让用户点一下目标应用（微信 / QQ / 邮件）即可；文件从缓存授权，不暴露私有目录。");
    }

    // ---------------- 公共小工具 ----------------

    private static File outputFile(String out, File source, String suffix, String format) {
        String extension = "jpeg".equals(format) || "jpg".equals(format) ? "jpg"
                : ("png".equals(format) ? "png" : ("webp".equals(format) ? "webp" : "mp4"));
        if (out != null && !out.trim().isEmpty()) {
            File target = new File(out.trim());
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            return target;
        }
        String base = source.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return new File(source.getParentFile(), base + suffix + "." + extension);
    }

    private static String guessMime(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".m4a") || lower.endsWith(".mp3") || lower.endsWith(".aac")) return "audio/*";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private static String jpegBase64(Bitmap bitmap, int quality) throws IOException {
        int maxSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
        Bitmap target = bitmap;
        if (maxSide > FRAME_MAX_SIDE) {
            float scale = FRAME_MAX_SIDE / (float) maxSide;
            target = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, (int) (bitmap.getWidth() * scale)),
                    Math.max(1, (int) (bitmap.getHeight() * scale)), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        target.compress(Bitmap.CompressFormat.JPEG, quality, out);
        if (target != bitmap) {
            target.recycle();
        }
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new java.io.FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "/sdcard";
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (Exception error) {
            return 0;
        }
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    private static String blank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static void runCatching(Action action) {
        try {
            action.run();
        } catch (Exception ignored) {
            // 清理失败不影响结果
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private static LocalTools.Result ok(String text) {
        return new LocalTools.Result(abbreviate(text), false);
    }

    private static LocalTools.Result fail(String text) {
        return new LocalTools.Result("错误：" + abbreviate(text), true);
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "\n…（已截断）";
    }

    // ---- spec 小工具（与 LocalTools 里的格式保持一致） ----

    private static JSONObject tool(String name, String description, JSONObject schema) {
        try {
            return new JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("input_schema", schema);
        } catch (Exception error) {
            return new JSONObject();
        }
    }

    private static JSONObject schema(JSONObject properties, String... required) {
        try {
            JSONObject schema = new JSONObject()
                    .put("type", "object")
                    .put("properties", properties);
            JSONArray list = new JSONArray();
            for (String name : required) {
                list.put(name);
            }
            schema.put("required", list);
            return schema;
        } catch (Exception error) {
            return new JSONObject();
        }
    }

    private static JSONObject props(Object... pairs) {
        JSONObject properties = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                properties.put((String) pairs[i], pairs[i + 1]);
            }
        } catch (Exception ignored) {
            // 不会发生
        }
        return properties;
    }

    private static JSONObject p(String type, String description) {
        try {
            return new JSONObject().put("type", type).put("description", description);
        } catch (Exception error) {
            return new JSONObject();
        }
    }
}
