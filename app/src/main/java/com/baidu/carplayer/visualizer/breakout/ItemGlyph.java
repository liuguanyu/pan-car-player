package com.baidu.carplayer.visualizer.breakout;

/**
 * 技能砖标志的着色器类型编号。
 *
 * <p>五种技能砖共用白色，靠形状区分；编号写进实例纹理的 kind 通道，
 * 由 {@code breakout_sprite_frag.glsl} 的 itemGlyph() 画成对应符号。
 * 幽灵态（弱音）不换形状，只压暗亮度。
 */
public final class ItemGlyph {
    public static final float MULTI = 10f;   // 三个点
    public static final float FAST = 11f;    // 双上箭头
    public static final float SLOW = 12f;    // 双下箭头
    public static final float WIDE = 13f;    // 横向展开箭头
    public static final float NARROW = 14f;  // 两个向内收拢箭头

    /** 弱音幽灵态下标志的亮度倍率：形状仍可见，但不抢注意力。 */
    public static final float GHOST_GAIN = 0.38f;

    private ItemGlyph() {
    }

    /** 技能砖类型 → 着色器编号；普通砖返回 0（不画标志）。 */
    public static float kindFor(int itemType) {
        switch (itemType) {
            case BreakoutScene.ITEM_MULTI:
                return MULTI;
            case BreakoutScene.ITEM_FAST:
                return FAST;
            case BreakoutScene.ITEM_SLOW:
                return SLOW;
            case BreakoutScene.ITEM_WIDE:
                return WIDE;
            case BreakoutScene.ITEM_NARROW:
                return NARROW;
            default:
                return 0f;
        }
    }
}
