package io.github.spwncm.ncmhelper;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.pf4j.Extension;

/**
 * 歌词扩展点：当播放器自己找不到歌词时，按歌名/歌手在线匹配网易云歌词。
 * 返回 LRC 文本即可让 Salt Player 显示同步歌词。
 */
@Extension
public class NcmLyricsExtension implements PlaybackExtensionPoint {

    @Override
    public String onAfterLoadLyrics(MediaItem mediaItem) {
        try {
            if (mediaItem == null || !MainPlugin.lyricsOnlineEnabled()) return null;
            String title = mediaItem.getTitle();
            if (title == null || title.isBlank()) return null;
            NeteaseLyrics service = MainPlugin.getLyricsService();
            if (service == null) return null;

            String lrc = service.getLyrics(title, mediaItem.getArtist(), mediaItem.getAlbum());
            if (lrc == null || lrc.isBlank()) return null;

            // 顺手落盘为 .lrc，下次播放器自己就能读到
            if (MainPlugin.writeLrcEnabled()) {
                String path = mediaItem.getPath();
                if (path != null && !path.isBlank()) {
                    try {
                        Path audio = Paths.get(path);
                        if (Files.isRegularFile(audio)) {
                            Path lrcPath = Organizer.replaceExt(audio, ".lrc");
                            if (!Files.exists(lrcPath)) {
                                Files.writeString(lrcPath, lrc, StandardCharsets.UTF_8);
                                Log.info("歌词已保存: " + lrcPath.getFileName());
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            return lrc;
        } catch (Throwable e) {
            Log.warn("歌词扩展异常: " + e);
            return null;
        }
    }
}
