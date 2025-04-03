import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_squared_error, mean_absolute_error

# 工具函数
from utils import load_json_records, get_origin, latlon_to_local_xy, local_xy_to_latlon
from alignment import piecewise_procrustes_alignment
from plotting import plot_results

def main():
    file_list = glob.glob("./data/*.json")
    if not file_list:
        print("未在 ./data 目录下找到任何 .json 文件，退出。")
        sys.exit(1)
    
    all_aligned_trimmed = []

    # (A) 批量对齐 & 修剪
    for json_file in file_list:
        data = load_json_records(json_file)
        if not data:
            continue
        
        lat0, lon0 = get_origin(data, use_wifi=False)
        if lat0 is None or lon0 is None:
            continue
        
        # 保存原始 PDR
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)
        
        # 分段对齐
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
            continue
        
        # 修剪
        anchor_indices = [i for i, r in enumerate(data_aligned) if r.get("isCalibration") is True]
        if len(anchor_indices) < 2:
            continue
        
        first_anchor_idx = anchor_indices[0]
        last_anchor_idx = anchor_indices[-1]
        trimmed = data_aligned[first_anchor_idx:last_anchor_idx+1]
        
        all_aligned_trimmed.extend(trimmed)
    
    if not all_aligned_trimmed:
        print("无法得到有效对齐+修剪数据.")
        sys.exit(1)
    
    print(f"对齐并修剪后的记录总数: {len(all_aligned_trimmed)}")

    # 可视化对齐结果
    plot_results(all_aligned_trimmed, lat0, lon0, use_wifi=False)
    
    # (B) 准备 WiFi RSSI 特征 + 对齐PDR标签
    TOP_N = 20
    dataset = []
    for r in all_aligned_trimmed:
        wifi_list = r.get("wifiList", [])
        if not wifi_list:
            continue
        # 取 RSSI 排名前 20
        wifi_sorted = sorted(wifi_list, key=lambda ap: ap["rssi"], reverse=True)
        wifi_top_n = wifi_sorted[:TOP_N]
        
        rssi_features = [ap["rssi"] for ap in wifi_top_n]
        if len(rssi_features) < TOP_N:
            rssi_features += [-100] * (TOP_N - len(rssi_features))
        
        x_aligned = r["pdrX"]
        y_aligned = r["pdrY"]
        dataset.append((rssi_features, x_aligned, y_aligned))
    
    if not dataset:
        print("没有可用于训练的WiFi-RSSI数据.")
        sys.exit(1)
    
    X = np.array([d[0] for d in dataset])
    Y = np.array([[d[1], d[2]] for d in dataset])  # shape=(N, 2)
    
    # (C) 划分训练集/测试集
    X_train, X_test, Y_train, Y_test = train_test_split(
        X, Y, test_size=0.3, random_state=42
    )
    
    # (D) 训练随机森林
    #     可根据需要调参，比如 n_estimators=100, max_depth=20, ...
    rf = RandomForestRegressor(
        n_estimators=100,   # 树的数量
        max_depth=None,     # 不限制深度
        random_state=42,
        n_jobs=-1           # 并行加速
    )
    # 此处为了简单，只直接预测 x,y 的平面坐标 => 可以拆成两个模型，也可以用多输出回归
    # 这里采用 scikit-learn “多目标回归” 用法，fit(Y)可以是 (N, 2)
    rf.fit(X_train, Y_train)
    
    # (E) 预测 & 评估
    Y_pred = rf.predict(X_test)  # shape=(N_test, 2)
    
    # 计算误差
    errors = np.linalg.norm(Y_pred - Y_test, axis=1)
    mean_error = np.mean(errors)
    std_error = np.std(errors)
    print(f"随机森林预测误差均值: {mean_error:.2f} m")
    print(f"随机森林预测误差标准差: {std_error:.2f} m")

    # 额外评估: RMSE, MAE
    rmse = np.sqrt(mean_squared_error(Y_test, Y_pred))
    mae = mean_absolute_error(Y_test, Y_pred)
    print(f"RMSE: {rmse:.2f} m")
    print(f"MAE: {mae:.2f} m")
    
    # (F) 可视化预测对比
    plt.figure(figsize=(8,6))
    plt.scatter(Y_test[:,0], Y_test[:,1], c='blue', alpha=0.6, marker='o', label='True')
    plt.scatter(Y_pred[:,0], Y_pred[:,1], c='red', alpha=0.6, marker='x', label='RF-Pred')
    plt.title("Random Forest Regressor\nWiFi RSSI → (X,Y)")
    plt.xlabel("Local X")
    plt.ylabel("Local Y")
    plt.legend()
    plt.grid(True)
    plt.axis('equal')
    plt.tight_layout()
    plt.show()

if __name__ == "__main__":
    main()
