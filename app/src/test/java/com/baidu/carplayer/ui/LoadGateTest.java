package com.baidu.carplayer.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LoadGateTest {
    @Test
    public void secondLoadWhileInFlightIsRejected() {
        LoadGate gate = new LoadGate();
        long first = gate.begin();
        assertTrue(first >= 0);
        assertTrue("重复加载应被忽略", gate.begin() < 0);
        assertTrue(gate.isCurrent(first));
    }

    @Test
    public void finishAllowsNextLoad() {
        LoadGate gate = new LoadGate();
        long first = gate.begin();
        gate.finish(first);
        assertFalse(gate.isInFlight());
        long second = gate.begin();
        assertTrue(second > first);
        assertFalse("旧结果失效", gate.isCurrent(first));
        assertTrue(gate.isCurrent(second));
    }

    @Test
    public void cancelInvalidatesInFlightResult() {
        LoadGate gate = new LoadGate();
        long token = gate.begin();
        gate.cancel(); // 例如页面 onStop
        assertFalse(gate.isCurrent(token));
        assertFalse(gate.isInFlight());
        assertTrue("取消后可以重新加载", gate.begin() >= 0);
    }

    @Test
    public void staleFinishDoesNotUnblockNewerLoad() {
        LoadGate gate = new LoadGate();
        long first = gate.begin();
        gate.cancel();
        long second = gate.begin();
        gate.finish(first); // 旧请求迟到的结束
        assertTrue(gate.isCurrent(second));
        assertTrue(gate.isInFlight());
    }
}
