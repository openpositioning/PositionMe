package com.openpositioning.PositionMe.fragments;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.openpositioning.PositionMe.Traj;

public class TrajectoryViewModel extends ViewModel {
    private final MutableLiveData<Traj.Trajectory> trajectory = new MutableLiveData<>();

    public MutableLiveData<Traj.Trajectory> getTrajectory() {
        return trajectory;
    }

    public void setTrajectory(Traj.Trajectory trajectory){
        this.trajectory.setValue(trajectory);
    }
}
