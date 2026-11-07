package com.baidu.carplayer.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LogRedactorTest {

    @Test
    public void redactsTokenQueryParameters() {
        String url = "https://d.pcs.baidu.com/file/x?fid=1&access_token=SECRET123&dstime=2";
        assertEquals("https://d.pcs.baidu.com/file/x?fid=1&access_token=***&dstime=2", LogRedactor.redact(url));
    }

    @Test
    public void redactsRefreshTokenAndClientSecret() {
        String redacted = LogRedactor.redact("refresh_token=abc&client_secret=xyz&device_code=dc");
        assertFalse(redacted.contains("abc"));
        assertFalse(redacted.contains("xyz"));
        assertFalse(redacted.contains("dc"));
    }

    @Test
    public void keepsNonSecretTextAndNull() {
        assertEquals("fid=1&path=/music", LogRedactor.redact("fid=1&path=/music"));
        assertNull(LogRedactor.redact(null));
    }
}
