package com.baidu.carplayer.manager;

import android.content.Context;

import android.text.TextUtils;

import com.baidu.carplayer.database.DatabaseManager;
import com.baidu.carplayer.model.FileItem;
import com.baidu.carplayer.model.FileListResponse;
import com.baidu.carplayer.model.Playlist;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.network.BaiduPanService;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import retrofit2.Response;

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

    /**
     * 刷新播放列表
     * 算法核心：从现有歌曲路径反推根文件夹，然后重新递归扫描并重建列表
     */
    public void refreshPlaylist(String playlistId, BaiduPanService service, String accessToken, OnResultListener listener) {
        new Thread(() -> {
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
                List<FileItem> cloudFiles = scanAllRoots(service, accessToken, scanRoots);

                // 4. 清空当前列表并重建
                int newCount = rebuildPlaylist(playlistId, cloudFiles);

                if (listener != null) {
                    listener.onSuccess("刷新完成，共 " + newCount + " 首歌曲");
                }

            } catch (Exception e) {
                e.printStackTrace();
                if (listener != null) {
                    listener.onError("刷新失败: " + e.getMessage());
                }
            }
        }).start();
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
    private Set<String> calculateSyncRoots(List<Song> songs) {
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

    private String getParentPath(String path) {
        if (TextUtils.isEmpty(path) || path.equals("/")) return null;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash == 0) return "/"; // 父路径是根
        if (lastSlash > 0) return path.substring(0, lastSlash);
        return null;
    }

    /**
     * 扫描所有根路径
     */
    private List<FileItem> scanAllRoots(BaiduPanService service, String accessToken, Set<String> roots) throws Exception {
        List<FileItem> allFiles = new ArrayList<>();
        Set<String> scannedPaths = new HashSet<>();
        // 这里可以使用线程池并发扫描，提高效率
        // 为简单起见，暂时串行扫描
        for (String root : roots) {
            scanFolderRecursive(service, accessToken, root, allFiles, scannedPaths);
        }
        return allFiles;
    }

    /**
     * 递归扫描单个文件夹（支持分页，防止重复扫描）
     */
    private void scanFolderRecursive(BaiduPanService service, String accessToken, String path, List<FileItem> result, Set<String> scannedPaths) throws Exception {
        // 防止重复扫描同一个路径
        if (scannedPaths.contains(path)) {
            return;
        }
        scannedPaths.add(path);

        int start = 0;
        final int limit = 1000;
        boolean hasMore = true;

        while (hasMore) {
            // 同步调用Retrofit接口，处理分页
            Response<FileListResponse> response = service.getFileList(
                    "list", accessToken, path, "name", start, limit, 1, 0, 0
            ).execute();

            if (response.isSuccessful() && response.body() != null) {
                List<FileItem> list = response.body().getList();
                
                if (list != null && !list.isEmpty()) {
                    for (FileItem item : list) {
                        if (item.getIsdir() == 1) {
                            // 递归扫描子文件夹
                            scanFolderRecursive(service, accessToken, item.getPath(), result, scannedPaths);
                        } else if (item.isAudioFile()) {
                            result.add(item);
                        }
                    }

                    // 检查是否还有更多文件
                    if (list.size() < limit) {
                        hasMore = false;
                    } else {
                        start += limit;
                    }
                } else {
                    hasMore = false;
                }
            } else {
                // 记录错误，停止当前文件夹扫描，但不中断整个过程
                System.err.println("扫描失败: " + path + ", code=" + response.code());
                hasMore = false;
            }
        }
    }

    /**
     * 清空当前列表并重建
     * 返回新列表的歌曲数量
     */
    private int rebuildPlaylist(String playlistId, List<FileItem> cloudFiles) {
        // 1. 删除当前列表中的所有歌曲
        databaseManager.getDatabase().songDao().deleteSongsByPlaylist(playlistId);
        
        // 2. 插入新的歌曲列表
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
        
        if (!newSongs.isEmpty()) {
            databaseManager.getDatabase().songDao().insertSongs(newSongs);
        }
        
        // 3. 更新播放列表统计
        Playlist playlist = databaseManager.getDatabase().playlistDao().getPlaylistById(playlistId);
        if (playlist != null) {
            playlist.setSongCount(newSongs.size());
            databaseManager.getDatabase().playlistDao().insertPlaylist(playlist);
        }
        
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