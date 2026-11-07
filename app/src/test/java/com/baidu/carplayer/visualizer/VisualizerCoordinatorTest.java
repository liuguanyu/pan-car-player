package com.baidu.carplayer.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class VisualizerCoordinatorTest {
    private static final class FakeCapture implements VisualizerCoordinator.Capture {
        int attachCount;
        int releaseCount;
        int lastSession;
        boolean playing;
        boolean failAttach;

        @Override public boolean attachToSession(int id) {
            attachCount++;
            lastSession = id;
            return !failAttach && id > 0;
        }
        @Override public void setPlaying(boolean value) { playing = value; }
        @Override public void release() { releaseCount++; }
    }

    private FakeCapture capture;
    private VisualizerCoordinator coordinator;

    @Before
    public void setUp() {
        capture = new FakeCapture();
        coordinator = new VisualizerCoordinator(capture);
        coordinator.setModeEnabled(true);
        coordinator.setPermissionGranted(true);
        coordinator.setScreenVisible(true);
        coordinator.setAudioSessionId(7);
        coordinator.setPlaying(true);
    }

    @Test
    public void attachesOnlyWhenAllConditionsHold() {
        assertTrue(coordinator.apply());
        assertEquals(7, capture.lastSession);
        assertTrue(coordinator.isRendering());

        coordinator.setPermissionGranted(false);
        assertFalse(coordinator.apply());
        assertEquals(1, capture.releaseCount);
        assertFalse(coordinator.isRendering());
    }

    @Test
    public void hiddenScreenReleasesCapture() {
        coordinator.apply();
        coordinator.setScreenVisible(false);
        assertFalse(coordinator.apply());
        assertEquals(1, capture.releaseCount);
        // 已释放后再次隐藏不会重复释放
        coordinator.apply();
        assertEquals(1, capture.releaseCount);
    }

    @Test
    public void invalidSessionNeverAttaches() {
        coordinator.setAudioSessionId(0);
        assertFalse(coordinator.apply());
        assertEquals(0, capture.attachCount);
    }

    @Test
    public void pausedStateStopsRenderingButKeepsCapture() {
        coordinator.apply();
        coordinator.setPlaying(false);
        assertTrue(coordinator.apply());
        assertFalse(capture.playing);
        assertFalse(coordinator.isRendering());
    }

    @Test
    public void attachFailureFallsBackAndReleases() {
        capture.failAttach = true;
        assertFalse(coordinator.apply());
        assertEquals(1, capture.releaseCount);
    }

    @Test
    public void closedCoordinatorNeverReattaches() {
        coordinator.apply();
        coordinator.close();
        assertFalse(coordinator.apply());
        assertEquals(1, capture.attachCount);
    }
}
