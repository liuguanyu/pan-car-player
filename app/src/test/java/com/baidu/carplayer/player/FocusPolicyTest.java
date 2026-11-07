package com.baidu.carplayer.player;

import static org.junit.Assert.assertEquals;

import android.media.AudioManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FocusPolicyTest {
    @Test
    public void transientLossPausesOnlyWhenPlaying() {
        assertEquals(FocusPolicy.Action.PAUSE_TRANSIENT, FocusPolicy.forFocusChange(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, true));
        assertEquals("已暂停时不应记录被动暂停", FocusPolicy.Action.NONE, FocusPolicy.forFocusChange(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, false));
    }

    @Test
    public void duckOnlyWhenPlaying() {
        assertEquals(FocusPolicy.Action.DUCK, FocusPolicy.forFocusChange(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, true));
        assertEquals(FocusPolicy.Action.NONE, FocusPolicy.forFocusChange(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, false));
    }

    @Test
    public void gainRestoresAndPermanentLossStops() {
        assertEquals(FocusPolicy.Action.RESTORE,
                FocusPolicy.forFocusChange(AudioManager.AUDIOFOCUS_GAIN, true));
        assertEquals(FocusPolicy.Action.STOP,
                FocusPolicy.forFocusChange(AudioManager.AUDIOFOCUS_LOSS, true));
    }

    @Test
    public void duckingNeverRaisesUserVolume() {
        assertEquals(0.2f, FocusPolicy.duckedVolume(1.0f), 0.0001f);
        assertEquals(0.1f, FocusPolicy.duckedVolume(0.1f), 0.0001f);
    }
}
