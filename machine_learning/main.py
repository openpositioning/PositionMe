import numpy as np
import sys

from utils import load_json_records, get_origin, latlon_to_local_xy, preprocess_standstill_near_anchors
from ekf import ekf_fusion
from plotting import plot_results

# 从当前目录导入 alignment 模块
from alignment import piecewise_procrustes_alignment

def main():
    # 1) 读取数据
    data_path = "./data/collection_data_2304FPN6DG_20250327_1600.json"
    data = load_json_records(data_path)
    if not data:
        print("No data loaded, exiting.")
        sys.exit(1)

    # 2) 选择使用 WiFi 还是 GNSS
    use_wifi = True  # 或者 False

    # 示例参数
    pdr_process_noise = 0.5
    gnss_measurement_noise = 50.0
    robust_threshold = 3.0
    inflation_factor = 15.0
    ekf_calib_weight = 1.5

    # 3) 获取参考原点（用于局部坐标转换）
    lat0, lon0 = get_origin(data, use_wifi)
    if lat0 is None or lon0 is None:
        print("无法确定参考原点.")
        sys.exit(1)

    # 4) 保存一份原始 PDR 数据以便后续对比
    for r in data:
        r["rawPdrX"] = r.get("pdrX", 0.0)
        r["rawPdrY"] = r.get("pdrY", 0.0)

    # # 5) 预处理：对锚点前后站立数据做特殊处理
    # # 当你在打锚时如果站在原地一段时间，多条记录几乎相同但略有抖动，这里把它们固定为同一坐标
    # data = preprocess_standstill_near_anchors(
    #     data=data,
    #     anchor_flag_field="isCalibration",
    #     standstill_window=5,      # 前后各 5 条记录
    #     dist_threshold=0.2,       # 距离阈值（单位与 pdrX,pdrY 同）
    #     step_counter_field="stepCounter",
    #     pred_x_field="pdrX",
    #     pred_y_field="pdrY"
    # )

    # 6) 第一次分段对齐：对 (pdrX, pdrY) 做多锚点相似变换
    data_aligned, transforms1 = piecewise_procrustes_alignment(
        data=data,
        lat0=lat0,
        lon0=lon0,
        pred_x_field="pdrX",
        pred_y_field="pdrY",
        true_lat_field="userLat",     # 校准真值的字段
        true_lng_field="userLng",
        anchor_flag_field="isCalibration",
        smoothing_count=30,           # 可调 overlap 数量
        latlon_to_local_xy=latlon_to_local_xy,
        debug=True
    )
    print(">>> First alignment done. Segments transforms:")
    for seg, (s, R, t) in transforms1:
        print(f" segment={seg}, scale={s}, R=\n{R}, t={t}")

    # 7) EKF 融合 (此时 pdrX/pdrY 已经过第一次分段对齐修正)
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

    # 8) 第二次对齐（可选）：对 EKF 融合输出再次分段对齐
    ekf_data_aligned, transforms2 = piecewise_procrustes_alignment(
        data=ekf_data,
        lat0=lat0,
        lon0=lon0,
        pred_x_field="ekfLocalX",  # EKF 融合输出字段
        pred_y_field="ekfLocalY",
        true_lat_field="userLat",
        true_lng_field="userLng",
        anchor_flag_field="isCalibration",
        smoothing_count=0,
        latlon_to_local_xy=latlon_to_local_xy,
        debug=True
    )
    print(">>> Second alignment done. Segments transforms:")
    for seg, (s, R, t) in transforms2:
        print(f" segment={seg}, scale={s}, R=\n{R}, t={t}")

    # 9) 如果需要，将 EKF 对齐结果反算回经纬度（示例代码，需自行实现 local_xy_to_latlon）
    # for r in ekf_data_aligned:
    #     lat_aligned, lng_aligned = local_xy_to_latlon(
    #         r["ekfLocalX"], r["ekfLocalY"], lat0, lon0
    #     )
    #     r["ekfLat_aligned"] = lat_aligned
    #     r["ekfLng_aligned"] = lng_aligned

    # 10) 绘图查看结果
    plot_results(ekf_data_aligned, lat0, lon0, use_wifi=use_wifi)

if __name__ == "__main__":
    main()
