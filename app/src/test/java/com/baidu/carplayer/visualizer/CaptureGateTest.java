package com.baidu.carplayer.visualizer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CaptureGateTest {
    @Test
    public void callbackFromReleasedSessionIsRejected() {
        CaptureGate gate = new CaptureGate();
        gate.setPlaying(true);
        int oldToken = gate.issue();
        assertTrue(gate.accepts(oldToken));
        gate.invalidate(); // release / 切换 session
        int newToken = gate.issue();
        assertFalse(gate.accepts(oldToken));
        assertTrue(gate.accepts(newToken));
    }

    @Test
    public void pausedCallbacksAreRejected() {
        CaptureGate gate = new CaptureGate();
        int token = gate.issue();
        assertFalse(gate.accepts(token));
        gate.setPlaying(true);
        assertTrue(gate.accepts(token));
        gate.setPlaying(false);
        assertFalse(gate.accepts(token));
    }
}
