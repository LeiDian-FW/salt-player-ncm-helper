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

    private volatile boolean lyricsOnline = true;
    private volatile boolean writeLrc = true;

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
            job = new NcmJob(dataDir, lyricsService, history);
            reloadSettings();

            configManager.addConfigChangeListener(h -> {
                Log.info("配置已更新，重新应用");
                reloadSettings();
            });
            registerHotkey();
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
