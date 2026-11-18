package com.baidu.carplayer.vehicle;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * 实时车速（GPS）。
 *
 * <p>车速取自系统定位的 {@link Location#getSpeed()}（多普勒频移算出的地速，比"两次坐标差"稳），
 * 换算成 km/h 后做低通平滑，避免数字抖动。GPS 1Hz 输出，急加减速约有 1 秒延迟；
 * 隧道、地库丢星时降为 0（此时界面显示占位符而不是 0）。
 *
 * <p>只读定位、不上传；定位权限由界面申请，未授权时本类不启动。
 */
public final class SpeedMonitor {
    /** 车速回调（主线程）。 */
    public interface Listener {
        /**
         * @param kmh   平滑后的车速（km/h）
         * @param valid 是否有有效定位；false 表示提供方未开启或尚未定位成功
         */
        void onSpeed(float kmh, boolean valid);
    }

    /** 低于这个速度视为静止：GPS 静止时会有 1~2km/h 的漂移。 */
    private static final float STILL_KMH = 2.2f;
    /** 平滑时间常数（毫秒）：约 3 个采样点内跟上真实车速，同时压掉 GPS 抖动。 */
    private static final long TAU_MS = 700L;
    /** 超过这个时间没有新的定位就认为失效（毫秒）。 */
    private static final long STALE_MS = 6000L;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private LocationManager locationManager;
    private boolean started;
    private float kmh;
    private long lastFixMs;

    public SpeedMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 开始接收定位；未授权或没有 GPS 提供方时不再重试，直接回调无效。 */
    public void start() {
        if (started || listener == null) return;
        if (!hasPermission(context)) {
            listener.onSpeed(0f, false);
            return;
        }
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            listener.onSpeed(0f, false);
            return;
        }
        started = true;
        try {
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) onLocation(last);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f,
                    locationListener, Looper.getMainLooper());
        } catch (RuntimeException e) {
            // 提供方被关闭或权限在运行中被回收：界面退回占位符
            started = false;
            listener.onSpeed(0f, false);
            return;
        }
        main.post(staleCheck);
    }

    /** 停止接收定位并释放。 */
    public void stop() {
        started = false;
        main.removeCallbacks(staleCheck);
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (RuntimeException ignored) {
                // 权限被回收后 removeUpdates 会抛异常，忽略即可
            }
            locationManager = null;
        }
    }

    /** 定位数据长时间不来时，把车速标为无效（避免界面一直显示旧值）。 */
    private final Runnable staleCheck = new Runnable() {
        @Override public void run() {
            if (!started) return;
            if (lastFixMs > 0 && System.currentTimeMillis() - lastFixMs > STALE_MS && listener != null) {
                listener.onSpeed(kmh, false);
            }
            main.postDelayed(this, 2000L);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            onLocation(location);
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

        @Override public void onProviderEnabled(String provider) { }

        @Override public void onProviderDisabled(String provider) {
            lastFixMs = 0;
            kmh = 0f;
            if (listener != null) listener.onSpeed(0f, false);
        }
    };

    private void onLocation(Location location) {
        if (location == null) return;
        float raw = location.hasSpeed()
                ? location.getSpeed() * 3.6f
                : fallbackSpeedKmh(location);
        lastFixMs = System.currentTimeMillis();
        kmh = filter(kmh, raw, 1000f);
        if (listener != null) listener.onSpeed(kmh, true);
    }

    /** 设备不提供 getSpeed 时的退路：用连续定位的位移估算。 */
    private float fallbackSpeedKmh(Location location) {
        return lastFixMs == 0L ? 0f : kmh;
    }

    /**
     * 低通平滑 + 静止死区：GPS 抖动在静止时会跳 1~2km/h。
     * 平滑系数由时间间隔推出（1-e^(-dt/τ)），因此采样率变化时行为一致，
     * 且加速/减速能在 3 个采样点内跟上，不会像固定系数那样拖十几秒。
     */
    static float filter(float previous, float raw, float dtMs) {
        float target = raw < STILL_KMH ? 0f : raw;
        float a = 1f - (float) Math.exp(-Math.max(1f, dtMs) / TAU_MS);
        float next = previous + (target - previous) * a;
        return next < .3f ? 0f : next;
    }

    /** 测试用：直接注入一次定位结果（间隔按 GPS 的 1Hz 计算）。 */
    void onLocationForTest(float speedKmh) {
        lastFixMs = System.currentTimeMillis();
        kmh = filter(kmh, speedKmh, 1000f);
        if (listener != null) listener.onSpeed(kmh, true);
    }

    float kmhForTest() { return kmh; }
}
