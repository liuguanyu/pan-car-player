package com.baidu.carplayer.visualizer;

/**
 * 可视化采集回调的代次闸门。系统 Visualizer 回调在 binder 线程上异步到达，
 * release/重新挂载后旧会话的回调仍可能晚到；只有当前代次且正在播放时才接受数据。
 */
final class CaptureGate {
    private volatile int generation;
    private volatile boolean playing;

    /** 为新的采集会话签发令牌。 */
    int issue() {
        return generation;
    }

    /** 使已签发的全部令牌失效。 */
    void invalidate() {
        generation++;
    }

    void setPlaying(boolean value) {
        playing = value;
    }

    boolean accepts(int token) {
        return playing && token == generation;
    }
}
