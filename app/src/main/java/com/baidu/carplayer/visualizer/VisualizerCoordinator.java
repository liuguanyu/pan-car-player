package com.baidu.carplayer.visualizer;

/**
 * 可视化采集的生命周期决策：只有在模式启用、已授权、页面可见且音频会话有效时才采集。
 * 不直接依赖 android.media.audiofx，便于单元测试；真实采集由 {@link Capture} 实现。
 */
public final class VisualizerCoordinator {

    /** 采集端抽象，{@link VisualizerManager} 为生产实现。 */
    public interface Capture {
        boolean attachToSession(int audioSessionId);

        void setPlaying(boolean playing);

        void release();
    }

    private final Capture capture;
    private boolean modeEnabled;
    private boolean permissionGranted;
    private boolean screenVisible;
    private int audioSessionId;
    private boolean playing;
    private boolean attached;
    private boolean closed;

    public VisualizerCoordinator(Capture capture) {
        this.capture = capture;
    }

    public void setModeEnabled(boolean value) { modeEnabled = value; }
    public void setPermissionGranted(boolean value) { permissionGranted = value; }
    public void setScreenVisible(boolean value) { screenVisible = value; }
    public void setAudioSessionId(int value) { audioSessionId = value; }
    public void setPlaying(boolean value) { playing = value; }

    /** 根据当前输入更新采集状态；返回可视化视图是否应当显示。 */
    public boolean apply() {
        boolean wanted = !closed && modeEnabled && permissionGranted && screenVisible && audioSessionId > 0;
        if (!wanted) {
            if (attached) {
                capture.release();
                attached = false;
            }
            return false;
        }
        // 采集前先同步播放状态，避免新建 Visualizer 时以错误的启用状态开始。
        capture.setPlaying(playing);
        attached = capture.attachToSession(audioSessionId);
        if (!attached) {
            capture.release();
        }
        return attached;
    }

    /** 当前是否应该绘制动画帧。 */
    public boolean isRendering() {
        return attached && playing;
    }

    /** 页面销毁：释放采集，之后 apply 不再重新附加。 */
    public void close() {
        closed = true;
        capture.release();
        attached = false;
    }
}
