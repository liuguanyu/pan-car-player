package com.baidu.carplayer.visualizer.fireworks;

/**
 * 烟花的发射调度：把音频特征翻译成"什么时候、在哪里、放什么样的一发"。
 *
 * <p>纯 Java、无 Android 依赖，可直接单测。渲染层只读 {@link #data()}：
 * 每个烟花槽位占 {@link #FLOATS_PER_BURST} 个 float，位置/半径/起始时间与颜色/类型分开放，
 * 粒子位置全部在着色器里按公式算出，CPU 不维护逐粒子状态。
 *
 * <p>每个槽位同时记录升空和爆开的时间，渲染层按时间判断阶段。
 */
public final class FireworksShow {
    public static final int MAX_BURSTS = 12;
    public static final int FLOATS_PER_BURST = 12;
    public static final int MAX_PARTICLES = 240;
    /** 升空到爆开的时长。 */
    public static final float RISE_TIME = .85f;

    public static final int TYPE_PEONY = 0, TYPE_WILLOW = 1, TYPE_RING = 2,
            TYPE_PALM = 3, TYPE_STROBE = 4, TYPE_DOUBLE_RING = 5,
            TYPE_HEART = 6, TYPE_FAN = 7;

    /** 槽位内 float 布局。 */
    public static final int F_X = 0, F_Y = 1, F_RADIUS = 2, F_BURST_TIME = 3,
            F_R = 4, F_G = 5, F_B = 6, F_TYPE = 7,
            F_SEED = 8, F_PARTICLES = 9, F_DURATION = 10, F_SHELL_TIME = 11;

    /** 音频特征快照，由渲染线程每帧填充。 */
    public static final class Audio {
        public float bass, mid, treble, volume, flux, centroid;
        public double beatPosition = Double.NaN;
        public float beatConfidence, periodMs = 500f;
        public boolean peak;
    }

    private final float[] data = new float[MAX_BURSTS * FLOATS_PER_BURST];
    private int used;
    private int cursor;
    private float time;
    private long seed = 20260101L;
    private double lastBeat = Double.NaN;
    private float volleyCooldown;
    private float nextLaunch = .25f;
    private long launchCount;
    private float aspect = .272f;

    public float[] data() { return data; }
    public int used() { return used; }
    public int capacity() { return MAX_BURSTS; }
    public long launchCount() { return launchCount; }
    public float time() { return time; }

    public void setAspect(float value) {
        aspect = Math.max(.12f, Math.min(2f, value));
    }

    public float horizonY() { return aspect * FireworksScene.HORIZON_RATIO; }

    public void reset() {
        java.util.Arrays.fill(data, 0f);
        used = 0;
        cursor = 0;
        time = 0f;
        lastBeat = Double.NaN;
        volleyCooldown = 0f;
        nextLaunch = .25f;
        seed = 20260101L;
        launchCount = 0;
    }

    public void update(float dt, Audio a) {
        dt = Math.max(0f, Math.min(.05f, dt));
        time += dt;
        if (volleyCooldown > 0f) volleyCooldown = Math.max(0f, volleyCooldown - dt);
        if (a == null) return;

        boolean beat = a.beatConfidence > .40f && Double.isFinite(lastBeat)
                && Math.floor(a.beatPosition) > Math.floor(lastBeat);
        lastBeat = a.beatPosition;
        boolean audible = Math.max(a.volume, Math.max(a.bass, a.mid)) > .025f;
        if (time < nextLaunch) return;
        if (beat || a.flux > .20f || time >= nextLaunch + (audible ? .65f : 2f)
                || launchCount == 0) {
            boolean volley = audible && a.flux > .24f && volleyCooldown == 0f;
            launch(a, volley ? .50f : .13f + unit() * .74f);
            if (volley) {
                launch(a, .19f);
                launch(a, .81f);
                volleyCooldown = 1.35f;
            }
            nextLaunch = time + (audible ? .58f : 1.5f);
        }
    }

    /** 立即放一发；lateral 为横向偏好（0..1），用于齐射时散开。 */
    public void launch(Audio a, float lateral) {
        float energy = clamp01(Math.max(a.volume, Math.max(a.bass, a.mid) * .8f) * 2.2f);
        float radius = Math.min(.095f, aspect * (.205f + .052f * energy + .025f * unit()));
        float x = clamp(.12f + lateral * .76f + (unit() - .5f) * .18f,
                radius * 1.16f + .015f, .985f - radius * 1.16f);
        // 给外圈留出天顶余量，高能段在同一片天空里获得更大花冠。
        float apexY = Math.max(radius * .90f + aspect * .035f,
                aspect * (.263f + .025f * unit()));
        int type = chooseType(a);

        int particles = (int) (110f + 110f * energy);
        float duration = 1.5f + .9f * unit();
        float[] palette = a.centroid > .5f ? COOL : WARM;
        int colorIndex = (int) (unit() * 3f) * 3;
        float r = palette[colorIndex], g = palette[colorIndex + 1], b = palette[colorIndex + 2];

        float slotSeed = unit();
        write(x, apexY, radius, time + RISE_TIME, r, g, b, type,
                slotSeed, particles, duration, time);
        launchCount++;
    }

    private int chooseType(Audio audio) {
        float variation = unit();
        if (audio.treble > .12f && (audio.peak || audio.treble > audio.bass * 1.15f)) {
            return variation < .62f ? TYPE_STROBE : TYPE_DOUBLE_RING;
        }
        if (audio.bass > .14f && audio.bass > audio.mid * 1.1f) {
            if (audio.flux > .19f) return variation < .55f ? TYPE_PALM : TYPE_FAN;
            return variation < .72f ? TYPE_WILLOW : TYPE_PEONY;
        }
        if (audio.beatConfidence > .55f) {
            if (variation < .30f) return TYPE_RING;
            if (variation < .58f) return TYPE_DOUBLE_RING;
        }
        if (variation < .22f || variation > .82f) return TYPE_HEART;
        return TYPE_PEONY;
    }

    private static final float[] WARM = {
            1f, .62f, .22f, 1f, .86f, .34f, 1f, .34f, .30f,
    };
    private static final float[] COOL = {
            .58f, .86f, 1f, .76f, .64f, 1f, .42f, 1f, .86f,
    };

    private void write(float x, float y, float radius, float burstTime,
                       float r, float g, float b, int type,
                       float slotSeed, int particles, float duration, float shellTime) {
        int slot;
        if (used < MAX_BURSTS) {
            slot = used++;
        } else {
            // 满了就覆盖最旧的一发，天然形成环形缓冲，不分配新对象
            slot = cursor;
            cursor = (cursor + 1) % MAX_BURSTS;
        }
        int i = slot * FLOATS_PER_BURST;
        data[i + F_X] = x;
        data[i + F_Y] = y;
        data[i + F_RADIUS] = radius;
        data[i + F_BURST_TIME] = burstTime;
        data[i + F_R] = r;
        data[i + F_G] = g;
        data[i + F_B] = b;
        data[i + F_TYPE] = type;
        data[i + F_SEED] = slotSeed;
        data[i + F_PARTICLES] = particles;
        data[i + F_DURATION] = duration;
        data[i + F_SHELL_TIME] = shellTime;
    }

    private float unit() {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (seed >>> 40) * 0x1.0p-24f;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
