package com.openpositioning.PositionMe.dataParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Container class for all trajectory data.
 */
public class TrajectoryData {
    private List<GnssData> gnssData;
    private List<PdrData> pdrData;
    private List<PositionData> positionData;

    public TrajectoryData() {
        gnssData = new ArrayList<>();
        pdrData = new ArrayList<>();
        positionData = new ArrayList<>();
    }

    public List<GnssData> getGnssData() {
        return gnssData;
    }

    public void setGnssData(List<GnssData> gnssData) {
        this.gnssData = gnssData;
    }

    public List<PdrData> getPdrData() {
        return pdrData;
    }

    public void setPdrData(List<PdrData> pdrData) {
        this.pdrData = pdrData;
    }

    public List<PositionData> getPositionData() {
        return positionData;
    }

    public void setPositionData(List<PositionData> positionData) {
        this.positionData = positionData;
    }
}