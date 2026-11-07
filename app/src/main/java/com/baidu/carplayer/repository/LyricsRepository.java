package com.baidu.carplayer.repository;

import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.utils.LrcParser;

import java.util.List;

/** 歌词加载边界：页面只拿到解析后的歌词，不接触网盘接口。 */
public interface LyricsRepository {

    interface Callback {
        void onLoaded(List<LrcParser.LrcEntry> entries);

        void onFailed(String message);
    }

    /**
     * 异步加载歌曲同目录下的同名 .lrc；回调在构造时指定的回调线程上执行。
     * 取消后不再回调。
     */
    DownloadUrlRepository.Cancellable load(Song song, Callback callback);
}
