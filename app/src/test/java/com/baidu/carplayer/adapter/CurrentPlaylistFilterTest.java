package com.baidu.carplayer.adapter;

import static org.junit.Assert.assertEquals;

import com.baidu.carplayer.model.Song;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class CurrentPlaylistFilterTest {
    private static Song song(long id, String title, String artist) {
        Song s = new Song(id, "p", title, "/" + title, 1L, 0L);
        s.setArtist(artist);
        return s;
    }

    @Test
    public void filtersByTitleOrArtistAndKeepsStableIds() {
        List<Song> all = Arrays.asList(song(10, "Hello", null), song(20, "World", "Adele"), song(30, "Other", null));
        List<Song> result = CurrentPlaylistAdapter.filterSongs(all, "  ADE ");
        assertEquals(1, result.size());
        // 过滤后第 0 行对应的是 ID 20，而不是原列表第 0 首
        assertEquals(20L, result.get(0).getId());
        assertEquals(3, CurrentPlaylistAdapter.filterSongs(all, "").size());
        assertEquals(1, CurrentPlaylistAdapter.filterSongs(all, "hello").size());
    }

    @Test
    public void nullTitleDoesNotCrash() {
        List<Song> all = Arrays.asList(song(1, null, null));
        assertEquals(0, CurrentPlaylistAdapter.filterSongs(all, "x").size());
    }
}
