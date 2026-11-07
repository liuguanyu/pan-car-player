package com.baidu.carplayer.player;

import com.baidu.carplayer.service.AudioPlayerService.PlayMode;

/** 播放模式按钮的循环顺序：顺序 → 随机 → 单曲循环 → 顺序。 */
public final class PlayModeCycle {
    private PlayModeCycle() {}

    public static PlayMode fromOrdinal(int ordinal) {
        PlayMode[] modes = PlayMode.values();
        return ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : PlayMode.ORDER;
    }

    public static PlayMode next(int currentOrdinal) {
        switch (fromOrdinal(currentOrdinal)) {
            case ORDER:
                return PlayMode.RANDOM;
            case RANDOM:
                return PlayMode.SINGLE;
            case SINGLE:
            default:
                return PlayMode.ORDER;
        }
    }

    public static String label(PlayMode mode) {
        switch (mode) {
            case RANDOM:
                return "随机播放";
            case SINGLE:
                return "单曲循环";
            case ORDER:
            default:
                return "顺序播放";
        }
    }
}
