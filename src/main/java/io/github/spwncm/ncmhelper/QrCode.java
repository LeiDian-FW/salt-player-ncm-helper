package io.github.spwncm.ncmhelper;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;

/** 使用 ZXing 生成登录二维码 PNG。 */
public final class QrCode {

    private QrCode() {
    }

    /** 生成二维码图像（供界面显示） */
    public static BufferedImage toImage(String text, int size) throws java.io.IOException {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);

            BufferedImage img = new BufferedImage(matrix.getWidth(), matrix.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    img.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            return img;
        } catch (Exception e) {
            throw new java.io.IOException("生成二维码失败: " + e, e);
        }
    }

    /** 返回二维码模块矩阵（true=黑），供原生 Compose 界面绘制 */
    public static boolean[][] matrix(String text, int size) throws java.io.IOException {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            boolean[][] out = new boolean[m.getHeight()][m.getWidth()];
            for (int y = 0; y < m.getHeight(); y++)
                for (int x = 0; x < m.getWidth(); x++)
                    out[y][x] = m.get(x, y);
            return out;
        } catch (Exception e) {
            throw new java.io.IOException("生成二维码失败: " + e, e);
        }
    }

    /** 生成并保存二维码 PNG */
    public static void writePng(String text, Path out, int size) throws java.io.IOException {
        BufferedImage img = toImage(text, size);
        Files.createDirectories(out.getParent());
        ImageIO.write(img, "png", out.toFile());
    }
}
