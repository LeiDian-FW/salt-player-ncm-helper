package io.github.spwncm.ncmhelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 网易云下载：把歌曲流式下载到音乐库（按 歌手/专辑 归档），写基础标签并记录到转换记录。
 * 无版权 / 需要 VIP 的歌曲会跳过并记录原因，不做任何绕过。
 */
public final class NeteaseDownloader {

    public interface Progress {
        void log(String line);

        void done(String summary);
    }

    private final NeteaseClient client;
    private final Path library;
    private final History history;

    public NeteaseDownloader(NeteaseClient client, Path library, History history) {
        this.client = client;
        this.library = library;
        this.history = history;
    }

    /** 下载列表中的歌曲，最多 limit 首；返回 (成功, 跳过, 失败) */
    public int[] download(List<NeteaseClient.Song> songs, int limit, boolean writeTags,
                          Progress progress) {
        int ok = 0;
        int skip = 0;
        int fail = 0;
        int total = Math.min(Math.max(1, limit), songs.size());
        for (int i = 0; i < total; i++) {
            NeteaseClient.Song s = songs.get(i);
            String label = s.name + " - " + s.artistText();
            try {
                String url = client.songUrl(s.id, 999000);
                if (url == null || url.isBlank()) {
                    skip++;
                    record("跳过", s, null, "无版权或需要 VIP/付费，无法下载");
                    progress.log("跳过（无版权/需 VIP）: " + label);
                    sleep(250);
                    continue;
                }
                String ext = NeteaseClient.guessExt(url);
                String rel = Organizer.planRelative(s.name, s.artistText(), s.album, ext);
                Path dest = Organizer.safeTarget(library.resolve(rel).getParent(),
                        library.resolve(rel).getFileName().toString());
                if (Files.exists(dest)) {
                    skip++;
                    record("跳过", s, dest, "文件已存在");
                    progress.log("跳过（已存在）: " + label);
                    sleep(150);
                    continue;
                }
                long bytes = client.download(url, dest);
                if (writeTags) TagWriter.write(dest, s.name, s.artistText(), s.album);
                ok++;
                record("成功", s, dest, "网易云下载 " + (bytes / 1024) + " KB");
                progress.log("下载完成: " + label + " -> " + rel);
                sleep(350);
            } catch (Exception e) {
                fail++;
                record("失败", s, null, String.valueOf(e));
                progress.log("下载失败: " + label + " -> " + e);
                sleep(350);
            }
        }
        String summary = "网易云下载完成：成功 " + ok + "，跳过 " + skip + "，失败 " + fail;
        progress.log(summary);
        progress.done(summary);
        return new int[]{ok, skip, fail};
    }

    private void record(String status, NeteaseClient.Song s, Path dest, String message) {
        if (history == null) return;
        long size = 0L;
        String fmt = "";
        if (dest != null) {
            try {
                size = Files.size(dest);
            } catch (Exception ignored) {
            }
            String name = dest.getFileName().toString();
            int dot = name.lastIndexOf('.');
            fmt = dot > 0 ? name.substring(dot + 1) : "";
        }
        history.add(new History.Record(System.currentTimeMillis(), status, s.name,
                s.artistText(), dest == null ? "" : dest.toString(), fmt, size, message));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
