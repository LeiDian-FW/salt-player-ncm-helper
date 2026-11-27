package io.github.spwncm.ncmhelper;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 给下载的音频写基础标签（标题/艺术家/专辑）。
 * 支持 FLAC（Vorbis Comment）与 MP3（ID3v2.4）；其他格式跳过。
 */
public final class TagWriter {

    private TagWriter() {
    }

    public static void write(Path audio, String title, String artist, String album) {
        try {
            byte[] head = new byte[4];
            try (var in = Files.newInputStream(audio)) {
                if (in.read(head) < 4) return;
            }
            if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
                writeFlac(audio, title, artist, album);
            } else if (head[0] == 'I' && head[1] == 'D' && head[2] == '3'
                    || (head[0] & 0xFF) == 0xFF) {
                writeMp3(audio, title, artist, album);
            }
        } catch (Exception e) {
            Log.warn("写标签失败: " + audio.getFileName() + " -> " + e);
        }
    }

    // ---------------- FLAC ----------------
    private static final class Block {
        int type;
        long start;
        int len;
        boolean last;
    }

    private static void writeFlac(Path file, String title, String artist, String album) throws IOException {
        long audioOffset = FlacCover.audioOffset(file);
        if (audioOffset < 0) return;
        List<Block> blocks = new ArrayList<>();
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long pos = 4;
            byte[] hdr = new byte[4];
            while (pos < audioOffset) {
                in.seek(pos);
                in.readFully(hdr);
                Block b = new Block();
                b.last = (hdr[0] & 0x80) != 0;
                b.type = hdr[0] & 0x7F;
                b.len = ((hdr[1] & 0xFF) << 16) | ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);
                b.start = pos + 4;
                blocks.add(b);
                pos += 4 + b.len;
            }
        }
        if (blocks.isEmpty()) return;
        boolean hasComment = blocks.stream().anyMatch(b -> b.type == 4);
        byte[] comment = buildVorbis(title, artist, album);

        Path tmp = file.resolveSibling(file.getFileName() + ".tagtmp");
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r");
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
            out.write('f');
            out.write('L');
            out.write('a');
            out.write('C');
            byte[] buf = new byte[1 << 16];
            for (int i = 0; i < blocks.size(); i++) {
                Block b = blocks.get(i);
                boolean lastFlag = b.last;
                if (!hasComment && i == blocks.size() - 1) lastFlag = false;
                byte[] body = null;
                if (b.type == 4) body = comment;
                out.write((lastFlag ? 0x80 : 0) | b.type);
                int len = body != null ? body.length : b.len;
                out.write((len >> 16) & 0xFF);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
                if (body != null) {
                    out.write(body);
                } else {
                    in.seek(b.start);
                    int remain = b.len;
                    while (remain > 0) {
                        int n = in.read(buf, 0, Math.min(buf.length, remain));
                        if (n < 0) throw new IOException("FLAC 数据异常");
                        out.write(buf, 0, n);
                        remain -= n;
                    }
                }
            }
            if (!hasComment) {
                out.write(0x80 | 4);
                out.write((comment.length >> 16) & 0xFF);
                out.write((comment.length >> 8) & 0xFF);
                out.write(comment.length & 0xFF);
                out.write(comment);
            }
            in.seek(audioOffset);
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] buildVorbis(String title, String artist, String album) throws IOException {
        String vendor = "ncm-helper";
        List<String[]> tags = new ArrayList<>();
        if (title != null && !title.isBlank()) tags.add(new String[]{"TITLE", title});
        if (artist != null && !artist.isBlank()) tags.add(new String[]{"ARTIST", artist});
        if (album != null && !album.isBlank()) tags.add(new String[]{"ALBUM", album});
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] v = vendor.getBytes(StandardCharsets.UTF_8);
        writeLe(out, v.length);
        out.writeBytes(v);
        writeLe(out, tags.size());
        for (String[] t : tags) {
            byte[] item = (t[0] + "=" + t[1]).getBytes(StandardCharsets.UTF_8);
            writeLe(out, item.length);
            out.writeBytes(item);
        }
        return out.toByteArray();
    }

    private static void writeLe(OutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    // ---------------- MP3 ----------------
    private static void writeMp3(Path file, String title, String artist, String album) throws IOException {
        long oldTagSize = 0;
        byte[] head = new byte[10];
        try (var in = Files.newInputStream(file)) {
            int n = in.read(head);
            if (n >= 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                int size = ((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14)
                        | ((head[8] & 0x7F) << 7) | (head[9] & 0x7F);
                oldTagSize = 10L + size;
            }
        }
        byte[] tag = buildId3(title, artist, album);
        Path tmp = file.resolveSibling(file.getFileName() + ".tagtmp");
        try (var in = Files.newInputStream(file);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
            out.write(tag);
            long skip = oldTagSize;
            byte[] buf = new byte[1 << 16];
            long skippedTotal = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                int off = 0;
                if (skippedTotal < skip) {
                    long need = skip - skippedTotal;
                    int drop = (int) Math.min(need, n);
                    skippedTotal += drop;
                    off = drop;
                }
                if (off < n) out.write(buf, off, n - off);
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] buildId3(String title, String artist, String album) throws IOException {
        java.io.ByteArrayOutputStream frames = new java.io.ByteArrayOutputStream();
        addFrame(frames, "TIT2", title);
        addFrame(frames, "TPE1", artist);
        addFrame(frames, "TALB", album);
        byte[] body = frames.toByteArray();
        java.io.ByteArrayOutputStream tag = new java.io.ByteArrayOutputStream();
        tag.write('I');
        tag.write('D');
        tag.write('3');
        tag.write(4);           // v2.4
        tag.write(0);           // revision
        tag.write(0);           // flags
        int size = body.length;
        tag.write((size >> 21) & 0x7F);
        tag.write((size >> 14) & 0x7F);
        tag.write((size >> 7) & 0x7F);
        tag.write(size & 0x7F);
        tag.writeBytes(body);
        return tag.toByteArray();
    }

    private static void addFrame(java.io.ByteArrayOutputStream out, String id, String value)
            throws IOException {
        if (value == null || value.isBlank()) return;
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        int size = 1 + text.length;
        out.write(id.getBytes(StandardCharsets.US_ASCII));
        out.write((size >> 21) & 0x7F);
        out.write((size >> 14) & 0x7F);
        out.write((size >> 7) & 0x7F);
        out.write(size & 0x7F);
        out.write(0);   // flags
        out.write(0);
        out.write(3);   // UTF-8
        out.writeBytes(text);
    }
}
