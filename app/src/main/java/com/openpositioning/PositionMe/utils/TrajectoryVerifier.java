package com.openpositioning.PositionMe.utils;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import com.openpositioning.PositionMe.Traj;
// Utility class to verify trajectory file contents
// Use this to debug trajectory recording/playback issues
public class TrajectoryVerifier {
    private static final String TAG = "TrajectoryVerifier";

    // Verify a trajectory file and log its contents
    // @param filePath Path to the trajectory .txt file
    // @return true if file contains valid data, false otherwise
    public static boolean verifyTrajectoryFile(String filePath) {
        File file = new File(filePath);
        
        Log.d(TAG, "========== TRAJECTORY FILE VERIFICATION ==========");
        Log.d(TAG, "File: " + filePath);
        
        if (!file.exists()) {
            Log.e(TAG, "ERROR: File does not exist!");
            return false;
        }
        
        if (!file.canRead()) {
            Log.e(TAG, "ERROR: File exists but cannot be read!");
            return false;
        }
        
        Log.d(TAG, "File size: " + file.length() + " bytes");
        
        if (file.length() == 0) {
            Log.e(TAG, "ERROR: File is empty (0 bytes)!");
            return false;
        }
        
        try (FileInputStream fis = new FileInputStream(file)) {
            Traj.Trajectory traj = Traj.Trajectory.parseFrom(fis);
            
            Log.d(TAG, "---------- Parsed Data Summary ----------");
            Log.d(TAG, "Trajectory ID: " + traj.getTrajectoryId());
            Log.d(TAG, "Start Timestamp: " + traj.getStartTimestamp());
            Log.d(TAG, "Android Version: " + traj.getAndroidVersion());
            
            if (traj.hasInitialPosition()) {
                Traj.GNSSPosition initPos = traj.getInitialPosition();
                Log.d(TAG, "Initial Position: lat=" + initPos.getLatitude() + 
                      ", lon=" + initPos.getLongitude());
            }
            
            Log.d(TAG, "---------- Data Counts ----------");
            Log.d(TAG, "IMU Data: " + traj.getImuDataCount());
            Log.d(TAG, "GNSS Data: " + traj.getGnssDataCount());
            Log.d(TAG, "PDR Data: " + traj.getPdrDataCount());
            Log.d(TAG, "Magnetometer Data: " + traj.getMagnetometerDataCount());
            Log.d(TAG, "Pressure Data: " + traj.getPressureDataCount());
            Log.d(TAG, "WiFi Fingerprints: " + traj.getWifiFingerprintsCount());
            Log.d(TAG, "BLE Data: " + traj.getBleDataCount());
            Log.d(TAG, "Test Points: " + traj.getTestPointsCount());
            
            boolean hasGnss = traj.getGnssDataCount() > 0;
            boolean hasPdr = traj.getPdrDataCount() > 0;
            
            Log.d(TAG, "---------- GNSS Data Details ----------");
            if (hasGnss) {
                Log.d(TAG, "鉁?GNSS data present - " + traj.getGnssDataCount() + " points");
                
                // Show first and last GNSS points
                Traj.GNSSReading first = traj.getGnssData(0);
                Traj.GNSSReading last = traj.getGnssData(traj.getGnssDataCount() - 1);
                
                Log.d(TAG, "First GNSS point:");
                Log.d(TAG, "  Lat: " + first.getPosition().getLatitude());
                Log.d(TAG, "  Lon: " + first.getPosition().getLongitude());
                Log.d(TAG, "  Accuracy: " + first.getAccuracy() + "m");
                Log.d(TAG, "  Speed: " + first.getSpeed() + " m/s");
                Log.d(TAG, "  Bearing: " + first.getBearing() + "掳");
                
                Log.d(TAG, "Last GNSS point:");
                Log.d(TAG, "  Lat: " + last.getPosition().getLatitude());
                Log.d(TAG, "  Lon: " + last.getPosition().getLongitude());
                Log.d(TAG, "  Accuracy: " + last.getAccuracy() + "m");
                
                // Calculate distance between first and last
                double distance = calculateDistance(
                    first.getPosition().getLatitude(), 
                    first.getPosition().getLongitude(),
                    last.getPosition().getLatitude(), 
                    last.getPosition().getLongitude()
                );
                Log.d(TAG, "Distance first->last: " + String.format("%.1f", distance) + "m");
            } else {
                Log.e(TAG, "鉁?NO GNSS DATA - trajectory will not display!");
            }
            
            Log.d(TAG, "---------- PDR Data Details ----------");
            if (hasPdr) {
                Log.d(TAG, "鉁?PDR data present - " + traj.getPdrDataCount() + " points");
                
                Traj.RelativePosition firstPdr = traj.getPdrData(0);
                Traj.RelativePosition lastPdr = traj.getPdrData(traj.getPdrDataCount() - 1);
                
                Log.d(TAG, "First PDR: x=" + firstPdr.getX() + ", y=" + firstPdr.getY());
                Log.d(TAG, "Last PDR: x=" + lastPdr.getX() + ", y=" + lastPdr.getY());
                
                double pdrDistance = Math.sqrt(
                    Math.pow(lastPdr.getX() - firstPdr.getX(), 2) + 
                    Math.pow(lastPdr.getY() - firstPdr.getY(), 2)
                );
                Log.d(TAG, "PDR distance: " + String.format("%.1f", pdrDistance) + "m");
            } else {
                Log.e(TAG, "鉁?NO PDR DATA");
            }
            
            Log.d(TAG, "---------- Verdict ----------");
            if (hasGnss && hasPdr) {
                Log.d(TAG, "鉁撯湏 FILE IS VALID - Contains both GNSS and PDR data");
                Log.d(TAG, "   Trajectory should replay correctly");
            } else if (hasGnss) {
                Log.w(TAG, "鈿?FILE HAS GNSS ONLY - Trajectory will show (GNSS used)");
            } else if (hasPdr) {
                Log.w(TAG, "鈿?FILE HAS PDR ONLY - Trajectory will show (PDR used)");
            } else {
                Log.e(TAG, "鉁椻湕 FILE IS INVALID - No trajectory data!");
                Log.e(TAG, "   Recording failed or data was not saved");
            }
            
            Log.d(TAG, "==================================================");
            
            return hasGnss || hasPdr;
            
        } catch (IOException e) {
            Log.e(TAG, "ERROR reading/parsing trajectory file", e);
            Log.d(TAG, "==================================================");
            return false;
        }
    }
    
    // Calculate distance between two GPS coordinates using Haversine formula
    private static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000; // Earth radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}


