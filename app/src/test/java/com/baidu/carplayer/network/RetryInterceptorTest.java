package com.baidu.carplayer.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class RetryInterceptorTest {
    private static final Request REQUEST = new Request.Builder().url("https://pan.example/x").build();

    /** 依次返回预设结果的假 Chain；proceed 次数可断言。 */
    private static final class FakeChain implements Interceptor.Chain {
        final Call call = new OkHttpClient().newCall(REQUEST);
        final Deque<Object> results = new ArrayDeque<>();
        int proceeds;
        Runnable onProceed;

        FakeChain then(Object result) {
            results.add(result);
            return this;
        }

        @Override public Request request() { return REQUEST; }

        @Override public Response proceed(Request request) throws IOException {
            proceeds++;
            if (onProceed != null) onProceed.run();
            Object next = results.isEmpty() ? Integer.valueOf(200) : results.poll();
            if (next instanceof IOException) throw (IOException) next;
            return response((Integer) next);
        }

        @Override public Connection connection() { return null; }
        @Override public Call call() { return call; }
        @Override public int connectTimeoutMillis() { return 0; }
        @Override public Interceptor.Chain withConnectTimeout(int t, TimeUnit u) { return this; }
        @Override public int readTimeoutMillis() { return 0; }
        @Override public Interceptor.Chain withReadTimeout(int t, TimeUnit u) { return this; }
        @Override public int writeTimeoutMillis() { return 0; }
        @Override public Interceptor.Chain withWriteTimeout(int t, TimeUnit u) { return this; }
    }

    private static Response response(int code) {
        return new Response.Builder().request(REQUEST).protocol(Protocol.HTTP_1_1)
                .code(code).message("m")
                .body(ResponseBody.create("", MediaType.get("text/plain"))).build();
    }

    @Test
    public void retriesServerErrorsThenSucceeds() throws IOException {
        FakeChain chain = new FakeChain().then(503).then(new IOException("reset")).then(200);
        Response r = new RetryInterceptor(3, 1).intercept(chain);
        assertEquals(200, r.code());
        assertEquals(3, chain.proceeds);
    }

    @Test
    public void clientErrorIsNotRetried() throws IOException {
        FakeChain chain = new FakeChain().then(404);
        assertEquals(404, new RetryInterceptor(3, 1).intercept(chain).code());
        assertEquals(1, chain.proceeds);
    }

    @Test
    public void lastServerErrorIsReturned() throws IOException {
        FakeChain chain = new FakeChain().then(500).then(502);
        assertEquals(502, new RetryInterceptor(1, 1).intercept(chain).code());
        assertEquals(2, chain.proceeds);
    }

    @Test
    public void exhaustedIoFailureIsRethrown() {
        FakeChain chain = new FakeChain().then(new IOException("a")).then(new IOException("b"));
        try {
            new RetryInterceptor(1, 1).intercept(chain);
            fail();
        } catch (IOException e) {
            assertEquals("b", e.getMessage());
        }
    }

    @Test
    public void canceledCallIsNeverAttempted() {
        FakeChain chain = new FakeChain();
        chain.call.cancel();
        try {
            new RetryInterceptor(3, 1).intercept(chain);
            fail();
        } catch (IOException expected) {
            assertEquals(0, chain.proceeds);
        }
    }

    @Test
    public void cancelDuringBackoffStopsQuickly() {
        // 快速切歌：旧请求失败后进入退避，此时被取消，必须立即释放线程，不再重试。
        FakeChain chain = new FakeChain().then(new IOException("timeout")).then(200);
        chain.onProceed = () -> new Thread(() -> {
            sleep(50);
            chain.call.cancel();
        }).start();
        long start = System.nanoTime();
        try {
            new RetryInterceptor(3, 5_000).intercept(chain);
            fail("取消后不应返回结果");
        } catch (IOException expected) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue("退避应在取消后很快结束，实际 " + elapsedMs + "ms", elapsedMs < 1_000);
            assertEquals("取消后不应再次发起请求", 1, chain.proceeds);
        }
    }

    @Test
    public void ioFailureAfterCancelIsNotRetried() {
        FakeChain chain = new FakeChain().then(new IOException("Canceled")).then(200);
        chain.onProceed = chain.call::cancel;
        try {
            new RetryInterceptor(3, 1).intercept(chain);
            fail();
        } catch (IOException expected) {
            assertEquals(1, chain.proceeds);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
