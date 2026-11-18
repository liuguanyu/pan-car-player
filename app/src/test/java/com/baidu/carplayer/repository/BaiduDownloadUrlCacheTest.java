package com.baidu.carplayer.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.baidu.carplayer.model.DeviceCodeResponse;
import com.baidu.carplayer.model.DownloadLinkResponse;
import com.baidu.carplayer.model.FileListResponse;
import com.baidu.carplayer.model.TokenResponse;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.network.RetryInterceptor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 下载链接缓存行为：首次取数、命中缓存、evict 强制重取、换 token 清空。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BaiduDownloadUrlCacheTest {

    /** 只实现 getFileDownloadLink 的假网盘服务，统计请求次数。 */
    private static class FakePanService implements BaiduPanService {
        int filemetasCalls = 0;
        final List<String> silentFlags = new ArrayList<>();
        final DownloadLinkResponse response = new DownloadLinkResponse();

        FakePanService(String dlink) {
            DownloadLinkResponse.FileInfo info = new DownloadLinkResponse.FileInfo();
            info.setDlink(dlink);
            response.setErrno(0);
            response.setList(new java.util.ArrayList<>());
            response.getList().add(info);
        }

        @Override
        public Call<DownloadLinkResponse> getFileDownloadLink(
                String method, String accessToken, String fsids, int dlink, String silent) {
            filemetasCalls++;
            silentFlags.add(silent);
            return new ImmediateCall<>(response);
        }

        @Override public Call<DeviceCodeResponse> getDeviceCode(String c, String s, String r) { throw new UnsupportedOperationException(); }
        @Override public Call<TokenResponse> getTokenByDeviceCode(String g, String c, String ci, String cs) { throw new UnsupportedOperationException(); }
        @Override public Call<TokenResponse> refreshToken(String g, String r, String ci, String cs) { throw new UnsupportedOperationException(); }
        @Override public Call<FileListResponse> getFileList(String m, String t, String d, String o, int de, int st, int l, int w, int f, int se) { throw new UnsupportedOperationException(); }
        @Override public Call<java.util.Map<String, Object>> search(String m, String t, String k, String d) { throw new UnsupportedOperationException(); }
    }

    /** 立即成功返回的 Call。 */
    private static class ImmediateCall<T> implements Call<T> {
        private final T value;
        ImmediateCall(T value) { this.value = value; }
        @Override public void enqueue(Callback<T> callback) {
            callback.onResponse(this, Response.success(value));
        }
        @Override public boolean isExecuted() { return false; }
        @Override public Response<T> execute() { return Response.success(value); }
        @Override public void cancel() { }
        @Override public boolean isCanceled() { return false; }
        @Override public Call<T> clone() { return new ImmediateCall<>(value); }
        @Override public okhttp3.Request request() { return new okhttp3.Request.Builder().url("http://fake/").build(); }
        @Override public okio.Timeout timeout() { return okio.Timeout.NONE; }
    }

    @Test
    public void cachesAfterFirstResolve() throws Exception {
        FakePanService service = new FakePanService("http://cdn/file?a=1");
        BaiduDownloadUrlRepository repo = new BaiduDownloadUrlRepository(service, () -> "tok");
        String first = resolve(repo, 42L);
        assertEquals("http://cdn/file?a=1&access_token=tok", first);
        assertEquals(1, service.filemetasCalls);
        // 第二次命中缓存：不再发请求
        String second = resolve(repo, 42L);
        assertEquals(first, second);
        assertEquals(1, service.filemetasCalls);
        // getCachedUrl 同样命中
        assertEquals(first, repo.getCachedUrl(42L));
        assertEquals(1, service.filemetasCalls);
    }

    @Test
    public void evictForcesRefetch() throws Exception {
        FakePanService service = new FakePanService("http://cdn/file?a=1");
        BaiduDownloadUrlRepository repo = new BaiduDownloadUrlRepository(service, () -> "tok");
        resolve(repo, 42L);
        assertEquals(1, service.filemetasCalls);

        repo.evict(42L);
        assertNull(repo.getCachedUrl(42L));
        resolve(repo, 42L);
        assertEquals("evict 后应重新请求 filemetas", 2, service.filemetasCalls);
    }

    @Test
    public void evictUnknownIdIsHarmless() throws Exception {
        FakePanService service = new FakePanService("http://cdn/file");
        BaiduDownloadUrlRepository repo = new BaiduDownloadUrlRepository(service, () -> "tok");
        repo.evict(999L); // 不抛异常
        assertEquals(0, service.filemetasCalls);
    }

    @Test
    public void differentFsIdsCachedSeparately() throws Exception {
        FakePanService service = new FakePanService("http://cdn/file");
        BaiduDownloadUrlRepository repo = new BaiduDownloadUrlRepository(service, () -> "tok");
        resolve(repo, 1L);
        resolve(repo, 2L);
        assertEquals(2, service.filemetasCalls);
        resolve(repo, 1L);
        assertEquals(2, service.filemetasCalls);
        repo.evict(2L);
        resolve(repo, 1L);
        assertEquals("evict 只影响指定 fsId", 2, service.filemetasCalls);
    }

    @Test
    public void prefetchAndForegroundShareOneInFlightRequest() throws Exception {
        FakePanService service = new FakePanService("http://cdn/file?a=1");
        BaiduDownloadUrlRepository repo = new BaiduDownloadUrlRepository(service, () -> "tok");

        AtomicReference<String> prefetched = new AtomicReference<>();
        repo.resolvePrefetch(7L, new DownloadUrlRepository.Callback() {
            @Override public void onSuccess(String playableUrl) { prefetched.set(playableUrl); }
            @Override public void onError(String message, boolean authExpired, Throwable cause) { }
        });
        assertEquals("预取请求带静默标记（失败不重试）",
                RetryInterceptor.SILENT_REQUEST, service.silentFlags.get(0));

        // 预取落地后缓存已就绪：前台切歌零请求
        assertEquals(prefetched.get(), repo.getCachedUrl(7L));
        resolve(repo, 7L);
        assertEquals("预取与前台共用一次请求", 1, service.filemetasCalls);
    }

    @Test
    public void foregroundResolveDoesNotCarrySilentFlag() throws Exception {
        FakePanService service = new FakePanService("http://cdn/file");
        BaiduDownloadUrlRepository repo = new BaiduDownloadUrlRepository(service, () -> "tok");
        resolve(repo, 3L);
        assertNull("前台请求允许重试", service.silentFlags.get(0));
    }

    private static String resolve(DownloadUrlRepository repo, long fsId) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> url = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        repo.resolve(fsId, new DownloadUrlRepository.Callback() {
            @Override public void onSuccess(String playableUrl) { url.set(playableUrl); latch.countDown(); }
            @Override public void onError(String message, boolean authExpired, Throwable cause) {
                error.set(message); latch.countDown();
            }
        });
        assertTrue("resolve 应同步完成", latch.await(2, TimeUnit.SECONDS));
        assertNull(error.get());
        return url.get();
    }
}
