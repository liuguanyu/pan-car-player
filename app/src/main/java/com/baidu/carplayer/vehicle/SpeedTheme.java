package com.baidu.carplayer.vehicle;

import android.graphics.Color;

/**
 * 车速 → 配色。
 *
 * <p>标题栏的上下两端渐变、底部控制栏的压暗色都由车速插值得到，
 * 于是"越快越暖"：静止是靛蓝，巡航是青蓝，高速转琥珀，超速段是橙红。
 * 所有颜色都压在下限亮度上，保证白色文字的可读性、夜间不刺眼。
 */
public final class SpeedTheme {
    /** 配色节点：km/h → 颜色。节点之间线性插值。 */
    private static final float[] STOPS = {0f, 30f, 60f, 100f, 140f};
    private static final int[] HEADER_TOP = {
            0xff1a237e, // 靛蓝
            0xff12496e, // 深青蓝
            0xff1f5f52, // 墨绿
            0xff6b3d10, // 琥珀
            0xff6e1a1a  // 橙红
    };
    private static final int[] HEADER_BOTTOM = {
            0xff311b92, // 深紫
            0xff1d3f7a, // 靛青
            0xff2e6b45, // 松绿
            0xff8a4a12, // 暗金
            0xff8c2020  // 朱红
    };
    /** 底部控制栏的压暗色：比标题栏更暗，避免和歌词抢注意力。 */
    private static final int[] CONTROL_TINT = {
            0xff101a3a,
            0xff0d2b3f,
            0xff123328,
            0xff3a2408,
            0xff3a1010
    };

    private SpeedTheme() { }

    public static int headerTop(float kmh) { return sample(HEADER_TOP, kmh); }

    public static int headerBottom(float kmh) { return sample(HEADER_BOTTOM, kmh); }

    public static int controlTint(float kmh) { return sample(CONTROL_TINT, kmh); }

    /** 文案用的强调色：车速数字本身比背景亮一档，快速时更醒目。 */
    public static int accent(float kmh) {
        float t = speedFraction(kmh);
        int cold = 0xffb9d4ff, warm = 0xffffd0a8;
        return blend(cold, warm, t);
    }

    /**
     * 一次算出界面需要的三种颜色：标题栏渐变上/下、控制栏压暗色。
     *
     * @param kmh   车速（km/h）
     * @param valid 是否有有效定位；false 时整体压暗表示"车速未知"
     * @return {headerTop, headerBottom, controlTint}
     */
    public static int[] headerColors(float kmh, boolean valid) {
        float dim = valid ? 1f : .78f;
        return new int[]{
                dim(headerTop(kmh), dim),
                dim(headerBottom(kmh), dim),
                dim(controlTint(kmh), dim)
        };
    }

    /** 0..1 的速度比例，用于任何需要"越快越强"的地方。 */
    public static float speedFraction(float kmh) {
        float v = Math.max(0f, Math.min(140f, kmh));
        return v / 140f;
    }

    private static int sample(int[] palette, float kmh) {
        float v = Math.max(STOPS[0], Math.min(STOPS[STOPS.length - 1], kmh));
        for (int i = 0; i < STOPS.length - 1; i++) {
            if (v <= STOPS[i + 1]) {
                float t = (v - STOPS[i]) / (STOPS[i + 1] - STOPS[i]);
                return blend(palette[i], palette[i + 1], t);
            }
        }
        return palette[palette.length - 1];
    }

    /** 按比例压暗（0.8 = 保留 80% 亮度），用于"无定位"时的中性状态。 */
    public static int dim(int color, float factor) {
        float f = Math.max(0f, Math.min(1f, factor));
        return Color.argb(Color.alpha(color),
                (int) (Color.red(color) * f),
                (int) (Color.green(color) * f),
                (int) (Color.blue(color) * f));
    }

    private static int blend(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.argb(
                (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t),
                (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }
}
