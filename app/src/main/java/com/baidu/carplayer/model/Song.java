package com.baidu.carplayer.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 歌曲实体类
 */
@Entity(tableName = "songs", primaryKeys = {"fsId", "playlistId"})
public class Song {
    public long fsId;
    @NonNull
    public String playlistId = "";
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
    
    /**
     * 从完整路径中提取目录路径
     * 例如：/music/album1/song.mp3 -> /music/album1
     */
    public static String getDirectoryPath(String fullPath) {
        if (fullPath == null || fullPath.isEmpty()) {
            return "";
        }
        int lastSlash = fullPath.lastIndexOf('/');
        if (lastSlash > 0) {
            return fullPath.substring(0, lastSlash);
        }
        return "";
    }
    
    /**
     * 按歌曲标题排序，标题相同时按目录、完整路径和歌曲ID稳定排序。
     * @param songs 歌曲列表
     * @param ascending true为正序，false为倒序
     */
    public static void sortSongs(List<Song> songs, boolean ascending) {
        final Collator collator = Collator.getInstance(Locale.CHINESE);
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(songs, new Comparator<Song>() {
            @Override
            public int compare(Song s1, Song s2) {
                // 与添加歌曲路径页面保持一致：默认按歌曲名称排序。
                int result = collator.compare(s1.getTitle(), s2.getTitle());
                // Collator 判定相等时继续使用原始字符串和完整路径，
                // 避免数据库返回顺序或添加顺序导致列表看起来随机。
                if (result == 0) {
                    result = s1.getTitle().compareTo(s2.getTitle());
                }
                if (result == 0) {
                    result = collator.compare(getDirectoryPath(s1.getPath()), getDirectoryPath(s2.getPath()));
                }
                if (result == 0) {
                    result = s1.getPath().compareTo(s2.getPath());
                }
                if (result == 0) {
                    result = Long.compare(s1.getFsId(), s2.getFsId());
                }
                return ascending ? result : -result;
            }
        });
    }
    
    /**
     * 根据歌曲ID在列表中查找位置
     * @param songs 歌曲列表
     * @param songId 歌曲ID
     * @return 歌曲在列表中的位置，如果未找到返回-1
     */
    public static int findSongPosition(List<Song> songs, long songId) {
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).getFsId() == songId) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * 去除文件扩展名
     * @param title 原始标题
     * @return 去除扩展名后的标题
     */
    public static String removeFileExtension(String title) {
        if (title == null || title.isEmpty()) {
            return title;
        }
        
        int lastDotIndex = title.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return title.substring(0, lastDotIndex);
        }
        return title;
    }
    
    /**
     * 获取去除扩展名的标题
     * @return 去除扩展名后的标题
     */
    public String getTitleWithoutExtension() {
        return removeFileExtension(this.title);
    }
    
    /**
     * 从文件路径中提取文件扩展名
     * @param filePath 文件路径
     * @return 文件扩展名（大写），如果没有扩展名返回空字符串
     */
    public static String getFileExtension(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }
        
        int lastDotIndex = filePath.lastIndexOf('.');
        int lastSlashIndex = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        
        // 确保点号在最后一个斜杠之后
        if (lastDotIndex > lastSlashIndex && lastDotIndex < filePath.length() - 1) {
            return filePath.substring(lastDotIndex + 1).toUpperCase();
        }
        return "";
    }
    
    /**
     * 获取当前歌曲的文件扩展名
     * @return 文件扩展名（大写）
     */
    public String getExtension() {
        return getFileExtension(this.path);
    }
}