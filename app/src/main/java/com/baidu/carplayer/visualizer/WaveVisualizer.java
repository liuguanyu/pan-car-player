package com.baidu.carplayer.visualizer;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * 月夜海浪：站在船头看一片随音乐起伏的夜海。
 *
 * <p>海面是一张真实的 3D 高度场，从远到近切成 {@link #ROWS} 条透视剖面（远处密、近处疏），
 * 画家算法由远到近绘制，近浪真实遮挡远浪：
 * <ul>
 *   <li><b>浪形</b>：{@link #WAVES} 组不同方向、不同波长的涌浪叠加，按深水色散关系
 *       （ω = √(gk)）各自传播，全部朝镜头推进；波峰尖、波谷宽（越响越尖），不是正弦；</li>
 *   <li><b>音乐</b>：低频抬高长涌浪，整体响度让短浪变碎变尖；频谱镜像铺在海面上，
 *       能量从远处生成、随浪一层层涌到脚下；鼓点会从远方推来一道大涌；</li>
 *   <li><b>光影</b>：远处海面反射天光更亮、被海雾吞没（大气透视 + 菲涅尔），近处深暗；
 *       浪尖有白沫粒子，月光在海面上铺出一条闪烁的光路；</li>
 *   <li><b>镜头</b>：相机高度与侧倾跟随脚下海面，像船随浪起伏。</li>
 * </ul>
 */
public final class WaveVisualizer implements AudioVisualizer {
    static final int POINTS = 128;
    /** 海面剖面数（远→近）。 */
    static final int ROWS = 44;
    /** 每条剖面的采样列数。 */
    static final int COLS = 112;
    private static final int HISTORY = 48;
    private static final int STARS = 70;
    private static final int WAVES = 5;
    private static final int FRONTS = 3;
    private static final float REST = .004f;
    private static final float FREQ_MIN = 20f;
    private static final float FREQ_MAX = 20000f;
    /** 最近 / 最远剖面到相机的距离（世界单位）。 */
    private static final float Z_NEAR = 1.1f;
    private static final float Z_FAR = 38f;
    /** 播放页窗口背景色：四边羽化到这个颜色，避免可视化区域出现硬边色差。 */
    private static final int PAGE_BG = 0xff121212;

    // 涌浪分量：方向、波数、基础振幅。前两组是长涌（低频驱动），后三组是风浪（响度驱动）。
    private static final float[] W_DX = {-.20f, .34f, -.56f, .68f, .08f};
    private static final float[] W_DZ = {1f, .94f, .83f, .73f, 1f};
    private static final float[] W_K = {.48f, .82f, 1.45f, 2.35f, 3.7f};
    private static final float[] W_A = {.30f, .19f, .10f, .055f, .028f};
    private static final float[] W_SEED = {0f, 1.7f, 4.1f, 2.6f, 5.3f};
    private final float[] wDx = new float[WAVES];
    private final float[] wDz = new float[WAVES];
    private final float[] wOmega = new float[WAVES];
    /** 每帧的有效振幅（随音乐变化）。 */
    private final float[] wAmp = new float[WAVES];

    // ---------- 画笔与缓存 ----------
    private final Paint skyPaint = new Paint();
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crestPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crestGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint addPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint();
    private final Path fillPath = new Path();
    private final Path crestPath = new Path();
    private final float[] rowH = new float[COLS];
    private final float[] rowX = new float[COLS];
    private final float[] rowY = new float[COLS];
    private final float[] foam = new float[COLS * 8];
    private final float[] glitter = new float[COLS * 2];
    private final float[] starXY = new float[STARS * 2];
    private final float[] starBuf = new float[STARS * 2];
    private Shader skyShader, moonShader, reflectShader, mistShader;
    private Shader edgeTop, edgeBottom, edgeLeft, edgeRight;
    private int shaderW = -1, shaderH = -1;

    // ---------- 音频状态 ----------
    private final float[] pointFreq = new float[POINTS];
    private final float[] current = new float[POINTS];
    private final float[][] history = new float[HISTORY][POINTS];
    private int historyHead;
    private boolean freqReady;
    private int width, height;
    private float energy;
    private float bass;
    private float kick;
    private float previousEnergy;

    // ---------- 运动状态 ----------
    private final float[] frontZ = new float[FRONTS];
    private final float[] frontAmp = new float[FRONTS];
    private float time;
    private float camH = 1.2f;
    private float camRoll;
    private int sparkleTick;
    private long lastFrameMs = -1;
    private float testStepMs = -1f;

    public WaveVisualizer() {
        for (int i = 0; i < WAVES; i++) {
            float len = (float) Math.sqrt(W_DX[i] * W_DX[i] + W_DZ[i] * W_DZ[i]);
            wDx[i] = W_DX[i] / len;
            wDz[i] = W_DZ[i] / len;
            // 深水色散：长浪走得快、短浪走得慢；整体放慢到适合观看的速度
            wOmega[i] = (float) Math.sqrt(9.8f * W_K[i]) * .55f;
        }
        fillPaint.setStyle(Paint.Style.FILL);
        crestPaint.setStyle(Paint.Style.STROKE);
        crestPaint.setStrokeJoin(Paint.Join.ROUND);
        crestPaint.setStrokeCap(Paint.Cap.ROUND);
        crestGlowPaint.setStyle(Paint.Style.STROKE);
        crestGlowPaint.setStrokeJoin(Paint.Join.ROUND);
        crestGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        dotPaint.setStyle(Paint.Style.STROKE);
        dotPaint.setStrokeCap(Paint.Cap.ROUND);
        addPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        for (int i = 0; i < STARS; i++) {
            starXY[i * 2] = hash1(i * 12.9898f);
            starXY[i * 2 + 1] = (float) Math.pow(hash1(i * 78.233f + 3f), 1.4f);
        }
    }

    @Override public String getName() { return "wave"; }

    // ================= 音频 =================

    @Override public void update(byte[] fft, int width, int height) {
        this.width = width;
        this.height = height;
        if (!freqReady) initializeFrequencies();
        float[] raw = AudioAnalyzer.fftToMagnitudes(fft, 512);
        float binWidth = 48000f / 1024f;
        for (int i = 0; i < POINTS; i++) {
            float f = pointFreq[i];
            int lo = Math.max(0, (int) ((f - binWidth * 2f) / binWidth));
            int hi = Math.min(raw.length - 1, (int) ((f + binWidth * 2f) / binWidth) + 1);
            float sum = 0f;
            int count = 0;
            for (int b = lo; b <= hi; b++) { sum += raw[b]; count++; }
            float target = count == 0 ? 0f : (float) Math.sqrt(sum / count);
            current[i] += (target - current[i]) * (target > current[i] ? .5f : .14f);
        }
        historyHead = (historyHead + 1) % HISTORY;
        System.arraycopy(current, 0, history[historyHead], 0, POINTS);

        float total = 0f, low = 0f;
        for (int i = 0; i < POINTS; i++) {
            total += current[i];
            if (i < 32) low += current[i];
        }
        energy += (Math.min(1f, total / POINTS * 3f) - energy) * .25f;
        bass += (Math.min(1f, low / 32f * 2.2f) - bass) * .3f;
        if (total > previousEnergy + 1.0f && total > 1.4f) onBeat(total - previousEnergy);
        previousEnergy = total;
    }

    private void initializeFrequencies() {
        float min = (float) Math.log(FREQ_MIN);
        float max = (float) Math.log(FREQ_MAX);
        for (int i = 0; i < POINTS; i++) {
            float t = i / (float) (POINTS - 1);
            pointFreq[i] = (float) Math.exp(min + t * (max - min));
        }
        freqReady = true;
    }

    /** 鼓点：从远方推来一道大涌，同时浪尖闪亮。 */
    public void onBeat(float strength) {
        kick = Math.min(1.2f, kick + .45f + Math.max(0f, strength) * .3f);
        // 复用最弱的一道涌
        int slot = 0;
        for (int i = 1; i < FRONTS; i++) if (frontAmp[i] < frontAmp[slot]) slot = i;
        frontZ[slot] = Z_FAR * .42f;
        frontAmp[slot] = Math.min(.55f, .22f + .18f * Math.max(0f, strength));
    }

    /** 测试用：固定每帧时长，使离线渲染可复现。 */
    void setFrameStepForTest(float ms) { testStepMs = ms; }

    // ================= 海面 =================

    /** 海面高度：涌浪叠加（尖波峰、宽波谷）+ 鼓点大涌。 */
    private float seaHeight(float x, float z, float sharp) {
        float h = 0f;
        for (int i = 0; i < WAVES; i++) {
            float ph = W_K[i] * (wDx[i] * x + wDz[i] * z) + wOmega[i] * time + W_SEED[i];
            float c = .5f + .5f * (float) Math.sin(ph);
            // sharp=0 为正弦；越大波峰越尖、波谷越宽（类摆线浪），并扣掉均值保持海平面
            float shaped = c + sharp * (c * c - c);
            h += wAmp[i] * 2f * (shaped - (.5f - sharp * .125f));
        }
        for (int i = 0; i < FRONTS; i++) {
            if (frontAmp[i] < .005f) continue;
            float d = (z - frontZ[i]) / 1.7f;
            if (d > 3f || d < -3f) continue;
            // 前坡陡、后坡缓：涌向镜头的一侧更陡
            float shape = d < 0f ? (float) Math.exp(-d * d * 2.2f) : (float) Math.exp(-d * d * .7f);
            h += frontAmp[i] * shape * (.75f + .25f * (float) Math.sin(x * .7f + i * 2f));
        }
        return h;
    }

    // ================= 绘制 =================

    @Override public void draw(Canvas canvas) {
        if (width <= 0 || height <= 0 || !freqReady) return;
        float dtMs;
        if (testStepMs > 0f) {
            dtMs = testStepMs;
        } else {
            long now = System.nanoTime() / 1_000_000L;
            dtMs = lastFrameMs < 0 ? 16f : Math.min(50f, now - lastFrameMs);
            lastFrameMs = now;
        }
        float dt = dtMs / 1000f;
        time += dt * (.75f + .45f * energy);
        kick *= (float) Math.pow(.9f, Math.max(.5f, dtMs / 16f));
        for (int i = 0; i < FRONTS; i++) {
            frontZ[i] -= dt * 6.5f;
            frontAmp[i] *= (float) Math.pow(.985f, Math.max(.5f, dtMs / 16f));
            if (frontZ[i] < Z_NEAR * .4f) frontAmp[i] = 0f;
        }
        if (++sparkleTick > 1_000_000) sparkleTick = 0;
        ensureShaders();

        // 音乐 → 浪：低频抬长涌，响度让风浪变碎变尖；静音时是一片平缓的涌
        float swell = .55f + 1.05f * bass + .35f * energy;
        float chop = .45f + 1.35f * energy + .4f * Math.min(1f, kick);
        for (int i = 0; i < WAVES; i++) wAmp[i] = W_A[i] * (i < 2 ? swell : chop);
        float sharp = Math.min(.92f, .35f + .55f * energy);
        float ampTotal = 0f;
        for (float a : wAmp) ampTotal += a;

        // 镜头像在船上：高度与侧倾跟随脚下海面
        float zc = Z_NEAR * .8f;
        float seaHere = seaHeight(0f, zc, sharp);
        float targetH = Math.max(1.15f + .5f * seaHere, seaHere + .55f);
        camH += (targetH - camH) * Math.min(1f, dt * 2.2f);
        float tilt = (seaHeight(.7f, zc, sharp) - seaHeight(-.7f, zc, sharp)) / 1.4f;
        camRoll += (tilt * 9f - camRoll) * Math.min(1f, dt * 1.8f);

        float cx = width * .5f;
        float f = height * 1.05f;
        float horizon = height * (.36f + .025f * (float) Math.sin(time * .37f))
                - (camH - 1.2f) * height * .05f;

        canvas.save();
        canvas.rotate(camRoll, cx, height * .5f);
        canvas.scale(1.08f, 1.08f, cx, height * .5f);

        float moonX = cx + width * .17f;
        drawSky(canvas, horizon, moonX);
        drawSea(canvas, cx, horizon, f, sharp, ampTotal, moonX);
        drawMoonPath(canvas, horizon, moonX);

        canvas.restore();
        drawEdgeFeather(canvas);
    }

    private void drawSky(Canvas canvas, float horizon, float moonX) {
        canvas.save();
        canvas.translate(0f, horizon - height * .36f);
        skyPaint.setShader(skyShader);
        canvas.drawRect(-width * .1f, -height * .3f, width * 1.1f, height * 1.2f, skyPaint);
        canvas.restore();

        // 星空：只在海平面以上
        int n = 0;
        for (int i = 0; i < STARS; i++) {
            starBuf[n++] = starXY[i * 2] * width * 1.1f - width * .05f;
            starBuf[n++] = starXY[i * 2 + 1] * (horizon - height * .04f);
        }
        dotPaint.setColor(Color.WHITE);
        dotPaint.setStrokeWidth(Math.max(1f, height / 280f));
        int half = STARS / 2;
        for (int pass = 0; pass < 2; pass++) {
            float tw = .5f + .5f * (float) Math.sin(time * (1.1f + pass * .6f) + pass * 2f);
            dotPaint.setAlpha((int) (35 + 80 * tw));
            canvas.drawPoints(starBuf, pass * half * 2, half * 2, dotPaint);
        }

        // 月亮与光晕
        float my = horizon - height * .2f;
        canvas.save();
        canvas.translate(moonX, my);
        glowPaint.setShader(moonShader);
        glowPaint.setAlpha((int) (170 + 60 * energy + 25 * Math.min(1f, kick)));
        canvas.drawCircle(0f, 0f, height * .42f, glowPaint);
        canvas.restore();
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(0xffeef3ff);
        dotPaint.setAlpha(240);
        canvas.drawCircle(moonX, my, height * .035f, dotPaint);
        dotPaint.setStyle(Paint.Style.STROKE);
    }

    private void drawSea(Canvas canvas, float cx, float horizon, float f,
                         float sharp, float ampTotal, float moonX) {
        float halfW = width * .5f * 1.2f;
        float bottom = height * 1.25f;
        float stroke = Math.max(1f, height / 320f);
        float foamThresh = ampTotal * .55f;
        float logSpan = (float) Math.log(Z_FAR / Z_NEAR);

        for (int r = ROWS - 1; r >= 0; r--) {
            float t = r / (float) (ROWS - 1);                       // 0 近, 1 远
            float z = Z_NEAR * (float) Math.exp(logSpan * t);        // 几何级数行距
            float near = 1f - t;
            // 浪从远处涌来：远处读最新的频谱，近处读更早的
            float[] spec = history[(historyHead - (int) (near * (HISTORY - 1)) + HISTORY) % HISTORY];
            float spanX = halfW / f * z;
            fillPath.reset();
            crestPath.reset();
            for (int c = 0; c < COLS; c++) {
                float u = c / (float) (COLS - 1) * 2f - 1f;
                float xw = u * spanX;
                float s = smoothSpec(spec, (int) (Math.abs(u) * (POINTS - 1) * .9f));
                float roll = .5f + .5f * (float) Math.sin(z * .9f - time * 1.6f + u * 2f);
                float h = seaHeight(xw, z, sharp) + s * .32f * roll;
                float sx = cx + u * halfW;
                float sy = horizon - (h - camH) / z * f;
                if (c == 0) {
                    fillPath.moveTo(sx, bottom);
                    fillPath.lineTo(sx, sy);
                    crestPath.moveTo(sx, sy);
                } else {
                    fillPath.lineTo(sx, sy);
                    crestPath.lineTo(sx, sy);
                }
                rowH[c] = h;
                rowX[c] = sx;
                rowY[c] = sy;
            }
            fillPath.lineTo(cx + halfW, bottom);
            fillPath.close();

            // 海水：近处深暗，远处反射天光并被海雾吞没（菲涅尔 + 大气透视）
            float fog = (float) Math.pow(t, 1.6f);
            fillPaint.setColor(mix(0xff03070f, 0xff26284c, fog));
            canvas.drawPath(fillPath, fillPaint);

            // 浪脊：近处银青、远处融进雾里；鼓点时闪亮
            int crest = mix(0xffcfe9ff, 0xff8f88c8, t);
            float bright = Math.min(1f, (.18f + .82f * near) * (.45f + .55f * energy)
                    + .45f * Math.min(1f, kick) * near);
            if (near > .3f) {
                crestGlowPaint.setColor(crest);
                crestGlowPaint.setAlpha((int) (38 * bright * near));
                crestGlowPaint.setStrokeWidth(stroke * (3f + 6f * near));
                canvas.drawPath(crestPath, crestGlowPaint);
            }
            crestPaint.setColor(crest);
            crestPaint.setAlpha((int) (30 + 190 * bright * (.35f + .65f * near)));
            crestPaint.setStrokeWidth(stroke * (.5f + 1.5f * near));
            canvas.drawPath(crestPath, crestPaint);

            // 浪尖白沫：高处撒一簇抖动粒子，随时间闪烁
            int fn = 0;
            float spread = stroke * (1.5f + 9f * near);
            int tick = sparkleTick / 3;
            for (int c = 1; c < COLS - 1 && fn + 1 < foam.length; c++) {
                float h = rowH[c];
                if (h < foamThresh) continue;
                float strength = Math.min(1f, (h - foamThresh) / (ampTotal * .4f + 1e-3f));
                int count = 1 + (int) (strength * 3f);
                for (int k = 0; k < count && fn + 1 < foam.length; k++) {
                    float jx = hash2(c * 7 + k, r * 13 + tick) - .5f;
                    float jy = hash2(c * 11 + k, r * 5 + tick + 3);
                    foam[fn++] = rowX[c] + jx * spread * 2f;
                    foam[fn++] = rowY[c] + jy * spread * .6f;
                }
            }
            if (fn > 0) {
                dotPaint.setColor(0xfff2f8ff);
                dotPaint.setAlpha((int) (60 + 170 * bright));
                dotPaint.setStrokeWidth(stroke * (.9f + 2.2f * near));
                canvas.drawPoints(foam, 0, fn, dotPaint);
            }

            // 月光碎影：月亮正下方的海面上，浪的受光面闪烁
            int gn = 0;
            float bandW = width * (.025f + .11f * near);
            for (int c = 1; c < COLS - 1; c++) {
                float dx = (rowX[c] - moonX) / bandW;
                float g = (float) Math.exp(-dx * dx);
                if (g < .08f) continue;
                float slope = rowH[c + 1] - rowH[c - 1];
                // 背向镜头的坡面（朝月亮）更容易反光
                float facing = slope > 0f ? .9f : .35f;
                if (hash2(c * 31 + r, tick * 7 + r) < g * facing * .7f) {
                    glitter[gn++] = rowX[c];
                    glitter[gn++] = rowY[c] + stroke;
                }
            }
            if (gn > 0) {
                dotPaint.setColor(0xfffff6e6);
                dotPaint.setAlpha((int) (110 + 140 * (.4f + .6f * near)));
                dotPaint.setStrokeWidth(stroke * (1f + 2.4f * near));
                canvas.drawPoints(glitter, 0, gn, dotPaint);
            }
        }

        // 海天交界的薄雾
        canvas.save();
        canvas.translate(0f, horizon);
        edgePaint.setShader(mistShader);
        canvas.drawRect(-width * .1f, -height * .08f, width * 1.1f, height * .08f, edgePaint);
        canvas.restore();
    }

    /** 月光在海面上的柔和光柱（加色叠加，只落在海平面以下）。 */
    private void drawMoonPath(Canvas canvas, float horizon, float moonX) {
        canvas.save();
        canvas.clipRect(-width * .1f, horizon, width * 1.1f, height * 1.3f);
        canvas.translate(moonX, horizon);
        canvas.scale(.16f, 1f);
        addPaint.setShader(reflectShader);
        addPaint.setAlpha((int) (90 + 70 * energy));
        canvas.drawCircle(0f, 0f, height * 1.1f, addPaint);
        canvas.restore();
    }

    /** 四边羽化：从页面背景色渐变到透明，场景与播放页无缝衔接。 */
    private void drawEdgeFeather(Canvas canvas) {
        float fy = height * .10f, fx = width * .05f;
        edgePaint.setShader(edgeTop);
        canvas.drawRect(0, 0, width, fy, edgePaint);
        edgePaint.setShader(edgeBottom);
        canvas.drawRect(0, height - fy, width, height, edgePaint);
        edgePaint.setShader(edgeLeft);
        canvas.drawRect(0, 0, fx, height, edgePaint);
        edgePaint.setShader(edgeRight);
        canvas.drawRect(width - fx, 0, width, height, edgePaint);
    }

    // ================= 工具 =================

    private void ensureShaders() {
        if (shaderW == width && shaderH == height) return;
        // 天空以默认地平线（.36h）为参照，绘制时整体平移
        skyShader = new LinearGradient(0, -height * .3f, 0, height * .36f,
                new int[]{0xff02040a, 0xff0a1128, 0xff1c1f45, 0xff3a3366},
                new float[]{0f, .45f, .8f, 1f}, Shader.TileMode.CLAMP);
        moonShader = new RadialGradient(0f, 0f, height * .42f,
                new int[]{0x88dfe8ff, 0x337f86d8, 0x0f4a4a9a, 0x00000000},
                new float[]{0f, .18f, .5f, 1f}, Shader.TileMode.CLAMP);
        reflectShader = new RadialGradient(0f, 0f, height * 1.1f,
                new int[]{0x55dfe6ff, 0x2a8a90d8, 0x00000000},
                new float[]{0f, .35f, 1f}, Shader.TileMode.CLAMP);
        mistShader = new LinearGradient(0, -height * .08f, 0, height * .08f,
                new int[]{0x00453c78, 0x664a4280, 0x00453c78},
                new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP);
        float fy = height * .10f, fx = width * .05f;
        int clear = PAGE_BG & 0x00ffffff;
        edgeTop = new LinearGradient(0, 0, 0, fy, PAGE_BG, clear, Shader.TileMode.CLAMP);
        edgeBottom = new LinearGradient(0, height, 0, height - fy, PAGE_BG, clear, Shader.TileMode.CLAMP);
        edgeLeft = new LinearGradient(0, 0, fx, 0, PAGE_BG, clear, Shader.TileMode.CLAMP);
        edgeRight = new LinearGradient(width, 0, width - fx, 0, PAGE_BG, clear, Shader.TileMode.CLAMP);
        shaderW = width;
        shaderH = height;
    }

    /** 频谱取样：相邻 5 点平滑，避免海面出现锯齿。 */
    private static float smoothSpec(float[] spec, int idx) {
        float sum = 0f;
        for (int k = -2; k <= 2; k++) {
            int j = Math.max(0, Math.min(POINTS - 1, idx + k));
            sum += spec[j];
        }
        return sum / 5f;
    }

    private static float hash2(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0x7fffffff) / (float) 0x7fffffff;
    }

    private static float hash1(float n) {
        float s = (float) Math.sin(n) * 43758.5453f;
        return s - (float) Math.floor(s);
    }

    private static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return 0xff000000
                | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    @Override public void reset() {
        java.util.Arrays.fill(current, 0f);
        for (float[] row : history) java.util.Arrays.fill(row, 0f);
        java.util.Arrays.fill(frontAmp, 0f);
        historyHead = 0;
        energy = 0f;
        bass = 0f;
        kick = 0f;
        previousEnergy = 0f;
        lastFrameMs = -1;
    }

    @Override public boolean isAtRest() {
        if (energy > REST || kick > .01f) return false;
        for (float m : current) if (m > REST) return false;
        return true;
    }
}
