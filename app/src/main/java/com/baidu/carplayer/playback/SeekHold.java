package com.baidu.carplayer.playback;

/**
 * 方向盘"下一首/上一首"键的长按判定状态机，纯 Java 无 Android 依赖，可直接单测。
 *
 * <p>语义：按下后先等待 {@link #HOLD_MS}；仍未松开则转为长按快进/快退，
 * 此后每次 {@link #tick} 都产出一个 {@link #STEP_MS} 的跳转量。
 * 松开时由 {@link #end()} 告知调用方这一下是"长按已消费"还是"短按需换曲"。
 */
public final class SeekHold {
    /** 超过该时长仍未松开即进入长按。 */
    public static final long HOLD_MS = 450L;
    /** 长按期间每次跳转的间隔。 */
    public static final long TICK_MS = 200L;
    /** 单次跳转量；配合 TICK_MS 约为 10 秒/秒。 */
    public static final long STEP_MS = 2000L;

    private boolean armed;
    private boolean active;
    private int direction;
    private long downMs;

    /** 由按键类型得到跳转方向：下一首 = +1 快进，上一首 = -1 快退，其余 = 0。 */
    public static int directionFor(PlaybackCommand.Type type) {
        if (type == PlaybackCommand.Type.NEXT) return 1;
        if (type == PlaybackCommand.Type.PREVIOUS) return -1;
        return 0;
    }

    /** 记录按下时刻；direction 取 {@link #directionFor} 的结果。 */
    public void begin(int direction, long nowMs) {
        this.direction = direction;
        this.downMs = nowMs;
        armed = direction != 0;
        active = false;
    }

    /**
     * 定时器每次触发调用。
     * @return 需要跳转的毫秒数（带符号），0 表示尚未进入长按、本次无需动作
     */
    public long tick(long nowMs) {
        if (!armed) return 0L;
        if (!active) {
            if (nowMs - downMs < HOLD_MS) return 0L;
            active = true;
        }
        return direction * STEP_MS;
    }

    public boolean isActive() {
        return active;
    }

    /** 松开按键：返回本次是否已作为长按处理（true 时调用方不要再执行换曲）。 */
    public boolean end() {
        boolean wasActive = active;
        cancel();
        return wasActive;
    }

    public void cancel() {
        armed = false;
        active = false;
        direction = 0;
        downMs = 0L;
    }
}
