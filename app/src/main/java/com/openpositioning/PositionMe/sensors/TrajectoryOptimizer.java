package com.openpositioning.PositionMe.sensors;

import java.util.List;

public class TrajectoryOptimizer {

    /**
     * Weighted trajectory smoothing using a sliding window of recent points.
     *
     * This method applies a simple optimization to balance between:
     * - A predicted point (based on a weighted average of historical points).
     * - The currently observed point.
     *
     * The closer a historical point is to the current one, the more weight it has.
     * This is useful for smoothing noisy trajectories such as from PDR or sensors.
     *
     * @param windowList     List of float[]{x, y} points; the last one is the current observed point.
     * @param lambdaSmooth   Weight for the smoothness term (historical prediction).
     * @param lambdaFit      Weight for the fitting term (current observation).
     * @param historyPoints  Number of historical points to use (excluding the current point). Minimum is 2.
     * @return A float[]{x, y} representing the smoothed and optimized current point.
     */
    public static float[] weightedSmoothOptimizedPoint(List<float[]> windowList,
                                                       float lambdaSmooth,
                                                       float lambdaFit,
                                                       int historyPoints) {
        int n = windowList.size();

        // Not enough history, return current observed point as-is
        if (n < historyPoints + 1) {
            return windowList.get(n - 1);
        }

        float sumWeights = 0f;
        float predX = 0f;
        float predY = 0f;

        // Use the last `historyPoints` before the current one to form prediction
        for (int i = n - historyPoints - 1, w = 1; i < n - 1; i++, w++) {
            float[] pt = windowList.get(i);
            float weight = w; // Linearly increasing weight: closer points have higher influence
            predX += pt[0] * weight;
            predY += pt[1] * weight;
            sumWeights += weight;
        }

        // Normalize the weighted prediction
        predX /= sumWeights;
        predY /= sumWeights;

        // Apply smoothing optimization: combine predicted point with current observation
        float[] xCurrObserved = windowList.get(n - 1);
        float numeratorX = lambdaSmooth * predX + lambdaFit * xCurrObserved[0];
        float numeratorY = lambdaSmooth * predY + lambdaFit * xCurrObserved[1];
        float denominator = lambdaSmooth + lambdaFit;

        return new float[]{numeratorX / denominator, numeratorY / denominator};
    }
}
