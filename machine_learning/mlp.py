import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
from sklearn.model_selection import train_test_split
from sklearn.neural_network import MLPRegressor
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import mean_squared_error, mean_absolute_error

# Import utility functions, alignment logic, and plotting
from utils import load_json_records, get_origin, latlon_to_local_xy
from alignment import piecewise_procrustes_alignment
from plotting import plot_results

def main():
    file_list = glob.glob("./data/*.json")
    if not file_list:
        sys.exit("No JSON files found.")

    all_aligned_trimmed = []
    for json_file in file_list:
        data = load_json_records(json_file)
        if not data:
            continue

        lat0, lon0 = get_origin(data, use_wifi=False)
        if lat0 is None or lon0 is None:
            continue

        # Backup original PDR coordinates
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)

        # Perform piecewise Procrustes alignment
        data_aligned, _ = piecewise_procrustes_alignment(
            data, lat0, lon0,
            "pdrX", "pdrY",
            "userLat", "userLng",
            "isCalibration",
            smoothing_count=30,
            latlon_to_local_xy=latlon_to_local_xy,
            debug=False
        )

        # Trim data between the first and last calibration point
        anchor_indices = [i for i, rr in enumerate(data_aligned) if rr.get("isCalibration") is True]
        if len(anchor_indices) < 2:
            continue
        first_anchor = anchor_indices[0]
        last_anchor = anchor_indices[-1]

        trimmed = data_aligned[first_anchor:last_anchor + 1]
        all_aligned_trimmed.extend(trimmed)

    # Optional: plot aligned PDR results
    plot_results(all_aligned_trimmed, lat0, lon0, use_wifi=False)

    # Extract WiFi RSSI features and aligned PDR coordinates
    TOP_N = 20
    features_list = []
    coords_list = []
    for r in all_aligned_trimmed:
        wifi_list = r.get("wifiList", [])
        if not wifi_list:
            continue

        wifi_sorted = sorted(wifi_list, key=lambda ap: ap["rssi"], reverse=True)
        top_n = wifi_sorted[:TOP_N]

        rssi_vec = [ap["rssi"] for ap in top_n]
        if len(rssi_vec) < TOP_N:
            rssi_vec += [-100] * (TOP_N - len(rssi_vec))  # Pad with -100 if not enough entries

        features_list.append(rssi_vec)
        coords_list.append([r["pdrX"], r["pdrY"]])

    X = np.array(features_list)
    Y = np.array(coords_list)

    # Split into training and test sets
    X_train, X_test, Y_train, Y_test = train_test_split(
        X, Y, test_size=0.3, random_state=42
    )

    # Normalize RSSI features using standard scaling
    scaler = StandardScaler()
    X_train_scaled = scaler.fit_transform(X_train)
    X_test_scaled = scaler.transform(X_test)

    # Train a simple MLP regressor to predict (x, y) from RSSI
    mlp = MLPRegressor(
        hidden_layer_sizes=(64, 64),  # Two hidden layers of 64 units each
        activation='relu',
        solver='adam',
        max_iter=500,
        random_state=42
    )
    mlp.fit(X_train_scaled, Y_train)

    # Predict on test set
    Y_pred = mlp.predict(X_test_scaled)

    # Evaluate prediction error
    errors = np.linalg.norm(Y_pred - Y_test, axis=1)
    mean_error = errors.mean()
    std_error = errors.std()
    rmse = np.sqrt(mean_squared_error(Y_test, Y_pred))
    mae = mean_absolute_error(Y_test, Y_pred)

    print(f"MLP: Mean Error = {mean_error:.2f} m, Std = {std_error:.2f} m")
    print(f"MLP: RMSE = {rmse:.2f} m, MAE = {mae:.2f} m")

    # Visualize predicted vs true positions
    plt.figure()
    plt.scatter(Y_test[:, 0], Y_test[:, 1], c='blue', alpha=0.6, marker='o', label='True')
    plt.scatter(Y_pred[:, 0], Y_pred[:, 1], c='red', alpha=0.6, marker='x', label='MLP-Pred')
    plt.title("MLP Regressor: WiFi RSSI → (X, Y)")
    plt.xlabel("Local X")
    plt.ylabel("Local Y")
    plt.legend()
    plt.grid(True)
    plt.axis('equal')
    plt.tight_layout()
    plt.show()

if __name__ == '__main__':
    main()
