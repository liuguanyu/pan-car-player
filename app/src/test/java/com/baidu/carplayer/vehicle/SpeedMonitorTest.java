package com.baidu.carplayer.vehicle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 车速平滑：静止死区、平滑逼近、无权限时不上报有效值。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SpeedMonitorTest {
    @Test public void stillDriftIsTreatedAsZero() {
        // GPS 静止时会有 1~2km/h 漂移，应视为 0
        assertEquals(0f, SpeedMonitor.filter(0f, 1.5f, 1000f), 1e-4f);
        // 从行驶中减速到静止：平滑衰减到 0，不是瞬时归零
        float v = 5f;
        float first = SpeedMonitor.filter(v, 2.1f, 1000f);
        assertTrue("应开始衰减：" + first, first < v && first > 0f);
        for (int i = 0; i < 60; i++) v = SpeedMonitor.filter(v, 2.1f, 1000f);
        assertEquals(0f, v, 1e-4f);
    }

    @Test public void approachesTargetSmoothly() {
        float single = SpeedMonitor.filter(0f, 100f, 1000f);
        assertTrue("单帧应跟手但不跳变：" + single, single > 40f && single < 90f);
        float v = 0f;
        for (int i = 0; i < 200; i++) v = SpeedMonitor.filter(v, 100f, 1000f);
        assertTrue("应收敛到 100：" + v, Math.abs(v - 100f) < 1f);
    }

    @Test public void deceleratingToZeroSnapsCleanly() {
        float v = 80f;
        for (int i = 0; i < 200; i++) v = SpeedMonitor.filter(v, 0f, 1000f);
        assertEquals(0f, v, 1e-4f);
    }

    @Test public void withoutPermissionReportsInvalid() {
        Context context = ApplicationProvider.getApplicationContext();
        SpeedMonitor monitor = new SpeedMonitor(context);
        final boolean[] called = {false};
        final boolean[] valid = {true};
        monitor.setListener((kmh, ok) -> { called[0] = true; valid[0] = ok; });
        monitor.start();   // 测试环境没有定位权限
        assertTrue("应回调一次", called[0]);
        assertFalse("未授权时应标记为无效", valid[0]);
        monitor.stop();
    }

    @Test public void convergesWithinFewSamplesAtOneHertz() {
        // 车速表不能拖：1Hz 采样下 3 帧应基本跟上（>85%）
        float v = 0f;
        for (int i = 0; i < 3; i++) v = SpeedMonitor.filter(v, 100f, 1000f);
        assertTrue("3 帧后应基本跟上：" + v, v > 85f);
        // 但单帧不跳变
        assertTrue(SpeedMonitor.filter(0f, 100f, 1000f) < 85f);
    }

    @Test public void smoothingIsFrameRateIndependent() {
        // 一帧 1000ms 与两帧 500ms 应得到接近的结果
        float one = SpeedMonitor.filter(0f, 100f, 1000f);
        float two = SpeedMonitor.filter(SpeedMonitor.filter(0f, 100f, 500f), 100f, 500f);
        assertTrue("不同帧率结果应接近：" + one + " vs " + two, Math.abs(one - two) < 3f);
    }

    @Test public void injectedFixUpdatesSmoothedSpeed() {
        Context context = ApplicationProvider.getApplicationContext();
        SpeedMonitor monitor = new SpeedMonitor(context);
        final float[] last = {-1f};
        monitor.setListener((kmh, ok) -> last[0] = kmh);
        monitor.onLocationForTest(60f);
        assertTrue("第一帧应部分逼近：" + last[0], last[0] > 0f && last[0] < 60f);
        for (int i = 0; i < 60; i++) monitor.onLocationForTest(60f);
        assertTrue("应收敛到 60：" + monitor.kmhForTest(), Math.abs(monitor.kmhForTest() - 60f) < 1.5f);
    }
}
