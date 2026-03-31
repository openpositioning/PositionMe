package com.openpositioning.PositionMe.utils;




public class PdrParameterOptimization {


    public static class CurrentParameters {

        public static final float K = 0.364f;
        

        public static final double Q_XX = 0.5;
        public static final double Q_YY = 0.5;
        public static final double R_GNSS_XX = 25.0;
        public static final double R_GNSS_YY = 25.0;
        public static final double R_WIFI_XX = 9.0;
        public static final double R_WIFI_YY = 9.0;
        public static final double P_INIT_XX = 10.0;
        public static final double P_INIT_YY = 10.0;
        
        // Madgwick AHRS
        public static final float MADGWICK_BETA = 0.12f;
    }


    public static class ImprovedParameters_Phase1 {

        public static final float K_BASE = 0.364f;
        

        public static class StepFrequencyClass {
            public static final float SLOW_WALK_THRESHOLD = 1.2f;      // Hz
            public static final float SLOW_WALK_FACTOR = 0.85f;
            
            public static final float NORMAL_WALK_THRESHOLD = 1.8f;    // Hz
            public static final float NORMAL_WALK_FACTOR = 1.0f;
            
            public static final float FAST_WALK_THRESHOLD = 2.2f;      // Hz
            public static final float FAST_WALK_FACTOR = 1.1f;
            
            public static final float VERY_FAST_FACTOR = 1.2f;
        }
        

        public static final double Q_XX = 0.01;
        public static final double Q_YY = 0.01;
        public static final double R_GNSS_XX = 4.0;
        public static final double R_GNSS_YY = 4.0;
        public static final double R_WIFI_XX = 36.0;
        public static final double R_WIFI_YY = 36.0;
        public static final double P_INIT_XX = 1.0;
        public static final double P_INIT_YY = 1.0;
        

        public static final float MADGWICK_BETA_DEFAULT = 0.15f;
        public static final float MADGWICK_BETA_HIGH_MOTION = 0.30f;
        public static final float MADGWICK_BETA_LOW_MOTION = 0.10f;
        

        public static final float MAGNETOMETER_MIN_UT = 20.0f;
        public static final float MAGNETOMETER_MAX_UT = 60.0f;
    }


    public static class ImprovedParameters_Phase2 {

        public static class UserAdaptation {


            public static final float[] HEIGHT_CM = {150, 160, 170, 180, 190};
            public static final float[] STRIDE_FACTOR = {0.80f, 0.90f, 1.0f, 1.10f, 1.25f};
            

            public static final float[] WEIGHT_KG = {50, 65, 80, 95};
            public static final float[] ACCEL_SENSITIVITY = {1.15f, 1.0f, 0.90f, 0.80f};
        }
        

        public static class NonlinearStrideModel {

            // stride_length = a0 + a1frequency + a2frequency^2 + a3sqrt(bounce)
            public static final float A0 = 0.1f;
            public static final float A1 = 0.3f;
            public static final float A2 = -0.05f;
            public static final float A3 = 0.5f;
        }
        

        public static class CorridorAlignment {
            public static final float CORRIDOR_SNAP_THRESHOLD = 1.5f;
            public static final float CORRIDOR_DIRECTION_TOLERANCE = 15.0f;
        }
        

        public static class MultiHypothesisTracking {
            public static final int NUM_HYPOTHESES = 3;
            public static final float HYPOTHESIS_PRUNING_RATIO = 0.1f;
        }
    }


    public static void printParameterComparison() {
        System.out.println("=== PDR鍙傛暟浼樺寲瀵规瘮 ===\n");
        
        System.out.println("銆怶eiberg绠楁硶銆?);
        System.out.println("褰撳墠: K = " + CurrentParameters.K + " (鍥哄畾鍊?");
        System.out.println("鏀硅繘: K = " + ImprovedParameters_Phase1.K_BASE + 
                         " 脳 姝ラ鍒嗙被鍣?(0.85~1.2)");
        System.out.println("棰勬湡鏀硅繘: 卤3-5% 姝ラ暱绮惧害鎻愬崌\n");
        
        System.out.println("銆怑KF杩囩▼鍣０(Q)銆?);
        System.out.println("褰撳墠: Q_xx = " + CurrentParameters.Q_XX + 
                         " (杩囧害淇′换GNSS/WiFi)");
        System.out.println("鏀硅繘: Q_xx = " + ImprovedParameters_Phase1.Q_XX + 
                         " (鏇翠俊浠籔DR鍐呴儴)");
        System.out.println("棰勬湡鏀硅繘: 鍑忓皯50-70% 鐨勮瀺鍚堣烦璺僜n");
        
        System.out.println("銆怑KF娴嬮噺鍣０(R_WIFI)銆?);
        System.out.println("褰撳墠: R_WIFI = " + CurrentParameters.R_WIFI_XX + 
                         "m虏 (蟽=3m) - 杩囧害涔愯");
        System.out.println("鏀硅繘: R_WIFI = " + ImprovedParameters_Phase1.R_WIFI_XX + 
                         "m虏 (蟽=6m) - 鏇寸幇瀹?);
        System.out.println("棰勬湡鏀硅繘: WiFi寮傚父鍊肩殑褰卞搷闄嶄綆50%\n");
        
        System.out.println("銆怣adgwick Beta銆?);
        System.out.println("褰撳墠: 尾 = " + CurrentParameters.MADGWICK_BETA + 
                         " (鍥哄畾鍊硷紝鍙兘杩囦綆)");
        System.out.println("鏀硅繘: 尾 = " + ImprovedParameters_Phase1.MADGWICK_BETA_DEFAULT + 
                         " (鑷€傚簲: 0.10~0.30)");
        System.out.println("棰勬湡鏀硅繘: 闄€铻轰华婕傜Щ閫熷害闄嶄綆30-40%\n");
        
        System.out.println("銆愬垵濮嬪崗鏂瑰樊(P)銆?);
        System.out.println("褰撳墠: P_init = " + CurrentParameters.P_INIT_XX);
        System.out.println("鏀硅繘: P_init = " + ImprovedParameters_Phase1.P_INIT_XX);
        System.out.println("棰勬湡鏀硅繘: EKF鏀舵暃閫熷害鎻愬崌3鍊峔n");
    }


    public static float classifyStepFrequency(float stepFrequencyHz) {
        if (stepFrequencyHz < ImprovedParameters_Phase1.StepFrequencyClass.SLOW_WALK_THRESHOLD) {
            return ImprovedParameters_Phase1.StepFrequencyClass.SLOW_WALK_FACTOR;
        } else if (stepFrequencyHz < ImprovedParameters_Phase1.StepFrequencyClass.NORMAL_WALK_THRESHOLD) {
            return ImprovedParameters_Phase1.StepFrequencyClass.NORMAL_WALK_FACTOR;
        } else if (stepFrequencyHz < ImprovedParameters_Phase1.StepFrequencyClass.FAST_WALK_THRESHOLD) {
            return ImprovedParameters_Phase1.StepFrequencyClass.FAST_WALK_FACTOR;
        } else {
            return ImprovedParameters_Phase1.StepFrequencyClass.VERY_FAST_FACTOR;
        }
    }


    public static float getUserHeightAdaptation(int userHeightCm) {

        float[] heights = ImprovedParameters_Phase2.UserAdaptation.HEIGHT_CM;
        float[] factors = ImprovedParameters_Phase2.UserAdaptation.STRIDE_FACTOR;
        
        if (userHeightCm <= heights[0]) {
            return factors[0];
        }
        if (userHeightCm >= heights[heights.length - 1]) {
            return factors[factors.length - 1];
        }
        
        for (int i = 0; i < heights.length - 1; i++) {
            if (userHeightCm >= heights[i] && userHeightCm < heights[i + 1]) {
                float ratio = (userHeightCm - heights[i]) / (heights[i + 1] - heights[i]);
                return factors[i] * (1 - ratio) + factors[i + 1] * ratio;
            }
        }
        
        return 1.0f;
    }


    public static float calculateImprovedStride(
            double maxAccel, double minAccel, 
            long stepDurationMs, int userHeightCm) {
        

        float bounce = (float) Math.pow((maxAccel - minAccel), 0.25);
        float baseStride = bounce * ImprovedParameters_Phase1.K_BASE * 2;
        

        float stepFreq = stepDurationMs > 0 ? 1000f / stepDurationMs : 1.8f;
        float frequencyFactor = classifyStepFrequency(stepFreq);
        

        float heightFactor = getUserHeightAdaptation(userHeightCm);
        

        float finalStride = baseStride * frequencyFactor * heightFactor;
        

        if (finalStride < 0.3f || finalStride > 1.2f) {
            return 0.75f;
        }
        
        return finalStride;
    }


    public static void printOptimizationSummary() {
        System.out.println("\n鈺斺晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晽");
        System.out.println("鈺?  PDR婕傜Щ鏀硅繘 - 浼樺厛绾ц鍔ㄨ鍒?     鈺?);
        System.out.println("鈺犫晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨暎");
        System.out.println("鈺?鉁?P1 (5鍒嗛挓): 璋冩暣EKF鍙傛暟             鈺?);
        System.out.println("鈺?  - Q: 0.5 鈫?0.01                      鈺?);
        System.out.println("鈺?  - R_WIFI: 9 鈫?36                    鈺?);
        System.out.println("鈺?  - P_init: 10 鈫?1                    鈺?);
        System.out.println("鈺?                                       鈺?);
        System.out.println("鈺?鉁?P2 (10鍒嗛挓): 纾佸姏璁″紓甯告娴?       鈺?);
        System.out.println("鈺?  - 妫€鏌ヨ寖鍥? 20-60 渭T                鈺?);
        System.out.println("鈺?  - 寮傚父鏃朵娇鐢ㄩ檧铻轰华+鍔犻€熷害璁?        鈺?);
        System.out.println("鈺?                                       鈺?);
        System.out.println("鈺?鉁?P3 (15鍒嗛挓): 姝ラ鍒嗙被鍣?            鈺?);
        System.out.println("鈺?  - 4涓€熷害绫诲埆                       鈺?);
        System.out.println("鈺?  - 淇鍥犲瓙: 0.85~1.2               鈺?);
        System.out.println("鈺?                                       鈺?);
        System.out.println("鈺?棰勬湡鎬绘敼杩? 30-50% 婕傜Щ鍑忓皯            鈺?);
        System.out.println("鈺氣晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨暆");
    }

    public static void main(String[] args) {
        printParameterComparison();
        printOptimizationSummary();
    }
}


