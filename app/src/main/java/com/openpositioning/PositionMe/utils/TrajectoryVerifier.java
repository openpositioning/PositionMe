package com.openpositioning.PositionMe.utils;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import com.openpositioning.PositionMe.Traj;
// Validates recorded trajectory files before replay.
// This helper logs structural information and data availability so replay
// issues can be diagnosed quickly from Logcat.
public class TrajectoryVerifier {
    private static final String TAG = "TrajectoryVerifier";

    // Verifies that a trajectory file exists, is readable, and contains usable
    // movement data for replay. The method logs parsed metadata and source-data
    // coverage to simplify troubleshooting.
    // Parameter filePath: Absolute path to a serialized trajectory file.
    // Returns: True when at least GNSS or PDR samples are present.
    public static boolean verifyTrajectoryFile(String filePath) {
        File file = new File(filePath);
        
        Log.d(TAG, "Trajectory file verification started");
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
            
            Log.d(TAG, "Parsed data summary");
            Log.d(TAG, "Trajectory ID: " + traj.getTrajectoryId());
            Log.d(TAG, "Start Timestamp: " + traj.getStartTimestamp());
            Log.d(TAG, "Android Version: " + traj.getAndroidVersion());
            
            if (traj.hasInitialPosition()) {
                Traj.GNSSPosition initPos = traj.getInitialPosition();
                Log.d(TAG, "Initial Position: lat=" + initPos.getLatitude() + 
                      ", lon=" + initPos.getLongitude());
            }
            
            Log.d(TAG, "Data counts");
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
            
            Log.d(TAG, "GNSS data details");
            if (hasGnss) {
                Log.d(TAG, "GNSS data present: " + traj.getGnssDataCount() + " points");
                
                // Log first and last GNSS samples for sanity checks.
                Traj.GNSSReading first = traj.getGnssData(0);
                Traj.GNSSReading last = traj.getGnssData(traj.getGnssDataCount() - 1);
                
                Log.d(TAG, "First GNSS point:");
                Log.d(TAG, "  Lat: " + first.getPosition().getLatitude());
                Log.d(TAG, "  Lon: " + first.getPosition().getLongitude());
                Log.d(TAG, "  Accuracy: " + first.getAccuracy() + "m");
                Log.d(TAG, "  Speed: " + first.getSpeed() + " m/s");
                Log.d(TAG, "  Bearing: " + first.getBearing() + " deg");
                
                Log.d(TAG, "Last GNSS point:");
                Log.d(TAG, "  Lat: " + last.getPosition().getLatitude());
                Log.d(TAG, "  Lon: " + last.getPosition().getLongitude());
                Log.d(TAG, "  Accuracy: " + last.getAccuracy() + "m");
                
                // Estimate path extent from first and last GNSS samples.
                double distance = calculateDistance(
                    first.getPosition().getLatitude(), 
                    first.getPosition().getLongitude(),
                    last.getPosition().getLatitude(), 
                    last.getPosition().getLongitude()
                );
                Log.d(TAG, "Distance first->last: " + String.format("%.1f", distance) + "m");
            } else {
                Log.e(TAG, "No GNSS data in file");
            }
            
            Log.d(TAG, "PDR data details");
            if (hasPdr) {
                Log.d(TAG, "PDR data present: " + traj.getPdrDataCount() + " points");
                
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
                Log.e(TAG, "No PDR data in file");
            }
            
            Log.d(TAG, "Replay verdict");
            if (hasGnss && hasPdr) {
                Log.d(TAG, "File is valid: contains both GNSS and PDR data");
                Log.d(TAG, "Trajectory should replay with full overlays");
            } else if (hasGnss) {
                Log.w(TAG, "File contains GNSS only: replay will use GNSS path");
            } else if (hasPdr) {
                Log.w(TAG, "File contains PDR only: replay will use PDR path");
            } else {
                Log.e(TAG, "File is invalid for replay: no GNSS or PDR trajectory data");
                Log.e(TAG, "Recording may have failed or data was not persisted");
            }
            
            Log.d(TAG, "Trajectory file verification finished");
            
            return hasGnss || hasPdr;
            
        } catch (IOException e) {
            Log.e(TAG, "ERROR reading/parsing trajectory file", e);
            Log.d(TAG, "Trajectory file verification finished with parser error");
            return false;
        }
    }
    
    // Computes great-circle distance between two latitude-longitude points.
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


