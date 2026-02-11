package com.openpositioning.PositionMe.data.utils;

import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.data.model.TestPointInfo;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

public class TestPointParser {

    public static List<TestPointInfo> parseFromFile(File file) {
        List<TestPointInfo> list = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file)) {

            Traj.Trajectory trajectory = Traj.Trajectory.parseFrom(fis);

            int counter = 1;
            for (Traj.GNSSPosition tp : trajectory.getTestPointsList()) {

                list.add(new TestPointInfo(
                        counter++,
                        tp.getLatitude(),
                        tp.getLongitude(),
                        tp.getRelativeTimestamp()   // <-- correct field
                ));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return list;
    }
}
