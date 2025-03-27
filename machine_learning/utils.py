import json
import numpy as np

def load_json_records(json_path):
    """Load JSON records sorted by timestamp."""
    with open(json_path, 'r', encoding='utf-8') as f:
        return sorted([json.loads(line) for line in f if line.strip()],
                      key=lambda x: x.get("timestamp", 0))

def latlon_to_local_xy(lat, lon, lat0, lon0):
    """Convert lat/lon (degrees) to local x,y (meters)."""
    R = 6378137  # Earth radius in meters
    dlat = np.radians(lat - lat0)
    dlon = np.radians(lon - lon0)
    lat_avg = np.radians((lat + lat0) / 2.0)
    x = dlon * R * np.cos(lat_avg)
    y = dlat * R
    return x, y

def local_xy_to_latlon(x, y, lat0, lon0):
    """Convert local x,y (meters) back to lat/lon (degrees)."""
    R = 6378137
    dlat = y / R
    dlon = x / (R * np.cos(np.radians(lat0)))
    lat = lat0 + np.degrees(dlat)
    lon = lon0 + np.degrees(dlon)
    return lat, lon

def get_origin(data, use_wifi=False):
    """
    Return the first valid positioning lat/lon as the origin.
    If use_wifi is True, use 'wifiLat' and 'wifiLon'; otherwise, use 'gnssLat' and 'gnssLon'.
    """
    if use_wifi:
        for rec in data:
            if rec.get("wifiLat") is not None and rec.get("wifiLon") is not None:
                return rec["wifiLat"], rec["wifiLon"]
    else:
        for rec in data:
            if rec.get("gnssLat") is not None and rec.get("gnssLon") is not None:
                return rec["gnssLat"], rec["gnssLon"]
    return None, None

def filter_unique_wifi(data, tolerance=1e-6):
    """
    Filter out duplicate WiFi position records (based on wifiLat and wifiLon) unless the record is a calibration point.
    Assumes data is sorted by timestamp.
    
    :param data: List of records.
    :param tolerance: Minimum difference in degrees to consider two points as unique.
    :return: Filtered list of records.
    """
    filtered = []
    last_wifi = None
    for record in data:
        # Always include calibration points.
        if record.get("isCalibration"):
            filtered.append(record)
            continue
        wifiLat = record.get("wifiLat")
        wifiLon = record.get("wifiLon")
        if wifiLat is None or wifiLon is None:
            filtered.append(record)
        else:
            if last_wifi is None:
                filtered.append(record)
                last_wifi = (wifiLat, wifiLon)
            else:
                if abs(wifiLat - last_wifi[0]) > tolerance or abs(wifiLon - last_wifi[1]) > tolerance:
                    filtered.append(record)
                    last_wifi = (wifiLat, wifiLon)
                else:
                    # Duplicate WiFi point, skip.
                    pass
    return filtered


def preprocess_standstill_near_anchors(
    data,
    anchor_flag_field="isCalibration",
    standstill_window=5,
    dist_threshold=0.2,
    step_counter_field="stepCounter",
    pred_x_field="pdrX",
    pred_y_field="pdrY"
):
    """
    对于每个锚点，在其前后 standstill_window 条记录内，
    若距离锚点较近且步数基本不变，则将这些记录的 pdrX, pdrY 固定为锚点的值。
    """
    data = sorted(data, key=lambda r: r["timestamp"])
    anchor_indices = [i for i, r in enumerate(data) if r.get(anchor_flag_field, False)]
    n = len(data)
    for anchor_i in anchor_indices:
        anchor_rec = data[anchor_i]
        ax = anchor_rec[pred_x_field]
        ay = anchor_rec[pred_y_field]
        anchor_step = anchor_rec.get(step_counter_field, 0)
        # 检查锚点前面的记录
        start_i = max(0, anchor_i - standstill_window)
        for i_pt in range(start_i, anchor_i):
            r_pt = data[i_pt]
            px = r_pt[pred_x_field]
            py = r_pt[pred_y_field]
            dist = ((px - ax)**2 + (py - ay)**2)**0.5
            if dist < dist_threshold:
                step_delta = abs(r_pt.get(step_counter_field, 0) - anchor_step)
                if step_delta < 1:
                    data[i_pt][pred_x_field] = ax
                    data[i_pt][pred_y_field] = ay
        # 检查锚点后面的记录
        end_i = min(n - 1, anchor_i + standstill_window)
        for i_pt in range(anchor_i + 1, end_i + 1):
            r_pt = data[i_pt]
            px = r_pt[pred_x_field]
            py = r_pt[pred_y_field]
            dist = ((px - ax)**2 + (py - ay)**2)**0.5
            step_delta = abs(r_pt.get(step_counter_field, 0) - anchor_step)
            if dist < dist_threshold and step_delta < 1:
                data[i_pt][pred_x_field] = ax
                data[i_pt][pred_y_field] = ay
    return data
