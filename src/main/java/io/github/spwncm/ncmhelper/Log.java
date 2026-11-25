package io.github.spwncm.ncmhelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 插件日志：控制台 + 插件数据目录下的日志文件。 */
public final class Log {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile Path file;

    private Log() {
    }

    public static void init(Path logFile) {
        file = logFile;
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException ignored) {
        }
    }

    public static void info(String msg) {
        write("INFO", msg);
    }

    public static void warn(String msg) {
        write("WARN", msg);
    }

    public static void error(String msg) {
        write("ERROR", msg);
    }

    private static void write(String level, String msg) {
        String line = "[" + LocalDateTime.now().format(FMT) + "] [" + level + "] " + msg;
        System.out.println("[NCM Helper] " + line);
        Path f = file;
        if (f == null) return;
        try {
            Files.writeString(f, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
