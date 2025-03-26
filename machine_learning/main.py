from optimization import main_bayesian
from plotting import plot_results
from utils import load_json_records, get_origin_from_first_gnss, latlon_to_local_xy
from ekf import ekf_fusion
from alignment import procrustes_alignment

if __name__ == "__main__":
    # data and config
    data_path = "./data/collection_data_20250326_0036.json"
    data = load_json_records(data_path)

    # Fixed parameters (example)
    pdr_process_noise = 0.2
    gnss_measurement_noise = 10.0
    robust_threshold = 9.21
    inflation_factor = 5.0
    pdr_calib_weight = 50.0
    ekf_calib_weight = 10.0

    # Get reference origin
    lat0, lon0 = get_origin_from_first_gnss(data)
    if lat0 is None or lon0 is None:
        print("No valid GNSS data found.")
    else:
        import numpy as np

        # ---- Step 1: Save raw PDR for later visualization ----
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)

        # ---- Step 2: Align PDR to calibration points (before EKF) ----
        # Assign initial EKF fields for alignment
        for r in data:
            r["ekfLocalX"] = r.get("pdrX", 0.0)
            r["ekfLocalY"] = r.get("pdrY", 0.0)

        data, _ = procrustes_alignment(
            ekf_data=data,
            lat0=lat0,
            lon0=lon0,
            weight_field="calibWeight",
            default_weight=pdr_calib_weight
        )

        # Overwrite PDR values with aligned ones (for EKF input)
        for r in data:
            if "ekfLat_aligned" in r and "ekfLng_aligned" in r:
                x, y = latlon_to_local_xy(r["ekfLat_aligned"], r["ekfLng_aligned"], lat0, lon0)
                r["pdrX"] = x
                r["pdrY"] = y

        # ---- Step 3: Run EKF Fusion ----
        Q = np.eye(2) * pdr_process_noise
        R = np.eye(2) * gnss_measurement_noise

        ekf_data = ekf_fusion(
            data=data,
            Q=Q,
            R=R,
            lat0=lat0,
            lon0=lon0,
            robust_threshold=robust_threshold,
            inflation_factor=inflation_factor
        )

        # ---- Step 4: Align EKF Output ----
        ekf_data_aligned, transf = procrustes_alignment(
            ekf_data=ekf_data,
            lat0=lat0,
            lon0=lon0,
            weight_field="calibWeight",
            default_weight=ekf_calib_weight
        )

        print("Final transformation parameters:", transf)

        # ---- Step 5: Plot everything ----
        plot_results(ekf_data_aligned, lat0, lon0)
