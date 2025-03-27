import numpy as np
import matplotlib.pyplot as plt
from utils import local_xy_to_latlon, latlon_to_local_xy

def plot_results(data, lat0, lon0, use_wifi=False):
    """
    Plot all trajectories:
      - Raw PDR (with dots and line)
      - Aligned PDR
      - Original EKF
      - Aligned EKF
      - GNSS/WiFi positioning
      - Calibration points
      - Raw PDR Calibration highlights
      - Arrows connecting raw → aligned calibration
    """
    print("Plotting final results ...")

    # Raw PDR
    raw_pdr = np.array([
        local_xy_to_latlon(r.get("rawPdrX", r.get("pdrX", 0)), r.get("rawPdrY", r.get("pdrY", 0)), lat0, lon0)
        for r in data
    ])[:, ::-1]  # to (lon, lat)

    # Aligned PDR
    aligned_pdr = np.array([
        local_xy_to_latlon(r.get("pdrX", 0), r.get("pdrY", 0), lat0, lon0)
        for r in data
    ])[:, ::-1]

    # EKF
    ekf = np.array([[r.get("ekfLng", 0), r.get("ekfLat", 0)] for r in data])
    
    # EKF aligned
    aligned_ekf = np.array([
        [r.get("ekfLng_aligned", np.nan), r.get("ekfLat_aligned", np.nan)]
        for r in data
    ])

    # GNSS / WiFi
    if use_wifi:
        pos_ll = np.array([
            [float(r.get("wifiLon")) if r.get("wifiLon") else np.nan,
             float(r.get("wifiLat")) if r.get("wifiLat") else np.nan]
            for r in data
        ])
        pos_label = "WiFi"
    else:
        pos_ll = np.array([
            [float(r.get("gnssLon")) if r.get("gnssLon") else np.nan,
             float(r.get("gnssLat")) if r.get("gnssLat") else np.nan]
            for r in data
        ])
        pos_label = "GNSS"

    # Calibration points (true positions)
    calib_true = np.array([
        [r.get("userLng"), r.get("userLat")]
        for r in data if r.get("isCalibration")
    ])

    # Raw calibration points
    calib_raw = np.array([
        local_xy_to_latlon(r.get("rawPdrX", r.get("pdrX", 0)), r.get("rawPdrY", r.get("pdrY", 0)), lat0, lon0)
        for r in data if r.get("isCalibration")
    ])[:, ::-1]

    # Aligned calibration points
    calib_aligned = np.array([
        local_xy_to_latlon(r.get("pdrX", 0), r.get("pdrY", 0), lat0, lon0)
        for r in data if r.get("isCalibration")
    ])[:, ::-1]

    # ==== Plotting ====
    plt.figure(figsize=(10, 10))

    # Raw PDR: line + small dots
    plt.plot(raw_pdr[:, 0], raw_pdr[:, 1], 'k--', alpha=0.4, label='Raw PDR')
    plt.scatter(raw_pdr[:, 0], raw_pdr[:, 1], c='k', s=5, alpha=0.7)

    # Aligned PDR
    plt.plot(aligned_pdr[:, 0], aligned_pdr[:, 1], 'm--', alpha=0.4, label='Aligned PDR')
    plt.scatter(aligned_pdr[:, 0], aligned_pdr[:, 1], c='m', s=5, alpha=0.7)

    # # EKF original
    # plt.plot(ekf[:, 0], ekf[:, 1], 'b-', alpha=0.4, label='Original EKF')
    # plt.scatter(ekf[:, 0], ekf[:, 1], c='b', s=5, alpha=0.7)

    # # EKF aligned
    # if not np.isnan(aligned_ekf[:, 0]).all():
    #     plt.plot(aligned_ekf[:, 0], aligned_ekf[:, 1], 'g-', alpha=0.4, label='Aligned EKF')
    #     plt.scatter(aligned_ekf[:, 0], aligned_ekf[:, 1], c='g', s=5, alpha=0.7)

    # # GNSS/WiFi positions
    # valid_pos = ~np.isnan(pos_ll[:, 0])
    # plt.scatter(pos_ll[valid_pos, 0], pos_ll[valid_pos, 1], c='r', s=20, label=pos_label)

    # Calibration True
    if calib_true.size > 0:
        plt.scatter(calib_true[:, 0], calib_true[:, 1], c='purple', s=60, marker='o', label='Calibration True')

    # Raw calibration points
    if calib_raw.size > 0:
        plt.scatter(calib_raw[:, 0], calib_raw[:, 1], facecolors='none', edgecolors='cyan', s=100, marker='o', label='Raw PDR Calibration')

    # Arrows: raw → aligned calibration
    for i in range(min(len(calib_raw), len(calib_aligned))):
        plt.annotate("",
                     xy=calib_aligned[i],
                     xytext=calib_raw[i],
                     arrowprops=dict(arrowstyle="->", color="cyan", lw=1.8))

    # ==== Labels ====
    plt.xlabel("Longitude")
    plt.ylabel("Latitude")
    plt.title("Fusion: Raw & Processed Trajectories (with Points + Arrows)")
    plt.legend()
    plt.axis("equal")
    plt.grid(True)
    plt.tight_layout()
    plt.savefig("debug_plot.png", dpi=200)
    print("Plot saved to debug_plot.png")
