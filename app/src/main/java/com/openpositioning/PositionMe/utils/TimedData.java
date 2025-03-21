package com.openpositioning.PositionMe.utils;
import java.util.Comparator;
import java.util.List;

/**
 * Abstract class to aid with storing timed datapoints.
 * Includes methods to work with timed records
 * @author Philip Heptonstall
 */
public abstract class TimedData {
  public long relativeTimestamp;

  /**
   *
   * @param timedData List of datapoints whose timestamp is in ascending order.
   * @param targetTimestamp Timestamp for which you would like to find the nearest datapoint
   * @return Datapoint whose timestamp is closest to the given timestamp
   * @param <T> Timed datapoint extending TimedData
   */
  public static <T extends TimedData> T findClosestRecord(List<T> timedData, long targetTimestamp) {
    return timedData.stream().min(Comparator.comparingLong(
            data -> Math.abs(data.relativeTimestamp - targetTimestamp))).orElse(null);
  }
}
