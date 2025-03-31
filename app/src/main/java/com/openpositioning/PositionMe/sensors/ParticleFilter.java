package com.openpositioning.PositionMe.sensors;

import android.graphics.PointF;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 粒子滤波定位模块，用于融合 WiFi、GNSS 和 PDR 数据进行位置跟踪。
 */
public class ParticleFilter {

    /** 粒子状态类，表示位置 (x, y) 以及对应的权重 */
    public static class Particle {
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
    public static class Result {
        public double bestX;      // 最优估计位置的 x 坐标
        public double bestY;      // 最优估计位置的 y 坐标
        public List<Particle> particles;  // 更新后的粒子集合
        Result(double bestX, double bestY, List<Particle> particles) {
            this.bestX = bestX;
            this.bestY = bestY;
            this.particles = particles;
        }
    }

    public static List<Particle> initializeParticles(PointF initPos) {
        List<Particle> particles = new ArrayList<>(NUM_PARTICLES);
        double initX = (initPos != null) ? initPos.x : 0.0;
        double initY = (initPos != null) ? initPos.y : 0.0;
        // 设定初始粒子散布范围（例如在初始中心附近随机散布5个单位的范围）
        double initSpread = 5.0;
        Random rand = new Random();
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double rx = initX + (rand.nextDouble() * 2 - 1) * initSpread;
            double ry = initY + (rand.nextDouble() * 2 - 1) * initSpread;
            // 初始时每个粒子权重相等
            particles.add(new Particle(rx, ry, 1.0 / NUM_PARTICLES));
        }
        return particles;
    }

    // 固定的粒子数量，可以根据需要进行配置
    private static final int NUM_PARTICLES = 200; // 增加到200以提高准确性

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
        double measurementStd = 0.0;
        boolean isMoving = true;

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
            double moveX = pdrDelta.x;
            double moveY = pdrDelta.y;
            double motionNoiseStd = 0.5; // 运动噪声标准差，可根据需要调整
            double absMovement = Math.sqrt(moveX * moveX + moveY * moveY);
//            Log.d("ParticleFilter", "Absolute movement: " + absMovement);
            if (absMovement < 0.5) {
                isMoving = false;
//                Log.d("ParticleFilter", "PDR moving detect"+isMoving);
            }


            for (Particle p : particles) {
                // 引入步长和方向的随机扰动
                double stepNoise = rand.nextGaussian() * 0.05; // 步长误差
                double angleNoise = rand.nextGaussian() * 0.02; // 方向误差（弧度）
                double noisyMoveX = moveX * (1 + stepNoise) * Math.cos(angleNoise) - moveY * Math.sin(angleNoise);
                double noisyMoveY = moveY * (1 + stepNoise) * Math.cos(angleNoise) + moveX * Math.sin(angleNoise);
                // 应用位移和噪声
                p.x += noisyMoveX + rand.nextGaussian() * motionNoiseStd;
                p.y += noisyMoveY + rand.nextGaussian() * motionNoiseStd;
            }
        }

        // 3. 观测更新：根据当前可用的 WiFi 或 GNSS 定位观测来更新粒子权重
        PointF measurement = null;

        // 先计算当前估计位置（加权平均）
        double estX = 0.0;
        double estY = 0.0;
        for (Particle p : particles) {
            estX += p.x * p.weight;
            estY += p.y * p.weight;
        }
        if (wifiPos != null) {
            measurement = wifiPos;
            if (isMoving){
                measurementStd = 1.0;
            } else {
                measurementStd = 99999;
            }
//            Log.d("ParticleFilter", "measurementStd is" + measurementStd); // 基础噪声
            // 如果WiFi位置与当前估计位置差异过大，增大噪声
            double dxEst = estX - wifiPos.x;
            double dyEst = estY - wifiPos.y;
            double distToEst = Math.sqrt(dxEst * dxEst + dyEst * dyEst);
            if (distToEst > 5.0) { // 阈值可调
                measurementStd = 3.0; // 增大噪声
            }
        } else if (gnssPos != null) {
            measurement = gnssPos;
            if (isMoving){
            measurementStd = 5.0;}
            else{measurementStd = 99999;}// GNSS 噪声
        }
//        Log.d("ParticleFilter", "measurementStd is" + measurementStd); // 基础噪声
        if (measurement != null) {
            double sigmaSq = measurementStd * measurementStd;
            double weightSum = 0.0;
            for (Particle p : particles) {
                double dx = p.x - measurement.x;
                double dy = p.y - measurement.y;
                double distSq = dx * dx + dy * dy;
                double dist = Math.sqrt(distSq);
                // 使用Huber-like鲁棒模型
                double huberThreshold = 2.0; // 可调参数
                double w = dist < huberThreshold ? Math.exp(-distSq / (2 * sigmaSq)) : huberThreshold / dist;
                p.weight = w;
                weightSum += w;
            }
            // 归一化权重
            if (weightSum > 0) {
                for (Particle p : particles) {
                    p.weight /= weightSum;
                }
            } else {
                // 极端情况，平均分配权重
                for (Particle p : particles) {
                    p.weight = 1.0 / N;
                }
            }
        } else {
            // 无观测数据，保持权重不变（但归一化）
            double weightSum = 0.0;
            for (Particle p : particles) {
                weightSum += p.weight;
            }
            if (weightSum == 0) {
                for (Particle p : particles) {
                    p.weight = 1.0 / N;
                }
            } else {
                for (Particle p : particles) {
                    p.weight /= weightSum;
                }
            }
        }

        // 4. 估计当前最优位置：计算粒子集合的加权平均坐标
        estX = 0.0;
        estY = 0.0;
        for (Particle p : particles) {
            estX += p.x * p.weight;
            estY += p.y * p.weight;
        }

        // 5. 低方差重采样：根据当前的粒子权重分布生成新的粒子集合
        List<Particle> newParticles = new ArrayList<>(N);
        double r = rand.nextDouble() / N;
        double c = particles.get(0).weight;
        int index = 0;
        for (int m = 0; m < N; m++) {
            double U = r + m * (1.0 / N);
            while (U > c) {
                index++;
                if (index >= N) {
                    index = N - 1;
                    break;
                }
                c += particles.get(index).weight;
            }
            Particle chosen = particles.get(index);
            newParticles.add(new Particle(chosen.x, chosen.y, 1.0 / N));
        }

        // 6. 返回结果，包括估计位置和新的粒子集合
        return new Result(estX, estY, newParticles);
    }
}