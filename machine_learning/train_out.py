import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
from sklearn.model_selection import train_test_split
from sklearn.neighbors import KNeighborsRegressor
import tensorflow as tf
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import Dense

# 你自己的工具函数
from utils import load_json_records, get_origin, latlon_to_local_xy, local_xy_to_latlon
from alignment import piecewise_procrustes_alignment
# 这里导入你给出的 plot_results 函数 (会使用到 rawPdrX/Y 和 pdrX/Y 字段作对比)
from plotting import plot_results

def main():
    # ========== (A) 批量处理每个文件 ==========
    file_list = glob.glob("./data/*.json")
    if not file_list:
        print("未在 ./data 目录下找到任何 .json 文件，退出。")
        sys.exit(1)
    
    all_aligned_trimmed = []  # 用来存放“对齐 + 修剪 + 转回经纬度”后的记录

    for json_file in file_list:
        print(f"处理文件: {json_file}")
        data = load_json_records(json_file)
        if not data:
            print(f"文件 {json_file} 数据为空, 跳过。")
            continue
        
        lat0_file, lon0_file = get_origin(data, use_wifi=False)
        if lat0_file is None or lon0_file is None:
            print(f"文件 {json_file} 无法确定参考原点, 跳过。")
            continue
        
        # 保存一份原始PDR (对比用)
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)
        
        # PDR 分段对齐 (仅针对本文件)
        data_aligned, transforms = piecewise_procrustes_alignment(
            data=data,
            lat0=lat0_file,
            lon0=lon0_file,
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
        
        # 找到第一个、最后一个校准点(锚点)，对数据进行trim
        anchor_indices = [i for i, r in enumerate(data_aligned) if r.get("isCalibration") is True]
        if len(anchor_indices) < 2:
            print(f"文件 {json_file} 锚点数量不足2个, 无法trim, 跳过。")
            continue
        
        first_anchor_idx = anchor_indices[0]
        last_anchor_idx = anchor_indices[-1]
        trimmed = data_aligned[first_anchor_idx:last_anchor_idx+1]
        
        # 转回“该文件原点”的经纬度
        for r in trimmed:
            r["fileLat0"] = lat0_file
            r["fileLon0"] = lon0_file
            
            # 对齐后的 pdrX, pdrY 转换为经纬度
            aligned_lat, aligned_lng = local_xy_to_latlon(
                r["pdrX"], r["pdrY"], lat0_file, lon0_file
            )
            r["alignedLat"] = aligned_lat
            r["alignedLng"] = aligned_lng

            # 原始 pdr 同样转换
            raw_lat, raw_lng = local_xy_to_latlon(
                r["rawPdrX"], r["rawPdrY"], lat0_file, lon0_file
            )
            r["rawLat"] = raw_lat
            r["rawLng"] = raw_lng
        
        all_aligned_trimmed.extend(trimmed)
    
    if not all_aligned_trimmed:
        print("所有文件都无法得到有效对齐+修剪数据, 退出。")
        sys.exit(1)
    
    print(f"对齐并修剪后的记录总数: {len(all_aligned_trimmed)}")

    # ========== (B) 选一个全局原点，将所有数据统一到同一张“局部坐标系”里 ==========
    global_lat0 = None
    global_lon0 = None
    for r in all_aligned_trimmed:
        if r.get("isCalibration"):
            global_lat0 = r["alignedLat"]
            global_lon0 = r["alignedLng"]
            break
    if global_lat0 is None:
        global_lat0 = all_aligned_trimmed[0]["alignedLat"]
        global_lon0 = all_aligned_trimmed[0]["alignedLng"]
    
    print(f"选取的全局原点 global_lat0={global_lat0}, global_lon0={global_lon0}")

    for r in all_aligned_trimmed:
        gx, gy = latlon_to_local_xy(r["alignedLat"], r["alignedLng"], global_lat0, global_lon0)
        r["pdrX"] = gx
        r["pdrY"] = gy

        rgx, rgy = latlon_to_local_xy(r["rawLat"], r["rawLng"], global_lat0, global_lon0)
        r["rawPdrX"] = rgx
        r["rawPdrY"] = rgy

    # ========== (C) 可视化：对齐前后 PDR 对比 ==========
    plot_results(all_aligned_trimmed, global_lat0, global_lon0, use_wifi=False)
    print("已生成对齐前后PDR对比图 (debug_plot.png).")
    
    # ========== (D) 基于对齐后PDR + WiFi RSSI (Top N) 训练 kNN ==========
    TOP_N = 20
    dataset = []
    for r in all_aligned_trimmed:
        wifi_list = r.get("wifiList", [])
        if not wifi_list:
            continue
        
        # 取 wifiList 中 RSSI 最强的 TOP_N 个
        wifi_sorted = sorted(wifi_list, key=lambda ap: ap["rssi"], reverse=True)
        wifi_top_n = wifi_sorted[:TOP_N]
        rssi_features = [ap["rssi"] for ap in wifi_top_n]
        if len(rssi_features) < TOP_N:
            rssi_features += [-100]*(TOP_N - len(rssi_features))
        
        x_aligned = r["pdrX"]
        y_aligned = r["pdrY"]
        
        dataset.append({
            "features": rssi_features,
            "x_true": x_aligned,
            "y_true": y_aligned
        })
    
    X = np.array([d["features"] for d in dataset])
    Y = np.array([[d["x_true"], d["y_true"]] for d in dataset])
    
    X_train, X_test, Y_train, Y_test = train_test_split(X, Y, test_size=0.3, random_state=42)
    
    knn = KNeighborsRegressor(n_neighbors=5)
    knn.fit(X_train, Y_train)
    
    Y_pred = knn.predict(X_test)
    
    # ========== (E) 可视化 kNN 预测 vs 对齐后 PDR ==========
    plt.figure(figsize=(8,6))
    plt.scatter(Y_test[:,0], Y_test[:,1], marker='o', alpha=0.6, label='True (Aligned PDR)')
    plt.scatter(Y_pred[:,0], Y_pred[:,1], marker='x', alpha=0.6, label='kNN Predicted')
    plt.title("WiFi RSSI → Position Prediction\n(Using Aligned+Trimmed PDR as Labels)")
    plt.xlabel("Global Local X (meters)")
    plt.ylabel("Global Local Y (meters)")
    plt.legend()
    plt.grid(True)
    plt.axis("equal")
    plt.tight_layout()
    plt.savefig("knn_result.png", dpi=200)
    plt.show()
    print("已生成 kNN 预测结果散点图 (knn_result.png).")
    
    errors = np.linalg.norm(Y_pred - Y_test, axis=1)
    mean_error = np.mean(errors)
    std_error = np.std(errors)
    print(f"kNN 预测误差均值: {mean_error:.2f} 米")
    print(f"kNN 预测误差标准差: {std_error:.2f} 米")
    print("kNN 预测完成.")
    
    # ========== (F) 使用 TensorFlow 训练模型并导出 TensorFlow Lite 模型 ==========
    print("开始训练 TensorFlow 模型...")
    tf_model = Sequential([
        Dense(64, activation='relu', input_shape=(TOP_N,)),
        Dense(64, activation='relu'),
        Dense(2)  # 输出全局局部坐标 (x, y)
    ])
    tf_model.compile(optimizer='adam', loss='mse', metrics=['mae'])
    
    tf_model.fit(X_train, Y_train, epochs=50, batch_size=32, validation_split=0.2)
    
    tf_loss, tf_mae = tf_model.evaluate(X_test, Y_test, verbose=0)
    print(f"TensorFlow 模型测试集 MAE: {tf_mae:.2f} 米")
    
    # 导出 TensorFlow Lite 模型
    converter = tf.lite.TFLiteConverter.from_keras_model(tf_model)
    tflite_model = converter.convert()
    with open("tf_model.tflite", "wb") as f:
        f.write(tflite_model)
    print("已导出 TensorFlow Lite 模型 (tf_model.tflite).")

if __name__ == "__main__":
    main()
