package com.openpositioning.PositionMe.sensors;

import android.net.wifi.ScanResult;

import com.openpositioning.PositionMe.presentation.fragment.MeasurementsFragment;

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

    /**
     * Empty public default constructor of the Wifi object.
     */
    public Wifi(){}

    public Wifi(ScanResult scanResult){
      this.setBssid(convertBssidToLong(scanResult.BSSID));
      this.setLevel(scanResult.level);
    }

    /**
     * Getters for each property
     */
    public String getSsid() { return ssid; }
    public long getBssid() { return bssid; }
    public int getLevel() { return level; }
    public long getFrequency() { return frequency; }

    /**
     * Setters for each property
     */
    public void setSsid(String ssid) { this.ssid = ssid; }
    public void setBssid(long bssid) { this.bssid = bssid; }
    public void setLevel(int level) { this.level = level; }
    public void setFrequency(long frequency) { this.frequency = frequency; }

    /**
     * Generates a string containing mac address and rssi of Wifi.
     *
     * Concatenates mac address and rssi to display in the
     * {@link MeasurementsFragment} fragment
     */
    @Override
    public String toString() {
        return  "bssid: " + bssid +", level: " + level;
    }

    private long convertBssidToLong(String wifiMacAddress){
      long intMacAddress =0;
      int colonCount =5;
      //Loop through each character
      for(int j =0; j<17; j++){
        //Identify character
        char macByte = wifiMacAddress.charAt(j);
        //convert string hex mac address with colons to decimal long integer
        if(macByte != ':'){
          //For characters 0-9 subtract 48 from ASCII code and multiply by 16^position
          if((int) macByte >= 48 && (int) macByte <= 57){
            intMacAddress = intMacAddress + (((int)macByte-48)*((long)Math.pow(16,16-j-colonCount)));
          }

          //For characters a-f subtract 87 (=97-10) from ASCII code and multiply by 16^index
          else if ((int) macByte >= 97 && (int) macByte <= 102){
            intMacAddress = intMacAddress + (((int)macByte-87)*((long)Math.pow(16,16-j-colonCount)));
          }
        }
        else
          //coloncount is used to obtain the index of each character
          colonCount --;
      }

      return intMacAddress;
    }
}
