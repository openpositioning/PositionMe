package com.openpositioning.PositionMe.data.remote;

import androidx.annotation.NonNull;

import com.google.protobuf.InvalidProtocolBufferException;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.UploadTraj;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

final class TrajectoryUploadCompat {

    // Prevents this compatibility helper from being instantiated.
    private TrajectoryUploadCompat() {
    }

    // Converts the local trajectory object into the server byte format.
    static byte[] toServerBytes(@NonNull Traj.Trajectory source) {
        return toServerTrajectory(source).toByteArray();
    }

    // Reads a local file and converts it into uploadable server bytes.
    static byte[] localFileToServerBytes(@NonNull File localTrajectory) throws IOException {
        byte[] rawBytes = Files.readAllBytes(localTrajectory.toPath());
        try {
            return toServerBytes(Traj.Trajectory.parseFrom(rawBytes));
        } catch (InvalidProtocolBufferException currentParseError) {
            try {
                UploadTraj.Trajectory.parseFrom(rawBytes);
                return rawBytes;
            } catch (InvalidProtocolBufferException legacyParseError) {
                IOException ioException = new IOException("Unsupported local trajectory proto format");
                ioException.addSuppressed(currentParseError);
                ioException.addSuppressed(legacyParseError);
                throw ioException;
            }
        }
    }

    // Parses server bytes and returns the current local trajectory model.
    static Traj.Trajectory parseServerBytes(@NonNull byte[] rawBytes) throws IOException {
        try {
            Traj.Trajectory currentTrajectory = Traj.Trajectory.parseFrom(rawBytes);
            if (looksLikeCurrentTrajectory(currentTrajectory)) {
                return currentTrajectory;
            }
        } catch (InvalidProtocolBufferException ignored) {
        }
        return fromServerTrajectory(UploadTraj.Trajectory.parseFrom(rawBytes));
    }

    // Checks whether the bytes already match the newer local schema.
    private static boolean looksLikeCurrentTrajectory(@NonNull Traj.Trajectory trajectory) {
        return trajectory.getStartTimestamp() > 0
                || trajectory.getImuDataCount() > 0
                || trajectory.getPdrDataCount() > 0
                || trajectory.getGnssDataCount() > 0
                || trajectory.getWifiFingerprintsCount() > 0
                || trajectory.getMagnetometerDataCount() > 0;
    }

    // Maps the current local trajectory fields into the server schema.
    private static UploadTraj.Trajectory toServerTrajectory(@NonNull Traj.Trajectory source) {
        UploadTraj.Trajectory.Builder target = UploadTraj.Trajectory.newBuilder();

        if (!source.getAndroidVersion().isEmpty()) {
            target.setAndroidVersion(source.getAndroidVersion());
        }
        if (!source.getTrajectoryId().isEmpty()) {
            target.setDataIdentifier(source.getTrajectoryId());
        }
        if (source.getStartTimestamp() != 0L) {
            target.setStartTimestamp(source.getStartTimestamp());
        }

        for (Traj.IMUReading reading : source.getImuDataList()) {
            UploadTraj.MotionSample.Builder motion = UploadTraj.MotionSample.newBuilder()
                    .setRelativeTimestamp(reading.getRelativeTimestamp())
                    .setStepCount(reading.getStepCount());
            if (reading.hasAcc()) {
                motion.setAccX(reading.getAcc().getX())
                        .setAccY(reading.getAcc().getY())
                        .setAccZ(reading.getAcc().getZ());
            }
            if (reading.hasGyr()) {
                motion.setGyrX(reading.getGyr().getX())
                        .setGyrY(reading.getGyr().getY())
                        .setGyrZ(reading.getGyr().getZ());
            }
            if (reading.hasRotationVector()) {
                motion.setRotationVectorX(reading.getRotationVector().getX())
                        .setRotationVectorY(reading.getRotationVector().getY())
                        .setRotationVectorZ(reading.getRotationVector().getZ())
                        .setRotationVectorW(reading.getRotationVector().getW());
            }
            target.addImuData(motion.build());
        }

        int pdrIndex = 0;
        for (Traj.RelativePosition position : source.getPdrDataList()) {
            long relativeTimestamp = position.getRelativeTimestamp();
            float x = Math.abs(position.getX()) < 1e-3f ? 0f : position.getX();
            float y = Math.abs(position.getY()) < 1e-3f ? 0f : position.getY();
            if (pdrIndex == 0) {
                x = 0f;
                y = 0f;
                if (relativeTimestamp <= 0L) {
                    relativeTimestamp = 1L;
                }
            }
            target.addPdrData(UploadTraj.PdrSample.newBuilder()
                    .setRelativeTimestamp(relativeTimestamp)
                    .setX(x)
                    .setY(y)
                    .build());
            pdrIndex++;
        }

        for (Traj.MagnetometerReading reading : source.getMagnetometerDataList()) {
            UploadTraj.PositionSample.Builder position = UploadTraj.PositionSample.newBuilder()
                    .setRelativeTimestamp(reading.getRelativeTimestamp());
            if (reading.hasMag()) {
                position.setMagX(reading.getMag().getX())
                        .setMagY(reading.getMag().getY())
                        .setMagZ(reading.getMag().getZ());
            }
            target.addPositionData(position.build());
        }

        for (Traj.BarometerReading reading : source.getPressureDataList()) {
            target.addPressureData(UploadTraj.PressureSample.newBuilder()
                    .setRelativeTimestamp(reading.getRelativeTimestamp())
                    .setPressure(reading.getPressure())
                    .build());
        }

        for (Traj.LightReading reading : source.getLightDataList()) {
            target.addLightData(UploadTraj.LightSample.newBuilder()
                    .setRelativeTimestamp(reading.getRelativeTimestamp())
                    .setLight(reading.getLight())
                    .build());
        }

        for (Traj.GNSSReading reading : source.getGnssDataList()) {
            UploadTraj.GnssSample.Builder gnss = UploadTraj.GnssSample.newBuilder()
                    .setAccuracy(reading.getAccuracy())
                    .setSpeed(reading.getSpeed())
                    .setProvider(reading.getProvider());
            if (reading.hasPosition()) {
                gnss.setRelativeTimestamp(reading.getPosition().getRelativeTimestamp())
                        .setLatitude((float) reading.getPosition().getLatitude())
                        .setLongitude((float) reading.getPosition().getLongitude())
                        .setAltitude((float) reading.getPosition().getAltitude());
            }
            target.addGnssData(gnss.build());
        }

        for (Traj.Fingerprint fingerprint : source.getWifiFingerprintsList()) {
            UploadTraj.WifiSample.Builder wifiSample = UploadTraj.WifiSample.newBuilder()
                    .setRelativeTimestamp(fingerprint.getRelativeTimestamp());
            for (Traj.RFScan scan : fingerprint.getRfScansList()) {
                wifiSample.addMacScans(UploadTraj.MacScan.newBuilder()
                        .setRelativeTimestamp(scan.getRelativeTimestamp() != 0L
                                ? scan.getRelativeTimestamp()
                                : fingerprint.getRelativeTimestamp())
                        .setMac(scan.getMac())
                        .setRssi(scan.getRssi())
                        .build());
            }
            target.addWifiData(wifiSample.build());
        }

        for (Traj.WiFiAPData apData : source.getApsDataList()) {
            target.addApsData(UploadTraj.ApData.newBuilder()
                    .setMac(apData.getMac())
                    .setSsid(apData.getSsid())
                    .setFrequency(apData.getFrequency())
                    .build());
        }

        if (source.hasAccelerometerInfo()) {
            target.setAccelerometerInfo(toServerSensorInfo(source.getAccelerometerInfo()));
        }
        if (source.hasGyroscopeInfo()) {
            target.setGyroscopeInfo(toServerSensorInfo(source.getGyroscopeInfo()));
        }
        if (source.hasRotationVectorInfo()) {
            target.setRotationVectorInfo(toServerSensorInfo(source.getRotationVectorInfo()));
        }
        if (source.hasMagnetometerInfo()) {
            target.setMagnetometerInfo(toServerSensorInfo(source.getMagnetometerInfo()));
        }
        if (source.hasBarometerInfo()) {
            target.setBarometerInfo(toServerSensorInfo(source.getBarometerInfo()));
        }
        if (source.hasLightSensorInfo()) {
            target.setLightSensorInfo(toServerSensorInfo(source.getLightSensorInfo()));
        }

        return target.build();
    }

    // Converts the server schema back into the current local model.
    private static Traj.Trajectory fromServerTrajectory(@NonNull UploadTraj.Trajectory source) {
        Traj.Trajectory.Builder target = Traj.Trajectory.newBuilder()
                .setTrajectoryVersion(2.0f);

        if (!source.getAndroidVersion().isEmpty()) {
            target.setAndroidVersion(source.getAndroidVersion());
        }
        if (!source.getDataIdentifier().isEmpty()) {
            target.setTrajectoryId(source.getDataIdentifier());
        }
        if (source.getStartTimestamp() != 0L) {
            target.setStartTimestamp(source.getStartTimestamp());
        }

        for (UploadTraj.MotionSample reading : source.getImuDataList()) {
            target.addImuData(Traj.IMUReading.newBuilder()
                    .setRelativeTimestamp(reading.getRelativeTimestamp())
                    .setAcc(Traj.Vector3.newBuilder()
                            .setX(reading.getAccX())
                            .setY(reading.getAccY())
                            .setZ(reading.getAccZ())
                            .build())
                    .setGyr(Traj.Vector3.newBuilder()
                            .setX(reading.getGyrX())
                            .setY(reading.getGyrY())
                            .setZ(reading.getGyrZ())
                            .build())
                    .setRotationVector(Traj.Quaternion.newBuilder()
                            .setX(reading.getRotationVectorX())
                            .setY(reading.getRotationVectorY())
                            .setZ(reading.getRotationVectorZ())
                            .setW(reading.getRotationVectorW())
                            .build())
                    .setStepCount(reading.getStepCount())
                    .build());
        }

        for (UploadTraj.PdrSample position : source.getPdrDataList()) {
            target.addPdrData(Traj.RelativePosition.newBuilder()
                    .setRelativeTimestamp(position.getRelativeTimestamp())
                    .setX(position.getX())
                    .setY(position.getY())
                    .build());
        }

        for (UploadTraj.PositionSample reading : source.getPositionDataList()) {
            target.addMagnetometerData(Traj.MagnetometerReading.newBuilder()
                    .setRelativeTimestamp(reading.getRelativeTimestamp())
                    .setMag(Traj.Vector3.newBuilder()
                            .setX(reading.getMagX())
                            .setY(reading.getMagY())
                            .setZ(reading.getMagZ())
                            .build())
                    .build());
        }

        for (UploadTraj.PressureSample reading : source.getPressureDataList()) {
            target.addPressureData(Traj.BarometerReading.newBuilder()
                    .setRelativeTimestamp(reading.getRelativeTimestamp())
                    .setPressure(reading.getPressure())
                    .build());
        }

        for (UploadTraj.LightSample reading : source.getLightDataList()) {
            target.addLightData(Traj.LightReading.newBuilder()
                    .setRelativeTimestamp(reading.getRelativeTimestamp())
                    .setLight(reading.getLight())
                    .build());
        }

        for (UploadTraj.GnssSample reading : source.getGnssDataList()) {
            target.addGnssData(Traj.GNSSReading.newBuilder()
                    .setPosition(Traj.GNSSPosition.newBuilder()
                            .setRelativeTimestamp(reading.getRelativeTimestamp())
                            .setLatitude(reading.getLatitude())
                            .setLongitude(reading.getLongitude())
                            .setAltitude(reading.getAltitude())
                            .build())
                    .setAccuracy(reading.getAccuracy())
                    .setSpeed(reading.getSpeed())
                    .setProvider(reading.getProvider())
                    .build());
        }

        for (UploadTraj.WifiSample sample : source.getWifiDataList()) {
            Traj.Fingerprint.Builder fingerprint = Traj.Fingerprint.newBuilder()
                    .setRelativeTimestamp(sample.getRelativeTimestamp());
            for (UploadTraj.MacScan scan : sample.getMacScansList()) {
                fingerprint.addRfScans(Traj.RFScan.newBuilder()
                        .setRelativeTimestamp(scan.getRelativeTimestamp())
                        .setMac(scan.getMac())
                        .setRssi(scan.getRssi())
                        .build());
            }
            target.addWifiFingerprints(fingerprint.build());
        }

        for (UploadTraj.ApData apData : source.getApsDataList()) {
            target.addApsData(Traj.WiFiAPData.newBuilder()
                    .setMac(apData.getMac())
                    .setSsid(apData.getSsid())
                    .setFrequency(apData.getFrequency())
                    .build());
        }

        if (source.hasAccelerometerInfo()) {
            target.setAccelerometerInfo(fromServerSensorInfo(source.getAccelerometerInfo()));
        }
        if (source.hasGyroscopeInfo()) {
            target.setGyroscopeInfo(fromServerSensorInfo(source.getGyroscopeInfo()));
        }
        if (source.hasRotationVectorInfo()) {
            target.setRotationVectorInfo(fromServerSensorInfo(source.getRotationVectorInfo()));
        }
        if (source.hasMagnetometerInfo()) {
            target.setMagnetometerInfo(fromServerSensorInfo(source.getMagnetometerInfo()));
        }
        if (source.hasBarometerInfo()) {
            target.setBarometerInfo(fromServerSensorInfo(source.getBarometerInfo()));
        }
        if (source.hasLightSensorInfo()) {
            target.setLightSensorInfo(fromServerSensorInfo(source.getLightSensorInfo()));
        }

        if (source.getGnssDataCount() > 0) {
            UploadTraj.GnssSample firstGnss = source.getGnssData(0);
            target.setInitialPosition(Traj.GNSSPosition.newBuilder()
                    .setRelativeTimestamp(firstGnss.getRelativeTimestamp())
                    .setLatitude(firstGnss.getLatitude())
                    .setLongitude(firstGnss.getLongitude())
                    .setAltitude(firstGnss.getAltitude())
                    .build());
        }

        return target.build();
    }

    // Copies sensor information into the server protobuf type.
    private static UploadTraj.SensorInfo toServerSensorInfo(@NonNull Traj.SensorInfo source) {
        return UploadTraj.SensorInfo.newBuilder()
                .setName(source.getName())
                .setVendor(source.getVendor())
                .setResolution(source.getResolution())
                .setPower(source.getPower())
                .setVersion(source.getVersion())
                .setType(source.getType())
                .build();
    }

    // Copies sensor information back into the local protobuf type.
    private static Traj.SensorInfo fromServerSensorInfo(@NonNull UploadTraj.SensorInfo source) {
        return Traj.SensorInfo.newBuilder()
                .setName(source.getName())
                .setVendor(source.getVendor())
                .setResolution(source.getResolution())
                .setPower(source.getPower())
                .setVersion(source.getVersion())
                .setType(source.getType())
                .build();
    }
}
