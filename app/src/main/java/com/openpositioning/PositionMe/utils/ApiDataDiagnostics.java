package com.openpositioning.PositionMe.utils;

import android.util.Log;
import com.openpositioning.PositionMe.presentation.fragment.NetworkUtils;
import java.util.Map;

/**
 * API数据诊断工具
 *
 * 用途：快速检查每个建筑的API返回数据
 * 帮助诊断为什么某些建筑只有少量楼层
 */
public class ApiDataDiagnostics {

    private static final String TAG = "ApiDiagnostics";

    /**
     * 建筑配置
     */
    public static class BuildingInfo {
        public String name;
        public double latitude;
        public double longitude;

        public BuildingInfo(String name, double latitude, double longitude) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    /**
     * 所有目标建筑
     */
    private static final BuildingInfo[] BUILDINGS = {
            new BuildingInfo("Murchison House", 55.92192, -3.17551),
            new BuildingInfo("The Nucleus", 55.92301, -3.17462),
            new BuildingInfo("Library", 55.92258, -3.17396),
            new BuildingInfo("Fleeming Jenkin", 55.92248, -3.17299)
    };

    /**
     * 诊断所有建筑的API数据
     * 在MapsFragment的onMapReady中调用此方法
     */
    public static void diagnoseAllBuildings() {
        Log.d(TAG, "========================================");
        Log.d(TAG, "开始 API 数据诊断");
        Log.d(TAG, "========================================");

        for (BuildingInfo building : BUILDINGS) {
            diagnoseBuilding(building);
        }
    }

    /**
     * 诊断单个建筑
     */
    private static void diagnoseBuilding(BuildingInfo building) {
        Log.d(TAG, "\n🏢 诊断建筑: " + building.name);
        Log.d(TAG, "   坐标: [" + building.latitude + ", " + building.longitude + "]");

        NetworkUtils.fetchFloorPlan(building.latitude, building.longitude,
                new NetworkUtils.Callback() {
                    @Override
                    public void onSuccess(NetworkUtils.BuildingData data) {
                        printBuildingReport(building.name, data);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "   ❌ API调用失败: " + error);
                    }
                });
    }

    /**
     * 打印详细的建筑数据报告
     */
    private static void printBuildingReport(String buildingName, NetworkUtils.BuildingData data) {
        Log.d(TAG, "\n" + "=".repeat(60));
        Log.d(TAG, "📊 " + buildingName + " 数据报告");
        Log.d(TAG, "=".repeat(60));

        // 1. 轮廓数据
        if (data.outlines != null && !data.outlines.isEmpty()) {
            Log.d(TAG, "✅ 建筑轮廓: " + data.outlines.size() + " 个多边形");
            for (int i = 0; i < data.outlines.size(); i++) {
                Log.d(TAG, "   - 多边形 " + (i+1) + ": " + data.outlines.get(i).size() + " 个点");
            }
        } else {
            Log.w(TAG, "⚠️ 建筑轮廓: 无数据");
        }

        // 2. 楼层数据
        if (data.floors != null && !data.floors.isEmpty()) {
            Log.d(TAG, "\n✅ 楼层数据: " + data.floors.size() + " 层");
            Log.d(TAG, "   楼层列表: " + data.floors.keySet());

            // 详细分析每层
            for (Map.Entry<String, NetworkUtils.FloorData> entry : data.floors.entrySet()) {
                String floorName = entry.getKey();
                NetworkUtils.FloorData floor = entry.getValue();

                Log.d(TAG, "\n   📍 楼层: " + floorName);
                Log.d(TAG, "      - 墙壁数量: " + floor.walls.size());
                Log.d(TAG, "      - 区域数量: " + floor.areas.size());
                Log.d(TAG, "      - POI数量: " + floor.pois.size());

                // 检查数据完整性
                if (floor.walls.isEmpty() && floor.areas.isEmpty()) {
                    Log.w(TAG, "      ⚠️ 警告: 该楼层没有墙壁和区域数据");
                }

                // 检查墙壁数据质量
                int emptyWalls = 0;
                int totalWallPoints = 0;
                for (var wall : floor.walls) {
                    if (wall.isEmpty()) {
                        emptyWalls++;
                    } else {
                        totalWallPoints += wall.size();
                    }
                }
                if (emptyWalls > 0) {
                    Log.w(TAG, "      ⚠️ 警告: " + emptyWalls + " 条墙壁数据为空");
                }
                if (totalWallPoints > 0) {
                    Log.d(TAG, "      - 墙壁点总数: " + totalWallPoints);
                }

                // 检查POI
                if (!floor.pois.isEmpty()) {
                    Log.d(TAG, "      - POI类型: ");
                    for (NetworkUtils.Poi poi : floor.pois) {
                        Log.d(TAG, "        * " + (poi.label.isEmpty() ? poi.type : poi.label) +
                                " [" + poi.type + "]");
                    }
                }
            }

            // 3. 楼层命名分析
            Log.d(TAG, "\n📝 楼层命名分析:");
            analyzeFloorNaming(data.floors.keySet());

        } else {
            Log.w(TAG, "\n⚠️ 楼层数据: 无数据");
            Log.w(TAG, "   可能原因:");
            Log.w(TAG, "   1. API服务器上没有该建筑的室内地图数据");
            Log.w(TAG, "   2. 坐标不够精确，API无法找到对应建筑");
            Log.w(TAG, "   3. 该建筑尚未被录入系统");
        }

        Log.d(TAG, "=".repeat(60) + "\n");
    }

    /**
     * 分析楼层命名规则
     */
    private static void analyzeFloorNaming(java.util.Set<String> floorNames) {
        for (String name : floorNames) {
            String analysis = analyzeFloorName(name);
            Log.d(TAG, "   - \"" + name + "\" → " + analysis);
        }
    }

    /**
     * 分析单个楼层名称
     */
    private static String analyzeFloorName(String name) {
        String lower = name.toLowerCase().trim();

        if (lower.matches(".*ground.*|.*gf.*|.*0.*")) {
            return "地面层 (Ground Floor)";
        } else if (lower.matches(".*basement.*|.*b\\d+.*|.*-\\d+.*")) {
            return "地下层 (Basement)";
        } else if (lower.matches(".*\\d+.*")) {
            return "第" + extractNumber(lower) + "层";
        } else {
            return "未知类型";
        }
    }

    /**
     * 提取楼层数字
     */
    private static String extractNumber(String name) {
        return name.replaceAll("[^0-9-]", "");
    }

    /**
     * 打印API数据摘要（简化版）
     */
    public static void printDataSummary(String buildingName, NetworkUtils.BuildingData data) {
        String summary = String.format(
                "🏢 %s: 轮廓=%d, 楼层=%d %s",
                buildingName,
                data.outlines != null ? data.outlines.size() : 0,
                data.floors != null ? data.floors.size() : 0,
                data.floors != null ? data.floors.keySet() : "[]"
        );
        Log.d(TAG, summary);
    }

    /**
     * 检查API是否返回了预期的楼层数
     *
     * @param buildingName 建筑名称
     * @param expectedFloors 预期楼层数（例如The Nucleus预期5层）
     * @param actualData API返回的实际数据
     */
    public static void checkExpectedFloors(String buildingName,
                                           int expectedFloors,
                                           NetworkUtils.BuildingData actualData) {
        int actualFloors = (actualData.floors != null) ? actualData.floors.size() : 0;

        Log.d(TAG, "\n🔍 楼层数量检查: " + buildingName);
        Log.d(TAG, "   预期楼层数: " + expectedFloors);
        Log.d(TAG, "   实际楼层数: " + actualFloors);

        if (actualFloors < expectedFloors) {
            Log.w(TAG, "   ⚠️ 警告: 楼层数量少于预期");
            Log.w(TAG, "   缺失 " + (expectedFloors - actualFloors) + " 层数据");
            Log.w(TAG, "\n   可能的原因:");
            Log.w(TAG, "   1. API服务器上该建筑的数据不完整");
            Log.w(TAG, "   2. 只有部分楼层被录入系统");
            Log.w(TAG, "   3. 需要联系API管理员补充数据");
            Log.w(TAG, "\n   建议:");
            Log.w(TAG, "   - 联系课程TA或API管理员");
            Log.w(TAG, "   - 在作业文档中说明此情况");
            Log.w(TAG, "   - 演示时选择数据完整的其他建筑");
        } else if (actualFloors == expectedFloors) {
            Log.d(TAG, "   ✅ 楼层数量符合预期");
        } else {
            Log.d(TAG, "   ℹ️ 楼层数量超出预期 (这是好事!)");
        }
    }

    /**
     * 建议的调用方式（在MapsFragment中）:
     *
     * // 在onMapReady回调中添加:
     *
     * if (BuildConfig.DEBUG) {
     *     ApiDataDiagnostics.diagnoseAllBuildings();
     * }
     *
     * // 或者针对特定建筑:
     *
     * NetworkUtils.fetchFloorPlan(lat, lon, new NetworkUtils.Callback() {
     *     @Override
     *     public void onSuccess(NetworkUtils.BuildingData data) {
     *         ApiDataDiagnostics.printDataSummary("The Nucleus", data);
     *         ApiDataDiagnostics.checkExpectedFloors("The Nucleus", 5, data);
     *         // ... 其他代码
     *     }
     * });
     */
}