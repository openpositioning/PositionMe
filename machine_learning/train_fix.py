import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
from sklearn.model_selection import train_test_split
from sklearn.neighbors import KNeighborsRegressor

# 你自己的工具函数
from utils import load_json_records, get_origin, latlon_to_local_xy
from alignment import piecewise_procrustes_alignment
from plotting import plot_results

def main():
    # 1) 找到 /data 目录下所有的 .json 文件
    file_list = glob.glob("./data/*.json")
    if not file_list:
        print("未在 ./data 目录下找到任何 .json 文件，退出。")
        sys.exit(1)
    
    all_aligned_trimmed = []  # 用来存放所有文件 对齐+修剪 后的记录

    # ---------- (A) 批量对齐 & 修剪 ----------
    for json_file in file_list:
        print(f"处理文件: {json_file}")
        # a) 读取文件
        data = load_json_records(json_file)
        if not data:
            print(f"文件 {json_file} 数据为空, 跳过。")
            continue
        
        # b) 获取原点
        lat0, lon0 = get_origin(data, use_wifi=False)
        if lat0 is None or lon0 is None:
            print(f"文件 {json_file} 无法确定参考原点, 跳过。")
            continue
        
        # c) 保存原始pdr，以便对比
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)
        
        # d) 做PDR分段对齐
        data_aligned, transforms = piecewise_procrustes_alignment(
            data=data,
            lat0=lat0,
            lon0=lon0,
            pred_x_field="pdrX",
            pred_y_field="pdrY",
            true_lat_field="userLat",
            true_lng_field="userLng",
            anchor_flag_field="isCalibration",
            smoothing_count=30,
            latlon_to_local_xy=latlon_to_local_xy,
            debug=False
        )
        if not data_aligned:
            print(f"文件 {json_file} 对齐后数据为空, 跳过。")
            continue
        
        # e) 找第一个和最后一个锚点，修剪数据
        anchor_indices = [i for i, rr in enumerate(data_aligned) if rr.get("isCalibration") is True]
        if len(anchor_indices) < 2:
            print(f"文件 {json_file} 锚点不足2个, 无法trim, 跳过。")
            continue
        
        first_anchor_idx = anchor_indices[0]
        last_anchor_idx = anchor_indices[-1]
        trimmed = data_aligned[first_anchor_idx : last_anchor_idx + 1]
        
        # f) 将此文件的trim结果加入总列表
        all_aligned_trimmed.extend(trimmed)
    
    if not all_aligned_trimmed:
        print("所有文件都无法得到有效对齐+修剪数据, 退出。")
        sys.exit(1)
    
    # 打印一下拼接后数据量
    print(f"对齐并修剪后的记录总数: {len(all_aligned_trimmed)}")

    # ---------- (A.1) 可选：先做一次整体可视化，查看对齐前后 ----------
    plot_results(all_aligned_trimmed, lat0, lon0, use_wifi=False)
    print("已生成对齐前后PDR对比图 (debug_plot.png).")

    # ---------- (B) 清理离锚点过远的“异常”对齐 PDR ----------
    # 1) 收集锚点在对齐坐标系下的位置
    anchor_positions = []
    for r in all_aligned_trimmed:
        if r.get("isCalibration") is True:
            anchor_positions.append((r["pdrX"], r["pdrY"]))
    anchor_positions = np.array(anchor_positions)
    
    if anchor_positions.shape[0] == 0:
        print("意外：没有任何锚点可做过滤基准，跳过过滤。")
    else:
        # 2) 设定一个距离阈值(米)，超过这个值就清理掉
        distance_threshold = 5.0  # 你可根据场景进行调整
        
        filtered_data = []
        for r in all_aligned_trimmed:
            px, py = r["pdrX"], r["pdrY"]
            # 计算 与所有锚点的距离，取最小值
            dists = np.linalg.norm(anchor_positions - np.array([px, py]), axis=1)
            min_dist = np.min(dists)
            
            # 如果最小距离小于阈值，才保留
            if min_dist <= distance_threshold:
                filtered_data.append(r)
        
        print(f"过滤前数据量: {len(all_aligned_trimmed)}, 过滤后数据量: {len(filtered_data)}")
        
        all_aligned_trimmed = filtered_data
    
    if not all_aligned_trimmed:
        print("过滤后无数据, 退出。")
        sys.exit(1)
    
    # 再次可视化过滤后的结果
    plot_results(all_aligned_trimmed, lat0, lon0, use_wifi=False)
    print("已生成过滤异常后的对齐轨迹图 (debug_plot_filtered.png).")

    # ---------- (C) 基于对齐后(且过滤完异常点) 的PDR + WiFi RSSI (Top 20) 训练kNN ----------
    TOP_N = 20
    dataset = []
    for r in all_aligned_trimmed:
        wifi_list = r.get("wifiList", [])
        if not wifi_list:
            continue
        
        wifi_sorted = sorted(wifi_list, key=lambda ap: ap["rssi"], reverse=True)
        wifi_top_n = wifi_sorted[:TOP_N]
        
        rssi_features = [ap["rssi"] for ap in wifi_top_n]
        if len(rssi_features) < TOP_N:
            rssi_features += [-100] * (TOP_N - len(rssi_features))
        
        dataset.append({
            "features": rssi_features,
            "x_true": r["pdrX"],
            "y_true": r["pdrY"]
        })
    
    X = np.array([d["features"] for d in dataset])
    Y = np.array([[d["x_true"], d["y_true"]] for d in dataset])
    
    # 划分训练/测试集
    X_train, X_test, Y_train, Y_test = train_test_split(
        X, Y, test_size=0.3, random_state=42
    )
    
    # 训练 kNN
    knn = KNeighborsRegressor(n_neighbors=5)
    knn.fit(X_train, Y_train)
    
    # 预测
    Y_pred = knn.predict(X_test)
    
    # ---------- (D) 可视化并输出误差 ----------
    plt.figure(figsize=(8, 6))
    plt.scatter(Y_test[:,0], Y_test[:,1], c='blue', marker='o', alpha=0.6, label='True (Aligned+Filtered)')
    plt.scatter(Y_pred[:,0], Y_pred[:,1], c='red', marker='x', alpha=0.6, label='Predicted by kNN')
    plt.title("WiFi RSSI → Position Prediction\n(Aligned+Trimmed+Filtered PDR)")
    plt.xlabel("Local X (m)")
    plt.ylabel("Local Y (m)")
    plt.legend()
    plt.grid(True)
    plt.axis("equal")
    plt.tight_layout()
    plt.savefig("knn_result_filtered.png", dpi=200)
    plt.show()
    
    # 误差统计
    errors = np.linalg.norm(Y_pred - Y_test, axis=1)
    mean_error = np.mean(errors)
    std_error = np.std(errors)
    print(f"预测误差均值: {mean_error:.2f} m")
    print(f"预测误差标准差: {std_error:.2f} m")

if __name__ == "__main__":
    main()
