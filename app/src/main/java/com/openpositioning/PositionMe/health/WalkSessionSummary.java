package com.openpositioning.PositionMe.health;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A data class holding the summary of a single walk session.
 * It is immutable and Parcelable to be easily passed between activities.
 */
public class WalkSessionSummary implements Parcelable {

    private final String sessionId;
    private final double distanceMeters;
    private final int durationSeconds;
    private final long timestampMillis;


    /**
     * Constructs a new WalkSessionSummary.
     *
     * @param sessionId      A unique identifier for the session.
     * @param distanceMeters Total distance walked in meters.
     * @param durationSeconds Total duration of the walk in seconds.
     * @param timestampMillis The time the session ended, in UTC milliseconds from the epoch.
     */
    public WalkSessionSummary(String sessionId, double distanceMeters, int durationSeconds, long timestampMillis) {
        this.sessionId = (sessionId == null) ? "" : sessionId;
        this.distanceMeters = Math.max(0, distanceMeters);
        this.durationSeconds = Math.max(0, durationSeconds);
        this.timestampMillis = timestampMillis;
    }

    //region Parcelable Implementation
    protected WalkSessionSummary(Parcel in) {
        sessionId = in.readString();
        distanceMeters = in.readDouble();
        durationSeconds = in.readInt();
        timestampMillis = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(sessionId);
        dest.writeDouble(distanceMeters);
        dest.writeInt(durationSeconds);
        dest.writeLong(timestampMillis);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<WalkSessionSummary> CREATOR = new Creator<WalkSessionSummary>() {
        @Override
        public WalkSessionSummary createFromParcel(Parcel in) {
            return new WalkSessionSummary(in);
        }

        @Override
        public WalkSessionSummary[] newArray(int size) {
            return new WalkSessionSummary[size];
        }
    };
    //endregion

    /** @return The unique identifier for the session. */
    public String getSessionId() { return sessionId; }

    /** @return The total distance walked in meters. */
    public double getDistanceMeters() { return distanceMeters; }

    /** @return The total duration of the walk in seconds. */
    public int getDurationSeconds() { return durationSeconds; }

    /** @return The time the session ended, in UTC milliseconds from the epoch. */
    public long getTimestampMillis() { return timestampMillis; }
}
