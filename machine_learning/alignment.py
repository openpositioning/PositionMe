import numpy as np

def procrustes_similarity(P, Q):
    """
    使用带缩放的 Procrustes 分析（2D）求解从 P -> Q 的相似变换 (scale, rotation, translation)。
    - P: shape (N,2)，源点集 (待对齐)
    - Q: shape (N,2)，目标点集 (真值或锚点对应坐标)
    返回:
       s: float, 同比缩放系数
       R: (2,2)旋转矩阵
       t: (2,)平移向量
    令变换为: X' = s * R * X + t
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
    对 data 做分段式 Procrustes 相似变换对齐（锚点 i+1 不被上一段修改）
    """

    if latlon_to_local_xy is None:
        raise ValueError("必须传入 latlon_to_local_xy 函数。")

    data = sorted(data, key=lambda r: r["timestamp"])

    anchors = []
    for i, r in enumerate(data):
        if r.get(anchor_flag_field, False) is True:
            anchors.append({"index": i, "timestamp": r["timestamp"]})

    if debug:
        print("=== piecewise_procrustes_alignment DEBUG START ===")
        print(f"  Found total {len(anchors)} anchors with flag={anchor_flag_field}.")
    if len(anchors) < 2:
        if debug: print("  不足两个锚点，跳过对齐。")
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

        for i_pt in range(start_i, end_i + 1):  # 包含 end_i 以供对齐用
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
                print(f"  [Warning] Segment {seg_idx} has <2 anchors, skip alignment.")
            continue

        P = np.array(seg_anchor_src, dtype=np.float64)
        Q = np.array(seg_anchor_tgt, dtype=np.float64)
        s, R, t = procrustes_similarity(P, Q)

        if debug:
            print(f"  => Procrustes result: scale={s:.3f}")
            print(f"     R=\n{R}")
            print(f"     t={t}")

        # 应用变换：注意这里不更新 end_i (即 anchor i+1)
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
