import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
import json
import random

import tensorflow as tf
from tensorflow.keras.layers import Input, Embedding, Dense, Concatenate, LSTM, Lambda, TimeDistributed
from tensorflow.keras.models import Model
from sklearn.model_selection import train_test_split

# 你自己已有的工具函数
from utils import load_json_records, get_origin, latlon_to_local_xy, local_xy_to_latlon
from alignment import piecewise_procrustes_alignment
from plotting import plot_results  # 可选

def build_multi_input_model(
    vocab_size,
    embedding_dim=16,
    top_n=20,
    sensor_feature_dim=9,
    time_window=10
):
    """
    构建一个示例多输入（WiFi + 传感器）的时序 LSTM 模型。
    - WiFi 输入: [Batch, Time, TOP_N] 的 BSSID 索引 + RSSI
    - 传感器输入: [Batch, Time, sensor_feature_dim]
    - 输出: 回归 (lat, lon)，亦可改为 (x, y)
    """
    # ---- WiFi 部分输入 ----
    bssid_input = Input(shape=(time_window, top_n), dtype='int32', name='bssid_input')
    rssi_input  = Input(shape=(time_window, top_n), dtype='float32', name='rssi_input')

    # BSSID embedding: [Batch, Time, TOP_N, embedding_dim]
    bssid_emb = Embedding(
        input_dim=vocab_size,
        output_dim=embedding_dim,
        name='bssid_embedding',
        mask_zero=False
    )(bssid_input)

    # rssi reshape: [Batch, Time, TOP_N, 1]
    rssi_reshaped = Lambda(lambda x: tf.expand_dims(x, axis=-1))(rssi_input)

    # 合并 WiFi embedding 和 RSSI
    # 维度变为 [Batch, Time, TOP_N, embedding_dim + 1]
    wifi_features = Concatenate(axis=-1)([bssid_emb, rssi_reshaped])

    # 可以将 TOP_N 这维再做一个时序池化，或者直接 flatten 后再 LSTM。
    # 这里简单做个 reshape，把 WiFi 的第 3 维 (TOP_N) 合并到 feature 上
    # 最终得到 [Batch, Time, TOP_N*(embedding_dim+1)]
    wifi_features = Lambda(
        lambda x: tf.reshape(x, (-1, x.shape[1], x.shape[2]*x.shape[3]))
    )(wifi_features)

    # ---- 传感器部分输入 ----
    sensor_input = Input(shape=(time_window, sensor_feature_dim), dtype='float32', name='sensor_input')
    # 例如 sensor_feature_dim = 9，包含 (accX, accY, accZ, gyroX, gyroY, gyroZ, heading, pressure, stepCounter) 等

    # 将 sensor 序列与 wifi_features 序列做拼接
    # [Batch, Time, wifi_dim + sensor_dim]
    merged_seq = Concatenate(axis=-1)([wifi_features, sensor_input])

    # ---- LSTM 时序网络 ----
    # 这里简单地放一个单层 LSTM
    lstm_out = LSTM(64)(merged_seq)
    x = Dense(64, activation='relu')(lstm_out)
    x = Dense(32, activation='relu')(x)
    output = Dense(2, name='position')(x)  # 回归 2D (lat, lon)

    model = Model(inputs=[bssid_input, rssi_input, sensor_input], outputs=output)
    model.compile(optimizer='adam', loss='mse', metrics=['mae'])
    model.summary()
    return model


def main():
    # ========== 1) 加载并对齐数据 ==========
    file_list = glob.glob("./data/*.json")
    if not file_list:
        print("未在 ./data 目录下找到任何 .json 文件，退出。")
        sys.exit(1)

    all_aligned_trimmed = []
    for json_file in file_list:
        print(f"处理文件: {json_file}")
        data = load_json_records(json_file)
        if not data:
            continue
        lat0_file, lon0_file = get_origin(data, use_wifi=False)
        if lat0_file is None:
            continue

        # 保留原始 PDR
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)

        # 通过已有的对齐函数 piecewise_procrustes_alignment
        data_aligned, _ = piecewise_procrustes_alignment(
            data, lat0_file, lon0_file,
            pred_x_field="pdrX", pred_y_field="pdrY",
            true_lat_field="userLat", true_lng_field="userLng",
            anchor_flag_field="isCalibration",
            smoothing_count=30, latlon_to_local_xy=latlon_to_local_xy
        )

        anchor_indices = [i for i, r in enumerate(data_aligned) if r.get("isCalibration") is True]
        if len(anchor_indices) < 2:
            continue

        trimmed = data_aligned[anchor_indices[0]:anchor_indices[-1]+1]
        for r in trimmed:
            r["alignedLat"], r["alignedLng"] = local_xy_to_latlon(r["pdrX"], r["pdrY"], lat0_file, lon0_file)
            r["rawLat"], r["rawLng"] = local_xy_to_latlon(r["rawPdrX"], r["rawPdrY"], lat0_file, lon0_file)

        all_aligned_trimmed.extend(trimmed)

    if not all_aligned_trimmed:
        sys.exit("未获取有效样本。")

    # 选取全局原点
    global_lat0, global_lon0 = next(
        (r["alignedLat"], r["alignedLng"]) for r in all_aligned_trimmed if r.get("isCalibration")
    )
    for r in all_aligned_trimmed:
        r["pdrX"], r["pdrY"] = latlon_to_local_xy(r["alignedLat"], r["alignedLng"], global_lat0, global_lon0)
        r["rawPdrX"], r["rawPdrY"] = latlon_to_local_xy(r["rawLat"], r["rawLng"], global_lat0, global_lon0)

    # 这里可视化一下对齐结果（可选）
    plot_results(all_aligned_trimmed, global_lat0, global_lon0, use_wifi=False)

    # ========== 2) 整理 WiFi / 传感器特征，并构建时序 ==========
    # 2.1 先把所有记录按时间排序
    all_aligned_trimmed.sort(key=lambda x: x.get("timestamp", 0))

    # 2.2 WiFi 的 BSSID 词典
    TOP_N = 20
    bssid_set = set()

    # 额外的传感器特征: 这里仅演示 9 维，你可根据实际需求增/删
    def get_sensor_features(r):
        accX = r.get("accX", 0.0)
        accY = r.get("accY", 0.0)
        accZ = r.get("accZ", 0.0)
        gyroX = r.get("gyroX", 0.0)
        gyroY = r.get("gyroY", 0.0)
        gyroZ = r.get("gyroZ", 0.0)
        heading = r.get("orientationHeading", 0.0)
        pressure = r.get("pressure", 0.0)
        stepCnt = r.get("stepCounter", 0.0)
        return [accX, accY, accZ, gyroX, gyroY, gyroZ, heading, pressure, stepCnt]

    # 2.3 把每条记录转换为一个 dict
    all_records = []
    for r in all_aligned_trimmed:
        # WiFi 排序并截取
        wifi = sorted(r.get("wifiList", []), key=lambda ap: ap["rssi"], reverse=True)[:TOP_N]
        rssi = [ap["rssi"] for ap in wifi] + [-100] * (TOP_N - len(wifi))
        bssid = [str(ap["bssid"]) for ap in wifi] + [""] * (TOP_N - len(wifi))
        for b in bssid:
            if b:
                bssid_set.add(b)

        # 记录传感器特征
        sensor_feat = get_sensor_features(r)

        # 记录最终的真值 (lat, lon) 或 (x, y)
        # 这里演示用 (x, y)
        x_true, y_true = r["pdrX"], r["pdrY"]

        all_records.append({
            "timestamp": r.get("timestamp", 0),
            "bssid": bssid,
            "rssi": rssi,
            "sensor_feat": sensor_feat,
            "x_true": x_true,
            "y_true": y_true,
        })

    # 2.4 构建 BSSID 词典并映射
    bssid_list = sorted(list(bssid_set))
    bssid2idx = {b: i+1 for i, b in enumerate(bssid_list)}  # 保留0给未知
    vocab_size = len(bssid2idx) + 1

    # ========== 3) 构建时序样本：滑动窗口 ==========
    # 例如在时间序列上每个位置都去取过去 10 帧，预测当前帧的 (x, y)
    TIME_WINDOW = 10

    X_bssid_seq = []
    X_rssi_seq = []
    X_sensor_seq = []
    Y_seq = []

    # 转化为数组方便处理
    records_arr = np.array(all_records)
    n = len(records_arr)

    # 为了简单，这里假设记录是均匀时间间隔（或者你已经重采样过）
    # 如果不均匀，可以先做插值或跳过部分记录
    for i in range(n):
        if i < TIME_WINDOW - 1:
            continue
        window_slice = records_arr[i-(TIME_WINDOW-1):i+1]  # 取 [i-9 ... i]

        # 拼接 WiFi bssid / rssi
        bssid_mat = []
        rssi_mat = []
        sensor_mat = []
        for item in window_slice:
            # 映射bssid
            bssid_idx = [bssid2idx.get(b, 0) for b in item["bssid"]]
            bssid_mat.append(bssid_idx)

            # rssi
            rssi_mat.append(item["rssi"])

            # 传感器
            sensor_mat.append(item["sensor_feat"])

        X_bssid_seq.append(bssid_mat)   # shape => [TIME_WINDOW, TOP_N]
        X_rssi_seq.append(rssi_mat)     # shape => [TIME_WINDOW, TOP_N]
        X_sensor_seq.append(sensor_mat) # shape => [TIME_WINDOW, sensor_feature_dim]

        # 预测目标：本帧的真实位置
        Y_seq.append([item["x_true"], item["y_true"] for item in [records_arr[i]]][0])

    X_bssid_seq = np.array(X_bssid_seq, dtype=np.int32)     # [Samples, TIME_WINDOW, TOP_N]
    X_rssi_seq  = np.array(X_rssi_seq, dtype=np.float32)    # [Samples, TIME_WINDOW, TOP_N]
    X_sensor_seq = np.array(X_sensor_seq, dtype=np.float32) # [Samples, TIME_WINDOW, sensor_feature_dim]
    Y_seq = np.array(Y_seq, dtype=np.float32)               # [Samples, 2]

    print("X_bssid_seq shape:", X_bssid_seq.shape)
    print("X_rssi_seq  shape:", X_rssi_seq.shape)
    print("X_sensor_seq shape:", X_sensor_seq.shape)
    print("Y_seq shape:", Y_seq.shape)

    # 如果坐标范围很大，可做一下归一化
    x_min, x_max = Y_seq[:, 0].min(), Y_seq[:, 0].max()
    y_min, y_max = Y_seq[:, 1].min(), Y_seq[:, 1].max()

    Y_norm = Y_seq.copy()
    Y_norm[:, 0] = (Y_seq[:, 0] - x_min) / (x_max - x_min)
    Y_norm[:, 1] = (Y_seq[:, 1] - y_min) / (y_max - y_min)

    with open("xy_normalization.json", "w") as f:
        json.dump({
            "x_min": float(x_min), "x_max": float(x_max),
            "y_min": float(y_min), "y_max": float(y_max)
        }, f, indent=2)

    # ========== 4) 切分训练集 / 测试集 ==========
    Xb_train, Xb_test, \
    Xr_train, Xr_test, \
    Xs_train, Xs_test, \
    Y_train, Y_test = train_test_split(
        X_bssid_seq, X_rssi_seq, X_sensor_seq, Y_norm,
        test_size=0.3, random_state=42
    )

    # ========== 5) 构建并训练 LSTM 模型 (多输入) ==========
    sensor_feature_dim = X_sensor_seq.shape[-1]
    model = build_multi_input_model(
        vocab_size=vocab_size,
        embedding_dim=16,
        top_n=TOP_N,
        sensor_feature_dim=sensor_feature_dim,
        time_window=TIME_WINDOW
    )

    history = model.fit(
        [Xb_train, Xr_train, Xs_train], Y_train,
        epochs=30,
        batch_size=32,
        validation_split=0.2,
        verbose=1
    )

    # 预测
    Y_pred = model.predict([Xb_test, Xr_test, Xs_test])
    # 反归一化
    Y_pred_denorm = Y_pred.copy()
    Y_pred_denorm[:, 0] = Y_pred[:, 0] * (x_max - x_min) + x_min
    Y_pred_denorm[:, 1] = Y_pred[:, 1] * (y_max - y_min) + y_min

    Y_test_denorm = Y_test.copy()
    Y_test_denorm[:, 0] = Y_test[:, 0] * (x_max - x_min) + x_min
    Y_test_denorm[:, 1] = Y_test[:, 1] * (y_max - y_min) + y_min

    # ========== 6) 误差可视化 ==========
    errors = np.linalg.norm(Y_pred_denorm - Y_test_denorm, axis=1)
    print(f"预测误差均值: {np.mean(errors):.6f} 米(如果 x,y 单位是米)")
    print(f"预测误差标准差: {np.std(errors):.6f} 米")

    plt.figure()
    plt.plot(history.history['loss'], label='Train Loss')
    plt.plot(history.history['val_loss'], label='Val Loss')
    plt.xlabel("Epoch")
    plt.ylabel("MSE Loss")
    plt.legend()
    plt.grid(True)
    plt.title("Training Loss Curve")
    plt.show()

    # 散点对比
    plt.figure()
    plt.scatter(Y_test_denorm[:, 0], Y_test_denorm[:, 1], label='True', alpha=0.5)
    plt.scatter(Y_pred_denorm[:, 0], Y_pred_denorm[:, 1], label='Pred', alpha=0.5)
    plt.legend()
    plt.title("Prediction vs True (X,Y)")
    plt.axis('equal')
    plt.grid(True)
    plt.show()


if __name__ == "__main__":
    main()
