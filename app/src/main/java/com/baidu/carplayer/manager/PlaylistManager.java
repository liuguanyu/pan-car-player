package com.baidu.carplayer.manager;

import android.content.Context;

import android.text.TextUtils;

import com.baidu.carplayer.concurrent.AppExecutors;
import com.baidu.carplayer.database.DatabaseManager;
import com.baidu.carplayer.model.FileItem;
import com.baidu.carplayer.model.Playlist;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.repository.CloudFileRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;


/**
 * 播放列表管理器
 */
public class PlaylistManager {
    private final DatabaseManager databaseManager;
    private final Executor diskExecutor;
    private final Executor networkExecutor;
    
    public PlaylistManager(Context context) {
        this(DatabaseManager.getInstance(context),
                AppExecutors.getInstance().diskIO(),
                AppExecutors.getInstance().networkIO());
    }

    /** 测试或特殊场景下可注入执行器。 */
    public PlaylistManager(DatabaseManager databaseManager, Executor diskExecutor, Executor networkExecutor) {
        this.databaseManager = databaseManager;
        this.diskExecutor = diskExecutor;
        this.networkExecutor = networkExecutor;
    }
    
    /**
     * 创建播放列表
     */
    public void createPlaylist(String name, OnResultListener listener) {
        diskExecutor.execute(() -> {
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
        });
    }
    
    /**
     * 更新播放列表
     */
    public void updatePlaylist(Playlist playlist, OnResultListener listener) {
        diskExecutor.execute(() -> {
            try {
                // 必须原地 UPDATE：REPLACE 会先删除旧行并级联删除该列表的全部歌曲。
                databaseManager.getDatabase().playlistDao().updatePlaylist(playlist);
                
                if (listener != null) {
                    listener.onSuccess(playlist);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        });
    }
    
    /**
     * 删除播放列表
     */
    public void deletePlaylist(Playlist playlist, OnResultListener listener) {
        diskExecutor.execute(() -> {
            try {
                deletePlaylistNow(playlist);
                
                if (listener != null) {
                    listener.onSuccess(null);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        });
    }
    
    /** 在一个事务中删除播放列表及其歌曲（外键也会级联删除，这里显式删除以兼容旧数据）。 */
    private void deletePlaylistNow(Playlist playlist) {
        databaseManager.getDatabase().runInTransaction(() -> {
            databaseManager.getDatabase().songDao().deleteSongsByPlaylist(playlist.getId());
            databaseManager.getDatabase().playlistDao().deletePlaylist(playlist);
        });
    }

    /**
     * 删除播放列表（通过ID）
     */
    public void deletePlaylist(String playlistId, OnResultListener listener) {
        diskExecutor.execute(() -> {
            try {
                Playlist playlist = databaseManager.getDatabase().playlistDao().getPlaylistById(playlistId);
                if (playlist != null) {
                    deletePlaylistNow(playlist);
                    if (listener != null) {
                        listener.onSuccess(null);
                    }
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
        });
    }
    
    /**
     * 添加歌曲到播放列表
     */
    public void addSongToPlaylist(String playlistId, Song song, OnResultListener listener) {
        diskExecutor.execute(() -> {
            try {
                // 检查歌曲是否已经在该播放列表中
                Song existingSong = databaseManager.getDatabase().songDao().getSong(song.getFsId(), playlistId);
                if (existingSong != null) {
                    if (listener != null) {
                        listener.onError("歌曲已存在于该播放列表中");
                    }
                    return;
                }

                // 需要创建一个新的Song对象，避免修改原对象影响其他列表
                Song newSong = new Song();
                newSong.setFsId(song.getFsId());
                newSong.setPlaylistId(playlistId);
                newSong.setTitle(song.getTitle());
                newSong.setPath(song.getPath());
                newSong.setSize(song.getSize());
                newSong.setAddedTime(System.currentTimeMillis());
                newSong.setArtist(song.getArtist());
                newSong.setAlbum(song.getAlbum());
                newSong.setDuration(song.getDuration());
                newSong.setCoverUrl(song.getCoverUrl());
                
                databaseManager.getDatabase().runInTransaction(() -> {
                    databaseManager.getDatabase().songDao().insertSong(newSong);
                    databaseManager.getDatabase().playlistDao().recountSongs(playlistId);
                });
                
                if (listener != null) {
                    listener.onSuccess(newSong);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        });
    }
    
    /**
     * 在一个事务中批量添加歌曲：已存在的跳过，歌单不存在时整体失败。
     * 成功回调的结果为实际新增的数量（Integer）。
     */
    public void addSongsToPlaylist(String playlistId, List<Song> songs, OnResultListener listener) {
        diskExecutor.execute(() -> {
            try {
                int added = addSongsNow(playlistId, songs);
                if (listener != null) {
                    listener.onSuccess(added);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        });
    }

    /** 同步批量添加，必须在后台线程调用。返回实际新增数量。 */
    int addSongsNow(String playlistId, List<Song> songs) {
        return databaseManager.getDatabase().runInTransaction(() -> {
            if (databaseManager.getDatabase().playlistDao().getPlaylistById(playlistId) == null) {
                throw new IllegalStateException("播放列表不存在，可能已被删除");
            }
            List<Song> toInsert = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            long now = System.currentTimeMillis();
            for (Song song : songs) {
                if (!seen.add(song.getFsId())
                        || databaseManager.getDatabase().songDao().getSong(song.getFsId(), playlistId) != null) {
                    continue;
                }
                Song copy = new Song(song.getFsId(), playlistId, song.getTitle(), song.getPath(),
                        song.getSize(), now + toInsert.size());
                copy.setArtist(song.getArtist());
                copy.setAlbum(song.getAlbum());
                copy.setDuration(song.getDuration());
                copy.setCoverUrl(song.getCoverUrl());
                toInsert.add(copy);
            }
            if (!toInsert.isEmpty()) {
                databaseManager.getDatabase().songDao().insertSongs(toInsert);
            }
            databaseManager.getDatabase().playlistDao().recountSongs(playlistId);
            return toInsert.size();
        });
    }

    /**
     * 从播放列表移除歌曲
     */
    public void removeSongFromPlaylist(Song song, OnResultListener listener) {
        diskExecutor.execute(() -> {
            try {
                String playlistId = song.getPlaylistId();
                databaseManager.getDatabase().runInTransaction(() -> {
                    databaseManager.getDatabase().songDao().deleteSong(song.getFsId(), playlistId);
                    databaseManager.getDatabase().playlistDao().recountSongs(playlistId);
                });
                
                if (listener != null) {
                    listener.onSuccess(null);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        });
    }
    
    /**
     * 从播放列表批量移除歌曲
     */
    public void removeSongsFromPlaylist(List<Song> songs, String playlistId, OnResultListener listener) {
        if (songs == null || songs.isEmpty()) {
            if (listener != null) listener.onSuccess(null);
            return;
        }
        diskExecutor.execute(() -> {
            try {
                databaseManager.getDatabase().runInTransaction(() -> {
                    for (Song song : songs) {
                        databaseManager.getDatabase().songDao().deleteSong(song.getFsId(), playlistId);
                    }
                    databaseManager.getDatabase().playlistDao().recountSongs(playlistId);
                });
                
                if (listener != null) {
                    listener.onSuccess(null);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        });
    }
    
    /**
     * 获取所有播放列表
     */
    public void getAllPlaylists(OnPlaylistsLoadListener listener) {
        diskExecutor.execute(() -> {
            try {
                List<Playlist> playlists = databaseManager.getAllPlaylists();
                
                // 为每个播放列表更新实际的歌曲数量
                databaseManager.getDatabase().runInTransaction(() -> {
                    for (Playlist playlist : playlists) {
                        int actualCount = databaseManager.getDatabase().songDao().getSongCount(playlist.getId());
                        // 如果歌曲数量不一致，更新数据库
                        if (playlist.getSongCount() != actualCount) {
                            playlist.setSongCount(actualCount);
                            databaseManager.getDatabase().playlistDao().updateSongCount(playlist.getId(), actualCount);
                        }
                    }
                });
                
                if (listener != null) {
                    listener.onSuccess(playlists);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        });
    }
    
    /**
     * 获取播放列表中的歌曲
     */
    public void getSongsForPlaylist(String playlistId, OnSongsLoadListener listener) {
        diskExecutor.execute(() -> {
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
        });
    }

    /**
     * 获取播放列表详情
     */
    public void getPlaylist(String playlistId, OnPlaylistLoadListener listener) {
        diskExecutor.execute(() -> {
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
        });
    }

    /**
     * 刷新播放列表
     * 算法核心：从现有歌曲路径反推根文件夹，然后重新递归扫描并重建列表
     */
    public void refreshPlaylist(String playlistId, BaiduPanService service, String accessToken, OnResultListener listener) {
        refreshPlaylist(playlistId, new CloudFileRepository(service, () -> accessToken), listener);
    }

    /** 通过网盘仓库刷新；仓库每次请求都读取最新令牌。 */
    public void refreshPlaylist(String playlistId, CloudFileRepository cloud, OnResultListener listener) {
        networkExecutor.execute(() -> {
            try {
                // 1. 获取当前列表所有歌曲
                List<Song> currentSongs = databaseManager.getDatabase().songDao().getSongsByPlaylistSync(playlistId);
                if (currentSongs.isEmpty()) {
                    if (listener != null) {
                        listener.onError("列表为空，无法刷新");
                    }
                    return;
                }

                // 2. 计算需要扫描的根路径集合 (核心算法)
                Set<String> scanRoots = calculateSyncRoots(currentSongs);
                if (scanRoots.isEmpty()) {
                    if (listener != null) {
                        listener.onError("无法推断扫描路径");
                    }
                    return;
                }

                // 3. 递归扫描这些根路径，获取网盘最新文件列表
                List<FileItem> cloudFiles = cloud.scanRoots(scanRoots);

                // 4. 快照完整后再清空当前列表并重建；空快照视为异常，避免误删整个列表。
                if (cloudFiles.isEmpty()) {
                    if (listener != null) {
                        listener.onError("网盘中未找到歌曲，已保留原列表");
                    }
                    return;
                }
                int newCount = rebuildPlaylist(playlistId, cloudFiles);

                if (listener != null) {
                    listener.onSuccess("刷新完成，共 " + newCount + " 首歌曲");
                }

            } catch (Exception e) {
                android.util.Log.e("PlaylistManager", "刷新失败", e);
                if (listener != null) {
                    listener.onError("刷新失败: " + com.baidu.carplayer.network.LogRedactor.redact(e.getMessage()));
                }
            }
        });
    }

    /**
     * 核心算法：推导最小扫描根集合
     * 策略：
     * 1. 提取所有歌曲的直接父文件夹路径
     * 2. 相互比较，如果路径A包含路径B（即A是B的父级），则只保留A
     * 3. 最终留下的就是最顶层的文件夹集合
     *
     * 这样可以确保：
     * - 扫描范围覆盖所有现有文件
     * - 如果某个父文件夹下有新创建的子文件夹，递归扫描父文件夹时能发现它
     */
    /** 纯函数：由已有歌曲路径推导需要扫描的最小根目录集合（去掉被父目录包含的子目录）。 */
    static Set<String> calculateSyncRoots(List<Song> songs) {
        // 1. 收集所有唯一的父路径
        Set<String> parentPaths = new HashSet<>();
        for (Song song : songs) {
            String parent = getParentPath(song.getPath());
            if (!TextUtils.isEmpty(parent)) {
                parentPaths.add(parent);
            }
        }

        // 2. 转换为List以便排序和比较
        List<String> sortedPaths = new ArrayList<>(parentPaths);
        // 按长度排序，短的在前（父目录通常比子目录短）
        // 这样比较时效率更高
        java.util.Collections.sort(sortedPaths);

        // 3. 过滤被包含的路径
        Set<String> roots = new HashSet<>();
        for (String path : sortedPaths) {
            boolean isChild = false;
            // 检查当前path是否是roots中某个路径的子路径
            for (String root : roots) {
                // 判断逻辑：root是path的前缀，且path的下一个字符是'/'（或者是完全相等）
                // 例如 root="/music", path="/music/rock" -> true
                // 例如 root="/music", path="/musical" -> false
                if (path.startsWith(root)) {
                    if (path.length() == root.length() || path.charAt(root.length()) == '/') {
                        isChild = true;
                        break;
                    }
                }
            }
            
            if (!isChild) {
                roots.add(path);
            }
        }

        return roots;
    }

    static String getParentPath(String path) {
        if (TextUtils.isEmpty(path) || path.equals("/")) return null;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash == 0) return "/"; // 父路径是根
        if (lastSlash > 0) return path.substring(0, lastSlash);
        return null;
    }

    /**
     * 清空当前列表并重建
     * 返回新列表的歌曲数量
     */
    private int rebuildPlaylist(String playlistId, List<FileItem> cloudFiles) {
        // 先构造完整快照，再在一个事务中替换旧数据。
        List<Song> newSongs = new ArrayList<>();
        for (FileItem item : cloudFiles) {
            if (item.isAudioFile()) {
                Song song = new Song();
                song.setPlaylistId(playlistId);
                song.setFsId(item.getFsId());
                song.setTitle(item.getServerFilename());
                song.setPath(item.getPath());
                song.setSize(item.getSize());
                song.setAddedTime(System.currentTimeMillis());
                newSongs.add(song);
            }
        }
        
        databaseManager.getDatabase().runInTransaction(() -> {
            databaseManager.getDatabase().songDao().deleteSongsByPlaylist(playlistId);
            if (!newSongs.isEmpty()) {
                databaseManager.getDatabase().songDao().insertSongs(newSongs);
            }
            databaseManager.getDatabase().playlistDao().recountSongs(playlistId);
        });
        
        return newSongs.size();
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