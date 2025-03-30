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

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * The WifiDataProcessor class is the Wi-Fi data gathering and processing class.
 * 它负责启动 WiFi 扫描、构造指纹数据，并通知观察者，同时调用 RESTful 定位请求。
 *
 * 新增了异常值检测功能，并在扫描结果为空时（无覆盖）进行通知。
 *
 * @author ...
 */
public class WifiDataProcessor implements Observable {

    // 每次扫描的间隔（毫秒）
    private static final long scanInterval = 5000;
    private final Context context;
    private final WifiManager wifiManager;
    // 保存扫描到的 WiFi 数据
    private Wifi[] wifiData;
    // 观察者列表
    private ArrayList<Observer> observers;
    // 定时扫描对象
    private Timer scanWifiDataTimer;

    /**
     * Public default constructor of the WifiDataProcessor class.
     */
    public WifiDataProcessor(Context context) {
        this.context = context;
        boolean permissionsGranted = checkWifiPermissions();
        this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        this.scanWifiDataTimer = new Timer();
        this.observers = new ArrayList<>();

        if (permissionsGranted) {
            this.scanWifiDataTimer.schedule(new ScheduledWifiScan(), 0, scanInterval);
        }
        checkWifiThrottling();
    }

    /**
     * 广播接收器：接收扫描完成后的广播
     */
    BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                stopListening();
                return;
            }

            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                List<ScanResult> wifiScanList = wifiManager.getScanResults();
                if(wifiScanList == null || wifiScanList.isEmpty()){
                    // 无扫描结果，提示无覆盖
                    wifiData = new Wifi[0];
                    notifyObservers(0);
                    return;
                }

                wifiData = new Wifi[wifiScanList.size()];
                for (int i = 0; i < wifiScanList.size(); i++) {
                    wifiData[i] = new Wifi();
                    String wifiMacAddress = wifiScanList.get(i).BSSID;
                    long intMacAddress = convertBssidToLong(wifiMacAddress);
                    wifiData[i].setBssid(intMacAddress);
                    wifiData[i].setLevel(wifiScanList.get(i).level);
                }

                // 异常值检测：过滤出信号异常的条目并标记
                wifiData = filterOutliers(wifiData);

                // Notify observers of change in wifiData variable
                notifyObservers(0);

                // Unregister receiver after handling scan result
                stopListening();
            }
        }
    };

    /**
     * 将 MAC 地址从字符串转换为 long 类型
     */
    private long convertBssidToLong(String wifiMacAddress) {
        long intMacAddress = 0;
        int colonCount = 5;
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

    /**
     * 检查是否已授予所有必需的权限
     */
    private boolean checkWifiPermissions() {
        int wifiAccessPermission = ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_WIFI_STATE);
        int wifiChangePermission = ActivityCompat.checkSelfPermission(this.context, Manifest.permission.CHANGE_WIFI_STATE);
        int coarseLocationPermission = ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_COARSE_LOCATION);
        int fineLocationPermission = ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_FINE_LOCATION);
        return wifiAccessPermission == PackageManager.PERMISSION_GRANTED &&
                wifiChangePermission == PackageManager.PERMISSION_GRANTED &&
                coarseLocationPermission == PackageManager.PERMISSION_GRANTED &&
                fineLocationPermission == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 发起一次 WiFi 扫描
     */
    private void startWifiScan() {
        if (checkWifiPermissions()) {
            context.registerReceiver(wifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            wifiManager.startScan();
        }
    }

    /**
     * 开始周期性扫描 WiFi 数据
     */
    public void startListening() {
        this.scanWifiDataTimer = new Timer();
        this.scanWifiDataTimer.scheduleAtFixedRate(new ScheduledWifiScan(), 0, scanInterval);
    }

    /**
     * 停止扫描
     */
    public void stopListening() {
        try {
            context.unregisterReceiver(wifiScanReceiver);
        } catch (IllegalArgumentException e) {
            // 已经注销
        }
        this.scanWifiDataTimer.cancel();
    }

    /**
     * 检查并提示用户禁用 WiFi 扫描节流
     */
    public void checkWifiThrottling(){
        if(checkWifiPermissions()) {
            try {
                if(Settings.Global.getInt(context.getContentResolver(), "wifi_scan_throttle_enabled") == 1) {
                    Toast.makeText(context, "Disable Wi-Fi Throttling", Toast.LENGTH_SHORT).show();
                }
            } catch (Settings.SettingNotFoundException e) {
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
        for(Observer o : observers) {
            o.update(wifiData);
        }
    }

    /**
     * 定时任务，每 scanInterval 毫秒发起一次 WiFi 扫描
     */
    private class ScheduledWifiScan extends TimerTask {
        @Override
        public void run() {
            startWifiScan();
        }
    }

    /**
     * 异常值检测方法：
     * 计算所有 WiFi 信号的均值和标准差，将偏离均值超过 2 倍标准差的信号标记为异常值。
     */
    private Wifi[] filterOutliers(Wifi[] data) {
        if(data == null || data.length == 0) return data;
        double sum = 0;
        for(Wifi wifi : data) {
            sum += wifi.getLevel();
        }
        double mean = sum / data.length;
        double variance = 0;
        for(Wifi wifi : data) {
            variance += Math.pow(wifi.getLevel() - mean, 2);
        }
        double stddev = Math.sqrt(variance / data.length);
        double threshold = 2 * stddev;

        for(Wifi wifi : data) {
            if(Math.abs(wifi.getLevel() - mean) > threshold) {
                wifi.setOutlier(true);
            } else {
                wifi.setOutlier(false);
            }
        }
        return data;
    }

    /**
     * 获取当前已连接的 WiFi 信息
     */
    public Wifi getCurrentWifiData(){
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        Wifi currentWifi = new Wifi();
        if(networkInfo.isConnected()) {
            currentWifi.setSsid(wifiManager.getConnectionInfo().getSSID());
            String wifiMacAddress = wifiManager.getConnectionInfo().getBSSID();
            long intMacAddress = convertBssidToLong(wifiMacAddress);
            currentWifi.setBssid(intMacAddress);
            currentWifi.setFrequency(wifiManager.getConnectionInfo().getFrequency());
        }
        else{
            currentWifi.setSsid("Not connected");
            currentWifi.setBssid(0);
            currentWifi.setFrequency(0);
        }
        return currentWifi;
    }
}
