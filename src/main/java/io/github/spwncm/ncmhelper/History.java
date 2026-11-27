package io.github.spwncm.ncmhelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 转换记录：保存最近的转换/归档结果到插件数据目录 history.jsonl，
 * 超过保留天数的记录在启动、定时检查与退出时自动清理。
 */
public final class History {

    /** 一条转换记录 */
    public static final class Record {
        public long time;
        public String status = "";
        public String title = "";
        public String artist = "";
        public String dest = "";
        public String format = "";
        public long size;
        public String message = "";

        public Record() {
        }

        public Record(long time, String status, String title, String artist,
                      String dest, String format, long size, String message) {
            this.time = time;
            this.status = status;
            this.title = nz(title);
            this.artist = nz(artist);
            this.dest = nz(dest);
            this.format = nz(format);
            this.size = size;
            this.message = nz(message);
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }

        public String timeText() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(time));
        }
    }

    private static final long DAY_MS = 24L * 3600 * 1000;
    private static final int MAX_RECORDS = 2000;

    private final Path file;
    private volatile int retainDays;
    private final List<Record> records = new ArrayList<>();
    private long lastPurge = 0L;

    public History(Path dataDir, int retainDays) {
        this.file = dataDir.resolve("history.jsonl");
        this.retainDays = Math.max(1, retainDays);
        load();
        purge();
    }

    public int retainDays() {
        return retainDays;
    }

    public void setRetainDays(int days) {
        this.retainDays = Math.max(1, days);
        purge();
    }

    /** 记录一条结果（成功/跳过/失败），超过保留期的记录顺带清理 */
    public synchronized void add(Record r) {
        records.add(r);
        append(r);
        if (System.currentTimeMillis() - lastPurge > 6L * 3600 * 1000) {
            purge();
        } else if (records.size() > MAX_RECORDS) {
            while (records.size() > MAX_RECORDS) records.remove(0);
            rewrite();
        }
    }

    /** 最近 [limit] 条，新的在前 */
    public synchronized List<Record> recent(int limit) {
        List<Record> out = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(records.get(i));
        }
        return out;
    }

    public synchronized int count() {
        return records.size();
    }

    /** 删除超过保留天数的记录，返回删除数量 */
    public synchronized int purge() {
        long cutoff = System.currentTimeMillis() - retainDays * DAY_MS;
        int before = records.size();
        records.removeIf(r -> r.time < cutoff);
        lastPurge = System.currentTimeMillis();
        if (records.size() != before) {
            rewrite();
        }
        return before - records.size();
    }

    public synchronized void clear() {
        records.clear();
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    // ---------------- 持久化 ----------------
    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.strip();
                if (s.isEmpty()) continue;
                try {
                    Map<String, Object> m = Json.asMap(Json.parse(s));
                    Record r = new Record();
                    r.time = Json.lng(m, "time", 0);
                    r.status = Json.str(m, "status", "");
                    r.title = Json.str(m, "title", "");
                    r.artist = Json.str(m, "artist", "");
                    r.dest = Json.str(m, "dest", "");
                    r.format = Json.str(m, "format", "");
                    r.size = Json.lng(m, "size", 0);
                    r.message = Json.str(m, "message", "");
                    records.add(r);
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void append(Record r) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson(r) + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private void rewrite() {
        StringBuilder sb = new StringBuilder();
        for (Record r : records) {
            sb.append(toJson(r)).append(System.lineSeparator());
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static String toJson(Record r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", r.time);
        m.put("status", r.status);
        m.put("title", r.title);
        m.put("artist", r.artist);
        m.put("dest", r.dest);
        m.put("format", r.format);
        m.put("size", r.size);
        m.put("message", r.message);
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number n) {
                sb.append(n);
            } else {
                sb.append(quote(String.valueOf(v)));
            }
        }
        return sb.append('}').toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
