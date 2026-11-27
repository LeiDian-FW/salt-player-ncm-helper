import io.github.spwncm.ncmhelper.History;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** 转换记录：保存、重新加载、超期清理、清空。 */
public class TestHistory {
    public static void main(String[] args) throws Exception {
        long day = 24L * 3600 * 1000;
        long now = System.currentTimeMillis();
        Path dir = Path.of(args[0]).resolve("history-test");
        deleteTree(dir);
        Files.createDirectories(dir);

        History h = new History(dir, 30);
        if (h.count() != 0) throw new AssertionError("初始应为空");
        h.add(new History.Record(now, "成功", "歌曲A", "歌手A", "D:/music/A.flac", "flac", 1234, ""));
        h.add(new History.Record(now - 10 * day, "跳过", "歌曲B", "", "", "", 0, "重复下载"));
        if (h.count() != 2) throw new AssertionError("应写入 2 条，实际 " + h.count());

        History h2 = new History(dir, 30);
        if (h2.count() != 2) throw new AssertionError("重新加载应保留 2 条，实际 " + h2.count());
        if (!h2.recent(1).get(0).title.equals("歌曲B")) throw new AssertionError("最近一条应为歌曲B");

        h2.setRetainDays(5);          // 10 天前的记录应被清理
        if (h2.count() != 1) throw new AssertionError("超期清理后应剩 1 条，实际 " + h2.count());

        History h3 = new History(dir, 5);
        if (h3.count() != 1) throw new AssertionError("清理结果应已持久化");
        h3.add(new History.Record(now, "失败", "歌曲C", "", "", "", 0, "密钥不匹配"));
        if (new History(dir, 5).count() != 2) throw new AssertionError("追加记录未持久化");

        h3.clear();
        if (new History(dir, 5).count() != 0) throw new AssertionError("清空未生效");

        System.out.println("转换记录：保存 / 重新加载 / 超期自动清理 / 清空 全部正常");
        System.out.println("全部通过");
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
