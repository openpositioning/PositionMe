import numpy as np
import matplotlib.pyplot as plt
from sklearn.metrics.pairwise import cosine_similarity
from sklearn.decomposition import PCA
from collections import defaultdict

# ==== 模拟加载已归一化后的 RSSI 和 坐标数据（你需要替换为你的数据） ====
# 例如：X_rssi.shape = [N, 20]，Y.shape = [N, 2]
X_rssi = np.load("X_rssi.npy")       # shape: [N, TOP_N], 归一化后的 RSSI
Y = np.load("Y_coord.npy")           # shape: [N, 2], 归一化后的 [lat, lon]

# ==== 检查 1: RSSI 相似 → 位置差异大？ ====
print("\n========== 检查 1：相似 RSSI 对应位置是否接近 ==========")
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
                print(f"⚠️ RSSI 相似但位置差异较大: Index {i} vs {j}, dist={dist:.4f}")
                count += 1
if count == 0:
    print("✅ 未发现显著 RSSI 模糊匹配现象")

# ==== 检查 2: 同一位置 → RSSI 是否不稳定？ ====
print("\n========== 检查 2：同一位置 RSSI 是否抖动明显 ==========")
pos_dict = defaultdict(list)
for rssi, y in zip(X_rssi, Y):
    key = (round(y[0], 3), round(y[1], 3))  # 近似同一位置
    pos_dict[key].append(rssi)

unstable_count = 0
for pos, rssis in pos_dict.items():
    if len(rssis) > 5:
        rssis = np.stack(rssis)
        std_rssi = np.std(rssis, axis=0).mean()
        if std_rssi > 0.1:
            print(f"⚠️ 同一位置 RSSI 方差较大: {pos}, mean std={std_rssi:.4f}")
            unstable_count += 1
if unstable_count == 0:
    print("✅ 所有位置 RSSI 波动较小")

# ==== 降维可视化: RSSI 分布 vs 坐标色彩 ==== 
print("\n========== 可视化：RSSI PCA 降维空间 ==========")
pca = PCA(n_components=2)
rssi_pca = pca.fit_transform(X_rssi)

plt.figure(figsize=(6,5))
plt.scatter(rssi_pca[:, 0], rssi_pca[:, 1], c=Y[:, 0], cmap='viridis', alpha=0.6)
plt.colorbar(label='Latitude (normalized)')
plt.title("RSSI PCA Space Colored by Latitude")
plt.xlabel("PCA-1")
plt.ylabel("PCA-2")
plt.grid(True)
plt.tight_layout()
plt.savefig("rssi_pca_latitude.png", dpi=150)
plt.show()

print("✅ 分析完成！如需进一步支持，请附上具体样本。")
