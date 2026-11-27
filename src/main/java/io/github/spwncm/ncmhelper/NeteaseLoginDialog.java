package io.github.spwncm.ncmhelper;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.RoundRectangle2D;
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
 * 播放器内网易云扫码登录窗口（深色圆角卡片风格，置顶显示在播放器上方）。
 * 支持刷新二维码、拖拽移动、ESC 关闭。
 */
public final class NeteaseLoginDialog {

    private static final int QR_SIZE = 300;
    private static final Color BG_TOP = new Color(0x15, 0x1C, 0x27);
    private static final Color BG_BOTTOM = new Color(0x1E, 0x2A, 0x3A);
    private static final Color BORDER = new Color(0x2E, 0x42, 0x5C);
    private static final Color TEXT = new Color(0xEC, 0xF2, 0xF9);
    private static final Color TEXT_DIM = new Color(0x8F, 0xA3, 0xB8);
    private static final Color ACCENT = new Color(0x2D, 0x6C, 0xDF);
    private static final Color OK = new Color(0x3D, 0xD5, 0x8A);
    private static final Color WARN = new Color(0xE5, 0xA5, 0x3A);

    private NeteaseLoginDialog() {
    }

    public static void show(NeteaseClient client) {
        Thread prepare = new Thread(() -> {
            try {
                Log.info("正在申请网易云登录二维码…");
                String key = client.requestQrKey();
                BufferedImage img = QrCode.toImage(
                        "https://music.163.com/login?codekey=" + key, QR_SIZE);
                Log.info("二维码已生成，等待扫码（180 秒）");
                SwingUtilities.invokeLater(() -> openCard(client, key, img));
            } catch (Throwable e) {
                Log.error("生成登录二维码失败: " + e);
                MainPlugin.toast("生成登录二维码失败: " + e.getMessage());
            }
        }, "netease-qr-prepare");
        prepare.setDaemon(true);
        prepare.start();
    }

    private static void openCard(NeteaseClient client, String key, BufferedImage image) {
        Window owner = findOwner();
        JDialog dialog = new JDialog(owner instanceof Frame f ? f : null, "网易云扫码登录");
        dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel status = new JLabel("请使用网易云音乐 App 扫描二维码");
        status.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        status.setForeground(TEXT_DIM);

        IDot dot = new IDot();
        JPanel statusRow = new JPanel();
        statusRow.setOpaque(false);
        statusRow.setLayout(new BoxLayout(statusRow, BoxLayout.X_AXIS));
        statusRow.add(dot);
        statusRow.add(Box.createHorizontalStrut(8));
        statusRow.add(status);

        JLabel hint = new JLabel("扫码后在手机上确认登录，登录信息仅保存在本机");
        hint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        hint.setForeground(new Color(0x6E, 0x82, 0x98));

        JButton refresh = new JButton("刷新二维码");
        stylePrimary(refresh);
        refresh.addActionListener(e -> {
            dialog.dispose();
            show(client);
        });
        JButton close = new JButton("关闭");
        styleGhost(close);
        close.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(refresh);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(close);

        CardPanel card = new CardPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(22, 26, 22, 26));

        JLabel title = new JLabel("网易云音乐");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 20));
        title.setForeground(TEXT);
        JLabel subtitle = new JLabel("扫码登录 · NCM 助手");
        subtitle.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        subtitle.setForeground(TEXT_DIM);

        JPanel qrBox = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.WHITE);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 18, 18));
                g2.drawImage(image, 12, 12, getWidth() - 24, getHeight() - 24, null);
                g2.dispose();
            }
        };
        qrBox.setOpaque(false);
        qrBox.setPreferredSize(new Dimension(QR_SIZE + 24, QR_SIZE + 24));
        qrBox.setMaximumSize(new Dimension(QR_SIZE + 24, QR_SIZE + 24));
        qrBox.setAlignmentX(JPanel.CENTER_ALIGNMENT);

        title.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        subtitle.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        hint.setAlignmentX(JPanel.CENTER_ALIGNMENT);
        buttons.setAlignmentX(JPanel.CENTER_ALIGNMENT);

        card.add(title);
        card.add(Box.createVerticalStrut(2));
        card.add(subtitle);
        card.add(Box.createVerticalStrut(16));
        card.add(qrBox);
        card.add(Box.createVerticalStrut(14));
        card.add(statusRow);
        card.add(Box.createVerticalStrut(6));
        card.add(hint);
        card.add(Box.createVerticalStrut(16));
        card.add(buttons);

        dialog.setContentPane(card);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setAlwaysOnTop(true);
        dialog.setShape(new RoundRectangle2D.Float(0, 0, card.getWidth(), card.getHeight(), 20, 20));
        installDrag(dialog, card);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setVisible(true);

        Thread poller = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 180_000;
            boolean confirmed = false;
            try {
                while (dialog.isDisplayable() && System.currentTimeMillis() < deadline) {
                    NeteaseClient.QrStatus st = client.pollQr(key);
                    if (st.code == 803 && st.success) {
                        String nick = client.nickname();
                        dot.setColor(OK);
                        status.setText("登录成功" + (nick.isEmpty() ? "" : "：" + nick));
                        Thread.sleep(800);
                        SwingUtilities.invokeLater(dialog::dispose);
                        MainPlugin.toast("网易云登录成功" + (nick.isEmpty() ? "" : "：" + nick));
                        return;
                    }
                    if (st.code == 800) {
                        dot.setColor(WARN);
                        status.setText("二维码已过期，请点击「刷新二维码」");
                        return;
                    }
                    if (st.code == 802 && !confirmed) {
                        confirmed = true;
                        dot.setColor(ACCENT);
                        status.setText("已扫码，请在手机上确认登录");
                    }
                }
                if (System.currentTimeMillis() >= deadline) {
                    dot.setColor(WARN);
                    status.setText("登录超时，请点击「刷新二维码」重试");
                }
            } catch (Throwable e) {
                Log.error("登录轮询失败: " + e);
                dot.setColor(new Color(0xE5, 0x5A, 0x5A));
                status.setText("登录失败: " + e.getMessage());
            }
        }, "netease-qr-poll");
        poller.setDaemon(true);
        poller.start();
    }

    // ---------------- 样式 ----------------
    private static final class CardPanel extends JPanel {
        CardPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 20, 20);
            g2.setPaint(new java.awt.GradientPaint(0, 0, BG_TOP, 0, h, BG_BOTTOM));
            g2.fill(shape);
            g2.setColor(BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(shape);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class IDot extends JPanel {
        private Color color = TEXT_DIM;

        IDot() {
            setOpaque(false);
            setPreferredSize(new Dimension(9, 9));
            setMaximumSize(new Dimension(9, 9));
        }

        void setColor(Color c) {
            color = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }

    private static void stylePrimary(JButton b) {
        b.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        b.setForeground(Color.WHITE);
        b.setBackground(ACCENT);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
        b.setOpaque(true);
    }

    private static void styleGhost(JButton b) {
        b.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        b.setForeground(TEXT);
        b.setBackground(new Color(0x25, 0x33, 0x45));
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
        b.setOpaque(true);
    }

    private static void installDrag(JDialog dialog, JPanel handle) {
        final int[] origin = new int[2];
        handle.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                origin[0] = e.getX();
                origin[1] = e.getY();
            }
        });
        handle.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                dialog.setLocation(dialog.getX() + e.getX() - origin[0],
                        dialog.getY() + e.getY() - origin[1]);
            }
        });
    }

    private static Window findOwner() {
        for (Frame f : Frame.getFrames()) {
            if (f.isShowing()) return f;
        }
        return null;
    }
}
