package io.github.spwncm.ncmhelper;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.RoundRectangle2D;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * 原生界面不可用时的回退面板（Swing）：登录状态、剪贴板登录、扫码登录、搜索/歌单下载、
 * 下载进度条与取消。保证即使没有 CLASS_TRANSFORM 权限也能使用全部功能。
 */
public final class NeteaseSwingPanel {

    private static final Color BG_TOP = new Color(0x15, 0x1C, 0x27);
    private static final Color BG_BOTTOM = new Color(0x1E, 0x2A, 0x3A);
    private static final Color BORDER = new Color(0x2E, 0x42, 0x5C);
    private static final Color TEXT = new Color(0xEC, 0xF2, 0xF9);
    private static final Color TEXT_DIM = new Color(0x8F, 0xA3, 0xB8);
    private static final Color ACCENT = new Color(0x2D, 0x6C, 0xDF);
    private static final Color OK = new Color(0x3D, 0xD5, 0x8A);
    private static final Color WARN = new Color(0xE5, 0xA5, 0x3A);
    private static final Color TRACK = new Color(0x23, 0x30, 0x41);

    private final JDialog dialog;
    private final JLabel status;
    private final JLabel message;
    private final JLabel songLabel;
    private final JProgressBar bar;
    private final JLabel stats;
    private final JTextField searchField;
    private final JTextField playlistField;
    private final JButton cancelButton;
    private volatile boolean cancelled;

    private NeteaseSwingPanel() {
        Window owner = findOwner();
        dialog = new JDialog(owner instanceof Frame f ? f : null, "网易云");
        dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        status = dimLabel("", 13);
        message = dimLabel("", 12);
        songLabel = boldLabel(" ", 13);
        stats = dimLabel("", 12);

        searchField = new JTextField();
        searchField.setBackground(new Color(0x23, 0x2F, 0x40));
        searchField.setForeground(TEXT);
        searchField.setCaretColor(TEXT);
        searchField.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        searchField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));

        playlistField = new JTextField();
        playlistField.setBackground(new Color(0x23, 0x2F, 0x40));
        playlistField.setForeground(TEXT);
        playlistField.setCaretColor(TEXT);
        playlistField.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        playlistField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));

        bar = new JProgressBar(0, 100) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                g2.setColor(TRACK);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, h, h));
                int filled = (int) (w * (getValue() / 100.0));
                if (filled > 0) {
                    g2.setColor(ACCENT);
                    g2.fill(new RoundRectangle2D.Float(0, 0, Math.max(filled, h), h, h, h));
                }
                String text = getValue() + "%";
                g2.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
                g2.setColor(Color.WHITE);
                int tw = g2.getFontMetrics().stringWidth(text);
                g2.drawString(text, (w - tw) / 2, (h + g2.getFontMetrics().getAscent()) / 2 - 2);
                g2.dispose();
            }
        };
        bar.setBorderPainted(false);
        bar.setPreferredSize(new Dimension(360, 16));

        cancelButton = ghostButton("取消下载");
        cancelButton.setEnabled(false);
        cancelButton.addActionListener(e -> {
            cancelled = true;
            cancelButton.setEnabled(false);
            cancelButton.setText("正在停止…");
        });

        JPanel searchRow = new JPanel();
        searchRow.setOpaque(false);
        searchRow.setLayout(new BoxLayout(searchRow, BoxLayout.X_AXIS));
        searchRow.add(searchField);
        searchRow.add(Box.createHorizontalStrut(8));
        searchRow.add(primaryButton("搜索", () -> {
            MainPlugin p = MainPlugin.instance();
            if (p == null) return;
            String kw = searchField.getText().strip();
            if (kw.isEmpty()) {
                message.setText("请输入搜索关键词");
                return;
            }
            p.startSearchDownload(kw);
        }));
        searchRow.add(Box.createHorizontalStrut(8));
        searchRow.add(primaryButton("下载前 N 首", () -> {
            MainPlugin p = MainPlugin.instance();
            if (p == null) return;
            String kw = searchField.getText().strip();
            if (kw.isEmpty()) {
                message.setText("请输入搜索关键词");
                return;
            }
            p.startSearchDownload(kw);
        }));

        JPanel playlistRow = new JPanel();
        playlistRow.setOpaque(false);
        playlistRow.setLayout(new BoxLayout(playlistRow, BoxLayout.X_AXIS));
        playlistRow.add(playlistField);
        playlistRow.add(Box.createHorizontalStrut(8));
        playlistRow.add(primaryButton("下载歌单", () -> {
            MainPlugin p = MainPlugin.instance();
            if (p == null) return;
            String id = playlistField.getText().strip();
            if (!id.matches("\\d+")) {
                message.setText("歌单 ID 需为数字");
                return;
            }
            try {
                p.startPlaylistDownload(Long.parseLong(id));
            } catch (Exception ex) {
                message.setText("下载失败: " + ex.getMessage());
            }
        }));
        playlistRow.add(Box.createHorizontalStrut(8));
        playlistRow.add(primaryButton("下载我喜欢的音乐", () -> {
            MainPlugin p = MainPlugin.instance();
            if (p != null) p.startLikesDownload();
        }));

        JPanel loginRow = new JPanel();
        loginRow.setOpaque(false);
        loginRow.setLayout(new BoxLayout(loginRow, BoxLayout.X_AXIS));
        loginRow.add(primaryButton("从剪贴板登录", () -> {
            String msg = NeteaseUiBridge.clipboardLogin();
            message.setText(msg);
            refreshStatus();
        }));
        loginRow.add(Box.createHorizontalStrut(8));
        loginRow.add(primaryButton("扫码登录", () -> {
            MainPlugin p = MainPlugin.instance();
            if (p == null) return;
            NeteaseLoginDialog.show(p.client());
        }));
        loginRow.add(Box.createHorizontalStrut(8));
        loginRow.add(primaryButton("刷新状态", this::refreshStatus));

        JPanel progressRow = new JPanel();
        progressRow.setOpaque(false);
        progressRow.setLayout(new BoxLayout(progressRow, BoxLayout.Y_AXIS));
        progressRow.add(songLabel);
        progressRow.add(Box.createVerticalStrut(6));
        progressRow.add(bar);
        progressRow.add(Box.createVerticalStrut(6));
        JPanel statsRow = new JPanel();
        statsRow.setOpaque(false);
        statsRow.setLayout(new BoxLayout(statsRow, BoxLayout.X_AXIS));
        statsRow.add(stats);
        statsRow.add(Box.createHorizontalStrut(10));
        statsRow.add(cancelButton);
        progressRow.add(statsRow);

        JPanel card = new JPanel() {
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
            }
        };
        card.setOpaque(false);
        card.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        card.setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(0, 0, 8, 0);

        JLabel title = boldLabel("网易云音乐", 18);
        card.add(title, gc);
        gc.gridy++;
        card.add(status, gc);
        gc.gridy++;
        card.add(loginRow, gc);
        gc.gridy++;
        card.add(dimLabel("搜索关键词", 12), gc);
        gc.gridy++;
        card.add(searchRow, gc);
        gc.gridy++;
        card.add(dimLabel("歌单 ID（数字）", 12), gc);
        gc.gridy++;
        card.add(playlistRow, gc);
        gc.gridy++;
        card.add(Box.createVerticalStrut(4), gc);
        gc.gridy++;
        card.add(progressRow, gc);
        gc.gridy++;
        card.add(message, gc);
        gc.gridy++;
        JButton close = ghostButton("关闭");
        close.addActionListener(e -> dialog.dispose());
        card.add(close, gc);

        dialog.setContentPane(card);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(620, 0));
        dialog.setLocationRelativeTo(owner);
        dialog.setAlwaysOnTop(true);
        try {
            dialog.setShape(new RoundRectangle2D.Float(0, 0, dialog.getWidth(), dialog.getHeight(), 20, 20));
        } catch (Throwable ignored) {
        }
        refreshStatus();
        dialog.setVisible(true);
    }

    public static void show() {
        SwingUtilities.invokeLater(NeteaseSwingPanel::new);
    }

    /** 供下载入口更新进度（由 MainPlugin 的进度监听调用） */
    void updateProgress(int percent, String song, String stat) {
        SwingUtilities.invokeLater(() -> {
            bar.setValue(Math.max(0, Math.min(100, percent)));
            if (song != null) songLabel.setText(song);
            if (stat != null) stats.setText(stat);
        });
    }

    void setMessage(String text) {
        SwingUtilities.invokeLater(() -> message.setText(text == null ? "" : text));
    }

    private void refreshStatus() {
        MainPlugin p = MainPlugin.instance();
        boolean logged = p != null && p.client() != null && p.client().isLoggedIn();
        String nick = logged ? p.client().nickname() : "";
        status.setForeground(logged ? OK : TEXT_DIM);
        status.setText(logged ? "已登录" + (nick.isEmpty() ? "" : "：" + nick) : "未登录");
        cancelButton.setEnabled(false);
        cancelButton.setText("取消下载");
    }

    // ---------------- 样式 ----------------
    private static JLabel dimLabel(String text, int size) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Microsoft YaHei", Font.PLAIN, size));
        l.setForeground(TEXT_DIM);
        return l;
    }

    private static JLabel boldLabel(String text, int size) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Microsoft YaHei", Font.BOLD, size));
        l.setForeground(TEXT);
        return l;
    }

    private static JButton primaryButton(String text, Runnable action) {
        JButton b = new JButton(text);
        b.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        b.setForeground(Color.WHITE);
        b.setBackground(ACCENT);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        b.setOpaque(true);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JButton ghostButton(String text) {
        JButton b = new JButton(text);
        b.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        b.setForeground(TEXT);
        b.setBackground(new Color(0x25, 0x33, 0x45));
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        b.setOpaque(true);
        return b;
    }

    private static Window findOwner() {
        for (Frame f : Frame.getFrames()) {
            if (f.isShowing()) return f;
        }
        return null;
    }
}
