package com.baidu.carplayer.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.selection.SelectionModel;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 6.6：过滤后点击仍按稳定 ID 定位；选择状态与列表位置无关。 */
public class FilteredSelectionTest {
    private static Song song(long fsId, String title, String artist) {
        Song s = new Song(fsId, "p", title, "/m/" + title + ".mp3", 1L, 0L);
        s.setArtist(artist);
        return s;
    }

    private static final List<Song> SOURCE = Arrays.asList(
            song(10, "晴天", "周杰伦"),
            song(11, "七里香", "周杰伦"),
            song(12, "夜曲", null),
            song(13, "稻香", "<unknown>"));

    @Test
    public void filteredClickUsesIdNotFilteredPosition() {
        List<Song> filtered = CurrentPlaylistAdapter.filterSongs(SOURCE, "夜曲");
        assertEquals(1, filtered.size());
        // 过滤后第 0 行是 fsId=12，而不是原列表的第 0 行。
        assertEquals(12, filtered.get(0).getId());
        Song playable = filtered.get(0);
        assertEquals("播放页必须用这个 ID 定位", 2, Song.findSongPosition(SOURCE, playable.getId()));
    }

    @Test
    public void filterMatchesTitleOrArtistIgnoringCaseAndSpaces() {
        assertEquals(2, CurrentPlaylistAdapter.filterSongs(SOURCE, " 周杰伦 ").size());
        assertEquals(1, CurrentPlaylistAdapter.filterSongs(SOURCE, "夜").size());
        assertEquals(SOURCE.size(), CurrentPlaylistAdapter.filterSongs(SOURCE, "").size());
        assertEquals(0, CurrentPlaylistAdapter.filterSongs(SOURCE, "没有这首歌").size());
    }

    @Test
    public void filterIgnoresUnknownArtist() {
        List<Song> filtered = CurrentPlaylistAdapter.filterSongs(SOURCE, "<unknown>");
        assertEquals("歌手字段不应把占位符当作可搜索内容", 0, filtered.size());
    }

    @Test
    public void selectionSurvivesReordering() {
        SelectionModel<Song> selection = new SelectionModel<>(Song::getFsId);
        selection.setItems(SOURCE);
        selection.toggle(SOURCE.get(0));
        selection.toggle(SOURCE.get(2));
        assertEquals(2, selection.size());

        // 重新排序后，选中的仍然是同样两首歌
        List<Song> reordered = Arrays.asList(SOURCE.get(3), SOURCE.get(2), SOURCE.get(1), SOURCE.get(0));
        selection.setItems(reordered);
        assertEquals(2, selection.size());
        assertTrue(selection.isSelectedId(12));
        assertFalse(selection.isSelectedId(13));

        List<Song> selected = selection.getSelectedItems();
        assertEquals("顺序跟随当前列表", Arrays.asList(12L, 10L),
                Arrays.asList(selected.get(0).getId(), selected.get(1).getId()));
    }

    @Test
    public void selectionPrunesRemovedSongs() {
        SelectionModel<Song> selection = new SelectionModel<>(Song::getFsId);
        selection.setItems(SOURCE);
        selection.selectAll(song -> true);
        assertEquals(4, selection.size());
        selection.setItems(Arrays.asList(SOURCE.get(0)));
        assertEquals("列表外的选中项必须被清理", 1, selection.size());
        assertTrue(selection.isSelectedId(10));
    }
}
