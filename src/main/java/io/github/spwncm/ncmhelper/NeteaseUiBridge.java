package io.github.spwncm.ncmhelper;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;

/**
 * 原生 Compose 界面（Kotlin）与插件 Java 逻辑之间的桥接层。
 * Kotlin 侧通过本类读取登录状态、发起登录、轮询扫码；本类通过反射控制原生面板的显隐，
 * 避免 Java 与 Kotlin 源码之间的循环编译依赖。
 */
public final class NeteaseUiBridge {

    private static volatile NeteaseClient client;
    private static volatile String nativeUiClass = "io.github.spwncm.ncmhelper.ui.NeteaseNativeUi";

    private NeteaseUiBridge() {
    }

    static void bind(NeteaseClient neteaseClient) {
        client = neteaseClient;
    }

    /** 原生界面是否可用（Hook 安装成功） */
    public static boolean nativeAvailable() {
        return Boolean.TRUE.equals(invokeStatic("isInstalled", new Class<?>[0]));
    }

    /** 打开原生面板 */
    public static void openPanel() {
        invokeStatic("setVisible", new Class<?>[]{boolean.class}, true);
    }

    /** 关闭原生面板 */
    public static void closePanel() {
        invokeStatic("setVisible", new Class<?>[]{boolean.class}, false);
    }

    /** 注入点未生效时切换下一个候选，返回是否安装成功 */
    public static boolean retryNextCandidate() {
        return Boolean.TRUE.equals(invokeStatic("retryNextCandidate", new Class<?>[0]));
    }

    /** 当前注入点描述 */
    public static String currentTarget() {
        Object v = invokeStatic("currentTarget", new Class<?>[0]);
        return v == null ? "未安装" : String.valueOf(v);
    }

    /** 原生 Hook 实际被调用次数 */
    public static int hookHits() {
        Object v = invokeStatic("hookHits", new Class<?>[0]);
        return v instanceof Integer i ? i : 0;
    }

    /** 面板显隐状态 */
    public static boolean panelVisible() {
        Object v = invokeStatic("isVisible", new Class<?>[0]);
        return Boolean.TRUE.equals(v);
    }

    /** 插件停用时卸载 Hook */
    public static void uninstall() {
        invokeStatic("uninstall", new Class<?>[0]);
    }

    // ---------------- 供 Kotlin 调用 ----------------
    /** 下载进度回调（由 Kotlin 实现，回调发生在下载线程） */
    public interface ProgressSink {
        void status(String text);

        void song(int index, int total, String name, String artist);

        void progress(float fraction);

        void stats(String text);

        void done(String summary);

        void error(String message);
    }

    private static volatile boolean downloadCancelled;

    /** 搜索（阻塞，调用方需在 IO 线程） */
    public static java.util.List<NeteaseClient.Song> search(String keyword, int limit) throws Exception {
        NeteaseClient c = client;
        if (c == null) throw new IllegalStateException("插件未就绪");
        return c.search(keyword, limit);
    }

    /** 歌单（阻塞） */
    public static java.util.List<NeteaseClient.Song> playlist(long id) throws Exception {
        NeteaseClient c = client;
        if (c == null) throw new IllegalStateException("插件未就绪");
        return c.playlist(id);
    }

    /** 我喜欢的音乐（阻塞） */
    public static java.util.List<NeteaseClient.Song> likes() throws Exception {
        NeteaseClient c = client;
        if (c == null) throw new IllegalStateException("插件未就绪");
        return c.myLikes();
    }

    private static volatile String lastSearchKeyword = "";

    /** 设置搜索关键词（发现页搜索用） */
    public static void setSearchKeyword(String keyword) {
        lastSearchKeyword = keyword == null ? "" : keyword;
    }

    /** 发现页数据：tab = playlist / newsong / mine / daily / roam / search */
    public static java.util.List<NeteaseClient.Card> discovery(String tab) throws Exception {
        NeteaseClient c = client;
        if (c == null) throw new IllegalStateException("插件未就绪");
        return switch (tab) {
            case "playlist" -> c.recommendPlaylists(10);
            case "newsong" -> c.newSongs(10);
            case "mine" -> c.myPlaylists();
            case "daily" -> c.dailySongs();
            case "roam" -> c.hotSongs(10);
            case "search" -> c.searchCards(lastSearchKeyword, 10);
            default -> throw new IllegalArgumentException("未知页签: " + tab);
        };
    }

    /** 封面缓存目录（插件数据目录下 covers/） */
    public static String cacheDir() {
        MainPlugin p = MainPlugin.instance();
        if (p == null) return "";
        java.nio.file.Path dir = p.dataDirRef().resolve("covers");
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir.toString();
    }

    /** 下载封面图片字节 */
    public static byte[] cover(String url) {
        NeteaseClient c = client;
        if (c == null || url == null || url.isEmpty()) return null;
        try {
            return c.fetchBytes(url);
        } catch (Exception e) {
            return null;
        }
    }

    /** 下载单曲（发现页卡片点击） */
    public static void downloadCard(long id, String name, String artist, String kind, ProgressSink sink) {
        if ("playlist".equals(kind)) {
            downloadAsync("playlist", String.valueOf(id), downloadLimit(), sink);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                MainPlugin p = MainPlugin.instance();
                if (p == null) throw new IllegalStateException("插件未就绪");
                NeteaseClient.Song song = new NeteaseClient.Song();
                song.id = id;
                song.name = name == null ? "" : name;
                if (artist != null && !artist.isBlank()) song.artists.add(artist);
                NeteaseDownloader dl = new NeteaseDownloader(client,
                        java.nio.file.Paths.get(p.neteaseLibraryPath()), p.historyRef());
                dl.download(java.util.List.of(song), 1, true, new NativeDownloadListener(sink));
            } catch (Throwable e) {
                sink.error(String.valueOf(e));
            }
        }, "netease-native-song-download");
        t.setDaemon(true);
        t.start();
    }

    /** 下载列表中的歌曲（异步），kind: search / playlist / likes */
    public static void downloadAsync(String kind, String argument, int limit, ProgressSink sink) {
        downloadCancelled = false;
        Thread t = new Thread(() -> {
            try {
                MainPlugin p = MainPlugin.instance();
                if (p == null) throw new IllegalStateException("插件未就绪");
                java.util.List<NeteaseClient.Song> songs;
                switch (kind) {
                    case "playlist" -> songs = playlist(Long.parseLong(argument.strip()));
                    case "likes" -> songs = likes();
                    default -> songs = search(argument.strip(), Math.max(limit * 3, 30));
                }
                if (songs.isEmpty()) {
                    sink.error("没有可下载的歌曲");
                    return;
                }
                NeteaseDownloader dl = new NeteaseDownloader(client, java.nio.file.Paths.get(p.neteaseLibraryPath()), p.historyRef());
                dl.download(songs, limit, true, new NativeDownloadListener(sink));
            } catch (NeteaseClient.DownloadCancelled e) {
                sink.error("已取消");
            } catch (Throwable e) {
                sink.error(String.valueOf(e));
            }
        }, "netease-native-download");
        t.setDaemon(true);
        t.start();
    }

    /** 原生界面的下载监听（同时支持取消） */
    private static final class NativeDownloadListener
            implements NeteaseDownloader.Listener, NeteaseDownloader.Cancellable {
        private final ProgressSink sink;

        NativeDownloadListener(ProgressSink sink) {
            this.sink = sink;
        }

        @Override public void onStart(int total) { sink.stats("0 / " + total); }

        @Override public void onSong(int index, int total, NeteaseClient.Song song) {
            sink.song(index, total, song.name, song.artistText());
        }

        @Override public void onBytes(long done, long fileTotal) {
            sink.progress(fileTotal > 0 ? (float) Math.min(1.0, done / (double) fileTotal) : 0f);
        }

        @Override public void onResult(String status, String message) { sink.stats(status + " | " + message); }

        @Override public void onFinish(String summary) { sink.done(summary); }

        @Override public boolean isCancelled() { return downloadCancelled; }
    }

    /** 取消原生界面发起的下载 */
    public static void downloadCancel() {
        downloadCancelled = true;
    }

    public static boolean downloadCancelled() {
        return downloadCancelled;
    }
    public static boolean loggedIn() {
        return client != null && client.isLoggedIn();
    }

    public static String statusText() {
        NeteaseClient c = client;
        if (c == null) return "插件未就绪";
        if (!c.isLoggedIn()) return "未登录";
        String nick = c.nickname();
        return nick.isEmpty() ? "已登录" : "已登录：" + nick;
    }

    public static String nickname() {
        NeteaseClient c = client;
        return c == null ? "" : c.nickname();
    }

    /** 从剪贴板读取 Cookie 登录，返回提示文本 */
    public static String clipboardLogin() {
        NeteaseClient c = client;
        if (c == null) return "插件未就绪";
        String text;
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            Object data = cb.getData(DataFlavor.stringFlavor);
            text = data == null ? "" : String.valueOf(data).strip();
        } catch (Exception e) {
            return "读取剪贴板失败: " + e.getMessage();
        }
        if (text.isEmpty()) return "剪贴板为空，请先复制 MUSIC_U";
        text = text.replace("\r", "").replace("\n", " ").replace("\"", "").strip();
        if (!text.contains("MUSIC_U") && !text.contains("=")) return "剪贴板内容不像 Cookie";
        boolean ok = c.loginWithCookie(text);
        if (ok) {
            String nick = c.nickname();
            return "登录成功" + (nick.isEmpty() ? "" : "：" + nick);
        }
        return "Cookie 无效或已过期";
    }

    /** 申请扫码 key（失败抛异常） */
    public static String requestQrKey() throws Exception {
        NeteaseClient c = client;
        if (c == null) throw new IllegalStateException("插件未就绪");
        return c.requestQrKey().key;
    }

    /** 轮询扫码状态：800 过期 / 801 等待 / 802 待确认 / 803 成功；异常返回 -1 */
    public static int pollQr(String key) {
        NeteaseClient c = client;
        if (c == null || key == null || key.isEmpty()) return -1;
        try {
            NeteaseClient.QrSession session = new NeteaseClient.QrSession(key, "api", 1);
            return c.pollQr(session).code;
        } catch (Exception e) {
            Log.warn("原生界面扫码轮询失败: " + e.getMessage());
            return -1;
        }
    }

    public static String libraryPath() {
        MainPlugin p = MainPlugin.instance();
        return p == null ? "D:\\music" : p.neteaseLibraryPath();
    }

    public static int downloadLimit() {
        MainPlugin p = MainPlugin.instance();
        return p == null ? 10 : p.neteaseDownloadLimit();
    }

    public static boolean downloadLimitValid() {
        MainPlugin p = MainPlugin.instance();
        return p == null || p.neteaseDownloadLimitValid();
    }

    /** 开始下载（kind: playlist / search / likes），进度通过配置页已有的转换记录与提示反馈 */
    public static String startDownload(String kind, String argument) {
        MainPlugin p = MainPlugin.instance();
        if (p == null) return "插件未就绪";
        if (!p.neteaseEnabledNow()) return "请先在配置中启用网易云功能";
        if (!loggedIn()) return "请先登录网易云";
        if (!p.neteaseDownloadLimitValid()) return "「每次最多下载数」需填写 1-50 的整数";
        try {
            switch (kind) {
                case "playlist" -> p.startPlaylistDownload(Long.parseLong(argument.strip()));
                case "search" -> p.startSearchDownload(argument.strip());
                case "likes" -> p.startLikesDownload();
                default -> {
                    return "未知下载类型";
                }
            }
            return "已开始下载";
        } catch (NumberFormatException e) {
            return "歌单 ID 需为数字";
        } catch (Exception e) {
            return "下载失败: " + e.getMessage();
        }
    }

    // ---------------- 反射调用 Kotlin ----------------
    private static Object invokeStatic(String method, Class<?>[] types, Object... args) {
        try {
            Class<?> c = Class.forName(nativeUiClass, true, NeteaseUiBridge.class.getClassLoader());
            java.lang.reflect.Method m = c.getMethod(method, types);
            return m.invoke(null, args);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            Log.warn("原生界面调用失败(" + method + "): " + e);
            return null;
        }
    }
}
