package io.github.spwncm.ncmhelper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * NCM 容器解密（纯 JDK、**流式**处理）。
 *
 * 宿主 JVM 只有 512MB 堆，NCM 文件可达 60MB，因此不能整文件读入内存：
 * 先只读头部（密钥/元数据/封面），再把音频按 256 字节块流式解密 XOR 到输出文件。
 */
public final class NcmDecoder {

    private static final byte[] CORE_KEY = "hzHRAmso5kInbaxW".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] META_KEY = {
            0x23, 0x31, 0x34, 0x6c, 0x6a, 0x6b, 0x5f, 0x21,
            0x5c, 0x5d, 0x26, 0x30, 0x55, 0x3c, 0x27, 0x28
    };
    private static final byte[] MAGIC = "CTENFDAM".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_PREFIX = "neteasecloudmusic".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] META_PREFIX = "163 key(Don't modify):".getBytes(StandardCharsets.US_ASCII);

    /** 解密所需信息（不含音频本体） */
    public static final class NcmInfo {
        public final String format;      // flac / mp3 / ogg
        public final Map<String, Object> meta;
        public final byte[] cover;
        public final long audioOffset;
        final byte[] mask;               // 256 字节重复密钥流

        NcmInfo(String format, Map<String, Object> meta, byte[] cover, long audioOffset, byte[] mask) {
            this.format = format;
            this.meta = meta;
            this.cover = cover;
            this.audioOffset = audioOffset;
            this.mask = mask;
        }

        public String title() {
            Object v = meta.get("musicName");
            return v == null ? "" : String.valueOf(v);
        }

        public String artist() {
            List<Object> artists = Json.asList(meta.get("artist"));
            List<String> names = new ArrayList<>();
            for (Object a : artists) {
                if (a instanceof List<?> arr && !arr.isEmpty() && arr.get(0) != null) {
                    names.add(String.valueOf(arr.get(0)));
                }
            }
            return String.join("/", names);
        }

        public String album() {
            Object v = meta.get("album");
            return v == null ? "" : String.valueOf(v);
        }
    }

    public static boolean isNcm(byte[] head) {
        if (head.length < 8) return false;
        for (int k = 0; k < 8; k++) {
            if (head[k] != MAGIC[k]) return false;
        }
        return true;
    }

    /** 只读取并解析头部，不加载音频 */
    public static NcmInfo inspect(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] head = new byte[14];
            raf.readFully(head);
            if (!isNcm(head)) throw new IOException("不是 NCM 文件");
            int klen = le32(head, 10);
            if (klen <= 0 || klen > 4096) throw new IOException("密钥块长度异常");

            byte[] keyBlock = new byte[klen];
            raf.readFully(keyBlock);
            for (int k = 0; k < klen; k++) keyBlock[k] ^= 0x64;
            byte[] keyPlain = aesDecrypt(keyBlock, CORE_KEY);
            if (keyPlain.length < 17) throw new IOException("密钥块解密失败");
            for (int k = 0; k < 17; k++) {
                if (keyPlain[k] != KEY_PREFIX[k]) throw new IOException("密钥校验失败");
            }
            byte[] keyBody = Arrays.copyOfRange(keyPlain, 17, keyPlain.length);

            int metaLen = readLe32(raf);
            if (metaLen <= 0 || metaLen > 4 * 1024 * 1024) throw new IOException("元数据长度异常");
            byte[] metaBlob = new byte[metaLen];
            raf.readFully(metaBlob);
            for (int k = 0; k < metaLen; k++) metaBlob[k] ^= 0x63;
            Map<String, Object> meta = parseMeta(metaBlob);

            readLe32(raf);                     // CRC
            raf.skipBytes(5);                  // 固定间隔
            int coverLen = readLe32(raf);
            if (coverLen < 0 || coverLen > 32 * 1024 * 1024) throw new IOException("封面长度异常");
            byte[] cover = new byte[coverLen];
            if (coverLen > 0) raf.readFully(cover);
            long audioOffset = raf.getFilePointer();

            // 用前 16 字节音频试出正确的密钥长度，并确定格式
            byte[] probe = new byte[16];
            raf.seek(audioOffset);
            int n = raf.read(probe);
            if (n < 4) throw new IOException("音频数据为空");
            byte[] first = Arrays.copyOf(probe, n);

            byte[] goodMask = null;
            String format = null;
            for (byte[] key : keyCandidates(keyBody)) {
                byte[] mask = buildMask(key);
                byte[] dec = xorWithMask(first, mask);
                String f = formatOf(dec);
                if (f != null) {
                    goodMask = mask;
                    format = f;
                    break;
                }
            }
            if (goodMask == null) throw new IOException("音频解密失败（密钥不匹配）");
            return new NcmInfo(format, meta, cover, audioOffset, goodMask);
        }
    }

    /** 流式解密音频到输出文件 */
    public static void decryptTo(Path file, NcmInfo info, Path out) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
             OutputStream os = new java.io.BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
            raf.seek(info.audioOffset);
            byte[] buf = new byte[256 * 4096];      // 1MB，256 的整数倍
            byte[] plain = new byte[buf.length];
            int n;
            while ((n = raf.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    plain[i] = (byte) (buf[i] ^ info.mask[i & 0xFF]);
                }
                os.write(plain, 0, n);              // 批量写，避免逐字节系统调用
            }
        }
    }

    // ---------------- 元数据 ----------------
    private static Map<String, Object> parseMeta(byte[] blob) {
        Map<String, Object> empty = new LinkedHashMap<>();
        if (blob.length <= META_PREFIX.length) return empty;
        for (int k = 0; k < META_PREFIX.length; k++) {
            if (blob[k] != META_PREFIX[k]) return empty;
        }
        try {
            String b64 = new String(blob, META_PREFIX.length, blob.length - META_PREFIX.length,
                    StandardCharsets.US_ASCII).trim();
            byte[] enc = Base64.getDecoder().decode(b64);
            byte[] plain = aesDecrypt(enc, META_KEY);
            plain = stripPadding(plain);
            String text = new String(plain, StandardCharsets.UTF_8);
            int idx = text.indexOf("music:");
            if (idx < 0) return empty;
            text = text.substring(idx + 6).strip();
            return Json.asMap(Json.parse(text));
        } catch (Exception e) {
            return empty;
        }
    }

    private static byte[] stripPadding(byte[] data) {
        if (data.length == 0) return data;
        int pad = data[data.length - 1] & 0xFF;
        if (pad >= 1 && pad <= 16 && pad <= data.length) {
            boolean ok = true;
            for (int k = data.length - pad; k < data.length; k++) {
                if ((data[k] & 0xFF) != pad) {
                    ok = false;
                    break;
                }
            }
            if (ok) return Arrays.copyOf(data, data.length - pad);
        }
        return data;
    }

    // ---------------- 密钥与密钥流 ----------------
    private static List<byte[]> keyCandidates(byte[] body) {
        List<byte[]> out = new ArrayList<>();
        for (int pad : new int[]{0x00, 0x0c, 0x0d, 0x0e, 0x0a}) {
            int end = body.length;
            while (end > 16 && (body[end - 1] & 0xFF) == pad) end--;
            if (end > 16) out.add(Arrays.copyOfRange(body, 0, end));
        }
        out.add(body);
        for (int n = body.length - 1; n > 16; n--) {
            out.add(Arrays.copyOfRange(body, 0, n));
        }
        return out;
    }

    /** keybox 每 256 字节重置 j，因此密钥流天然按 256 字节循环 */
    private static byte[] buildMask(byte[] key) {
        int[] box = new int[256];
        for (int k = 0; k < 256; k++) box[k] = k;
        int last = 0;
        for (int k = 0; k < 256; k++) {
            int t = (box[k] + last + (key[k % key.length] & 0xFF)) & 0xFF;
            int tmp = box[k];
            box[k] = box[t];
            box[t] = tmp;
            last = t;
        }
        byte[] mask = new byte[256];
        for (int idx = 0; idx < 256; idx++) {
            int j = (idx + 1) & 0xFF;
            mask[idx] = (byte) box[(box[j] + box[(box[j] + j) & 0xFF]) & 0xFF];
        }
        return mask;
    }

    private static byte[] xorWithMask(byte[] data, byte[] mask) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) out[i] = (byte) (data[i] ^ mask[i & 0xFF]);
        return out;
    }

    private static String formatOf(byte[] audio) {
        if (audio.length < 4) return null;
        if (audio[0] == 'f' && audio[1] == 'L' && audio[2] == 'a' && audio[3] == 'C') return "flac";
        if (audio[0] == 'I' && audio[1] == 'D' && audio[2] == '3') return "mp3";
        if (audio[0] == 'O' && audio[1] == 'g' && audio[2] == 'g' && audio[3] == 'S') return "ogg";
        if ((audio[0] & 0xFF) == 0xFF && (audio[1] & 0xE0) == 0xE0) return "mp3";
        return null;
    }

    // ---------------- 工具 ----------------
    private static byte[] aesDecrypt(byte[] data, byte[] key) {
        try {
            int len = data.length - data.length % 16;
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(data, 0, len);
        } catch (Exception e) {
            throw new IllegalStateException("AES 解密失败", e);
        }
    }

    private static int readLe32(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return le32(b, 0);
    }

    private static int le32(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }
}
