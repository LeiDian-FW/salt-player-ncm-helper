package io.github.spwncm.ncmhelper;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
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
import javax.swing.SwingUtilities;

/**
 * 网易云下载进度窗口：深色卡片 + 进度条 + 当前歌曲 + 成功/跳过/失败统计 + 停止按钮。
 */
public final class DownloadProgressDialog {

    private static final Color BG_TOP = new Color(0x15, 0x1C, 0x27);
    private static final Color BG_BOTTOM = new Color(0x1E, 0x2A, 0x3A);
    private static final Color BORDER = new Color(0x2E, 0x42, 0x5C);
    private static final Color TEXT = new Color(0xEC, 0xF2, 0xF9);
    private static final Color TEXT_DIM = new Color(0x8F, 0xA3, 0xB8);
    private static final Color ACCENT = new Color(0x2D, 0x6C, 0xDF);
    private static final Color OK = new Color(0x3D, 0xD5, 0x8A);
    private static final Color TRACK = new Color(0x23, 0x30, 0x41);

    private final JDialog dialog;
    private final JLabel phaseLabel;
    private final JLabel songLabel;
    private final JLabel statsLabel;
    private final JProgressBar bar;
    private final JButton cancelButton;
    private final JButton closeButton;

    private volatile boolean cancelled;
    private volatile int total = 0;
    private volatile int completed = 0;
    private volatile int ok = 0;
    private volatile int skip = 0;
    private volatile int fail = 0;
    private volatile long fileDone = 0;
    private volatile long fileTotal = 0;

    private DownloadProgressDialog(String title) {
        Window owner = findOwner();
        dialog = new JDialog(owner instanceof Frame f ? f : null, title);
        dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 18));
        titleLabel.setForeground(TEXT);

        phaseLabel = new JLabel("准备中…");
        phaseLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        phaseLabel.setForeground(TEXT_DIM);

        songLabel = new JLabel(" ");
        songLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        songLabel.setForeground(TEXT);

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
        bar.setPreferredSize(new java.awt.Dimension(340, 18));
        bar.setMaximumSize(new java.awt.Dimension(340, 18));
        bar.setAlignmentX(JPanel.LEFT_ALIGNMENT);

        statsLabel = new JLabel("已完成 0 / 0 · 跳过 0 · 失败 0");
        statsLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        statsLabel.setForeground(TEXT_DIM);

        cancelButton = new JButton("停止");
        cancelButton.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        cancelButton.setForeground(TEXT);
        cancelButton.setBackground(new Color(0x25, 0x33, 0x45));
        cancelButton.setFocusPainted(false);
        cancelButton.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
        cancelButton.setOpaque(true);
        cancelButton.addActionListener(e -> {
            cancelled = true;
            cancelButton.setEnabled(false);
            cancelButton.setText("正在停止…");
        });

        closeButton = new JButton("关闭");
        closeButton.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        closeButton.setForeground(TEXT);
        closeButton.setBackground(new Color(0x25, 0x33, 0x45));
        closeButton.setFocusPainted(false);
        closeButton.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
        closeButton.setOpaque(true);
        closeButton.setEnabled(false);
        closeButton.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(cancelButton);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(closeButton);

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
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        card.setOpaque(false);

        titleLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        phaseLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        songLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        statsLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        buttons.setAlignmentX(JPanel.LEFT_ALIGNMENT);

        card.add(titleLabel);
        card.add(Box.createVerticalStrut(2));
        card.add(phaseLabel);
        card.add(Box.createVerticalStrut(14));
        card.add(songLabel);
        card.add(Box.createVerticalStrut(8));
        card.add(bar);
        card.add(Box.createVerticalStrut(10));
        card.add(statsLabel);
        card.add(Box.createVerticalStrut(14));
        card.add(buttons);

        dialog.setContentPane(card);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setAlwaysOnTop(true);
        dialog.setShape(new RoundRectangle2D.Float(0, 0, card.getWidth(), card.getHeight(), 20, 20));
        dialog.setVisible(true);
    }

    public static DownloadProgressDialog show(String title) {
        DownloadProgressDialog[] holder = new DownloadProgressDialog[1];
        try {
            SwingUtilities.invokeAndWait(() -> holder[0] = new DownloadProgressDialog(title));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            Log.error("打开下载进度窗口失败: " + e.getCause());
        }
        return holder[0];
    }

    // ---------------- 供下载线程调用 ----------------
    public void setPhase(String text) {
        ui(() -> phaseLabel.setText(text));
    }

    public void start(int total) {
        this.total = Math.max(1, total);
        ui(() -> {
            statsLabel.setText("已完成 0 / " + this.total + " · 跳过 0 · 失败 0");
            bar.setValue(0);
        });
    }

    public void song(int index, NeteaseClient.Song song) {
        fileDone = 0;
        fileTotal = 0;
        String text = "(" + index + "/" + total + ") " + song.name
                + (song.artistText().isEmpty() ? "" : " - " + song.artistText());
        ui(() -> {
            songLabel.setText(text);
            phaseLabel.setText("正在下载…");
        });
        recompute();
    }

    public void bytes(long done, long totalBytes) {
        fileDone = done;
        fileTotal = totalBytes;
        recompute();
    }

    public void result(String status, String message) {
        completed++;
        if ("成功".equals(status)) ok++;
        else if ("跳过".equals(status)) skip++;
        else fail++;
        ui(() -> statsLabel.setText("已完成 " + completed + " / " + total
                + " · 成功 " + ok + " · 跳过 " + skip + " · 失败 " + fail));
        recompute();
    }

    public void finish(String summary) {
        ui(() -> {
            phaseLabel.setText(summary);
            songLabel.setText(" ");
            bar.setValue(100);
            cancelButton.setEnabled(false);
            closeButton.setEnabled(true);
        });
        // 4 秒后自动关闭
        new Thread(() -> {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException ignored) {
            }
            SwingUtilities.invokeLater(dialog::dispose);
        }, "download-progress-close").start();
    }

    public void error(String message) {
        ui(() -> {
            phaseLabel.setText("下载失败: " + message);
            cancelButton.setEnabled(false);
            closeButton.setEnabled(true);
        });
    }

    public boolean isCancelled() {
        return cancelled;
    }

    private void recompute() {
        int t = Math.max(1, total);
        double fraction = 0;
        if (fileTotal > 0) fraction = Math.min(1.0, fileDone / (double) fileTotal);
        int percent = (int) Math.min(100, Math.round((completed + fraction) * 100.0 / t));
        ui(() -> bar.setValue(percent));
    }

    private void ui(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    private static Window findOwner() {
        for (Frame f : Frame.getFrames()) {
            if (f.isShowing()) return f;
        }
        return null;
    }
}
