package com.baidu.carplayer.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** 网络抖动重试策略：瞬时错误可重试、4xx 不重试、退避间隔递增。 */
public class NetworkRetryTest {

    @Test public void transientErrorsAreRetryable() {
        assertTrue(NetworkRetry.isTransient(new SocketTimeoutException("timeout")));
        assertTrue(NetworkRetry.isTransient(new UnknownHostException("dns")));
        assertTrue(NetworkRetry.isTransient(new IOException("connection reset")));
        // 播放器异常通常把网络原因包在 cause 链里
        assertTrue(NetworkRetry.isTransient(
                new IllegalStateException("player", new SocketTimeoutException("timeout"))));
    }

    @Test public void deterministicFailuresAreNotRetried() {
        assertFalse(NetworkRetry.isTransient(new IllegalStateException("decode failed")));
        assertFalse(NetworkRetry.isTransient(null));
    }

    @Test public void delaysGrowAndThenCap() {
        assertTrue(NetworkRetry.delayFor(1) < NetworkRetry.delayFor(2));
        assertTrue(NetworkRetry.delayFor(2) < NetworkRetry.delayFor(3));
        assertEquals(NetworkRetry.delayFor(NetworkRetry.MAX_ATTEMPTS),
                NetworkRetry.delayFor(NetworkRetry.MAX_ATTEMPTS + 5));
        // 首次重试也不能立刻打，避免抖动瞬间形成请求风暴
        assertTrue(NetworkRetry.delayFor(1) >= 1000L);
    }
}
