package com.openpositioning.PositionMe;

import android.hardware.SensorManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.FileInputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

import com.google.protobuf.util.JsonFormat;
import com.google.android.gms.maps.model.LatLng;

public class ReplayDataProcessor {


    public static class TrajRecorder extends ReplayDataProcessor {
        private static final TrajRecorder INSTANCE = new TrajRecorder();

        private TrajRecorder() {
        }

        public static TrajRecorder getInstance() {
            return INSTANCE;
        }

        private Traj.Trajectory replayTraj;

        private final List<String> trajectoryPoints = new ArrayList<>();

        public Traj.Trajectory getReplayTraj() {
            return replayTraj;
        }

        public void setReplayFile(Traj.Trajectory replayTraj) {
            this.replayTraj = replayTraj;
        }

        public List<String> getTrajectoryPoints() {
            return trajectoryPoints;
        }

        public void addTrajectoryPoint(String point) {
            trajectoryPoints.add(point);
        }
    }

    // A simple method to check if a file seems to be text.
    // This example returns false if a null byte is found.
    public static boolean isTextFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            byte[] fileBytes = Files.readAllBytes(path);
            for (byte b : fileBytes) {
                if (b == 0) { // A null byte is a common sign of binary data
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }



    //    public static void protoBinDecoder(String filePath) {
    public static Traj.Trajectory protoDecoder(File file) {
//        String filePath_sf = filePath.toString();
        String filePath = file.getAbsolutePath();

        Traj.Trajectory trajectory = null;

        if (isTextFile(filePath)) { // plain text format
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            Traj.Trajectory.Builder trajBuilder = Traj.Trajectory.newBuilder();
            try {
                // Decrypt the text format data into the builder
//                TextFormat.merge(sb.toString(), trajBuilder);
                JsonFormat.parser().merge(sb.toString(), trajBuilder);
                trajectory = trajBuilder.build();
//                System.out.println("Decoded message: " + trajectory.toString()); // test line
                trajProcessing(trajectory);
//            } catch (TextFormat.ParseException e) {
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        else { // bin format
//            File file = new File(filePath);
//            try (FileInputStream fis = new FileInputStream(file)) {
            try (FileInputStream fis = new FileInputStream(file)) {
                // use the parseFrom() method of Traj.Trajectory to parse binary data
                trajectory = Traj.Trajectory.parseFrom(fis);
//                System.out.println("Decoded message: " + trajectory.toString()); // test line
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return trajectory;
    }

    public static float[] getStartLocation(Traj.Trajectory trajectory) {

        float[] startLocation = new float[2];

        if (trajectory == null){
            throw new IllegalArgumentException("Trajectory cannot be null");
        }

        if (!trajectory.hasStartPosition()) {
            System.err.println("Cannot resolve start position");
//            startLocation = new float[] {0, 0};
//            return startLocation;
            throw new IllegalArgumentException("Cannot resolve start position for trajectory");
        }

        Traj.Lat_Long_Position startPosition = trajectory.getStartPosition();
        float startLat = startPosition.getLat();
        float startLong = startPosition.getLong();
//            System.out.println("Start position: " + startLat + ", " + startLong);

        startLocation[0] = startLat;
        startLocation[1] = startLong;

        return startLocation;
    }

    public static List<Traj.GNSS_Sample> getGNSSDataList (Traj.Trajectory trajectory) {
        // TODO: null handling
        List<Traj.GNSS_Sample> gnssDataList = trajectory.getGnssDataList();
        return gnssDataList;
    }


    public static List<Traj.Pdr_Sample> getPdrDataList(Traj.Trajectory trajectory) {
        // TODO: null handling
        List<Traj.Pdr_Sample> pdrDataList = trajectory.getPdrDataList();
        return pdrDataList;
    }

    public static float[] getFirstGnssLocation(Traj.Trajectory trajectory) {
        float[] startLocation = new float[2];
        if (trajectory == null){
            throw new IllegalArgumentException("Trajectory cannot be null");
        }
        if (trajectory.getGnssDataCount() == 0) {
            System.err.println("Trajectory has no GNSS data, using 0,0 instead");
            startLocation = new float[] {0, 0};
            return startLocation;
        }
        Traj.GNSS_Sample gnssData = trajectory.getGnssData(0);
        float gnssLat = gnssData.getLatitude();
        float gnssLong = gnssData.getLongitude();
        startLocation[0] = gnssLat;
        startLocation[1] = gnssLong;
        return startLocation;
    }

    public static float recalculateBaseAltitude(List<Traj.Pressure_Sample> pressureDataList) {

        if (pressureDataList == null || pressureDataList.size() < 3) {
            throw new IllegalArgumentException("List must contain at least 3 elements.");
        }

        // obtain the first three saved sample (in this ver the first 3sec of data)
        List<Float> sortedPressures = pressureDataList.stream()
                .limit(3)
                .map(Traj.Pressure_Sample::getPressure)
                .sorted()
                .collect(Collectors.toList());

        // use the medium to return the base altitude
        return SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, sortedPressures.get(1));
    }

    public static List<LatLng> translatePdrPath(Traj.Trajectory trajectory) {
        List<LatLng> latLngList = new ArrayList<>();
        LatLng startLocation = new LatLng(0, 0);

        try {
            float[] start = getStartLocation(trajectory);
            startLocation = new LatLng(start[0], start[1]);
            latLngList.add(startLocation);
        }
        catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error getting start location, trying to use GNSS instead");
            float[] startLatLong = getFirstGnssLocation(trajectory);
            startLocation = new LatLng(startLatLong[0], startLatLong[1]);
            latLngList.add(startLocation); // Temp solution
        }

        List<Traj.Pdr_Sample> pdrDataList = trajectory.getPdrDataList();

        for (Traj.Pdr_Sample data : pdrDataList) {
            float[] pdrMoved = {data.getX(), data.getY()};
            LatLng newLocation = UtilFunctions.calculateNewPos(startLocation, pdrMoved);
            latLngList.add(newLocation);
        }

        return latLngList;
    }

    public static List<Traj.Pressure_Sample> pressureSampleAdapter(List<Traj.Pressure_Sample> PressureDataList) {
        if (PressureDataList == null || PressureDataList.isEmpty()) {
            return PressureDataList;
        }

        float baseAltitude = calculateAltitude(PressureDataList.get(0));
        List<Traj.Pressure_Sample> updatedList = new ArrayList<>();

        for (Traj.Pressure_Sample pressureSample : PressureDataList) {
            float altitude = calculateAltitude(pressureSample);
            Traj.Pressure_Sample.Builder builder = pressureSample.toBuilder();
            builder.setEstimatedElevation(altitude - baseAltitude);
            updatedList.add(builder.build()); // generate new object
        }

        return updatedList; // return new list
    }

    public static float calculateAltitude(Traj.Pressure_Sample pressureSample) {
        float pressure = pressureSample.getPressure();
        return SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure);
    }

    public static boolean hasEstimatedAltitude(Traj.Trajectory trajectory) {
        if (trajectory == null) {
            throw new IllegalArgumentException("Trajectory cannot be null");
        }
        for (Traj.Pressure_Sample pressureSample : trajectory.getPressureDataList()) {
            if (pressureSample.hasField(Traj.Pressure_Sample.getDescriptor().findFieldByNumber(3))) {
                return true;
            }
        }
        return false;
    }

    public static List<Traj.Pressure_Sample> getPressureDataList(Traj.Trajectory trajectory) {
        if (trajectory == null) {
            throw new IllegalArgumentException("Trajectory cannot be null");
        }
        List<Traj.Pressure_Sample> pressureDataList = trajectory.getPressureDataList();
        if (!ReplayDataProcessor.hasEstimatedAltitude(trajectory)) {
            pressureDataList = ReplayDataProcessor.pressureSampleAdapter(pressureDataList);
        }
        return pressureDataList;
    }

    public static boolean hasOrientationData(Traj.Trajectory trajectory) {
        if (trajectory == null) {
            throw new IllegalArgumentException("Trajectory cannot be null");
        }
        for (Traj.Motion_Sample motionSample : trajectory.getImuDataList()) {
            if (motionSample.hasField(Traj.Motion_Sample.getDescriptor().findFieldByNumber(13))) {
                return true;
            }
        }
        return false;
    }

    public static float calculateAzimuth(Traj.Motion_Sample motionSample) {
        float rx = motionSample.getRotationVectorX();
        float ry = motionSample.getRotationVectorY();
        float rz = motionSample.getRotationVectorZ();
        float rw = motionSample.getRotationVectorW();

        float[] rotationVector = {rx, ry, rz, rw};
        float[] orientation = new float[3];

        float[] rotationVectorDCM = new float[9];
        SensorManager.getRotationMatrixFromVector(rotationVectorDCM, rotationVector);
        SensorManager.getOrientation(rotationVectorDCM, orientation);

        return orientation[0];
    }

    public static List<Traj.Motion_Sample> MotionSampleAdapter(List<Traj.Motion_Sample> motionDataList) {
        if (motionDataList == null || motionDataList.isEmpty()) {
            return motionDataList;
        }

        List<Traj.Motion_Sample> updatedList = new ArrayList<>(); // 存储修改后的对象

        for (Traj.Motion_Sample motionSample : motionDataList) {
            float azimuth = calculateAzimuth(motionSample);
            Traj.Motion_Sample.Builder builder = motionSample.toBuilder();
            builder.setAzimuth(azimuth);
//            System.out.println("Azimuth: " + azimuth);
            updatedList.add(builder.build());
        }
        System.out.println("MotionSampleAdapter: " + updatedList);

        return updatedList;
    }

    public static List<Traj.Motion_Sample> getMotionDataList(Traj.Trajectory trajectory) {
        if (trajectory == null) {
            throw new IllegalArgumentException("Trajectory cannot be null");
        }
        List<Traj.Motion_Sample> motionDataList = trajectory.getImuDataList();
        if (!ReplayDataProcessor.hasOrientationData(trajectory)) {
            System.out.println("No orientation data found in the trajectory");
            motionDataList = ReplayDataProcessor.MotionSampleAdapter(motionDataList);
        }
        return motionDataList;
    }

    /**
     * test method
     * @param trajectory
     */
    public static void trajProcessing(Traj.Trajectory trajectory) {
//        Traj.Lat_Long_Position startPosition = trajectory.getStartPosition();
//        Traj.Motion_Sample motionData = trajectory.getImuData(0);
//        int result = motionData.getStepCount();
//        System.out.println("Step count: " + result);

    }
}