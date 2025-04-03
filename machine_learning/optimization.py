import numpy as np
from skopt import gp_minimize
from skopt.space import Real
from skopt.utils import use_named_args
from utils import load_json_records, get_origin_from_first_gnss, latlon_to_local_xy
from ekf import ekf_fusion
from alignment import procrustes_alignment
from plotting import compute_calibration_error

def run_pipeline_and_score(params, data_path, lat0, lon0):
    """Pipeline: align PDR → EKF fusion → align EKF → return calibration error."""
    data = load_json_records(data_path)

    # Step 1: Align raw PDR trajectory to calibration points (Procrustes on pdrX/Y)
    for r in data:
        r["ekfLocalX"] = r.get("pdrX", 0.0)
        r["ekfLocalY"] = r.get("pdrY", 0.0)

    data, _ = procrustes_alignment(
        ekf_data=data,
        lat0=lat0,
        lon0=lon0,
        weight_field="calibWeight",
        default_weight=params["default_calib_weight"]
    )

    # Overwrite pdrX/Y with aligned values (as EKF input)
    for r in data:
        if "ekfLat_aligned" in r and "ekfLng_aligned" in r:
            r["pdrX"], r["pdrY"] = latlon_to_local_xy(r["ekfLat_aligned"], r["ekfLng_aligned"], lat0, lon0)

    # Step 2: Run EKF using aligned PDR
    Q = np.eye(2) * params["pdr_process_noise"]
    R = np.eye(2) * params["gnss_measurement_noise"]

    ekf_data = ekf_fusion(
        data=data,
        Q=Q,
        R=R,
        lat0=lat0,
        lon0=lon0,
        robust_threshold=params["robust_threshold"],
        inflation_factor=params["inflation_factor"]
    )

    # Step 3: Final Procrustes alignment after EKF
    ekf_data, _ = procrustes_alignment(
        ekf_data=ekf_data,
        lat0=lat0,
        lon0=lon0,
        weight_field="calibWeight",
        default_weight=params["default_calib_weight"]
    )

    # Score using final aligned EKF
    score = compute_calibration_error(ekf_data, lat0, lon0, use_aligned=True)
    return score if score is not None else 1e6


def main_bayesian():
    data_path = "./data/collection_data_20250326_0036.json"
    data_tmp = load_json_records(data_path)
    lat0, lon0 = get_origin_from_first_gnss(data_tmp)
    if lat0 is None or lon0 is None:
        print("No valid GNSS data found for reference origin.")
        return

    space = [
        Real(0.05, 1.0, name='pdr_process_noise'),
        Real(1.0, 20.0, name='gnss_measurement_noise'),
        Real(2.0, 20.0, name='robust_threshold'),
        Real(1.0, 10.0, name='inflation_factor'),
        Real(1.0, 20.0, name='default_calib_weight')
    ]

    @use_named_args(space)
    def objective(pdr_process_noise, gnss_measurement_noise, robust_threshold,
                  inflation_factor, default_calib_weight):
        params = {
            "pdr_process_noise": pdr_process_noise,
            "gnss_measurement_noise": gnss_measurement_noise,
            "robust_threshold": robust_threshold,
            "inflation_factor": inflation_factor,
            "default_calib_weight": default_calib_weight
        }
        score = run_pipeline_and_score(params, data_path, lat0, lon0)
        print("Score:", score, "with params:", params)
        return score

    print("Starting Bayesian optimization ...")
    res = gp_minimize(
        func=objective,
        dimensions=space,
        n_calls=25,
        n_random_starts=5,
        random_state=42
    )

    best_params_dict = {
        "pdr_process_noise": res.x[0],
        "gnss_measurement_noise": res.x[1],
        "robust_threshold": res.x[2],
        "inflation_factor": res.x[3],
        "default_calib_weight": res.x[4],
    }
    best_score = res.fun

    print("\nBayesian optimization complete.")
    print("Best parameters found:")
    for k, v in best_params_dict.items():
        print(f"  {k} = {v:.4f}")
    print(f"=> Achieved average calibration error: {best_score:.2f} m")
