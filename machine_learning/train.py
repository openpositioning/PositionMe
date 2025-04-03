import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
from sklearn.model_selection import train_test_split
from sklearn.neighbors import KNeighborsRegressor

# Utility functions
from utils import load_json_records, get_origin, latlon_to_local_xy, local_xy_to_latlon
from alignment import piecewise_procrustes_alignment
from plotting import plot_results  # Uses rawPdrX/Y and pdrX/Y for visual comparison

def main():
    # 1) Load all JSON files from the ./data directory
    file_list = glob.glob("./data/*.json")
    if not file_list:
        print("No .json files found in ./data. Exiting.")
        sys.exit(1)

    all_aligned_trimmed = []  # Stores all aligned + trimmed + converted records

    # ========== (A) Process each file ==========
    # Each record will preserve its file-specific origin (lat0/lon0) so aligned coordinates
    # can be converted back to geographic coordinates before merging across files.

    for json_file in file_list:
        print(f"Processing file: {json_file}")

        # a) Load records from the file
        data = load_json_records(json_file)
        if not data:
            print(f"Empty data in {json_file}. Skipping.")
            continue

        # b) Get the origin point for this file (based on WiFi/GNSS anchors)
        lat0_file, lon0_file = get_origin(data, use_wifi=False)
        if lat0_file is None or lon0_file is None:
            print(f"Failed to determine reference origin for {json_file}. Skipping.")
            continue

        # c) Store original PDR (for visualization/comparison later)
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)

        # d) Perform segment-wise Procrustes alignment (PDR alignment)
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
            print(f"No aligned data in {json_file}. Skipping.")
            continue

        # e) Trim data to only include samples between the first and last anchor points
        anchor_indices = [i for i, r in enumerate(data_aligned) if r.get("isCalibration") is True]
        if len(anchor_indices) < 2:
            print(f"Not enough anchors in {json_file}. Skipping.")
            continue

        first_anchor_idx = anchor_indices[0]
        last_anchor_idx = anchor_indices[-1]
        trimmed = data_aligned[first_anchor_idx:last_anchor_idx + 1]

        # f) Convert aligned PDR (pdrX/pdrY) and raw PDR back to lat/lon using this file's origin
        for r in trimmed:
            r["fileLat0"] = lat0_file
            r["fileLon0"] = lon0_file

            aligned_lat, aligned_lng = local_xy_to_latlon(r["pdrX"], r["pdrY"], lat0_file, lon0_file)
            r["alignedLat"] = aligned_lat
            r["alignedLng"] = aligned_lng

            raw_lat, raw_lng = local_xy_to_latlon(r["rawPdrX"], r["rawPdrY"], lat0_file, lon0_file)
            r["rawLat"] = raw_lat
            r["rawLng"] = raw_lng

        # g) Merge into final list
        all_aligned_trimmed.extend(trimmed)

    if not all_aligned_trimmed:
        print("No usable aligned+trimmed data found. Exiting.")
        sys.exit(1)

    print(f"Total aligned and trimmed records: {len(all_aligned_trimmed)}")

    # ========== (B) Select a global origin and convert all records into a unified local coordinate frame ==========

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
        # Convert aligned GPS to unified local (X,Y)
        gx, gy = latlon_to_local_xy(r["alignedLat"], r["alignedLng"], global_lat0, global_lon0)
        r["pdrX"] = gx
        r["pdrY"] = gy

        # Convert raw PDR to same frame for plotting
        rgx, rgy = latlon_to_local_xy(r["rawLat"], r["rawLng"], global_lat0, global_lon0)
        r["rawPdrX"] = rgx
        r["rawPdrY"] = rgy

    # ========== (C) Visualize raw vs aligned PDR trajectories ==========
    plot_results(all_aligned_trimmed, global_lat0, global_lon0, use_wifi=False)
    print("Saved raw vs aligned PDR trajectory plot (debug_plot.png).")

    # ========== (D) Train kNN using top-N RSSI values as features, and aligned positions as labels ==========
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

    # Split into training and test sets (70% / 30%)
    X_train, X_test, Y_train, Y_test = train_test_split(X, Y, test_size=0.3, random_state=42)

    # Train a kNN model
    knn = KNeighborsRegressor(n_neighbors=5)
    knn.fit(X_train, Y_train)

    # Predict on the test set
    Y_pred = knn.predict(X_test)

    # ========== (E) Visualize kNN predictions ==========
    plt.figure(figsize=(8, 6))
    plt.scatter(Y_test[:, 0], Y_test[:, 1], marker='o', alpha=0.6, label='True (Aligned PDR)')
    plt.scatter(Y_pred[:, 0], Y_pred[:, 1], marker='x', alpha=0.6, label='kNN Predicted')
    plt.title("WiFi RSSI → Position Prediction\n(Aligned & Trimmed PDR)")
    plt.xlabel("Global Local X (meters)")
    plt.ylabel("Global Local Y (meters)")
    plt.legend()
    plt.grid(True)
    plt.axis("equal")
    plt.tight_layout()
    plt.savefig("knn_result.png", dpi=200)
    plt.show()
    print("Saved kNN prediction scatter plot (knn_result.png).")

    # Calculate prediction error
    errors = np.linalg.norm(Y_pred - Y_test, axis=1)
    mean_error = np.mean(errors)
    std_error = np.std(errors)
    print(f"kNN Mean Prediction Error: {mean_error:.2f} meters")
    print(f"kNN Error Std Dev:         {std_error:.2f} meters")
    print("kNN prediction completed.")

if __name__ == "__main__":
    main()
