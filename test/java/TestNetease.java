import io.github.spwncm.ncmhelper.NeteaseClient;
import io.github.spwncm.ncmhelper.Organizer;
import io.github.spwncm.ncmhelper.QrCode;
import io.github.spwncm.ncmhelper.TagWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 网易云模块离线自测：二维码生成、扩展名推断、标签写入与读回。 */
public class TestNetease {
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]).resolve("netease-test");
        Files.createDirectories(dir);

        System.out.println("[1] 二维码生成");
        Path png = dir.resolve("qr.png");
        QrCode.writePng("https://music.163.com/login?codekey=TEST-KEY", png, 420);
        byte[] head = new byte[8];
        try (var in = Files.newInputStream(png)) {
            if (in.read(head) != 8) throw new AssertionError("PNG 过短");
        }
        if (!(head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G')) {
            throw new AssertionError("不是合法 PNG");
        }
        if (Files.size(png) < 300) throw new AssertionError("PNG 体积异常: " + Files.size(png));
        System.out.println("    ✓ 二维码 PNG: " + Files.size(png) + " 字节");

        System.out.println("[2] 下载扩展名推断");
        if (!".flac".equals(NeteaseClient.guessExt("http://x/y.flac?auth=1"))) throw new AssertionError("flac");
        if (!".mp3".equals(NeteaseClient.guessExt("http://x/y.mp3?auth=1"))) throw new AssertionError("mp3");
        if (!".mp3".equals(NeteaseClient.guessExt("http://x/stream"))) throw new AssertionError("默认");
        System.out.println("    ✓ 扩展名推断正常");

        System.out.println("[3] FLAC 标签写入/读回");
        Path flac = dir.resolve("sample.flac");
        byte[] si = new byte[34];
        si[0] = 0x10; si[1] = 0x00; si[2] = 0x10; si[3] = 0x00;
        java.io.ByteArrayOutputStream flacBytes = new java.io.ByteArrayOutputStream();
        flacBytes.write(new byte[]{'f','L','a','C'});
        flacBytes.write(0x80);              // last + STREAMINFO
        flacBytes.write(0); flacBytes.write(0); flacBytes.write(34);
        flacBytes.write(si);
        flacBytes.write(new byte[]{(byte)0xFF, (byte)0xF8, 0x69, 0x18, 0x00});
        flacBytes.write(new byte[4096]);
        Files.write(flac, flacBytes.toByteArray());
        TagWriter.write(flac, "测试标题", "测试歌手", "测试专辑");
        Organizer.Tags tags = Organizer.readTags(flac);
        if (!"测试标题".equals(tags.title) || !"测试歌手".equals(tags.artist)
                || !"测试专辑".equals(tags.album)) {
            throw new AssertionError("FLAC 标签读回异常: " + tags.title + "/" + tags.artist + "/" + tags.album);
        }
        System.out.println("    ✓ FLAC 标签: " + tags.title + " / " + tags.artist + " / " + tags.album);

        System.out.println("[4] MP3 标签写入/读回");
        Path mp3 = dir.resolve("sample.mp3");
        java.io.ByteArrayOutputStream mp3Bytes = new java.io.ByteArrayOutputStream();
        mp3Bytes.write(new byte[]{(byte)0xFF, (byte)0xFB, (byte)0x90, 0x00});
        mp3Bytes.write(new byte[4096]);
        Files.write(mp3, mp3Bytes.toByteArray());
        TagWriter.write(mp3, "MP3标题", "MP3歌手", "MP3专辑");
        Organizer.Tags mt = Organizer.readTags(mp3);
        if (!"MP3标题".equals(mt.title) || !"MP3歌手".equals(mt.artist)) {
            throw new AssertionError("MP3 标签读回异常: " + mt.title + "/" + mt.artist);
        }
        System.out.println("    ✓ MP3 标签: " + mt.title + " / " + mt.artist + " / " + mt.album);

        System.out.println("\n全部通过");
    }
}
