package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Handles Wi-Fi scanning, constructs fingerprint JSON, and calls WiFiPositioning.
 * 额外增加了：
 *  - Outlier detection：对比上次 RSSI，如跳变过大则视为异常过滤掉；
 *  - No coverage detection：如果有效样本数少于 3，则认为覆盖不足，不发送请求。
 */
public class WifiDataProcessor implements Observable {

    // 扫描间隔改为 30 秒，避免 WiFi Throttling（注意：开发者选项中最好禁用该功能）
    private static final long SCAN_INTERVAL = 30000;
    private final Context context;
    private final WifiManager wifiManager;
    private Wifi[] wifiData;
    private ArrayList<Observer> observers;
    private Timer scanWifiDataTimer;

    // 用于简单的 RSSI 异常值检测：记录每个 AP 上一次的 RSSI
    private final ArrayList<String> validMacs = new ArrayList<>();
    private final ArrayList<Integer> lastRssiList = new ArrayList<>();
    // 定义 RSSI 最大跳变阈值
    private static final int RSSI_OUTLIER_THRESHOLD = 30;

    public WifiDataProcessor(Context context) {
        this.context = context;
        boolean permissionsGranted = checkWifiPermissions();
        this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        this.scanWifiDataTimer = new Timer();
        this.observers = new ArrayList<>();

        if (permissionsGranted) {
            this.scanWifiDataTimer.schedule(new ScheduledWifiScan(), 0, SCAN_INTERVAL);
        }
        checkWifiThrottling();
    }

    BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                stopListening();
                return;
            }
            List<ScanResult> scanResults = wifiManager.getScanResults();
            ctx.unregisterReceiver(this);

            wifiData = new Wifi[scanResults.size()];
            for (int i = 0; i < scanResults.size(); i++) {
                ScanResult sr = scanResults.get(i);
                Wifi w = new Wifi();
                w.setBssidString(sr.BSSID);
                w.setBssid(convertBssidToLong(sr.BSSID));
                w.setLevel(sr.level);
                w.setSsid(sr.SSID);
                w.setFrequency(sr.frequency);
                wifiData[i] = w;
            }

            // 构造指纹 JSON
            JSONObject fingerprint = buildFingerprintJSON(wifiData);
            if (fingerprint == null) {
                Log.e("WifiDataProcessor", "No valid WiFi data to send. Coverage insufficient.");
                Toast.makeText(context, "无足够WiFi覆盖，请确认至少检测到3个有效AP", Toast.LENGTH_SHORT).show();
                return;
            }

            Log.d("WifiDataProcessor", "Fingerprint JSON: " + fingerprint.toString());

            // 调用 WiFiPositioning（发送 REST 请求）
            WiFiPositioning positioning = new WiFiPositioning(context);
            positioning.request(fingerprint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(com.google.android.gms.maps.model.LatLng location, int floor) {
                    Toast.makeText(context, "定位成功: (" + location.latitude + ", " + location.longitude + "), floor=" + floor, Toast.LENGTH_LONG).show();
                }

                @Override
                public void onError(String message) {
                    Toast.makeText(context, "定位错误: " + message, Toast.LENGTH_LONG).show();
                }
            });

            notifyObservers(0);
        }
    };

    /**
     * 构造 fingerprint JSON。
     * 格式：{
     *   "radio": "wifi",
     *   "samples": [ { "mac": "xx:xx:xx:xx:xx:xx", "rssi": -60 }, ... ],
     *   "timestamp": 1234567890
     * }
     * 如果有效样本数少于 3，则返回 null（No coverage detection）。
     */
    private JSONObject buildFingerprintJSON(Wifi[] wifiArray) {
        if (wifiArray == null || wifiArray.length == 0) return null;

        JSONArray samplesArr = new JSONArray();
        // 初始化 outlier 检测数据
        // 对于每个 AP，检查与上次记录的 RSSI 是否跳变过大（简单实现：若第一次出现，则记录，否则比较差值）
        for (Wifi w : wifiArray) {
            if (w.getLevel() < -85) continue; // 忽略弱信号
            if (w.getBssidString() == null || w.getBssidString().isEmpty()) continue;

            // Outlier detection
            int currentRssi = w.getLevel();
            int index = validMacs.indexOf(w.getBssidString());
            if (index != -1) {
                int lastRssi = lastRssiList.get(index);
                if (Math.abs(currentRssi - lastRssi) > RSSI_OUTLIER_THRESHOLD) {
                    Log.w("WifiDataProcessor", "Outlier detected for " + w.getBssidString() + ": last=" + lastRssi + ", current=" + currentRssi);
                    continue; // 过滤掉跳变过大的数据
                } else {
                    // 更新记录
                    lastRssiList.set(index, currentRssi);
                }
            } else {
                validMacs.add(w.getBssidString());
                lastRssiList.add(currentRssi);
            }

            try {
                samplesArr.put(w.toJSONObject());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        // 如果有效样本数少于 3，则认为没有足够覆盖
        if (samplesArr.length() < 3) return null;

        JSONObject fingerprint = new JSONObject();
        try {
            fingerprint.put("radio", "wifi");
            fingerprint.put("samples", samplesArr);
            fingerprint.put("timestamp", System.currentTimeMillis());
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
        return fingerprint;
    }

    private long convertBssidToLong(String wifiMacAddress) {
        long intMacAddress = 0;
        int colonCount = 5;
        if (wifiMacAddress == null) return 0;
        if (wifiMacAddress.length() != 17) return 0;
        for (int j = 0; j < 17; j++) {
            char macByte = wifiMacAddress.charAt(j);
            if (macByte != ':') {
                if ((int) macByte >= 48 && (int) macByte <= 57) {
                    intMacAddress += (((int) macByte - 48) * ((long) Math.pow(16, 16 - j - colonCount)));
                } else if ((int) macByte >= 97 && (int) macByte <= 102) {
                    intMacAddress += (((int) macByte - 87) * ((long) Math.pow(16, 16 - j - colonCount)));
                }
            } else {
                colonCount--;
            }
        }
        return intMacAddress;
    }

    private boolean checkWifiPermissions() {
        int wifiAccessPermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE);
        int wifiChangePermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.CHANGE_WIFI_STATE);
        int coarseLocationPermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION);
        int fineLocationPermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION);

        return wifiAccessPermission == PackageManager.PERMISSION_GRANTED &&
                wifiChangePermission == PackageManager.PERMISSION_GRANTED &&
                coarseLocationPermission == PackageManager.PERMISSION_GRANTED &&
                fineLocationPermission == PackageManager.PERMISSION_GRANTED;
    }

    private void startWifiScan() {
        if (checkWifiPermissions()) {
            context.registerReceiver(wifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            wifiManager.startScan();
        }
    }

    public void startListening() {
        this.scanWifiDataTimer = new Timer();
        this.scanWifiDataTimer.scheduleAtFixedRate(new ScheduledWifiScan(), 0, SCAN_INTERVAL);
    }

    public void stopListening() {
        try {
            context.unregisterReceiver(wifiScanReceiver);
        } catch (IllegalArgumentException e) {
            // already unregistered
        }
        this.scanWifiDataTimer.cancel();
    }

    public void checkWifiThrottling() {
        if (checkWifiPermissions()) {
            try {
                if (android.provider.Settings.Global.getInt(context.getContentResolver(), "wifi_scan_throttle_enabled") == 1) {
                    Toast.makeText(context, "请在开发者选项中禁用 Wi-Fi 扫描节流", Toast.LENGTH_SHORT).show();
                }
            } catch (android.provider.Settings.SettingNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void registerObserver(Observer o) {
        observers.add(o);
    }

    @Override
    public void notifyObservers(int idx) {
        for (Observer ob : observers) {
            ob.update(wifiData);
        }
    }

    private class ScheduledWifiScan extends TimerTask {
        @Override
        public void run() {
            startWifiScan();
        }
    }

    public Wifi getCurrentWifiData() {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        Wifi currentWifi = new Wifi();
        if (networkInfo.isConnected()) {
            currentWifi.setSsid(wifiManager.getConnectionInfo().getSSID());
            String macStr = wifiManager.getConnectionInfo().getBSSID();
            long intMac = convertBssidToLong(macStr);
            currentWifi.setBssid(intMac);
            currentWifi.setBssidString(macStr);
            currentWifi.setFrequency(wifiManager.getConnectionInfo().getFrequency());
        } else {
            currentWifi.setSsid("Not connected");
            currentWifi.setBssid(0);
            currentWifi.setBssidString("");
            currentWifi.setFrequency(0);
        }
        return currentWifi;
    }
}
