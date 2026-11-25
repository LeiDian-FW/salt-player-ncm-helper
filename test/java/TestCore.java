import io.github.spwncm.ncmhelper.FlacCover;
import io.github.spwncm.ncmhelper.NcmDecoder;
import io.github.spwncm.ncmhelper.Organizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 核心逻辑自测：流式解密、封面内嵌、命名、标签读取。
 * 用法： TestCore <sample.ncm> <sample.flac>
 */
public class TestCore {
    public static void main(String[] args) throws Exception {
        Path ncm = Path.of(args[0]);
        Path sourceFlac = Path.of(args[1]);

        System.out.println("[1] 流式解密 NCM: " + ncm.getFileName()
                + " (" + Files.size(ncm) + " 字节)");
        NcmDecoder.NcmInfo info = NcmDecoder.inspect(ncm);
        System.out.println("    格式=" + info.format
                + " 标题=" + info.title() + " 歌手=" + info.artist() + " 专辑=" + info.album()
                + " 封面=" + info.cover.length + " 字节");
        Path tmpDir = Paths.get("test", "tmp");
        Files.createDirectories(tmpDir);
        Path decrypted = Files.createTempFile(tmpDir, "ncm-test", ".flac");
        NcmDecoder.decryptTo(ncm, info, decrypted);
        if (Files.mismatch(decrypted, sourceFlac) != -1) {
            throw new AssertionError("解密音频与源 FLAC 不一致");
        }
        System.out.println("    ✓ 解密结果与源 FLAC 逐字节一致（流式，峰值内存与文件大小无关）");

        System.out.println("[2] 内嵌封面");
        boolean hadCover = FlacCover.hasPicture(decrypted);
        Path withCover = Files.createTempFile(tmpDir, "ncm-test-cover", ".flac");
        FlacCover.embed(decrypted, withCover, info.cover, info.album());
        if (!FlacCover.sameAudio(withCover, decrypted)) {
            throw new AssertionError("内嵌后音频帧被破坏");
        }
        if (!hadCover && !FlacCover.hasPicture(withCover)) {
            throw new AssertionError("封面未写入");
        }
        System.out.println("    " + (hadCover ? "源文件已有封面，保持不变" : "封面写入成功")
                + "，音频帧未变，文件 " + Files.size(withCover) + " 字节");
        Files.deleteIfExists(decrypted);
        Files.deleteIfExists(withCover);

        System.out.println("[3] 归档路径");
        String rel = Organizer.planRelative(info.title(), info.artist(), info.album(), "." + info.format);
        System.out.println("    相对路径: " + rel);
        String bad = Organizer.planRelative("歌:名<测试>", "A/B", "专辑?", ".flac");
        System.out.println("    含非法字符: " + bad);
        if (bad.contains(":") || bad.contains("<") || bad.contains(">") || bad.contains("?")) {
            throw new AssertionError("非法字符未被清洗");
        }
        System.out.println("    ✓ 命名清洗正常");

        System.out.println("[4] 标签读取（流式）");
        Organizer.Tags tags = Organizer.readTags(sourceFlac);
        System.out.println("    标签: " + tags.title + " / " + tags.artist + " / " + tags.album);
        if (!tags.title.equals("测试歌曲") || !tags.artist.contains("测试歌手")) {
            throw new AssertionError("FLAC 标签读取异常: " + tags.title);
        }
        System.out.println("    ✓ 标签读取正常");

        System.out.println("\n全部通过");
    }
}
