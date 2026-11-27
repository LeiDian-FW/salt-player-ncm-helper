package io.github.spwncm.ncmhelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 网易云下载：把歌曲流式下载到音乐库（按 歌手/专辑 归档），写基础标签并记录到转换记录。
 * 无版权 / 需要 VIP 的歌曲会跳过并记录原因，不做任何绕过。
 */
public final class NeteaseDownloader {

    /** 下载进度回调（UI 用），全部在下载线程调用 */
    public interface Listener {
        void onStart(int total);

        void onSong(int index, int total, NeteaseClient.Song song);

        void onBytes(long done, long fileTotal);

        void onResult(String status, String message);

        void onFinish(String summary);
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
                          Listener listener) {
        int ok = 0;
        int skip = 0;
        int fail = 0;
        boolean cancelled = false;
        int total = Math.min(Math.max(1, limit), songs.size());
        if (listener != null) listener.onStart(total);
        for (int i = 0; i < total; i++) {
            if (isCancelled(listener)) {
                cancelled = true;
                break;
            }
            NeteaseClient.Song s = songs.get(i);
            int index = i + 1;
            if (listener != null) listener.onSong(index, total, s);
            String label = s.name + " - " + s.artistText();
            try {
                String url = client.songUrl(s.id, 999000);
                if (url == null || url.isBlank()) {
                    skip++;
                    record("跳过", s, null, "无版权或需要 VIP/付费，无法下载");
                    Log.info("跳过（无版权/需 VIP）: " + label);
                    if (listener != null) listener.onResult("跳过", "无版权/需 VIP");
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
                    Log.info("跳过（已存在）: " + label);
                    if (listener != null) listener.onResult("跳过", "文件已存在");
                    sleep(150);
                    continue;
                }
                final long[] last = {0};
                long bytes = client.download(url, dest,
                        (done, fileTotal) -> {
                            last[0] = done;
                            if (listener != null) listener.onBytes(done, fileTotal);
                        },
                        () -> isCancelled(listener));
                if (writeTags) TagWriter.write(dest, s.name, s.artistText(), s.album);
                ok++;
                record("成功", s, dest, "网易云下载 " + (bytes / 1024) + " KB");
                Log.info("下载完成: " + label + " -> " + rel);
                if (listener != null) listener.onResult("成功", rel);
                sleep(350);
            } catch (NeteaseClient.DownloadCancelled e) {
                cancelled = true;
                break;
            } catch (Exception e) {
                fail++;
                record("失败", s, null, String.valueOf(e));
                Log.warn("下载失败: " + label + " -> " + e);
                if (listener != null) listener.onResult("失败", String.valueOf(e));
                sleep(350);
            }
        }
        String summary = cancelled
                ? "已停止：成功 " + ok + "，跳过 " + skip + "，失败 " + fail
                : "网易云下载完成：成功 " + ok + "，跳过 " + skip + "，失败 " + fail;
        Log.info(summary);
        if (listener != null) listener.onFinish(summary);
        return new int[]{ok, skip, fail};
    }

    private static boolean isCancelled(Listener listener) {
        return listener instanceof Cancellable c && c.isCancelled();
    }

    /** 可选取消接口 */
    public interface Cancellable {
        boolean isCancelled();
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
