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
