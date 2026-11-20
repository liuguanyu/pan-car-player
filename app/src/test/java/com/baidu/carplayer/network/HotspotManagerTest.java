package com.baidu.carplayer.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 热点重连的纯逻辑：SSID 清洗与 SSID/密码校验。系统交互部分需车机实测。 */
public class HotspotManagerTest {

    @Test public void cleanSsidStripsSurroundingQuotes() {
        assertEquals("MyPhone", HotspotManager.cleanSsid("\"MyPhone\""));
    }

    @Test public void cleanSsidKeepsPlainName() {
        assertEquals("CMCC-5G", HotspotManager.cleanSsid("CMCC-5G"));
    }

    @Test public void cleanSsidTrimsWhitespace() {
        assertEquals("pad", HotspotManager.cleanSsid("  pad  "));
    }

    @Test public void cleanSsidEmptyOrNull() {
        assertNull(HotspotManager.cleanSsid(null));
        assertNull(HotspotManager.cleanSsid(""));
        assertNull(HotspotManager.cleanSsid("   "));
        // 只剩引号本身视为空
        assertNull(HotspotManager.cleanSsid("\"\""));
    }

    @Test public void validateAcceptsWpa2Passphrase() {
        assertNull(HotspotManager.validate("MyPhone", "01234567"));
        assertNull(HotspotManager.validate("a", "12345678"));
    }

    @Test public void validateRejectsEmptySsid() {
        assertNotNull(HotspotManager.validate("", "12345678"));
        assertNotNull(HotspotManager.validate(null, "12345678"));
    }

    @Test public void validateRejectsTooLongSsid() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 33; i++) sb.append('a');
        assertNotNull(HotspotManager.validate(sb.toString(), "12345678"));
    }

    @Test public void validateRejectsShortOrMissingPass() {
        assertNotNull(HotspotManager.validate("MyPhone", "1234567"));   // 7 位
        assertNotNull(HotspotManager.validate("MyPhone", null));
        assertNotNull(HotspotManager.validate("MyPhone", ""));
    }

    @Test public void validateRejectsTooLongPass() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) sb.append('x');
        assertNotNull(HotspotManager.validate("MyPhone", sb.toString()));
    }
}
