package com.baidu.carplayer.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.baidu.carplayer.auth.BaiduAuthService;
import com.baidu.carplayer.concurrent.AppExecutors;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.network.RetrofitClient;
import com.baidu.carplayer.repository.BaiduLyricsRepository;
import com.baidu.carplayer.repository.DownloadUrlRepository;
import com.baidu.carplayer.repository.LyricsRepository;
import com.baidu.carplayer.utils.LrcParser;

import java.util.List;

/**
 * 歌词加载入口：同一时间只保留一个请求，新请求会取消旧请求。
 * 网络细节在 {@link LyricsRepository}，回调总在主线程。
 */
public class LyricsManager {

    public interface OnLyricsLoadListener {
        void onLyricsLoaded(List<LrcParser.LrcEntry> lrcEntries);
        void onLyricsLoadFailed(String error);
    }

    private final LyricsRepository repository;
    private DownloadUrlRepository.Cancellable pending;

    public LyricsManager(Context context) {
        this(new BaiduLyricsRepository(
                RetrofitClient.getInstance().create(BaiduPanService.class),
                BaiduAuthService.getInstance(context)::getAccessToken,
                AppExecutors.getInstance().networkIO(),
                new Handler(Looper.getMainLooper())::post));
    }

    public LyricsManager(LyricsRepository repository) {
        this.repository = repository;
    }

    /** 加载歌曲的歌词；会先取消尚未完成的旧请求，避免旧歌词覆盖当前歌曲。 */
    public void loadLyrics(Song song, OnLyricsLoadListener listener) {
        cancelPendingLoad();
        pending = repository.load(song, new LyricsRepository.Callback() {
            @Override
            public void onLoaded(List<LrcParser.LrcEntry> entries) {
                listener.onLyricsLoaded(entries);
            }

            @Override
            public void onFailed(String message) {
                listener.onLyricsLoadFailed(message);
            }
        });
    }

    public void cancelPendingLoad() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }
}
