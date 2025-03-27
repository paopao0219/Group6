package com.openpositioning.PositionMe.sensors;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Wifi object holds WiFi parameters.
 * 为了兼容旧代码保留 long 型 bssid，同时新增 String 型 bssidString 用于发送给服务器。
 * 服务器要求发送 JSON 格式为：{ "mac": "xx:xx:xx:xx:xx:xx", "rssi": -60 }
 */
public class Wifi {
    private String ssid;
    private long bssid;          // 旧代码兼容
    private String bssidString;  // 用于 REST 请求，保留原始 MAC 地址
    private int level;           // RSSI
    private long frequency;      // WiFi 频率

    public Wifi(){}

    public String getSsid() { return ssid; }
    public long getBssid() { return bssid; }
    public String getBssidString() { return bssidString; }
    public int getLevel() { return level; }
    public long getFrequency() { return frequency; }

    public void setSsid(String ssid) { this.ssid = ssid; }
    public void setBssid(long bssid) { this.bssid = bssid; }
    public void setBssidString(String bssidString) { this.bssidString = bssidString; }
    public void setLevel(int level) { this.level = level; }
    public void setFrequency(long frequency) { this.frequency = frequency; }

    @Override
    public String toString() {
        return "Wifi{bssid(long)=" + bssid + ", level=" + level + "}";
    }

    /**
     * 将 Wifi 对象转换为 JSONObject，用于发送给 openpositioning 服务器。
     * 仅包含 "mac" 和 "rssi" 字段（符合常见接口要求）。
     */
    public JSONObject toJSONObject() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("mac", bssidString != null ? bssidString : "");
        obj.put("rssi", level);
        return obj;
    }
}
