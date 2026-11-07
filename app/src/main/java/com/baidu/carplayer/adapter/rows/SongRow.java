package com.baidu.carplayer.adapter.rows;

import com.baidu.carplayer.model.Song;

/** 歌曲行的不可变渲染模型；id 为稳定的 fsId。 */
public final class SongRow {
    public final long id;
    public final String title;
    public final String subtitle;
    public final boolean selectionMode;
    public final boolean selected;
    public final boolean playing;

    public SongRow(long id, String title, String subtitle, boolean selectionMode, boolean selected, boolean playing) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.selectionMode = selectionMode;
        this.selected = selected;
        this.playing = playing;
    }

    /** 歌曲列表页：标题 + 路径。 */
    public static SongRow forLibrary(Song song, boolean selectionMode, boolean selected, long playingId) {
        return new SongRow(song.getFsId(), song.getTitle(), song.getPath(),
                selectionMode, selectionMode && selected, song.getFsId() == playingId);
    }

    /** 播放队列：标题追加有效的歌手名。 */
    public static SongRow forQueue(Song song, long playingId) {
        return new SongRow(song.getFsId(), queueTitle(song), song.getPath(), false, false,
                song.getFsId() == playingId);
    }

    static String queueTitle(Song song) {
        String title = song.getTitle() == null ? "" : song.getTitle();
        String artist = song.getArtist();
        if (artist != null && !artist.isEmpty() && !artist.equals("<unknown>")) {
            return title + " - " + artist;
        }
        return title;
    }
}
