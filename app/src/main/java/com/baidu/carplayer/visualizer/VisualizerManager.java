package com.baidu.carplayer.visualizer;

import android.graphics.Canvas;
import android.media.audiofx.Visualizer;
import android.util.Log;

/** Owns capture only while the player page is visible. All effect state is updated on the UI thread. */
public final class VisualizerManager {
    public static final String[] MODES = {"default", "bars", "wave", "particles", "sheep"};
    private static final byte[] EMPTY = new byte[0];
    private final AudioVisualizer[] effects = {new BarsVisualizer(), new WaveVisualizer(),
            new ParticlesVisualizer(), new DancingSheepVisualizer()};
    private AudioVisualizer active = effects[0];
    private Visualizer capture;
    private int sessionId;
    private volatile byte[] lastFft = EMPTY;
    private volatile int generation;
    private boolean playing;
    private boolean initialized;

    public boolean attachToSession(int id) {
        // Session 0 captures global output: never attach to it.
        if (id <= 0) { release(); return false; }
        if (capture != null && sessionId == id) return true;
        release();
        final int token = generation;
        Visualizer next = null;
        try {
            next = new Visualizer(id);
            next.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            next.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
                    if (generation == token) lastFft = fft.clone();
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
    public void switchTo(String mode) {
        active.reset();
        for (AudioVisualizer effect : effects) if (effect.getName().equals(mode)) { active = effect; break; }
        active.reset();
        initialized = false;
    }
    public void setPlaying(boolean value) {
        if (playing == value) return;
        playing = value;
        if (!value) lastFft = EMPTY;
        if (capture != null) {
            try { capture.setEnabled(value); }
            catch (RuntimeException e) { release(); }
        }
    }
    public void draw(Canvas canvas, int width, int height) {
        // A paused first frame still needs dimensions (e.g. opening the sheep scene while paused).
        if (playing || !initialized) {
            active.update(playing ? lastFft : EMPTY, width, height);
            initialized = true;
        }
        active.draw(canvas);
    }
    public void release() {
        generation++; sessionId = 0; lastFft = EMPTY;
        initialized = false;
        if (capture != null) {
            try { capture.setEnabled(false); } catch (RuntimeException ignored) {}
            try { capture.release(); } catch (RuntimeException ignored) {}
            capture = null;
        }
        for (AudioVisualizer effect : effects) effect.reset();
    }
}
