package com.baidu.carplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import com.baidu.carplayer.widget.LcdTextView;

import com.baidu.carplayer.vehicle.SpeedTheme;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 车速读数 → 界面：数字显示/隐藏、标题栏与控制栏确实被重绘、无定位时压暗。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PlayerSpeedThemeTest {
    /** 播放页在缺少 playlistId 时会直接 finish，这里补上参数让 onCreate 走完初始化。 */
    private PlayerActivity activity() {
        android.content.Intent intent = new android.content.Intent(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_PLAYLIST_ID, "1");
        intent.putExtra(PlayerActivity.EXTRA_RESUME_PLAYBACK, true);
        return Robolectric.buildActivity(PlayerActivity.class, intent).create().get();
    }

    @Test public void stoppedHidesSpeed() {
        PlayerActivity a = activity();
        a.applySpeed(0f, true);
        assertEquals(View.GONE, a.findViewById(R.id.speed_text).getVisibility());
        assertEquals(View.GONE, a.findViewById(R.id.speed_unit).getVisibility());
    }

    @Test public void noFixHidesSpeed() {
        PlayerActivity a = activity();
        a.applySpeed(0f, false);
        assertEquals(View.GONE, a.findViewById(R.id.speed_text).getVisibility());
    }

    @Test public void movingShowsRoundedSpeed() {
        PlayerActivity a = activity();
        a.applySpeed(87.6f, true);
        LcdTextView speed = a.findViewById(R.id.speed_text);
        assertEquals(View.VISIBLE, speed.getVisibility());
        assertEquals("88", speed.getText().toString());
        assertEquals(View.VISIBLE, a.findViewById(R.id.speed_unit).getVisibility());
    }

    @Test public void headerAndControlBarAreRedrawnAsGradients() {
        PlayerActivity a = activity();
        a.applySpeed(0f, true);
        assertTrue(a.findViewById(R.id.header_layout).getBackground() instanceof GradientDrawable);
        assertTrue(a.findViewById(R.id.control_bar).getBackground() instanceof GradientDrawable);
        // 每次读数都重绘一次：换车速后背景对象应被替换
        Object cold = a.findViewById(R.id.header_layout).getBackground();
        a.applySpeed(140f, true);
        assertNotEquals(cold, a.findViewById(R.id.header_layout).getBackground());
    }

    @Test public void invalidFixIsDimmerThanValid() {
        int[] valid = SpeedTheme.headerColors(60f, true);
        int[] invalid = SpeedTheme.headerColors(60f, false);
        float validLum = lum(valid[0]), invalidLum = lum(invalid[0]);
        assertTrue("无定位时应更暗：" + invalidLum + " vs " + validLum, invalidLum < validLum);
    }

    private static float lum(int c) {
        return .2126f * android.graphics.Color.red(c)
                + .7152f * android.graphics.Color.green(c)
                + .0722f * android.graphics.Color.blue(c);
    }
}
