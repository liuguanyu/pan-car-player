package com.baidu.carplayer.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.baidu.carplayer.model.Song;
import com.google.gson.Gson;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 冷启动恢复依赖的持久化行为。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PlaybackPersistenceTest {
    private SharedPreferences prefs;
    private PlaybackPersistence persistence;

    @Before
    public void setUp() {
        prefs = ApplicationProvider.<Context>getApplicationContext()
                .getSharedPreferences("persistence-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        persistence = new PlaybackPersistence(prefs, new Gson());
    }

    private static List<Song> songs(int n) {
        List<Song> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Song song = new Song(100 + i, "p", "t" + i, "/m/" + i + ".mp3", 10L + i, 0L);
            song.setArtist("a" + i);
            list.add(song);
        }
        return list;
    }

    @Test
    public void emptyStoreHasNothingToResume() {
        assertNull(persistence.loadQueue());
        assertEquals(0, persistence.loadPosition());
        assertEquals(0L, persistence.loadProgress());
        assertNull(persistence.loadPlayModeName());
    }

    @Test
    public void saveAndLoadRoundTrip() {
        persistence.save(songs(3), 2, 45_000L, "RANDOM", true);

        List<Song> loaded = persistence.loadQueue();
        assertEquals(3, loaded.size());
        assertEquals("fsId 是歌曲身份，必须原样恢复", 102L, loaded.get(2).getFsId());
        assertEquals("/m/2.mp3", loaded.get(2).getPath());
        assertEquals("a2", loaded.get(2).getArtist());
        assertEquals(2, persistence.loadPosition());
        assertEquals(45_000L, persistence.loadProgress());
        assertEquals("RANDOM", persistence.loadPlayModeName());
    }

    @Test
    public void emptyQueueDoesNotOverwriteSavedSession() {
        persistence.save(songs(2), 1, 10L, "ORDER", false);
        persistence.save(Collections.<Song>emptyList(), 0, 0L, "ORDER", false);
        persistence.save(null, 0, 0L, "ORDER", false);
        assertEquals(2, persistence.loadQueue().size());
        assertEquals(1, persistence.loadPosition());
    }

    @Test
    public void corruptedQueueIsTreatedAsNoSession() {
        prefs.edit().putString("playlist", "{not json").commit();
        assertNull(persistence.loadQueue());
    }

    @Test
    public void restoredStaleIndexIsClampedByQueue() {
        // 旧会话保存的下标超出了当前队列长度（例如数据被重建）
        persistence.save(songs(5), 4, 0L, "ORDER", false);
        PlaybackQueue queue = new PlaybackQueue();
        queue.setSongs(songs(2));
        queue.moveTo(persistence.loadPosition());
        assertEquals("越界下标不应被采纳", 0, queue.getIndex());
    }
}
