import numpy as np
import matplotlib.pyplot as plt
from sklearn.metrics.pairwise import cosine_similarity
from sklearn.decomposition import PCA
from collections import defaultdict

# ==== Load normalized RSSI and coordinate data (replace with your actual data) ====
# Example shapes: X_rssi.shape = [N, 20], Y.shape = [N, 2]
X_rssi = np.load("X_rssi.npy")       # shape: [N, TOP_N], original RSSI (in dBm)
Y = np.load("Y_coord.npy")           # shape: [N, 2], normalized coordinates [lat, lon]

# ==== Convert RSSI from dB scale to linear scale ====
print("\n========== Converting RSSI from dBm to linear scale ==========")
X_rssi = np.clip(X_rssi, -100, -30)         # Clamp extreme values
X_rssi = 10 ** (X_rssi / 10.0)              # Approximate linear power conversion

# ==== Test 1: Are similar RSSI vectors mapped to distant locations? ====
print("\n========== Test 1: High RSSI similarity with large location differences ==========")
sample_size = min(500, len(X_rssi))
indices = np.random.choice(len(X_rssi), size=sample_size, replace=False)
X_sample = X_rssi[indices]
Y_sample = Y[indices]
similarity = cosine_similarity(X_sample)

threshold = 0.95
count = 0
for i in range(sample_size):
    for j in range(i + 1, sample_size):
        if similarity[i, j] > threshold:
            dist = np.linalg.norm(Y_sample[i] - Y_sample[j])
            if dist > 0.05:
                print(f"⚠️ High RSSI similarity but large distance: Index {i} vs {j}, dist = {dist:.4f}")
                count += 1
if count == 0:
    print("✅ No significant ambiguity: similar RSSI implies nearby locations")

# ==== Test 2: Is RSSI stable at the same location? ====
print("\n========== Test 2: RSSI stability at the same position ==========")
pos_dict = defaultdict(list)
for rssi, y in zip(X_rssi, Y):
    key = (round(y[0], 3), round(y[1], 3))  # Group nearby coordinates
    pos_dict[key].append(rssi)

unstable_count = 0
for pos, rssis in pos_dict.items():
    if len(rssis) > 5:
        rssis = np.stack(rssis)
        std_rssi = np.std(rssis, axis=0).mean()
        if std_rssi > 0.1:
            print(f"⚠️ High RSSI variation at location {pos}, mean std = {std_rssi:.4f}")
            unstable_count += 1
if unstable_count == 0:
    print("✅ RSSI is stable across repeated measurements at same positions")

# ==== Dimensionality Reduction: PCA Visualization of RSSI Space ====
print("\n========== Visualizing RSSI in PCA space ==========")
pca = PCA(n_components=2)
rssi_pca = pca.fit_transform(X_rssi)

plt.figure(figsize=(6, 5))
plt.scatter(rssi_pca[:, 0], rssi_pca[:, 1], c=Y[:, 0], cmap='viridis', alpha=0.6)
plt.colorbar(label='Latitude (normalized)')
plt.title("RSSI PCA Projection Colored by Latitude")
plt.xlabel("PCA-1")
plt.ylabel("PCA-2")
plt.grid(True)
plt.tight_layout()
plt.savefig("rssi_pca_latitude.png", dpi=150)
plt.show()

print("✅ Analysis complete. Provide sample data if further assistance is needed.")
