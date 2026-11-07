package com.baidu.carplayer.player;

import android.content.Context;
import android.content.SharedPreferences;

import com.baidu.carplayer.model.Song;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 恢复播放所需的持久化：队列、当前下标、播放进度、播放模式、播放意图。
 * 只做读写，不持有播放器；播放器状态由调用方查好后传入。
 */
public final class PlaybackPersistence {
    private static final String PREFS_NAME = "AudioPlayerPrefs";
    private static final String KEY_PLAYLIST = "playlist";
    private static final String KEY_CURRENT_POSITION = "current_position";
    private static final String KEY_PLAYBACK_PROGRESS = "playback_progress";
    private static final String KEY_PLAY_MODE = "play_mode";
    private static final String KEY_IS_PLAYING = "is_playing";

    private final SharedPreferences prefs;
    private final Gson gson;

    public PlaybackPersistence(SharedPreferences prefs, Gson gson) {
        this.prefs = prefs;
        this.gson = gson;
    }

    public static PlaybackPersistence from(Context context) {
        return new PlaybackPersistence(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), new Gson());
    }

    /** 没有存过队列时返回 null。 */
    public List<Song> loadQueue() {
        String json = prefs.getString(KEY_PLAYLIST, null);
        if (json == null) {
            return null;
        }
        Type type = new TypeToken<ArrayList<Song>>() {}.getType();
        try {
            return gson.fromJson(json, type);
        } catch (RuntimeException e) {
            return null; // 旧版本或损坏的数据按"没有保存状态"处理
        }
    }

    public int loadPosition() {
        return prefs.getInt(KEY_CURRENT_POSITION, 0);
    }

    public long loadProgress() {
        return prefs.getLong(KEY_PLAYBACK_PROGRESS, 0);
    }

    /** 未知或缺失的模式回落到 ORDER。 */
    public String loadPlayModeName() {
        return prefs.getString(KEY_PLAY_MODE, null);
    }

    public void save(List<Song> queue, int position, long progressMs, String playModeName, boolean wasPlaying) {
        if (queue == null || queue.isEmpty()) {
            return;
        }
        prefs.edit()
                .putString(KEY_PLAYLIST, gson.toJson(queue))
                .putInt(KEY_CURRENT_POSITION, position)
                .putLong(KEY_PLAYBACK_PROGRESS, progressMs)
                .putString(KEY_PLAY_MODE, playModeName)
                .putBoolean(KEY_IS_PLAYING, wasPlaying)
                .apply();
    }
}
