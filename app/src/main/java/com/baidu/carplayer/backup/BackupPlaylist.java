package com.baidu.carplayer.backup;

import com.baidu.carplayer.model.Song;

import java.util.Collections;
import java.util.List;

/** 备份文件中的一个播放列表（解析结果，尚未写库）。 */
public final class BackupPlaylist {
    private final String name;
    private final String description;
    private final List<Song> songs;
    private final long createTimeSeconds;
    private final int skippedItems;

    public BackupPlaylist(String name, String description, List<Song> songs,
                          long createTimeSeconds, int skippedItems) {
        this.name = name;
        this.description = description;
        this.songs = Collections.unmodifiableList(songs);
        this.createTimeSeconds = createTimeSeconds;
        this.skippedItems = skippedItems;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    /** 按文件顺序排列的歌曲；playlistId 为空，写库时再填。 */
    public List<Song> getSongs() { return songs; }
    public long getCreateTimeSeconds() { return createTimeSeconds; }
    /** 被跳过的无效条目（缺 fs_id、目录、重复）数量。 */
    public int getSkippedItems() { return skippedItems; }
}
