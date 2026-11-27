package io.github.spwncm.ncmhelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 网易云音乐客户端（实验性）：二维码登录、搜索、歌单、我喜欢的音乐、歌曲下载。
 *
 * 仅使用公开接口，受网易云服务条款与 DRM 限制：VIP/付费歌曲无法获取下载地址，不能绕过。
 * Cookie 仅保存在插件数据目录，不上传。
 */
public final class NeteaseClient {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String LOGIN_URL = "https://music.163.com/login?codekey=";

    /** 歌曲信息 */
    public static final class Song {
        public long id;
        public String name = "";
        public String album = "";
        public final List<String> artists = new ArrayList<>();
        public long fee;
        public long duration;

        public String artistText() {
            return String.join("/", artists);
        }
    }

    /** 二维码轮询结果 */
    public static final class QrStatus {
        public int code;
        public String message = "";
        public boolean success;
    }

    private final Path dataDir;
    private final Path cookieFile;
    private volatile String cookie = "";
    private volatile String nickname = "";
    private volatile long uid = 0L;
    private volatile Consumer<String> logger = s -> {
    };

    public NeteaseClient(Path dataDir) {
        this.dataDir = dataDir;
        this.cookieFile = dataDir.resolve("netease-cookie.txt");
        loadCookie();
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {
        } : logger;
    }

    public boolean isLoggedIn() {
        return cookie.contains("MUSIC_U=");
    }

    public String nickname() {
        return nickname;
    }

    public long uid() {
        return uid;
    }

    public Path dataDir() {
        return dataDir;
    }

    // ---------------- 二维码登录 ----------------
    /** 申请登录二维码 key */
    public String requestQrKey() throws IOException {
        Map<String, Object> d = getJson("https://music.163.com/api/login/qrcode/unikey?type=1", false);
        String key = Json.str(d, "unikey", "");
        if (key.isEmpty()) throw new IOException("申请登录二维码失败: " + Json.lng(d, "code", 0));
        return key;
    }

    /** 生成二维码图片（内容为网易云登录链接），返回 PNG 路径 */
    public Path writeQrPng(String key, String fileName) throws IOException {
        Path out = dataDir.resolve(fileName);
        QrCode.writePng(LOGIN_URL + key, out, 420);
        return out;
    }

    /** 轮询扫码状态：801 等待扫码 / 802 待确认 / 803 成功 / 800 过期 */
    public QrStatus pollQr(String key) throws IOException {
        QrStatus st = new QrStatus();
        Resp r = get("https://music.163.com/api/login/qrcode/client/login?key="
                + enc(key) + "&type=1", false);
        Map<String, Object> d = Json.asMap(Json.parse(r.body));
        st.code = (int) Json.lng(d, "code", 0);
        st.message = Json.str(d, "message", "");
        if (st.code == 803) {
            String c = extractCookie(r.setCookies, "");
            if (c.isEmpty()) {
                // 部分版本把 cookie 放在响应体
                c = extractCookie(null, Json.str(d, "cookie", ""));
            }
            if (!c.isEmpty()) {
                cookie = c;
                saveCookie();
                st.success = true;
                refreshProfile();
            }
        }
        return st;
    }

    /** 获取账号信息（昵称 / uid） */
    public void refreshProfile() {
        try {
            Map<String, Object> d = getJson("https://music.163.com/api/nuser/account/get", true);
            Map<String, Object> account = Json.asMap(d.get("account"));
            Map<String, Object> profile = Json.asMap(account.get("profile"));
            uid = Json.lng(account, "id", 0);
            String nn = Json.str(profile, "nickname", "");
            if (!nn.isEmpty()) nickname = nn;
        } catch (Exception e) {
            log("获取账号信息失败: " + e.getMessage());
        }
    }

    public void logout() {
        cookie = "";
        nickname = "";
        uid = 0;
        try {
            Files.deleteIfExists(cookieFile);
        } catch (IOException ignored) {
        }
    }

    // ---------------- 搜索 / 歌单 / 喜欢 ----------------
    public List<Song> search(String keyword, int limit) throws IOException {
        Map<String, Object> d = getJson("https://music.163.com/api/search/get?s="
                + enc(keyword) + "&type=1&offset=0&limit=" + Math.max(1, Math.min(50, limit)), true);
        List<Object> songs = Json.asList(Json.asMap(d.get("result")).get("songs"));
        List<Song> out = new ArrayList<>();
        for (Object o : songs) out.add(parseSong(o));
        return out;
    }

    public List<Song> playlist(long playlistId) throws IOException {
        Map<String, Object> d = getJson(
                "https://music.163.com/api/playlist/detail?id=" + playlistId, true);
        List<Object> tracks = Json.asList(Json.asMap(d.get("result")).get("tracks"));
        List<Song> out = new ArrayList<>();
        for (Object o : tracks) out.add(parseSong(o));
        return out;
    }

    /** 我喜欢的音乐：从账号歌单里找 specialType=5 的歌单，再拉取详情 */
    public List<Song> myLikes() throws IOException {
        ensureProfile();
        if (uid == 0) throw new IOException("未获取到账号信息，请先登录");
        Map<String, Object> d = getJson("https://music.163.com/api/user/playlist?uid=" + uid
                + "&limit=1000&offset=0", true);
        long likeId = 0;
        for (Object o : Json.asList(d.get("playlist"))) {
            Map<String, Object> p = Json.asMap(o);
            if (Json.lng(p, "specialType", 0) == 5) {
                likeId = Json.lng(p, "id", 0);
                break;
            }
        }
        if (likeId == 0) throw new IOException("未找到「我喜欢的音乐」歌单");
        return playlist(likeId);
    }

    /** 获取歌曲下载地址（无版权/VIP 限制时返回 null） */
    public String songUrl(long songId, int br) throws IOException {
        for (int bitrate : new int[]{br, 320000, 128000}) {
            Map<String, Object> d = getJson("https://music.163.com/api/song/enhance/player/url?ids=["
                    + songId + "]&br=" + bitrate, true);
            List<Object> data = Json.asList(d.get("data"));
            if (data.isEmpty()) continue;
            Map<String, Object> item = Json.asMap(data.get(0));
            String url = Json.str(item, "url", "");
            if (!url.isEmpty()) return url;
        }
        return null;
    }

    /** 点赞 / 取消点赞（需要登录） */
    public boolean setLike(long songId, boolean like) throws IOException {
        if (!isLoggedIn()) throw new IOException("请先登录");
        Resp r = post("https://music.163.com/api/song/like?trackId=" + songId
                + "&like=" + (like ? "true" : "false") + "&csrf_token=" + csrf(), "");
        Map<String, Object> d = Json.asMap(Json.parse(r.body));
        return Json.lng(d, "code", 0) == 200;
    }

    // ---------------- 下载 ----------------
    /** 流式下载歌曲到目标文件，返回字节数 */
    public long download(String url, Path dest) throws IOException {
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        HttpURLConnection conn = open(url, "GET", false);
        long total = 0;
        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
        } finally {
            conn.disconnect();
        }
        Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return total;
    }

    /** 根据下载链接推断扩展名 */
    public static String guessExt(String url) {
        String u = url;
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        String lower = u.toLowerCase();
        if (lower.endsWith(".flac")) return ".flac";
        if (lower.endsWith(".mp3")) return ".mp3";
        if (lower.endsWith(".m4a")) return ".m4a";
        return ".mp3";
    }

    // ---------------- HTTP ----------------
    private static final class Resp {
        String body = "";
        List<String> setCookies = new ArrayList<>();
    }

    private Map<String, Object> getJson(String url, boolean withCookie) throws IOException {
        Resp r = get(url, withCookie);
        return Json.asMap(Json.parse(r.body));
    }

    private Resp get(String url, boolean withCookie) throws IOException {
        HttpURLConnection conn = open(url, "GET", withCookie);
        Resp r = new Resp();
        r.setCookies = conn.getHeaderFields().getOrDefault("Set-Cookie", List.of());
        r.body = readAll(conn);
        conn.disconnect();
        return r;
    }

    private Resp post(String url, String body) throws IOException {
        HttpURLConnection conn = open(url, "POST", true);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        Resp r = new Resp();
        r.setCookies = conn.getHeaderFields().getOrDefault("Set-Cookie", List.of());
        r.body = readAll(conn);
        conn.disconnect();
        return r;
    }

    private HttpURLConnection open(String url, String method, boolean withCookie) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod(method);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", "https://music.163.com/");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (withCookie && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        return conn;
    }

    private static String readAll(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    // ---------------- 解析 ----------------
    private static Song parseSong(Object o) {
        Map<String, Object> m = Json.asMap(o);
        Song s = new Song();
        s.id = Json.lng(m, "id", 0);
        s.name = Json.str(m, "name", "");
        s.album = Json.str(Json.asMap(m.get("album")), "name", "");
        s.fee = Json.lng(m, "fee", 0);
        s.duration = Json.lng(m, "duration", 0);
        for (Object a : Json.asList(m.get("artists"))) {
            String n = Json.str(Json.asMap(a), "name", "");
            if (!n.isEmpty()) s.artists.add(n);
        }
        return s;
    }

    // ---------------- 工具 ----------------
    private void ensureProfile() {
        if (uid == 0 && isLoggedIn()) refreshProfile();
    }

    private String csrf() {
        for (String part : cookie.split(";")) {
            String p = part.strip();
            if (p.startsWith("__csrf=")) return p.substring("__csrf=".length());
        }
        return "";
    }

    private static String extractCookie(List<String> setCookies, String fallback) {
        StringBuilder sb = new StringBuilder();
        if (setCookies != null) {
            for (String raw : setCookies) {
                if (raw == null) continue;
                String pair = raw.split(";", 2)[0].strip();
                if (pair.startsWith("MUSIC_U=") || pair.startsWith("__csrf=")) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(pair);
                }
            }
        }
        if (sb.indexOf("MUSIC_U=") < 0 && fallback != null && !fallback.isEmpty()) {
            String c = fallback.strip();
            if (c.contains("MUSIC_U=")) return c;
        }
        return sb.toString();
    }

    private void loadCookie() {
        try {
            if (Files.isRegularFile(cookieFile)) {
                cookie = Files.readString(cookieFile, StandardCharsets.UTF_8).strip();
            }
        } catch (IOException ignored) {
        }
    }

    private void saveCookie() {
        try {
            Files.createDirectories(dataDir);
            Files.writeString(cookieFile, cookie + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private void log(String msg) {
        Log.info(msg);
        logger.accept(msg);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
