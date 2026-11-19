package com.audiocpp.ttsdemo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ModelRepository {
    static final String REPO = "audio-cpp/audio.cpp-gguf";
    private static final String HF_TREE = "/api/models/" + REPO + "/tree/main";
    private static final String MS_REPO = "/api/v1/models/" + REPO + "/repo";
    private static final Pattern NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?");
    private static final Pattern RATE_RESET = Pattern.compile("(?:^|;)\\s*t=(\\d+)");
    private static final int CATALOG_LIMIT = 8 * 1024 * 1024;
    private static final int BUFFER_SIZE = 256 * 1024;

    enum Source {
        HF("Hugging Face", "https://huggingface.co"),
        HF_MIRROR("Hugging Face 镜像", "https://hf-mirror.com"),
        MODELSCOPE("ModelScope 魔搭", "https://modelscope.cn");

        final String label;
        final String base;

        Source(String label, String base) {
            this.label = label;
            this.base = base;
        }

        @Override public String toString() { return label; }
    }

    record Entry(String path, long bytes, String sha256) {
        String name() { return path.substring(path.lastIndexOf('/') + 1); }

        int order() {
            return switch (name()) {
                case "moss-tts-nano-100m-q8_0.gguf" -> 0;
                case "moss-transcribe-diarize-q4_k.gguf" -> 1;
                default -> 2;
            };
        }
    }

    interface Progress {
        void update(long copied, long total);
    }

    static final class RateLimited extends IOException {
        final long retrySeconds;

        RateLimited(long retrySeconds) {
            super("模型源请求过于频繁（HTTP 429）");
            this.retrySeconds = retrySeconds;
        }
    }

    static List<Entry> fetchCatalog(Source source) throws IOException, JSONException {
        List<Entry> entries = new ArrayList<>();
        if (source == Source.MODELSCOPE) {
            URL page = new URL(source.base + MS_REPO + "/files?Revision=master&Recursive=true");
            HttpURLConnection connection = open(page, "application/json");
            try {
                JSONObject result = new JSONObject(readCatalog(connection));
                if (result.optInt("Code") != 200) throw new IOException("魔搭未返回模型列表");
                JSONArray items = result.getJSONObject("Data").getJSONArray("Files");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if ("blob".equals(item.optString("Type"))) {
                        add(entries, item.optString("Path"), item.optLong("Size"), item.optString("Sha256"));
                    }
                }
            } finally {
                connection.disconnect();
            }
        } else {
            URL page = new URL(source.base + HF_TREE + "?recursive=true&expand=false");
            for (int count = 0; page != null; count++) {
                if (count == 24) throw new IOException("模型列表分页过多");
                HttpURLConnection connection = open(page, "application/json");
                try {
                    JSONArray items = new JSONArray(readCatalog(connection));
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        if (!"file".equals(item.optString("type"))) continue;
                        JSONObject lfs = item.optJSONObject("lfs");
                        add(entries, item.optString("path"), lfs == null ? item.optLong("size")
                                : lfs.optLong("size"), lfs == null ? "" : lfs.optString("oid"));
                    }
                    String link = connection.getHeaderField("Link");
                    Matcher next = NEXT.matcher(link == null ? "" : link);
                    if (next.find()) {
                        URL previous = connection.getURL();
                        page = new URL(previous, next.group(1));
                        if (!page.getHost().equalsIgnoreCase(previous.getHost())
                                || !page.getPath().equals(HF_TREE)) throw new IOException("无效的模型列表分页地址");
                    } else {
                        page = null;
                    }
                } finally {
                    connection.disconnect();
                }
            }
        }
        entries.sort(Comparator.comparingInt(Entry::order)
                .thenComparing(Entry::path, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private static void add(List<Entry> entries, String path, long bytes, String hash) {
        if (bytes <= 0 || !ModelLibrary.hasExtension(path) || path.indexOf('\\') >= 0) return;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.getBytes(StandardCharsets.UTF_8).length > 255
                    || segment.codePoints().anyMatch(c -> Character.isISOControl(c) || c > 0xffff)) return;
        }
        entries.add(new Entry(path, bytes, hash.matches("(?i)[a-f0-9]{64}")
                ? hash.toLowerCase(Locale.ROOT) : ""));
    }

    static void download(Source source, Entry entry, File target, BooleanSupplier stopped, Progress progress)
            throws IOException {
        checkCancelled(stopped);
        byte[] header = new byte[24];
        if (target.isFile() && target.length() == entry.bytes()) {
            try (InputStream input = new FileInputStream(target)) {
                if (ModelLibrary.readHeader(input, header)) return;
            }
        }
        long available = target.getParentFile().getUsableSpace();
        if (available < entry.bytes()) throw new IOException("剩余空间不足，无法保存模型");
        HttpURLConnection connection = open(downloadUrl(source, entry), "application/octet-stream");
        File partial = null;
        try (InputStream input = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE)) {
            long length = contentLength(connection);
            if (length >= 0 && length != entry.bytes()) throw new IOException("下载大小与目录不符，请刷新列表");
            checkCancelled(stopped);
            if (!ModelLibrary.readHeader(input, header)) throw new IOException("下载内容不是 GGUF 模型");
            partial = File.createTempFile("gguf-", ".part", target.getParentFile());
            MessageDigest digest = entry.sha256().isEmpty() ? null : sha256();
            long written = header.length;
            try (FileOutputStream output = new FileOutputStream(partial)) {
                output.write(header);
                if (digest != null) digest.update(header);
                byte[] buffer = new byte[BUFFER_SIZE];
                long lastUpdate = 0;
                while (true) {
                    checkCancelled(stopped);
                    int count = input.read(buffer);
                    if (count < 0) break;
                    written += count;
                    if (written > entry.bytes()) throw new IOException("下载大小超过目录所列大小");
                    output.write(buffer, 0, count);
                    if (digest != null) digest.update(buffer, 0, count);
                    long now = System.nanoTime();
                    if (now - lastUpdate > 250_000_000L) {
                        progress.update(written, entry.bytes());
                        lastUpdate = now;
                    }
                }
                if (written != entry.bytes()) throw new IOException("模型下载不完整，请重试");
                if (digest != null && !entry.sha256().equals(hex(digest.digest()))) {
                    throw new IOException("模型 SHA-256 校验失败，请重试或切换下载源");
                }
                output.getFD().sync();
            }
            checkCancelled(stopped);
            if (!partial.renameTo(target)) throw new IOException("无法保存已下载的模型");
            progress.update(written, entry.bytes());
        } finally {
            connection.disconnect();
            if (partial != null && partial.exists()) partial.delete();
        }
    }

    private static long contentLength(HttpURLConnection connection) throws IOException {
        String header = connection.getHeaderField("Content-Length");
        if (header == null) return -1;
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException invalid) {
            throw new IOException("模型源返回的文件大小无效", invalid);
        }
    }

    private static URL downloadUrl(Source source, Entry entry) throws IOException {
        if (source == Source.MODELSCOPE) {
            return new URL(source.base + MS_REPO + "?Revision=master&FilePath=" + encode(entry.path()));
        }
        StringBuilder path = new StringBuilder();
        for (String segment : entry.path().split("/")) path.append('/').append(encode(segment));
        return new URL(source.base + "/" + REPO + "/resolve/main" + path);
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static HttpURLConnection open(URL start, String accept) throws IOException {
        URL url = start;
        for (int redirects = 0; redirects < 10; redirects++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) {
                throw new IOException("模型源返回了不安全的地址");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "audio.cpp-android-demo/1.0");
            boolean keepOpen = false;
            try {
                int code = connection.getResponseCode();
                if (code == 200) {
                    keepOpen = true;
                    return connection;
                }
                if (code == 429) throw new RateLimited(retryDelaySeconds(
                        connection.getHeaderField("Retry-After"), connection.getHeaderField("RateLimit"),
                        System.currentTimeMillis()));
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
                    throw new IOException("模型源返回 HTTP " + code);
                }
                String location = connection.getHeaderField("Location");
                if (location == null || location.isEmpty()) throw new IOException("模型源重定向缺少地址");
                url = new URL(url, location);
            } finally {
                if (!keepOpen) connection.disconnect();
            }
        }
        throw new IOException("模型源重定向过多");
    }

    static long retryDelaySeconds(String retryAfter, String rateLimit, long now) {
        long delay = -1;
        if (retryAfter != null) {
            String value = retryAfter.trim();
            delay = decimalSeconds(value);
            if (delay < 0) {
                SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
                format.setLenient(false);
                format.setTimeZone(TimeZone.getTimeZone("GMT"));
                ParsePosition parsed = new ParsePosition(0);
                Date retryTime = format.parse(value, parsed);
                if (retryTime != null && parsed.getIndex() == value.length()) {
                    delay = Math.max(0, (retryTime.getTime() - now + 999) / 1000);
                }
            }
        }
        if (delay < 0 && rateLimit != null) {
            Matcher reset = RATE_RESET.matcher(rateLimit);
            if (reset.find()) delay = decimalSeconds(reset.group(1));
        }
        return delay < 0 ? 60 : Math.min(86_400, Math.max(1, delay));
    }

    private static long decimalSeconds(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static String readCatalog(HttpURLConnection connection) throws IOException {
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                if (output.size() + count > CATALOG_LIMIT) throw new IOException("模型列表过大");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void checkCancelled(BooleanSupplier stopped) {
        if (stopped.getAsBoolean()) throw new CancellationException();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hex(byte[] data) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(data.length * 2);
        for (byte b : data) result.append(digits[(b & 0xff) >> 4]).append(digits[b & 0xf]);
        return result.toString();
    }
}
