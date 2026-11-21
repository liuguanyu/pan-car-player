package com.baidu.carplayer.network;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Parcelable;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 车机 ↔ 手机热点的重连工具。
 *
 * <p>车机不联网、靠手机热点。手机离线后热点断开，系统不会自动重连。
 * 本类的策略是「档 2」：把热点以 {@link WifiNetworkSuggestion} 注册给系统
 * （需要 SSID + 密码，密码由用户配置一次并存在 SharedPreferences），
 * 之后系统在热点范围内会自己加入，App 无需常驻。
 *
 * <p>兜底：注册建议 + 尽力 reconnect 之后若仍没连上，调用方打开系统 Wi-Fi 面板
 * （Settings.Panel.ACTION_WIFI），用户点一下即连。
 */
public final class HotspotManager {
    private static final String TAG = "HotspotManager";
    private static final String PREFS = "hotspot";
    private static final String KEY_SSID = "ssid";
    private static final String KEY_PASS = "pass";

    private HotspotManager() {}

    // ---------- 纯逻辑（可单测） ----------

    /**
     * 校验 SSID 和密码。WPA/WPA2 热点密码 8~63 字符。
     *
     * @return null 表示通过；否则返回给用户的错误文案
     */
    @SuppressLint("CheckResult")
    public static String validate(String ssid, String pass) {
        if (ssid == null || ssid.isEmpty()) return "热点名称（SSID）不能为空";
        if (ssid.length() > 32) return "热点名称过长";
        if (pass == null || pass.length() < 8) return "热点密码至少 8 位";
        if (pass.length() > 63) return "热点密码过长（最多 63 位）";
        return null;
    }

    /** 去掉 Android 对未连接 SSID 加的空引号。 */
    public static String cleanSsid(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s.isEmpty() ? null : s;
    }

    // ---------- 持久化 ----------

    public static String loadSsid(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SSID, null);
    }

    public static String loadPass(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PASS, null);
    }

    public static void save(Context context, String ssid, String pass) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SSID, ssid)
                .putString(KEY_PASS, pass)
                .apply();
    }

    public static boolean hasConfig(Context context) {
        return loadSsid(context) != null && loadPass(context) != null;
    }

    // ---------- 系统交互 ----------

    /** 当前是否连着 Wi-Fi。 */
    public static boolean isWifiConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info;
        if (Build.VERSION.SDK_INT >= 23) {
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return false;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            info = cm.getActiveNetworkInfo();
            return info != null && info.getType() == ConnectivityManager.TYPE_WIFI && info.isConnected();
        }
    }

    /** 当前连接的 SSID；未连接或读不到时返回 null。 */
    public static String currentSsid(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return null;
            android.net.wifi.WifiInfo info = wifi.getConnectionInfo();
            return info == null ? null : cleanSsid(info.getSSID());
        } catch (Throwable e) {
            // 没有定位权限时读 SSID 会抛 SecurityException；不影响其它功能
            Log.d(TAG, "currentSsid: " + e.getMessage());
            return null;
        }
    }

    /**
     * 把热点注册为系统建议（档 2 的核心）。先删旧建议再添加，保证密码更新生效。
     *
     * @return 是否成功提交给系统（提交成功 ≠ 立刻连上）
     */
    public static boolean suggest(Context context, String ssid, String pass) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return false;
            WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid);
            // 密码非空时注册为 WPA2；不注册开放网络的建议，避免误连
            if (pass != null && !pass.isEmpty()) {
                builder.setWpa2Passphrase(pass);
            } else {
                return false;
            }
            List<WifiNetworkSuggestion> all = new ArrayList<>();
            WifiNetworkSuggestion s = builder.build();
            all.add(s);
            // 先删后加：WifiNetworkSuggestion 是 Parcelable，用 removeNetworkSuggestionsById 不可用，
            // 直接 add 会按 id 覆盖
            wifi.addNetworkSuggestions(all);
            Log.i(TAG, "已注册热点建议: " + ssid);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "addNetworkSuggestions 失败: " + e);
            return false;
        }
    }

    /** 删掉我们注册过的建议（密码改错时用）。removeNetworkSuggestions 按 id 匹配，需要重建同一个建议。 */
    public static void removeSuggestion(Context context, String ssid, String pass) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            List<WifiNetworkSuggestion> all = new ArrayList<>();
            all.add(new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid).setWpa2Passphrase(pass).build());
            wifi.removeNetworkSuggestions(all);
        } catch (Throwable e) {
            Log.w(TAG, "removeNetworkSuggestions 失败: " + e);
        }
    }

    /**
     * 尽力重连（档 3，best-effort）：部分 ROM 上 reconnect 是空操作，
     * 失败与否都不阻塞——调用方 2.5 秒后检查状态，没连上就打开系统面板兜底。
     */
    public static void tryReconnect(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) wifi.reconnect();
        } catch (Throwable e) {
            Log.w(TAG, "reconnect 失败: " + e);
        }
    }

    /** 注册建议 + 重连完成后的回调（主线程）。 */
    public interface SuggestCallback { void onResult(boolean registered); }

    private static final java.util.concurrent.Executor EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "hotspot-wifi");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    /**
     * 后台执行 suggest + reconnect，完成后回主线程回调。
     * 车机 ROM 上 Wi-Fi 服务可能被深度定制，binder 调用可能抛非 RuntimeException
     * （如类被 stub 的 LinkageError）或长时间阻塞，因此绝不能在主线程直接做。
     */
    public static void suggestAsync(final Context context, final String ssid, final String pass,
                                    final android.os.Handler main, final SuggestCallback callback) {
        EXEC.execute(() -> {
            boolean ok = false;
            try {
                ok = suggest(context, ssid, pass);
                tryReconnect(context);
            } catch (Throwable t) {
                Log.w(TAG, "后台 Wi-Fi 操作失败: " + t);
            }
            final boolean r = ok;
            main.post(() -> {
                if (callback != null) callback.onResult(r);
            });
        });
    }

    /** 打开系统 Wi-Fi 面板（Android 10+ 用 Panel，更早用设置页）。 */
    public static void openWifiPanel(Context context) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 29) {
            intent = new Intent(Settings.Panel.ACTION_WIFI);
        } else {
            intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Throwable e) {
            // 极少数 ROM 没实现 Panel，退回设置页
            if (Build.VERSION.SDK_INT >= 29) {
                context.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } else {
                Log.w(TAG, "打开 Wi-Fi 设置失败: " + e);
            }
        }
    }
}
