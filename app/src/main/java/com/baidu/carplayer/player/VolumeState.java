package com.baidu.carplayer.player;

/**
 * 播放页静音/恢复音量的纯状态；不读取 Service，只根据已发布的音量计算下一步要设置的值。
 */
public final class VolumeState {
    static final float DEFAULT_RESTORE_VOLUME = 0.5f;

    private boolean muted;
    private float restoreVolume = DEFAULT_RESTORE_VOLUME;

    public boolean isMuted() {
        return muted;
    }

    /** 用户拖动音量条；拖到大于 0 时自动取消静音。 */
    public void onUserVolume(float volume) {
        if (volume > 0f) {
            restoreVolume = volume;
            muted = false;
        }
    }

    /**
     * 切换静音。
     * @param currentVolume 当前已发布的用户音量
     * @return 需要设置给播放器的音量
     */
    public float toggle(float currentVolume) {
        if (muted) {
            muted = false;
            return restoreVolume;
        }
        if (currentVolume > 0f) {
            restoreVolume = currentVolume;
        }
        muted = true;
        return 0f;
    }
}
