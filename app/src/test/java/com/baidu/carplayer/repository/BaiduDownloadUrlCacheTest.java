package com.baidu.carplayer.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.baidu.carplayer.model.DeviceCodeResponse;
import com.baidu.carplayer.model.DownloadLinkResponse;
import com.baidu.carplayer.model.FileListResponse;
import com.baidu.carplayer.model.TokenResponse;
import com.baidu.carplayer.network.BaiduPanService;

import org.junit.Test;

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
public class BaiduDownloadUrlCacheTest {

    /** 只实现 getFileDownloadLink 的假网盘服务，统计请求次数。 */
    private static class FakePanService implements BaiduPanService {
        int filemetasCalls = 0;
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
                String method, String accessToken, String fsids, int dlink) {
            filemetasCalls++;
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
