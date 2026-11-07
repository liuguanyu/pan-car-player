package com.baidu.carplayer.repository;

import android.util.Log;

import androidx.annotation.Nullable;

import com.baidu.carplayer.model.DownloadLinkResponse;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.network.LogRedactor;
import com.baidu.carplayer.utils.LrcParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import retrofit2.Response;

/**
 * 基于百度网盘 search + filemetas 的歌词仓库。
 * 网络、令牌、下载和回调线程均可注入，便于 JVM 单元测试。
 */
public final class BaiduLyricsRepository implements LyricsRepository {
    private static final String TAG = "LyricsRepository";
    static final String NOT_LOGGED_IN = "未登录或令牌已过期";

    /** 下载歌词文本；返回 null 表示失败。 */
    public interface TextDownloader {
        @Nullable
        String download(String url) throws IOException;
    }

    private final BaiduPanService panService;
    private final BaiduDownloadUrlRepository.TokenProvider tokenProvider;
    private final Executor networkExecutor;
    private final Executor callbackExecutor;
    private final TextDownloader downloader;

    public BaiduLyricsRepository(BaiduPanService panService,
                                 BaiduDownloadUrlRepository.TokenProvider tokenProvider,
                                 Executor networkExecutor,
                                 Executor callbackExecutor) {
        this(panService, tokenProvider, networkExecutor, callbackExecutor, BaiduLyricsRepository::httpGet);
    }

    public BaiduLyricsRepository(BaiduPanService panService,
                                 BaiduDownloadUrlRepository.TokenProvider tokenProvider,
                                 Executor networkExecutor,
                                 Executor callbackExecutor,
                                 TextDownloader downloader) {
        this.panService = panService;
        this.tokenProvider = tokenProvider;
        this.networkExecutor = networkExecutor;
        this.callbackExecutor = callbackExecutor;
        this.downloader = downloader;
    }

    @Override
    public DownloadUrlRepository.Cancellable load(Song song, Callback callback) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        String path = song != null ? song.getPath() : null;
        if (path == null || path.isEmpty()) {
            deliverFailure(cancelled, callback, "歌曲路径为空");
            return () -> cancelled.set(true);
        }
        String baseName = baseNameWithoutExtension(path);
        if (baseName.isEmpty()) {
            deliverFailure(cancelled, callback, "无法解析歌曲文件名");
            return () -> cancelled.set(true);
        }
        String lrcName = baseName + ".lrc";
        String dir = parentDirectory(path);
        networkExecutor.execute(() -> {
            if (cancelled.get()) return;
            try {
                List<LrcParser.LrcEntry> entries = fetch(lrcName, dir, cancelled);
                if (entries != null) {
                    callbackExecutor.execute(() -> {
                        if (!cancelled.get()) callback.onLoaded(entries);
                    });
                }
            } catch (LyricsException e) {
                deliverFailure(cancelled, callback, e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "加载歌词失败: " + LogRedactor.redact(e.getMessage()));
                deliverFailure(cancelled, callback, "加载歌词失败: " + LogRedactor.redact(e.getMessage()));
            }
        });
        return () -> cancelled.set(true);
    }

    /** 返回 null 表示已取消。 */
    @Nullable
    private List<LrcParser.LrcEntry> fetch(String lrcName, String dir, AtomicBoolean cancelled)
            throws IOException, LyricsException {
        String token = tokenProvider.getAccessToken();
        if (token == null || token.isEmpty()) throw new LyricsException(NOT_LOGGED_IN);

        Response<Map<String, Object>> search = panService.search("search", token, lrcName, dir).execute();
        if (cancelled.get()) return null;
        Long fsId = search.isSuccessful() ? findLrcFsId(search.body(), lrcName) : null;
        if (fsId == null) throw new LyricsException("未找到歌词文件: " + lrcName);

        Response<DownloadLinkResponse> meta = panService
                .getFileDownloadLink("filemetas", token, "[" + fsId + "]", 1).execute();
        if (cancelled.get()) return null;
        DownloadLinkResponse body = meta.isSuccessful() ? meta.body() : null;
        if (body == null || body.getErrno() != 0 || body.getList() == null || body.getList().isEmpty()
                || body.getList().get(0).getDlink() == null) {
            throw new LyricsException("获取歌词下载链接失败");
        }
        String url = BaiduDownloadUrlRepository.appendAccessToken(body.getList().get(0).getDlink(), token);

        String content = downloader.download(url);
        if (cancelled.get()) return null;
        if (content == null || content.isEmpty()) throw new LyricsException("歌词文件内容为空");

        List<LrcParser.LrcEntry> entries = LrcParser.parseLrc(content);
        if (entries == null || entries.isEmpty()) throw new LyricsException("歌词解析失败");
        return entries;
    }

    private void deliverFailure(AtomicBoolean cancelled, Callback callback, String message) {
        callbackExecutor.execute(() -> {
            if (!cancelled.get()) callback.onFailed(message);
        });
    }

    /** 从 search 接口结果中按文件名（忽略大小写）找到 .lrc 的 fs_id。 */
    @Nullable
    static Long findLrcFsId(@Nullable Map<String, Object> body, String lrcName) {
        if (body == null) return null;
        Object errno = body.get("errno");
        if (!(errno instanceof Number) || ((Number) errno).intValue() != 0) return null;
        Object list = body.get("list");
        if (!(list instanceof List)) return null;
        for (Object item : (List<?>) list) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> file = (Map<?, ?>) item;
            // 百度网盘返回字段名是 fs_id，Gson 会把数字解析为 Double。
            if (lrcName.equalsIgnoreCase(String.valueOf(file.get("server_filename")))
                    && file.get("fs_id") instanceof Number) {
                return ((Number) file.get("fs_id")).longValue();
            }
        }
        return null;
    }

    static String baseNameWithoutExtension(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash == -1 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String parentDirectory(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    @Nullable
    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append('\n');
                }
            }
            return content.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static final class LyricsException extends Exception {
        LyricsException(String message) {
            super(message);
        }
    }
}
