package io.github.spwncm.ncmhelper;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Window;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * 在播放器内弹出网易云扫码登录窗口（属于播放器窗口的对话框，不调用外部图片查看器）。
 */
public final class NeteaseLoginDialog {

    private static final int QR_SIZE = 300;

    private NeteaseLoginDialog() {
    }

    /** 生成二维码并在播放器内显示，随后轮询登录状态 */
    public static void show(NeteaseClient client) {
        Thread prepare = new Thread(() -> {
            try {
                String key = client.requestQrKey();
                BufferedImage img = QrCode.toImage(
                        "https://music.163.com/login?codekey=" + key, QR_SIZE);
                SwingUtilities.invokeLater(() -> showDialog(client, key, img));
            } catch (Throwable e) {
                Log.error("生成登录二维码失败: " + e);
                MainPlugin.toast("生成登录二维码失败: " + e.getMessage());
            }
        }, "netease-qr-prepare");
        prepare.setDaemon(true);
        prepare.start();
    }

    private static void showDialog(NeteaseClient client, String key, BufferedImage image) {
        Window owner = findOwner();
        JDialog dialog = new JDialog(owner instanceof Frame f ? f : null,
                "网易云扫码登录", false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel qr = new JLabel(new ImageIcon(image));
        qr.setAlignmentX(JLabel.CENTER_ALIGNMENT);
        JLabel status = new JLabel("请用网易云 App 扫码", JLabel.CENTER);
        status.setAlignmentX(JLabel.CENTER_ALIGNMENT);
        JLabel hint = new JLabel("登录信息仅保存在本机插件数据目录", JLabel.CENTER);
        hint.setAlignmentX(JLabel.CENTER_ALIGNMENT);

        JButton close = new JButton("关闭");
        close.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel();
        buttons.add(close);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 18, 12, 18));
        panel.add(qr);
        panel.add(Box.createVerticalStrut(10));
        panel.add(status);
        panel.add(Box.createVerticalStrut(4));
        panel.add(hint);
        panel.add(Box.createVerticalStrut(8));
        panel.add(buttons);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(QR_SIZE + 60, 0));
        dialog.setLocationRelativeTo(owner);
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);

        Thread poller = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 120_000;
            boolean confirmed = false;
            try {
                while (dialog.isDisplayable() && System.currentTimeMillis() < deadline) {
                    NeteaseClient.QrStatus st = client.pollQr(key);
                    if (st.code == 803 && st.success) {
                        String nick = client.nickname();
                        update(status, "登录成功" + (nick.isEmpty() ? "" : "：" + nick));
                        Thread.sleep(600);
                        SwingUtilities.invokeLater(dialog::dispose);
                        MainPlugin.toast("网易云登录成功" + (nick.isEmpty() ? "" : "：" + nick));
                        return;
                    }
                    if (st.code == 800) {
                        update(status, "二维码已过期，请关闭后重新点击「扫码登录」");
                        return;
                    }
                    if (st.code == 802 && !confirmed) {
                        confirmed = true;
                        update(status, "已扫码，请在手机上确认登录");
                    }
                }
                if (System.currentTimeMillis() >= deadline) {
                    update(status, "登录超时，请关闭后重试");
                }
            } catch (Throwable e) {
                Log.error("登录轮询失败: " + e);
                update(status, "登录失败: " + e.getMessage());
            }
        }, "netease-qr-poll");
        poller.setDaemon(true);
        poller.start();
    }

    private static void update(JLabel label, String text) {
        SwingUtilities.invokeLater(() -> label.setText(text));
    }

    private static Window findOwner() {
        for (Frame f : Frame.getFrames()) {
            if (f.isShowing()) return f;
        }
        return null;
    }
}
