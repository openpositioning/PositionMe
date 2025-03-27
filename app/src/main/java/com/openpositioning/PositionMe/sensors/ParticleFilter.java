package com.openpositioning.PositionMe.sensors;

import android.graphics.PointF;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 粒子滤波定位模块，用于融合 WiFi、GNSS 和 PDR 数据进行位置跟踪。
 */
public class ParticleFilter {

    /** 粒子状态类，表示位置 (x, y) 以及对应的权重 */
    static class Particle {
        double x;
        double y;
        double weight;
        Particle(double x, double y, double weight) {
            this.x = x;
            this.y = y;
            this.weight = weight;
        }
    }

    /** 结果类，包含估计的最优位置和完整的粒子集合 */
    static class Result {
        public double bestX;      // 最优估计位置的 x 坐标
        public double bestY;      // 最优估计位置的 y 坐标
        public List<Particle> particles;  // 更新后的粒子集合
        Result(double bestX, double bestY, List<Particle> particles) {
            this.bestX = bestX;
            this.bestY = bestY;
            this.particles = particles;
        }
    }

    // 固定的粒子数量，可以根据需要进行配置
    private static final int NUM_PARTICLES = 100;

    /**
     * 更新粒子滤波器状态的核心函数。融合当前的传感器数据（WiFi/GNSS定位和PDR位移），
     * 进行粒子状态预测、观测更新和重采样，返回新的粒子集合和估计的位置。
     *
     * @param particles 上一时刻的粒子列表（可为空或大小为0表示初始化）
     * @param wifiPos 当前 WiFi 定位得到的位置 (x,y)，如果无 WiFi 数据可传入 null
     * @param gnssPos 当前 GNSS 定位得到的位置 (x,y)，如果无 GNSS 数据可传入 null
     * @param pdrDelta PDR 提供的位移增量 (Δx, Δy)，表示自上一时刻以来的移动位移，可为 null
     * @return Result 对象，包含估计的最优位置 (bestX, bestY) 以及新的粒子列表
     */
    public static Result updateParticleFilter(List<Particle> particles,
                                              PointF wifiPos, PointF gnssPos, PointF pdrDelta) {
        int N = NUM_PARTICLES;
        Random rand = new Random();

        // 1. 如果粒子列表为空，则进行初始化
        if (particles == null || particles.isEmpty()) {
            particles = new ArrayList<>(N);
            // 确定初始中心位置：优先使用WiFi或GNSS的初始值，否则默认为 (0,0)
            double initX = 0.0;
            double initY = 0.0;
            if (wifiPos != null) {
                initX = wifiPos.x;
                initY = wifiPos.y;
            } else if (gnssPos != null) {
                initX = gnssPos.x;
                initY = gnssPos.y;
            }
            // 设定初始粒子散布范围（例如在初始中心附近随机散布5个单位的范围）
            double initSpread = 5.0;
            for (int i = 0; i < N; i++) {
                double rx = initX + (rand.nextDouble() * 2 - 1) * initSpread;
                double ry = initY + (rand.nextDouble() * 2 - 1) * initSpread;
                // 初始时每个粒子权重相等
                particles.add(new Particle(rx, ry, 1.0 / N));
            }
        } else {
            // 使用传入的粒子列表，并确保数量为固定的 N
            N = particles.size();
        }

        // 2. 运动预测：根据 PDR 数据更新每个粒子的位置
        if (pdrDelta != null) {
            // 从 PDR 数据获取位移增量 Δx, Δy
            double moveX = pdrDelta.x;
            double moveY = pdrDelta.y;
            // 设置运动模型噪声，用于模拟运动过程的不确定性
            double motionNoiseStd = 0.5;  // 运动噪声标准差，可根据需要调整
            for (Particle p : particles) {
                // 对每个粒子应用相同的位移增量，并加入随机噪声扰动
                double dxNoise = rand.nextGaussian() * motionNoiseStd;
                double dyNoise = rand.nextGaussian() * motionNoiseStd;
                p.x += moveX + dxNoise;
                p.y += moveY + dyNoise;
            }
        }

        // 3. 观测更新：根据当前可用的 WiFi 或 GNSS 定位观测来更新粒子权重
        PointF measurement = null;
        double measurementStd = 0.0;
        if (wifiPos != null) {
            // 使用 WiFi 数据进行观测校正
            measurement = wifiPos;
            measurementStd = 3.0;    // 假设 WiFi 定位误差的标准差为约3个单位（可调参数）
        } else if (gnssPos != null) {
            // WiFi 数据不可用，使用 GNSS 数据进行校正
            measurement = gnssPos;
            measurementStd = 5.0;    // 假设 GNSS 定位误差标准差为约5个单位
        }
        if (measurement != null) {
            // 如果有观测数据，计算每个粒子相对于观测位置的概率（距离越近权重越大）
            double sigmaSq = measurementStd * measurementStd;
            double weightSum = 0.0;
            for (Particle p : particles) {
                // 计算粒子与观测位置的欧氏距离平方
                double dx = p.x - measurement.x;
                double dy = p.y - measurement.y;
                double distSq = dx * dx + dy * dy;
                // 根据高斯模型计算似然（未归一化的权重），距离越小权重越高
                double w = Math.exp(-distSq / (2 * sigmaSq));
                p.weight = w;
                weightSum += w;
            }
            // 归一化权重，使所有粒子权重和为1
            if (weightSum > 0) {
                for (Particle p : particles) {
                    p.weight /= weightSum;
                }
            } else {
                // 极端情况下如果权重和为0（可能由于观测过于精确导致所有粒子权重趋零），则退化为平均分配权重
                for (Particle p : particles) {
                    p.weight = 1.0 / N;
                }
            }
        } else {
            // 当前无 WiFi/GNSS 观测数据可用：跳过观测更新
            // 确保权重仍然归一化
            double weightSum = 0.0;
            for (Particle p : particles) {
                weightSum += p.weight;
            }
            if (weightSum == 0) {
                // 若无观测且所有粒子权重为0（例如初始化还未观测时），则重新均匀赋权
                for (Particle p : particles) {
                    p.weight = 1.0 / N;
                }
                weightSum = 1.0;
            }
            // 正常情况下维持上一时刻的权重分布，但需确保和为1
            if (Math.abs(weightSum - 1.0) > 1e-6) {
                for (Particle p : particles) {
                    p.weight /= weightSum;
                }
            }
        }

        // 4. 估计当前最优位置：计算粒子集合的加权平均坐标
        double estX = 0.0;
        double estY = 0.0;
        for (Particle p : particles) {
            estX += p.x * p.weight;
            estY += p.y * p.weight;
        }

        // 5. 低方差重采样：根据当前的粒子权重分布生成新的粒子集合
        List<Particle> newParticles = new ArrayList<>(N);
        // 随机起始索引偏移量 r，范围 [0, 1/N)
        double r = rand.nextDouble() / N;
        double c = particles.get(0).weight;  // 累积权重
        int index = 0;
        // 循环抽取 N 个新粒子
        for (int m = 0; m < N; m++) {
            double U = r + m * (1.0 / N);
            // 移动index，寻找使累积权重超过U的粒子
            while (U > c) {
                index++;
                // 防止索引越界（通常不会发生，因为U最大不会超过总权重1）
                if (index >= N) {
                    index = N - 1;
                    break;
                }
                c += particles.get(index).weight;
            }
            // 选中粒子 index 作为重采样结果（复制其状态）
            Particle chosen = particles.get(index);
            // 将选中粒子添加到新列表（权重重置为均匀分布的初始权重）
            newParticles.add(new Particle(chosen.x, chosen.y, 1.0 / N));
        }

        // 6. 返回结果，包括估计位置和新的粒子集合（供下一周期迭代使用）
        return new Result(estX, estY, newParticles);
    }
}
