package com.baidu.carplayer.visualizer;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import com.baidu.carplayer.visualizer.GlslLoader;

/**
 * GLES 3.0 3D 频谱柱渲染器。
 *
 * <p>64 根真实立方体柱排在地平线上，固定透视相机（略俯视、微侧倾），
 * 逐面着色：正面垂直渐变 + 顶部辉光、顶面受光最亮、侧面背光压暗；
 * 地面有随高度指数淡出的镜面倒影。配色沿用 Canvas 版的绿→青→蓝。
 *
 * <p>音频手感与 Canvas 版一致：快攻击（.55）、慢衰减（.22），
 * 鼓点（BeatTracker 拍点跨越）给一次整体亮度脉冲。
 */
public final class BarsGlRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "BarsGl";
    private static final byte[] EMPTY = new byte[0];
    private static final int BAR_COUNT = 64;
    /** 立方体可见面模板：3 面 × 2 三角 × 3 顶点 = 18。 */
    private static final int VERTS = 18;
    /** 倒影模糊：多遍绘制 + 屏幕空间垂直偏移（像素），低透明度叠加。 */
    private static final int MIRROR_PASSES = 4;
    private static final float[] MIRROR_OFFSETS = {-4.5f, -1.5f, 1.5f, 4.5f};
    private static final float MIRROR_ALPHA = .30f;

    public interface FftSource { byte[] latest(); int epoch(); }
    public interface TempoSource { double beatPosition(long nowMs); }
    public interface IdleListener { void onIdle(); }

    private volatile FftSource source;
    private volatile TempoSource tempo;
    private volatile IdleListener idle;
    private volatile boolean active;

    private boolean failed;
    private int w = 1, h = 1, epoch = Integer.MIN_VALUE;
    private int bgProgram, barProgram, vao;
    private int uVp;
    private int uHeights, uSide, uMirror, uKick;
    /** 柱宽与列距由 CPU 按投影结果算出后传入，保证柱群恰好铺满可视宽度。 */
    private int uBarPitch, uBarW, uBarH;
    private int uRipple, uBlurOffset, uMirrorAlpha, uHeartbeat;
    private int uBgAspect, uBgHorizon;
    private final float[] vp = new float[16];
    private final float[] invVp = new float[16];
    /** 每帧由投影结果推出：柱距、柱宽、柱高上限（世界单位）。 */
    private float barPitch = .2f, barW = .16f, barHmax = 3.5f;
    private final float[] heights = new float[BAR_COUNT];
    /** GLSL 里打包成 vec4[16] 的一行，用 uniform4fv 上传。 */
    private final float[] heightsPacked = new float[BAR_COUNT];

    private long lastNs;
    /** 累计时间（秒），给倒影噪点做时间变化。 */
    private float time;
    private double lastBeat = Double.NaN;
    private float kick;
    private boolean anyActive;

    public void setFftSource(FftSource s) { source = s; }
    public void setTempoSource(TempoSource s) { tempo = s; }
    public void setIdleListener(IdleListener l) { idle = l; }
    public void setWantActive(boolean v) { active = v; }

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // shader 从 res/raw 读取，需要 Context；由 View 在 setRenderer 前注入（见 BarsGlView）。
        if (appContext == null) { failed = true; Log.e(TAG, "no context"); return; }
        try {
            bgProgram = GlslLoader.program(appContext,
                    com.baidu.carplayer.R.raw.bars_bg_vert, com.baidu.carplayer.R.raw.bars_bg_frag);
            barProgram = GlslLoader.program(appContext,
                    com.baidu.carplayer.R.raw.bars_bar_vert, com.baidu.carplayer.R.raw.bars_bar_frag);
        } catch (RuntimeException e) {
            Log.e(TAG, "shader", e);
            failed = true;
            return;
        }
        uBgAspect = GLES30.glGetUniformLocation(bgProgram, "uAspect");
        uBgHorizon = GLES30.glGetUniformLocation(bgProgram, "uHorizon");
        uVp = GLES30.glGetUniformLocation(barProgram, "uVP");
        uHeights = GLES30.glGetUniformLocation(barProgram, "uHeights");
        uSide = GLES30.glGetUniformLocation(barProgram, "uSide");
        uMirror = GLES30.glGetUniformLocation(barProgram, "uMirror");
        uKick = GLES30.glGetUniformLocation(barProgram, "uKick");
        uBarPitch = GLES30.glGetUniformLocation(barProgram, "uBarPitch");
        uBarW = GLES30.glGetUniformLocation(barProgram, "uBarW");
        uBarH = GLES30.glGetUniformLocation(barProgram, "uBarH");
        uRipple = GLES30.glGetUniformLocation(barProgram, "uRipple");
        uBlurOffset = GLES30.glGetUniformLocation(barProgram, "uBlurOffset");
        uMirrorAlpha = GLES30.glGetUniformLocation(barProgram, "uMirrorAlpha");
        uHeartbeat = GLES30.glGetUniformLocation(barProgram, "uHeartbeat");

        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        vao = ids[0];
        GLES30.glBindVertexArray(vao);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** 由 View 在 setRenderer 前注入的应用 Context（读 raw 资源用）。 */
    private Context appContext;

    public void setContext(Context context) { this.appContext = context.getApplicationContext(); }

    /** 柱群宽度占可视宽度的比例；<1 给边缘留余量，避免最外侧柱被裁切。 */
    private static final float FILL = .94f;

    /** 地面基线的屏幕 uv 高度，每帧由投影反解得出。 */
    private float horizonUv = .35f;

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

        updateAudio(dt);
        kick *= (float) Math.pow(.88, dt * 60);

        computeVp();
        GLES30.glClearColor(.0706f, .0706f, .0706f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        // 背景：天空 + 地面
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glUseProgram(bgProgram);
        GLES30.glBindVertexArray(vao);
        GLES30.glUniform1f(uBgAspect, w / (float) h);
        GLES30.glUniform1f(uBgHorizon, horizonUv);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

        // 柱体（先倒影后实体；凸面无重叠，无需深度测试）
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glUseProgram(barProgram);
        GLES30.glUniformMatrix4fv(uVp, 1, false, vp, 0);
        GLES30.glUniform4fv(uHeights, 16, heightsPacked, 0);
        GLES30.glUniform1f(uSide, -1f);
        GLES30.glUniform1f(uKick, kick);
        GLES30.glUniform1f(uHeartbeat, (time * 37.1f) % 97f);
        GLES30.glUniform1f(uBarPitch, barPitch);
        GLES30.glUniform1f(uBarW, barW);
        GLES30.glUniform1f(uBarH, barHmax);
        // 倒影：4 遍屏幕空间垂直偏移 + 低透明度叠加 = 廉价模糊，并带横向涟漪
        GLES30.glUniform1f(uMirror, 1f);
        GLES30.glUniform1f(uRipple, barPitch * .35f);
        GLES30.glUniform1f(uMirrorAlpha, MIRROR_ALPHA);
        for (int i = 0; i < MIRROR_PASSES; i++) {
            GLES30.glUniform1f(uBlurOffset, MIRROR_OFFSETS[i] * 2f / h);
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, VERTS, BAR_COUNT);
        }
        GLES30.glUniform1f(uMirror, 0f);
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, VERTS, BAR_COUNT);

        if (!active && !anyActive && idle != null) idle.onIdle();
    }

    /**
     * 固定相机：正对柱排（cx = 0，所以柱排基线严格水平、左右对称），
     * 略俯视以看到顶面。距离和视场角选得让柱群几乎铺满画面宽度。
     *
     * <p>相机参数只决定"看的角度"，柱距/柱宽/基线位置全部由投影矩阵反解出来
     * （见 {@link #solveLayout()}），不在这里手算，避免改了相机就错位。
     */
    private void computeVp() {
        float[] proj = new float[16];
        float aspect = w / (float) h;
        Matrix.perspectiveM(proj, 0, 20f, aspect, .5f, 200f);
        float cx = 0f, cy = 4.0f, cz = -12.2f;
        float lookX = 0f, lookY = .9f, lookZ = 0f;
        float upX = 0f, upY = 1f, upZ = 0f;
        Matrix.setLookAtM(vp, 0, cx, cy, cz, lookX, lookY, lookZ, upX, upY, upZ);
        float[] view = new float[16];
        System.arraycopy(vp, 0, view, 0, 16);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        Matrix.invertM(invVp, 0, vp, 0);
        solveLayout();
    }

    /**
     * 由投影矩阵反解布局，全部从"射线打到地面 z=0 平面"的实际结果算出：
     * <ul>
     *   <li>{@code horizonUv}：地面基线的屏幕位置，传给背景，使地面亮线与柱脚重合；</li>
     *   <li>{@code barPitch}：可视宽度 / 64，柱群右左恰好铺满，不留白；</li>
     *   <li>{@code barW}：柱距的 82%，留出柱间空隙。</li>
     * </ul>
     */
    private void solveLayout() {
        // 取柱排中心水平方向的可视半宽；柱子有宽有厚、相机有俯角，
        // 边缘柱的外侧会略微超出，所以留 6% 余量（fill=0.94）。
        float hw = Math.max(Math.abs(rayHitX(-1f)), Math.abs(rayHitX(1f)));
        barPitch = hw * 2f * FILL / BAR_COUNT;
        barW = barPitch * .80f;
        barHmax = barPitch * 16f;
        horizonUv = ndcYtoUv(projectY(0f, 0f, 0f));
    }

    /**
     * 屏幕 NDC x 方向的射线与<b>柱排所在平面 z=0</b> 的交点 x。
     *
     * <p>注意不能与地面 y=0 求交：相机俯视时那条射线打在地面上的点比柱子远得多，
     * 算出来的"可视宽度"偏大，柱群就会溢出画面两侧。
     */
    private float rayHitX(float ndcX) {
        float[] a = unproject(ndcX, 0f, -1f);
        float[] b = unproject(ndcX, 0f, 1f);
        float dz = b[2] - a[2];
        if (Math.abs(dz) < 1e-6f) return 0f;
        float t = (0f - a[2]) / dz;
        return a[0] + (b[0] - a[0]) * t;
    }

    private float[] unproject(float x, float y, float z) {
        float[] p = new float[4];
        Matrix.multiplyMV(p, 0, invVp, 0, new float[]{x, y, z, 1f}, 0);
        if (Math.abs(p[3]) < 1e-9f) return new float[]{0f, 0f, 0f};
        return new float[]{p[0] / p[3], p[1] / p[3], p[2] / p[3]};
    }

    /** 世界点投影后的屏幕 NDC y。 */
    private float projectY(float wx, float wy, float wz) {
        float[] p = new float[4];
        Matrix.multiplyMV(p, 0, vp, 0, new float[]{wx, wy, wz, 1f}, 0);
        return Math.abs(p[3]) < 1e-9f ? 0f : p[1] / p[3];
    }

    private static float ndcYtoUv(float ndcY) { return ndcY * .5f + .5f; }

    private void updateAudio(float dt) {
        FftSource fs = source;
        byte[] fft = fs == null ? EMPTY : fs.latest();
        if (fs != null && epoch != fs.epoch()) {
            epoch = fs.epoch();
            lastBeat = Double.NaN;
        }
        float[] raw = AudioAnalyzer.fftToMagnitudes(fft, BAR_COUNT);
        anyActive = false;
        for (int i = 0; i < BAR_COUNT; i++) {
            float target = i < raw.length ? (float) Math.sqrt(raw[i]) : 0f;
            float prev = heights[i];
            heights[i] = prev + (target - prev) * (target > prev ? .55f : .22f);
            if (heights[i] > .004f) anyActive = true;
        }
        System.arraycopy(heights, 0, heightsPacked, 0, BAR_COUNT);

        TempoSource t = tempo;
        if (t != null) {
            double beat = t.beatPosition(System.currentTimeMillis());
            if (!Double.isNaN(lastBeat) && Math.floor(beat) > Math.floor(lastBeat)) {
                kick = Math.min(1.2f, kick + .55f);
            }
            lastBeat = beat;
        }
    }
}
