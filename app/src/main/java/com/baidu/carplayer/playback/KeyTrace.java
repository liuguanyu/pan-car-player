package com.baidu.carplayer.playback;

import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import com.baidu.carplayer.BuildConfig;

/** 调试期把收到的每个按键打到日志，用于确认车机方向盘实际发送的键码。 */
public final class KeyTrace {
    public static final String TAG = "CarKey";
    private static int lastCode = Integer.MIN_VALUE;
    private static long lastLogMs;

    private KeyTrace() {
    }

    static void log(KeyEvent event) {
        if (!BuildConfig.DEBUG) return;
        int code = event.getKeyCode();
        long now = SystemClock.uptimeMillis();
        // 长按会产生大量重复事件，同一键码 300ms 内只记一条。
        if (code == lastCode && now - lastLogMs < 300L) return;
        lastCode = code;
        lastLogMs = now;
        Log.d(TAG, "code=" + code + " (" + KeyEvent.keyCodeToString(code) + ")"
                + " action=" + (event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP")
                + " repeat=" + event.getRepeatCount()
                + " scan=" + event.getScanCode()
                + " device=" + event.getDeviceId());
    }
}
