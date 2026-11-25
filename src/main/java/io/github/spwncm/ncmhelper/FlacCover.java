package io.github.spwncm.ncmhelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FLAC 元数据操作（**流式**，不整文件读入内存）：
 * 判断/写入封面、取音频帧偏移、比对两个文件音频帧是否一致。
 */
public final class FlacCover {

    private static final int BLOCK_PICTURE = 6;
    private static final int PICTURE_TYPE_FRONT = 3;
    private static final int BUF = 1 << 16;

    public static boolean isFlac(Path file) {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] h = new byte[4];
            raf.readFully(h);
            return h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C';
        } catch (IOException e) {
            return false;
        }
    }

    /** 返回音频帧起始偏移；不是 FLAC 或结构异常返回 -1 */
    public static long audioOffset(Path file) {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            return audioOffset(raf);
        } catch (IOException e) {
            return -1;
        }
    }

    private static long audioOffset(RandomAccessFile raf) throws IOException {
        byte[] magic = new byte[4];
        raf.readFully(magic);
        if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') return -1;
        long pos = 4;
        while (pos + 4 <= raf.length()) {
            raf.seek(pos);
            int hdr = raf.read();
            if (hdr < 0 || hdr == 0xFF) return pos;        // 同步码：音频区
            boolean last = (hdr & 0x80) != 0;
            int type = hdr & 0x7F;
            if (type == 0x7F) return -1;
            int len = ((raf.read() & 0xFF) << 16) | ((raf.read() & 0xFF) << 8) | (raf.read() & 0xFF);
            pos += 4 + len;
            if (last) return pos;
        }
        return -1;
    }

    public static boolean hasPicture(Path file) {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] magic = new byte[4];
            raf.readFully(magic);
            if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') return false;
            long pos = 4;
            while (pos + 4 <= raf.length()) {
                raf.seek(pos);
                int hdr = raf.read();
                if (hdr < 0 || hdr == 0xFF) return false;
                boolean last = (hdr & 0x80) != 0;
                int type = hdr & 0x7F;
                if (type == 0x7F) return false;
                int len = ((raf.read() & 0xFF) << 16) | ((raf.read() & 0xFF) << 8) | (raf.read() & 0xFF);
                if (type == BLOCK_PICTURE) return true;
                pos += 4 + len;
                if (last) return false;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** 把封面写入 FLAC；已存在 PICTURE 时原样复制。 */
    public static void embed(Path src, Path dest, byte[] image, String description) throws IOException {
        if (image == null || image.length < 4) {
            copyFile(src, dest);
            return;
        }
        String mime;
        if ((image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8) mime = "image/jpeg";
        else if ((image[0] & 0xFF) == 0x89 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G') mime = "image/png";
        else {
            copyFile(src, dest);
            return;
        }
        if (hasPicture(src)) {
            copyFile(src, dest);
            return;
        }

        long srcAudio = audioOffset(src);
        if (srcAudio < 0) {
            copyFile(src, dest);
            return;
        }

        byte[] imageBlock = buildPictureBlock(image, mime, description);
        try (RandomAccessFile in = new RandomAccessFile(src.toFile(), "r");
             OutputStream out = Files.newOutputStream(dest)) {
            out.write('f');
            out.write('L');
            out.write('a');
            out.write('C');
            long pos = 4;
            byte[] hdr = new byte[4];
            byte[] buf = new byte[BUF];
            while (pos < srcAudio) {
                in.seek(pos);
                in.readFully(hdr);
                int last = hdr[0] & 0x80;
                int type = hdr[0] & 0x7F;
                int len = ((hdr[1] & 0xFF) << 16) | ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);
                // 原最后一块要清掉 last 标志，把 last 让给新加的封面块
                out.write(type);
                out.write((len >> 16) & 0xFF);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
                in.seek(pos + 4);
                int remain = len;
                while (remain > 0) {
                    int n = in.read(buf, 0, Math.min(BUF, remain));
                    if (n < 0) throw new IOException("FLAC 元数据块损坏");
                    out.write(buf, 0, n);
                    remain -= n;
                }
                pos += 4 + len;
                if (last != 0) break;
            }
            // 追加封面块（最后一块）
            int blockLen = imageBlock.length;
            out.write(0x80 | BLOCK_PICTURE);
            out.write((blockLen >> 16) & 0xFF);
            out.write((blockLen >> 8) & 0xFF);
            out.write(blockLen & 0xFF);
            out.write(imageBlock);
            // 原样复制音频帧
            in.seek(srcAudio);
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** 两个文件的音频帧是否一致（流式比对） */
    public static boolean sameAudio(Path a, Path b) {
        boolean aFlac = isFlac(a);
        boolean bFlac = isFlac(b);
        if (aFlac != bFlac) return false;
        try {
            long offA = aFlac ? audioOffset(a) : 0;
            long offB = bFlac ? audioOffset(b) : 0;
            if (offA < 0 || offB < 0) return false;
            long lenA = Files.size(a) - offA;
            long lenB = Files.size(b) - offB;
            if (lenA != lenB) return false;
            try (RandomAccessFile ra = new RandomAccessFile(a.toFile(), "r");
                 RandomAccessFile rb = new RandomAccessFile(b.toFile(), "r")) {
                ra.seek(offA);
                rb.seek(offB);
                byte[] ba = new byte[BUF];
                byte[] bb = new byte[BUF];
                long remain = lenA;
                while (remain > 0) {
                    int want = (int) Math.min(BUF, remain);
                    ra.readFully(ba, 0, want);
                    rb.readFully(bb, 0, want);
                    for (int i = 0; i < want; i++) {
                        if (ba[i] != bb[i]) return false;
                    }
                    remain -= want;
                }
                return true;
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyFile(Path src, Path dest) throws IOException {
        Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] buildPictureBlock(byte[] image, String mime, String description) {
        int[] dim = imageSize(image, mime);
        byte[] desc = description == null ? new byte[0] : description.getBytes(StandardCharsets.UTF_8);
        byte[] mimeB = mime.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream body = new ByteArrayOutputStream(image.length + 128);
        writeBE32(body, PICTURE_TYPE_FRONT);
        writeBE32(body, mimeB.length);
        body.writeBytes(mimeB);
        writeBE32(body, desc.length);
        body.writeBytes(desc);
        writeBE32(body, dim[0]);
        writeBE32(body, dim[1]);
        writeBE32(body, dim[2]);
        writeBE32(body, 0);
        writeBE32(body, image.length);
        body.writeBytes(image);
        return body.toByteArray();
    }

    private static int[] imageSize(byte[] img, String mime) {
        if ("image/jpeg".equals(mime)) {
            int i = 2;
            while (i + 9 < img.length) {
                if ((img[i] & 0xFF) != 0xFF) {
                    i++;
                    continue;
                }
                int marker = img[i + 1] & 0xFF;
                if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    int h = ((img[i + 5] & 0xFF) << 8) | (img[i + 6] & 0xFF);
                    int w = ((img[i + 7] & 0xFF) << 8) | (img[i + 8] & 0xFF);
                    return new int[]{w, h, 24};
                }
                int seg = ((img[i + 2] & 0xFF) << 8) | (img[i + 3] & 0xFF);
                i += 2 + seg;
            }
        } else if (img.length > 25) {
            int w = be32(img, 16);
            int h = be32(img, 20);
            int colorType = img[25] & 0xFF;
            int depth = switch (colorType) {
                case 2 -> 24;
                case 4 -> 16;
                case 6 -> 32;
                default -> 8;
            };
            return new int[]{w, h, depth};
        }
        return new int[]{0, 0, 0};
    }

    private static void writeBE32(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static int be32(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }
}
