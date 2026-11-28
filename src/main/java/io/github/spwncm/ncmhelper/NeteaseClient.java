package io.github.spwncm.ncmhelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
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
    /** eapi 加密密钥（网易云 PC 客户端公开协议常量） */
    private static final byte[] EAPI_KEY = "e82ckenh8dichen8".getBytes(StandardCharsets.US_ASCII);
    private static final String EAPI_DIVIDER = "-36cd479b6b5-";

    /** 歌曲信息 */
    public static final class Song {
        public long id;
        public String name = "";
        public String album = "";
        public final List<String> artists = new ArrayList<>();
        public long fee;
        public long duration;
        public String coverUrl = "";

        public String artistText() {
            return String.join("/", artists);
        }
    }

    /** 发现页卡片（歌单或歌曲） */
    public static final class Card {
        public long id;
        public String title = "";
        public String subtitle = "";
        public String coverUrl = "";
        public long duration;      // 毫秒（歌曲）
        public int trackCount;     // 曲目数（歌单）
        public long playCount;     // 播放量（歌单）
        public String kind = "playlist"; // playlist / song
    }

    /** 登录会话：eapi（新客户端协议）或 api（旧协议） */
    public static final class QrSession {
        public final String key;
        public final String protocol;
        public final int type;

        QrSession(String key, String protocol, int type) {
            this.key = key;
            this.protocol = protocol;
            this.type = type;
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
    private final java.util.Map<String, String> cookies = new java.util.concurrent.ConcurrentHashMap<>();
    private final CookieManager cookieManager;
    private volatile String cookie = "";
    private volatile String nickname = "";
    private volatile String avatarUrl = "";
    private volatile long uid = 0L;
    private volatile Consumer<String> logger = s -> {
    };

    public NeteaseClient(Path dataDir) {
        this.dataDir = dataDir;
        this.cookieFile = dataDir.resolve("netease-cookie.txt");
        // 进程级 CookieManager：自动处理重定向与多次 Set-Cookie，比手工解析响应头可靠
        CookieManager existing = null;
        CookieHandler def = CookieHandler.getDefault();
        if (def instanceof CookieManager cm) {
            existing = cm;
        }
        if (existing == null) {
            existing = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            CookieHandler.setDefault(existing);
        }
        this.cookieManager = existing;
        loadCookie();
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {
        } : logger;
    }

    public boolean isLoggedIn() {
        String v = cookies.get("MUSIC_U");
        return v != null && !v.isEmpty();
    }

    public String nickname() {
        return nickname;
    }

    public String avatarUrl() {
        return avatarUrl;
    }

    public long uid() {
        return uid;
    }

    public Path dataDir() {
        return dataDir;
    }

    // ---------------- 二维码登录 ----------------
    /** 申请登录二维码 key：优先 eapi（新客户端协议，带设备指纹），失败再回退旧 api */
    public QrSession requestQrKey() throws IOException {
        for (int type : new int[]{3, 1}) {
            try {
                Resp r = eapi("/eapi/login/qrcode/unikey", mapOf("type", type));
                Map<String, Object> d = Json.asMap(Json.parse(r.body));
                String key = Json.str(d, "unikey", "");
                if (!key.isEmpty()) {
                    Log.info("二维码来源: eapi (type=" + type + ")");
                    return new QrSession(key, "eapi", type);
                }
                Log.warn("eapi unikey 失败 type=" + type + " code=" + Json.lng(d, "code", 0)
                        + " body=" + abbreviate(r.body));
            } catch (Exception e) {
                Log.warn("eapi unikey 异常 type=" + type + ": " + e.getMessage());
            }
        }
        try {
            Map<String, Object> d = getJson("https://music.163.com/api/login/qrcode/unikey?type=1", false);
            String key = Json.str(d, "unikey", "");
            if (!key.isEmpty()) {
                Log.info("二维码来源: api (旧协议)");
                return new QrSession(key, "api", 1);
            }
            Log.warn("api unikey 失败 code=" + Json.lng(d, "code", 0) + " body=" + abbreviate(d.toString()));
        } catch (Exception e) {
            Log.warn("api unikey 异常: " + e.getMessage());
        }
        throw new IOException("申请登录二维码失败（eapi/api 均失败），可能被网易云风控，请稍后再试");
    }

    /** 生成二维码图片（内容为网易云登录链接），返回 PNG 路径 */
    public Path writeQrPng(String key, String fileName) throws IOException {
        Path out = dataDir.resolve(fileName);
        QrCode.writePng(LOGIN_URL + key, out, 420);
        return out;
    }

    /** 按会话协议轮询扫码状态 */
    public QrStatus pollQr(QrSession session) throws IOException {
        if ("eapi".equals(session.protocol)) {
            Resp r = eapi("/eapi/login/qrcode/client/login",
                    mapOf("key", session.key, "type", session.type));
            return handlePoll(r);
        }
        return pollQr(session.key);
    }

    /** 轮询扫码状态：801 等待扫码 / 802 待确认 / 803 成功 / 800 过期 */
    public QrStatus pollQr(String key) throws IOException {
        QrStatus st = new QrStatus();
        String pollUrl = "https://music.163.com/api/login/qrcode/client/login";
        String form = "key=" + enc(key) + "&type=1";
        // 官方实现的轮询是 POST；GET 在部分版本只返回状态、不下发登录 Cookie
        String path = pollUrl + "?" + form;
        Resp r = null;
        try {
            r = post("https://music.163.com/api/login/qrcode/client/login", "key=" + enc(key) + "&type=1");
        } catch (Exception e) {
            Log.warn("POST 轮询失败，回退 GET: " + e.getMessage());
        }
        if (r == null || r.body == null || r.body.isBlank()) {
            r = get(path, false);
        }
        Map<String, Object> d = Json.asMap(Json.parse(r.body));
        st.code = (int) Json.lng(d, "code", 0);
        st.message = Json.str(d, "message", "");
        if (st.code == 803) {
            // cookie 可能在多次 Set-Cookie 里，也可能在响应体 cookie 字段
            absorbCookies(r.setCookies);
            absorbCookieString(Json.str(d, "cookie", ""));
            if (!isLoggedIn()) {
                // 个别版本第二次轮询才下发 cookie
                sleepMs(800);
                absorbCookies(get(path, false).setCookies);
            }
            syncFromStore();
            if (isLoggedIn()) {
                saveCookie();
                st.success = true;
                Log.info("登录成功，已保存 Cookie: " + cookies.keySet());
                refreshProfile();
            } else {
                Log.warn("扫码已确认，但未拿到登录 Cookie（响应头数=" + r.setCookies.size()
                        + "，CookieStore=" + cookieManager.getCookieStore().getCookies().size()
                        + "，响应体=" + abbreviate(r.body) + "）");
            }
        }
        return st;
    }

    /** 获取账号信息（昵称 / uid），返回是否成功 */
    public boolean refreshProfile() {
        try {
            Map<String, Object> d = getJson("https://music.163.com/api/nuser/account/get", true);
            Map<String, Object> account = Json.asMap(d.get("account"));
            // 注意：实际返回的 profile 在顶层，不在 account 内
            Map<String, Object> profile = Json.asMap(d.get("profile"));
            if (profile.isEmpty()) profile = Json.asMap(account.get("profile"));
            uid = Json.lng(account, "id", Json.lng(profile, "userId", 0));
            String nn = Json.str(profile, "nickname", "");
            if (!nn.isEmpty()) nickname = nn;
            String avatar = Json.str(profile, "avatarUrl", "");
            if (avatar.isEmpty()) avatar = Json.str(profile, "avatarurl", "");
            if (!avatar.isEmpty()) avatarUrl = avatar;
            return uid != 0;
        } catch (Exception e) {
            log("获取账号信息失败: " + e.getMessage());
            return false;
        }
    }

    /** 用已有 Cookie 登录（支持粘贴完整 Cookie 串或仅 MUSIC_U 值） */
    public boolean loginWithCookie(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String text = raw.strip();
        if (!text.contains("=")) text = "MUSIC_U=" + text;
        absorbCookieString(text);
        if (!isLoggedIn()) return false;
        saveCookie();
        syncFromStore();
        boolean verified = refreshProfile();
        if (verified) {
            Log.info("使用 Cookie 登录成功" + (nickname.isEmpty() ? "" : ": " + nickname)
                    + "（uid=" + uid + "）");
        } else {
            Log.warn("Cookie 已保存但账号校验失败（Cookie 过期或网络异常）");
        }
        return verified;
    }

    public void logout() {
        cookies.clear();
        cookie = "";
        nickname = "";
        uid = 0;
        for (HttpCookie c : new ArrayList<>(cookieManager.getCookieStore().getCookies())) {
            cookieManager.getCookieStore().remove(null, c);
        }
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

    // ---------------- 发现页数据 ----------------
    /** 推荐歌单 */
    public java.util.List<Card> recommendPlaylists(int limit) throws IOException {
        Map<String, Object> d = getJson("https://music.163.com/api/personalized/playlist?limit="
                + Math.max(1, Math.min(50, limit)), true);
        java.util.List<Card> out = new java.util.ArrayList<>();
        for (Object o : Json.asList(d.get("result"))) {
            Map<String, Object> m = Json.asMap(o);
            Card c = new Card();
            c.id = Json.lng(m, "id", 0);
            c.title = Json.str(m, "name", "");
            c.coverUrl = Json.str(m, "picUrl", "");
            c.trackCount = (int) Json.lng(m, "trackCount", 0);
            c.playCount = Json.lng(m, "playCount", 0);
            c.subtitle = c.trackCount + " 首";
            c.kind = "playlist";
            out.add(c);
        }
        return out;
    }

    /** 推荐新歌 */
    public java.util.List<Card> newSongs(int limit) throws IOException {
        Map<String, Object> d = getJson("https://music.163.com/api/personalized/newsong?limit="
                + Math.max(1, Math.min(50, limit)), true);
        java.util.List<Card> out = new java.util.ArrayList<>();
        for (Object o : Json.asList(d.get("result"))) {
            Map<String, Object> m = Json.asMap(o);
            Map<String, Object> song = Json.asMap(m.get("song"));
            Card c = new Card();
            c.id = Json.lng(song, "id", Json.lng(m, "id", 0));
            c.title = Json.str(song, "name", Json.str(m, "name", ""));
            c.duration = Json.lng(song, "duration", 0);
            Map<String, Object> album = Json.asMap(song.get("album"));
            c.coverUrl = Json.str(album, "picUrl", Json.str(m, "picUrl", ""));
            StringBuilder artists = new StringBuilder();
            for (Object a : Json.asList(song.get("artists"))) {
                String n = Json.str(Json.asMap(a), "name", "");
                if (!n.isEmpty()) {
                    if (artists.length() > 0) artists.append(" / ");
                    artists.append(n);
                }
            }
            c.subtitle = artists.toString();
            c.kind = "song";
            out.add(c);
        }
        return out;
    }

    /** 我的歌单 */
    public java.util.List<Card> myPlaylists() throws IOException {
        ensureProfile();
        if (uid == 0) throw new IOException("未获取到账号信息，请先登录");
        Map<String, Object> d = getJson("https://music.163.com/api/user/playlist?uid=" + uid
                + "&limit=1000&offset=0", true);
        java.util.List<Card> out = new java.util.ArrayList<>();
        for (Object o : Json.asList(d.get("playlist"))) {
            Map<String, Object> m = Json.asMap(o);
            Card c = new Card();
            c.id = Json.lng(m, "id", 0);
            c.title = Json.str(m, "name", "");
            c.coverUrl = Json.str(m, "coverImgUrl", Json.str(m, "picUrl", ""));
            c.trackCount = (int) Json.lng(m, "trackCount", 0);
            c.subtitle = c.trackCount + " 首";
            c.kind = "playlist";
            out.add(c);
        }
        return out;
    }

    /** 每日推荐歌曲 */
    public java.util.List<Card> dailySongs() throws IOException {
        Map<String, Object> d = getJson("https://music.163.com/api/v3/discovery/recommend/songs?limit=20", true);
        Map<String, Object> data = Json.asMap(d.get("data"));
        java.util.List<Card> out = new java.util.ArrayList<>();
        for (Object o : Json.asList(data.get("dailySongs"))) {
            Map<String, Object> song = Json.asMap(o);
            Card c = new Card();
            c.id = Json.lng(song, "id", 0);
            c.title = Json.str(song, "name", "");
            c.duration = Json.lng(song, "duration", 0);
            Map<String, Object> album = Json.asMap(song.get("al"));
            c.coverUrl = Json.str(album, "picUrl", "");
            StringBuilder artists = new StringBuilder();
            for (Object a : Json.asList(song.get("ar"))) {
                String n = Json.str(Json.asMap(a), "name", "");
                if (!n.isEmpty()) {
                    if (artists.length() > 0) artists.append(" / ");
                    artists.append(n);
                }
            }
            c.subtitle = artists.toString();
            c.kind = "song";
            out.add(c);
        }
        return out;
    }

    /** 搜索并转为发现页卡片 */
    public java.util.List<Card> searchCards(String keyword, int limit) throws IOException {
        java.util.List<Song> songs = search(keyword, Math.max(1, Math.min(30, limit)));
        java.util.List<Card> out = new java.util.ArrayList<>();
        for (Song song : songs) {
            Card c = new Card();
            c.id = song.id;
            c.title = song.name;
            c.subtitle = song.artistText();
            c.coverUrl = song.coverUrl;
            c.duration = song.duration;
            c.kind = "song";
            out.add(c);
        }
        return out;
    }

    /** 热歌榜歌曲（音乐漫游用） */
    public java.util.List<Card> hotSongs(int limit) throws IOException {
        java.util.List<Song> songs = playlist(3778678L);
        java.util.List<Card> out = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(limit, songs.size()); i++) {
            Song song = songs.get(i);
            Card c = new Card();
            c.id = song.id;
            c.title = song.name;
            c.subtitle = song.artistText();
            c.coverUrl = song.coverUrl;
            c.duration = song.duration;
            c.kind = "song";
            out.add(c);
        }
        return out;
    }

    /** 下载图片字节（封面等） */
    public byte[] fetchBytes(String url) throws IOException {
        HttpURLConnection conn = open(url, "GET", false);
        conn.setRequestProperty("Referer", "https://music.163.com/");
        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        } finally {
            conn.disconnect();
        }
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

    /** 统一处理轮询响应 */
    private QrStatus handlePoll(Resp r) throws IOException {
        QrStatus st = new QrStatus();
        Map<String, Object> d = Json.asMap(Json.parse(r.body));
        st.code = (int) Json.lng(d, "code", 0);
        st.message = Json.str(d, "message", "");
        if (st.code == 803) {
            absorbCookies(r.setCookies);
            absorbCookieString(Json.str(d, "cookie", ""));
            if (!isLoggedIn()) {
                syncFromStore();
            }
            if (isLoggedIn()) {
                saveCookie();
                st.success = true;
                Log.info("登录成功，已保存 Cookie: " + cookies.keySet());
                refreshProfile();
            } else {
                Log.warn("扫码已确认，但未拿到登录 Cookie（响应头数=" + r.setCookies.size()
                        + "，响应体=" + abbreviate(r.body) + "）");
            }
        } else if (st.code != 801 && st.code != 802) {
            Log.warn("扫码轮询返回 code=" + st.code + " " + st.message + " body=" + abbreviate(r.body));
        }
        return st;
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
    /** 下载被用户取消 */
    public static final class DownloadCancelled extends IOException {
        public DownloadCancelled() {
            super("已取消");
        }
    }

    /** 流式下载歌曲到目标文件，返回字节数 */
    public long download(String url, Path dest) throws IOException {
        return download(url, dest, null, null);
    }

    /** 流式下载（带进度与取消）：progress(done, totalBytes)，cancel 返回 true 时中止 */
    public long download(String url, Path dest,
                         java.util.function.BiConsumer<Long, Long> progress,
                         java.util.function.BooleanSupplier cancel) throws IOException {
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        HttpURLConnection conn = open(url, "GET", false);
        long total = conn.getContentLengthLong();
        long done = 0;
        boolean moved = false;
        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            long lastReport = 0;
            while ((n = in.read(buf)) > 0) {
                if (cancel != null && cancel.getAsBoolean()) {
                    throw new DownloadCancelled();
                }
                out.write(buf, 0, n);
                done += n;
                if (progress != null && (done - lastReport >= (1 << 16) || (total > 0 && done >= total))) {
                    lastReport = done;
                    progress.accept(done, total);
                }
            }
        } finally {
            conn.disconnect();
            if (!moved) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
        Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        moved = true;
        return done;
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

    // ---------------- eapi ----------------
    private Resp eapi(String path, Map<String, Object> data) throws IOException {
        String json = JsonOut.write(data);
        String md5 = md5Hex("nobody" + path + "use" + json + "md5forencrypt");
        String text = path + EAPI_DIVIDER + json + EAPI_DIVIDER + md5;
        byte[] enc = aesEcbEncrypt(text.getBytes(StandardCharsets.UTF_8), EAPI_KEY);
        String params = hexUpper(enc);
        String url = "https://music.163.com" + path;
        HttpURLConnection conn = open(url, "POST", true);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Cookie", eapiCookie());
        try (OutputStream os = conn.getOutputStream()) {
            os.write(("params=" + params).getBytes(StandardCharsets.US_ASCII));
        }
        Resp r = new Resp();
        r.body = readAll(conn);
        r.setCookies = readSetCookies(conn);
        conn.disconnect();
        absorbCookies(r.setCookies);
        return r;
    }

    private String eapiCookie() {
        String base = "os=pc; appver=8.10.05; osver=Microsoft-Windows-10.0.19045; "
                + "deviceId=" + deviceId();
        return cookie.isEmpty() ? base : cookie + "; " + base;
    }

    private String deviceId() {
        Path f = dataDir.resolve("netease-device-id.txt");
        try {
            if (Files.isRegularFile(f)) {
                String id = Files.readString(f, StandardCharsets.UTF_8).strip();
                if (!id.isEmpty()) return id;
            }
        } catch (IOException ignored) {
        }
        String id = java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase();
        try {
            Files.createDirectories(dataDir);
            Files.writeString(f, id, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        return id;
    }

    private static byte[] aesEcbEncrypt(byte[] data, byte[] key) {
        try {
            int pad = 16 - data.length % 16;
            byte[] padded = java.util.Arrays.copyOf(data, data.length + pad);
            java.util.Arrays.fill(padded, data.length, padded.length, (byte) pad);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"));
            return c.doFinal(padded);
        } catch (Exception e) {
            throw new IllegalStateException("eapi 加密失败", e);
        }
    }

    private static String hexUpper(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private static String md5Hex(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
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
        r.body = readAll(conn);
        r.setCookies = readSetCookies(conn);
        conn.disconnect();
        absorbCookies(r.setCookies);
        return r;
    }

    private Resp post(String url, String body) throws IOException {
        HttpURLConnection conn = open(url, "POST", true);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        Resp r = new Resp();
        r.body = readAll(conn);
        r.setCookies = readSetCookies(conn);
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
        Map<String, Object> albumMap = Json.asMap(m.get("album"));
        s.album = Json.str(albumMap, "name", "");
        s.coverUrl = Json.str(albumMap, "picUrl", "");
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

    /** 从进程 CookieManager 同步 cookie（覆盖重定向场景） */
    private void syncFromStore() {
        for (HttpCookie c : cookieManager.getCookieStore().getCookies()) {
            if (c.getValue() != null && !c.getValue().isEmpty()) {
                cookies.put(c.getName(), c.getValue());
            }
        }
        rebuildCookieHeader();
    }

    private static List<HttpCookie> parseCookies(String text) {
        List<HttpCookie> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split(";")) {
            String p = part.strip();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.add(new HttpCookie(p.substring(0, eq).strip(), p.substring(eq + 1).strip()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 逐条读取所有 Set-Cookie 响应头（不再依赖 Map 的键大小写/合并行为） */
    private static List<String> readSetCookies(HttpURLConnection conn) {
        List<String> out = new ArrayList<>();
        for (int i = 0; ; i++) {
            String key = conn.getHeaderFieldKey(i);
            if (key == null) {
                if (i == 0) continue;
                break;
            }
            String val = conn.getHeaderField(i);
            if (val != null && key.equalsIgnoreCase("Set-Cookie")) out.add(val);
        }
        return out;
    }

    private void absorbCookies(List<String> setCookies) {
        if (setCookies == null) return;
        for (String raw : setCookies) {
            if (raw == null) continue;
            String pair = raw.split(";", 2)[0].strip();
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).strip();
            String value = pair.substring(eq + 1).strip();
            if (!name.isEmpty() && !value.isEmpty()) cookies.put(name, value);
        }
        rebuildCookieHeader();
    }

    private void absorbCookieString(String text) {
        if (text == null || text.isBlank()) return;
        for (String part : text.split(";")) {
            String p = part.strip();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String name = p.substring(0, eq).strip();
            String value = p.substring(eq + 1).strip();
            if (!name.isEmpty() && !value.isEmpty()) cookies.put(name, value);
        }
        rebuildCookieHeader();
    }

    private void rebuildCookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        cookie = sb.toString();
    }

    /** 极简 JSON 序列化（eapi 参数用） */
    static final class JsonOut {
        static String write(Object o) {
            if (o == null) return "null";
            if (o instanceof String str) return quote(str);
            if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
            if (o instanceof Map<?, ?> m) {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(quote(String.valueOf(e.getKey()))).append(':').append(write(e.getValue()));
                }
                return sb.append('}').toString();
            }
            if (o instanceof java.util.List<?> list) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(write(list.get(i)));
                }
                return sb.append(']').toString();
            }
            return quote(String.valueOf(o));
        }

        static String quote(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            return sb.append('"').toString();
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ");
        return t.length() <= 160 ? t : t.substring(0, 160) + "…";
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loadCookie() {
        try {
            if (Files.isRegularFile(cookieFile)) {
                String text = Files.readString(cookieFile, StandardCharsets.UTF_8).strip();
                absorbCookieString(text);
                try {
                    java.net.URI uri = new java.net.URI("https://music.163.com");
                    for (HttpCookie c : parseCookies(text)) {
                        cookieManager.getCookieStore().add(uri, c);
                    }
                } catch (java.net.URISyntaxException ignored) {
                }
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
