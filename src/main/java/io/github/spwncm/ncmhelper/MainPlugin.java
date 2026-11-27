package io.github.spwncm.ncmhelper;

import com.xuncorp.spw.workshop.api.ActionShortcut;
import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.PluginPermission;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * NCM 助手：把网易云 NCM 解密、归档、封面、歌词整合进 Salt Player。
 *
 * 功能：
 *  1. 自动识别网易云下载目录，后台监视并把新下载的 NCM 转成原始 FLAC/MP3
 *  2. 按 歌手/专辑 归档到音乐库，内嵌封面，清理源文件（先写后删）
 *  3. 播放时若找不到歌词，按歌名/歌手在线匹配网易云歌词
 */
public class MainPlugin extends SpwPlugin {

    private static volatile MainPlugin INSTANCE;

    private ConfigManager configManager;
    private ConfigHelper config;
    private NcmJob job;
    private NeteaseLyrics lyricsService;
    private AutoCloseable hotkey;
    private Path dataDir;
    private History history;
    private NeteaseClient netease;

    private volatile boolean lyricsOnline = true;
    private volatile boolean writeLrc = true;
    private volatile boolean neteaseEnabled = false;
    private volatile int neteaseLimit = 10;
    private volatile boolean neteaseLimitValid = true;
    private volatile String neteasePlaylistId = "";
    private volatile String neteaseKeyword = "";
    private volatile String neteaseCookie = "";
    private volatile String neteaseLibrary = "D:\\music";

    public MainPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        INSTANCE = this;
        try {
            configManager = WorkshopApi.manager().createConfigManager();
            config = configManager.getConfig();
            dataDir = resolveDataDir();
            Log.init(dataDir.resolve("ncm-helper.log"));
            Log.info("启动 v" + getPluginContext().getPluginVersion()
                    + " | SPW " + getPluginContext().getSpwVersion()
                    + " | 渠道 " + getPluginContext().getSpwChannel());

            lyricsService = new NeteaseLyrics(dataDir.resolve("lyrics-cache"));
            history = new History(dataDir, 30);
            netease = new NeteaseClient(dataDir);
            NeteaseUiBridge.bind(netease);
            job = new NcmJob(dataDir, lyricsService, history);
            reloadSettings();

            configManager.addConfigChangeListener(h -> {
                Log.info("配置已更新，重新应用");
                reloadSettings();
                tryInstallNativeUi();
            });
            registerHotkey();
            tryInstallNativeUi();
            toast("NCM 助手已启用");
        } catch (Throwable e) {
            Log.error("启动失败: " + e);
        }
    }

    @Override
    public void stop() {
        try {
            if (job != null) job.stop();
            if (hotkey != null) {
                hotkey.close();
                hotkey = null;
            }
            NeteaseUiBridge.uninstall();
            Log.info("已停用");
            toast("NCM 助手已停用");
        } catch (Throwable e) {
            Log.error("停用异常: " + e);
        } finally {
            INSTANCE = null;
        }
    }

    @Override
    public void delete() {
        try {
            Log.info("插件被删除");
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void update() {
        toast("NCM 助手已更新");
    }

    /** 插件数据目录：优先用宿主给配置文件的目录，取不到时退回 workshop/data/<pluginId> */
    private Path resolveDataDir() {
        try {
            Path cfgPath = config.getConfigPath();
            if (cfgPath != null && cfgPath.getParent() != null) return cfgPath.getParent();
        } catch (Throwable ignored) {
        }
        String appData = System.getenv("APPDATA");
        if (appData == null) appData = System.getProperty("user.home", "") + "\\AppData\\Roaming";
        return Paths.get(appData, "Salt Player for Windows", "workshop", "data",
                getPluginContext().getPluginId());
    }

    // ---------------- 设置 ----------------
    private void reloadSettings() {
        boolean watch = cfgBool("ncm.auto", true);
        String src = cfgString("ncm.source", "");
        String lib = cfgString("ncm.library", "D:\\music");
        int interval = cfgInt("ncm.interval", 60);
        boolean moveAudio = cfgBool("ncm.move_audio", true);
        boolean fetchLyrics = cfgBool("ncm.lyrics", true);
        lyricsOnline = cfgBool("lyrics.online", true);
        writeLrc = cfgBool("lyrics.write_lrc", true);
        if (history != null) history.setRetainDays(cfgInt("history.days", 30));
        neteaseEnabled = cfgBool("netease.enabled", false);
        String limitRaw = cfgString("netease.limit", "10").strip();
        if (limitRaw.matches("\\d+")) {
            try {
                int v = Integer.parseInt(limitRaw);
                if (v >= 1 && v <= 50) {
                    neteaseLimit = v;
                    neteaseLimitValid = true;
                } else {
                    neteaseLimit = Math.max(1, Math.min(50, v));
                    neteaseLimitValid = false;
                }
            } catch (NumberFormatException e) {
                neteaseLimit = 10;
                neteaseLimitValid = false;
            }
        } else {
            neteaseLimit = 10;
            neteaseLimitValid = false;
        }
        neteasePlaylistId = cfgString("netease.playlist_id", "");
        neteaseKeyword = cfgString("netease.search_keyword", "");
        neteaseCookie = cfgString("netease.cookie", "");
        neteaseLibrary = lib.isBlank() ? "D:\\music" : lib;

        List<Path> sources = new ArrayList<>();
        for (String part : src.split(";")) {
            String p = part.strip();
            if (!p.isEmpty()) sources.add(Paths.get(p));
        }
        Path library;
        try {
            library = Paths.get(lib.isBlank() ? "D:\\music" : lib);
        } catch (Exception e) {
            library = Paths.get("D:\\music");
        }
        if (job != null) {
            job.apply(NcmJob.JobConfig.of(library, watch, sources, interval, moveAudio, fetchLyrics));
        }
        Log.info("配置：自动监视=" + watch + "，库=" + library + "，间隔=" + interval
                + "s，源=" + (sources.isEmpty() ? "自动识别" : sources.toString())
                + "，在线歌词=" + lyricsOnline);
    }

    private void registerHotkey() {
        try {
            if (!WorkshopApi.manager().isPermissionGranted(PluginPermission.KEY_BINDINGS)) {
                Log.warn("未获得快捷键权限，跳过快捷键注册");
                return;
            }
            hotkey = WorkshopApi.manager().getKeyBindingManager().register(
                    "ncm-run",
                    "NCM 助手：立即处理",
                    new ActionShortcut(KeyEvent.VK_N, ActionShortcut.CONTROL | ActionShortcut.ALT),
                    false,
                    () -> {
                        Log.info("快捷键触发处理");
                        if (job != null) job.triggerOnce(true);
                    });
            Log.info("快捷键已注册：Ctrl+Alt+N");
        } catch (Throwable e) {
            Log.warn("快捷键注册失败: " + e);
        }
    }

    private Object raw(String key, Object def) {
        try {
            return config == null ? def : config.get(key, def);
        } catch (Throwable e) {
            return def;
        }
    }

    private boolean cfgBool(String key, boolean def) {
        Object v = raw(key, def);
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private String cfgString(String key, String def) {
        Object v = raw(key, def);
        return v == null ? def : String.valueOf(v);
    }

    private int cfgInt(String key, int def) {
        Object v = raw(key, def);
        if (v instanceof Number n) return n.intValue();
        try {
            return (int) Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    // ---------------- 供其余类使用 ----------------
    static NeteaseLyrics getLyricsService() {
        MainPlugin p = INSTANCE;
        return p == null ? null : p.lyricsService;
    }

    static boolean lyricsOnlineEnabled() {
        MainPlugin p = INSTANCE;
        return p != null && p.lyricsOnline;
    }

    static boolean writeLrcEnabled() {
        MainPlugin p = INSTANCE;
        return p == null || p.writeLrc;
    }

    static boolean toastAvailable() {
        return INSTANCE != null;
    }

    static void toast(String text) {
        try {
            WorkshopApi.ui().toast(text, WorkshopApi.Ui.ToastType.Success);
        } catch (Throwable e) {
            Log.warn("toast 失败: " + e);
        }
    }

    /** 配置页「立即处理一次」按钮入口 */
    public static void onRunNow() {
        MainPlugin p = INSTANCE;
        if (p == null || p.job == null) {
            toast("插件尚未启用");
            return;
        }
        p.job.triggerOnce(true);
        toast("已开始扫描并处理 NCM…");
    }

    // ---------------- 网易云（实验性） ----------------
    private interface SongSource {
        java.util.List<NeteaseClient.Song> load() throws Exception;
    }

    /** 配置页「扫码登录」按钮入口 */
    public static void onNeteaseLogin() {
        MainPlugin p = INSTANCE;
        if (p == null || p.netease == null) {
            toast("插件尚未启用");
            return;
        }
        if (!p.neteaseEnabled) {
            toast("请先在配置中启用网易云功能");
            return;
        }
        NeteaseLoginDialog.show(p.netease);
    }

    /** 配置页「使用 Cookie 登录」按钮入口（风控 8821 时推荐） */
    public static void onNeteaseCookieLogin() {
        MainPlugin p = INSTANCE;
        if (p == null || p.netease == null) {
            toast("插件尚未启用");
            return;
        }
        if (!p.neteaseEnabled) {
            toast("请先在配置中启用网易云功能");
            return;
        }
        String raw = p.neteaseCookie == null ? "" : p.neteaseCookie.strip();
        if (raw.isEmpty()) {
            toast("请先在配置中粘贴网易云 Cookie（MUSIC_U=...）");
            return;
        }
        boolean ok = p.netease.loginWithCookie(raw);
        if (ok) {
            String nick = p.netease.nickname();
            toast("网易云登录成功" + (nick.isEmpty() ? "" : "：" + nick));
        } else {
            toast("Cookie 无效或已过期，请重新复制 MUSIC_U");
            Log.warn("Cookie 登录失败：未解析到有效 MUSIC_U");
        }
    }

    /** 配置页「从剪贴板登录」按钮入口：读取剪贴板里的 Cookie 直接登录 */
    public static void onNeteaseCookieFromClipboard() {
        MainPlugin p = INSTANCE;
        if (p == null || p.netease == null) {
            toast("插件尚未启用");
            return;
        }
        if (!p.neteaseEnabled) {
            toast("请先在配置中启用网易云功能");
            return;
        }
        String text = "";
        try {
            java.awt.datatransfer.Clipboard cb = java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard();
            Object data = cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (data != null) text = String.valueOf(data).strip();
        } catch (Throwable e) {
            toast("读取剪贴板失败: " + e.getMessage());
            return;
        }
        if (text.isEmpty()) {
            toast("剪贴板为空，请先复制 MUSIC_U 或其值");
            return;
        }
        // 允许直接复制 MUSIC_U 的值、或整串 Cookie、或带引号/换行的内容
        text = text.replace("\r", "").replace("\n", " ").replace("\"", "").strip();
        if (!text.contains("MUSIC_U") && !text.contains("=")) {
            toast("剪贴板内容不像 Cookie，请复制 MUSIC_U=... 或仅复制其值");
            return;
        }
        Log.info("尝试从剪贴板登录网易云");
        boolean ok = p.netease.loginWithCookie(text);
        if (ok) {
            String nick = p.netease.nickname();
            toast("网易云登录成功" + (nick.isEmpty() ? "" : "：" + nick));
        } else {
            toast("Cookie 无效或已过期（也可能网络异常），请重新复制 MUSIC_U");
            Log.warn("剪贴板 Cookie 登录失败");
        }
    }

    /** 配置页「登录状态」按钮入口 */
    public static void onNeteaseStatus() {
        MainPlugin p = INSTANCE;
        if (p == null || p.netease == null) {
            toast("插件尚未启用");
            return;
        }
        if (!p.netease.isLoggedIn()) {
            toast("网易云：未登录");
            return;
        }
        p.netease.refreshProfile();
        String nick = p.netease.nickname();
        toast("网易云：已登录" + (nick.isEmpty() ? "" : "（" + nick + "）"));
    }

    /** 配置页「退出登录」按钮入口 */
    public static void onNeteaseLogout() {
        MainPlugin p = INSTANCE;
        if (p == null || p.netease == null) {
            toast("插件尚未启用");
            return;
        }
        p.netease.logout();
        toast("已退出网易云账号");
    }

    /** 配置页「下载歌单」按钮入口 */
    public static void onDownloadPlaylist() {
        MainPlugin p = INSTANCE;
        if (!neteaseReady()) return;
        String idText = p.neteasePlaylistId.strip();
        long id;
        try {
            id = Long.parseLong(idText);
        } catch (Exception e) {
            toast("请先在配置中填写歌单 ID（纯数字）");
            return;
        }
        downloadAsync("下载歌单", () -> p.netease.playlist(id));
    }

    /** 配置页「搜索并下载」按钮入口 */
    public static void onSearchDownload() {
        MainPlugin p = INSTANCE;
        if (!neteaseReady()) return;
        String kw = p.neteaseKeyword.strip();
        if (kw.isEmpty()) {
            toast("请先在配置中填写搜索关键词");
            return;
        }
        downloadAsync("搜索下载", () -> p.netease.search(kw, p.neteaseLimit));
    }

    /** 配置页「下载我喜欢的音乐」按钮入口 */
    public static void onDownloadLikes() {
        MainPlugin p = INSTANCE;
        if (!neteaseReady()) return;
        downloadAsync("下载我喜欢的音乐", () -> p.netease.myLikes());
    }

    private static boolean neteaseReady() {
        MainPlugin p = INSTANCE;
        if (p == null || p.netease == null) {
            toast("插件尚未启用");
            return false;
        }
        if (!p.neteaseEnabled) {
            toast("请先在配置中启用网易云功能");
            return false;
        }
        if (!p.netease.isLoggedIn()) {
            toast("请先扫码登录网易云");
            return false;
        }
        return true;
    }

    private static void downloadAsync(String what, SongSource source) {
        MainPlugin p = INSTANCE;
        Path library = Paths.get(p.neteaseLibrary);
        if (!p.neteaseLimitValid) {
            toast("「每次最多下载数」需填写 1-50 的整数");
            return;
        }
        DownloadProgressDialog progress = DownloadProgressDialog.show(what);
        Thread t = new Thread(() -> {
            try {
                progress.setPhase("正在获取歌曲列表…");
                java.util.List<NeteaseClient.Song> songs = source.load();
                if (songs.isEmpty()) {
                    progress.error("没有可下载的歌曲");
                    toast(what + "：没有可下载的歌曲");
                    return;
                }
                NeteaseDownloader dl = new NeteaseDownloader(p.netease, library, p.history);
                dl.download(songs, p.neteaseLimit, true, new DownloadListener(progress));
            } catch (Throwable e) {
                Log.error(what + "失败: " + e);
                progress.error(e.getMessage());
                toast(what + "失败: " + e.getMessage());
            }
        }, "netease-download");
        t.setDaemon(true);
        t.start();
    }

    /** 带取消能力的下载进度监听 */
    private static final class DownloadListener
            implements NeteaseDownloader.Listener, NeteaseDownloader.Cancellable {
        private final DownloadProgressDialog progress;

        DownloadListener(DownloadProgressDialog progress) {
            this.progress = progress;
        }

        @Override
        public void onStart(int total) {
            progress.start(total);
            progress.setPhase("开始下载，共 " + total + " 首");
        }

        @Override
        public void onSong(int index, int total, NeteaseClient.Song song) {
            progress.song(index, song);
        }

        @Override
        public void onBytes(long done, long fileTotal) {
            progress.bytes(done, fileTotal);
        }

        @Override
        public void onResult(String status, String message) {
            progress.result(status, message);
        }

        @Override
        public void onFinish(String summary) {
            progress.finish(summary);
            toast(summary + "；如未显示请扫描音乐库");
        }

        @Override
        public boolean isCancelled() {
            return progress.isCancelled();
        }
    }


    /** 安装前预检宿主 Hook 目标是否存在，便于诊断 */
    private void preflightNativeHook() {
        try {
            ClassLoader host = com.xuncorp.spw.workshop.api.WorkshopApi.class.getClassLoader();
            Class<?> nav = Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt", false, host);
            boolean found = false;
            for (java.lang.reflect.Method m : nav.getDeclaredMethods()) {
                if (m.getName().equals("Content")) {
                    found = true;
                    break;
                }
            }
            Log.info("原生界面预检: AppNavigationKt.Content 存在=" + found
                    + "，方法数=" + nav.getDeclaredMethods().length);
        } catch (Throwable e) {
            Log.warn("原生界面预检失败: " + e);
        }
    }

    /** 尝试安装原生 Compose 界面（需要 CLASS_TRANSFORM 权限） */
    private void tryInstallNativeUi() {
        try {
            if (!com.xuncorp.spw.workshop.api.WorkshopApi.manager()
                    .isPermissionGranted(com.xuncorp.spw.workshop.api.PluginPermission.CLASS_TRANSFORM)) {
                return;
            }
            if (NeteaseUiBridge.nativeAvailable()) return;
            preflightNativeHook();
            Class<?> c = Class.forName("io.github.spwncm.ncmhelper.ui.NeteaseNativeUi",
                    true, getClass().getClassLoader());
            Object ok = c.getMethod("install").invoke(null);
            Log.info("原生界面安装: " + ok);
        } catch (Throwable e) {
            Log.warn("原生界面安装失败: " + e);
        }
    }

    public static MainPlugin instance() {
        return INSTANCE;
    }

    public NeteaseClient client() {
        return netease;
    }

    public History historyRef() {
        return history;
    }

    public String neteaseLibraryPath() {
        return neteaseLibrary;
    }

    public int neteaseDownloadLimit() {
        return neteaseLimit;
    }

    public boolean neteaseDownloadLimitValid() {
        return neteaseLimitValid;
    }

    public boolean neteaseEnabledNow() {
        return neteaseEnabled;
    }

    public void startPlaylistDownload(long id) {
        downloadAsync("下载歌单", () -> netease.playlist(id));
    }

    public void startSearchDownload(String keyword) {
        String kw = keyword == null ? "" : keyword.strip();
        if (kw.isEmpty()) {
            toast("请先填写搜索关键词");
            return;
        }
        downloadAsync("搜索下载", () -> netease.search(kw, neteaseLimit));
    }

    public void startLikesDownload() {
        downloadAsync("下载我喜欢的音乐", () -> netease.myLikes());
    }

    /** 配置页「打开网易云原生面板」按钮入口 */
    public static void onOpenNeteasePanel() {
        MainPlugin p = INSTANCE;
        if (p == null) {
            toast("插件尚未启用");
            return;
        }
        if (!p.neteaseEnabled) {
            toast("请先在配置中启用网易云功能");
            return;
        }
        try {
            p.tryInstallNativeUi();
        } catch (Throwable ignored) {
        }
        if (!NeteaseUiBridge.nativeAvailable()) {
            Log.info("原生界面不可用，回退到 Swing 面板（提示：授予 CLASS_TRANSFORM 后可启用原生界面）");
            NeteaseSwingPanel.show();
            return;
        }
        NeteaseUiBridge.openPanel();
    }

    /** 配置页「打开转换记录」按钮入口 */
    public static void onOpenHistory() {
        MainPlugin p = INSTANCE;
        if (p == null || p.dataDir == null) {
            toast("插件尚未启用");
            return;
        }
        try {
            Path f = p.dataDir.resolve("history.jsonl");
            if (!Files.isRegularFile(f)) {
                toast("暂无转换记录");
                return;
            }
            java.awt.Desktop.getDesktop().open(f.toFile());
        } catch (Throwable e) {
            toast("打开失败: " + e.getMessage());
        }
    }

    /** 配置页「清空转换记录」按钮入口 */
    public static void onClearHistory() {
        MainPlugin p = INSTANCE;
        if (p == null || p.history == null) {
            toast("插件尚未启用");
            return;
        }
        p.history.clear();
        toast("转换记录已清空");
    }

    /** 配置页「查看状态」按钮入口 */
    public static void onShowStatus() {
        MainPlugin p = INSTANCE;
        if (p == null || p.job == null) {
            toast("插件尚未启用");
            return;
        }
        toast(p.job.isRunning() ? "自动监视运行中" : "自动监视未启用");
    }
}
