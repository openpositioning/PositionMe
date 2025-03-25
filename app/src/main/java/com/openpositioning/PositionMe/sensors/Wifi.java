package com.openpositioning.PositionMe.sensors;

import com.openpositioning.PositionMe.presentation.fragment.MeasurementsFragment;
import java.util.Date;

/**
 * The Wifi object holds the Wifi parameters listed below.
 *
 * It contains the ssid (the identifier of the wifi), bssid (the mac address of the wifi), level
 * (the strength of the wifi in dB) and frequency (the frequency of the wifi network (2.4GHz or
 * 5GHz). For most objects only the bssid and the level are set.
 *
 * @author Virginia Cangelosi
 * @author Mate Stodulka
 */
public class Wifi {
    private String ssid;
    private long bssid;
    private int level;
    private long frequency;
    private Date timestamp;
    private int channel;
    private String capabilities;
    private boolean isConnected;
    private double distance;

    /**
     * Empty public default constructor of the Wifi object.
     */
    public Wifi(){
        this.timestamp = new Date();
    }
    
    /**
     * Constructor with bssid and level parameters
     */
    public Wifi(long bssid, int level) {
        this.bssid = bssid;
        this.level = level;
        this.timestamp = new Date();
    }

    /**
     * Getters for each property
     */
    public String getSsid() { return ssid; }
    public long getBssid() { return bssid; }
    public int getLevel() { return level; }
    public long getFrequency() { return frequency; }
    public Date getTimestamp() { return timestamp; }
    public int getChannel() { return channel; }
    public String getCapabilities() { return capabilities; }
    public boolean isConnected() { return isConnected; }
    public double getDistance() { return distance; }

    /**
     * Setters for each property
     */
    public void setSsid(String ssid) { this.ssid = ssid; }
    public void setBssid(long bssid) { this.bssid = bssid; }
    public void setLevel(int level) { this.level = level; }
    public void setFrequency(long frequency) { 
        this.frequency = frequency;
        this.channel = calculateChannel(frequency);
    }
    public void setTimestamp(Date timestamp) { this.timestamp = timestamp; }
    public void setCapabilities(String capabilities) { this.capabilities = capabilities; }
    public void setConnected(boolean connected) { isConnected = connected; }
    
    /**
     * 计算WiFi信号对应的信道
     */
    private int calculateChannel(long frequency) {
        if (frequency >= 2412 && frequency <= 2484) {
            return (int)((frequency - 2412) / 5 + 1);
        } else if (frequency >= 5170 && frequency <= 5825) {
            return (int)((frequency - 5170) / 5 + 34);
        } else {
            return 0;
        }
    }
    
    /**
     * 根据RSSI估算距离
     * 使用对数距离路径损耗模型
     * @param txPower 发射功率，通常为-59dBm到-65dBm
     * @return 估计距离（米）
     */
    public double calculateDistance(int txPower) {
        if (level == 0) {
            return -1.0;
        }
        
        double ratio = (double)(txPower - level)/(10 * 2.0);
        this.distance = Math.pow(10, ratio);
        return this.distance;
    }
    
    /**
     * 判断WiFi信号强度质量
     * @return 信号质量描述
     */
    public String getSignalQuality() {
        if (level >= -50) return "极好";
        else if (level >= -60) return "很好";
        else if (level >= -70) return "好";
        else if (level >= -80) return "一般";
        else return "弱";
    }

    /**
     * Generates a string containing mac address and rssi of Wifi.
     *
     * Concatenates mac address and rssi to display in the
     * {@link MeasurementsFragment} fragment
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (ssid != null && !ssid.isEmpty()) {
            sb.append("SSID: ").append(ssid).append(", ");
        }
        sb.append("bssid: ").append(bssid)
          .append(", level: ").append(level);
        
        if (frequency > 0) {
            sb.append(", 频率: ").append(frequency).append("MHz");
        }
        
        return sb.toString();
    }
    
    /**
     * 比较两个WiFi对象是否表示同一个接入点
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Wifi wifi = (Wifi) obj;
        return bssid == wifi.bssid;
    }
    
    @Override
    public int hashCode() {
        return (int) (bssid ^ (bssid >>> 32));
    }
}
