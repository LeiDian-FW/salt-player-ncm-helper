import io.github.spwncm.ncmhelper.NeteaseLyrics;
import java.nio.file.Path;

public class TestLyrics {
    public static void main(String[] args) throws Exception {
        NeteaseLyrics svc = new NeteaseLyrics(Path.of(System.getProperty("java.io.tmpdir"), "ncm-helper-test-cache"));
        String[][] cases = {
                {"unravel", "Ado", "unravel"},
                {"天ノ弱", "164/GUMI", "EXIT TUNES PRESENTS GUMitive from Megpoid"},
                {"不存在的歌名xyzabc", "无名", "无"},
        };
        for (String[] c : cases) {
            long t0 = System.currentTimeMillis();
            String lrc = svc.getLyrics(c[0], c[1], c[2]);
            long ms = System.currentTimeMillis() - t0;
            System.out.println("--- " + c[0] + " / " + c[1] + " (" + ms + "ms)");
            if (lrc == null) {
                System.out.println("    (无歌词)");
            } else {
                String[] lines = lrc.split("\\R");
                System.out.println("    行数=" + lines.length);
                for (int i = 0; i < Math.min(6, lines.length); i++) System.out.println("    " + lines[i]);
            }
        }
    }
}
