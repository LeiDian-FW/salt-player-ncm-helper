package io.github.spwncm.ncmhelper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * NCM 自动处理任务（全程流式，适配宿主 512MB 堆）：
 *   识别下载目录 → 解密 → 按 歌手/专辑 归档 → 内嵌封面 → 补歌词 → 清理源文件。
 * 解析/校验失败保留源文件，绝不丢数据。
 */
public final class NcmJob {

    private static final Set<String> AUDIO_EXT = Set.of(".flac", ".mp3", ".m4a", ".ogg", ".wav", ".ape");
    private static final Pattern WEBDB_FILENAME =
            Pattern.compile("\"filename\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final long DETECT_CACHE_MS = 5 * 60 * 1000L;

    /** 本轮新增文件及其（来自 NCM 元数据的）歌曲信息 */
    private record NewFile(Path path, String title, String artist, String album) {
    }

    /** 配置快照 */
    public static final class JobConfig {
        public boolean watch = true;
        public List<Path> sources = new ArrayList<>();
        public Path library = Paths.get("D:\\music");
        public int intervalSec = 60;
        public boolean moveAudio = true;
        public boolean fetchLyrics = true;

        public static JobConfig of(Path library, boolean watch, List<Path> sources,
                                   int intervalSec, boolean moveAudio, boolean fetchLyrics) {
            JobConfig c = new JobConfig();
            c.library = library;
            c.watch = watch;
            c.sources = sources == null ? new ArrayList<>() : sources;
            c.intervalSec = Math.max(10, intervalSec);
            c.moveAudio = moveAudio;
            c.fetchLyrics = fetchLyrics;
            return c;
        }
    }

    private final NeteaseLyrics lyrics;
    private final Path dataDir;
    private final Path stateFile;
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    private volatile JobConfig config = new JobConfig();
    private volatile boolean running;
    private Thread worker;

    // 下载目录识别结果缓存（webdb.dat 有 200MB+，不能每轮都扫）
    private volatile List<Path> cachedSources = new ArrayList<>();
    private volatile long cachedAt = 0L;

    public NcmJob(Path dataDir, NeteaseLyrics lyrics) {
        this.dataDir = dataDir;
        this.lyrics = lyrics;
        this.stateFile = dataDir.resolve("processed.txt");
        loadState();
    }

    /** 应用新配置：必要时重启监视线程 */
    public synchronized void apply(JobConfig cfg) {
        boolean wasRunning = running;
        if (wasRunning) stop();
        this.config = cfg;
        cachedSources = new ArrayList<>();
        cachedAt = 0;
        if (cfg.watch) start();
    }

    public synchronized void start() {
        if (running) return;
        if (!config.watch) {
            Log.info("自动监视未启用");
            return;
        }
        running = true;
        worker = new Thread(this::loop, "ncm-helper-watcher");
        worker.setDaemon(true);
        worker.start();
        Log.info("自动监视已启动，间隔 " + config.intervalSec + " 秒");
    }

    public synchronized void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        Log.info("自动监视已停止");
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /** 立即在后台执行一次（不阻塞调用线程） */
    public void triggerOnce(boolean manual) {
        Thread t = new Thread(() -> runOnce(manual), "ncm-helper-once");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        while (running) {
            try {
                runOnce(false);
            } catch (Throwable e) {
                Log.error("本轮处理异常: " + e);
            }
            long deadline = System.currentTimeMillis() + config.intervalSec * 1000L;
            while (running && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(Math.min(1000, Math.max(1, deadline - System.currentTimeMillis())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ---------------- 单轮处理 ----------------
    public synchronized void runOnce(boolean manual) {
        JobConfig cfg = config;
        List<Path> sources = cfg.sources.isEmpty() ? autoDetectSources() : cfg.sources;
        if (sources.isEmpty()) {
            if (manual) Log.info("未找到下载目录（可在插件配置里指定）");
            return;
        }
        Path library = cfg.library.toAbsolutePath().normalize();
        int ncm = 0;
        int moved = 0;
        int failed = 0;
        int skipped = 0;
        List<NewFile> newFiles = new ArrayList<>();

        for (Path src : sources) {
            Path abs = src.toAbsolutePath().normalize();
            if (!Files.isDirectory(abs)) continue;
            if (abs.equals(library) || abs.startsWith(library)) {
                Log.warn("跳过与音乐库重叠的目录: " + abs);
                continue;
            }
            List<Path> ncmFiles = new ArrayList<>();
            List<Path> audioFiles = new ArrayList<>();
            scan(abs, ncmFiles, audioFiles);
            if (ncmFiles.isEmpty() && audioFiles.isEmpty()) continue;
            Log.info("扫描 " + abs + "：NCM " + ncmFiles.size() + "，音频 " + audioFiles.size());

            for (Path f : ncmFiles) {
                try {
                    NewFile dest = processNcm(f, library);
                    if (dest == null) {
                        skipped++;
                    } else {
                        ncm++;
                        newFiles.add(dest);
                        Log.info("转换完成: " + f.getFileName() + " -> " + library.relativize(dest.path()));
                    }
                } catch (Throwable e) {
                    failed++;
                    Log.error("转换失败（源文件已保留）: " + f.getFileName() + " -> " + e);
                }
            }
            if (cfg.moveAudio) {
                for (Path f : audioFiles) {
                    try {
                        NewFile dest = moveAudio(f, library);
                        if (dest != null) {
                            moved++;
                            newFiles.add(dest);
                            Log.info("归档: " + f.getFileName() + " -> " + library.relativize(dest.path()));
                        } else {
                            skipped++;
                        }
                    } catch (Throwable e) {
                        failed++;
                        Log.error("归档失败: " + f.getFileName() + " -> " + e);
                    }
                }
            }
            Organizer.cleanupEmptyDirs(abs);
        }

        if (cfg.fetchLyrics && !newFiles.isEmpty()) {
            for (NewFile nf : newFiles) {
                try {
                    if (nf.title() != null && !nf.title().isBlank()) {
                        // 优先用 NCM 元数据（比音频内嵌标签更贴合当前歌曲）
                        storeLyricsIfMissing(nf.path(), nf.title(), nf.artist(), nf.album());
                    } else {
                        fetchAndStoreLyrics(nf.path());
                    }
                } catch (Throwable e) {
                    Log.warn("歌词处理失败: " + nf.path().getFileName() + " -> " + e);
                }
            }
        }
        saveState();
        String summary = "本轮汇总：转换 " + ncm + "，归档 " + moved + "，跳过 " + skipped
                + "，失败 " + failed + "，新增文件 " + newFiles.size();
        Log.info(summary);
        if (manual && MainPlugin.toastAvailable()) {
            MainPlugin.toast(summary);
        }
    }

    // ---------------- NCM ----------------
    private NewFile processNcm(Path src, Path library) throws IOException {
        String sha = sha1(src);
        NcmDecoder.NcmInfo info = NcmDecoder.inspect(src);
        String ext = "." + info.format;
        String rel = Organizer.planRelative(info.title(), info.artist(), info.album(), ext);
        Path natural = library.resolve(rel);
        Path dir = natural.getParent();
        Files.createDirectories(dir);

        String base = natural.getFileName().toString();
        Path tempAudio = dir.resolve(base + ".part.audio");
        Path tempOut = dir.resolve(base + ".part");
        try {
            NcmDecoder.decryptTo(src, info, tempAudio);

            // 同一首歌已在库中（音频帧一致）→ 视为重复下载
            if (Files.isRegularFile(natural) && FlacCover.sameAudio(tempAudio, natural)) {
                Organizer.moveSidecar(src, natural);
                Files.deleteIfExists(src);
                processed.add(sha);
                return null;
            }

            boolean writeCover = "flac".equals(info.format) && info.cover.length > 2
                    && (info.cover[0] & 0xFF) == 0xFF && (info.cover[1] & 0xFF) == 0xD8
                    && !FlacCover.hasPicture(tempAudio);
            if (writeCover) {
                FlacCover.embed(tempAudio, tempOut, info.cover, info.album());
                // 校验：内嵌封面后音频帧必须与原解密结果逐字节一致
                if (!FlacCover.sameAudio(tempOut, tempAudio)) {
                    throw new IOException("输出校验失败，未删除源文件");
                }
            } else {
                Files.move(tempAudio, tempOut, StandardCopyOption.REPLACE_EXISTING);
            }

            Path dest = Files.exists(natural)
                    ? Organizer.safeTarget(dir, base)
                    : natural;
            Files.move(tempOut, dest, StandardCopyOption.REPLACE_EXISTING);
            Organizer.moveSidecar(src, dest);
            Files.deleteIfExists(src);
            processed.add(sha);
            return new NewFile(dest, info.title(), info.artist(), info.album());
        } finally {
            try {
                Files.deleteIfExists(tempAudio);
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(tempOut);
            } catch (IOException ignored) {
            }
        }
    }

    /** 用已知歌曲信息补歌词（优先路径） */
    private void storeLyricsIfMissing(Path audio, String title, String artist, String album) {
        if (!MainPlugin.lyricsOnlineEnabled()) return;
        Path lrc = Organizer.replaceExt(audio, ".lrc");
        if (Files.exists(lrc)) return;
        String lrcText = lyrics.getLyrics(title, artist, album);
        if (lrcText != null && !lrcText.isBlank() && MainPlugin.writeLrcEnabled()) {
            try {
                Files.writeString(lrc, lrcText, StandardCharsets.UTF_8);
                Log.info("已补歌词: " + audio.getFileName());
            } catch (IOException e) {
                Log.warn("写歌词失败: " + e.getMessage());
            }
        }
    }

    private void fetchAndStoreLyrics(Path audio) {
        if (!MainPlugin.lyricsOnlineEnabled()) return;
        Path lrc = Organizer.replaceExt(audio, ".lrc");
        if (Files.exists(lrc)) return;
        Organizer.Tags tags = Organizer.readTags(audio);
        String title = tags.title;
        if (title.isEmpty()) {
            String name = audio.getFileName().toString();
            int dot = name.lastIndexOf('.');
            name = dot > 0 ? name.substring(0, dot) : name;
            int sp = name.indexOf(" - ");
            if (sp > 0 && tags.artist.isEmpty()) {
                tags.artist = name.substring(0, sp);
                title = name.substring(sp + 3);
            } else {
                title = name;
            }
        }
        String lrcText = lyrics.getLyrics(title, tags.artist, tags.album);
        if (lrcText != null && !lrcText.isBlank() && MainPlugin.writeLrcEnabled()) {
            try {
                Files.writeString(lrc, lrcText, StandardCharsets.UTF_8);
                Log.info("已补歌词: " + audio.getFileName());
            } catch (IOException e) {
                Log.warn("写歌词失败: " + e.getMessage());
            }
        }
    }

    // ---------------- 已有音频 ----------------
    private NewFile moveAudio(Path src, Path library) throws IOException {
        String name = src.getFileName().toString().toLowerCase(Locale.ROOT);
        String ext = name.substring(name.lastIndexOf('.'));
        Organizer.Tags tags = Organizer.readTags(src);
        String stem = src.getFileName().toString();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        if (tags.title.isEmpty()) {
            int sp = stem.indexOf(" - ");
            if (sp > 0) {
                if (tags.artist.isEmpty()) tags.artist = stem.substring(0, sp);
                tags.title = stem.substring(sp + 3);
            } else {
                tags.title = stem;
            }
        }
        String rel = Organizer.planRelative(tags.title, tags.artist, tags.album, ext);
        Path natural = library.resolve(rel);

        if (Files.isRegularFile(natural) && FlacCover.sameAudio(src, natural)) {
            Files.deleteIfExists(src);
            return null;
        }
        Path dest = Organizer.safeTarget(natural.getParent(), natural.getFileName().toString());
        Files.createDirectories(dest.getParent());
        Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
        Organizer.moveSidecar(src, dest);
        return new NewFile(dest, null, null, null);
    }

    // ---------------- 自动识别下载目录 ----------------
    public List<Path> autoDetectSources() {
        List<Path> cached = cachedSources;
        long at = cachedAt;
        if (!cached.isEmpty() && System.currentTimeMillis() - at < DETECT_CACHE_MS) {
            return cached;
        }
        List<Path> dirs = new ArrayList<>();
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            Path db = Paths.get(local, "NetEase", "CloudMusic", "Library", "webdb.dat");
            if (Files.isRegularFile(db)) {
                dirs.addAll(scanWebdb(db));
            }
        }
        String home = System.getProperty("user.home", "");
        String[] candidates = {
                home + "\\Music\\CloudMusic",
                home + "\\Music\\网易云音乐",
                home + "\\Desktop\\CloudMusic",
                "D:\\CloudMusic", "C:\\CloudMusic", "E:\\CloudMusic"
        };
        for (String c : candidates) {
            Path p = Paths.get(c);
            if (Files.isDirectory(p)) dirs.add(p.toAbsolutePath().normalize());
        }
        List<Path> result = collapse(dirs);
        cachedSources = result;
        cachedAt = System.currentTimeMillis();
        return result;
    }

    /** 流式扫描 webdb.dat 中记录的 .ncm 路径（文件可能 200MB+，绝不能整体读入） */
    private static List<Path> scanWebdb(Path db) {
        List<Path> dirs = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(db), 1 << 20)) {
            byte[] buf = new byte[1 << 20];
            byte[] carry = new byte[0];
            int n;
            while ((n = in.read(buf)) > 0) {
                byte[] data = new byte[carry.length + n];
                System.arraycopy(carry, 0, data, 0, carry.length);
                System.arraycopy(buf, 0, data, carry.length, n);
                String text = new String(data, StandardCharsets.ISO_8859_1);
                Matcher m = WEBDB_FILENAME.matcher(text);
                while (m.find()) {
                    String raw = m.group(1);
                    byte[] rb = new byte[raw.length()];
                    for (int i = 0; i < raw.length(); i++) rb[i] = (byte) raw.charAt(i);
                    String pathText = new String(rb, StandardCharsets.UTF_8);
                    String path;
                    try {
                        path = Json.unescape("\"" + pathText + "\"");
                    } catch (Exception e) {
                        path = pathText;
                    }
                    if (!path.toLowerCase(Locale.ROOT).endsWith(".ncm")) continue;
                    try {
                        Path parent = Paths.get(path).getParent();
                        if (parent != null && Files.isDirectory(parent)
                                && !dirs.contains(parent.toAbsolutePath().normalize())) {
                            dirs.add(parent.toAbsolutePath().normalize());
                        }
                    } catch (Exception ignored) {
                    }
                }
                int keep = Math.min(data.length, 65536);
                carry = Arrays.copyOfRange(data, data.length - keep, data.length);
            }
        } catch (Exception e) {
            Log.warn("读取网易云下载记录失败: " + e.getMessage());
        }
        return dirs;
    }

    /** 折叠嵌套目录，只保留父级 */
    public static List<Path> collapse(List<Path> dirs) {
        List<Path> sorted = new ArrayList<>(dirs);
        sorted.sort((a, b) -> a.getNameCount() - b.getNameCount());
        List<Path> out = new ArrayList<>();
        for (Path d : sorted) {
            boolean covered = false;
            for (Path k : out) {
                if (d.equals(k) || d.startsWith(k)) {
                    covered = true;
                    break;
                }
            }
            if (!covered && !out.contains(d)) out.add(d);
        }
        return out;
    }

    // ---------------- 工具 ----------------
    private static void scan(Path root, List<Path> ncm, List<Path> audio) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                if (".ncm".equals(ext)) {
                    ncm.add(p);
                } else if (AUDIO_EXT.contains(ext)) {
                    audio.add(p);
                } else if (!".txt".equals(ext) && !".ini".equals(ext) && !".jpg".equals(ext)
                        && !".png".equals(ext) && !".db".equals(ext) && !".dat".equals(ext)) {
                    try {
                        if (Files.size(p) > 100 * 1024) {
                            byte[] head = new byte[8];
                            try (InputStream in = Files.newInputStream(p)) {
                                int n = in.read(head);
                                if (n == 8 && NcmDecoder.isNcm(head)) ncm.add(p);
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            });
        } catch (IOException e) {
            Log.warn("遍历目录失败: " + root + " -> " + e.getMessage());
        }
    }

    private static String sha1(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return file.toString();
        }
    }

    private void loadState() {
        try {
            if (Files.isRegularFile(stateFile)) {
                for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
                    String s = line.strip();
                    if (!s.isEmpty()) processed.add(s);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void saveState() {
        try {
            Files.createDirectories(dataDir);
            Files.write(stateFile, new ArrayList<>(processed), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
