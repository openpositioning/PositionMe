import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
from sklearn.model_selection import train_test_split
from sklearn.neighbors import KNeighborsRegressor

# 你自己的工具函数
from utils import load_json_records, get_origin, latlon_to_local_xy, local_xy_to_latlon
from alignment import piecewise_procrustes_alignment
# 这里导入你给出的 plot_results 函数 (会使用到 rawPdrX/Y 和 pdrX/Y 字段作对比)
from plotting import plot_results

def main():
    # 1) 找到 ./data 目录下所有的 .json 文件
    file_list = glob.glob("./data/*.json")
    if not file_list:
        print("未在 ./data 目录下找到任何 .json 文件，退出。")
        sys.exit(1)
    
    all_aligned_trimmed = []  # 用来存放“对齐 + 修剪 + 转回经纬度”后的记录

    # ========== (A) 批量处理每个文件 ==========

    # 为了后面能统一回经纬度，需要先为每条记录保留“各文件自己的 lat0/lon0”
    # 然后将对齐后的 (pdrX, pdrY) 也先转换回“该文件原点”下的经纬度，再保存。
    
    for json_file in file_list:
        print(f"处理文件: {json_file}")
        # a) 读取文件
        data = load_json_records(json_file)
        if not data:
            print(f"文件 {json_file} 数据为空, 跳过。")
            continue
        
        # b) 获取原点（该文件内部使用，可能基于 WiFi/GNSS anchor）
        lat0_file, lon0_file = get_origin(data, use_wifi=False)
        if lat0_file is None or lon0_file is None:
            print(f"文件 {json_file} 无法确定参考原点, 跳过。")
            continue
        
        # c) 保存一份原始pdr（对比用）
        for r in data:
            r["rawPdrX"] = r.get("pdrX", 0.0)
            r["rawPdrY"] = r.get("pdrY", 0.0)
        
        # d) 做PDR分段对齐 (仅针对本文件)
        data_aligned, transforms = piecewise_procrustes_alignment(
            data=data,
            lat0=lat0_file,
            lon0=lon0_file,
            pred_x_field="pdrX",
            pred_y_field="pdrY",
            true_lat_field="userLat",
            true_lng_field="userLng",
            anchor_flag_field="isCalibration",
            smoothing_count=30,  # 可根据需要调整
            latlon_to_local_xy=latlon_to_local_xy,
            debug=False
        )
        if not data_aligned:
            print(f"文件 {json_file} 对齐后数据为空, 跳过。")
            continue
        
        # e) 找到第一个、最后一个校准点(锚点)，对数据进行trim
        anchor_indices = [i for i, r in enumerate(data_aligned) if r.get("isCalibration") is True]
        if len(anchor_indices) < 2:
            print(f"文件 {json_file} 锚点数量不足2个, 无法trim, 跳过。")
            continue
        
        first_anchor_idx = anchor_indices[0]
        last_anchor_idx = anchor_indices[-1]
        trimmed = data_aligned[first_anchor_idx:last_anchor_idx+1]
        
        # f) 把本文件的对齐 + 修剪 后数据统一回“该文件原点”的经纬度
        #    这样每条记录都有“对齐后”的绝对经纬度 (alignedLat, alignedLng)
        #    也可顺便处理“原始 pdr”的绝对经纬度，方便后面画图对比。
        for r in trimmed:
            # 给每条记录，保存本文件的 lat0/lon0，后续可能要调试
            r["fileLat0"] = lat0_file
            r["fileLon0"] = lon0_file
            
            # 1) 先把对齐后的 pdrX, pdrY 转回经纬度
            #    注意：现在的 pdrX, pdrY 已是对齐后坐标
            aligned_lat, aligned_lng = local_xy_to_latlon(
                r["pdrX"], r["pdrY"], lat0_file, lon0_file
            )
            r["alignedLat"] = aligned_lat
            r["alignedLng"] = aligned_lng

            # 2) 也把“原始” pdrX, pdrY 转回经纬度，便于统一对比
            raw_lat, raw_lng = local_xy_to_latlon(
                r["rawPdrX"], r["rawPdrY"], lat0_file, lon0_file
            )
            r["rawLat"] = raw_lat
            r["rawLng"] = raw_lng
        
        # g) 将trim结果加入总列表
        all_aligned_trimmed.extend(trimmed)
    
    if not all_aligned_trimmed:
        print("所有文件都无法得到有效对齐+修剪数据, 退出。")
        sys.exit(1)
    
    print(f"对齐并修剪后的记录总数: {len(all_aligned_trimmed)}")

    # ========== (B) 选一个全局原点，将所有数据统一到同一张“局部坐标系”里 ==========

    # 先找一个全局锚点(比如说第一条 isCalibration=True 的记录)，如果没有就用列表第一个
    global_lat0 = None
    global_lon0 = None
    for r in all_aligned_trimmed:
        if r.get("isCalibration"):
            global_lat0 = r["alignedLat"]
            global_lon0 = r["alignedLng"]
            break
    if global_lat0 is None:
        # 如果没有找到任何 isCalibration=True 的记录，就用第一条记录的 alignedLat/Lng 当原点
        global_lat0 = all_aligned_trimmed[0]["alignedLat"]
        global_lon0 = all_aligned_trimmed[0]["alignedLng"]
    
    print(f"选取的全局原点 global_lat0={global_lat0}, global_lon0={global_lon0}")

    # 将对齐后的坐标“alignedLat, alignedLng”统一到 (pdrX, pdrY)
    # 将原始坐标“rawLat, rawLng”统一到 (rawPdrX, rawPdrY) ——方便 plot_results 做对比

    for r in all_aligned_trimmed:
        # 把对齐后的绝对经纬度 → 全局原点下的局部坐标
        gx, gy = latlon_to_local_xy(r["alignedLat"], r["alignedLng"], global_lat0, global_lon0)
        r["pdrX"] = gx
        r["pdrY"] = gy

        # 原始 pdr 同样也转到全局坐标系
        rgx, rgy = latlon_to_local_xy(r["rawLat"], r["rawLng"], global_lat0, global_lon0)
        r["rawPdrX"] = rgx
        r["rawPdrY"] = rgy
    
    # 对齐后每条记录依然保留 userLat, userLng (校准锚点)，它们是“真值”。
    # 如果你也想让 plot_results 里显示锚点在同一个局部坐标系上，需要在 plot_results 内部做相应处理
    # 或者你可以在这里统一再加一个 "anchorX/anchorY" 字段。但这取决于你的 plotting.py 具体逻辑。

    # 现在所有记录 (rawPdrX, rawPdrY) 是“原始轨迹”，(pdrX, pdrY) 是“对齐后轨迹”
    # 它们都在同一个 (global_lat0, global_lon0) 参考原点下面，可以一起画图了。

    # ========== (C) 做一次可视化：对齐前后 PDR 对比 ==========

    # 注意：plot_results 会读取每条记录的 rawPdrX, rawPdrY 以及 pdrX, pdrY 来画
    #       校准点则是 userLat, userLng，若需要也可以将 userLat/userLng → anchorX/anchorY
    #       不过此处先保持原样，它会用散点或箭头显示。
    plot_results(all_aligned_trimmed, global_lat0, global_lon0, use_wifi=False)
    print("已生成对齐前后PDR对比图 (debug_plot.png).")
    
    # ========== (D) 基于对齐后PDR + WiFi RSSI (Top N) 训练 kNN ==========

    # a) 构建特征/标签。这里示例取每条记录 wifiList 里 RSSI 最强的 TOP_N 个做特征
    TOP_N = 20
    dataset = []
    for r in all_aligned_trimmed:
        wifi_list = r.get("wifiList", [])
        if not wifi_list:
            continue
        
        # 按 rssi 从大到小排序，取前 TOP_N
        wifi_sorted = sorted(wifi_list, key=lambda ap: ap["rssi"], reverse=True)
        wifi_top_n = wifi_sorted[:TOP_N]
        rssi_features = [ap["rssi"] for ap in wifi_top_n]
        # 不足 TOP_N 的补 -100
        if len(rssi_features) < TOP_N:
            rssi_features += [-100]*(TOP_N - len(rssi_features))
        
        # 用对齐后的“全局坐标” pdrX, pdrY 作为回归真值
        x_aligned = r["pdrX"]
        y_aligned = r["pdrY"]
        
        dataset.append({
            "features": rssi_features,
            "x_true": x_aligned,
            "y_true": y_aligned
        })
    
    # b) 转为 numpy array
    X = np.array([d["features"] for d in dataset])  # (N, TOP_N)
    Y = np.array([[d["x_true"], d["y_true"]] for d in dataset])  # (N, 2)
    
    # c) 划分训练/测试集 (70% / 30%)
    X_train, X_test, Y_train, Y_test = train_test_split(X, Y, test_size=0.3, random_state=42)
    
    # d) 训练 kNN
    knn = KNeighborsRegressor(n_neighbors=5)
    knn.fit(X_train, Y_train)
    
    # e) 测试集预测
    Y_pred = knn.predict(X_test)
    
    # ========== (E) 可视化 kNN 预测 vs 对齐后 PDR ==========
    plt.figure(figsize=(8,6))
    plt.scatter(Y_test[:,0], Y_test[:,1], marker='o', alpha=0.6, label='True (Aligned PDR)')
    plt.scatter(Y_pred[:,0], Y_pred[:,1], marker='x', alpha=0.6, label='kNN Predicted')
    plt.title("WiFi RSSI → Position Prediction\n(Using Aligned+Trimmed PDR as Labels)")
    plt.xlabel("Global Local X (meters)")
    plt.ylabel("Global Local Y (meters)")
    plt.legend()
    plt.grid(True)
    plt.axis("equal")
    plt.tight_layout()
    plt.savefig("knn_result.png", dpi=200)
    plt.show()
    print("已生成 kNN 预测结果散点图 (knn_result.png).")
    
    # 计算预测误差
    errors = np.linalg.norm(Y_pred - Y_test, axis=1)
    mean_error = np.mean(errors)
    std_error = np.std(errors)
    print(f"kNN 预测误差均值: {mean_error:.2f} 米")
    print(f"kNN 预测误差标准差: {std_error:.2f} 米")
    print("kNN 预测完成.")

if __name__ == "__main__":
    main()
