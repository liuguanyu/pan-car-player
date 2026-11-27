package com.baidu.carplayer.visualizer.breakout;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;

import com.baidu.carplayer.visualizer.AudioAnalyzer;
import com.baidu.carplayer.visualizer.GlslLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 打砖块的 GLES 3.0 渲染器。
 *
 * <p>CPU 侧只有一个纯 Java {@link BreakoutScene} 负责物理与音频映射；这里每帧把砖块、
 * 球、拖尾、挡板、冲击波打包成实例纹理，用两个 program 画完：全屏背景 + 实例化 SDF 精灵。
 * 辉光在片元里由 SDF 的 core + halo 一次算出，不用 FBO 泛光后处理。
 */
public final class BreakoutRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "BreakoutGl";
    private static final byte[] EMPTY = new byte[0];
    private static final int CAP = 160;
    private static final int VERTS = 6;
    private static final int STRIDE = 8;

    public interface FftSource {
        byte[] latest();
        int epoch();
        float sampleRateHz();
    }
    public interface TempoSource {
        double beatPosition(long nowMs);
        float confidence(long nowMs);
        float periodMs();
    }
    public interface IdleListener { void onIdle(); }

    private volatile FftSource source;
    private volatile TempoSource tempo;
    private volatile IdleListener idle;
    private volatile boolean active;

    private Context appContext;
    private boolean failed;
    private int w = 1, h = 1, epoch = Integer.MIN_VALUE;
    private int bgProgram, spriteProgram, vao, instTex;
    private int uBgAspect, uBgTime, uBgPulse, uBgRescue, uBgAudio;
    private int uInst, uSpriteAspect;

    private final BreakoutScene scene = new BreakoutScene();
    private final BreakoutScene.Audio audio = new BreakoutScene.Audio();
    private final AudioAnalyzer analyzer = new AudioAnalyzer();
    private final float[] prevSpectrum = new float[24];
    private final float[] upload = new float[CAP * STRIDE];
    private final FloatBuffer instances = ByteBuffer
            .allocateDirect(CAP * STRIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private static final class Track {
        final long seed, revision;
        final int titleKey;
        Track(long songId, String title, long revision) {
            long titleHash = GlyphPattern.stableHash(title);
            seed = songId > 0 ? GlyphPattern.stableHash(Long.toString(songId)) : titleHash;
            titleKey = (int) Math.floorMod(titleHash, GlyphFont.SIZE);
            this.revision = revision;
        }
    }

    private volatile Track track;
    private Track appliedTrack;
    private final KeyEstimator keyEstimator = new KeyEstimator();
    private byte[] previousCapture;
    private float sampleSeconds;
    private boolean pending;
    private long lastNs;
    private float time, pulse;
    private double lastBeat = Double.NaN;

    public void setContext(Context context) { appContext = context.getApplicationContext(); }
    public void setFftSource(FftSource s) { source = s; }
    public void setTempoSource(TempoSource s) { tempo = s; }
    public void setIdleListener(IdleListener l) { idle = l; }
    public void setWantActive(boolean v) { active = v; }
    public void setPlayback(long songId, String title, long revision) {
        track = title == null ? null : new Track(songId, title, revision);
    }

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            if (appContext == null) throw new IllegalStateException("no context");
            bgProgram = GlslLoader.program(appContext,
                    com.baidu.carplayer.R.raw.breakout_bg_vert,
                    com.baidu.carplayer.R.raw.breakout_bg_frag);
            spriteProgram = GlslLoader.program(appContext,
                    com.baidu.carplayer.R.raw.breakout_sprite_vert,
                    com.baidu.carplayer.R.raw.breakout_sprite_frag);
        } catch (RuntimeException e) {
            Log.e(TAG, "shader", e);
            failed = true;
            return;
        }
        uBgAspect = GLES30.glGetUniformLocation(bgProgram, "uAspect");
        uBgTime = GLES30.glGetUniformLocation(bgProgram, "uTime");
        uBgPulse = GLES30.glGetUniformLocation(bgProgram, "uPulse");
        uBgRescue = GLES30.glGetUniformLocation(bgProgram, "uRescue");
        uBgAudio = GLES30.glGetUniformLocation(bgProgram, "uAudio");
        uInst = GLES30.glGetUniformLocation(spriteProgram, "uInst");
        uSpriteAspect = GLES30.glGetUniformLocation(spriteProgram, "uAspect");

        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        vao = ids[0];
        GLES30.glGenTextures(1, ids, 0);
        instTex = ids[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, instTex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        // 每个实例占 2 个 texel（位置/尺寸 + 颜色/类型），纹理宽度必须是实例数的 2 倍。
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F,
                CAP * 2, 1, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, null);
    }

    @Override public void onSurfaceChanged(GL10 unused, int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
        GLES30.glViewport(0, 0, w, h);
        scene.setAspect(h / (float) w);
    }

    @Override public void onDrawFrame(GL10 unused) {
        if (failed) return;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? .016f : Math.min(.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        time += dt;
        pulse *= (float) Math.pow(.86, dt * 60f);

        syncTrack();
        if (pending && !active) finishPattern(0, -1);
        if (active) {
            updateScene(dt);
            if (!pending) {
                if (appliedTrack == null) scene.easeBricks(dt);
                scene.update(dt, audio);
            }
        }

        GLES30.glClearColor(.018f, .025f, .042f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        drawBackground();
        int count = packInstances();
        drawSprites(count);

        if (!active && idle != null) idle.onIdle();
    }

    private void syncTrack() {
        Track next = track;
        if (next == appliedTrack) return;
        boolean changed = next == null || appliedTrack == null || next.seed != appliedTrack.seed;
        boolean seek = !changed && next.revision != appliedTrack.revision;
        appliedTrack = next;
        if (changed) {
            sampleSeconds = 0f;
            previousCapture = source == null ? EMPTY : source.latest();
            keyEstimator.reset();
            pending = next != null;
            if (next != null) {
                int saved = features().getInt(Long.toHexString(next.seed), -1);
                if (saved >= 0) applyPattern(saved / 64, saved % 64 - 1);
                else if (!active) finishPattern(0, -1);
            }
            if (pending || next == null) scene.setGlyph(null);
            resetAudio();
        } else if (seek) {
            if (pending) finishPattern(0, -1);
            else scene.reset();
            resetAudio();
        }
    }

    private void finishPattern(int bpm, int key) {
        features().edit().putInt(Long.toHexString(appliedTrack.seed), bpm * 64 + key + 1).apply();
        applyPattern(bpm, key);
    }

    private void applyPattern(int bpm, int key) {
        scene.setGlyph(GlyphPattern.generate(appliedTrack.seed, bpm, key, appliedTrack.titleKey));
        pending = false;
    }

    private android.content.SharedPreferences features() {
        return appContext.getSharedPreferences("breakout_features_v1", Context.MODE_PRIVATE);
    }

    private void resetAudio() {
        analyzer.reset();
        lastBeat = Double.NaN;
        pulse = 0f;
        java.util.Arrays.fill(prevSpectrum, 0f);
    }

    private final float[] magnitudes = new float[128];

    private void updateScene(float dt) {
        FftSource fs = source;
        byte[] fft = fs == null ? EMPTY : fs.latest();
        int captureEpoch = fs == null ? 0 : fs.epoch();
        if (epoch != captureEpoch) {
            epoch = captureEpoch;
            resetAudio();
        }
        int count = AudioAnalyzer.decodeMagnitudes(fft, magnitudes);
        audio.bass = AudioAnalyzer.average(magnitudes, 0, Math.min(6, count));
        audio.mid = AudioAnalyzer.average(magnitudes, 6, Math.min(32, count));
        audio.treble = AudioAnalyzer.average(magnitudes, 32, count);
        audio.volume = AudioAnalyzer.average(magnitudes, 0, count);
        audio.peak = analyzer.detectPeak(audio.volume);
        float sum = 0f, weighted = 0f, flux = 0f;
        for (int i = 0; i < count; i++) {
            sum += magnitudes[i];
            weighted += i * magnitudes[i];
            if (i < prevSpectrum.length) {
                flux += Math.max(0f, magnitudes[i] - prevSpectrum[i]);
                prevSpectrum[i] = magnitudes[i];
            }
        }
        audio.centroid = sum > 1e-5f ? Math.min(1f, weighted / sum / 63f) : 0f;
        audio.flux = Math.min(1f, flux * .12f);

        TempoSource t = tempo;
        if (t != null) {
            long ms = android.os.SystemClock.uptimeMillis();
            double beat = t.beatPosition(ms);
            if (!Double.isNaN(lastBeat) && Math.floor(beat) > Math.floor(lastBeat)) pulse = 1f;
            lastBeat = beat;
            audio.beatPosition = beat;
            audio.beatConfidence = t.confidence(ms);
            audio.periodMs = t.periodMs();
        } else {
            audio.beatPosition = Double.NaN;
            audio.beatConfidence = 0f;
        }
        if (pending) {
            sampleSeconds += dt;
            if (fs != null && fft != previousCapture && count > 2) {
                previousCapture = fft;
                keyEstimator.addFrame(magnitudes, count, fs.sampleRateHz() / fft.length);
            }
            if (sampleSeconds >= 4f) {
                int bpm = audio.beatConfidence >= .5f && audio.periodMs >= 250f
                        ? Math.round(60000f / audio.periodMs) : 0;
                int key = keyEstimator.letterKey();
                finishPattern(bpm, key);
            }
        }
    }

    private void drawBackground() {
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glUseProgram(bgProgram);
        GLES30.glBindVertexArray(vao);
        GLES30.glUniform1f(uBgAspect, scene.aspect());
        GLES30.glUniform1f(uBgTime, time);
        GLES30.glUniform1f(uBgPulse, pulse);
        GLES30.glUniform1f(uBgRescue,
                Math.max(scene.rescueFlash(), scene.shockwaveFlash()));
        GLES30.glUniform4f(uBgAudio, scene.bass(), scene.mid(), scene.treble(), scene.volume());
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    private int packInstances() {
        if (pending) return 0;
        int n = 0;
        boolean ghost = scene.ghostBricks();
        for (BreakoutScene.Brick b : scene.bricks()) {
            if (!b.alive) continue;
            float band = b.band == 0 ? scene.bass() : (b.band == 1 ? scene.mid() : scene.treble());
            float scale = .92f + .08f * (float) Math.sin(scene.time() * 2f + b.col + b.row)
                    + .10f * band;
            // 深一些、饱和一些的配色：之前的高明度粉彩在加法混合下会糊成灰白，
            // 沉下去的颜色配上细亮边才显得干净。
            float r, g, bl;
            if (b.type == BreakoutScene.ITEM_SLOW) { r = .14f; g = .72f; bl = .92f; }
            else if (b.type == BreakoutScene.ITEM_FAST) { r = .95f; g = .50f; bl = .12f; }
            else if (b.type == BreakoutScene.ITEM_WIDE) { r = .22f; g = .88f; bl = .40f; }
            else if (b.type == BreakoutScene.ITEM_NARROW) { r = .92f; g = .26f; bl = .36f; }
            else if (b.type == BreakoutScene.ITEM_MULTI) { r = .66f; g = .38f; bl = .96f; }
            else if (b.band == 0) { r = .92f; g = .58f; bl = .14f; }
            else if (b.band == 1) { r = .14f; g = .78f; bl = .48f; }
            else { r = .34f; g = .44f; bl = .95f; }
            n = put(n, b.x, b.y, b.w * .5f * scale, b.h * .5f * scale, r, g, bl, ghost ? 7f : 0f);
            // 技能砖标志：白色、按类型给不同形状；弱音幽灵态只压暗亮度，形状仍保留。
            if (b.type != BreakoutScene.ITEM_NONE) {
                float pulse = .75f + .25f * (float) Math.sin(scene.time() * 6f + b.col);
                float size = Math.min(b.w, b.h) * .46f * pulse;
                float gain = ghost ? ItemGlyph.GHOST_GAIN : 1f;
                n = put(n, b.x, b.y, size, size, gain, gain, gain,
                        ItemGlyph.kindFor(b.type));
            }
        }
        float t = scene.colorT();
        float br, bg, bb;
        if (t < .5f) {
            float k = t * 2f;
            br = .92f + (.14f - .92f) * k;
            bg = .58f + (.78f - .58f) * k;
            bb = .14f + (.48f - .14f) * k;
        } else {
            float k = (t - .5f) * 2f;
            br = .14f + (.34f - .14f) * k;
            bg = .78f + (.44f - .78f) * k;
            bb = .48f + (.95f - .48f) * k;
        }
        // 穿透状态下球体转金色，和普通回击明显区分。
        if (scene.piercing()) { br = 1f; bg = .74f; bb = .22f; }
        for (BreakoutScene.Ball b : scene.balls()) {
            if (!b.active) continue;
            for (int j = BreakoutScene.TRAIL - 1; j >= 1; j--) {
                float fade = 1f - j / (float) BreakoutScene.TRAIL;
                n = put(n, b.trailX[j], b.trailY[j],
                        BreakoutScene.BALL_R * (1f - j * .08f),
                        BreakoutScene.BALL_R * (1f - j * .08f),
                        br * fade, bg * fade, bb * fade, 4f);
            }
            n = put(n, b.x, b.y, BreakoutScene.BALL_R, BreakoutScene.BALL_R, br, bg, bb, 1f);
            // 搓球/救球带侧旋：球体外加一圈紫色光环，强度随 spin。
            float spin = Math.abs(b.spin);
            if (spin > .012f) {
                float k = Math.min(1f, spin / .14f);
                n = put(n, b.x, b.y, BreakoutScene.BALL_R * 2.6f,
                        BreakoutScene.BALL_R * 2.6f,
                        .72f * k, .62f * k, .95f * k, 5f);
            }
        }
        BreakoutScene.Paddle p = scene.paddle();
        // 回击类型用配色和厚度区分：推挡青、削球琥珀、搓球紫、救球白热。
        // 渲染与碰撞共享挡板宽度，命中道具砖后同步变化。
        float pr = .22f, pg = .84f, pb = .70f, ph = .011f;
        if (p.shotType == BreakoutScene.SHOT_CHOP) {
            pr = .95f; pg = .60f; pb = .18f; ph = .008f;
        } else if (p.shotType == BreakoutScene.SHOT_SPIN) {
            pr = .58f; pg = .46f; pb = .95f;
        } else if (p.shotType == BreakoutScene.SHOT_SAVE) {
            pr = .90f; pg = .95f; pb = .98f; ph = .015f;
        }
        if (p.shotTimer > 0f) {
            float k = p.shotTimer / .65f;
            pr += .25f * k; pg += .18f * k; pb += .18f * k;
        }
        n = put(n, p.x, scene.aspect() - BreakoutScene.PADDLE_MARGIN + .011f, p.width * .5f, ph, pr, pg, pb, 2f);
        for (BreakoutScene.Shock s : scene.shocks()) {
            if (!s.active) continue;
            float size = (.012f + s.age * .11f) * s.scale;
            float fade = 1f - s.age / .42f;
            n = put(n, s.x, s.y, size, size,
                    .52f * fade, .80f * fade, .92f * fade, 3f);
        }
        return n;
    }

    private int put(int n, float x, float y, float hw, float hh,
                    float r, float g, float b, float kind) {
        int i = n * STRIDE;
        upload[i] = x; upload[i + 1] = y; upload[i + 2] = hw; upload[i + 3] = hh;
        upload[i + 4] = r; upload[i + 5] = g; upload[i + 6] = b; upload[i + 7] = kind;
        return n + 1;
    }

    private void drawSprites(int count) {
        if (count <= 0) return;
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
        GLES30.glUseProgram(spriteProgram);
        GLES30.glBindVertexArray(vao);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, instTex);
        GLES30.glUniform1i(uInst, 0);
        GLES30.glUniform1f(uSpriteAspect, scene.aspect());
        instances.clear();
        instances.put(upload, 0, count * STRIDE);
        instances.flip();
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, count * 2, 1,
                GLES30.GL_RGBA, GLES30.GL_FLOAT, instances);
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, VERTS, count);
    }
}
