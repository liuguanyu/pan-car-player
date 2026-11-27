package com.baidu.carplayer.visualizer.fireworks;

/**
 * 香江烟花秀的纯 Java 场景数据：天际线几何。
 *
 * <p>坐标与其它 GL 可视化一致：x∈[0,1]，y∈[0,aspect] 且 y 向下，aspect = 视图高/宽。
 * 楼群立在 {@link #horizonY()} 上，向上生长；水面在其下方。
 *
 * <p>不依赖 Android，可在 JVM 上单测：同一 seed 生成完全一致的天际线。
 */
public final class FireworksScene {
    public static final int MAX_BUILDINGS = 120;
    /** 地平线位置（相对 aspect）：上方是天空，下方是香江水面。 */
    public static final float HORIZON_RATIO = .58f;

    /** 轮廓样式：由片元着色器按样式把矩形裁成对应剪影。 */
    public static final int STYLE_PLAIN = 0, STYLE_SETBACK = 1, STYLE_AFRAME = 2, STYLE_ARCH = 3;

    /** 层：远 → 近，越近越暗、窗格越亮、视差越大。 */
    public static final int LAYER_FAR = 0, LAYER_MID = 1, LAYER_NEAR = 2;

    public static final class Building {
        public float x, halfW;
        /** 相对地平线高度的比例（0..1），与视图比例无关。 */
        public float heightRatio;
        /** 实际高度（场景单位）= heightRatio × 地平线高度，由 {@link #applyHorizonScale()} 重算。 */
        public float height;
        public int layer, style;
        public float seed;
    }

    private final Building[] buildings = new Building[MAX_BUILDINGS];
    private int count;
    private float aspect = .272f;
    private long seed = 1L;

    public FireworksScene() {
        for (int i = 0; i < MAX_BUILDINGS; i++) buildings[i] = new Building();
        generate(1L);
    }

    public Building[] buildings() { return buildings; }
    public int count() { return count; }
    public float aspect() { return aspect; }
    public float horizonY() { return aspect * HORIZON_RATIO; }

    /** 比例变化只影响地平线与楼高，天际线形状（横向布局与相对高度）不变。 */
    public void setAspect(float value) {
        float a = Math.max(.12f, Math.min(2f, value));
        if (Math.abs(a - aspect) < 1e-4f) return;
        aspect = a;
        applyHorizonScale();
    }

    public void reset(long seed) { generate(seed); }

    public void generate(long newSeed) {
        seed = newSeed;
        count = 0;
        long state = newSeed * 6364136223846793005L + 1442695040888963407L;
        // 远层最多、最矮；近层最少、最高。三层叠出纵深。
        addLayer(LAYER_FAR, 34, .30f, .60f, .007f, .016f, state);
        addLayer(LAYER_MID, 20, .45f, .78f, .010f, .024f, state ^ 0x9E3779B97F4A7C15L);
        addLayer(LAYER_NEAR, 12, .58f, .92f, .016f, .034f, state ^ 0x2545F4914F6CDD1DL);
        addLandmarks();
        applyHorizonScale();
    }

    /** 楼高按地平线高度归一化：高度比例是主数据，实际尺寸随比例重算。 */
    private void applyHorizonScale() {
        float horizon = horizonY();
        for (int i = 0; i < count; i++) {
            buildings[i].height = buildings[i].heightRatio * horizon;
        }
    }

    private void addLayer(int layer, int wanted, float minH, float maxH,
                          float minW, float maxW, long initState) {
        long state = initState;
        int placed = 0;
        for (int attempt = 0; attempt < wanted * 3 && placed < wanted && count < MAX_BUILDINGS; attempt++) {
            state = next(state);
            float rnd = unit(state);
            float halfW = minW + rnd * (maxW - minW);
            state = next(state);
            float x = .02f + unit(state) * .96f;
            // 相邻楼太挤就跳过，保持天际线起伏而不是糊成墙。
            if (tooClose(x, halfW, layer)) continue;
            state = next(state);
            float height = minH + unit(state) * (maxH - minH);
            state = next(state);
            int style = unit(state) < .12f ? STYLE_SETBACK : STYLE_PLAIN;
            add(x, halfW, height, layer, style, unit(next(state)));
            placed++;
        }
    }

    /** 几座有辨识度的轮廓：中环收分塔、三角桁架塔、超高塔、会展弧形屋顶。 */
    private void addLandmarks() {
        add(.30f, .028f, .96f, LAYER_NEAR, STYLE_SETBACK, .21f);   // IFC 式收分塔
        add(.44f, .024f, .84f, LAYER_NEAR, STYLE_AFRAME, .47f);    // 三角桁架
        add(.62f, .022f, .99f, LAYER_NEAR, STYLE_SETBACK, .63f);   // 超高塔
        add(.78f, .062f, .44f, LAYER_NEAR, STYLE_ARCH, .85f);      // 弧形屋顶
    }

    private void add(float x, float halfW, float heightRatio, int layer, int style, float seedValue) {
        if (count >= MAX_BUILDINGS) return;
        Building b = buildings[count++];
        b.x = x;
        b.halfW = halfW;
        b.heightRatio = Math.min(1f, Math.max(.05f, heightRatio));
        b.height = b.heightRatio * horizonY();
        b.layer = layer;
        b.style = style;
        b.seed = seedValue;
    }

    private boolean tooClose(float x, float halfW, int layer) {
        for (int i = 0; i < count; i++) {
            Building b = buildings[i];
            if (b.layer != layer) continue;
            if (Math.abs(b.x - x) < (b.halfW + halfW) * 1.05f) return true;
        }
        return false;
    }

    private static long next(long state) {
        return state * 6364136223846793005L + 1442695040888963407L;
    }

    private static float unit(long state) {
        return ((state >>> 11) & 0x1FFFFFFFL) / (float) 0x1FFFFFFFL;
    }
}
