package com.baidu.carplayer.player;

/**
 * 音频焦点变化的处理策略（纯逻辑，便于单测）。
 * 只决定"该做什么"，真正操作播放器由调用方执行。
 */
public final class FocusPolicy {
    /** 降音时把音量压到这个上限。 */
    public static final float DUCK_VOLUME = 0.2f;

    public enum Action {
        /** 无需处理。 */
        NONE,
        /** 恢复用户音量，并在之前是被动暂停时恢复播放。 */
        RESTORE,
        /** 暂停播放，并记住"是被动暂停的"，等焦点恢复后继续。 */
        PAUSE_TRANSIENT,
        /** 只降低音量，不改变播放意图。 */
        DUCK,
        /** 永久停止播放并放弃焦点。 */
        STOP
    }

    private FocusPolicy() {}

    /**
     * @param playWhenReady 当前是否有播放意图
     * @param focusChange   AudioManager 的焦点事件
     */
    public static Action forFocusChange(int focusChange, boolean playWhenReady) {
        switch (focusChange) {
            case android.media.AudioManager.AUDIOFOCUS_GAIN:
                return Action.RESTORE;
            case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                return playWhenReady ? Action.PAUSE_TRANSIENT : Action.NONE;
            case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // 已暂停时降音没有意义，也不应改变音量状态。
                return playWhenReady ? Action.DUCK : Action.NONE;
            case android.media.AudioManager.AUDIOFOCUS_LOSS:
                return Action.STOP;
            default:
                return Action.NONE;
        }
    }

    /** 降音后的实际音量：不会因为 ducking 把用户音量调高。 */
    public static float duckedVolume(float userVolume) {
        return Math.min(userVolume, DUCK_VOLUME);
    }
}
