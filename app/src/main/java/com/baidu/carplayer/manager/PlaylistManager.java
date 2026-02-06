package com.baidu.carplayer.manager;

import android.content.Context;

import com.baidu.carplayer.database.DatabaseManager;
import com.baidu.carplayer.model.Playlist;
import com.baidu.carplayer.model.Song;

import java.util.List;
import java.util.UUID;

/**
 * 播放列表管理器
 */
public class PlaylistManager {
    private DatabaseManager databaseManager;
    
    public PlaylistManager(Context context) {
        databaseManager = DatabaseManager.getInstance(context);
    }
    
    /**
     * 创建播放列表
     */
    public void createPlaylist(String name, OnResultListener listener) {
        new Thread(() -> {
            try {
                Playlist playlist = new Playlist();
                playlist.setId(UUID.randomUUID().toString());
                playlist.setName(name);
                playlist.setCreatedTime(System.currentTimeMillis());
                playlist.setSongCount(0);
                
                databaseManager.getDatabase().playlistDao().insertPlaylist(playlist);
                
                if (listener != null) {
                    listener.onSuccess(playlist);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        }).start();
    }
    
    /**
     * 更新播放列表
     */
    public void updatePlaylist(Playlist playlist, OnResultListener listener) {
        new Thread(() -> {
            try {
                databaseManager.getDatabase().playlistDao().insertPlaylist(playlist);
                
                if (listener != null) {
                    listener.onSuccess(playlist);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        }).start();
    }
    
    /**
     * 删除播放列表
     */
    public void deletePlaylist(Playlist playlist, OnResultListener listener) {
        new Thread(() -> {
            try {
                // 删除播放列表及其关联的歌曲
                databaseManager.getDatabase().songDao().deleteSongsByPlaylist(playlist.getId());
                databaseManager.getDatabase().playlistDao().deletePlaylist(playlist);
                
                if (listener != null) {
                    listener.onSuccess(null);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        }).start();
    }
    
    /**
     * 删除播放列表（通过ID）
     */
    public void deletePlaylist(String playlistId, OnResultListener listener) {
        new Thread(() -> {
            try {
                Playlist playlist = databaseManager.getDatabase().playlistDao().getPlaylistById(playlistId);
                if (playlist != null) {
                    deletePlaylist(playlist, listener);
                } else {
                    if (listener != null) {
                        listener.onError("播放列表不存在");
                    }
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        }).start();
    }
    
    /**
     * 添加歌曲到播放列表
     */
    public void addSongToPlaylist(String playlistId, Song song, OnResultListener listener) {
        new Thread(() -> {
            try {
                song.setPlaylistId(playlistId);
                song.setAddedTime(System.currentTimeMillis());
                
                databaseManager.getDatabase().songDao().insertSong(song);
                
                // 更新播放列表的歌曲数量
                Playlist playlist = databaseManager.getDatabase().playlistDao().getPlaylistById(playlistId);
                if (playlist != null) {
                    playlist.setSongCount(playlist.getSongCount() + 1);
                    databaseManager.getDatabase().playlistDao().insertPlaylist(playlist);
                }
                
                if (listener != null) {
                    listener.onSuccess(song);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        }).start();
    }
    
    /**
     * 从播放列表移除歌曲
     */
    public void removeSongFromPlaylist(Song song, OnResultListener listener) {
        new Thread(() -> {
            try {
                String playlistId = song.getPlaylistId();
                databaseManager.getDatabase().songDao().deleteSong(song);
                
                // 更新播放列表的歌曲数量
                Playlist playlist = databaseManager.getDatabase().playlistDao().getPlaylistById(playlistId);
                if (playlist != null) {
                    playlist.setSongCount(Math.max(0, playlist.getSongCount() - 1));
                    databaseManager.getDatabase().playlistDao().insertPlaylist(playlist);
                }
                
                if (listener != null) {
                    listener.onSuccess(null);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        }).start();
    }
    
    /**
     * 获取所有播放列表
     */
    public void getAllPlaylists(OnPlaylistsLoadListener listener) {
        new Thread(() -> {
            try {
                List<Playlist> playlists = databaseManager.getAllPlaylists();
                
                // 为每个播放列表更新实际的歌曲数量
                for (Playlist playlist : playlists) {
                    List<Song> songs = databaseManager.getDatabase().songDao().getSongsByPlaylistSync(playlist.getId());
                    int actualCount = songs.size();
                    
                    // 如果歌曲数量不一致，更新数据库
                    if (playlist.getSongCount() != actualCount) {
                        playlist.setSongCount(actualCount);
                        databaseManager.getDatabase().playlistDao().insertPlaylist(playlist);
                    }
                }
                
                if (listener != null) {
                    listener.onSuccess(playlists);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        }).start();
    }
    
    /**
     * 获取播放列表中的歌曲
     */
    public void getSongsForPlaylist(String playlistId, OnSongsLoadListener listener) {
        new Thread(() -> {
            try {
                List<Song> songs = databaseManager.getDatabase().songDao().getSongsByPlaylistSync(playlistId);
                if (listener != null) {
                    listener.onSuccess(songs);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 获取播放列表详情
     */
    public void getPlaylist(String playlistId, OnPlaylistLoadListener listener) {
        new Thread(() -> {
            try {
                Playlist playlist = databaseManager.getDatabase().playlistDao().getPlaylistById(playlistId);
                if (listener != null) {
                    listener.onSuccess(playlist);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        }).start();
    }
    
    public interface OnResultListener {
        void onSuccess(Object result);
        void onError(String error);
    }
    
    public interface OnPlaylistsLoadListener {
        void onSuccess(List<Playlist> playlists);
        void onError(String error);
    }
    
    public interface OnSongsLoadListener {
        void onSuccess(List<Song> songs);
        void onError(String error);
    }

    public interface OnPlaylistLoadListener {
        void onSuccess(Playlist playlist);
        void onError(String error);
    }
}