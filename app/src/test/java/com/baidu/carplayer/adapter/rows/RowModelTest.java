package com.baidu.carplayer.adapter.rows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.baidu.carplayer.model.FileItem;
import com.baidu.carplayer.model.Song;

import org.junit.Test;

public class RowModelTest {
    private static Song song(long fsId, String title, String artist) {
        Song s = new Song(fsId, "p", title, "/m/" + title + ".mp3", 1L, 0L);
        s.setArtist(artist);
        return s;
    }

    @Test
    public void libraryRowUsesFsIdAndOnlyShowsSelectionInSelectionMode() {
        SongRow row = SongRow.forLibrary(song(7, "a", null), false, true, 7);
        assertEquals(7, row.id);
        assertTrue(row.playing);
        assertFalse("非多选模式不显示选中", row.selected);
        assertTrue(SongRow.forLibrary(song(7, "a", null), true, true, 1).selected);
    }

    @Test
    public void queueRowAppendsKnownArtistOnly() {
        assertEquals("a - b", SongRow.forQueue(song(1, "a", "b"), -1).title);
        assertEquals("a", SongRow.forQueue(song(1, "a", "<unknown>"), -1).title);
        assertEquals("a", SongRow.forQueue(song(1, "a", ""), -1).title);
    }

    private static FileItem file(long fsId, String name, boolean dir, long size) {
        FileItem f = new FileItem();
        f.setFsId(fsId);
        f.setServerFilename(name);
        f.setPath("/" + name);
        f.setIsdir(dir ? 1 : 0);
        f.setSize(size);
        return f;
    }

    @Test
    public void fileRowDecidesClickActions() {
        FileRow folder = FileRow.of(file(1, "dir", true, 0), false);
        assertEquals(FileRow.Kind.FOLDER, folder.kind);
        assertNull(folder.sizeText);
        assertEquals(FileRow.Action.OPEN, folder.clickAction());
        assertEquals(FileRow.Action.TOGGLE, folder.longClickAction());

        FileRow audio = FileRow.of(file(2, "x.mp3", false, 2048), true);
        assertEquals(FileRow.Kind.AUDIO, audio.kind);
        assertEquals("2.0 KB", audio.sizeText);
        assertTrue(audio.selected);
        assertEquals(FileRow.Action.TOGGLE, audio.clickAction());
        assertEquals(FileRow.Action.NONE, audio.longClickAction());
    }

    @Test
    public void formatSizeUnits() {
        assertEquals("10 B", FileRow.formatSize(10));
        assertEquals("1.5 MB", FileRow.formatSize(1536L * 1024));
        assertEquals("3.0 GB", FileRow.formatSize(3L * 1024 * 1024 * 1024));
    }
}
