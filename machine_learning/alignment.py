import numpy as np
from utils import latlon_to_local_xy, local_xy_to_latlon

def procrustes_alignment(ekf_data, lat0, lon0, weight_field="calibWeight", default_weight=1.0):
    """
    Perform weighted Procrustes alignment (a 2D similarity transform) to map the
    EKF predictions to calibration (ground-truth) points.
    """
    pred_points = []
    true_points = []
    weights = []

    for rec in ekf_data:
        if rec.get("isCalibration"):
            pred = np.array([rec.get("ekfLocalX", 0), rec.get("ekfLocalY", 0)])
            if rec.get("userLat") is not None and rec.get("userLng") is not None:
                true = np.array(latlon_to_local_xy(rec["userLat"], rec["userLng"], lat0, lon0))
                pred_points.append(pred)
                true_points.append(true)
                weights.append(rec.get(weight_field, default_weight))

    if len(pred_points) < 2:
        return ekf_data, None

    X = np.array(pred_points)
    Y = np.array(true_points)
    w = np.array(weights).reshape(-1, 1)

    sum_w = np.sum(w)
    muX = np.sum(X * w, axis=0) / sum_w
    muY = np.sum(Y * w, axis=0) / sum_w

    Xc = X - muX
    Yc = Y - muY

    A = (Xc * w).T @ Yc
    U, s, Vt = np.linalg.svd(A)
    R_mat = Vt.T @ U.T
    if np.linalg.det(R_mat) < 0:
        Vt[-1, :] *= -1
        R_mat = Vt.T @ U.T

    numerator = np.sum(s)
    denominator = np.sum(w * np.sum(Xc**2, axis=1).reshape(-1, 1))
    scale = numerator / denominator

    T_vec = muY - scale * (muX @ R_mat)

    # Apply transformation to each record
    for rec in ekf_data:
        x_local = rec.get("ekfLocalX", 0)
        y_local = rec.get("ekfLocalY", 0)
        aligned_local = scale * np.dot([x_local, y_local], R_mat) + T_vec
        aligned_lat, aligned_lon = local_xy_to_latlon(aligned_local[0], aligned_local[1], lat0, lon0)
        rec["ekfLat_aligned"] = aligned_lat
        rec["ekfLng_aligned"] = aligned_lon

    transf = {"scale": scale, "rotation": R_mat, "translation": T_vec}
    return ekf_data, transf
