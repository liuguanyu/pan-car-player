package com.baidu.carplayer.playback;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlaybackCoordinatorTest {
    private PlaybackState state(long requestId, PlaybackState.Status status) {
        return new PlaybackState(Collections.emptyList(), -1, -1, status,
                false, 0, 0, 0, 0, requestId);
    }

    @Test
    public void dispatchesCommandsInSubmissionOrder() throws Exception {
        PlaybackCoordinator coordinator = new PlaybackCoordinator(state(0, PlaybackState.Status.IDLE));
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger lastRequest = new AtomicInteger();

        coordinator.subscribe(value -> {
            lastRequest.set((int) value.getRequestId());
            if (value.getRequestId() > 0) done.countDown();
        });
        coordinator.dispatch(PlaybackCommand.simple(PlaybackCommand.Type.PLAY, 1),
                old -> state(1, PlaybackState.Status.BUFFERING));
        coordinator.dispatch(PlaybackCommand.simple(PlaybackCommand.Type.PAUSE, 2),
                old -> state(2, PlaybackState.Status.READY));

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(2, lastRequest.get());
        coordinator.close();
    }

    @Test
    public void unsubscribeStopsFutureNotifications() throws Exception {
        PlaybackCoordinator coordinator = new PlaybackCoordinator(state(0, PlaybackState.Status.IDLE));
        AtomicInteger callbacks = new AtomicInteger();
        PlaybackCoordinator.StateSubscriber subscriber = value -> callbacks.incrementAndGet();
        coordinator.subscribe(subscriber);
        int initial = callbacks.get();
        coordinator.unsubscribe(subscriber);
        coordinator.publish(state(1, PlaybackState.Status.READY));
        Thread.sleep(100);
        assertEquals(initial, callbacks.get());
        coordinator.close();
    }

    @Test
    public void everySubscriberReceivesPublishedState() throws Exception {
        PlaybackCoordinator coordinator = new PlaybackCoordinator(state(0, PlaybackState.Status.IDLE));
        CountDownLatch received = new CountDownLatch(2);
        coordinator.subscribe(value -> { if (value.getRequestId() == 5) received.countDown(); });
        coordinator.subscribe(value -> { if (value.getRequestId() == 5) received.countDown(); });

        coordinator.publish(state(5, PlaybackState.Status.READY));

        assertTrue(received.await(2, TimeUnit.SECONDS));
        coordinator.close();
    }

    @Test
    public void closedCoordinatorIgnoresPublish() throws Exception {
        PlaybackCoordinator coordinator = new PlaybackCoordinator(state(0, PlaybackState.Status.IDLE));
        coordinator.close();

        coordinator.publish(state(9, PlaybackState.Status.READY));
        Thread.sleep(50);

        assertEquals(0L, coordinator.getState().getRequestId());
    }
}
