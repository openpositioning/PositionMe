import numpy as np
from utils import latlon_to_local_xy, local_xy_to_latlon

def cluster_anchors(
    data,
    lat0,
    lon0,
    pred_x_field="ekfLocalX",    # Which field stores the predicted X (e.g. pdrX, ekfLocalX)
    pred_y_field="ekfLocalY",    # Same for predicted Y
    user_lat_field="userLat",    # Anchor lat field
    user_lng_field="userLng",    # Anchor lng field
    weight_field="calibWeight",  # Calibration weight field
    default_weight=1.0,
    dist_thresh=1.0
):
    """
    Condense repeated anchors (e.g., standing still) into a single cluster so they
    won't be over-weighted in the transform.
    """
    anchors = []
    for r in data:
        if r.get("isCalibration"):
            user_lat = r.get(user_lat_field)
            user_lng = r.get(user_lng_field)
            if user_lat is None or user_lng is None:
                continue
            # Convert anchor lat/lon -> local XY.
            ux, uy = latlon_to_local_xy(user_lat, user_lng, lat0, lon0)
            px = r.get(pred_x_field, 0.0)
            py = r.get(pred_y_field, 0.0)
            w = r.get(weight_field, default_weight)
            anchors.append((ux, uy, px, py, w))
    if not anchors:
        return []
    clusters = []
    def dist(ca, cb):
        ax = ca["userX_sum"] / ca["w_sum"]
        ay = ca["userY_sum"] / ca["w_sum"]
        bx = cb["userX_sum"] / cb["w_sum"]
        by = cb["userY_sum"] / cb["w_sum"]
        return np.hypot(ax - bx, ay - by)
    for (ux, uy, px, py, w) in anchors:
        new_cluster = {
            "userX_sum": ux * w,
            "userY_sum": uy * w,
            "predX_sum": px * w,
            "predY_sum": py * w,
            "w_sum": w
        }
        placed = False
        for c in clusters:
            if dist_thresh > 0 and dist(new_cluster, c) <= dist_thresh:
                c["userX_sum"] += new_cluster["userX_sum"]
                c["userY_sum"] += new_cluster["userY_sum"]
                c["predX_sum"] += new_cluster["predX_sum"]
                c["predY_sum"] += new_cluster["predY_sum"]
                c["w_sum"] += new_cluster["w_sum"]
                placed = True
                break
        if not placed:
            clusters.append(new_cluster)
    condensed_anchors = []
    for c in clusters:
        ws = c["w_sum"]
        condensed_anchors.append({
            "userX": c["userX_sum"] / ws,
            "userY": c["userY_sum"] / ws,
            "predX": c["predX_sum"] / ws,
            "predY": c["predY_sum"] / ws,
            "weight": ws
        })
    return condensed_anchors

def procrustes_alignment(
    ekf_data,
    lat0,
    lon0,
    weight_field="calibWeight",
    default_weight=1.0,
    dist_thresh=1.0,
    pred_x_field="ekfLocalX",
    pred_y_field="ekfLocalY",
    output_local=False
):
    """
    Perform a weighted Procrustes alignment using condensed anchor points.
    
    If output_local is True, the aligned coordinates (in local metric units) are
    used to update the predicted fields directly (e.g. pdrX/pdrY).
    
    Otherwise, the aligned local coordinates are converted back to lat/lon and stored
    in new fields ("ekfLat_aligned" and "ekfLng_aligned").
    """
    # 1) Gather & cluster anchor points.
    condensed_anchors = cluster_anchors(
        ekf_data,
        lat0=lat0,
        lon0=lon0,
        pred_x_field=pred_x_field,
        pred_y_field=pred_y_field,
        weight_field=weight_field,
        default_weight=default_weight,
        dist_thresh=dist_thresh
    )
    if len(condensed_anchors) < 2:
        print("procrustes_alignment: Not enough anchor points after clustering, skipping.")
        return ekf_data, None
    # 2) Build arrays for SVD.
    X = []  # predicted XY.
    Y = []  # user (calibration) XY.
    W = []  # weight.
    for c in condensed_anchors:
        X.append([c["predX"], c["predY"]])
        Y.append([c["userX"], c["userY"]])
        W.append(c["weight"])
    X = np.array(X)
    Y = np.array(Y)
    w = np.array(W).reshape(-1, 1)
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
    scale = numerator / denominator if denominator > 1e-12 else 1.0
    T_vec = muY - scale * (muX @ R_mat)
    # 3) Apply transform.
    for rec in ekf_data:
        px = rec.get(pred_x_field, 0.0)
        py = rec.get(pred_y_field, 0.0)
        aligned_local = scale * np.dot([px, py], R_mat) + T_vec
        if output_local:
            # Update the predicted local coordinates directly.
            rec[pred_x_field] = aligned_local[0]
            rec[pred_y_field] = aligned_local[1]
        else:
            aligned_lat, aligned_lon = local_xy_to_latlon(aligned_local[0], aligned_local[1], lat0, lon0)
            rec["ekfLat_aligned"] = aligned_lat
            rec["ekfLng_aligned"] = aligned_lon
    transform = {
        "scale": scale,
        "rotation": R_mat,
        "translation": T_vec,
    }
    return ekf_data, transform
