package io.github.spwncm.ncmhelper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 网易云歌词：搜索 → 保守匹配 → 取歌词 → 生成标准 LRC（含翻译），带磁盘缓存。
 */
public final class NeteaseLyrics {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern BRACKETS = Pattern.compile("[(\uFF08\\[\u3010].*?[)\uFF09\\]\u3011]");
    private static final Pattern FEAT = Pattern.compile("\\b(feat|ft|featuring)\\b.*$");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Pattern TS = Pattern.compile("^\\[(\\d+:\\d+(?:\\.\\d+)?)]");

    private final Path cacheDir;

    public NeteaseLyrics(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException ignored) {
        }
    }

    // ---------------- 对外入口 ----------------
    /** 按歌名/歌手/专辑匹配歌词；返回 LRC 文本，找不到返回 null。 */
    public String getLyrics(String title, String artist, String album) {
        if (title == null || title.isBlank()) return null;
        String key = sha1(title + "|" + artist);
        String cached = readCache(key);
        if (cached != null) return cached.isEmpty() ? null : cached;

        try {
            String best = null;
            for (String query : new String[]{title + " " + firstArtist(artist), title}) {
                String q = query.strip();
                if (q.isEmpty()) continue;
                List<Candidate> songs = search(q);
                Candidate c = pickBest(title, artist, album, songs);
                if (c != null) {
                    String lrc = fetchLyric(c);
                    if (lrc != null) {
                        best = lrc;
                        break;
                    }
                }
                sleep(250);
            }
            writeCache(key, best == null ? "" : best);
            return best;
        } catch (Exception e) {
            Log.warn("歌词查询失败: " + title + " -> " + e.getMessage());
            return null;
        }
    }

    // ---------------- 搜索与获取 ----------------
    private static final class Candidate {
        long id;
        String name = "";
        List<String> artists = new ArrayList<>();
        String album = "";
    }

    private List<Candidate> search(String keyword) throws IOException {
        String url = "https://music.163.com/api/search/get?s="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&type=1&offset=0&limit=8";
        Map<String, Object> root = requestJson(url);
        if (Json.lng(root, "code", 0) != 200) return List.of();
        Map<String, Object> result = Json.asMap(root.get("result"));
        List<Object> songs = Json.asList(result.get("songs"));
        List<Candidate> out = new ArrayList<>();
        for (Object o : songs) {
            Map<String, Object> s = Json.asMap(o);
            Candidate c = new Candidate();
            c.id = Json.lng(s, "id", 0);
            c.name = Json.str(s, "name", "");
            for (Object a : Json.asList(s.get("artists"))) {
                String n = Json.str(Json.asMap(a), "name", "");
                if (!n.isEmpty()) c.artists.add(n);
            }
            c.album = Json.str(Json.asMap(s.get("album")), "name", "");
            out.add(c);
        }
        return out;
    }

    private String fetchLyric(Candidate c) throws IOException {
        String url = "https://music.163.com/api/song/lyric?os=pc&id=" + c.id + "&lv=-1&kv=-1&tv=-1";
        Map<String, Object> root = requestJson(url);
        String lrc = Json.str(Json.asMap(root.get("lrc")), "lyric", "");
        String tlyric = Json.str(Json.asMap(root.get("tlyric")), "lyric", "");
        if (lrc.isBlank()) return null;
        return buildLrc(c, lrc, tlyric);
    }

    private Map<String, Object> requestJson(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://music.163.com/");
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            byte[] data;
            try (in) {
                data = in.readAllBytes();
            }
            return Json.asMap(Json.parse(new String(data, StandardCharsets.UTF_8)));
        } finally {
            conn.disconnect();
        }
    }

    // ---------------- 匹配 ----------------
    private Candidate pickBest(String title, String artist, String album, List<Candidate> songs) {
        Candidate best = null;
        double bestScore = -1;
        double bestRatio = 0;
        boolean bestOverlap = false;
        for (Candidate c : songs) {
            String nt = norm(title);
            String nc = norm(c.name);
            if (nt.isEmpty() || nc.isEmpty()) continue;
            double ratio = similarity(nt, nc);
            boolean overlap = artistOverlap(artist, c.artists);
            double bonus = albumMatch(album, c.album) ? 0.03 : 0;
            double score = ratio + bonus;
            if (score > bestScore) {
                bestScore = score;
                bestRatio = ratio;
                bestOverlap = overlap;
                best = c;
            }
        }
        if (best == null) return null;
        boolean ok = (bestOverlap && bestRatio >= 0.70) || bestRatio >= 0.93;
        if (bestRatio < 0.55) ok = false;
        return ok ? best : null;
    }

    private static String norm(String s) {
        if (s == null) return "";
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        t = BRACKETS.matcher(t).replaceAll(" ");
        t = FEAT.matcher(t).replaceAll(" ");
        return NON_WORD.matcher(t).replaceAll("");
    }

    private static List<String> splitArtists(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : s.split("[/,、;&]")) {
            String p = part.strip();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static String firstArtist(String s) {
        List<String> a = splitArtists(s);
        return a.isEmpty() ? "" : a.get(0);
    }

    private static boolean artistOverlap(String fileArtist, List<String> candArtists) {
        List<String> fb = new ArrayList<>();
        for (String a : splitArtists(fileArtist)) {
            String n = norm(a);
            if (!n.isEmpty()) fb.add(n);
        }
        List<String> ca = new ArrayList<>();
        for (String a : candArtists) {
            String n = norm(a);
            if (!n.isEmpty()) ca.add(n);
        }
        for (String x : fb) {
            for (String y : ca) {
                if (x.equals(y) || x.contains(y) || y.contains(x)) return true;
            }
        }
        return false;
    }

    private static boolean albumMatch(String a, String b) {
        String na = norm(a);
        String nb = norm(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        return na.contains(nb) || nb.contains(na);
    }

    /** 1 - 编辑距离/最长长度，0..1 */
    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int la = a.length();
        int lb = b.length();
        if (la == 0 || lb == 0) return 0.0;
        if (la > 200 || lb > 200) return a.contains(b) || b.contains(a) ? 0.9 : 0.0;
        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - (double) prev[lb] / Math.max(la, lb);
    }

    // ---------------- LRC 生成 ----------------
    private static String buildLrc(Candidate c, String lrc, String tlyric) {
        StringBuilder sb = new StringBuilder();
        if (!c.name.isBlank()) sb.append("[ti:").append(c.name).append("]\n");
        if (!c.artists.isEmpty()) sb.append("[ar:").append(String.join("/", c.artists)).append("]\n");
        if (!c.album.isBlank()) sb.append("[al:").append(c.album).append("]\n");
        sb.append('\n');
        String body = lrc.strip();
        if (tlyric != null && !tlyric.isBlank()) {
            List<String[]> parsed = new ArrayList<>();
            for (String line : tlyric.split("\\R")) {
                var m = TS.matcher(line);
                if (m.find() && line.length() > m.end()) {
                    String text = line.substring(m.end()).strip();
                    if (!text.isEmpty()) parsed.add(new String[]{m.group(1), text});
                }
            }
            if (!parsed.isEmpty()) {
                StringBuilder merged = new StringBuilder();
                for (String line : body.split("\\R")) {
                    merged.append(line).append('\n');
                    var m = TS.matcher(line);
                    if (m.find()) {
                        String ts = m.group(1);
                        for (String[] tr : parsed) {
                            if (tr[0].equals(ts)) {
                                merged.append('[').append(tr[0]).append(']').append(tr[1]).append('\n');
                            }
                        }
                    }
                }
                body = merged.toString().strip();
            }
        }
        sb.append(body).append('\n');
        return sb.toString();
    }

    // ---------------- 缓存 ----------------
    private String readCache(String key) {
        Path f = cacheDir.resolve(key + ".lrc");
        try {
            if (Files.isRegularFile(f)) return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        return null;
    }

    private void writeCache(String key, String content) {
        try {
            Files.writeString(cacheDir.resolve(key + ".lrc"), content, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
