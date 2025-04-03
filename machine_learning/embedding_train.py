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
from tensorflow.keras.layers import Input, Embedding, Dense, Concatenate, LSTM 
from utils import load_json_records, get_origin, latlon_to_local_xy, local_xy_to_latlon
from alignment import piecewise_procrustes_alignment
from plotting import plot_results

def main():
    # ========== (A) Batch process each file: align, trim, convert back to lat/lon ==========
    file_list = glob.glob("./data_floor1_flat/*.json")
    if not file_list:
        print("No .json files found in ./data. Exiting.")
        sys.exit(1)

    all_aligned_trimmed = []
    for json_file in file_list:
        print(f"Processing file: {json_file}")
        data = load_json_records(json_file)
        if not data:
            print(f"No data found in file {json_file}, skipping.")
            continue

        lat0_file, lon0_file = get_origin(data, use_wifi=False)
        if lat0_file is None or lon0_file is None:
            print(f"Cannot determine origin for file {json_file}, skipping.")
            continue

        # Backup original PDR
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)

        # Piecewise Procrustes alignment
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
            print(f"Alignment resulted in empty data for file {json_file}, skipping.")
            continue

        # Find first and last anchor
        anchor_indices = [i for i, r in enumerate(data_aligned) if r.get("isCalibration") is True]
        if len(anchor_indices) < 2:
            print(f"Not enough anchors in file {json_file}, skipping.")
            continue

        first_anchor_idx = anchor_indices[0]
        last_anchor_idx = anchor_indices[-1]
        trimmed = data_aligned[first_anchor_idx:last_anchor_idx+1]

        # Convert back to absolute lat/lon
        for r in trimmed:
            aligned_lat, aligned_lng = local_xy_to_latlon(r["pdrX"], r["pdrY"], lat0_file, lon0_file)
            r["alignedLat"] = aligned_lat
            r["alignedLng"] = aligned_lng

            raw_lat, raw_lng = local_xy_to_latlon(r["rawPdrX"], r["rawPdrY"], lat0_file, lon0_file)
            r["rawLat"] = raw_lat
            r["rawLng"] = raw_lng

        all_aligned_trimmed.extend(trimmed)

    if not all_aligned_trimmed:
        print("No valid aligned+trimmed data found. Exiting.")
        sys.exit(1)

    print(f"Total records after alignment and trimming: {len(all_aligned_trimmed)}")

    # ========== (B) Select a global origin (for plotting only) ==========
    global_lat0, global_lon0 = None, None
    for r in all_aligned_trimmed:
        if r.get("isCalibration"):
            global_lat0 = r["alignedLat"]
            global_lon0 = r["alignedLng"]
            break
    if global_lat0 is None:
        global_lat0 = all_aligned_trimmed[0]["alignedLat"]
        global_lon0 = all_aligned_trimmed[0]["alignedLng"]
    print(f"Selected global origin: lat={global_lat0}, lon={global_lon0}")

    # Convert to global local coordinates (pdrX, pdrY) for plot_results
    for r in all_aligned_trimmed:
        gx, gy = latlon_to_local_xy(r["alignedLat"], r["alignedLng"], global_lat0, global_lon0)
        r["pdrX"] = gx
        r["pdrY"] = gy
        rgx, rgy = latlon_to_local_xy(r["rawLat"], r["rawLng"], global_lat0, global_lon0)
        r["rawPdrX"] = rgx
        r["rawPdrY"] = rgy

    plot_results(all_aligned_trimmed, global_lat0, global_lon0, use_wifi=False)
    print("Alignment result plotted to debug_plot.png.")

    # ========== (C) Construct dataset with BSSID + RSSI (Top-N) ==========
    TOP_N = 4
    dataset = []
    bssid_set = set()

    for r in all_aligned_trimmed:
        wifi_list = r.get("wifiList", [])
        if not wifi_list:
            continue

        wifi_sorted = sorted(wifi_list, key=lambda ap: ap["rssi"], reverse=True)
        wifi_top_n = wifi_sorted[:TOP_N]

        rssi_features = [ap["rssi"] for ap in wifi_top_n]
        bssid_features = [str(ap["bssid"]) for ap in wifi_top_n]
        if len(rssi_features) < TOP_N:
            rssi_features += [-100] * (TOP_N - len(rssi_features))
            bssid_features += [""] * (TOP_N - len(bssid_features))

        for b in bssid_features:
            if b:
                bssid_set.add(b)

        dataset.append({
            "rssi": rssi_features,
            "bssid": bssid_features,
            "lat_true": r["alignedLat"],
            "lon_true": r["alignedLng"]
        })

    if not dataset:
        print("Failed to build a valid dataset. Exiting.")
        sys.exit(1)

    # ========== (D) Create BSSID vocabulary mapping ==========
    bssid_list = sorted(list(bssid_set))
    bssid2idx = {b: i+1 for i, b in enumerate(bssid_list)}  # 0 reserved for padding
    vocab_size = len(bssid2idx) + 1
    print(f"BSSID vocab size = {vocab_size} (including padding)")

    # ========== (E) Prepare training data ==========
    X_bssid = []
    X_rssi = []
    Y = []

    for d in dataset:
        bssid_idx = [bssid2idx.get(b, 0) for b in d["bssid"]]
        X_bssid.append(bssid_idx)
        X_rssi.append(d["rssi"])
        Y.append([d["lat_true"], d["lon_true"]])

    X_bssid = np.array(X_bssid, dtype=np.int32)
    X_rssi = np.array(X_rssi, dtype=np.float32)

    np.save("X_rssi.npy", X_rssi)

    # RSSI normalization
    X_rssi_shifted = X_rssi + 110.0
    X_rssi_log = np.log(X_rssi_shifted)

    log_min = np.min(X_rssi_log)
    log_max = np.max(X_rssi_log)
    X_rssi_norm = (X_rssi_log - log_min) / (log_max - log_min)

    rssi_norm_params = {
        "log_shift": 110.0,
        "log_min": float(log_min),
        "log_max": float(log_max)
    }
    with open("rssi_normalization_params.json", "w") as f:
        json.dump(rssi_norm_params, f, indent=2)

    X_rssi = X_rssi_norm

    Y = np.array(Y, dtype=np.float32)

    # Normalize coordinates
    lat_min, lat_max = Y[:, 0].min(), Y[:, 0].max()
    lon_min, lon_max = Y[:, 1].min(), Y[:, 1].max()
    lat_range = lat_max - lat_min
    lon_range = lon_max - lon_min

    norm_params = {
        "lat_min": float(lat_min), "lat_max": float(lat_max),
        "lon_min": float(lon_min), "lon_max": float(lon_max)
    }
    with open("normalization_params.json", "w") as f:
        json.dump(norm_params, f, indent=2)
    print("Saved normalization parameters to normalization_params.json")

    if lat_range == 0 or lon_range == 0:
        print("Latitude or longitude range is 0. Possibly only one location in data. Exiting.")
        sys.exit(1)

    Y_norm = np.copy(Y)
    Y_norm[:, 0] = (Y_norm[:, 0] - lat_min) / lat_range
    Y_norm[:, 1] = (Y_norm[:, 1] - lon_min) / lon_range
    Y = Y_norm

    np.save("Y_coord.npy", Y)

    X_bssid_train, X_bssid_test, X_rssi_train, X_rssi_test, Y_train, Y_test = train_test_split(
        X_bssid, X_rssi, Y, test_size=0.3, random_state=42
    )

    # ========== (F) Model: BSSID embedding + RSSI → (lat, lon) ==========
    bssid_input = Input(shape=(TOP_N,), dtype='int32', name='bssid_input')
    embedding_dim = 16
    bssid_emb = Embedding(input_dim=vocab_size, output_dim=embedding_dim, input_length=TOP_N, 
                          mask_zero=True, name='bssid_embedding')(bssid_input)
    bssid_flat = Flatten()(bssid_emb)

    rssi_input = Input(shape=(TOP_N,), dtype='float32', name='rssi_input')

    merged = Concatenate()([bssid_flat, rssi_input])
    x = Dense(128, activation='relu')(merged)
    x = Dense(64, activation='relu')(x)
    x = Dense(64, activation='relu')(x)
    x = Dense(32, activation='relu')(x)
    output = Dense(2)(x)

    model = Model(inputs=[bssid_input, rssi_input], outputs=output)
    model.compile(optimizer='adam', loss='mse', metrics=['mae'])
    model.summary()

    print("Training model (BSSID + RSSI)...")
    history = model.fit(
        [X_bssid_train, X_rssi_train],
        Y_train,
        epochs=10,
        batch_size=16,
        validation_split=0.2,
        verbose=1
    )

    loss, mae = model.evaluate([X_bssid_test, X_rssi_test], Y_test, verbose=0)
    print(f"Test MAE: {mae:.6f} degrees")

    Y_pred = model.predict([X_bssid_test, X_rssi_test])

    # Denormalize predictions
    Y_pred_denorm = np.copy(Y_pred)
    Y_pred_denorm[:, 0] = Y_pred_denorm[:, 0] * lat_range + lat_min
    Y_pred_denorm[:, 1] = Y_pred_denorm[:, 1] * lon_range + lon_min

    Y_test_denorm = np.copy(Y_test)
    Y_test_denorm[:, 0] = Y_test_denorm[:, 0] * lat_range + lat_min
    Y_test_denorm[:, 1] = Y_test_denorm[:, 1] * lon_range + lon_min

    # ========== (F++) Loss curve ==========
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

    # ========== (G) Prediction vs Ground Truth ==========
    plt.figure(figsize=(8, 6))
    plt.scatter(Y_test_denorm[:, 0], Y_test_denorm[:, 1], marker='o', alpha=0.6, label='True (lat, lon)')
    plt.scatter(Y_pred_denorm[:, 0], Y_pred_denorm[:, 1], marker='x', alpha=0.6, label='Predicted')

    num_arrows = min(200, len(Y_test_denorm))
    sample_indices = random.sample(range(len(Y_test_denorm)), num_arrows)

    for i in sample_indices:
        plt.annotate(
            '',
            xy=(Y_test_denorm[i, 0], Y_test_denorm[i, 1]),
            xytext=(Y_pred_denorm[i, 0], Y_pred_denorm[i, 1]),
            arrowprops=dict(arrowstyle="->", color='gray', alpha=0.3, linewidth=1)
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

    # ========== (G++) Compute error ==========
    errors = np.linalg.norm(Y_pred_denorm - Y_test_denorm, axis=1)
    mean_error = np.mean(errors)
    std_error = np.std(errors)
    print(f"Mean prediction error: {mean_error:.6f} degrees")
    print(f"Standard deviation of error: {std_error:.6f} degrees")

    # ========== (H) Export BSSID embedding weights ==========
    embedding_matrix = model.get_layer("bssid_embedding").get_weights()[0]
    bssid_embedding_dict = {bssid: embedding_matrix[idx].tolist() for bssid, idx in bssid2idx.items()}
    with open("bssid_embedding.json", "w") as f:
        json.dump(bssid_embedding_dict, f, indent=2)
    print("Exported BSSID embeddings to bssid_embedding.json")

    # ========== (I) Export TFLite model ==========
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    with open("tf_model_bssid_embed.tflite", "wb") as f:
        f.write(tflite_model)
    print("Exported TFLite model to tf_model_bssid_embed.tflite")

if __name__ == "__main__":
    main()
