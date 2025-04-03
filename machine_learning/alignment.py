import numpy as np

def procrustes_similarity(P, Q):
    """
    Perform a 2D Procrustes analysis with scaling to compute the similarity transform from P -> Q.
    
    Parameters:
        P (np.ndarray): shape (N,2), source point set (to be aligned)
        Q (np.ndarray): shape (N,2), target point set (ground truth or anchor-aligned coordinates)

    Returns:
        s (float): uniform scaling factor
        R (np.ndarray): shape (2,2), rotation matrix
        t (np.ndarray): shape (2,), translation vector

    The transform follows: X' = s * R * X + t
    """
    p_mean = np.mean(P, axis=0)
    q_mean = np.mean(Q, axis=0)

    P_centered = P - p_mean
    Q_centered = Q - q_mean

    M = np.dot(P_centered.T, Q_centered)
    U, S, Vt = np.linalg.svd(M)
    R_ = np.dot(Vt.T, U.T)

    if np.linalg.det(R_) < 0:
        Vt[-1, :] *= -1
        R_ = np.dot(Vt.T, U.T)

    norm_p2 = np.sum(P_centered**2)
    s_ = np.sum(S) / norm_p2 if norm_p2 > 1e-12 else 1.0
    t_ = q_mean - s_ * R_.dot(p_mean)

    return s_, R_, t_

def piecewise_procrustes_alignment(
    data,
    lat0,
    lon0,
    pred_x_field="pdrX",
    pred_y_field="pdrY",
    true_lat_field="userLat",
    true_lng_field="userLng",
    anchor_flag_field="isCalibration",
    latlon_to_local_xy=None,
    smoothing_count=0,
    debug=True
):
    """
    Perform piecewise Procrustes similarity transformation alignment on data.
    For each pair of adjacent anchor points, compute a transform that aligns the segment.
    The i+1 anchor is excluded from being transformed by the current segment.

    Parameters:
        data (list): List of data points (dicts) to align
        lat0, lon0: Origin reference latitude and longitude for coordinate transformation
        pred_x_field, pred_y_field (str): Keys for predicted (unadjusted) local XY fields
        true_lat_field, true_lng_field (str): Keys for true GPS coordinates
        anchor_flag_field (str): Key indicating whether a record is an anchor
        latlon_to_local_xy (function): Function to convert (lat, lon) to local XY
        smoothing_count (int): Unused currently (reserved)
        debug (bool): Whether to print debug info

    Returns:
        aligned_data (list): Modified data with adjusted coordinates
        transforms (list): List of transformations applied per segment
    """
    if latlon_to_local_xy is None:
        raise ValueError("The 'latlon_to_local_xy' function must be provided.")

    data = sorted(data, key=lambda r: r["timestamp"])

    anchors = []
    for i, r in enumerate(data):
        if r.get(anchor_flag_field, False) is True:
            anchors.append({"index": i, "timestamp": r["timestamp"]})

    if debug:
        print("=== piecewise_procrustes_alignment DEBUG START ===")
        print(f"  Found total {len(anchors)} anchors with flag={anchor_flag_field}.")
    if len(anchors) < 2:
        if debug: print("  Not enough anchors (less than 2). Skipping alignment.")
        return data, []

    transforms = []

    for seg_idx in range(len(anchors) - 1):
        start_i = anchors[seg_idx]["index"]
        end_i   = anchors[seg_idx + 1]["index"]
        if start_i > end_i:
            start_i, end_i = end_i, start_i

        if debug:
            print(f"\n[Segment {seg_idx}] range = [{start_i}, {end_i})")

        seg_anchor_src = []
        seg_anchor_tgt = []
        anchor_indices = []

        for i_pt in range(start_i, end_i + 1):  # Include end_i for alignment reference
            r_pt = data[i_pt]
            if r_pt.get(anchor_flag_field, False) is True:
                px = r_pt[pred_x_field]
                py = r_pt[pred_y_field]
                lat_val = r_pt.get(true_lat_field)
                lng_val = r_pt.get(true_lng_field)
                if lat_val is None or lng_val is None:
                    continue
                tx, ty = latlon_to_local_xy(lat_val, lng_val, lat0, lon0)
                seg_anchor_src.append((px, py))
                seg_anchor_tgt.append((tx, ty))
                anchor_indices.append(i_pt)

        if debug:
            print(f"  - Found {len(seg_anchor_src)} anchors inside this segment.")
            if len(seg_anchor_src) > 0:
                print(f"    anchor indices = {anchor_indices}")

        if len(seg_anchor_src) < 2:
            if debug:
                print(f"  [Warning] Segment {seg_idx} has <2 anchors, skipping alignment.")
            continue

        P = np.array(seg_anchor_src, dtype=np.float64)
        Q = np.array(seg_anchor_tgt, dtype=np.float64)
        s, R, t = procrustes_similarity(P, Q)

        if debug:
            print(f"  => Procrustes result: scale={s:.3f}")
            print(f"     R=\n{R}")
            print(f"     t={t}")

        # Apply transform: note that end_i is not modified (i+1 anchor)
        for i_pt in range(start_i, end_i):
            r_pt = data[i_pt]
            px = r_pt[pred_x_field]
            py = r_pt[pred_y_field]
            new_xy = s * R.dot([px, py]) + t
            r_pt[pred_x_field] = new_xy[0]
            r_pt[pred_y_field] = new_xy[1]

        transforms.append(((start_i, end_i), (s, R, t)))

    if debug:
        print("=== piecewise_procrustes_alignment DEBUG END ===\n")

    return data, transforms
