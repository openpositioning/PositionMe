package com.openpositioning.PositionMe.utils;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Abstract class to aid with storing timed datapoints.
 * Includes methods to work with timed records
 * @author Philip Heptonstall
 */
public abstract class TimedData {
  public long relativeTimestamp;

  /**
   * Given a list of timed datapoints, find the one which has a timestamp closest but less
   * than or equal to the given target timestamp.
   *
   * @param timedData List of datapoints whose timestamp is in ascending order
   * @param targetTimestamp Timestamp for which you would like to find the nearest datapoint
   * @return Datapoint whose timestamp is closest to the given timestamp
   * @param <T> Timed datapoint extending TimedData
   */
  public static <T extends TimedData> T findClosestRecord(List<T> timedData, long targetTimestamp) {
    int i = 0;
    while (i < timedData.size() && timedData.get(i).relativeTimestamp <= targetTimestamp) i++;
    // TODO: THIS MAY RETURN 0 (BEFORE THE SUBTRACTION), indicating even the first element
    // is > the target timestamp. In these cases, we should show nothing.
    i--;
    if (i == -1) {
      return null;
    }
    return timedData.get(i);
  }
}
