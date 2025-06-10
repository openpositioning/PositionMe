import numpy as np
import sys

from utils import load_json_records, get_origin, latlon_to_local_xy, preprocess_standstill_near_anchors
from ekf import ekf_fusion
from plotting import plot_results
from alignment import piecewise_procrustes_alignment

def main():
    # Step 1: Load data
    data_path = "./data/collection_data_Pixel 8a_20250327_1819.json"
    data = load_json_records(data_path)
    if not data:
        print("No data found. Exiting.")
        sys.exit(1)

    # Step 2: Choose whether to use WiFi-based anchors or GNSS
    use_wifi = True  # Set to False to use GNSS as the reference

    # Step 3: Set configuration parameters
    pdr_process_noise = 0.5            # PDR motion model noise
    gnss_measurement_noise = 50.0      # GNSS/WiFi measurement uncertainty (meters^2)
    robust_threshold = 3.0             # Outlier rejection threshold (Mahalanobis distance)
    inflation_factor = 15.0            # Outlier inflation penalty
    ekf_calib_weight = 1.5             # Weight for calibration points in fusion (not yet used)

    # Step 4: Get reference origin for local coordinate conversion
    lat0, lon0 = get_origin(data, use_wifi)
    if lat0 is None or lon0 is None:
        print("Failed to determine origin coordinates.")
        sys.exit(1)

    # Step 5: Backup original PDR coordinates for later comparison
    for r in data:
        r["rawPdrX"] = r.get("pdrX", 0.0)
        r["rawPdrY"] = r.get("pdrY", 0.0)

    # Optional: Preprocess anchor points where the user was standing still
    # This can reduce noise from repeated anchor points in the same location
    # data = preprocess_standstill_near_anchors(
    #     data=data,
    #     anchor_flag_field="isCalibration",
    #     standstill_window=5,      # Number of points before/after anchor to check
    #     dist_threshold=0.2,       # Maximum movement to consider "standing still"
    #     step_counter_field="stepCounter",
    #     pred_x_field="pdrX",
    #     pred_y_field="pdrY"
    # )

    # Step 6: First alignment pass using Procrustes per segment
    data_aligned, transforms1 = piecewise_procrustes_alignment(
        data=data,
        lat0=lat0,
        lon0=lon0,
        pred_x_field="pdrX",
        pred_y_field="pdrY",
        true_lat_field="userLat",
        true_lng_field="userLng",
        anchor_flag_field="isCalibration",
        smoothing_count=30,               # Allows overlap for smoother transitions
        latlon_to_local_xy=latlon_to_local_xy,
        debug=True
    )

    print(">>> First alignment complete. Transformation parameters:")
    for seg, (s, R, t) in transforms1:
        print(f" Segment={seg}, Scale={s:.3f}, Rotation=\n{R}, Translation={t}")

    # Step 7: Apply Extended Kalman Filter to fuse aligned PDR with GNSS/WiFi
    Q = np.eye(2) * pdr_process_noise
    R_mat = np.eye(2) * gnss_measurement_noise
    ekf_data = ekf_fusion(
        data=data_aligned,
        Q=Q,
        R=R_mat,
        lat0=lat0,
        lon0=lon0,
        robust_threshold=robust_threshold,
        inflation_factor=inflation_factor,
        use_wifi=use_wifi
    )

    # Step 8: Optional second alignment on EKF output (fine-tuning)
    ekf_data_aligned, transforms2 = piecewise_procrustes_alignment(
        data=ekf_data,
        lat0=lat0,
        lon0=lon0,
        pred_x_field="ekfLocalX",     # EKF output fields
        pred_y_field="ekfLocalY",
        true_lat_field="userLat",
        true_lng_field="userLng",
        anchor_flag_field="isCalibration",
        smoothing_count=0,
        latlon_to_local_xy=latlon_to_local_xy,
        debug=True
    )

    print(">>> Second alignment complete. Transformation parameters:")
    for seg, (s, R, t) in transforms2:
        print(f" Segment={seg}, Scale={s:.3f}, Rotation=\n{R}, Translation={t}")

    # Step 9: (Optional) Convert final EKF-aligned local coordinates back to latitude/longitude
    # for r in ekf_data_aligned:
    #     lat_aligned, lng_aligned = local_xy_to_latlon(
    #         r["ekfLocalX"], r["ekfLocalY"], lat0, lon0
    #     )
    #     r["ekfLat_aligned"] = lat_aligned
    #     r["ekfLng_aligned"] = lng_aligned

    # Step 10: Plot results for visual inspection
    plot_results(ekf_data_aligned, lat0, lon0, use_wifi=use_wifi)

if __name__ == "__main__":
    main()
