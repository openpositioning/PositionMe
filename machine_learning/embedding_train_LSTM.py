import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
from sklearn.model_selection import train_test_split
import tensorflow as tf
from tensorflow.keras.layers import Input, Embedding, Dense, Concatenate, LSTM, Lambda
from tensorflow.keras.models import Model
import json
import random

# 你自己的工具函数
from utils import load_json_records, get_origin, latlon_to_local_xy, local_xy_to_latlon
from alignment import piecewise_procrustes_alignment
from plotting import plot_results  # 可选

def main():
    # ========== (A) 加载数据、对齐、转换坐标 ==========
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

        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)

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

    global_lat0, global_lon0 = next((r["alignedLat"], r["alignedLng"]) for r in all_aligned_trimmed if r.get("isCalibration"))
    for r in all_aligned_trimmed:
        r["pdrX"], r["pdrY"] = latlon_to_local_xy(r["alignedLat"], r["alignedLng"], global_lat0, global_lon0)
        r["rawPdrX"], r["rawPdrY"] = latlon_to_local_xy(r["rawLat"], r["rawLng"], global_lat0, global_lon0)

    plot_results(all_aligned_trimmed, global_lat0, global_lon0, use_wifi=False)

    # ========== (C) 构建训练数据 ==========
    TOP_N = 20
    dataset, bssid_set = [], set()
    for r in all_aligned_trimmed:
        wifi = sorted(r.get("wifiList", []), key=lambda ap: ap["rssi"], reverse=True)[:TOP_N]
        rssi = [ap["rssi"] for ap in wifi] + [-100] * (TOP_N - len(wifi))
        bssid = [str(ap["bssid"]) for ap in wifi] + [""] * (TOP_N - len(wifi))
        for b in bssid:
            if b:
                bssid_set.add(b)
        dataset.append({"rssi": rssi, "bssid": bssid, "lat_true": r["alignedLat"], "lon_true": r["alignedLng"]})

    bssid_list = sorted(list(bssid_set))
    bssid2idx = {b: i+1 for i, b in enumerate(bssid_list)}
    vocab_size = len(bssid2idx) + 1

    X_bssid, X_rssi, Y = [], [], []
    for d in dataset:
        X_bssid.append([bssid2idx.get(b, 0) for b in d["bssid"]])
        X_rssi.append(d["rssi"])
        Y.append([d["lat_true"], d["lon_true"]])

    X_bssid = np.array(X_bssid, dtype=np.int32)
    X_rssi = np.array(X_rssi, dtype=np.float32) / 100.0
    Y = np.array(Y, dtype=np.float32)

    lat_min, lat_max = Y[:, 0].min(), Y[:, 0].max()
    lon_min, lon_max = Y[:, 1].min(), Y[:, 1].max()
    Y[:, 0] = (Y[:, 0] - lat_min) / (lat_max - lat_min)
    Y[:, 1] = (Y[:, 1] - lon_min) / (lon_max - lon_min)

    with open("normalization_params.json", "w") as f:
        json.dump({"lat_min": float(lat_min), "lat_max": float(lat_max), "lon_min": float(lon_min), "lon_max": float(lon_max)}, f, indent=2)

    X_bssid_train, X_bssid_test, X_rssi_train, X_rssi_test, Y_train, Y_test = train_test_split(
        X_bssid, X_rssi, Y, test_size=0.3, random_state=42
    )

    # ========== (F) 构建 LSTM 模型 ==========
    bssid_input = Input(shape=(TOP_N,), dtype='int32', name='bssid_input')
    rssi_input = Input(shape=(TOP_N,), dtype='float32', name='rssi_input')

    embedding_dim = 16
    bssid_emb = Embedding(input_dim=vocab_size, output_dim=embedding_dim, name='bssid_embedding', mask_zero=False)(bssid_input)
    rssi_reshaped = Lambda(lambda x: tf.expand_dims(x, axis=-1))(rssi_input)
    seq_features = Concatenate(axis=-1)([bssid_emb, rssi_reshaped])
    lstm_out = LSTM(64)(seq_features)

    x = Dense(64, activation='relu')(lstm_out)
    x = Dense(32, activation='relu')(x)
    output = Dense(2)(x)

    def custom_loss(y_true, y_pred):
        mse = tf.reduce_mean(tf.square(y_true - y_pred), axis=-1)
        dist = tf.norm(y_true - y_pred, axis=-1)
        penalty = tf.where(dist > 0.1, tf.square(dist - 0.1), 0.0)
        return mse + penalty

    model = Model(inputs=[bssid_input, rssi_input], outputs=output)
    model.compile(optimizer='adam', loss=custom_loss, metrics=['mae'])
    model.summary()

    print("开始训练 LSTM 模型...")
    history = model.fit([X_bssid_train, X_rssi_train], Y_train, epochs=50, batch_size=32, validation_split=0.2, verbose=1)

    Y_pred = model.predict([X_bssid_test, X_rssi_test])
    lat_range, lon_range = lat_max - lat_min, lon_max - lon_min
    Y_pred_denorm = Y_pred.copy()
    Y_pred_denorm[:, 0] = Y_pred[:, 0] * lat_range + lat_min
    Y_pred_denorm[:, 1] = Y_pred[:, 1] * lon_range + lon_min

    Y_test_denorm = Y_test.copy()
    Y_test_denorm[:, 0] = Y_test[:, 0] * lat_range + lat_min
    Y_test_denorm[:, 1] = Y_test[:, 1] * lon_range + lon_min

    # ========== (G) Loss 可视化 ==========
    plt.figure()
    plt.plot(history.history['loss'], label='Train Loss')
    plt.plot(history.history['val_loss'], label='Val Loss')
    plt.title("Training Loss Curve")
    plt.xlabel("Epoch")
    plt.ylabel("Loss")
    plt.legend()
    plt.grid()
    plt.tight_layout()
    plt.savefig("loss_curve.png", dpi=200)
    plt.show()

    # ========== (H) 预测 vs 真实图 ==========
    plt.figure(figsize=(8, 6))
    plt.scatter(Y_test_denorm[:, 0], Y_test_denorm[:, 1], label='True', alpha=0.6)
    plt.scatter(Y_pred_denorm[:, 0], Y_pred_denorm[:, 1], label='Pred', alpha=0.6)

    sample_indices = random.sample(range(len(Y_test_denorm)), min(200, len(Y_test_denorm)))
    for i in sample_indices:
        plt.annotate('', xy=(Y_test_denorm[i, 0], Y_test_denorm[i, 1]),
                     xytext=(Y_pred_denorm[i, 0], Y_pred_denorm[i, 1]),
                     arrowprops=dict(arrowstyle='->', color='gray', alpha=0.3))

    plt.title("Prediction vs Ground Truth")
    plt.xlabel("Latitude")
    plt.ylabel("Longitude")
    plt.legend()
    plt.grid(True)
    plt.axis("equal")
    plt.tight_layout()
    plt.savefig("bssid_embed_result_with_arrows.png", dpi=200)
    plt.show()

    # ========== (I) 误差分析 ==========
    errors = np.linalg.norm(Y_pred_denorm - Y_test_denorm, axis=1)
    print(f"预测误差均值: {np.mean(errors):.6f} 度")
    print(f"预测误差标准差: {np.std(errors):.6f} 度")

if __name__ == "__main__":
    main()