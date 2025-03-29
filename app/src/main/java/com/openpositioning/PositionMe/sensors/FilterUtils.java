package com.openpositioning.PositionMe.sensors;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class FilterUtils {
    // Particle state: position and weight
    public static class Particle {
        double x;
        double y;
        double weight;

        public Particle(double x, double y, double weight) {
            this.x = x;
            this.y = y;
            this.weight = weight;
        }

        public Particle(Particle p) {
            this.x = p.x;
            this.y = p.y;
            this.weight = p.weight;
        }
    }

    public static class ParticleFilter {
        List<Particle> particles;
        int numParticles;
        Random random = new Random();

        // Noise parameters
        double processNoisePos = 0.6;
        double measurementNoise = 1.1; // Adjust based on your sensor characteristics

        // WiFi weight parameters: a constant base weight and an external ratio
        double wifiRatio;  // External factor that determines how important the WiFi measurement is

        /**
         * @param numParticles Number of particles
         * @param initX Initial x position
         * @param initY Initial y position
         * @param wifiRatio External factor to boost WiFi influence
         */
        public ParticleFilter(int numParticles, double initX, double initY, double wifiRatio) {
            this.numParticles = numParticles;
            this.wifiRatio = wifiRatio;
            particles = new ArrayList<>();
            for (int i = 0; i < numParticles; i++) {
                double x = initX + randomGaussian(0, processNoisePos);
                double y = initY + randomGaussian(0, processNoisePos);
                particles.add(new Particle(x, y, 1.0 / numParticles));
            }
        }

        // Setter to update the WiFi ratio dynamically
        public void setWifiRatio(double wifiRatio) {
            this.wifiRatio = wifiRatio;
        }

        private double randomGaussian(double mean, double stdDev) {
            return mean + stdDev * random.nextGaussian();
        }

        /**
         * Prediction step: update particles based on PDR input.
         * @param pdrX Predicted x from PDR
         * @param pdrY Predicted y from PDR
         */
        public void predict(double pdrX, double pdrY) {
            for (Particle p : particles) {
                p.x = pdrX + randomGaussian(0, processNoisePos);
                p.y = pdrY + randomGaussian(0, processNoisePos);
            }
        }

        /**
         * Gaussian likelihood function.
         * @param error Distance error between particle and measurement
         * @param stdDev Standard deviation (effective noise)
         * @return Likelihood value
         */
        private double gaussianProbability(double error, double stdDev) {
            return (1.0 / (stdDev * Math.sqrt(2 * Math.PI))) *
                    Math.exp(- (error * error) / (2 * stdDev * stdDev));
        }

        /**
         * Update step: adjust particle weights using WiFi measurements.
         * This version uses an effective measurement noise reduced by wifiRatio and injects new particles near the WiFi measurement.
         * @param measuredX WiFi measured x position
         * @param measuredY WiFi measured y position
         */
        public void update(double measuredX, double measuredY) {
            double totalWeight = 0;
            // Use a smaller effective noise to boost the WiFi measurement's influence.
            double effectiveNoise = measurementNoise / wifiRatio;
            for (Particle p : particles) {
                double dx = p.x - measuredX;
                double dy = p.y - measuredY;
                double error = Math.sqrt(dx * dx + dy * dy);
                double likelihood = gaussianProbability(error, effectiveNoise);
                p.weight = likelihood;
                totalWeight += p.weight;
            }
            // Normalize weights; if total weight is zero, reset to uniform weights.
            if (totalWeight == 0) {
                for (Particle p : particles) {
                    p.weight = 1.0 / numParticles;
                }
            } else {
                for (Particle p : particles) {
                    p.weight /= totalWeight;
                }
            }

            // Inject new particles at the WiFi measurement to further emphasize its influence.
            // For example, replace 20% of the particles with new ones centered around the WiFi measurement.
            int numInjection = (int)(0.05 * numParticles);
            for (int i = 0; i < numInjection; i++) {
                int idx = random.nextInt(numParticles);
                // Adding a bit of noise so the injected particles are not all identical.
                double newX = measuredX + randomGaussian(0, measurementNoise / 2);
                double newY = measuredY + randomGaussian(0, measurementNoise / 2);
                particles.set(idx, new Particle(newX, newY, 1.0 / numParticles));
            }
        }

        /**
         * Resample step: perform systematic resampling based on particle weights.
         */
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
                // Ensure we do not go out of bounds by checking index against cumulativeSum length.
                while (index < cumulativeSum.length - 1 && u > cumulativeSum[index]) {
                    index++;
                }
                Particle selected = particles.get(index);
                newParticles.add(new Particle(selected.x, selected.y, 1.0 / numParticles));
            }
            particles = newParticles;
        }

        /**
         * Estimate the current state as the weighted average of the particles.
         * @return Estimated particle state.
         */
        public Particle estimate() {
            double estX = 0, estY = 0;
            for (Particle p : particles) {
                estX += p.x * p.weight;
                estY += p.y * p.weight;
            }
            return new Particle(estX, estY, 1.0);
        }
    }


    /**
     * This EKF fuses PDR (for prediction) and WiFi (for measurement update).
     * The state vector is [x, y] (position), and the filter assumes a very simple
     * dynamic model where the control input is the incremental displacement computed
     * from successive PDR readings. The measurement model is assumed to be direct (z = x + noise).
     */
    public static class EKFFilter {
        // State vector (position)
        private double x;
        private double y;
        // 2x2 state covariance matrix P
        private double[][] P = new double[2][2];
        // Process noise covariance Q (for the prediction step)
        private double[][] Q = new double[2][2];
        // Measurement noise covariance R (for the WiFi measurement)
        private double[][] R = new double[2][2];

        // To compute incremental displacement from PDR readings
        private double lastPDRx;
        private double lastPDRy;
        private boolean isInitialized = false;

        // WiFi weight factor: larger values reduce effective measurement noise, increasing WiFi influence.
        private double wifiRatio;

        /**
         * @param initialX         Initial x position from PDR.
         * @param initialY         Initial y position from PDR.
         * @param processNoise     Standard deviation for process noise (PDR uncertainty).
         * @param measurementNoise Standard deviation for WiFi measurement noise (before scaling).
         * @param wifiRatio        Factor to boost WiFi influence (larger -> WiFi has more weight).
         */
        public EKFFilter(double initialX, double initialY, double processNoise, double measurementNoise, double wifiRatio) {
            // Initialize state with the first PDR reading.
            this.x = initialX;
            this.y = initialY;
            this.lastPDRx = initialX;
            this.lastPDRy = initialY;
            this.wifiRatio = wifiRatio;
            isInitialized = true;

            // Initialize state covariance P with a small uncertainty.
            P[0][0] = 1.0;
            P[0][1] = 0.0;
            P[1][0] = 0.0;
            P[1][1] = 1.0;

            // Set process noise covariance Q = diag(processNoise^2, processNoise^2)
            Q[0][0] = processNoise * processNoise;
            Q[0][1] = 0.0;
            Q[1][0] = 0.0;
            Q[1][1] = processNoise * processNoise;

            // Set measurement noise covariance R.
            // To boost WiFi, we reduce effective measurement noise by dividing by wifiRatio.
            double effectiveMeasurementNoise = measurementNoise / wifiRatio;
            R[0][0] = effectiveMeasurementNoise * effectiveMeasurementNoise;
            R[0][1] = 0.0;
            R[1][0] = 0.0;
            R[1][1] = effectiveMeasurementNoise * effectiveMeasurementNoise;
        }

        /**
         * Prediction step using new PDR data (assumed to be an absolute position).
         * The control input is computed as the incremental displacement from the last PDR reading.
         *
         * @param pdrX The current PDR x measurement.
         * @param pdrY The current PDR y measurement.
         */
        public void predict(double pdrX, double pdrY) {
            if (!isInitialized) {
                // If not yet initialized, set the state from the first PDR reading.
                x = pdrX;
                y = pdrY;
                lastPDRx = pdrX;
                lastPDRy = pdrY;
                isInitialized = true;
                return;
            }

            // Compute displacement (control input) as difference from the last PDR reading.
            double u_x = pdrX - lastPDRx;
            double u_y = pdrY - lastPDRy;

            // Update the last PDR reading.
            lastPDRx = pdrX;
            lastPDRy = pdrY;

            // Propagate state: x_k = x_{k-1} + u
            x = x + u_x;
            y = y + u_y;

            // Update the covariance: P = P + Q (since the state transition Jacobian is I)
            P[0][0] += Q[0][0];
            P[0][1] += Q[0][1];
            P[1][0] += Q[1][0];
            P[1][1] += Q[1][1];
        }

        /**
         * Update step using the WiFi measurement.
         * The measurement model is assumed to be: z = [x, y] + noise.
         *
         * @param wifiX The WiFi measured x position.
         * @param wifiY The WiFi measured y position.
         */
        public void update(double wifiX, double wifiY) {
            // Innovation (measurement residual): y = z - h(x), where h(x) = [x, y].
            double innov0 = wifiX - x;
            double innov1 = wifiY - y;

            // Innovation covariance S = P + R (since the measurement matrix H = I)
            double s00 = P[0][0] + R[0][0];
            double s01 = P[0][1] + R[0][1];
            double s10 = P[1][0] + R[1][0];
            double s11 = P[1][1] + R[1][1];

            // Invert S (2x2 inversion)
            double detS = s00 * s11 - s01 * s10;
            double invS00 = s11 / detS;
            double invS01 = -s01 / detS;
            double invS10 = -s10 / detS;
            double invS11 = s00 / detS;

            // Compute Kalman Gain: K = P * S^{-1} (since H = I)
            double K00 = P[0][0] * invS00 + P[0][1] * invS10;
            double K01 = P[0][0] * invS01 + P[0][1] * invS11;
            double K10 = P[1][0] * invS00 + P[1][1] * invS10;
            double K11 = P[1][0] * invS01 + P[1][1] * invS11;

            // Update state estimate: x = x + K * innovation
            x = x + K00 * innov0 + K01 * innov1;
            y = y + K10 * innov0 + K11 * innov1;

            // Update covariance: P = (I - K) * P
            double newP00 = (1 - K00) * P[0][0] - K01 * P[1][0];
            double newP01 = (1 - K00) * P[0][1] - K01 * P[1][1];
            double newP10 = -K10 * P[0][0] + (1 - K11) * P[1][0];
            double newP11 = -K10 * P[0][1] + (1 - K11) * P[1][1];

            P[0][0] = newP00;
            P[0][1] = newP01;
            P[1][0] = newP10;
            P[1][1] = newP11;
        }

        /**
         * Returns the current estimated x position.
         */
        public double getX() {
            return x;
        }

        /**
         * Returns the current estimated y position.
         */
        public double getY() {
            return y;
        }
    }
}





//package com.openpositioning.PositionMe.sensors;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Random;
//public class FilterUtils {
//    // 定义粒子状态
//    class Particle {
//        double x;      // 位置 x
//        double y;      // 位置 y
//        double weight; // 权重
//
//        public Particle(double x, double y, double weight) {
//            this.x = x;
//            this.y = y;
//            this.weight = weight;
//        }
//    }
//
//
//    class ParticleFilter {
//        List<Particle> particles;
//        int numParticles;
//        Random random = new Random();
//
//        // 噪声参数
//        double processNoisePos = 0.5;
//        double processNoiseTheta = 0.05;
//        double measurementNoise = 3.0; // 根据实际传感器调整
//
//        public ParticleFilter(int numParticles, double initX, double initY) {
//            this.numParticles = numParticles;
//            particles = new ArrayList<>();
//            for (int i = 0; i < numParticles; i++) {
//                double x = initX + randomGaussian(0, processNoisePos);
//                double y = initY + randomGaussian(0, processNoisePos);
//                particles.add(new Particle(x, y, 1.0 / numParticles));
//            }
//        }
//
//        private double randomGaussian(double mean, double stdDev) {
//            return mean + stdDev * random.nextGaussian();
//        }
//
//        // 预测步骤：利用 PDR 数据（步长和转角）
//        public void predict(double pdrX, double pdrY) {
//            for (Particle p : particles) {
//                // 将 PDR 输出作为预测中心，加上高斯过程噪声
//                p.x = pdrX + randomGaussian(0, processNoisePos);
//                p.y = pdrY + randomGaussian(0, processNoisePos);
//            }
//        }
//
//
//        // 更新步骤：利用 WiFi 和 GPS 测量数据进行校正
//        public void update(double measuredX, double measuredY) {
//            double totalWeight = 0;
//            for (Particle p : particles) {
//                // 计算位置误差（欧氏距离）
//                double dx = p.x - measuredX;
//                double dy = p.y - measuredY;
//                double error = Math.sqrt(dx * dx + dy * dy);
//                // 计算似然值，假设测量噪声为高斯分布
//                double likelihood = gaussianProbability(error, measurementNoise);
//                p.weight = likelihood;
//                totalWeight += p.weight;
//            }
//            // 归一化权重
//            for (Particle p : particles) {
//                p.weight /= totalWeight;
//            }
//        }
//
//        private double gaussianProbability(double error, double stdDev) {
//            return (1.0 / (stdDev * Math.sqrt(2 * Math.PI))) *
//                    Math.exp(- (error * error) / (2 * stdDev * stdDev));
//        }
//
//        // 重采样步骤：系统重采样
//        public void resample() {
//            List<Particle> newParticles = new ArrayList<>();
//            double[] cumulativeSum = new double[particles.size()];
//            cumulativeSum[0] = particles.get(0).weight;
//            for (int i = 1; i < particles.size(); i++) {
//                cumulativeSum[i] = cumulativeSum[i - 1] + particles.get(i).weight;
//            }
//
//            double step = 1.0 / numParticles;
//            double start = random.nextDouble() * step;
//            int index = 0;
//            for (int i = 0; i < numParticles; i++) {
//                double u = start + i * step;
//                while (u > cumulativeSum[index]) {
//                    index++;
//                }
//                Particle selected = particles.get(index);
//                // 复制粒子，重新初始化权重
//                newParticles.add(new Particle(selected.x, selected.y, 1.0 / numParticles));
//            }
//            particles = newParticles;
//        }
//
//
//        // 估计当前状态：加权平均法
//        public Particle estimate() {
//            double estX = 0, estY = 0;
//            for (Particle p : particles) {
//                estX += p.x * p.weight;
//                estY += p.y * p.weight;
//            }
//            return new Particle(estX, estY, 1.0);
//        }
//
//    }
//
//}
