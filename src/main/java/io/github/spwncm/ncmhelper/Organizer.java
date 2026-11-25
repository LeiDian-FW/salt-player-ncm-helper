package io.github.spwncm.ncmhelper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 音乐库归档：命名清洗、歌手/专辑目录规划、歌词跟随、标签读取。
 */
public final class Organizer {

    public static final String UNKNOWN_ARTIST = "未知歌手";
    public static final String UNKNOWN_ALBUM = "未知专辑";
    private static final String BAD = "<>:\"/\\|?*";

    public static String sanitize(String name, String fallback, int maxLen) {
        if (name == null || name.isBlank()) return fallback;
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (BAD.indexOf(c) >= 0) continue;
            if (c < 0x20) continue;
            sb.append(c);
        }
        String out = sb.toString().strip();
        while (out.endsWith(".")) out = out.substring(0, out.length() - 1).strip();
        out = out.replaceAll("\\s+", " ");
        if (out.length() > maxLen) out = out.substring(0, maxLen).strip();
        return out.isEmpty() ? fallback : out;
    }

    /** 返回库内相对路径：歌手/专辑/歌手 - 歌名.ext */
    public static String planRelative(String title, String artist, String album, String ext) {
        String t = title == null ? "" : title.strip();
        String a = artist == null ? "" : artist.strip();
        String al = album == null ? "" : album.strip();
        if (t.isEmpty()) t = "未知标题";
        if (a.isEmpty()) a = UNKNOWN_ARTIST;
        if (al.isEmpty()) al = UNKNOWN_ALBUM;

        // 目录取第一个歌手（日文「、」可能属于组合名内部，不作分隔）
        String primary = a.split("\\s*[,/;]\\s*")[0].strip();
        if (primary.isEmpty()) primary = UNKNOWN_ARTIST;
        String fullArtist = a.replaceAll("\\s*/\\s*", ",");

        String artistDir = sanitize(primary, UNKNOWN_ARTIST, 80);
        String albumDir = sanitize(al, UNKNOWN_ALBUM, 90);
        String fileName = sanitize(fullArtist + " - " + t, sanitize(t, "track", 80), 120) + ext;
        return artistDir + "/" + albumDir + "/" + fileName;
    }

    /** 同名不覆盖：返回一个当前不存在的目标路径 */
    public static Path safeTarget(Path dir, String fileName) {
        Path dest = dir.resolve(fileName);
        if (!Files.exists(dest)) return dest;
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        int n = 2;
        while (true) {
            Path cand = dir.resolve(base + " (" + n + ")" + ext);
            if (!Files.exists(cand)) return cand;
            n++;
        }
    }

    /** 同名 .lrc 跟随音频移动 */
    public static void moveSidecar(Path srcAudio, Path destAudio) {
        Path side = replaceExt(srcAudio, ".lrc");
        if (!Files.isRegularFile(side)) return;
        Path lrcDest = replaceExt(destAudio, ".lrc");
        try {
            if (Files.exists(lrcDest)) {
                Files.deleteIfExists(side);
            } else {
                Files.move(side, lrcDest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    public static Path replaceExt(Path p, String ext) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return p.resolveSibling(base + ext);
    }

    // ---------------- 标签读取 ----------------
    public static final class Tags {
        public String title = "";
        public String artist = "";
        public String album = "";
    }

    public static Tags readTags(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        try {
            if (name.endsWith(".flac")) return readFlac(file);
            if (name.endsWith(".mp3")) return readMp3(file);
        } catch (Exception ignored) {
        }
        return new Tags();
    }

    private static Tags readFlac(Path file) throws IOException {
        Tags tags = new Tags();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] magic = new byte[4];
            if (raf.length() < 4) return tags;
            raf.readFully(magic);
            if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') return tags;
            long pos = 4;
            while (pos + 4 <= raf.length()) {
                raf.seek(pos);
                int hdr = raf.read();
                if (hdr < 0 || hdr == 0xFF) break;
                boolean last = (hdr & 0x80) != 0;
                int type = hdr & 0x7F;
                int len = ((raf.read() & 0xFF) << 16) | ((raf.read() & 0xFF) << 8) | (raf.read() & 0xFF);
                if (len < 0) break;
                if (type == 4 && len <= 4 * 1024 * 1024) {
                    byte[] body = new byte[len];
                    raf.readFully(body);
                    parseVorbis(body, 0, len, tags);
                } else if (type == 4) {
                    break;
                }
                pos += 4 + len;
                if (last) break;
            }
        }
        return tags;
    }

    private static void parseVorbis(byte[] d, int off, int len, Tags tags) {
        try {
            int vendorLen = le32(d, off);
            int p = off + 4 + vendorLen;
            int count = le32(d, p);
            p += 4;
            for (int k = 0; k < count && p + 4 <= off + len; k++) {
                int itemLen = le32(d, p);
                p += 4;
                if (p + itemLen > off + len) break;
                String item = new String(d, p, itemLen, StandardCharsets.UTF_8);
                p += itemLen;
                int eq = item.indexOf('=');
                if (eq <= 0) continue;
                String key = item.substring(0, eq).toUpperCase();
                String val = item.substring(eq + 1);
                switch (key) {
                    case "TITLE" -> tags.title = val;
                    case "ARTIST" -> tags.artist = val;
                    case "ALBUMARTIST" -> {
                        if (tags.artist.isEmpty()) tags.artist = val;
                    }
                    case "ALBUM" -> tags.album = val;
                    default -> {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Tags readMp3(Path file) throws IOException {
        Tags tags = new Tags();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < 10) return tags;
            byte[] head = new byte[10];
            raf.readFully(head);
            if (head[0] != 'I' || head[1] != 'D' || head[2] != '3') return tags;
            int ver = head[3] & 0xFF;
            int size = ((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14)
                    | ((head[8] & 0x7F) << 7) | (head[9] & 0x7F);
            long end = Math.min(raf.length(), 10L + size);
            long p = 10;
            byte[] fh = new byte[10];
            while (p + 10 <= end) {
                raf.seek(p);
                raf.readFully(fh);
                String id = new String(fh, 0, 4, StandardCharsets.US_ASCII);
                if (!id.matches("[A-Z0-9]{4}")) break;
                int fsize = (ver == 4)
                        ? ((fh[4] & 0x7F) << 21) | ((fh[5] & 0x7F) << 14) | ((fh[6] & 0x7F) << 7) | (fh[7] & 0x7F)
                        : ((fh[4] & 0xFF) << 24) | ((fh[5] & 0xFF) << 16) | ((fh[6] & 0xFF) << 8) | (fh[7] & 0xFF);
                if (fsize < 0 || p + 10 + fsize > end) break;
                boolean needText = id.equals("TIT2") || id.equals("TPE1")
                        || id.equals("TALB") || id.equals("TPE2");
                if (needText && fsize <= 1024 * 1024) {
                    byte[] body = new byte[fsize];
                    raf.readFully(body);
                    String val = decodeText(body, 0, fsize);
                    switch (id) {
                        case "TIT2" -> tags.title = val;
                        case "TPE1" -> tags.artist = val;
                        case "TALB" -> tags.album = val;
                        case "TPE2" -> {
                            if (tags.artist.isEmpty()) tags.artist = val;
                        }
                        default -> {
                        }
                    }
                }
                p += 10 + fsize;
            }
        }
        return tags;
    }

    private static String decodeText(byte[] d, int off, int len) {
        if (len <= 0) return "";
        try {
            int enc = d[off] & 0xFF;
            if (enc == 1) return new String(d, off + 1, len - 1, "UTF-16").replace("\u0000", "").strip();
            if (enc == 2) return new String(d, off + 1, len - 1, "UTF-16BE").replace("\u0000", "").strip();
            String cs = enc == 3 ? "UTF-8" : "ISO-8859-1";
            return new String(d, off + 1, len - 1, cs).replace("\u0000", "").strip();
        } catch (Exception e) {
            return "";
        }
    }

    private static int le32(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8) | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    /** 清理空目录（保留根目录） */
    public static int cleanupEmptyDirs(Path root) {
        int[] removed = new int[]{0};
        try (var stream = Files.walk(root)) {
            List<Path> dirs = new ArrayList<>();
            stream.filter(Files::isDirectory).forEach(dirs::add);
            dirs.sort((a, b) -> b.getNameCount() - a.getNameCount());
            for (Path d : dirs) {
                if (d.equals(root)) continue;
                try (var children = Files.list(d)) {
                    if (children.findAny().isEmpty()) {
                        Files.delete(d);
                        removed[0]++;
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return removed[0];
    }
}
