package com.baidu.carplayer.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.baidu.carplayer.model.Playlist;
import com.baidu.carplayer.model.Song;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlaylistBackupCodecTest {

    private static String resource(String name) throws Exception {
        try (InputStream in = PlaylistBackupCodecTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void parsesWebBackup() throws Exception {
        PlaylistBackupCodec.ParsedBackup b = PlaylistBackupCodec.parse(resource("/backup/web-sample.json"));
        assertEquals(2, b.getPlaylists().size());
        BackupPlaylist first = b.getPlaylists().get(0);
        assertEquals("生肖", first.getName());
        assertEquals(1784516969L, first.getCreateTimeSeconds());
        assertEquals(3, first.getSongs().size());
        Song s = first.getSongs().get(0);
        assertEquals(950121540866953L, s.getFsId());           // 大整数不丢精度
        assertEquals("01.子鼠·尘埃里的星光.mp3", s.getTitle());
        assertEquals("/音乐/suno/生肖/01.子鼠·尘埃里的星光.mp3", s.getPath());
        assertEquals(4569270L, s.getSize());
        assertEquals(196512L, s.getDuration());                // 秒 → 毫秒
        // 文件顺序
        assertEquals("02.丑牛·雾中的界线.mp3", first.getSongs().get(1).getTitle());
        assertEquals("周.转", b.getPlaylists().get(1).getName());
        assertEquals(2319948456587L, b.getPlaylists().get(1).getSongs().get(2).getFsId());
        assertEquals(6, b.totalSongs());
        assertEquals(0, b.totalSkipped());
    }

    @Test public void parsesSinglePlaylistObjectForm() throws Exception {
        // Web 端单列表导出写 "playlist"（单数、对象），而不是 "playlists"（数组）
        String json = "{"
                + "\"version\":\"1.0\",\"export_time\":\"2026-11-13T07:04:58.569Z\","
                + "\"playlist\":{\"name\":\"我们动画片里面的好朋友\","
                + "\"description\":\"Created from web interface\","
                + "\"items\":["
                + "{\"fs_id\":371687575009887,\"server_filename\":\"01.勇敢狗狗，出发！.mp3\","
                + "\"path\":\"/音乐/宝宝专辑/我们动画片里的好朋友/01.勇敢狗狗，出发！.mp3\","
                + "\"size\":1885642,\"category\":2,\"isdir\":0,\"md5\":\"90688cb49n6dbbcbb5b0cdc502275f75\","
                + "\"add_time\":1794553444},"
                + "{\"fs_id\":40889745777257,\"server_filename\":\"02.潜入大海深处.mp3\","
                + "\"path\":\"/音乐/宝宝专辑/我们动画片里的好朋友/02.潜入大海深处.mp3\","
                + "\"size\":1970890,\"isdir\":0,\"add_time\":1794553445}"
                + "]}}";
        PlaylistBackupCodec.ParsedBackup b = PlaylistBackupCodec.parse(json);
        assertEquals(1, b.getPlaylists().size());
        BackupPlaylist p = b.getPlaylists().get(0);
        assertEquals("我们动画片里面的好朋友", p.getName());
        assertEquals(2, p.getSongs().size());
        assertEquals(371687575009887L, p.getSongs().get(0).getFsId());
        assertEquals("01.勇敢狗狗，出发！.mp3", p.getSongs().get(0).getTitle());
        assertEquals("/音乐/宝宝专辑/我们动画片里的好朋友/02.潜入大海深处.mp3",
                p.getSongs().get(1).getPath());
    }

    @Test public void parsesSinglePlaylistAsArrayUnderSingularKey() throws Exception {
        // 宽容处理：单数键也接受数组
        String json = "{\"version\":\"1.0\",\"playlist\":["
                + "{\"name\":\"A\",\"items\":[{\"fs_id\":1,\"server_filename\":\"a.mp3\",\"path\":\"/a.mp3\"}]},"
                + "{\"name\":\"B\",\"items\":[{\"fs_id\":2,\"server_filename\":\"b.mp3\",\"path\":\"/b.mp3\"}]}"
                + "]}";
        PlaylistBackupCodec.ParsedBackup b = PlaylistBackupCodec.parse(json);
        assertEquals(2, b.getPlaylists().size());
        assertEquals("A", b.getPlaylists().get(0).getName());
        assertEquals("B", b.getPlaylists().get(1).getName());
    }

    @Test public void recentSongsAreIgnored() throws Exception {
        // 样本含 recentSongs，解析结果只有 playlists
        PlaylistBackupCodec.ParsedBackup b = PlaylistBackupCodec.parse(resource("/backup/web-sample.json"));
        for (BackupPlaylist p : b.getPlaylists()) assertFalse(p.getName().contains("recent"));
        assertEquals(2, b.getPlaylists().size());
    }

    @Test public void rejectsInvalidFiles() {
        for (String text : new String[]{null, "", "not json", "[]", "{}", "{\"playlists\":3}", "{\"playlists\":[]}"}) {
            try {
                PlaylistBackupCodec.parse(text);
                fail("should reject: " + text);
            } catch (PlaylistBackupCodec.BackupFormatException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
    }

    @Test public void invalidFileMessage() {
        try {
            PlaylistBackupCodec.parse("{oops");
            fail();
        } catch (PlaylistBackupCodec.BackupFormatException e) {
            assertEquals(PlaylistBackupCodec.INVALID_FILE, e.getMessage());
        }
    }

    @Test public void skipsInvalidAndDuplicateItems() throws Exception {
        String json = "\uFEFF{\"playlists\":[{\"name\":\"  \",\"items\":["
                + "{\"fs_id\":1,\"server_filename\":\"a.mp3\",\"path\":\"/a.mp3\"},"
                + "{\"server_filename\":\"no-id.mp3\"},"
                + "{\"fs_id\":2,\"server_filename\":\"dir\",\"isdir\":1},"
                + "{\"fs_id\":1,\"server_filename\":\"dup.mp3\"},"
                + "{\"fs_id\":\"3\",\"path\":\"/x/b.mp3\"},"
                + "7"
                + "]}]}";
        PlaylistBackupCodec.ParsedBackup b = PlaylistBackupCodec.parse(json);
        BackupPlaylist p = b.getPlaylists().get(0);
        assertEquals(PlaylistBackupCodec.UNNAMED, p.getName());
        assertEquals(2, p.getSongs().size());
        assertEquals("a.mp3", p.getSongs().get(0).getTitle());
        assertEquals(3L, p.getSongs().get(1).getFsId());
        assertEquals("b.mp3", p.getSongs().get(1).getTitle());   // 缺文件名时取路径
        assertEquals(4, p.getSkippedItems());
    }

    @Test public void writesWebFormatAndRoundTrips() throws Exception {
        Playlist p = new Playlist("id-1", "生肖", 1784516969000L);
        Song a = new Song(950121540866953L, "id-1", "01.子鼠.mp3", "/音乐/01.子鼠.mp3", 4569270, 1784516970000L);
        a.setDuration(196512);
        Song b = new Song(2319948456587L, "id-1", "02.丑牛.mp3", "/音乐/02.丑牛.mp3", 100, 1784516971000L);
        Map<String, List<Song>> songs = new HashMap<>();
        songs.put("id-1", Arrays.asList(a, b));
        Playlist empty = new Playlist("id-2", "空列表", 1000L);

        String text = PlaylistBackupCodec.write(Arrays.asList(p, empty), songs, 1794189595036L);
        assertTrue("中文不转义", text.contains("生肖"));
        assertFalse(text.contains("recentSongs"));

        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        assertEquals("1.0", root.get("version").getAsString());
        assertEquals("2026-11-09T01:59:55.036Z", root.get("export_time").getAsString());
        JsonObject item = root.getAsJsonArray("playlists").get(0).getAsJsonObject()
                .getAsJsonArray("items").get(0).getAsJsonObject();
        for (String key : new String[]{"fs_id", "server_filename", "path", "size", "category", "isdir",
                "local_mtime", "server_mtime", "md5", "add_time", "duration"}) {
            assertTrue("missing " + key, item.has(key));
        }
        assertEquals("950121540866953", item.get("fs_id").getAsString());
        assertEquals(196.512, item.get("duration").getAsDouble(), 1e-9);
        assertEquals(1784516970L, item.get("add_time").getAsLong());
        JsonObject po = root.getAsJsonArray("playlists").get(0).getAsJsonObject();
        assertEquals(1784516969L, po.get("create_time").getAsLong());
        assertEquals(1784516971L, po.get("update_time").getAsLong());

        PlaylistBackupCodec.ParsedBackup back = PlaylistBackupCodec.parse(text);
        assertEquals(2, back.getPlaylists().size());
        List<Song> round = back.getPlaylists().get(0).getSongs();
        assertEquals(2, round.size());
        for (int i = 0; i < 2; i++) {
            Song src = songs.get("id-1").get(i), got = round.get(i);
            assertEquals(src.getFsId(), got.getFsId());
            assertEquals(src.getTitle(), got.getTitle());
            assertEquals(src.getPath(), got.getPath());
            assertEquals(src.getSize(), got.getSize());
            assertEquals(src.getDuration(), got.getDuration());
        }
        assertEquals("空列表", back.getPlaylists().get(1).getName());
        assertEquals(0, back.getPlaylists().get(1).getSongs().size());
    }

    @Test public void webSampleRoundTripsThroughWriter() throws Exception {
        PlaylistBackupCodec.ParsedBackup b = PlaylistBackupCodec.parse(resource("/backup/web-sample.json"));
        Map<String, List<Song>> songs = new HashMap<>();
        java.util.ArrayList<Playlist> playlists = new java.util.ArrayList<>();
        for (int i = 0; i < b.getPlaylists().size(); i++) {
            BackupPlaylist bp = b.getPlaylists().get(i);
            playlists.add(new Playlist("p" + i, bp.getName(), bp.getCreateTimeSeconds() * 1000));
            songs.put("p" + i, bp.getSongs());
        }
        PlaylistBackupCodec.ParsedBackup again = PlaylistBackupCodec.parse(
                PlaylistBackupCodec.write(playlists, songs, 0));
        for (int i = 0; i < b.getPlaylists().size(); i++) {
            List<Song> x = b.getPlaylists().get(i).getSongs(), y = again.getPlaylists().get(i).getSongs();
            assertEquals(x.size(), y.size());
            for (int j = 0; j < x.size(); j++) {
                assertEquals(x.get(j).getFsId(), y.get(j).getFsId());
                assertEquals(x.get(j).getDuration(), y.get(j).getDuration());
            }
        }
    }
}
