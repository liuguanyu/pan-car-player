package com.baidu.carplayer.manager;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import com.baidu.carplayer.auth.BaiduAuthService;
import com.baidu.carplayer.model.DownloadLinkResponse;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.network.RetrofitClient;
import com.baidu.carplayer.utils.LrcParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * 歌词管理器 - 负责从百度网盘加载LRC歌词文件
 */
public class LyricsManager {
    private static final String TAG = "LyricsManager";
    
    private Context context;
    private BaiduAuthService authService;
    private BaiduPanService panService;
    
    public interface OnLyricsLoadListener {
        void onLyricsLoaded(List<LrcParser.LrcEntry> lrcEntries);
        void onLyricsLoadFailed(String error);
    }
    
    public LyricsManager(Context context) {
        this.context = context.getApplicationContext();
        this.authService = BaiduAuthService.getInstance(context);
        this.panService = RetrofitClient.getInstance().create(BaiduPanService.class);
    }
    
    /**
     * 加载歌曲的歌词
     * @param song 歌曲对象
     * @param listener 加载监听器
     */
    public void loadLyrics(Song song, OnLyricsLoadListener listener) {
        if (song == null || song.getPath() == null || song.getPath().isEmpty()) {
            listener.onLyricsLoadFailed("歌曲路径为空");
            return;
        }
        
        // 从歌曲路径中提取文件名（不含扩展名）
        String songPath = song.getPath();
        String fileName = extractFileNameWithoutExtension(songPath);
        
        if (fileName == null || fileName.isEmpty()) {
            listener.onLyricsLoadFailed("无法解析歌曲文件名");
            return;
        }
        
        // 构建LRC文件名
        String lrcFileName = fileName + ".lrc";
        
        // 获取歌曲所在目录
        String songDir = extractDirectory(songPath);
        
        Log.d(TAG, "搜索歌词文件: " + lrcFileName + " 在目录: " + songDir);
        
        // 异步搜索并加载歌词
        new LoadLyricsTask(lrcFileName, songDir, listener).execute();
    }
    
    /**
     * 从路径中提取文件名（不含扩展名）
     */
    private String extractFileNameWithoutExtension(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        
        // 获取最后一个斜杠后的部分
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash == -1) {
            lastSlash = path.lastIndexOf('\\');
        }
        
        String fileName = (lastSlash == -1) ? path : path.substring(lastSlash + 1);
        
        // 去除扩展名
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            fileName = fileName.substring(0, lastDot);
        }
        
        return fileName;
    }
    
    /**
     * 从路径中提取目录
     */
    private String extractDirectory(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        
        // 获取最后一个斜杠前的部分
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash == -1) {
            lastSlash = path.lastIndexOf('\\');
        }
        
        return (lastSlash == -1) ? "/" : path.substring(0, lastSlash);
    }
    
    /**
     * 异步加载歌词任务
     */
    private class LoadLyricsTask extends AsyncTask<Void, Void, List<LrcParser.LrcEntry>> {
        private String lrcFileName;
        private String searchDir;
        private OnLyricsLoadListener listener;
        private String errorMessage;
        
        public LoadLyricsTask(String lrcFileName, String searchDir, OnLyricsLoadListener listener) {
            this.lrcFileName = lrcFileName;
            this.searchDir = searchDir;
            this.listener = listener;
        }
        
        @Override
        protected List<LrcParser.LrcEntry> doInBackground(Void... voids) {
            try {
                // 获取访问令牌
                String accessToken = authService.getAccessToken();
                if (accessToken == null || accessToken.isEmpty()) {
                    errorMessage = "未登录或令牌已过期";
                    return null;
                }
                
                // 搜索LRC文件
                String lrcDownloadUrl = searchLrcFile(accessToken, lrcFileName, searchDir);
                
                if (lrcDownloadUrl == null || lrcDownloadUrl.isEmpty()) {
                    errorMessage = "未找到歌词文件: " + lrcFileName;
                    return null;
                }
                
                Log.d(TAG, "找到歌词文件，下载链接: " + lrcDownloadUrl);
                
                // 下载并解析歌词
                String lrcContent = downloadLrcContent(lrcDownloadUrl);
                
                if (lrcContent == null || lrcContent.isEmpty()) {
                    errorMessage = "歌词文件内容为空";
                    return null;
                }
                
                // 解析LRC歌词
                List<LrcParser.LrcEntry> lrcEntries = LrcParser.parseLrc(lrcContent);
                
                if (lrcEntries == null || lrcEntries.isEmpty()) {
                    errorMessage = "歌词解析失败";
                    return null;
                }
                
                Log.d(TAG, "歌词加载成功，共 " + lrcEntries.size() + " 行");
                return lrcEntries;
                
            } catch (Exception e) {
                Log.e(TAG, "加载歌词失败", e);
                errorMessage = "加载歌词失败: " + e.getMessage();
                return null;
            }
        }
        
        @Override
        protected void onPostExecute(List<LrcParser.LrcEntry> lrcEntries) {
            if (lrcEntries != null && !lrcEntries.isEmpty()) {
                listener.onLyricsLoaded(lrcEntries);
            } else {
                listener.onLyricsLoadFailed(errorMessage);
            }
        }
        
        /**
         * 搜索LRC文件
         */
        private String searchLrcFile(String accessToken, String lrcFileName, String searchDir) {
            try {
                // 使用搜索API查找LRC文件
                retrofit2.Call<Map<String, Object>> call = panService.search(
                        "search",
                        accessToken,
                        lrcFileName,
                        searchDir
                );
                
                retrofit2.Response<Map<String, Object>> response = call.execute();
                
                if (!response.isSuccessful() || response.body() == null) {
                    Log.e(TAG, "搜索歌词文件失败: " + response.code());
                    return null;
                }
                
                Map<String, Object> body = response.body();
                // Gson 可能将数字解析为 Double，需要安全转换
                Number errnoNumber = (Number) body.get("errno");
                int errno = (errnoNumber != null) ? errnoNumber.intValue() : -1;
                
                if (errno == 0) {
                    // 搜索成功，获取文件列表
                    Object listObj = body.get("list");
                    if (listObj instanceof List) {
                        List<?> fileList = (List<?>) listObj;
                        
                        // 查找匹配的LRC文件
                        for (Object item : fileList) {
                            if (item instanceof Map) {
                                Map<?, ?> fileMap = (Map<?, ?>) item;
                                String serverFilename = (String) fileMap.get("server_filename");
                                
                                if (lrcFileName.equalsIgnoreCase(serverFilename)) {
                                    // 找到匹配的文件，获取下载链接
                                    // 注意：百度网盘API返回的字段名是 fs_id 而不是 fsid
                                    Object fsidObj = fileMap.get("fs_id");
                                    if (fsidObj instanceof Number) {
                                        Long fsid = ((Number) fsidObj).longValue();
                                        return getDownloadLink(accessToken, fsid);
                                    } else {
                                        Log.w(TAG, "文件 fs_id 不存在或类型不正确: " + fsidObj);
                                    }
                                }
                            }
                        }
                    }
                }
                
                return null;
                
            } catch (Exception e) {
                Log.e(TAG, "搜索歌词文件异常", e);
                return null;
            }
        }
        
        /**
         * 获取文件下载链接
         */
        private String getDownloadLink(String accessToken, long fsid) {
            try {
                // 将 fsid 转换为 JSON 数组格式的字符串，例如 "[12345]"
                String fsids = "[" + fsid + "]";
                retrofit2.Call<DownloadLinkResponse> call = panService.getFileDownloadLink(
                        "filemetas",
                        accessToken,
                        fsids,
                        1  // 添加 dlink=1 参数以获取下载链接
                );
                
                retrofit2.Response<DownloadLinkResponse> response = call.execute();
                
                if (!response.isSuccessful() || response.body() == null) {
                    Log.e(TAG, "获取下载链接失败: " + response.code());
                    return null;
                }
                
                DownloadLinkResponse body = response.body();
                int errno = body.getErrno();
                
                // 下载链接在 list 数组的第一个元素中
                if (errno == 0 && body.getList() != null && !body.getList().isEmpty()) {
                    String dlink = body.getList().get(0).getDlink();
                    Log.d(TAG, "获取下载链接成功: " + dlink);
                    
                    // 百度网盘下载链接需要附加 access_token 参数
                    String urlWithToken = dlink + (dlink.contains("?") ? "&" : "?") + "access_token=" + accessToken;
                    return urlWithToken;
                } else {
                    Log.e(TAG, "获取下载链接失败，errno=" + errno);
                    return null;
                }
                
            } catch (Exception e) {
                Log.e(TAG, "获取下载链接异常", e);
                return null;
            }
        }
        
        /**
         * 下载LRC文件内容
         */
        private String downloadLrcContent(String downloadUrl) {
            HttpURLConnection connection = null;
            BufferedReader reader = null;
            
            try {
                URL url = new URL(downloadUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                connection.connect();
                
                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "下载歌词失败: " + responseCode);
                    return null;
                }
                
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder content = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                
                return content.toString();
                
            } catch (Exception e) {
                Log.e(TAG, "下载歌词异常", e);
                return null;
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        Log.e(TAG, "关闭reader失败", e);
                    }
                }
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }
}