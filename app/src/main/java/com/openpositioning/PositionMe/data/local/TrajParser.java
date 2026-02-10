package com.openpositioning.PositionMe.data.local;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.openpositioning.PositionMe.Traj;
/**
 * TrajParser (Updated for Assignment 1 / Proto v2)
 */
public class TrajParser {

    private static final String TAG = "TrajParser";
    private Traj.Trajectory trajectory;

    public TrajParser(Traj.Trajectory trajectory) {
        this.trajectory = trajectory;
    }

    public static class ReplayPoint {
        public LatLng pdrLocation;
        public float orientation;
        public LatLng gnssLocation;

        public ReplayPoint(LatLng pdr, float ori, LatLng gnss) {
            this.pdrLocation = pdr;
            this.orientation = ori;
            this.gnssLocation = gnss;
        }
    }

    public static List<ReplayPoint> parseTrajectoryData(String filePath, Context context, float startLat, float startLon) {
        List<ReplayPoint> replayPoints = new ArrayList<>();
        File file = new File(filePath);

        if (!file.exists()) {
            Log.e(TAG, "File not found: " + filePath);
            return replayPoints;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            Traj.Trajectory traj = Traj.Trajectory.parseFrom(fis);
            List<Traj.RelativePosition> pdrList = traj.getPdrDataList();
            List<Traj.GNSSReading> gnssList = traj.getGnssDataList();
            
            Log.d(TAG, "Parsing trajectory: PDR points=" + pdrList.size() + ", GNSS points=" + gnssList.size());

            // STRATEGY: Prefer PDR data for accurate step-based trajectory display (Red Line)
            // If PDR data exists, use it as primary.
            // Map GNSS data (Blue Dots) to the closest PDR point based on timestamp.

            if (!pdrList.isEmpty()) {
                Log.d(TAG, "Using PDR data as primary trajectory (Fixed Priority)");
                
                // Calculate meters per degree for this latitude (approximation)
                double metersPerDegLat = 111132.954 - 559.822 * Math.cos(2 * startLat * Math.PI / 180);
                double metersPerDegLon = 111412.84 * Math.cos(startLat * Math.PI / 180);

                int gnssIndex = 0;

                for (int i = 0; i < pdrList.size(); i++) {
                    Traj.RelativePosition pdr = pdrList.get(i);
                    
                    // 1. Calculate Lat/Lon from PDR X/Y
                    double deltaLat = pdr.getY() / metersPerDegLat; // y is North/South
                    double deltaLon = pdr.getX() / metersPerDegLon; // x is East/West
                    LatLng currentPdrLatLng = new LatLng(startLat + deltaLat, startLon + deltaLon);

                    // 2. Calculate Orientation
                    float orientation = 0f;
                    if (i > 0) {
                        Traj.RelativePosition prev = pdrList.get(i - 1);
                        double dx = pdr.getX() - prev.getX();
                        double dy = pdr.getY() - prev.getY();
                        orientation = (float) Math.toDegrees(Math.atan2(dx, dy));
                    }

                    // 3. Find matching GNSS point (closest in time, assuming sorted)
                    LatLng currentGnssLatLng = null;
                    if (!gnssList.isEmpty()) {
                        long pdrTime = pdr.getRelativeTimestamp();
                        // Advance gnssIndex to find closest
                        while (gnssIndex < gnssList.size() - 1) {
                            Traj.GNSSReading curr = gnssList.get(gnssIndex);
                            Traj.GNSSReading next = gnssList.get(gnssIndex + 1);
                            if (Math.abs(next.getPosition().getRelativeTimestamp() - pdrTime) < 
                                Math.abs(curr.getPosition().getRelativeTimestamp() - pdrTime)) {
                                gnssIndex++;
                            } else {
                                break;
                            }
                        }
                        // Check if within reasonable time window (e.g. 2 seconds)
                        Traj.GNSSReading bestGnss = gnssList.get(gnssIndex);
                        if (Math.abs(bestGnss.getPosition().getRelativeTimestamp() - pdrTime) < 2000) {
                            currentGnssLatLng = new LatLng(
                                bestGnss.getPosition().getLatitude(),
                                bestGnss.getPosition().getLongitude()
                            );
                        }
                    }

                    replayPoints.add(new ReplayPoint(currentPdrLatLng, orientation, currentGnssLatLng));
                }
                Log.d(TAG, "Created " + replayPoints.size() + " replay points from PDR data");
                
            } else if (!gnssList.isEmpty()) {
                // Fallback: No PDR data, use GNSS
                Log.d(TAG, "No PDR data, falling back to GNSS as primary trajectory");
                for (int i = 0; i < gnssList.size(); i++) {
                    Traj.GNSSReading gnss = gnssList.get(i);
                    LatLng gnssLatLng = new LatLng(
                        gnss.getPosition().getLatitude(), 
                        gnss.getPosition().getLongitude()
                    );
                    
                    float orientation = gnss.getBearing();
                    if (i > 0 && orientation == 0) {
                        Traj.GNSSReading prev = gnssList.get(i - 1);
                        double dx = gnss.getPosition().getLongitude() - prev.getPosition().getLongitude();
                        double dy = gnss.getPosition().getLatitude() - prev.getPosition().getLatitude();
                        orientation = (float) Math.toDegrees(Math.atan2(dx, dy));
                    }
                    
                    replayPoints.add(new ReplayPoint(gnssLatLng, orientation, gnssLatLng));
                }
                Log.d(TAG, "Created " + replayPoints.size() + " replay points from GNSS data");
            } else {
                Log.e(TAG, "No PDR or GNSS data found in trajectory!");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error parsing trajectory file", e);
        }
        return replayPoints;
    }

    public List<Object[]> parse(SensorTypes type) {
        List<Object[]> dataList = new ArrayList<>();
        if (trajectory == null) return dataList;

        // Key fix: ensure switch case uses enum constant names, not fully qualified names
        switch (type) {
            case ACCELEROMETER:
                for (Traj.IMUReading r : trajectory.getImuDataList()) {
                    if (r.hasAcc()) dataList.add(new Object[]{r.getRelativeTimestamp(), r.getAcc().getX(), r.getAcc().getY(), r.getAcc().getZ()});
                }
                break;
            case GYRO:
                for (Traj.IMUReading r : trajectory.getImuDataList()) {
                    if (r.hasGyr()) dataList.add(new Object[]{r.getRelativeTimestamp(), r.getGyr().getX(), r.getGyr().getY(), r.getGyr().getZ()});
                }
                break;
            case GNSSLATLONG:
                for (Traj.GNSSReading r : trajectory.getGnssDataList()) {
                    if (r.hasPosition()) dataList.add(new Object[]{r.getPosition().getRelativeTimestamp(), r.getPosition().getLatitude(), r.getPosition().getLongitude()});
                }
                break;
            // Assuming WIFI exists in SensorTypes
            case WIFI:
                for (Traj.Fingerprint fp : trajectory.getWifiFingerprintsList()) {
                    dataList.add(new Object[]{fp.getRelativeTimestamp(), (float) fp.getRfScansCount()});
                }
                break;
            case PRESSURE:
                for (Traj.BarometerReading r : trajectory.getPressureDataList()) {
                    dataList.add(new Object[]{r.getRelativeTimestamp(), r.getPressure()});
                }
                break;
            case LIGHT:
                for (Traj.LightReading r : trajectory.getLightDataList()) {
                    dataList.add(new Object[]{r.getRelativeTimestamp(), r.getLight()});
                }
                break;
            case PDR:
                for (Traj.RelativePosition r : trajectory.getPdrDataList()) {
                    dataList.add(new Object[]{r.getRelativeTimestamp(), r.getX(), r.getY()});
                }
                break;
        }
        return dataList;
    }

    public long getStartTimestamp() {
        return trajectory != null ? trajectory.getStartTimestamp() : 0;
    }
}