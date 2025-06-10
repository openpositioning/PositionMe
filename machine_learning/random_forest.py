import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_squared_error, mean_absolute_error

# Utility functions
from utils import load_json_records, get_origin, latlon_to_local_xy, local_xy_to_latlon
from alignment import piecewise_procrustes_alignment
from plotting import plot_results

def main():
    file_list = glob.glob("./data/*.json")
    if not file_list:
        print("No .json files found in ./data, exiting.")
        sys.exit(1)
    
    all_aligned_trimmed = []

    # (A) Batch alignment and trimming
    for json_file in file_list:
        data = load_json_records(json_file)
        if not data:
            continue
        
        lat0, lon0 = get_origin(data, use_wifi=False)
        if lat0 is None or lon0 is None:
            continue
        
        # Backup raw PDR values
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)
        
        # Piecewise alignment using calibration anchors
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
            continue
        
        # Trim between first and last calibration anchor
        anchor_indices = [i for i, r in enumerate(data_aligned) if r.get("isCalibration") is True]
        if len(anchor_indices) < 2:
            continue
        
        first_anchor_idx = anchor_indices[0]
        last_anchor_idx = anchor_indices[-1]
        trimmed = data_aligned[first_anchor_idx:last_anchor_idx+1]
        
        all_aligned_trimmed.extend(trimmed)
    
    if not all_aligned_trimmed:
        print("No valid aligned and trimmed data available.")
        sys.exit(1)
    
    print(f"Total records after alignment and trimming: {len(all_aligned_trimmed)}")

    # Visualize aligned trajectory
    plot_results(all_aligned_trimmed, lat0, lon0, use_wifi=False)
    
    # (B) Prepare RSSI features and aligned PDR labels
    TOP_N = 20
    dataset = []
    for r in all_aligned_trimmed:
        wifi_list = r.get("wifiList", [])
        if not wifi_list:
            continue
        # Take top 20 strongest RSSI values
        wifi_sorted = sorted(wifi_list, key=lambda ap: ap["rssi"], reverse=True)
        wifi_top_n = wifi_sorted[:TOP_N]
        
        rssi_features = [ap["rssi"] for ap in wifi_top_n]
        if len(rssi_features) < TOP_N:
            rssi_features += [-100] * (TOP_N - len(rssi_features))  # Pad with -100
        
        x_aligned = r["pdrX"]
        y_aligned = r["pdrY"]
        dataset.append((rssi_features, x_aligned, y_aligned))
    
    if not dataset:
        print("No valid WiFi-RSSI data for training.")
        sys.exit(1)
    
    X = np.array([d[0] for d in dataset])
    Y = np.array([[d[1], d[2]] for d in dataset])  # shape = (N, 2)
    
    # (C) Train-test split
    X_train, X_test, Y_train, Y_test = train_test_split(
        X, Y, test_size=0.3, random_state=42
    )
    
    # (D) Train Random Forest model
    # You may tune parameters like n_estimators, max_depth, etc.
    rf = RandomForestRegressor(
        n_estimators=100,   # Number of trees
        max_depth=None,     # No depth limit
        random_state=42,
        n_jobs=-1           # Use all CPU cores
    )
    # Predicting 2D (x, y) position directly as a multi-output regression
    rf.fit(X_train, Y_train)
    
    # (E) Inference and evaluation
    Y_pred = rf.predict(X_test)  # shape = (N_test, 2)
    
    # Compute Euclidean error
    errors = np.linalg.norm(Y_pred - Y_test, axis=1)
    mean_error = np.mean(errors)
    std_error = np.std(errors)
    print(f"Random Forest mean error: {mean_error:.2f} m")
    print(f"Random Forest error std deviation: {std_error:.2f} m")

    # Extra metrics
    rmse = np.sqrt(mean_squared_error(Y_test, Y_pred))
    mae = mean_absolute_error(Y_test, Y_pred)
    print(f"RMSE: {rmse:.2f} m")
    print(f"MAE: {mae:.2f} m")
    
    # (F) Visualize prediction vs ground truth
    plt.figure(figsize=(8, 6))
    plt.scatter(Y_test[:, 0], Y_test[:, 1], c='blue', alpha=0.6, marker='o', label='True')
    plt.scatter(Y_pred[:, 0], Y_pred[:, 1], c='red', alpha=0.6, marker='x', label='RF-Pred')
    plt.title("Random Forest Regressor\nWiFi RSSI → (X, Y)")
    plt.xlabel("Local X")
    plt.ylabel("Local Y")
    plt.legend()
    plt.grid(True)
    plt.axis('equal')
    plt.tight_layout()
    plt.show()

if __name__ == "__main__":
    main()
