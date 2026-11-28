package io.github.spwncm.ncmhelper;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 一键导出诊断包：插件/宿主版本、权限、原生 Hook 状态、配置与日志尾部。 */
public final class NeteaseDiagnostics {

    private NeteaseDiagnostics() {
    }

    /** 导出诊断 zip 到桌面，返回文件路径 */
    public static Path export() throws IOException {
        MainPlugin p = MainPlugin.instance();
        Path dataDir = p == null ? Paths.get(".") : p.dataDirRef();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path desktop = desktopDir();
        Path out = desktop.resolve("ncm-helper-diagnostics-" + stamp + ".zip");

        StringBuilder report = new StringBuilder();
        report.append("NCM 助手诊断报告\n");
        report.append("时间: ").append(LocalDateTime.now()).append('\n');
        if (p != null) {
            try {
                report.append("插件版本: ").append(p.getPluginContext().getPluginVersion()).append('\n');
                report.append("SPW 版本: ").append(p.getPluginContext().getSpwVersion()).append('\n');
                report.append("SPW 渠道: ").append(p.getPluginContext().getSpwChannel()).append('\n');
            } catch (Throwable ignored) {
            }
        }
        report.append("Java: ").append(System.getProperty("java.version"))
                .append(" / ").append(System.getProperty("os.name"))
                .append(' ').append(System.getProperty("os.version")).append('\n');
        try {
            boolean granted = com.xuncorp.spw.workshop.api.WorkshopApi.manager()
                    .isPermissionGranted(com.xuncorp.spw.workshop.api.PluginPermission.CLASS_TRANSFORM);
            report.append("CLASS_TRANSFORM 权限: ").append(granted).append('\n');
        } catch (Throwable e) {
            report.append("CLASS_TRANSFORM 权限: 查询失败(").append(e).append(")\n");
        }
        report.append("原生界面已安装: ").append(NeteaseUiBridge.nativeAvailable()).append('\n');
        report.append("原生 Hook 调用次数: ").append(NeteaseUiBridge.hookHits()).append('\n');
        report.append("当前注入点: ").append(NeteaseUiBridge.currentTarget()).append('\n');
        report.append("内嵌页面可用: ").append(NeteaseUiBridge.embedded()).append('\n');
        report.append("侧边栏 Hook 命中: ").append(NeteaseUiBridge.sidebarHits()).append('\n');
        report.append("覆盖层 Hook 命中: ").append(NeteaseUiBridge.overlayHits()).append('\n');
        report.append("网易云已登录: ").append(NeteaseUiBridge.loggedIn()).append('\n');
        report.append("音乐库目录: ").append(NeteaseUiBridge.libraryPath()).append('\n');
        report.append("每次最多下载: ").append(NeteaseUiBridge.downloadLimit())
                .append("（合法=").append(NeteaseUiBridge.downloadLimitValid()).append("）\n");
        report.append("数据目录: ").append(dataDir).append('\n');

        StringBuilder logTail = new StringBuilder();
        Path log = dataDir.resolve("ncm-helper.log");
        if (Files.isRegularFile(log)) {
            try {
                java.util.List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
                int from = Math.max(0, lines.size() - 3000);
                for (int i = from; i < lines.size(); i++) {
                    logTail.append(lines.get(i)).append('\n');
                }
            } catch (IOException e) {
                logTail.append("读取日志失败: ").append(e).append('\n');
            }
        } else {
            logTail.append("未找到日志文件\n");
        }

        Files.createDirectories(desktop);
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(out)))) {
            zip.putNextEntry(new ZipEntry("diagnostics.txt"));
            zip.write(report.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ncm-helper.log.tail.txt"));
            zip.write(logTail.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Log.info("诊断包已导出: " + out);
        return out;
    }

    private static Path desktopDir() {
        String up = System.getenv("USERPROFILE");
        if (up == null || up.isEmpty()) up = System.getProperty("user.home", ".");
        Path desktop = Paths.get(up, "Desktop");
        if (Files.isDirectory(desktop)) return desktop;
        return Paths.get(up);
    }
}
