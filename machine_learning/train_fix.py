import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
from sklearn.model_selection import train_test_split
from sklearn.neighbors import KNeighborsRegressor

# Your custom utility functions
from utils import load_json_records, get_origin, latlon_to_local_xy
from alignment import piecewise_procrustes_alignment
from plotting import plot_results

def main():
    # Step 1: Locate all .json files under /data
    file_list = glob.glob("./data/*.json")
    if not file_list:
        print("No .json files found in ./data. Exiting.")
        sys.exit(1)

    all_aligned_trimmed = []  # Stores all aligned and trimmed records across files

    # ---------- (A) Batch alignment and trimming ----------
    for json_file in file_list:
        print(f"Processing file: {json_file}")
        # a) Load file
        data = load_json_records(json_file)
        if not data:
            print(f"Empty data in file {json_file}, skipping.")
            continue

        # b) Get local origin
        lat0, lon0 = get_origin(data, use_wifi=False)
        if lat0 is None or lon0 is None:
            print(f"Failed to determine origin for {json_file}, skipping.")
            continue

        # c) Backup raw PDR for comparison
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)

        # d) Perform piecewise alignment using calibration anchors
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
            print(f"No valid aligned data in {json_file}, skipping.")
            continue

        # e) Trim between first and last anchor
        anchor_indices = [i for i, rr in enumerate(data_aligned) if rr.get("isCalibration") is True]
        if len(anchor_indices) < 2:
            print(f"Not enough anchors in {json_file}, skipping.")
            continue

        first_anchor_idx = anchor_indices[0]
        last_anchor_idx = anchor_indices[-1]
        trimmed = data_aligned[first_anchor_idx : last_anchor_idx + 1]

        # f) Append trimmed data to full list
        all_aligned_trimmed.extend(trimmed)

    if not all_aligned_trimmed:
        print("No valid aligned + trimmed data found in any file. Exiting.")
        sys.exit(1)

    print(f"Total aligned and trimmed records: {len(all_aligned_trimmed)}")

    # ---------- (A.1) Optional: visualize raw vs aligned PDR ----------
    plot_results(all_aligned_trimmed, lat0, lon0, use_wifi=False)
    print("Generated debug_plot.png (raw vs aligned PDR).")

    # ---------- (B) Filter out points far from any anchor ----------
    # 1) Gather anchor positions in the aligned space
    anchor_positions = []
    for r in all_aligned_trimmed:
        if r.get("isCalibration") is True:
            anchor_positions.append((r["pdrX"], r["pdrY"]))
    anchor_positions = np.array(anchor_positions)

    if anchor_positions.shape[0] == 0:
        print("Warning: no anchors found for filtering. Skipping filtering step.")
    else:
        # 2) Filter threshold (in meters)
        distance_threshold = 5.0  # Adjust as needed

        filtered_data = []
        for r in all_aligned_trimmed:
            px, py = r["pdrX"], r["pdrY"]
            dists = np.linalg.norm(anchor_positions - np.array([px, py]), axis=1)
            min_dist = np.min(dists)

            if min_dist <= distance_threshold:
                filtered_data.append(r)

        print(f"Before filtering: {len(all_aligned_trimmed)} samples, after: {len(filtered_data)}")
        all_aligned_trimmed = filtered_data

    if not all_aligned_trimmed:
        print("No data left after filtering. Exiting.")
        sys.exit(1)

    # Visualize filtered results
    plot_results(all_aligned_trimmed, lat0, lon0, use_wifi=False)
    print("Generated debug_plot_filtered.png (filtered aligned PDR).")

    # ---------- (C) Train kNN on RSSI → Aligned PDR prediction ----------
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

    # Split into train/test sets
    X_train, X_test, Y_train, Y_test = train_test_split(
        X, Y, test_size=0.3, random_state=42
    )

    # Train kNN model
    knn = KNeighborsRegressor(n_neighbors=5)
    knn.fit(X_train, Y_train)

    # Predict
    Y_pred = knn.predict(X_test)

    # ---------- (D) Visualize results and report error ----------
    plt.figure(figsize=(8, 6))
    plt.scatter(Y_test[:, 0], Y_test[:, 1], c='blue', marker='o', alpha=0.6, label='True (Aligned + Filtered)')
    plt.scatter(Y_pred[:, 0], Y_pred[:, 1], c='red', marker='x', alpha=0.6, label='Predicted by kNN')
    plt.title("WiFi RSSI → Position Prediction\n(Aligned + Trimmed + Filtered PDR)")
    plt.xlabel("Local X (m)")
    plt.ylabel("Local Y (m)")
    plt.legend()
    plt.grid(True)
    plt.axis("equal")
    plt.tight_layout()
    plt.savefig("knn_result_filtered.png", dpi=200)
    plt.show()

    # Error stats
    errors = np.linalg.norm(Y_pred - Y_test, axis=1)
    mean_error = np.mean(errors)
    std_error = np.std(errors)
    print(f"Mean prediction error: {mean_error:.2f} m")
    print(f"Prediction error std deviation: {std_error:.2f} m")

if __name__ == "__main__":
    main()
