package com.baidu.carplayer.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackRequestTrackerTest {

    @Test
    public void outOfOrderResponseFromOldRequestIsRejected() {
        PlaybackRequestTracker tracker = new PlaybackRequestTracker();
        long first = tracker.begin(1L, null, true).getRequestId();
        long second = tracker.begin(2L, null, true).getRequestId();

        // 旧请求的响应晚到，必须被丢弃；新请求的响应才能提交。
        assertFalse(tracker.isCurrent(first));
        assertTrue(tracker.isCurrent(second));
        assertEquals(2L, tracker.current().getSongId());
    }

    @Test
    public void pauseDuringPrepareDisablesAutoplay() {
        PlaybackRequestTracker tracker = new PlaybackRequestTracker();
        tracker.begin(1L, 3000L, true);

        tracker.setPlayWhenReady(false);

        PlaybackCommand seek = tracker.consumeSeek();
        assertNotNull(seek);
        assertEquals(3000L, seek.getSeekPositionMs());
        assertFalse(seek.isPlayWhenReady());
    }

    @Test
    public void seekIsConsumedOnlyOnce() {
        PlaybackRequestTracker tracker = new PlaybackRequestTracker();
        tracker.begin(1L, 500L, true);

        assertNotNull(tracker.consumeSeek());
        assertNull(tracker.consumeSeek());
        assertNotNull(tracker.current());
    }

    @Test
    public void staleSeekFromReplacedRequestIsNotApplied() {
        PlaybackRequestTracker tracker = new PlaybackRequestTracker();
        tracker.begin(1L, 9000L, true);
        tracker.begin(2L, null, true);

        assertNull(tracker.consumeSeek());
    }

    @Test
    public void closeInvalidatesPendingAndFutureRequests() {
        PlaybackRequestTracker tracker = new PlaybackRequestTracker();
        long pending = tracker.begin(1L, null, true).getRequestId();

        tracker.close();

        assertFalse(tracker.isCurrent(pending));
        assertNull(tracker.current());
        long afterClose = tracker.begin(2L, null, true).getRequestId();
        assertFalse(tracker.isCurrent(afterClose));
    }

    @Test
    public void finishClearsOnlyMatchingRequest() {
        PlaybackRequestTracker tracker = new PlaybackRequestTracker();
        long first = tracker.begin(1L, null, true).getRequestId();
        tracker.begin(2L, null, true);

        tracker.finish(first);

        assertNotNull(tracker.current());
    }
}
