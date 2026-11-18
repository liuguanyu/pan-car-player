package com.baidu.carplayer.visualizer;

import java.util.Random;

/**
 * "光轴环群 + 彗星" 的场景状态（与渲染无关的纯 Java，便于单元测试）。
 *
 * <p>场景结构（参考 YouTube CQtOFtSBA3I）：一根沿世界 X 轴的粗白光轴，彗星沿光轴从 +X 端飞向 -X 端；
 * 彗星每隔一小段（节拍 + 响度驱动）在身后撒下一圈粒子环。环生成时很小，随时间向外扩张、变暗、消失，
 * 因此彗星身后自然形成"近头小、远端大"的锥形尾迹。彗星下一轮飞行时会重新穿过上一轮留下的大环，
 * 穿过的瞬间该环被点亮并向外一弹。
 *
 * <p>环的轮廓是极坐标下的星形闭合曲线 r(θ)=R·f(θ)，f=1+Σ A_h·cos(hθ+φ_h)。
 * 只要 f&gt;0 曲线就一定封闭且不自交：出生时频谱快照的幅度总和 ≤ {@link #SNAP_CAP}，
 * 实时频谱附加幅度总和 ≤ {@link #LIVE_CAP}，着色器里再把 f 钳制在 0.4 以上。
 *
 * <p>相机绕光轴整圈环绕（{@link #ORBIT_PERIOD} 秒一圈）：转到彗星飞来的一侧会迎面看到它冲来，
 * 转到另一侧会看到它远去；侧面时彗星的横向运动方向会在"右→左"与"左→右"之间翻转。
 * 另有缓慢俯仰、轻微侧倾（dutch angle）与推拉。
 *
 * <p>本类不碰 GL：每帧 {@link #step} 之后，渲染器读取公开的矩阵与 uniform 数组即可。
 */
public final class ParticleScene {
    public static final int MAX_RINGS = 64;
    /** 相机绕光轴环绕一整圈的时间（秒）。 */
    public static final float ORBIT_PERIOD = 80f;
    /** 光轴几何允许的最小相机深度。 */
    public static final float MIN_DEPTH = 0.9f;
    /** 轮廓谐波阶数 2..8 共 7 阶。 */
    public static final int HARM = 7;
    public static final int BANDS = 24;
    /** 环寿命（秒）。 */
    public static final float LIFE = 16f;
    /** 出生快照的轮廓幅度总和上限。 */
    public static final float SNAP_CAP = 0.28f;
    /** 实时频谱附加轮廓幅度总和上限。 */
    public static final float LIVE_CAP = 0.14f;

    // ---------- 渲染器读取的输出 ----------
    /** 列主序 4x4：投影 × 视图。 */
    public final float[] vp = new float[16];
    /** 每环 (birthX, age, r0, seed)；age&lt;0 表示未使用。 */
    public final float[] ringA = new float[MAX_RINGS * 4];
    /** 每环出生快照：h2..h5。 */
    public final float[] ringH0 = new float[MAX_RINGS * 4];
    /** 每环出生快照：h6..h8 + 出生响度。 */
    public final float[] ringH1 = new float[MAX_RINGS * 4];
    /** 实时频谱谐波：h2..h5 / h6..h8 + 0。 */
    public final float[] live0 = new float[4];
    public final float[] live1 = new float[4];
    /** 光轴两端（世界坐标，已避开相机近平面）。 */
    public final float[] axisA = new float[3];
    public final float[] axisB = new float[3];
    public final float[] headPos = new float[3];
    /** 相机世界坐标。 */
    public final float[] camPos = new float[3];

    public float time;
    public float pulse;
    public float level;
    public float energy;
    /** 彗头 X 坐标 / 可见度 / 彗尾长度 / 对焦距离。 */
    public float headX, headVis, tailLen, focus;
    /** 当前光轴与视线夹角、公转角、画面倾斜（调试/测试用）。 */
    public float gamma, orbit, axisAngle;
    /** 轨迹曲率参数（由 GLSL 与 CPU 使用同一套路径函数）。 */
    public float curveAmp = 0.72f;
    public float curvePhase;

    // ---------- 内部状态 ----------
    private final Random random = new Random(7);
    private final AudioAnalyzer analyzer = new AudioAnalyzer();
    private final float[] spectrum = new float[BANDS];
    private final float[] ringBirth = new float[MAX_RINGS];
    private final float[][] ringSnap = new float[MAX_RINGS][HARM];
    private final float[] ringR0 = new float[MAX_RINGS];
    private final float[] ringSeed = new float[MAX_RINGS];
    private final float[] ringX = new float[MAX_RINGS];
    private final float[] ringLoud = new float[MAX_RINGS];
    private final float[] harmTmp = new float[HARM];
    private final float[] view = new float[16];
    private final float[] proj = new float[16];
    private float bass, mid, volume;
    private float motion;
    private float spawnAcc;
    private float camTime;
    private float orbitTime;   // 环绕专用时钟：匀速，不随音乐加速，保证"缓慢旋转"
    private float fwdX = -1f, fwdY, fwdZ;
    private float targetX;
    private float targetY;
    private float targetZ;
    private float cometP = -0.12f;   // <0 表示起飞前停顿
    private float axisHalf = 4f;
    private boolean beatPending;

    public ParticleScene() {
        reset();
    }

    public void reset() {
        bass = mid = volume = level = 0f;
        motion = 0f;
        energy = 0f;
        pulse = 0f;
        time = 0f;
        camTime = 0f;
        orbitTime = 0f;
        curvePhase = 0f;
        targetX = 0f;
        targetY = 0f;
        targetZ = 0f;
        spawnAcc = 0f;
        cometP = -0.12f;
        headVis = 0f;
        headX = 0f;
        beatPending = false;
        analyzer.reset();
        java.util.Arrays.fill(spectrum, 0f);
        java.util.Arrays.fill(live0, 0f);
        java.util.Arrays.fill(live1, 0f);
        clearRings();
        // 初始化一次相机，保证首帧矩阵有效
        updateCamera(1.78f);
    }

    private void clearRings() {
        for (int i = 0; i < MAX_RINGS; i++) {
            ringBirth[i] = Float.NEGATIVE_INFINITY;
            ringA[i * 4 + 1] = -1f;
        }
    }

    /** 停止播放后是否已回到完全不可见的静止态。 */
    public boolean isAtRest() {
        return energy < 0.004f && pulse < 0.01f && bass < 0.004f && mid < 0.004f;
    }

    /** 当前存活的环数量（测试用）。 */
    public int activeRings() {
        int n = 0;
        for (int i = 0; i < MAX_RINGS; i++) if (ringA[i * 4 + 1] >= 0f) n++;
        return n;
    }

    /**
     * 推进一帧。
     *
     * @param fft         Android Visualizer 的 FFT 字节；空数组表示无信号
     * @param dt          距上一帧的秒数
     * @param periodMs    估计的节拍周期（毫秒）
     * @param aspect      视口宽高比
     */
    public void step(byte[] fft, float dt, float periodMs, float aspect) {
        dt = Math.max(0.001f, Math.min(0.05f, dt));
        time += dt;
        if (aspect <= 0f) aspect = 1.78f;
        // 光轴半长随视口宽高比增长：宽屏下光轴铺得更满
        axisHalf = 1.6f + 0.8f * Math.min(3.8f, aspect);
        tailLen = 0.45f * axisHalf;
        analyzeAudio(fft, periodMs);

        // 整体可见度：有信号时常亮，无信号衰减到 0
        boolean hasSignal = bass > 0.001f || mid > 0.001f;
        energy += ((hasSignal ? 1f : 0f) - energy) * (hasSignal ? 0.3f : 0.05f);
        pulse *= (float) Math.pow(0.93f, dt * 60f);

        if (energy < 0.004f && pulse < 0.01f && !hasSignal) {
            // 静止：清空环，避免恢复播放时看到陈旧画面
            if (activeRings() > 0) clearRings();
            headVis = 0f;
            cometP = -0.12f;
            updateCamera(aspect);
            return;
        }

        camTime += dt * (0.8f + 0.5f * Math.min(1.5f, motion));
        orbitTime += dt;
        curvePhase = time * 0.16f;

        advanceComet(dt);
        spawnRings(dt);
        ageRings(dt);
        updateCamera(aspect);
    }

    // ---------- 音频 ----------

    private void analyzeAudio(byte[] fft, float periodMs) {
        AudioAnalyzer.Analysis a = analyzer.analyze(fft);
        boolean hasData = fft.length > 0;
        bass += ((hasData ? a.bass : 0f) - bass) * (a.bass > bass ? .45f : .18f);
        mid += ((hasData ? a.mid : 0f) - mid) * (a.mid > mid ? .45f : .18f);
        volume += ((hasData ? a.volume : 0f) - volume) * (a.volume > volume ? .4f : .12f);
        float lvTarget = Math.max(bass * 0.95f, Math.max(mid * 0.85f, volume));
        level += (lvTarget - level) * (lvTarget > level ? .5f : .1f);

        float[] raw = AudioAnalyzer.fftToMagnitudes(fft, BANDS * 2);
        for (int i = 0; i < BANDS; i++) {
            float x = i * 2 < raw.length ? raw[i * 2] : 0f;
            float y = i * 2 + 1 < raw.length ? raw[i * 2 + 1] : 0f;
            float target = (float) Math.sqrt((x + y) * 0.5f);
            spectrum[i] += (target - spectrum[i]) * (target > spectrum[i] ? .55f : .14f);
        }
        harmonics(spectrum, harmTmp, LIVE_CAP, 0.35f);
        for (int h = 0; h < 4; h++) live0[h] = harmTmp[h];
        live1[0] = harmTmp[4]; live1[1] = harmTmp[5]; live1[2] = harmTmp[6]; live1[3] = 0f;

        float bpm = 60000f / Math.max(300f, Math.min(1000f, periodMs));
        float tempoNorm = Math.max(0f, Math.min(1f, (bpm - 60f) / 140f));
        motion = (0.35f + volume * 1.5f) * (0.55f + tempoNorm * 0.9f);

        boolean beat = hasData && (a.peak || (bass > .45f && bass - a.bass < 0 && mid > .12f));
        if (beat) {
            pulse = Math.min(1.4f, pulse + .55f + bass * .5f);
            beatPending = true;
        }
    }

    /**
     * 24 段频谱 → 7 阶轮廓谐波幅度：低频对应低阶（大起伏），高频对应高阶（细起伏）。
     * 幅度总和被缩放到不超过 cap，并保证每阶不低于 floor 的一小部分，避免环变成正圆。
     */
    static void harmonics(float[] bands, float[] out, float cap, float floorFrac) {
        float sum = 0f;
        for (int h = 0; h < HARM; h++) {
            float v = (bands[h * 3] + bands[h * 3 + 1] + bands[h * 3 + 2]) / 3f;
            float a = Math.max(0.05f, v) / (1f + 0.18f * h);
            out[h] = a;
            sum += a;
        }
        float k = cap / Math.max(1e-4f, sum);
        // 总量随响度变化：安静时轮廓接近正圆，响时才达到上限
        float vol = 0f;
        for (int i = 0; i < BANDS; i++) vol += bands[i];
        vol = Math.min(1f, vol / BANDS * 2.2f);
        float gain = floorFrac + (1f - floorFrac) * vol;
        for (int h = 0; h < HARM; h++) out[h] *= k * gain;
    }

    // ---------- 彗星 ----------

    private void advanceComet(float dt) {
        float duration = Math.max(7f, 12f - 3.5f * Math.min(1.2f, motion));
        cometP += dt / duration;
        if (cometP >= 1.06f) cometP = -0.1f - random.nextFloat() * 0.08f;
        float p = Math.max(0f, Math.min(1f, cometP));
        // 从 +X 飞向 -X
        headX = (1f - 2f * p) * axisHalf;
        float in = smooth(0f, 0.07f, cometP);
        float out = 1f - smooth(0.93f, 1.0f, cometP);
        // 彗头在相机背后/过近时淡出，避免穿过近平面
        float seen = smooth(0.9f, 2.6f, depthOf(headX));
        headVis = cometP < 0f ? 0f : energy * in * out * seen;
        headPos[0] = headX;
        headPos[1] = pathY(headX);
        headPos[2] = pathZ(headX);
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    // ---------- 环 ----------

    private void spawnRings(float dt) {
        if (headVis < 0.08f) {
            beatPending = false;
            return;
        }
        // 参考视频的尾迹是连续的密集环，不让曲线段之间出现明显断层。
        spawnAcc += dt * (3.0f + 3.2f * level + 1.0f * Math.min(1.5f, motion));
        if (beatPending) {
            spawnAcc += 1.5f;
            beatPending = false;
        }
        int guard = 4;
        while (spawnAcc >= 1f && guard-- > 0) {
            if (!spawnOne()) {
                spawnAcc = Math.min(spawnAcc, 1f);
                break;
            }
            spawnAcc -= 1f;
        }
    }

    private boolean spawnOne() {
        // 选空槽；没有就顶替最老的，但最老的还年轻时宁可不生（避免突然消失）
        int slot = -1;
        float oldest = Float.POSITIVE_INFINITY;
        for (int i = 0; i < MAX_RINGS; i++) {
            if (ringA[i * 4 + 1] < 0f) { slot = i; oldest = Float.NEGATIVE_INFINITY; break; }
            if (ringBirth[i] < oldest) { oldest = ringBirth[i]; slot = i; }
        }
        if (slot < 0) return false;
        if (oldest != Float.NEGATIVE_INFINITY && time - oldest < LIFE * 0.75f) return false;

        ringBirth[slot] = time;
        ringX[slot] = headX;
        ringSeed[slot] = random.nextFloat();
        ringLoud[slot] = Math.max(0.1f, Math.min(1.2f, level + pulse * 0.25f));
        ringR0[slot] = 0.2f + 0.5f * ringLoud[slot] + 0.1f * random.nextFloat();
        harmonics(spectrum, ringSnap[slot], SNAP_CAP, 0.45f);
        int o = slot * 4;
        ringA[o] = headX;
        ringA[o + 1] = 0f;
        ringA[o + 2] = ringR0[slot];
        ringA[o + 3] = ringSeed[slot];
        for (int h = 0; h < 4; h++) ringH0[o + h] = ringSnap[slot][h];
        ringH1[o] = ringSnap[slot][4];
        ringH1[o + 1] = ringSnap[slot][5];
        ringH1[o + 2] = ringSnap[slot][6];
        ringH1[o + 3] = ringLoud[slot];
        return true;
    }

    private void ageRings(float dt) {
        // 无信号时加速消散
        float rate = (bass > 0.001f || mid > 0.001f) ? 1f : 3f;
        for (int i = 0; i < MAX_RINGS; i++) {
            int o = i * 4;
            if (ringA[o + 1] < 0f) continue;
            float age = ringA[o + 1] + dt * rate;
            if (age >= LIFE) {
                ringA[o + 1] = -1f;
                ringBirth[i] = Float.NEGATIVE_INFINITY;
            } else {
                ringA[o + 1] = age;
                // 保持 birth 与 age 一致（rate≠1 时）
                ringBirth[i] = time - age;
            }
        }
    }

    // ---------- 相机 ----------

    /**
     * 相机绕光轴中点的竖直世界轴整圈环绕（{@link #ORBIT_PERIOD} 秒一圈）：
     * <ul>
     *   <li>方位角 az=0：相机在 +X 端，彗星从远处向镜头迎面冲来；</li>
     *   <li>az=π/2：正侧面，彗星自右向左飞；</li>
     *   <li>az=π：相机在 -X 端，从彗星身后看它远去，环呈同心圆；</li>
     *   <li>az=3π/2：另一侧，彗星自左向右飞。</li>
     * </ul>
     * 另有较慢的俯仰起伏、轻微侧倾（dutch angle）和推拉，彗头附近有缓慢追随。
     */
    private void updateCamera(float aspect) {
        if (aspect <= 0f) aspect = 1.78f;
        float half = axisHalf;
        float dolly = 5.0f * (1f + 0.06f * (float) Math.sin(camTime * 0.13f) - 0.04f * Math.min(1f, pulse));
        // 整圈方位角：从 0 开始匀速增长（不取模，方便测试断言“转满一圈”）
        orbit = orbitTime * ((float) (2.0 * Math.PI) / ORBIT_PERIOD);
        // 俯仰：略高于光轴平面，慢速起伏，保证环始终有立体感，不会变成纯直线
        float elev = 0.30f + 0.20f * (float) Math.sin(camTime * 0.071f + 0.8f);
        gamma = elev;
        float tilt = (float) Math.max(0.35, Math.min(1.0, 2.2 / aspect));
        axisAngle = (0.10f * (float) Math.sin(camTime * 0.057f + 0.5f)) * tilt;   // 侧倾

        // 正面/背面逐渐把彗头拉到画面中心；侧面恢复看整条轨迹。
        // smoothstep 避免进入/离开迎面视角时构图突变。
        float frontBack = Math.abs((float) Math.cos(orbit));
        float centerWeight = smooth(0.62f, 0.96f, frontBack);
        float focusX = headX * centerWeight;
        float focusY = pathY(headX) * centerWeight;
        float focusZ = pathZ(headX) * centerWeight;
        targetX += (focusX - targetX) * 0.035f;
        targetY += (focusY - targetY) * 0.035f;
        targetZ += (focusZ - targetZ) * 0.035f;
        float ce = (float) Math.cos(elev), se = (float) Math.sin(elev);
        float px = targetX + dolly * ce * (float) Math.cos(orbit);
        float py = targetY + dolly * se;
        float pz = targetZ + dolly * ce * (float) Math.sin(orbit);
        camPos[0] = px; camPos[1] = py; camPos[2] = pz;
        focus = dolly;

        // 前向：指向平滑注视点；正面/背面时注视彗头，确保头部居中
        float fx = targetX - px, fy = targetY - py, fz = targetZ - pz;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl; fy /= fl; fz /= fl;
        fwdX = fx; fwdY = fy; fwdZ = fz;
        // up：世界 +Y 在垂直于前向平面内的投影（镜头自己不翻滚，只有轻微侧倾）
        float d = fy;
        float ux = -d * fx, uy = 1f - d * fy, uz = -d * fz;
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;
        float rx = fy * uz - fz * uy, ry = fz * ux - fx * uz, rz = fx * uy - fy * ux;
        float cd = (float) Math.cos(axisAngle), sd = (float) Math.sin(axisAngle);
        float r2x = cd * rx + sd * ux, r2y = cd * ry + sd * uy, r2z = cd * rz + sd * uz;
        float u2x = -sd * rx + cd * ux, u2y = -sd * ry + cd * uy, u2z = -sd * rz + cd * uz;

        view[0] = r2x; view[4] = r2y; view[8] = r2z;  view[12] = -(r2x * px + r2y * py + r2z * pz);
        view[1] = u2x; view[5] = u2y; view[9] = u2z;  view[13] = -(u2x * px + u2y * py + u2z * pz);
        view[2] = -fx; view[6] = -fy; view[10] = -fz; view[14] = fx * px + fy * py + fz * pz;
        view[3] = 0f;  view[7] = 0f;  view[11] = 0f;  view[15] = 1f;
        perspective(proj, (float) Math.toRadians(52f), aspect, 0.1f, 80f);
        multiply(vp, proj, view);

        // 光轴端点：过近的一端沿轴向内收，避免穿过近平面
        float xA = clampToView(half), xB = clampToView(-half);
        axisA[0] = xA; axisA[1] = pathY(xA); axisA[2] = pathZ(xA);
        axisB[0] = xB; axisB[1] = pathY(xB); axisB[2] = pathZ(xB);
    }

    /** 路径中心线：一个主弯曲 + 一个较弱的空间扭摆，整体是平滑的抽象天体轨迹。 */
    public float pathY(float x) {
        return curveAmp * (float) Math.sin(x * 0.72f + curvePhase)
                + 0.16f * (float) Math.sin(x * 1.43f - curvePhase * 1.7f);
    }

    public float pathZ(float x) {
        return curveAmp * 0.44f * (float) Math.cos(x * 0.55f + curvePhase * 0.8f);
    }

    /** 光轴上 x 处的点到相机平面的距离（沿视线方向）。 */
    public float depthOf(float x) {
        return (x - camPos[0]) * fwdX - camPos[1] * fwdY - camPos[2] * fwdZ;
    }

    /** 把光轴上的 x 向远离相机的方向收到深度 ≥ {@link #MIN_DEPTH} 处。 */
    public float clampToView(float x) {
        float depth = depthOf(x);
        if (depth >= MIN_DEPTH || Math.abs(fwdX) < 1e-4f) return x;
        return x + (MIN_DEPTH - depth) / fwdX;
    }

    // ---------- 矩阵（列主序，与 GL 一致） ----------

    private void perspective(float[] m, float fovY, float aspect, float near, float far) {
        float f = 1f / (float) Math.tan(fovY / 2f);
        java.util.Arrays.fill(m, 0f);
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1f;
        m[14] = 2f * far * near / (near - far);
        projF = f;
    }

    /** 投影矩阵的 P[1][1]（1/tan(fov/2)），渲染器用来换算屏幕空间的光斑尺寸。 */
    public float focalY() { return projF; }
    private float projF = 1f;

    static void multiply(float[] out, float[] a, float[] b) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float s = 0f;
                for (int k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
                out[c * 4 + r] = s;
            }
        }
    }
}
