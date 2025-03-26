import numpy as np
from utils import latlon_to_local_xy, local_xy_to_latlon

def ekf_fusion(data, Q, R, lat0, lon0, robust_threshold=9.21, inflation_factor=10.0):
    """
    Fuse PDR and GNSS data in a common local coordinate system.
    Uses a simple EKF with robust gating.
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

        # Update step using GNSS if available
        if record.get("gnssLat") is not None and record.get("gnssLon") is not None:
            z = np.array(latlon_to_local_xy(record["gnssLat"], record["gnssLon"], lat0, lon0))
            y_innov = z - x_pred
            S = P_pred + R
            d2 = y_innov.T @ np.linalg.inv(S) @ y_innov
            if d2 > robust_threshold:
                S_inflated = P_pred + R * inflation_factor
                K = P_pred @ np.linalg.inv(S_inflated)
            else:
                K = P_pred @ np.linalg.inv(S)
            x = x_pred + K @ y_innov
            P = (np.eye(2) - K) @ P_pred
        else:
            x = x_pred
            P = P_pred

        lat, lon = local_xy_to_latlon(x[0], x[1], lat0, lon0)
        record["ekfLat"] = lat
        record["ekfLng"] = lon
        record["ekfLocalX"] = x[0]
        record["ekfLocalY"] = x[1]

        prev_pdr = current_pdr

    return data
