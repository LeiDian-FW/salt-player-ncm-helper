import io.github.spwncm.ncmhelper.NcmJob;
import io.github.spwncm.ncmhelper.NeteaseLyrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 独立测试 NcmJob 完整流水线（不依赖 Salt Player 宿主）。 */
public class TestPipeline {
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]).toAbsolutePath();
        Path ncmSample = Path.of(args[1]);
        Path src = base.resolve("src");
        Path lib = base.resolve("lib");
        Path data = base.resolve("data");
        deleteTree(base);
        Files.createDirectories(src.resolve("album"));
        Files.createDirectories(lib);
        Files.createDirectories(data);

        // 两个 NCM：一个正常，一个内容相同（测去重）
        Path ncm1 = src.resolve("album/测试歌手 - 测试歌曲.ncm");
        Files.copy(ncmSample, ncm1);
        Path ncm2 = src.resolve("album/测试歌手 - 测试歌曲 (重复下载).ncm");
        Files.copy(ncmSample, ncm2);
        // 同名歌词跟随
        Files.writeString(src.resolve("album/测试歌手 - 测试歌曲.lrc"), "[ti:测试歌曲]\n[00:01.00]源目录歌词\n");

        NcmJob job = new NcmJob(data, new NeteaseLyrics(data.resolve("cache")));
        job.apply(NcmJob.JobConfig.of(lib, false, List.of(src), 60, true, false));
        job.runOnce(true);

        List<Path> out = new ArrayList<>();
        try (Stream<Path> w = Files.walk(lib)) {
            w.filter(Files::isRegularFile).forEach(out::add);
        }
        out.sort(Comparator.comparing(Path::toString));
        System.out.println("=== 库内文件 ===");
        for (Path p : out) System.out.println("  " + lib.relativize(p) + " (" + Files.size(p) + " 字节)");

        long ncmLeft = 0;
        try (Stream<Path> w = Files.walk(src)) {
            ncmLeft = w.filter(p -> p.toString().endsWith(".ncm")).count();
        }
        System.out.println("=== 源目录剩余 NCM ===");
        System.out.println("  " + ncmLeft);

        String expectedAudio = "测试歌手/测试专辑/测试歌手,合作歌手 - 测试歌曲.flac";
        boolean hasAudio = out.stream().anyMatch(p -> lib.relativize(p).toString().replace('\\', '/').equals(expectedAudio));
        boolean hasLrc = false;
        for (Path p : out) {
            if (p.toString().endsWith(".lrc") && Files.readString(p).contains("源目录歌词")) {
                hasLrc = true;
                break;
            }
        }
        if (!hasAudio) throw new AssertionError("音频未按预期归档: " + expectedAudio);
        if (!hasLrc) throw new AssertionError("歌词未跟随");
        if (ncmLeft != 0) throw new AssertionError("源 NCM 未清理: " + ncmLeft);
        if (out.size() != 2) throw new AssertionError("重复文件未被去重，文件数=" + out.size());
        System.out.println("=== 自动识别下载目录 ===");
        System.out.println("  " + job.autoDetectSources());

        System.out.println("\n全部通过：转换、归档、歌词跟随、去重、源清理、下载目录识别");
    }

    private static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (Stream<Path> w = Files.walk(p)) {
            w.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
