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
    
    public Playlist() {
    }
    
    @Ignore
    public Playlist(@NonNull String id, String name, long createdTime) {
        this.id = id;
        this.name = name;
        this.createdTime = createdTime;
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
}