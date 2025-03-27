package com.openpositioning.PositionMe.sensors;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
public class FilterUtils {
    // 定义粒子状态
    class Particle {
        double x;      // 位置 x
        double y;      // 位置 y
        double weight; // 权重

        public Particle(double x, double y, double weight) {
            this.x = x;
            this.y = y;
            this.weight = weight;
        }
    }


    class ParticleFilter {
        List<Particle> particles;
        int numParticles;
        Random random = new Random();

        // 噪声参数
        double processNoisePos = 0.5;
        double processNoiseTheta = 0.05;
        double measurementNoise = 3.0; // 根据实际传感器调整

        public ParticleFilter(int numParticles, double initX, double initY) {
            this.numParticles = numParticles;
            particles = new ArrayList<>();
            for (int i = 0; i < numParticles; i++) {
                double x = initX + randomGaussian(0, processNoisePos);
                double y = initY + randomGaussian(0, processNoisePos);
                particles.add(new Particle(x, y, 1.0 / numParticles));
            }
        }

        private double randomGaussian(double mean, double stdDev) {
            return mean + stdDev * random.nextGaussian();
        }

        // 预测步骤：利用 PDR 数据（步长和转角）
        public void predict(double pdrX, double pdrY) {
            for (Particle p : particles) {
                // 将 PDR 输出作为预测中心，加上高斯过程噪声
                p.x = pdrX + randomGaussian(0, processNoisePos);
                p.y = pdrY + randomGaussian(0, processNoisePos);
            }
        }


        // 更新步骤：利用 WiFi 和 GPS 测量数据进行校正
        public void update(double measuredX, double measuredY) {
            double totalWeight = 0;
            for (Particle p : particles) {
                // 计算位置误差（欧氏距离）
                double dx = p.x - measuredX;
                double dy = p.y - measuredY;
                double error = Math.sqrt(dx * dx + dy * dy);
                // 计算似然值，假设测量噪声为高斯分布
                double likelihood = gaussianProbability(error, measurementNoise);
                p.weight = likelihood;
                totalWeight += p.weight;
            }
            // 归一化权重
            for (Particle p : particles) {
                p.weight /= totalWeight;
            }
        }

        private double gaussianProbability(double error, double stdDev) {
            return (1.0 / (stdDev * Math.sqrt(2 * Math.PI))) *
                    Math.exp(- (error * error) / (2 * stdDev * stdDev));
        }

        // 重采样步骤：系统重采样
        public void resample() {
            List<Particle> newParticles = new ArrayList<>();
            double[] cumulativeSum = new double[particles.size()];
            cumulativeSum[0] = particles.get(0).weight;
            for (int i = 1; i < particles.size(); i++) {
                cumulativeSum[i] = cumulativeSum[i - 1] + particles.get(i).weight;
            }

            double step = 1.0 / numParticles;
            double start = random.nextDouble() * step;
            int index = 0;
            for (int i = 0; i < numParticles; i++) {
                double u = start + i * step;
                while (u > cumulativeSum[index]) {
                    index++;
                }
                Particle selected = particles.get(index);
                // 复制粒子，重新初始化权重
                newParticles.add(new Particle(selected.x, selected.y, 1.0 / numParticles));
            }
            particles = newParticles;
        }


        // 估计当前状态：加权平均法
        public Particle estimate() {
            double estX = 0, estY = 0;
            for (Particle p : particles) {
                estX += p.x * p.weight;
                estY += p.y * p.weight;
            }
            return new Particle(estX, estY, 1.0);
        }

    }

}
