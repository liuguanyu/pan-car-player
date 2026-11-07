package com.baidu.carplayer.ui;

/**
 * 页面数据加载闸门：同一时刻只允许一个加载在途，且只接受最新一次的代次。
 * 用于避免 onCreate/onStart/onResume 重复触发的重复加载，以及迟到的旧结果覆盖新数据。
 * 只在主线程使用。
 */
public final class LoadGate {
    private long generation;
    private boolean inFlight;

    /** 开始一次加载；已有一个在途时返回 -1（调用方应放弃本次）。 */
    public long begin() {
        if (inFlight) {
            return -1;
        }
        inFlight = true;
        return ++generation;
    }

    /** 该代次的结果是否还有效。 */
    public boolean isCurrent(long token) {
        return inFlight && token == generation;
    }

    /** 结果已消费（无论成功失败）。 */
    public void finish(long token) {
        if (token == generation) {
            inFlight = false;
        }
    }

    /** 放弃在途加载（例如页面销毁或数据已被本地写入覆盖）。 */
    public void cancel() {
        inFlight = false;
        generation++;
    }

    public boolean isInFlight() {
        return inFlight;
    }
}
