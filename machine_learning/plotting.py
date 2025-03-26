import numpy as np
import matplotlib.pyplot as plt
from utils import local_xy_to_latlon, latlon_to_local_xy

def compute_calibration_error(ekf_data, lat0, lon0, use_aligned=True):
    """
    Compute average Euclidean error (in meters) between EKF (aligned if available)
    and calibration (user-labeled) points.
    """
    distances = []
    for r in ekf_data:
        if r.get("isCalibration"):
            user_lat = r.get("userLat")
            user_lng = r.get("userLng")
            if user_lat is None or user_lng is None:
                continue
            user_x, user_y = local_xy_to_latlon(*latlon_to_local_xy(user_lat, user_lng, lat0, lon0), lat0, lon0)
            if use_aligned and ("ekfLat_aligned" in r):
                ekf_x, ekf_y = local_xy_to_latlon(r["ekfLat_aligned"], r["ekfLng_aligned"], lat0, lon0)
            else:
                ekf_x, ekf_y = local_xy_to_latlon(r["ekfLat"], r["ekfLng"], lat0, lon0)
            dist = np.sqrt((ekf_x - user_x)**2 + (ekf_y - user_y)**2)
            distances.append(dist)
    if not distances:
        return None
    return np.mean(distances)

import numpy as np
import matplotlib.pyplot as plt
from utils import local_xy_to_latlon

def plot_results(data, lat0, lon0):
    """
    Plot all trajectories:
      - Raw PDR (if available as 'rawPdrX'/'rawPdrY', else falls back to current 'pdrX'/'pdrY'),
      - Aligned PDR (after the first Procrustes alignment),
      - Original EKF (before the second alignment),
      - Aligned EKF (final EKF output after alignment),
      - GNSS positions, and
      - Calibration (user-labeled) points.
    """
    print("Plotting final results ...")
    
    # Raw PDR trajectory: try to use stored raw values, or fallback to current pdrX/Y.
    raw_pdr_ll = np.array([
        local_xy_to_latlon(
            r.get("rawPdrX", r.get("pdrX", 0)),
            r.get("rawPdrY", r.get("pdrY", 0)),
            lat0,
            lon0
        ) for r in data
    ])
    raw_pdr_ll = raw_pdr_ll[:, ::-1]  # Swap to (lon, lat)
    
    # Aligned PDR trajectory (the first correction stage)
    aligned_pdr_ll = np.array([
        local_xy_to_latlon(r.get("pdrX", 0), r.get("pdrY", 0), lat0, lon0)
        for r in data
    ])
    aligned_pdr_ll = aligned_pdr_ll[:, ::-1]

    # Original EKF output (before final alignment)
    ekf_ll = np.array([[r.get("ekfLng", 0), r.get("ekfLat", 0)] for r in data])
    
    # Final aligned EKF output
    aligned_ekf_ll = np.array([
        [r.get("ekfLng_aligned", np.nan), r.get("ekfLat_aligned", np.nan)]
        for r in data
    ])
    
    # GNSS positions
    gnss_ll = np.array([[r.get("gnssLon", np.nan), r.get("gnssLat", np.nan)] for r in data])
    
    # Calibration (true) points
    calib_true = np.array([
        [r.get("userLng"), r.get("userLat")]
        for r in data if r.get("isCalibration")
    ])
    
    plt.figure(figsize=(10, 10))
    
    # Plot each path with different style/markers:
    plt.plot(raw_pdr_ll[:, 0], raw_pdr_ll[:, 1], 'k--', label='Raw PDR')
    plt.plot(aligned_pdr_ll[:, 0], aligned_pdr_ll[:, 1], 'm--', label='Aligned PDR')
    plt.plot(ekf_ll[:, 0], ekf_ll[:, 1], 'b-', label='Original EKF')
    
    if not np.isnan(aligned_ekf_ll[:, 0]).all():
        plt.plot(aligned_ekf_ll[:, 0], aligned_ekf_ll[:, 1], 'g-', label='Aligned EKF')
    
    # GNSS data (filter out nan entries)
    valid_gnss = ~np.isnan(gnss_ll[:, 0])
    plt.scatter(gnss_ll[valid_gnss, 0], gnss_ll[valid_gnss, 1], c='r', s=20, label='GNSS')
    
    if calib_true.size > 0:
        plt.scatter(calib_true[:, 0], calib_true[:, 1], c='purple', s=60, marker='o', label='Calibration True')
    
    plt.xlabel("Longitude")
    plt.ylabel("Latitude")
    plt.title("Fusion: Raw & Processed Trajectories (PDR, EKF, Aligned Paths)")
    plt.legend()
    plt.axis("equal")
    plt.grid(True)
    plt.savefig("debug_plot.png")
    print("Plot saved to debug_plot.png")


