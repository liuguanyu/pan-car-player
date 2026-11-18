package com.baidu.carplayer.visualizer;

import android.graphics.Canvas;
import android.media.audiofx.Visualizer;
import android.util.Log;

/** Owns capture only while the player page is visible. All effect state is updated on the UI thread. */
public final class VisualizerManager implements VisualizerCoordinator.Capture {
    public static final String[] MODES = {"default", "bars", "sheep", "particles", "wave"};
    private static final byte[] EMPTY = new byte[0];
    /** 节拍跟踪：在采集线程喂入 FFT，小羊在 UI 线程读取拍位置，让舞步与音乐同步。 */
    private final BeatTracker beats = new BeatTracker();
    private final DancingSheepVisualizer sheep = new DancingSheepVisualizer();
    private final ParticlesVisualizer particles = new ParticlesVisualizer();
    private final AudioVisualizer[] effects = {new BarsVisualizer(), sheep, particles, new WaveVisualizer()};

    public VisualizerManager() {
        sheep.setBeatSource(new DancingSheepVisualizer.BeatSource() {
            @Override public double beatPosition(long nowMs) { return beats.beatPosition(nowMs); }
            @Override public float confidence(long nowMs) { return beats.confidence(nowMs); }
        });
        particles.setTempoSource(beats::periodMs);
    }
    private AudioVisualizer active = effects[0];
    private Visualizer capture;
    private int sessionId;
    private volatile byte[] lastFft = EMPTY;
    private final CaptureGate gate = new CaptureGate();
    /** 场景重置代次：切模式 / 会话释放时递增，GL 渲染器据此重置场景。 */
    private volatile int epoch;
    private boolean playing;
    private boolean initialized;

    @Override
    public boolean attachToSession(int id) {
        // Session 0 captures global output: never attach to it.
        if (id <= 0) { release(); return false; }
        if (capture != null && sessionId == id) return true;
        release();
        beats.reset();
        final int token = gate.issue();
        Visualizer next = null;
        try {
            next = new Visualizer(id);
            next.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            next.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
                    if (!gate.accepts(token)) return;
                    lastFft = fft.clone();
                    beats.onFrame(fft, android.os.SystemClock.uptimeMillis());
                }
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] wave, int rate) {}
            }, Visualizer.getMaxCaptureRate(), false, true);
            next.setEnabled(playing);
            capture = next; sessionId = id;
            return true;
        } catch (RuntimeException e) {
            if (next != null) next.release();
            Log.w("VisualizerManager", "Audio visualization is unavailable", e);
            return false;
        }
    }
    /** 粒子模式由 GLES 视图渲染（设备支持时）；其余模式仍走 Canvas。 */
    public static boolean isGlMode(String mode) { return "particles".equals(mode); }

    /** GL 渲染器每帧读取：播放中为最新 FFT，否则为空数组。 */
    public byte[] latestFft() { return playing ? lastFft : EMPTY; }
    public int epoch() { return epoch; }
    public float periodMs() { return beats.periodMs(); }

    public void switchTo(String mode) {
        epoch++;
        active.reset();
        for (AudioVisualizer effect : effects) if (effect.getName().equals(mode)) { active = effect; break; }
        active.reset();
        initialized = false;
    }
    @Override
    public void setPlaying(boolean value) {
        if (playing == value) return;
        playing = value;
        gate.setPlaying(value);
        if (!value) lastFft = EMPTY;
        if (capture != null) {
            try { capture.setEnabled(value); }
            catch (RuntimeException e) { release(); }
        }
    }
    public void draw(Canvas canvas, int width, int height) {
        // 播放时喂入最新频谱；暂停/停止后持续喂空数据，让效果平滑过渡到 0 态再停帧。
        // 首帧即使暂停也要拿到尺寸（例如暂停时打开小羊场景）。
        if (playing || !initialized || !active.isAtRest()) {
            active.update(playing ? lastFft : EMPTY, width, height);
            initialized = true;
        }
        active.draw(canvas);
    }

    /** 停止播放后效果是否仍在回落；为 true 时视图应继续绘制下一帧。 */
    public boolean isSettling() {
        return !playing && initialized && !active.isAtRest();
    }
    @Override
    public void release() {
        gate.invalidate(); sessionId = 0; lastFft = EMPTY; epoch++;
        initialized = false;
        if (capture != null) {
            try { capture.setEnabled(false); } catch (RuntimeException ignored) {}
            try { capture.release(); } catch (RuntimeException ignored) {}
            capture = null;
        }
        for (AudioVisualizer effect : effects) effect.reset();
    }
}
