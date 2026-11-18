package com.baidu.carplayer.visualizer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * "光轴环群 + 彗星"粒子效果（黑白电影感）。
 *
 * 场景是真正的 3D 结构：一条贯穿画面的光轴，16 个粒子圆环像铜钱一样串在轴上
 * （环面垂直于轴），一颗彗星沿光轴逐个穿过这些圆环。
 *
 *  - 环的截面是"封闭曲线"：半径 r(θ)=R·(1+Σ Aₕ·cos(nₕθ+φₕ))，各谐波幅度来自频谱
 *    （低频→低阶谐波），总幅度被限制在 0.45 以内，因此曲线始终封闭、不自交；
 *    环周形状 = 出生时冻结的频谱快照 × 当前实时频谱，随响度/鼓点持续跳动变化；
 *  - 彗星从远端出发，速度略快于环的流速，因此一个环接一个环地被穿过：
 *    彗头越过时该环瞬间被点亮、膨胀、形状放大，随后沿彗尾逐渐回落；
 *  - 相机是一条缓慢的环绕运镜：俯仰/偏航（环在椭圆↔近圆之间变形）、侧倾、推拉，
 *    鼓点会让相机微微前推；
 *  - 流速随运动能量（响度 × BPM）；鼓点让所有环外胀、光轴端点闪光。
 *
 * 性能：每帧 ~2400 个粒子做 3D 变换 + 透视投影，按景深分 4 档用 drawPoints 批量绘制，
 * 每环再叠一层极淡的闭合轮廓线；不逐粒子创建对象。
 */
public final class ParticlesVisualizer implements AudioVisualizer {
    private static final int RING_COUNT = 16;
    private static final int DOTS = 150;
    private static final int BANDS = 24;
    private static final int HARM = 7;          // 轮廓谐波：阶数 2..8
    private static final int DEPTH_BUCKETS = 4;
    private static final float SHAPE_CAP = 0.45f; // 轮廓总幅度上限，保证封闭且不自交
    private static final int TAIL_SEGMENTS = 22;
    /** 按景深分档的线宽/亮度（预分配，避免每帧产生临时数组）。 */
    private static final float[] DEPTH_WIDTH = {1.6f, 2.4f, 3.4f, 4.6f};
    private static final int[] DEPTH_ALPHA = {135, 195, 240, 255};
    private static final PorterDuffXfermode ADD_MODE = new PorterDuffXfermode(PorterDuff.Mode.ADD);

    /** 节拍来源：由 VisualizerManager 注入共享节拍跟踪器，为空按 120 BPM。 */
    interface TempoSource { float periodMs(); }
    private TempoSource tempoSource;

    private final Random random = new Random();
    private final AudioAnalyzer analyzer = new AudioAnalyzer();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path contour = new Path();
    private final RectF rect = new RectF();
    private Bitmap glowSprite;

    // 预计算的圆周三角函数与各阶谐波表
    private final float[] cosK = new float[DOTS];
    private final float[] sinK = new float[DOTS];
    private final float[][] cosH = new float[HARM][DOTS];
    private final float[][] sinH = new float[HARM][DOTS];

    // 每个环出生时冻结的状态
    private final float[] spin = new float[RING_COUNT];
    private final float[] ringChar = new float[RING_COUNT];
    private final float[] ringSig = new float[RING_COUNT];
    private final float[][] ringSnap = new float[RING_COUNT][HARM];
    private final float[][] ringPhase = new float[RING_COUNT][HARM];
    private final float[] prevPhase = new float[RING_COUNT];

    // 每帧复用的缓冲
    private final float[][] pts = new float[DEPTH_BUCKETS][DOTS * 2];
    private final int[] ptCount = new int[DEPTH_BUCKETS];
    private final float[] amp = new float[HARM];
    private final float[] cph = new float[HARM];
    private final float[] sph = new float[HARM];

    // 音频状态
    private final float[] spectrum = new float[BANDS];
    private final float[] liveHarm = new float[HARM];
    private int width, height;
    private float bass, mid, volume, level;
    private float motionEnergy;
    private float energy;      // 整体可见度：停止后衰减到 0
    private float pulse;       // 鼓点膨胀包络
    private float kick;        // 鼓点流速冲击
    private float flow;        // 环沿轴流动相位
    private float time;        // 秒
    private float camTime;     // 运镜时间（随能量略微加速）
    private float shapeTime;   // 轮廓谐波漂移时间（随响度加速）
    private long lastFrameMs = -1;
    private boolean seeded;

    // 彗星：cometP 为沿光轴的进度 0..1；cometIdle>0 表示两次穿越之间的停顿
    private float cometP;
    private float cometIdle = 0.6f;

    // 相机（每帧计算一次，project() 复用）
    private float cg, sg, cp, sp, cr, sr, dolly, focal, cx, cy;
    private float projX, projY, projS;

    @Override public String getName() { return "particles"; }

    /** 注入共享节拍跟踪器（估算 BPM 用）。 */
    public void setTempoSource(TempoSource source) { this.tempoSource = source; }

    public ParticlesVisualizer() {
        for (int k = 0; k < DOTS; k++) {
            double a = k * Math.PI * 2 / DOTS;
            cosK[k] = (float) Math.cos(a);
            sinK[k] = (float) Math.sin(a);
            for (int h = 0; h < HARM; h++) {
                cosH[h][k] = (float) Math.cos((h + 2) * a);
                sinH[h][k] = (float) Math.sin((h + 2) * a);
            }
        }
        pointPaint.setStrokeCap(Paint.Cap.ROUND);
        pointPaint.setStyle(Paint.Style.STROKE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void update(byte[] fft, int width, int height) {
        this.width = width;
        this.height = height;
        AudioAnalyzer.Analysis analysis = analyzer.analyze(fft);
        boolean hasData = fft.length > 0;
        bass += ((hasData ? analysis.bass : 0f) - bass) * (analysis.bass > bass ? .45f : .18f);
        mid += ((hasData ? analysis.mid : 0f) - mid) * (analysis.mid > mid ? .45f : .18f);
        volume += ((hasData ? analysis.volume : 0f) - volume) * (analysis.volume > volume ? .4f : .12f);
        float lvTarget = Math.max(bass * 0.95f, Math.max(mid * 0.85f, volume));
        level += (lvTarget - level) * (lvTarget > level ? .5f : .1f);

        float[] raw = AudioAnalyzer.fftToMagnitudes(fft, BANDS * 2);
        for (int i = 0; i < BANDS; i++) {
            float a = i * 2 < raw.length ? raw[i * 2] : 0f;
            float b = i * 2 + 1 < raw.length ? raw[i * 2 + 1] : 0f;
            float target = (float) Math.sqrt((a + b) * 0.5f);
            spectrum[i] += (target - spectrum[i]) * (target > spectrum[i] ? .55f : .14f);
        }
        harmonicsOf(spectrum, liveHarm);

        float period = tempoSource != null ? tempoSource.periodMs() : 500f;
        float bpm = 60000f / Math.max(300f, Math.min(1000f, period));
        float tempoNorm = Math.max(0f, Math.min(1f, (bpm - 60f) / 140f));
        motionEnergy = (0.35f + volume * 1.5f) * (0.55f + tempoNorm * 0.9f);

        boolean beat = hasData
                && (analysis.peak || (bass > .45f && bass - analysis.bass < 0 && mid > .12f));
        if (beat) {
            pulse = Math.min(1.4f, pulse + .55f + bass * .5f);
            kick = Math.min(0.25f, kick + 0.1f);
        }
    }

    /** 24 段频谱 → 7 阶轮廓谐波：低频段对应低阶（大块起伏），高频段对应高阶（细碎起伏）。 */
    private static void harmonicsOf(float[] bands, float[] out) {
        for (int h = 0; h < HARM; h++) {
            float sum = 0f;
            for (int j = 0; j < 3; j++) sum += bands[h * 3 + j];
            out[h] = sum / 3f;
        }
    }

    @Override public void draw(Canvas canvas) {
        if (width <= 0 || height <= 0) return;
        long now = System.nanoTime() / 1_000_000L;
        float dt = lastFrameMs < 0 ? 16f : Math.min(50f, now - lastFrameMs);
        lastFrameMs = now;
        float dts = dt / 1000f;
        float dtf = Math.max(0.5f, dt / 16f);
        time += dts;

        if (glowSprite == null) glowSprite = softCircle(192);
        if (!seeded) seed();

        // 近纯黑背景
        paint.setXfermode(null);
        paint.setShader(null);
        canvas.drawColor(Color.argb(0xE6, 2, 3, 6), PorterDuff.Mode.SRC_OVER);

        // 可见度：有信号时常亮，停止后衰减到 0（静止态契约）；脉冲/冲击按帧衰减
        boolean hasSignal = bass > 0.001f || mid > 0.001f;
        energy += ((hasSignal ? 1f : 0f) - energy) * (hasSignal ? .3f : .05f);
        pulse *= (float) Math.pow(0.93f, dtf);
        kick *= (float) Math.pow(0.95f, dtf);
        if (energy < 0.004f) return;

        // 沿轴流动：基础速度 + 运动能量 + 鼓点冲击
        float baseFlow = 0.03f + Math.min(2f, motionEnergy) * 0.045f;
        flow += dts * (baseFlow + kick);
        camTime += dts * (0.75f + 0.45f * Math.min(1.5f, motionEnergy));
        shapeTime += dts * (0.5f + 1.6f * level);

        float scale = Math.max(1f, height / 190f);
        float pxUnit = 0.55f * height;
        float zMax = 0.80f * width / pxUnit;
        float camDist = 1.6f * zMax + 3f;
        setupCamera(camDist, pxUnit);

        // 彗星：速度略快于环流，从远端逐个穿过圆环
        float zAxis = zMax * 1.28f;
        float cometVis = advanceComet(dts, baseFlow);
        float cometX = zAxis * (2f * cometP - 1f);

        drawRings(canvas, zMax, cometX, cometVis, scale);
        drawAxisAndComet(canvas, zAxis, cometX, cometVis, zMax, scale);
    }

    // ---------- 相机 ----------

    /**
     * 缓慢的环绕运镜：
     *  - 偏航 γ：光轴相对视线的夹角，决定环是椭圆还是近圆（0.28~1.15 rad）；
     *  - 俯仰 φ：相机上下抬/压；侧倾 ρ：画面整体倾斜；
     *  - 推拉：相机距离缓慢呼吸，鼓点时微微前推。
     * 各分量周期互不整除，组合出的运镜长期不重复。
     */
    private void setupCamera(float camDist, float pxUnit) {
        float gamma = 0.70f + 0.36f * (float) Math.sin(camTime * 0.105f)
                + 0.10f * (float) Math.sin(camTime * 0.29f + 1.3f);
        gamma = Math.max(0.28f, Math.min(1.15f, gamma));
        float phi = 0.32f * (float) Math.sin(camTime * 0.083f + 0.9f)
                + 0.07f * (float) Math.sin(camTime * 0.23f);
        float rho = 0.14f + 0.20f * (float) Math.sin(camTime * 0.061f + 0.6f);
        cg = (float) Math.cos(gamma); sg = (float) Math.sin(gamma);
        cp = (float) Math.cos(phi);   sp = (float) Math.sin(phi);
        cr = (float) Math.cos(rho);   sr = (float) Math.sin(rho);
        dolly = camDist * (1f + 0.10f * (float) Math.sin(camTime * 0.14f + 2f)
                - 0.05f * Math.min(1f, pulse));
        focal = pxUnit * camDist;
        cx = width * 0.5f;
        cy = height * 0.5f;
    }

    /** 场景坐标（x 沿光轴，y 竖直，zs 为环面内的深度向分量）→ 屏幕；结果写入 projX/projY/projS。 */
    private void project(float x, float y, float zs) {
        float x1 = x * cg + zs * sg;
        float z1 = -x * sg + zs * cg;
        float y2 = y * cp - z1 * sp;
        float z2 = y * sp + z1 * cp;
        float d = Math.max(0.6f, dolly + z2);
        float s = focal / d;
        float sx = x1 * s, sy = y2 * s;
        projX = cx + sx * cr - sy * sr;
        projY = cy + sx * sr + sy * cr;
        projS = s;
    }

    // ---------- 彗星 ----------

    /** 推进彗星进度，返回可见度 0..1（出入场渐隐，停顿期为 0）。 */
    private float advanceComet(float dts, float baseFlow) {
        if (cometIdle > 0f) {
            cometIdle -= dts;
            if (cometIdle <= 0f) cometP = 0f;
            return 0f;
        }
        // 光轴长度是环带的 1.28 倍；速度 = 环流速折算 + 固定超速，保证稳定地一环接一环穿过
        float speed = baseFlow / 1.28f + 0.07f;
        cometP += dts * speed;
        if (cometP >= 1f) {
            cometP = 1f;
            cometIdle = 1.0f + random.nextFloat() * 1.6f;
            return 0f;
        }
        float in = Math.min(1f, cometP / 0.07f);
        float out = Math.min(1f, (1f - cometP) / 0.07f);
        return energy * in * out;
    }

    // ---------- 环群 ----------

    private void drawRings(Canvas canvas, float zMax, float cometX, float cometVis, float scale) {
        float spacing = 2f * zMax / RING_COUNT;   // 相邻两环的轴向间距

        for (int i = 0; i < RING_COUNT; i++) {
            float phase = flow + i / (float) RING_COUNT;
            phase -= (float) Math.floor(phase);
            // 环从入口（phase 回绕）出生：冻结当时的响度与频谱
            if (phase < prevPhase[i]) born(i);
            prevPhase[i] = phase;

            float x = (phase * 2f - 1f) * zMax;
            float edge = 1f - Math.abs(phase * 2f - 1f);        // 两端为 0
            float env = (float) Math.pow(Math.max(0f, edge), 0.55f);

            // 彗星穿环：彗头越过时最亮，之后沿彗尾缓慢回落；彗头前方有一点预热
            float flare = 0f;
            if (cometVis > 0f) {
                float passed = cometX - x;      // >0：彗星已越过该环
                flare = passed >= 0f
                        ? (float) Math.exp(-passed / (3.5f * spacing))
                        : 0.9f * (float) Math.exp(-(passed * passed) / (0.64f * spacing * spacing));
                flare *= cometVis;
            }

            float ringAlpha = env * energy * (0.6f + 0.4f * Math.min(1f, ringSig[i]))
                    + 0.55f * flare * env;
            ringAlpha = Math.min(1f, ringAlpha);
            if (ringAlpha < 0.02f) continue;

            // 半径：基础波浪 × 出生时响度 × 实时响度 × 鼓点膨胀 × 彗星穿过时的外扩
            float personality = 0.42f + 1.1f * ringChar[i];
            float wave = 0.7f + 0.55f * (float) Math.sin(phase * 4.4f + time * 0.45f + i * 0.9f);
            float radius = 0.85f * personality * wave * (0.55f + 0.7f * ringSig[i])
                    * (0.9f + 0.35f * level) * (1f + 0.28f * pulse) * (1f + 0.22f * flare);

            // 封闭轮廓：各阶谐波幅度（快照与实时频谱各半），总和受 SHAPE_CAP 约束
            float boost = 1f + 0.8f * flare + 0.7f * pulse;
            float sum = 0f;
            float[] snap = ringSnap[i];
            float[] ph0 = ringPhase[i];
            for (int h = 0; h < HARM; h++) {
                float v = Math.max(0.06f, 0.5f * snap[h] + 0.5f * liveHarm[h]);
                float a = v * 0.36f / (1f + 0.2f * h) * boost;
                amp[h] = a;
                sum += a;
                float drift = shapeTime * ((h & 1) == 0 ? 1f : -1f) * (0.30f + 0.06f * h);
                float ph = ph0[h] + drift;
                cph[h] = (float) Math.cos(ph);
                sph[h] = (float) Math.sin(ph);
            }
            if (sum > SHAPE_CAP) {
                float k = SHAPE_CAP / sum;
                for (int h = 0; h < HARM; h++) amp[h] *= k;
            }

            float spinNow = time * (i % 2 == 0 ? 0.28f : -0.2f) + spin[i];
            float cs = (float) Math.cos(spinNow), sn = (float) Math.sin(spinNow);
            for (int b = 0; b < DEPTH_BUCKETS; b++) ptCount[b] = 0;
            contour.reset();

            for (int k = 0; k < DOTS; k++) {
                float f = 1f;
                for (int h = 0; h < HARM; h++) {
                    f += amp[h] * (cosH[h][k] * cph[h] - sinH[h][k] * sph[h]);
                }
                float rr = radius * f;
                float zs = rr * (cosK[k] * cs - sinK[k] * sn);
                float y = rr * (sinK[k] * cs + cosK[k] * sn);
                project(x, y, zs);
                float px = projX, py = projY;
                if (k == 0) contour.moveTo(px, py); else contour.lineTo(px, py);
                if (px < -20f || px > width + 20f || py < -20f || py > height + 20f) continue;
                float rel = projS / (focal / dolly);
                int bucket = rel < 0.85f ? 0 : rel < 1.1f ? 1 : rel < 1.45f ? 2 : 3;
                int n = ptCount[bucket];
                pts[bucket][n++] = px;
                pts[bucket][n++] = py;
                ptCount[bucket] = n;
            }
            contour.close();

            // 闭合轮廓：细线压住粒子串，让“环始终封闭”一眼可见
            linePaint.setStrokeWidth(Math.max(1f, 1.15f * scale) * (1f + 0.8f * flare));
            linePaint.setColor(Color.argb((int) (255 * Math.min(1f, (0.32f + 0.45f * flare) * ringAlpha)),
                    255, 255, 255));
            canvas.drawPath(contour, linePaint);

            float wBoost = 1f + 0.5f * flare;
            for (int b = 0; b < DEPTH_BUCKETS; b++) {
                if (ptCount[b] == 0) continue;
                pointPaint.setStrokeWidth(DEPTH_WIDTH[b] * scale * wBoost);
                pointPaint.setColor(Color.argb(Math.min(255, (int) (DEPTH_ALPHA[b] * ringAlpha)), 255, 255, 255));
                canvas.drawPoints(pts[b], 0, ptCount[b], pointPaint);
            }
        }
    }

    /** 环出生：冻结当前响度与频谱（折算成轮廓谐波）。 */
    private void born(int i) {
        ringSig[i] = Math.max(0.15f, Math.min(1.6f, 0.2f + 1.35f * level));
        harmonicsOf(spectrum, ringSnap[i]);
        spin[i] = random.nextFloat() * 6.2832f;
        ringChar[i] = random.nextFloat();
        for (int h = 0; h < HARM; h++) ringPhase[i][h] = random.nextFloat() * 6.2832f;
    }

    private void seed() {
        seeded = true;
        for (int i = 0; i < RING_COUNT; i++) {
            ringSig[i] = 0.35f + random.nextFloat() * 0.45f;
            ringChar[i] = random.nextFloat();
            spin[i] = random.nextFloat() * 6.2832f;
            prevPhase[i] = 0f;
            for (int h = 0; h < HARM; h++) {
                ringSnap[i][h] = random.nextFloat() * 0.35f;
                ringPhase[i][h] = random.nextFloat() * 6.2832f;
            }
        }
    }

    // ---------- 光轴与彗星 ----------

    private void drawAxisAndComet(Canvas canvas, float zAxis, float cometX, float cometVis,
                                  float zMax, float scale) {
        float sRef = focal / dolly;   // 轴心处的透视缩放，作为"正常大小"基准

        // 光轴本体：细而淡的一条线，远细近粗
        project(-zAxis, 0f, 0f);
        float fx = projX, fy = projY, fs = projS;
        project(zAxis, 0f, 0f);
        float nx = projX, ny = projY, ns = projS;
        float axisW = 1.2f * scale * (0.8f + level * 0.6f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(Color.argb((int) (70 * energy), 255, 255, 255));
        linePaint.setStrokeWidth(axisW * 0.5f * (fs / sRef + ns / sRef));
        canvas.drawLine(fx, fy, nx, ny, linePaint);

        if (cometVis <= 0.01f) return;

        // 彗尾：从彗头向远端逐段变细变淡
        float tailLen = 0.95f * zMax;
        float prevX = 0f, prevY = 0f, prevS = 0f;
        for (int j = 0; j <= TAIL_SEGMENTS; j++) {
            float t = j / (float) TAIL_SEGMENTS;
            float x = Math.max(-zAxis, cometX - tailLen * t);
            project(x, 0f, 0f);
            if (j > 0) {
                float fade = (float) Math.pow(1f - t, 1.7f);
                float w = 3.4f * scale * (1f - 0.85f * t) * 0.5f * (projS + prevS) / sRef;
                linePaint.setStrokeWidth(Math.max(0.8f, w));
                linePaint.setColor(Color.argb((int) (235 * fade * cometVis), 255, 255, 255));
                canvas.drawLine(prevX, prevY, projX, projY, linePaint);
            }
            prevX = projX; prevY = projY; prevS = projS;
        }

        // 彗头：核心亮点 + 随响度/鼓点增强的泛光
        project(cometX, 0f, 0f);
        float hx = projX, hy = projY;
        float persp = Math.max(0.35f, Math.min(2.4f, projS / sRef));
        float glow = Math.min(1f, 0.45f * level + 0.55f * Math.min(1f, pulse));
        float r = (16f + 30f * glow) * scale * persp;
        paint.setXfermode(ADD_MODE);
        rect.set(hx - r, hy - r, hx + r, hy + r);
        paint.setAlpha((int) (Math.min(255, 130 + glow * 125) * cometVis));
        canvas.drawBitmap(glowSprite, null, rect, paint);
        paint.setXfermode(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int) (255 * cometVis), 255, 255, 255));
        canvas.drawCircle(hx, hy, 3.2f * scale * persp, paint);
    }

    private static Bitmap softCircle(int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(size / 2f, size / 2f, size / 2f,
                new int[]{0xFFFFFFFF, 0x8CFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.32f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        return bmp;
    }

    @Override public void reset() {
        bass = 0;
        mid = 0;
        volume = 0;
        level = 0;
        motionEnergy = 0;
        energy = 0;
        pulse = 0;
        kick = 0;
        flow = 0;
        time = 0;
        camTime = 0;
        shapeTime = 0;
        cometP = 0;
        cometIdle = 0.6f;
        lastFrameMs = -1;
        seeded = false;
        analyzer.reset();
        java.util.Arrays.fill(spectrum, 0f);
        java.util.Arrays.fill(liveHarm, 0f);
    }

    @Override public boolean isAtRest() {
        return energy < 0.004f && pulse < 0.01f && bass < .004f && mid < .004f;
    }
}
