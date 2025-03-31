package com.openpositioning.PositionMe.sensors;

import java.util.List;

public class TrajectoryOptimizer {

    /**
     * 多点加权平滑轨迹优化（带自定义参考历史点数）
     * @param windowList 轨迹窗口（float[]{x, y}），最后一个为当前点
     * @param lambdaSmooth 平滑项权重
     * @param lambdaFit 拟合当前估计点的权重
     * @param historyPoints 使用的历史点数量（不包括当前点），最小值为2
     * @return 优化后的当前点 float[]{x, y}
     */
    public static float[] weightedSmoothOptimizedPoint(List<float[]> windowList,
                                                       float lambdaSmooth,
                                                       float lambdaFit,
                                                       int historyPoints) {
        int n = windowList.size();
        if (n < historyPoints + 1) {
            return windowList.get(n - 1); // 历史点不够，不优化
        }

        float sumWeights = 0f;
        float predX = 0f;
        float predY = 0f;

        // 从倒数 historyPoints+1 个点中，取前面 historyPoints 个点作为历史轨迹
        for (int i = n - historyPoints - 1, w = 1; i < n - 1; i++, w++) {
            float[] pt = windowList.get(i);
            float weight = w; // 越靠近当前点，权重越高（线性）
            predX += pt[0] * weight;
            predY += pt[1] * weight;
            sumWeights += weight;
        }

        predX /= sumWeights;
        predY /= sumWeights;

        float[] xCurrObserved = windowList.get(n - 1);
        float numeratorX = lambdaSmooth * predX + lambdaFit * xCurrObserved[0];
        float numeratorY = lambdaSmooth * predY + lambdaFit * xCurrObserved[1];
        float denominator = lambdaSmooth + lambdaFit;

        return new float[]{numeratorX / denominator, numeratorY / denominator};
    }
}
