package com.baidu.carplayer.vehicle;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 实时车速（GPS / 网络定位）。
 *
 * <p>车速优先取 {@link Location#getSpeed()}（多普勒频移算出的地速，比"两次坐标差"稳）。
 * 不少车机的定位只给经纬度、不给地速，此时退化为用相邻两次定位的大圆距离估算，
 * 因此 {@code hasSpeed()} 为 false 的设备也能显示车速。
 *
 * <p>提供方不再写死 GPS：先试 GPS，再试 NETWORK，最后按 {@link Criteria} 找最合适的那个。
 * 车机偶尔只有网络定位或只有厂商自定义 provider，写死 GPS 会导致完全收不到数据。
 *
 * <p>只读定位、不上传；定位权限由界面申请，未授权时本类不启动。
 */
public final class SpeedMonitor {
    private static final String TAG = "SpeedMonitor";

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
    /** 位移估速时认为有意义的定位精度上限（米）。 */
    private static final float MAX_ACCURACY_M = 150f;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 实际注册成功的提供方，stop 时逐个注销。 */
    private final List<String> activeProviders = new ArrayList<>();
    private Listener listener;
    private LocationManager locationManager;
    private boolean started;
    private float kmh;
    private long lastFixMs;
    /** 上一个用于位移估速的定位点。 */
    private Location lastForDelta;
    private int fixCount;

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

    /** 开始接收定位；未授权或没有任何可用提供方时直接回调无效。 */
    public void start() {
        if (started || listener == null) return;
        if (!hasPermission(context)) {
            Log.w(TAG, "没有定位权限，车速不可用");
            listener.onSpeed(0f, false);
            return;
        }
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            Log.w(TAG, "LocationManager 不可用");
            listener.onSpeed(0f, false);
            return;
        }
        started = true;
        fixCount = 0;
        lastForDelta = null;

        List<String> providers = resolveProviders(locationManager);
        Log.i(TAG, "可用定位提供方=" + providers);
        if (providers.isEmpty()) {
            Log.w(TAG, "没有任何定位提供方，车速不可用");
            listener.onSpeed(0f, false);
            return;
        }

        // 先用上次的已知位置顶一下，界面不必等第一颗星
        for (String provider : providers) {
            try {
                Location last = locationManager.getLastKnownLocation(provider);
                if (last != null) {
                    Log.i(TAG, "上次已知位置(" + provider + ") 时间=" + last.getTime()
                            + " 有速度=" + last.hasSpeed());
                    onLocation(last);
                    break;
                }
            } catch (RuntimeException ignored) {
                // 某些车机上取上次位置也会抛异常，忽略继续
            }
        }

        for (String provider : providers) {
            try {
                locationManager.requestLocationUpdates(provider, 1000L, 0f,
                        locationListener, Looper.getMainLooper());
                activeProviders.add(provider);
                Log.i(TAG, "已订阅 " + provider);
            } catch (RuntimeException e) {
                Log.w(TAG, "订阅 " + provider + " 失败: " + e);
            }
        }
        if (activeProviders.isEmpty()) {
            started = false;
            listener.onSpeed(0f, false);
            return;
        }
        main.post(staleCheck);
    }

    /**
     * 选出真正可用的提供方：GPS 优先，其次 NETWORK，最后交给 Criteria 决定。
     * 已启用的排在前面，未启用的一并不订阅（订阅会立即失败或永久无数据）。
     */
    private static List<String> resolveProviders(LocationManager manager) {
        List<String> out = new ArrayList<>();
        String[] preferred = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
        for (String provider : preferred) {
            try {
                if (manager.isProviderEnabled(provider)) out.add(provider);
            } catch (RuntimeException ignored) {
                // 提供方不存在时部分车机会抛异常
            }
        }
        if (out.isEmpty()) {
            try {
                Criteria criteria = new Criteria();
                criteria.setAccuracy(Criteria.ACCURACY_FINE);
                String best = manager.getBestProvider(criteria, true);
                if (best != null) out.add(best);
            } catch (RuntimeException ignored) {
                // 忽略：下面会把空列表返回给调用方
            }
        }
        return out;
    }

    /** 停止接收定位并释放。 */
    public void stop() {
        started = false;
        main.removeCallbacks(staleCheck);
        if (locationManager != null) {
            for (String provider : activeProviders) {
                try {
                    locationManager.removeUpdates(locationListener);
                } catch (RuntimeException ignored) {
                    // 权限被回收后 removeUpdates 会抛异常，忽略即可
                }
                Log.i(TAG, "已退订 " + provider);
            }
            activeProviders.clear();
            locationManager = null;
        }
    }

    /** 定位数据长时间不来时，把车速标为无效（避免界面一直显示旧值）。 */
    private final Runnable staleCheck = new Runnable() {
        @Override public void run() {
            if (!started) return;
            if (lastFixMs > 0 && System.currentTimeMillis() - lastFixMs > STALE_MS && listener != null) {
                Log.w(TAG, "超过 " + STALE_MS + "ms 没有新定位，标记为无效");
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
            Log.w(TAG, "提供方被关闭: " + provider);
            lastFixMs = 0;
            kmh = 0f;
            if (listener != null) listener.onSpeed(0f, false);
        }
    };

    private void onLocation(Location location) {
        if (location == null) return;
        boolean fromSpeedField = location.hasSpeed();
        float raw = fromSpeedField
                ? location.getSpeed() * 3.6f
                : fallbackSpeedKmh(location);
        lastForDelta = location;
        lastFixMs = System.currentTimeMillis();
        kmh = filter(kmh, raw, 1000f);
        if (fixCount < 5 || fixCount % 20 == 0) {
            Log.i(TAG, "定位#" + fixCount + " provider=" + location.getProvider()
                    + " 来源=" + (fromSpeedField ? "getSpeed" : "位移估算")
                    + " 原始=" + raw + "km/h 平滑后=" + kmh
                    + " 精度=" + (location.hasAccuracy() ? location.getAccuracy() + "m" : "未知"));
        }
        fixCount++;
        if (listener != null) listener.onSpeed(kmh, true);
    }

    /**
     * 设备不提供 getSpeed 时的退路：用相邻两次定位的大圆距离除以时间间隔。
     * 精度太差的点直接跳过，否则经纬度漂移会被算成几十公里的速度。
     */
    private float fallbackSpeedKmh(Location location) {
        Location previous = lastForDelta;
        if (previous == null) return 0f;
        long dtMs = location.getTime() - previous.getTime();
        if (dtMs < 300L || dtMs > 10000L) return kmh;
        if (location.hasAccuracy() && location.getAccuracy() > MAX_ACCURACY_M) return kmh;
        double meters = distanceMeters(previous, location);
        return (float) (meters / (dtMs / 1000.0) * 3.6);
    }

    /** 两点间大圆距离（米），Haversine 公式。 */
    static double distanceMeters(Location a, Location b) {
        double lat1 = Math.toRadians(a.getLatitude()), lat2 = Math.toRadians(b.getLatitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.getLongitude() - a.getLongitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371000.0 * Math.asin(Math.min(1.0, Math.sqrt(h)));
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
