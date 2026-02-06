package com.baidu.carplayer.model;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * 歌曲实体类
 */
@Entity(tableName = "songs")
public class Song {
    @PrimaryKey
    public long fsId;
    public String playlistId;
    public String title;
    public String path;
    public long size;
    public long addedTime;
    
    // 扩展字段（可选）
    public String artist;
    public String album;
    public long duration;
    public String coverUrl;
    
    public Song() {
    }
    
    @Ignore
    public Song(long fsId, String playlistId, String title, String path, long size, long addedTime) {
        this.fsId = fsId;
        this.playlistId = playlistId;
        this.title = title;
        this.path = path;
        this.size = size;
        this.addedTime = addedTime;
    }
    
    // Getters and Setters
    public long getFsId() {
        return fsId;
    }
    
    public void setFsId(long fsId) {
        this.fsId = fsId;
    }
    
    public String getPlaylistId() {
        return playlistId;
    }
    
    public void setPlaylistId(String playlistId) {
        this.playlistId = playlistId;
    }
    
    public String getTitle() {
        return title;
    }
    
    public void setTitle(String title) {
        this.title = title;
    }
    
    public String getPath() {
        return path;
    }
    
    public void setPath(String path) {
        this.path = path;
    }
    
    public long getSize() {
        return size;
    }
    
    public void setSize(long size) {
        this.size = size;
    }
    
    public long getAddedTime() {
        return addedTime;
    }
    
    public void setAddedTime(long addedTime) {
        this.addedTime = addedTime;
    }
    
    public String getArtist() {
        return artist;
    }
    
    public void setArtist(String artist) {
        this.artist = artist;
    }
    
    public String getAlbum() {
        return album;
    }
    
    public void setAlbum(String album) {
        this.album = album;
    }
    
    public long getDuration() {
        return duration;
    }
    
    public void setDuration(long duration) {
        this.duration = duration;
    }
    
    public String getCoverUrl() {
        return coverUrl;
    }
    
    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }
    
    // 添加getId方法用于适配器
    public long getId() {
        return fsId;
    }
}