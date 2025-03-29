import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
from sklearn.model_selection import train_test_split
import tensorflow as tf
from tensorflow.keras.layers import Input, Embedding, Flatten, Dense, Concatenate
from tensorflow.keras.models import Model
import json
import random
from tensorflow.keras.layers import Input, Embedding, Dense, Concatenate, LSTM  # 去掉Flatten
import tensorflow as tf

# 你自己的工具函数
from utils import load_json_records, get_origin, latlon_to_local_xy, local_xy_to_latlon
from alignment import piecewise_procrustes_alignment
# 仅用于可视化对齐后PDR（可选）
from plotting import plot_results

def main():
    # ========== (A) 批量处理每个文件，做对齐 + trim + 转回经纬度 ==========
    file_list = glob.glob("./data_floor1_flat/*.json")
    if not file_list:
        print("未在 ./data 目录下找到任何 .json 文件，退出。")
        sys.exit(1)
    
    all_aligned_trimmed = []
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
        
        # 保存一份原始 PDR
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)
        
        # 分段对齐
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
        
        # 找到第一个 & 最后一个校准点
        anchor_indices = [i for i, r in enumerate(data_aligned) if r.get("isCalibration") is True]
        if len(anchor_indices) < 2:
            print(f"文件 {json_file} 锚点不足2个, 跳过。")
            continue
        
        first_anchor_idx = anchor_indices[0]
        last_anchor_idx = anchor_indices[-1]
        trimmed = data_aligned[first_anchor_idx:last_anchor_idx+1]
        
        # 转回绝对经纬度
        for r in trimmed:
            aligned_lat, aligned_lng = local_xy_to_latlon(r["pdrX"], r["pdrY"], lat0_file, lon0_file)
            r["alignedLat"] = aligned_lat
            r["alignedLng"] = aligned_lng
            
            raw_lat, raw_lng = local_xy_to_latlon(r["rawPdrX"], r["rawPdrY"], lat0_file, lon0_file)
            r["rawLat"] = raw_lat
            r["rawLng"] = raw_lng
        
        all_aligned_trimmed.extend(trimmed)
    
    if not all_aligned_trimmed:
        print("无法得到有效对齐+trim数据，退出。")
        sys.exit(1)
    
    print(f"对齐并trim后的记录总数: {len(all_aligned_trimmed)}")

    # ========== (B) 选一个全局原点（仅用于plotting对比） ==========
    global_lat0, global_lon0 = None, None
    for r in all_aligned_trimmed:
        if r.get("isCalibration"):
            global_lat0 = r["alignedLat"]
            global_lon0 = r["alignedLng"]
            break
    if global_lat0 is None:
        global_lat0 = all_aligned_trimmed[0]["alignedLat"]
        global_lon0 = all_aligned_trimmed[0]["alignedLng"]
    print(f"选取的全局原点 global_lat0={global_lat0}, global_lon0={global_lon0}")

    # 将对齐后数据转换到全局坐标 (pdrX, pdrY) 用于 plot_results
    for r in all_aligned_trimmed:
        gx, gy = latlon_to_local_xy(r["alignedLat"], r["alignedLng"], global_lat0, global_lon0)
        r["pdrX"] = gx
        r["pdrY"] = gy
        rgx, rgy = latlon_to_local_xy(r["rawLat"], r["rawLng"], global_lat0, global_lon0)
        r["rawPdrX"] = rgx
        r["rawPdrY"] = rgy
    
    plot_results(all_aligned_trimmed, global_lat0, global_lon0, use_wifi=False)
    print("已生成对齐前后PDR对比图 (debug_plot.png).")

    # ========== (C) 构建含 BSSID + RSSI 的数据集 (Top-N) ==========
    TOP_N = 4
    dataset = []
    bssid_set = set()

    for r in all_aligned_trimmed:
        wifi_list = r.get("wifiList", [])
        if not wifi_list:
            continue

        # 1) 按 RSSI 降序排序，取前 TOP_N
        wifi_sorted = sorted(wifi_list, key=lambda ap: ap["rssi"], reverse=True)
        wifi_top_n = wifi_sorted[:TOP_N]
        
        rssi_features = [ap["rssi"] for ap in wifi_top_n]
        bssid_features = [str(ap["bssid"]) for ap in wifi_top_n]  # 转为字符串
        if len(rssi_features) < TOP_N:
            # 不足的补 -100 或空字符串
            rssi_features += [-100]*(TOP_N - len(rssi_features))
            bssid_features += [""]*(TOP_N - len(bssid_features))
        
        # 收集所有 BSSID
        for b in bssid_features:
            if b:  # 非空
                bssid_set.add(b)

        lat_true = r["alignedLat"]
        lon_true = r["alignedLng"]
        dataset.append({
            "rssi": rssi_features,
            "bssid": bssid_features,
            "lat_true": lat_true,
            "lon_true": lon_true
        })

    if not dataset:
        print("无法构建有效 dataset，退出。")
        sys.exit(1)

    # ========== (D) BSSID 字典构建：把每个 BSSID 映射为整数ID ==========
    # 保留 0 做为 padding
    bssid_list = sorted(list(bssid_set))
    bssid2idx = {b: i+1 for i, b in enumerate(bssid_list)}
    vocab_size = len(bssid2idx) + 1
    print(f"BSSID vocab_size = {vocab_size} (含 padding)")

    # ========== (E) 构造训练数据 (X_bssid, X_rssi, Y) ==========
    X_bssid = []
    X_rssi = []
    Y = []
    for d in dataset:
        # 将 BSSID 转为 int 索引
        bssid_idx = [bssid2idx.get(b, 0) for b in d["bssid"]]
        X_bssid.append(bssid_idx)
        # 直接用 RSSI 值
        X_rssi.append(d["rssi"])
        Y.append([d["lat_true"], d["lon_true"]])
    
    # 循环结束后统一转换为数组
    X_bssid = np.array(X_bssid, dtype=np.int32)
    X_rssi = np.array(X_rssi, dtype=np.float32)

    np.save("X_rssi.npy", X_rssi)        # shape = [N, 20]

    # 将 RSSI 映射到正数，防止对数为负无穷（最低值 -100，加 110 → 范围是 [10, 110]）
    X_rssi_shifted = X_rssi + 110.0

    # 对数变换
    X_rssi_log = np.log(X_rssi_shifted)

    # 线性归一化到 [0, 1]
    log_min = np.min(X_rssi_log)
    log_max = np.max(X_rssi_log)
    X_rssi_norm = (X_rssi_log - log_min) / (log_max - log_min)

    # 保存归一化用的 log 参数，便于部署还原
    rssi_norm_params = {
        "log_shift": 110.0,
        "log_min": float(log_min),
        "log_max": float(log_max)
    }
    with open("rssi_normalization_params.json", "w") as f:
        json.dump(rssi_norm_params, f, indent=2)

    X_rssi = X_rssi_norm  # 替换原始变量

    Y = np.array(Y, dtype=np.float32)

    # 归一化标签 Y 到 [0, 1]
    lat_min, lat_max = Y[:, 0].min(), Y[:, 0].max()
    lon_min, lon_max = Y[:, 1].min(), Y[:, 1].max()
    lat_range = lat_max - lat_min
    lon_range = lon_max - lon_min

    norm_params = {
        "lat_min": float(lat_min),
        "lat_max": float(lat_max),
        "lon_min": float(lon_min),
        "lon_max": float(lon_max)
    }
    with open("normalization_params.json", "w") as f:
        json.dump(norm_params, f, indent=2)
    print("已保存归一化参数到 normalization_params.json")

    if lat_range == 0 or lon_range == 0:
        print("经纬度范围为0，可能数据只有一个位置，无法归一化，退出。")
        sys.exit(1)

    Y_norm = np.copy(Y)
    Y_norm[:, 0] = (Y_norm[:, 0] - lat_min) / lat_range
    Y_norm[:, 1] = (Y_norm[:, 1] - lon_min) / lon_range
    Y = Y_norm


    np.save("Y_coord.npy", Y_norm)       # shape = [N, 2]


    X_bssid_train, X_bssid_test, X_rssi_train, X_rssi_test, Y_train, Y_test = train_test_split(
        X_bssid, X_rssi, Y, test_size=0.3, random_state=42
    )

    # ========== (F) 构建模型：BSSID embedding + RSSI (双输入) → 输出经纬度 ==========
    # 1) BSSID 输入，增加 name 用于后续提取 embedding 权重
    bssid_input = Input(shape=(TOP_N,), dtype='int32', name='bssid_input')
    embedding_dim = 16
    bssid_emb = Embedding(input_dim=vocab_size, output_dim=embedding_dim, input_length=TOP_N, 
                          mask_zero=True, name='bssid_embedding')(bssid_input)
    bssid_flat = Flatten()(bssid_emb)

    # 2) RSSI 输入
    rssi_input = Input(shape=(TOP_N,), dtype='float32', name='rssi_input')

    # 3) 拼接
    merged = Concatenate()([bssid_flat, rssi_input])
    x = Dense(128, activation='relu')(merged)
    x = Dense(64, activation='relu')(x)
    x = Dense(64, activation='relu')(x)
    x = Dense(32, activation='relu')(x)
    output = Dense(2)(x)

    model = Model(inputs=[bssid_input, rssi_input], outputs=output)
    model.compile(optimizer='adam', loss='mse', metrics=['mae'])
    model.summary()

    print("开始训练模型 (BSSID + RSSI)...")
    history = model.fit(
        [X_bssid_train, X_rssi_train],
        Y_train,
        epochs=10,
        batch_size=16,
        validation_split=0.2,
        verbose=1
    )

    # 评估
    loss, mae = model.evaluate([X_bssid_test, X_rssi_test], Y_test, verbose=0)
    print(f"测试集 MAE: {mae:.6f} 度")

    # 预测
    Y_pred = model.predict([X_bssid_test, X_rssi_test])

    # 反归一化预测结果：将预测值从 [0,1] 恢复到原始经纬度范围
    Y_pred_denorm = np.copy(Y_pred)
    Y_pred_denorm[:, 0] = Y_pred_denorm[:, 0] * lat_range + lat_min
    Y_pred_denorm[:, 1] = Y_pred_denorm[:, 1] * lon_range + lon_min

    # 同样对测试集标签也反归一化（便于比较）
    Y_test_denorm = np.copy(Y_test)
    Y_test_denorm[:, 0] = Y_test_denorm[:, 0] * lat_range + lat_min
    Y_test_denorm[:, 1] = Y_test_denorm[:, 1] * lon_range + lon_min

    # ========== (F++) 可视化 Loss 曲线 ==========
    plt.figure(figsize=(8, 5))
    plt.plot(history.history['loss'], label='Train Loss')
    plt.plot(history.history['val_loss'], label='Val Loss')
    plt.title("Model Training Loss Curve")
    plt.xlabel("Epoch")
    plt.ylabel("Loss (MSE)")
    plt.legend()
    plt.grid(True)
    plt.tight_layout()
    plt.savefig("loss_curve.png", dpi=200)
    plt.show()


    # ========== (G) 可视化预测 vs True ==========
    plt.figure(figsize=(8, 6))
    plt.scatter(Y_test_denorm[:, 0], Y_test_denorm[:, 1], marker='o', alpha=0.6, label='True (lat, lon)')
    plt.scatter(Y_pred_denorm[:, 0], Y_pred_denorm[:, 1], marker='x', alpha=0.6, label='Predicted')

    # 随机采样一部分点画箭头
    num_arrows = min(200, len(Y_test_denorm))  # 最多200个箭头
    sample_indices = random.sample(range(len(Y_test_denorm)), num_arrows)

    for i in sample_indices:
        plt.annotate(
            '',  # 无文字
            xy=(Y_test_denorm[i, 0], Y_test_denorm[i, 1]),          # 终点 (真值)
            xytext=(Y_pred_denorm[i, 0], Y_pred_denorm[i, 1]),      # 起点 (预测值)
            arrowprops=dict(
                arrowstyle="->",
                color='gray',
                alpha=0.3,
                linewidth=1
            )
        )

    plt.title("BSSID Embedding + RSSI → (Lat, Lon)")
    plt.xlabel("Latitude")
    plt.ylabel("Longitude")
    plt.legend()
    plt.grid(True)
    plt.axis("equal")
    plt.tight_layout()
    plt.savefig("bssid_embed_result_with_arrows.png", dpi=200)
    plt.show()



    # 计算误差（单位为经纬度，若需要转换为米，请另外计算）
    errors = np.linalg.norm(Y_pred_denorm - Y_test_denorm, axis=1)
    mean_error = np.mean(errors)
    std_error = np.std(errors)
    print(f"预测误差均值: {mean_error:.6f} 度")
    print(f"预测误差标准差: {std_error:.6f} 度")

    # ========== (H) 导出 BSSID embedding 权重 ==========
    # 从 embedding 层提取权重（形状为 [vocab_size, embedding_dim]）
    embedding_matrix = model.get_layer("bssid_embedding").get_weights()[0]
    # 构造 bssid -> embedding 映射（注意：索引0为 padding，不导出）
    bssid_embedding_dict = {bssid: embedding_matrix[idx].tolist() for bssid, idx in bssid2idx.items()}
    with open("bssid_embedding.json", "w") as f:
        json.dump(bssid_embedding_dict, f, indent=2)
    print("已导出 BSSID embedding 权重到 bssid_embedding.json")
    
    # ========== (I) 如需导出 TFLite 模型 ==========
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    with open("tf_model_bssid_embed.tflite", "wb") as f:
        f.write(tflite_model)
    print("已导出 TFLite 模型 (tf_model_bssid_embed.tflite).")
    

if __name__ == "__main__":
    main()
