import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
from sklearn.model_selection import train_test_split
from sklearn.neighbors import KNeighborsRegressor
import tensorflow as tf
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import Dense

# Your utility functions
from utils import load_json_records, get_origin, latlon_to_local_xy, local_xy_to_latlon
from alignment import piecewise_procrustes_alignment
from plotting import plot_results  # Will use rawPdrX/Y and pdrX/Y for comparison

def main():
    # ========== (A) Load and align all .json files ==========
    file_list = glob.glob("./data/*.json")
    if not file_list:
        print("No .json files found in ./data. Exiting.")
        sys.exit(1)

    all_aligned_trimmed = []

    for json_file in file_list:
        print(f"Processing file: {json_file}")
        data = load_json_records(json_file)
        if not data:
            print(f"Empty file: {json_file}. Skipping.")
            continue

        # Determine reference origin from anchors
        lat0_file, lon0_file = get_origin(data, use_wifi=False)
        if lat0_file is None or lon0_file is None:
            print(f"Failed to determine origin for file: {json_file}. Skipping.")
            continue

        # Store original PDR before alignment
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)

        # Apply Procrustes-based piecewise alignment
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
            print(f"No aligned data for {json_file}. Skipping.")
            continue

        # Trim between first and last calibration anchor
        anchor_indices = [i for i, r in enumerate(data_aligned) if r.get("isCalibration") is True]
        if len(anchor_indices) < 2:
            print(f"Not enough anchors in {json_file}. Skipping.")
            continue

        first_anchor_idx = anchor_indices[0]
        last_anchor_idx = anchor_indices[-1]
        trimmed = data_aligned[first_anchor_idx:last_anchor_idx + 1]

        # Convert aligned and original PDR positions to absolute lat/lon
        for r in trimmed:
            r["fileLat0"] = lat0_file
            r["fileLon0"] = lon0_file

            aligned_lat, aligned_lng = local_xy_to_latlon(r["pdrX"], r["pdrY"], lat0_file, lon0_file)
            r["alignedLat"] = aligned_lat
            r["alignedLng"] = aligned_lng

            raw_lat, raw_lng = local_xy_to_latlon(r["rawPdrX"], r["rawPdrY"], lat0_file, lon0_file)
            r["rawLat"] = raw_lat
            r["rawLng"] = raw_lng

        all_aligned_trimmed.extend(trimmed)

    if not all_aligned_trimmed:
        print("No usable data after alignment and trimming. Exiting.")
        sys.exit(1)

    print(f"Total aligned and trimmed records: {len(all_aligned_trimmed)}")

    # ========== (B) Set global origin for visualization ==========
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

    print(f"Selected global origin: lat={global_lat0}, lon={global_lon0}")

    for r in all_aligned_trimmed:
        gx, gy = latlon_to_local_xy(r["alignedLat"], r["alignedLng"], global_lat0, global_lon0)
        r["pdrX"] = gx
        r["pdrY"] = gy

        rgx, rgy = latlon_to_local_xy(r["rawLat"], r["rawLng"], global_lat0, global_lon0)
        r["rawPdrX"] = rgx
        r["rawPdrY"] = rgy

    # ========== (C) Visualize raw vs aligned PDR ==========
    plot_results(all_aligned_trimmed, global_lat0, global_lon0, use_wifi=False)
    print("Generated debug_plot.png comparing raw and aligned PDR.")

    # ========== (D) Train kNN using aligned lat/lon as labels ==========
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
            "lat_true": r["alignedLat"],
            "lon_true": r["alignedLng"]
        })

    X = np.array([d["features"] for d in dataset])  # RSSI features
    Y = np.array([[d["lat_true"], d["lon_true"]] for d in dataset])  # Aligned lat/lon

    X_train, X_test, Y_train, Y_test = train_test_split(X, Y, test_size=0.3, random_state=42)

    knn = KNeighborsRegressor(n_neighbors=5)
    knn.fit(X_train, Y_train)
    Y_pred = knn.predict(X_test)

    # ========== (E) Visualize kNN prediction vs ground truth ==========
    plt.figure(figsize=(8, 6))
    plt.scatter(Y_test[:, 0], Y_test[:, 1], marker='o', alpha=0.6, label='True (Aligned Lat/Lon)')
    plt.scatter(Y_pred[:, 0], Y_pred[:, 1], marker='x', alpha=0.6, label='kNN Predicted')
    plt.title("WiFi RSSI → Location Prediction\n(Aligned + Trimmed Labels)")
    plt.xlabel("Latitude")
    plt.ylabel("Longitude")
    plt.legend()
    plt.grid(True)
    plt.axis("equal")
    plt.tight_layout()
    plt.savefig("knn_result.png", dpi=200)
    plt.show()
    print("Saved kNN prediction result as knn_result.png")

    errors = np.linalg.norm(Y_pred - Y_test, axis=1)
    mean_error = np.mean(errors)
    std_error = np.std(errors)
    print(f"kNN Mean Error: {mean_error:.6f} degrees")
    print(f"kNN Std Dev:    {std_error:.6f} degrees")
    print("kNN prediction complete.")

    # ========== (F) Train and export a TensorFlow Lite model ==========
    print("Training TensorFlow model...")
    tf_model = Sequential([
        Dense(64, activation='relu', input_shape=(TOP_N,)),
        Dense(64, activation='relu'),
        Dense(2)  # Output: latitude and longitude
    ])
    tf_model.compile(optimizer='adam', loss='mse', metrics=['mae'])

    tf_model.fit(X_train, Y_train, epochs=50, batch_size=32, validation_split=0.2)

    tf_loss, tf_mae = tf_model.evaluate(X_test, Y_test, verbose=0)
    print(f"TensorFlow model test MAE: {tf_mae:.6f} degrees")

    # Export to TFLite
    converter = tf.lite.TFLiteConverter.from_keras_model(tf_model)
    tflite_model = converter.convert()
    with open("tf_model.tflite", "wb") as f:
        f.write(tflite_model)
    print("Exported TensorFlow Lite model to tf_model.tflite.")

if __name__ == "__main__":
    main()
