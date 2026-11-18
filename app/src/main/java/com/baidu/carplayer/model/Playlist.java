package com.baidu.carplayer.model;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

/**
 * 播放列表实体类
 */
@Entity(tableName = "playlists")
public class Playlist {
    @PrimaryKey
    @NonNull
    public String id;
    
    public String name;
    public long createdTime;
    public int songCount;

    /** 列表页的显示顺序，升序；数值只用于排序，不要求连续。 */
    public int sortOrder;
    
    public Playlist() {
    }
    
    @Ignore
    public Playlist(@NonNull String id, String name, long createdTime) {
        this(id, name, createdTime, 0);
    }

    @Ignore
    public Playlist(@NonNull String id, String name, long createdTime, int sortOrder) {
        this.id = id;
        this.name = name;
        this.createdTime = createdTime;
        this.sortOrder = sortOrder;
    }
    
    // Getters and Setters
    @NonNull
    public String getId() {
        return id;
    }
    
    public void setId(@NonNull String id) {
        this.id = id;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public long getCreatedTime() {
        return createdTime;
    }
    
    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }
    
    public int getSongCount() {
        return songCount;
    }
    
    public void setSongCount(int songCount) {
        this.songCount = songCount;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}