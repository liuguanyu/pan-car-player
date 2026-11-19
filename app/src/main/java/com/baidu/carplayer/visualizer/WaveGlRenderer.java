package com.baidu.carplayer.visualizer;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GLES 3.0 月夜海浪渲染器：GPU 生成波面、海雾、月光、泡沫与帆船。
 *
 * <p>CPU 侧只做音频特征提取和少量需要"记忆"的状态积分：
 * <ul>
 *   <li>风力 {@code uWind}：响度包络（起风快、回落慢）+ 自然阵风 + 鼓点阵风，再经一阶惯性平滑，
 *       所以帆和船的反应是连续、有惯性的，而不是逐帧跟着音量跳；</li>
 *   <li>船位 {@code uBoatX}：按风力积分出航速，船从左向右航行，出画后从另一侧回来。</li>
 * </ul>
 */
public final class WaveGlRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "WaveGl";
    private static final byte[] EMPTY = new byte[0];
    private static final int HISTORY_W = 128, HISTORY_H = 48;
    /** 船在画面外多远处回绕（uv 单位），保证回绕时整船都在画外。 */
    private static final float BOAT_MIN_X = -0.12f, BOAT_MAX_X = 1.12f;

    public interface FftSource { byte[] latest(); int epoch(); }
    public interface TempoSource { float periodMs(); }
    public interface IdleListener { void onIdle(); }

    private volatile FftSource source;
    private volatile TempoSource tempo;
    private volatile IdleListener idle;
    private volatile boolean active;

    private boolean failed;
    private int epoch = Integer.MIN_VALUE, w = 1, h = 1, program, vao, tex, head;
    private long lastNs, lastFft;
    private float time, energy, bass, kick, loudness;
    /** 风：loudEnv 是响度包络，gust 是鼓点阵风冲量，wind 是最终平滑后的风力。 */
    private float loudEnv, gust, wind = 0.25f;
    private float boatX = 0.27f;
    private final FloatBuffer rowBuffer = ByteBuffer.allocateDirect(HISTORY_W * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] row = new float[HISTORY_W];

    private int uTime, uEnergy, uBass, uKick, uAspect, uPeriod, uHistory, uHead, uWind, uBoatX;

    public void setFftSource(FftSource s) { source = s; }
    public void setTempoSource(TempoSource s) { tempo = s; }
    public void setIdleListener(IdleListener l) { idle = l; }
    public void setWantActive(boolean v) { active = v; }

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            program = link(WaveGlShaders.VERT, WaveGlShaders.FRAG);
        } catch (RuntimeException e) {
            Log.e(TAG, "shader", e);
            failed = true;
            return;
        }
        uTime = GLES30.glGetUniformLocation(program, "uTime");
        uEnergy = GLES30.glGetUniformLocation(program, "uEnergy");
        uBass = GLES30.glGetUniformLocation(program, "uBass");
        uKick = GLES30.glGetUniformLocation(program, "uKick");
        uAspect = GLES30.glGetUniformLocation(program, "uAspect");
        uPeriod = GLES30.glGetUniformLocation(program, "uPeriod");
        uHistory = GLES30.glGetUniformLocation(program, "uHistory");
        uHead = GLES30.glGetUniformLocation(program, "uHistoryHead");
        uWind = GLES30.glGetUniformLocation(program, "uWind");
        uBoatX = GLES30.glGetUniformLocation(program, "uBoatX");

        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        vao = ids[0];
        GLES30.glBindVertexArray(vao);

        GLES30.glGenTextures(1, ids, 0);
        tex = ids[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        // 用全 0 初始化，否则未定义内容会在海面上显示成矩形块
        FloatBuffer zero = ByteBuffer.allocateDirect(HISTORY_W * HISTORY_H * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, HISTORY_W, HISTORY_H, 0,
                GLES30.GL_RED, GLES30.GL_FLOAT, zero);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
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

        float kickBefore = kick;
        update();
        kick *= (float) Math.pow(.90, dt * 60);
        energy += (((bass + loudness) * .5f > .003f ? 1f : 0f) - energy) * .18f;
        updateWind(dt, kick > kickBefore + .2f);
        updateBoat(dt);

        GLES30.glClearColor(.004f, .006f, .012f, 1);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUseProgram(program);
        GLES30.glBindVertexArray(vao);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glUniform1i(uHistory, 0);
        GLES30.glUniform1i(uHead, head);
        GLES30.glUniform1f(uTime, time);
        GLES30.glUniform1f(uEnergy, energy);
        GLES30.glUniform1f(uBass, bass);
        GLES30.glUniform1f(uKick, kick);
        GLES30.glUniform1f(uAspect, w / (float) h);
        TempoSource t = tempo;
        GLES30.glUniform1f(uPeriod, t == null ? 500 : t.periodMs());
        GLES30.glUniform1f(uWind, wind);
        GLES30.glUniform1f(uBoatX, boatX);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

        if (!active && energy < .004f && kick < .01f && idle != null) idle.onIdle();
    }

    /** 读一帧 FFT，更新低频、响度与鼓点，并按约 22 Hz 写入一行频谱历史。 */
    private void update() {
        FftSource fs = source;
        byte[] fft = fs == null ? EMPTY : fs.latest();
        if (fs != null && epoch != fs.epoch()) {
            epoch = fs.epoch();
            head = 0;
        }
        float[] mags = AudioAnalyzer.fftToMagnitudes(fft, 256);
        float total = 0, low = 0;
        for (int i = 0; i < HISTORY_W; i++) {
            float v = i < mags.length ? mags[i] : 0;
            row[i] = v;
            total += v;
            if (i < 32) low += v;
        }
        bass += (Math.min(1, low / 32 * 2.2f) - bass) * .3f;
        loudness = Math.min(1, total / HISTORY_W * 3);
        if (loudness > energy + .04f) kick = Math.min(1.2f, kick + .5f);

        long ms = System.currentTimeMillis();
        if (ms - lastFft > 45) {
            lastFft = ms;
            head = (head + 1) % HISTORY_H;
            rowBuffer.clear();
            rowBuffer.put(row).position(0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, head, HISTORY_W, 1,
                    GLES30.GL_RED, GLES30.GL_FLOAT, rowBuffer);
        }
    }

    /**
     * 风力模型。真实的风有三个特征，这里一一对应：
     * <ol>
     *   <li>随大环境变化：响度包络，起风快（约 0.4 s）、回落慢（约 2.5 s），乐曲越饱满风越大；</li>
     *   <li>阵风是不规则的：几个非整数比频率的正弦叠加，不会出现机械的周期感；</li>
     *   <li>重拍带来一阵风：鼓点给一个冲量，约 1.2 s 衰减。</li>
     * </ol>
     * 最后整体经 0.7 s 的一阶惯性平滑，帆和船不会逐帧抖动。
     */
    private void updateWind(float dt, boolean beat) {
        float drive = Math.min(1f, .6f * loudness + .5f * bass);
        float tau = drive > loudEnv ? .40f : 2.5f;
        loudEnv += (drive - loudEnv) * (1f - (float) Math.exp(-dt / tau));

        if (beat) gust = Math.min(.45f, gust + .22f);
        gust *= (float) Math.exp(-dt / 2.0f);

        float swirl = .55f * (float) Math.sin(time * .23f)
                + .30f * (float) Math.sin(time * .61f + 1.3f)
                + .15f * (float) Math.sin(time * 1.47f + 2.1f);

        float target = .18f + .75f * loudEnv + gust + .10f * swirl * (.4f + loudEnv);
        target = Math.max(.05f, Math.min(1.15f, target));
        wind += (target - wind) * (1f - (float) Math.exp(-dt / 1.30f));
    }

    /** 航速随风力：无风时缓慢漂移，满风时约 45 s 横穿画面。 */
    private void updateBoat(float dt) {
        boatX += dt * (.003f + .024f * wind);
        if (boatX > BOAT_MAX_X) boatX = BOAT_MIN_X;
    }

    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException(GLES30.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(String vert, String frag) {
        int v = shader(GLES30.GL_VERTEX_SHADER, vert), f = shader(GLES30.GL_FRAGMENT_SHADER, frag);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException(GLES30.glGetProgramInfoLog(p));
        return p;
    }
}
