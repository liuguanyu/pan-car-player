package com.baidu.carplayer.visualizer;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GLES 3.0 雷雨渲染器。
 *
 * <p>分工：
 * <ul>
 *   <li>CPU：音频特征（低 / 中 / 高频、响度）、节拍概率触发、每次雷击生成一次分形放电通道
 *       （{@link ThunderScene}，几百个线段，只在雷击瞬间计算并上传一次纹理行）；</li>
 *   <li>GPU：云层域扭曲 FBM、云内照明、雨丝（背景 pass）；每个线段实例化成四边形，
 *       片元里算距离场得到核心与光晕（闪电 pass）。</li>
 * </ul>
 *
 * <p>一次雷击的时间结构：先导在约 60～110 ms 内从起点"爬"到终点 → 回击瞬间整条通道最亮 →
 * 指数衰减；响度高时追加 1～2 次回击（间隔 ≥ 70 ms）。云层照明只跟随主闪光，
 * 两次雷击至少相隔 {@link ThunderScene#MIN_GAP} 秒，大面积闪光频率远低于 3 Hz。
 */
public final class ThunderGlRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "ThunderGl";
    private static final byte[] EMPTY = new byte[0];

    public interface FftSource { byte[] latest(); int epoch(); }
    public interface TempoSource { double beatPosition(long nowMs); float periodMs(); float confidence(long nowMs); }
    public interface IdleListener { void onIdle(); }

    private volatile FftSource source;
    private volatile TempoSource tempo;
    private volatile IdleListener idle;
    private volatile boolean active;

    private boolean failed;
    private int w = 1, h = 1, epoch = Integer.MIN_VALUE;
    private int bgProgram, boltProgram, vao, segTex;
    private int uBgTime, uBgAspect, uBgRain, uBgWind, uBgFlash;
    private int uBoltSeg, uBoltSlot, uBoltAspect, uBoltCore, uBoltGlow, uBoltFront, uBoltTime;

    private long lastNs;
    private float time;
    private float bass, mid, treble, loud, energy, rain, wind = .3f;
    private double lastBeat = Double.NaN;
    /** 节拍不可信时的兜底：按响度随机触发，保证没有鼓点的曲子也有雷。 */
    private float idleTimer;
    private boolean demo;
    private float dbgTimer;
    private float demoCheck;

    private final ThunderScene scene = new ThunderScene();
    private final Random random = new Random();
    private final boolean[] dirty = new boolean[ThunderScene.SLOTS];
    private final float[] flash = new float[ThunderScene.SLOTS * 4];
    private final FloatBuffer upload = ByteBuffer.allocateDirect(ThunderScene.CAP * ThunderScene.FLOATS * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

    public void setFftSource(FftSource s) { source = s; }
    public void setTempoSource(TempoSource s) { tempo = s; }
    public void setIdleListener(IdleListener l) { idle = l; }
    public void setWantActive(boolean v) { active = v; }

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            bgProgram = link(ThunderShaders.BG_VERT, ThunderShaders.BG_FRAG);
            boltProgram = link(ThunderShaders.BOLT_VERT, ThunderShaders.BOLT_FRAG);
        } catch (RuntimeException e) {
            Log.e(TAG, "shader", e);
            failed = true;
            return;
        }
        uBgTime = GLES30.glGetUniformLocation(bgProgram, "uTime");
        uBgAspect = GLES30.glGetUniformLocation(bgProgram, "uAspect");
        uBgRain = GLES30.glGetUniformLocation(bgProgram, "uRain");
        uBgWind = GLES30.glGetUniformLocation(bgProgram, "uWind");
        uBgFlash = GLES30.glGetUniformLocation(bgProgram, "uFlash");
        uBoltSeg = GLES30.glGetUniformLocation(boltProgram, "uSeg");
        uBoltSlot = GLES30.glGetUniformLocation(boltProgram, "uSlot");
        uBoltAspect = GLES30.glGetUniformLocation(boltProgram, "uAspect");
        uBoltCore = GLES30.glGetUniformLocation(boltProgram, "uCore");
        uBoltGlow = GLES30.glGetUniformLocation(boltProgram, "uGlow");
        uBoltFront = GLES30.glGetUniformLocation(boltProgram, "uFront");
        uBoltTime = GLES30.glGetUniformLocation(boltProgram, "uTime");

        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        vao = ids[0];
        GLES30.glGenTextures(1, ids, 0);
        segTex = ids[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, segTex);
        // texelFetch 精确取值：浮点纹理必须用 NEAREST，否则部分 GPU 判定纹理不完整、读出 0
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F,
                ThunderScene.CAP * 2, ThunderScene.SLOTS, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, null);
        for (int i = 0; i < ThunderScene.SLOTS; i++) dirty[i] = scene.count[i] > 0;
    }

    @Override public void onSurfaceChanged(GL10 unused, int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
        GLES30.glViewport(0, 0, w, h);
    }

    @Override public void onDrawFrame(GL10 unused) {
        if (failed) return;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? .016f : Math.min(.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        time += dt;
        float aspect = w / (float) h;

        updateAudio(dt);
        maybeStrike(dt, aspect);
        for (int i = 0; i < ThunderScene.SLOTS; i++) if (dirty[i]) uploadSlot(i);

        // ---- 背景：云 + 云内照明 + 雨 ----
        float anyAlive = 0f;
        for (int i = 0; i < ThunderScene.SLOTS; i++) {
            float b = scene.alive(i, time) ? cloudLight(i, time - scene.birth[i]) : 0f;
            anyAlive = Math.max(anyAlive, b);
            flash[i * 4] = scene.centerX[i];
            flash[i * 4 + 1] = scene.centerY[i];
            flash[i * 4 + 2] = b;
            flash[i * 4 + 3] = .30f + .35f * scene.peak[i];
        }
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glUseProgram(bgProgram);
        GLES30.glBindVertexArray(vao);
        GLES30.glUniform1f(uBgTime, time);
        GLES30.glUniform1f(uBgAspect, aspect);
        GLES30.glUniform1f(uBgRain, rain);
        GLES30.glUniform1f(uBgWind, wind);
        GLES30.glUniform4fv(uBgFlash, ThunderScene.SLOTS, flash, 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

        // ---- 闪电：每段一个实例化四边形，GL_MAX 混合 ----
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendEquation(GLES30.GL_MAX);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
        GLES30.glUseProgram(boltProgram);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, segTex);
        GLES30.glUniform1i(uBoltSeg, 0);
        GLES30.glUniform1f(uBoltAspect, aspect);
        GLES30.glUniform1f(uBoltTime, time);
        for (int i = 0; i < ThunderScene.SLOTS; i++) {
            if (!scene.alive(i, time)) continue;
            float age = time - scene.birth[i];
            float lead = leaderTime(i);
            float front = Math.min(1f, age / lead);
            float core = coreLevel(i, age - lead) * scene.peak[i];
            float glow = glowLevel(i, age - lead) * scene.peak[i];
            if (front < 1f) { core = .55f * scene.peak[i]; glow = .25f * scene.peak[i]; }
            if (core + glow < .004f) continue;
            GLES30.glUniform1i(uBoltSlot, i);
            GLES30.glUniform1f(uBoltCore, core);
            GLES30.glUniform1f(uBoltGlow, glow);
            GLES30.glUniform1f(uBoltFront, front);
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, scene.count[i]);
        }
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD);
        GLES30.glDisable(GLES30.GL_BLEND);

        if (!active && anyAlive < .003f && idle != null) idle.onIdle();
    }

    /** 先导生长时长：越响越快。 */
    private float leaderTime(int slot) {
        return .11f - .05f * scene.peak[slot];
    }

    /** 核心亮度：回击瞬间最亮，指数衰减；最多三次回击。 */
    private float coreLevel(int slot, float t) {
        if (t < 0f) return 0f;
        float tauCore = .14f + .10f * scene.peak[slot];
        float v = (float) Math.exp(-t / tauCore);
        if (scene.strokes[slot] > 1) v = Math.max(v, .85f * pulse(t - scene.off1[slot], tauCore));
        if (scene.strokes[slot] > 2) v = Math.max(v, .70f * pulse(t - scene.off2[slot], tauCore));
        float tail = .38f * (float) Math.exp(-t / scene.tau[slot]);                  // 通道余辉
        return Math.max(v, tail);
    }

    private float glowLevel(int slot, float t) {
        if (t < 0f) return 0f;
        return Math.max(coreLevel(slot, t) * .9f, .65f * (float) Math.exp(-t / (scene.tau[slot] * 1.4f)));
    }

    /** 云内照明：只跟随主闪光一次（不跟每次回击闪），并有更长的散射余辉。 */
    private float cloudLight(int slot, float age) {
        float lead = leaderTime(slot);
        if (age < lead) return .12f * scene.peak[slot] * (age / lead);
        float t = age - lead;
        float v = (float) Math.exp(-t / (.18f + .12f * scene.peak[slot]))
                + .35f * (float) Math.exp(-t / (scene.tau[slot] * 1.8f));
        return Math.min(.85f, v * scene.peak[slot] * .75f);
    }

    private static float pulse(float t, float tau) {
        return t < 0f ? 0f : (float) Math.exp(-t / tau);
    }

    private void updateAudio(float dt) {
        FftSource fs = source;
        byte[] bytes = fs == null ? EMPTY : fs.latest();
        if (fs != null && epoch != fs.epoch()) {
            epoch = fs.epoch();
            lastBeat = Double.NaN;
        }
        float[] m = AudioAnalyzer.fftToMagnitudes(bytes, 128);
        float lo = 0, md = 0, hi = 0, all = 0;
        for (int i = 0; i < m.length; i++) {
            float v = m[i];
            all += v;
            if (i < 6) lo += v; else if (i < 32) md += v; else hi += v;
        }
        float n = Math.max(1, m.length);
        float a = 1f - (float) Math.exp(-dt / .12f);
        bass += (Math.min(1f, lo / 6f * 1.6f) - bass) * a;
        mid += (Math.min(1f, md / 26f * 3.2f) - mid) * a;
        treble += (Math.min(1f, hi / Math.max(1f, n - 32f) * 9f) - treble) * a;
        loud += (Math.min(1f, all / n * 3f) - loud) * a;
        float slow = 1f - (float) Math.exp(-dt / 1.2f);
        energy += (loud - energy) * slow;
        rain += (Math.min(1f, .15f + energy * 1.1f) - rain) * (1f - (float) Math.exp(-dt / 2.5f));
        wind += (.25f + .6f * energy - wind) * (1f - (float) Math.exp(-dt / 3f));
    }

    /**
     * 触发：节拍可信时，在拍点上按概率触发（越响概率越高，上限 55%）；
     * 节拍不可信但有声音时，按响度做泊松式随机触发。两种都受最小间隔约束。
     */
    private void maybeStrike(float dt, float aspect) {
        // 开发演示开关：adb shell setprop log.tag.ThunderGl VERBOSE → 约每 1.2 秒强制一次雷击，
        // 用于在音频采集很弱的模拟器上检查闪电画面。默认关闭。
        demoCheck -= dt;
        if (demoCheck <= 0f) { demoCheck = 1f; demo = Log.isLoggable(TAG, Log.VERBOSE); }
        if (demo) {
            if (time - scene.birth[(scene.lastSlot())] > 2.2f) {
                int slot = scene.strike(time, aspect, .55f + .4f * random.nextFloat(), .65f,
                        .3f + .5f * random.nextFloat(), .75f, random);
                dirty[slot] = true;
            }
            return;
        }
        dbgTimer += dt;
        if (dbgTimer > 1f && Log.isLoggable(TAG, Log.DEBUG)) {
            dbgTimer = 0f;
            TempoSource dt0 = tempo;
            Log.d(TAG, "state energy=" + energy + " loud=" + loud + " bass=" + bass
                    + " conf=" + (dt0 == null ? -1f : dt0.confidence(System.currentTimeMillis()))
                    + " canStrike=" + scene.canStrike(time)
                    + " gap=" + (time - scene.birth[scene.lastSlot()]));
        }
        if (energy < .02f || !scene.canStrike(time)) {
            TempoSource t = tempo;
            if (t != null) lastBeat = t.beatPosition(System.currentTimeMillis());
            return;
        }
        TempoSource t = tempo;
        long nowMs = System.currentTimeMillis();
        boolean fire = false;
        // 空窗保底：距上一道雷太久时，下一拍必触发；再拖 0.8 秒仍无拍点则立刻触发。越响，容忍的空窗越短（约 1.6～3.2 秒）。
        float gap = time - scene.birth[scene.lastSlot()];
        float maxGap = 2.0f - 0.8f * loud;
        if (t != null && t.confidence(nowMs) > .3f) {
            double beat = t.beatPosition(nowMs);
            if (!Double.isNaN(lastBeat) && Math.floor(beat) > Math.floor(lastBeat)) {
                float p = Math.min(.75f, .22f + .45f * loud + .20f * bass);
                if (gap > maxGap) p = 1f;
                fire = random.nextFloat() < p;
            }
            lastBeat = beat;
            idleTimer = 0f;
        } else {
            idleTimer += dt;
            float rate = .70f + 1.0f * loud;                 // 次/秒
            fire = random.nextFloat() < 1f - (float) Math.exp(-rate * dt);
        }
        if (!fire && gap > maxGap + .8f) fire = true;
        if (fire) {
            int slot = scene.strike(time, aspect, bass, mid, treble, loud, random);
            dirty[slot] = true;
            strikeExtras(aspect);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "strike slot=" + slot + " segs=" + scene.count[slot]
                        + " peak=" + scene.peak[slot] + " kind=" + scene.kind[slot]
                        + " bass=" + bass + " mid=" + mid + " loud=" + loud);
            }
        }
    }

    /** 成群闪电：主闪电之外，按概率在同一瞬间再出现 1～2 道（总数最多 3 道），越响、低频越重越容易成群。 */
    private void strikeExtras(float aspect) {
        float multi = .14f + .30f * loud + .12f * bass;
        if (random.nextFloat() >= multi) return;
        int extras = random.nextFloat() < .35f * multi + .08f ? 2 : 1;
        for (int i = 0; i < extras; i++) {
            float k = 1f - .15f * (i + 1);
            int s = scene.strike(time, aspect, bass * .7f, mid, treble, loud * k, random);
            dirty[s] = true;
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "strike extra slot=" + s + " segs=" + scene.count[s] + " peak=" + scene.peak[s]);
            }
        }
    }

    private void uploadSlot(int slot) {
        dirty[slot] = false;
        int n = scene.count[slot];
        if (n <= 0) return;
        upload.clear();
        upload.put(scene.data[slot], 0, n * ThunderScene.FLOATS);
        upload.position(0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, segTex);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, slot, n * 2, 1,
                GLES30.GL_RGBA, GLES30.GL_FLOAT, upload);
    }

    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = {0};
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException(GLES30.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(String v, String f) {
        int a = shader(GLES30.GL_VERTEX_SHADER, v), b = shader(GLES30.GL_FRAGMENT_SHADER, f);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, a);
        GLES30.glAttachShader(p, b);
        GLES30.glLinkProgram(p);
        int[] ok = {0};
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException(GLES30.glGetProgramInfoLog(p));
        return p;
    }
}
