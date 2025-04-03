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

# Utility functions provided separately
from utils import load_json_records, get_origin, latlon_to_local_xy, local_xy_to_latlon
from alignment import piecewise_procrustes_alignment
from plotting import plot_results  # Optional

def build_multi_input_model(
    vocab_size,
    embedding_dim=16,
    top_n=20,
    sensor_feature_dim=9,
    time_window=10
):
    """
    Build a sample multi-input (WiFi + sensors) sequential LSTM model.
    - WiFi input: [Batch, Time, TOP_N] for BSSID indices and RSSI values
    - Sensor input: [Batch, Time, sensor_feature_dim]
    - Output: regression to (lat, lon), or optionally (x, y)
    """
    # ---- WiFi Inputs ----
    bssid_input = Input(shape=(time_window, top_n), dtype='int32', name='bssid_input')
    rssi_input  = Input(shape=(time_window, top_n), dtype='float32', name='rssi_input')

    bssid_emb = Embedding(
        input_dim=vocab_size,
        output_dim=embedding_dim,
        name='bssid_embedding',
        mask_zero=False
    )(bssid_input)

    rssi_reshaped = Lambda(lambda x: tf.expand_dims(x, axis=-1))(rssi_input)

    # Concatenate BSSID embedding with RSSI to shape [Batch, Time, TOP_N, embedding_dim + 1]
    wifi_features = Concatenate(axis=-1)([bssid_emb, rssi_reshaped])

    # Flatten TOP_N and embedding dimensions into a single feature vector per timestep
    wifi_features = Lambda(
        lambda x: tf.reshape(x, (-1, x.shape[1], x.shape[2]*x.shape[3]))
    )(wifi_features)

    # ---- Sensor Inputs ----
    sensor_input = Input(shape=(time_window, sensor_feature_dim), dtype='float32', name='sensor_input')
    # e.g., sensor_feature_dim = 9 includes (accX, accY, accZ, gyroX, gyroY, gyroZ, heading, pressure, stepCounter)

    # Combine WiFi and sensor sequences
    merged_seq = Concatenate(axis=-1)([wifi_features, sensor_input])

    # ---- LSTM Sequence Network ----
    lstm_out = LSTM(64)(merged_seq)
    x = Dense(64, activation='relu')(lstm_out)
    x = Dense(32, activation='relu')(x)
    output = Dense(2, name='position')(x)  # Regress 2D coordinates (lat, lon or x, y)

    model = Model(inputs=[bssid_input, rssi_input, sensor_input], outputs=output)
    model.compile(optimizer='adam', loss='mse', metrics=['mae'])
    model.summary()
    return model


def main():
    # ========== 1) Load and align data ==========
    file_list = glob.glob("./data/*.json")
    if not file_list:
        print("No .json files found in ./data. Exiting.")
        sys.exit(1)

    all_aligned_trimmed = []
    for json_file in file_list:
        print(f"Processing file: {json_file}")
        data = load_json_records(json_file)
        if not data:
            continue
        lat0_file, lon0_file = get_origin(data, use_wifi=False)
        if lat0_file is None:
            continue

        # Backup original PDR before alignment
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)

        # Apply piecewise Procrustes alignment
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
        sys.exit("No valid samples found.")

    # Select global origin for coordinate conversion
    global_lat0, global_lon0 = next(
        (r["alignedLat"], r["alignedLng"]) for r in all_aligned_trimmed if r.get("isCalibration")
    )
    for r in all_aligned_trimmed:
        r["pdrX"], r["pdrY"] = latlon_to_local_xy(r["alignedLat"], r["alignedLng"], global_lat0, global_lon0)
        r["rawPdrX"], r["rawPdrY"] = latlon_to_local_xy(r["rawLat"], r["rawLng"], global_lat0, global_lon0)

    # Optional: visualize aligned results
    plot_results(all_aligned_trimmed, global_lat0, global_lon0, use_wifi=False)

    # ========== 2) Prepare WiFi and sensor features ==========
    all_aligned_trimmed.sort(key=lambda x: x.get("timestamp", 0))

    TOP_N = 20
    bssid_set = set()

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

    all_records = []
    for r in all_aligned_trimmed:
        wifi = sorted(r.get("wifiList", []), key=lambda ap: ap["rssi"], reverse=True)[:TOP_N]
        rssi = [ap["rssi"] for ap in wifi] + [-100] * (TOP_N - len(wifi))
        bssid = [str(ap["bssid"]) for ap in wifi] + [""] * (TOP_N - len(wifi))
        for b in bssid:
            if b:
                bssid_set.add(b)

        sensor_feat = get_sensor_features(r)
        x_true, y_true = r["pdrX"], r["pdrY"]

        all_records.append({
            "timestamp": r.get("timestamp", 0),
            "bssid": bssid,
            "rssi": rssi,
            "sensor_feat": sensor_feat,
            "x_true": x_true,
            "y_true": y_true,
        })

    bssid_list = sorted(list(bssid_set))
    bssid2idx = {b: i+1 for i, b in enumerate(bssid_list)}  # reserve 0 for unknown
    vocab_size = len(bssid2idx) + 1

    # ========== 3) Create sequence samples using sliding window ==========
    TIME_WINDOW = 10

    X_bssid_seq = []
    X_rssi_seq = []
    X_sensor_seq = []
    Y_seq = []

    records_arr = np.array(all_records)
    n = len(records_arr)

    for i in range(n):
        if i < TIME_WINDOW - 1:
            continue
        window_slice = records_arr[i-(TIME_WINDOW-1):i+1]

        bssid_mat = []
        rssi_mat = []
        sensor_mat = []
        for item in window_slice:
            bssid_idx = [bssid2idx.get(b, 0) for b in item["bssid"]]
            bssid_mat.append(bssid_idx)
            rssi_mat.append(item["rssi"])
            sensor_mat.append(item["sensor_feat"])

        X_bssid_seq.append(bssid_mat)
        X_rssi_seq.append(rssi_mat)
        X_sensor_seq.append(sensor_mat)
        Y_seq.append([item["x_true"], item["y_true"] for item in [records_arr[i]]][0])

    X_bssid_seq = np.array(X_bssid_seq, dtype=np.int32)
    X_rssi_seq  = np.array(X_rssi_seq, dtype=np.float32)
    X_sensor_seq = np.array(X_sensor_seq, dtype=np.float32)
    Y_seq = np.array(Y_seq, dtype=np.float32)

    print("X_bssid_seq shape:", X_bssid_seq.shape)
    print("X_rssi_seq  shape:", X_rssi_seq.shape)
    print("X_sensor_seq shape:", X_sensor_seq.shape)
    print("Y_seq shape:", Y_seq.shape)

    # Normalize ground truth coordinates
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

    # ========== 4) Split training / testing ==========
    Xb_train, Xb_test, \
    Xr_train, Xr_test, \
    Xs_train, Xs_test, \
    Y_train, Y_test = train_test_split(
        X_bssid_seq, X_rssi_seq, X_sensor_seq, Y_norm,
        test_size=0.3, random_state=42
    )

    # ========== 5) Train the LSTM model ==========
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

    Y_pred = model.predict([Xb_test, Xr_test, Xs_test])

    Y_pred_denorm = Y_pred.copy()
    Y_pred_denorm[:, 0] = Y_pred[:, 0] * (x_max - x_min) + x_min
    Y_pred_denorm[:, 1] = Y_pred[:, 1] * (y_max - y_min) + y_min

    Y_test_denorm = Y_test.copy()
    Y_test_denorm[:, 0] = Y_test[:, 0] * (x_max - x_min) + x_min
    Y_test_denorm[:, 1] = Y_test[:, 1] * (y_max - y_min) + y_min

    # ========== 6) Visualize error ==========
    errors = np.linalg.norm(Y_pred_denorm - Y_test_denorm, axis=1)
    print(f"Mean prediction error: {np.mean(errors):.6f} meters (assuming unit is meters)")
    print(f"Standard deviation of error: {np.std(errors):.6f} meters")

    plt.figure()
    plt.plot(history.history['loss'], label='Train Loss')
    plt.plot(history.history['val_loss'], label='Val Loss')
    plt.xlabel("Epoch")
    plt.ylabel("MSE Loss")
    plt.legend()
    plt.grid(True)
    plt.title("Training Loss Curve")
    plt.show()

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
