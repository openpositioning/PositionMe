import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
from sklearn.model_selection import train_test_split
import tensorflow as tf
from tensorflow.keras.layers import Input, Embedding, Flatten, Dense, Concatenate
from tensorflow.keras.models import Model

# 你自己的工具函数
from utils import load_json_records, get_origin, latlon_to_local_xy, local_xy_to_latlon
from alignment import piecewise_procrustes_alignment
# 仅用于可视化对齐后PDR（可选）
from plotting import plot_results

def main():
    # ========== (A) 批量处理每个文件，做对齐 + trim + 转回经纬度 ==========
    file_list = glob.glob("./data/*.json")
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
    TOP_N = 20
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
        # 直接用 RSSI 值，若你想归一化，可以： rssi / 100.0
        X_rssi.append(d["rssi"])
        Y.append([d["lat_true"], d["lon_true"]])

    X_bssid = np.array(X_bssid, dtype=np.int32)     # shape (N, TOP_N)
    X_rssi = np.array(X_rssi, dtype=np.float32)     # shape (N, TOP_N)
    Y = np.array(Y, dtype=np.float32)               # shape (N, 2)

    X_bssid_train, X_bssid_test, X_rssi_train, X_rssi_test, Y_train, Y_test = train_test_split(
        X_bssid, X_rssi, Y, test_size=0.3, random_state=42
    )

    # ========== (F) 构建模型：BSSID embedding + RSSI (双输入) → 输出经纬度 ==========

    # 1) BSSID 输入
    bssid_input = Input(shape=(TOP_N,), dtype='int32', name='bssid_input')
    # Embedding: vocab_size, embedding_dim (可调)，input_length=TOP_N
    embedding_dim = 8
    bssid_emb = Embedding(input_dim=vocab_size, output_dim=embedding_dim, input_length=TOP_N, mask_zero=True)(bssid_input)
    # 将 [batch, TOP_N, embedding_dim] 拉直
    bssid_flat = Flatten()(bssid_emb)

    # 2) RSSI 输入
    rssi_input = Input(shape=(TOP_N,), dtype='float32', name='rssi_input')
    # 这里不做 embedding，而是直接用 float
    # 你可以考虑先做个 scale，如 rssi / 100.0

    # 3) 拼接
    merged = Concatenate()([bssid_flat, rssi_input])
    x = Dense(64, activation='relu')(merged)
    x = Dense(64, activation='relu')(x)
    output = Dense(2)(x)  # 输出 (lat, lon)

    model = Model(inputs=[bssid_input, rssi_input], outputs=output)
    model.compile(optimizer='adam', loss='mse', metrics=['mae'])
    model.summary()

    print("开始训练模型 (BSSID + RSSI)...")
    history = model.fit(
        [X_bssid_train, X_rssi_train],
        Y_train,
        epochs=50,
        batch_size=32,
        validation_split=0.2,
        verbose=1
    )

    # 评估
    loss, mae = model.evaluate([X_bssid_test, X_rssi_test], Y_test, verbose=0)
    print(f"测试集 MAE: {mae:.6f} 度")

    # 预测
    Y_pred = model.predict([X_bssid_test, X_rssi_test])

    # ========== (G) 可视化预测 vs True ==========
    plt.figure(figsize=(8,6))
    plt.scatter(Y_test[:,0], Y_test[:,1], marker='o', alpha=0.6, label='True (lat, lon)')
    plt.scatter(Y_pred[:,0], Y_pred[:,1], marker='x', alpha=0.6, label='Predicted')
    plt.title("BSSID Embedding + RSSI → (Lat, Lon)")
    plt.xlabel("Latitude")
    plt.ylabel("Longitude")
    plt.legend()
    plt.grid(True)
    plt.axis("equal")
    plt.tight_layout()
    plt.savefig("bssid_embed_result.png", dpi=200)
    plt.show()

    # 计算误差
    errors = np.linalg.norm(Y_pred - Y_test, axis=1)
    mean_error = np.mean(errors)
    std_error = np.std(errors)
    print(f"预测误差均值: {mean_error:.6f} 度")
    print(f"预测误差标准差: {std_error:.6f} 度")

    # 如需导出 TFLite，取消注释以下内容：
    """
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    with open("tf_model_bssid_embed.tflite", "wb") as f:
        f.write(tflite_model)
    print("已导出 TFLite 模型 (tf_model_bssid_embed.tflite).")
    """

if __name__ == "__main__":
    main()
