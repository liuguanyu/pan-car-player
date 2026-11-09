package com.baidu.carplayer.backup;

import com.baidu.carplayer.model.Playlist;
import com.baidu.carplayer.model.Song;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Web 端播放列表备份（version 1.0）的解析与生成。纯 Java，便于单测。
 *
 * <p>fs_id 是 16 位左右的大整数，必须按 long 读取，不能经过 double。
 * 本地不保存 md5/mtime，导出时 md5 写空串、mtime 取歌曲加入时间。
 */
public final class PlaylistBackupCodec {
    public static final String VERSION = "1.0";
    public static final String INVALID_FILE = "不是有效的播放列表备份文件";
    public static final String UNNAMED = "未命名列表";
    private static final int CATEGORY_AUDIO = 2;

    /** 解析失败（格式错误）。 */
    public static final class BackupFormatException extends Exception {
        public BackupFormatException(String message) { super(message); }
    }

    /** 解析结果。 */
    public static final class ParsedBackup {
        private final List<BackupPlaylist> playlists;
        ParsedBackup(List<BackupPlaylist> playlists) { this.playlists = playlists; }
        public List<BackupPlaylist> getPlaylists() { return playlists; }
        public int totalSongs() {
            int n = 0;
            for (BackupPlaylist p : playlists) n += p.getSongs().size();
            return n;
        }
        public int totalSkipped() {
            int n = 0;
            for (BackupPlaylist p : playlists) n += p.getSkippedItems();
            return n;
        }
    }

    private PlaylistBackupCodec() {}

    public static ParsedBackup parse(String text) throws BackupFormatException {
        if (text == null) throw new BackupFormatException(INVALID_FILE);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (JsonParseException | IllegalStateException e) {
            throw new BackupFormatException(INVALID_FILE);
        }
        if (!root.isJsonObject()) throw new BackupFormatException(INVALID_FILE);
        JsonElement list = root.getAsJsonObject().get("playlists");
        if (list == null || !list.isJsonArray()) throw new BackupFormatException(INVALID_FILE);

        List<BackupPlaylist> playlists = new ArrayList<>();
        for (JsonElement element : list.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            playlists.add(parsePlaylist(element.getAsJsonObject()));
        }
        if (playlists.isEmpty()) throw new BackupFormatException("备份文件中没有播放列表");
        return new ParsedBackup(playlists);
    }

    private static BackupPlaylist parsePlaylist(JsonObject o) {
        String name = string(o, "name");
        if (name == null || name.trim().isEmpty()) name = UNNAMED;
        else name = name.trim();
        List<Song> songs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int skipped = 0;
        JsonElement items = o.get("items");
        if (items != null && items.isJsonArray()) {
            for (JsonElement e : items.getAsJsonArray()) {
                Song song = e.isJsonObject() ? parseItem(e.getAsJsonObject()) : null;
                if (song == null || !seen.add(song.getFsId())) {
                    skipped++;
                    continue;
                }
                songs.add(song);
            }
        }
        return new BackupPlaylist(name, string(o, "description"), songs, longValue(o, "create_time", 0), skipped);
    }

    private static Song parseItem(JsonObject o) {
        long fsId = longValue(o, "fs_id", 0);
        if (fsId <= 0 || longValue(o, "isdir", 0) == 1) return null;
        String path = string(o, "path");
        String title = string(o, "server_filename");
        if (title == null || title.isEmpty()) title = fileName(path);
        if (title == null || title.isEmpty()) return null;
        Song song = new Song();
        song.setFsId(fsId);
        song.setTitle(title);
        song.setPath(path);
        song.setSize(longValue(o, "size", 0));
        song.setDuration(Math.round(doubleValue(o, "duration") * 1000));
        song.setAddedTime(longValue(o, "add_time", 0) * 1000);
        return song;
    }

    /** 生成备份文本。songsByPlaylist 以 playlist id 为键，值按播放顺序排列。 */
    public static String write(List<Playlist> playlists, Map<String, List<Song>> songsByPlaylist, long nowMillis) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("export_time", isoUtc(nowMillis));
        JsonArray out = new JsonArray();
        for (Playlist p : playlists) {
            JsonObject po = new JsonObject();
            po.addProperty("name", p.getName());
            po.addProperty("description", "");
            JsonArray items = new JsonArray();
            List<Song> songs = songsByPlaylist.get(p.getId());
            long latest = p.getCreatedTime() / 1000;
            if (songs != null) {
                for (Song s : songs) {
                    long added = s.getAddedTime() / 1000;
                    latest = Math.max(latest, added);
                    JsonObject item = new JsonObject();
                    item.addProperty("fs_id", s.getFsId());
                    item.addProperty("server_filename", s.getTitle());
                    item.addProperty("path", s.getPath());
                    item.addProperty("size", s.getSize());
                    item.addProperty("category", CATEGORY_AUDIO);
                    item.addProperty("isdir", 0);
                    item.addProperty("local_mtime", added);
                    item.addProperty("server_mtime", added);
                    item.addProperty("md5", "");
                    item.addProperty("add_time", added);
                    item.add("duration", new JsonPrimitive(s.getDuration() / 1000.0));
                    items.add(item);
                }
            }
            po.add("items", items);
            po.addProperty("create_time", p.getCreatedTime() / 1000);
            po.addProperty("update_time", latest);
            out.add(po);
        }
        root.add("playlists", out);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(root);
    }

    static String isoUtc(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(millis));
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        return e.getAsString();
    }

    private static long longValue(JsonObject o, String key, long fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try {
            // getAsBigDecimal 能精确处理大整数和 "123" 字符串形式
            return e.getAsBigDecimal().longValue();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double doubleValue(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return 0;
        try {
            double d = e.getAsDouble();
            return Double.isFinite(d) && d > 0 ? d : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String fileName(String path) {
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
