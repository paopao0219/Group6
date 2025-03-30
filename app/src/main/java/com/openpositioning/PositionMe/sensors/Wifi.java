package com.openpositioning.PositionMe.sensors;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Wifi object holds the Wifi parameters listed below.
 *
 * It contains the ssid (the identifier of the wifi), bssid (the mac address of the wifi), level
 * (the strength of the wifi in dB) and frequency (the frequency of the wifi network (2.4GHz or
 * 5GHz). For most objects only the bssid and the level are set.
 *
 * 增加了异常值标记，用于异常值检测
 *
 * @author Virginia
 * @author Mate
 */
public class Wifi {
    private String ssid;
    private long bssid;
    private int level;
    private long frequency;
    private boolean isOutlier; // 新增：是否为异常值

    /**
     * Empty public default constructor of the Wifi object.
     */
    public Wifi(){}

    /**
     * Getters for each property
     */
    public String getSsid() { return ssid; }
    public long getBssid() { return bssid; }
    public int getLevel() { return level; }
    public long getFrequency() { return frequency; }
    public boolean isOutlier() { return isOutlier; }

    /**
     * Setters for each property
     */
    public void setSsid(String ssid) { this.ssid = ssid; }
    public void setBssid(long bssid) { this.bssid = bssid; }
    public void setLevel(int level) { this.level = level; }
    public void setFrequency(long frequency) { this.frequency = frequency; }
    public void setOutlier(boolean isOutlier) { this.isOutlier = isOutlier; }

    /**
     * Generates a string containing mac address and rssi of Wifi.
     *
     * If the signal is marked as an outlier, it appends a notice.
     */
    @Override
    public String toString() {
        String outlierStr = isOutlier ? " [Outlier]" : "";
        return  "bssid: " + bssid + ", level: " + level + outlierStr;
    }
}
