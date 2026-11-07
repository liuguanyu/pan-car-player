package com.baidu.carplayer.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BaiduDownloadUrlRepositoryTest {

    @Test
    public void appendsAccessTokenWithCorrectSeparator() {
        assertEquals("https://a/b?access_token=t", BaiduDownloadUrlRepository.appendAccessToken("https://a/b", "t"));
        assertEquals("https://a/b?x=1&access_token=t", BaiduDownloadUrlRepository.appendAccessToken("https://a/b?x=1", "t"));
    }

    @Test
    public void recognisesTokenExpiredErrnos() {
        assertTrue(BaiduDownloadUrlRepository.isAuthError(110));
        assertTrue(BaiduDownloadUrlRepository.isAuthError(111));
        assertTrue(BaiduDownloadUrlRepository.isAuthError(31034));
    }

    @Test
    public void missingTokenReportsAuthExpiredWithoutNetworkCall() {
        final boolean[] authExpired = {false};
        BaiduDownloadUrlRepository repository = new BaiduDownloadUrlRepository(null, () -> null);
        repository.resolve(1L, new DownloadUrlRepository.Callback() {
            @Override public void onSuccess(String playableUrl) { }
            @Override public void onError(String message, boolean expired, Throwable cause) { authExpired[0] = expired; }
        });
        assertTrue(authExpired[0]);
    }
}
