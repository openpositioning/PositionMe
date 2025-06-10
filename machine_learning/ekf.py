import numpy as np
from utils import latlon_to_local_xy, local_xy_to_latlon

def ekf_fusion(data, Q, R, lat0, lon0, robust_threshold=9.21, inflation_factor=10.0, use_wifi=False):
    """
    Fuse PDR and GNSS/WiFi data in a common local coordinate system.
    Uses a simple EKF with robust gating.
    
    :param use_wifi: if True, use wifiLat/wifiLon; otherwise, use gnssLat/gnssLon.
    """
    if not data:
        return data

    x = np.array([0.0, 0.0])
    P = np.eye(2) * 1e-4
    prev_pdr = np.array([data[0].get("pdrX", 0), data[0].get("pdrY", 0)])

    for record in data:
        current_pdr = np.array([record.get("pdrX", 0), record.get("pdrY", 0)])
        u = current_pdr - prev_pdr

        # Prediction step
        x_pred = x + u
        P_pred = P + Q

        # Select which positioning fields to use based on the switch
        if use_wifi:
            pos_lat_field = "wifiLat"
            pos_lon_field = "wifiLon"
            used_field = "wifiUsed"
        else:
            pos_lat_field = "gnssLat"
            pos_lon_field = "gnssLon"
            used_field = "gnssUsed"

        # Update step using available positioning data (GNSS or WiFi)
        if record.get(pos_lat_field) is not None and record.get(pos_lon_field) is not None:
            z = np.array(latlon_to_local_xy(record[pos_lat_field], record[pos_lon_field], lat0, lon0))
            y_innov = z - x_pred
            S = P_pred + R
            d2 = y_innov.T @ np.linalg.inv(S) @ y_innov

            # Robust gating: if innovation is too high, inflate uncertainty
            if d2 > robust_threshold:
                record[used_field] = False
                S_inflated = P_pred + R * inflation_factor
                K = P_pred @ np.linalg.inv(S_inflated)
            else:
                record[used_field] = True
                K = P_pred @ np.linalg.inv(S)
            x = x_pred + K @ y_innov
            P = (np.eye(2) - K) @ P_pred
        else:
            record[used_field] = False
            x = x_pred
            P = P_pred

        lat, lon = local_xy_to_latlon(x[0], x[1], lat0, lon0)
        record["ekfLat"] = lat
        record["ekfLng"] = lon
        record["ekfLocalX"] = x[0]
        record["ekfLocalY"] = x[1]

        prev_pdr = current_pdr

    return data
