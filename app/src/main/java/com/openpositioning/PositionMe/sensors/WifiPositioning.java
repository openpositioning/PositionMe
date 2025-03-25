package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WiFi定位实现类
 * 提供基于WiFi指纹的室内定位功能
 */
public class WifiPositioning implements Observer {
    
    private static final String TAG = "WifiPositioning";

    @Override
    public void update(Object[] objList) {

    }

    // 定位算法类型
    public enum Algorithm {
        KNN,            // K最近邻
        WEIGHTED_KNN,   // 加权K最近邻
        EUCLIDEAN       // 欧几里得距离
    }
    
    private Context context;
    private WifiDataProcessor wifiDataProcessor;
    
    // 存储WiFi指纹数据库
    private Map<String, List<WifiFingerprint>> fingerprintDatabase;
    
    // 当前位置
    private Position currentPosition;
    private Position lastPosition;
    
    // 观察者列表
    private List<Observer> observers;
    
    // 定位算法
    private Algorithm algorithm = Algorithm.WEIGHTED_KNN;
    
    // KNN算法的K值
    private int kValue = 3;
    
    // 是否启用平滑过滤
    private boolean enableSmoothing = true;
    
    /**
     * 构造函数
     */
    public WifiPositioning(Context context, WifiDataProcessor wifiDataProcessor) {
        this.context = context;
        this.wifiDataProcessor = wifiDataProcessor;
        this.fingerprintDatabase = new HashMap<>();
        this.observers = new ArrayList<>();
        
        // 注册为WiFi数据处理器的观察者
        wifiDataProcessor.registerObserver(this);
    }
    
    /**
     * 接收WiFi数据更新
     */
    @Override
    public void update(Object data) {
        if (data instanceof Wifi[]) {
            Wifi[] wifiData = (Wifi[]) data;
            estimatePosition(wifiData);
        }
    }
    
    /**
     * 根据WiFi数据估计位置
     */
    private void estimatePosition(Wifi[] wifiData) {
        if (wifiData == null || wifiData.length == 0 || fingerprintDatabase.isEmpty()) {
            return;
        }
        
        lastPosition = currentPosition;
        
        switch (algorithm) {
            case KNN:
                currentPosition = estimatePositionByKNN(wifiData);
                break;
            case WEIGHTED_KNN:
                currentPosition = estimatePositionByWeightedKNN(wifiData);
                break;
            case EUCLIDEAN:
                currentPosition = estimatePositionByEuclidean(wifiData);
                break;
            default:
                currentPosition = estimatePositionByWeightedKNN(wifiData);
        }
        
        // 应用平滑过滤
        if (enableSmoothing && lastPosition != null) {
            currentPosition = smoothPosition(lastPosition, currentPosition);
        }
        
        if (currentPosition != null) {
            notifyObservers();
        }
    }
    
    /**
     * 使用K最近邻算法估计位置
     */
    private Position estimatePositionByKNN(Wifi[] wifiData) {
        List<DistanceResult> results = new ArrayList<>();
        
        // 计算当前WiFi指纹与所有参考指纹的距离
        for (Map.Entry<String, List<WifiFingerprint>> entry : fingerprintDatabase.entrySet()) {
            String locationId = entry.getKey();
            List<WifiFingerprint> fingerprints = entry.getValue();
            
            for (WifiFingerprint fingerprint : fingerprints) {
                double distance = calculateEuclideanDistance(wifiData, fingerprint.getWifiData());
                results.add(new DistanceResult(locationId, fingerprint.getPosition(), distance));
            }
        }
        
        // 按距离排序
        Collections.sort(results, new Comparator<DistanceResult>() {
            @Override
            public int compare(DistanceResult r1, DistanceResult r2) {
                return Double.compare(r1.distance, r2.distance);
            }
        });
        
        // 取前K个结果的平均位置
        int k = Math.min(kValue, results.size());
        if (k == 0) return null;
        
        double sumX = 0, sumY = 0, sumZ = 0;
        for (int i = 0; i < k; i++) {
            Position pos = results.get(i).position;
            sumX += pos.getX();
            sumY += pos.getY();
            sumZ += pos.getZ();
        }
        
        return new Position(sumX / k, sumY / k, sumZ / k);
    }
    
    /**
     * 使用加权K最近邻算法估计位置
     */
    private Position estimatePositionByWeightedKNN(Wifi[] wifiData) {
        List<DistanceResult> results = new ArrayList<>();
        
        // 计算当前WiFi指纹与所有参考指纹的距离
        for (Map.Entry<String, List<WifiFingerprint>> entry : fingerprintDatabase.entrySet()) {
            String locationId = entry.getKey();
            List<WifiFingerprint> fingerprints = entry.getValue();
            
            for (WifiFingerprint fingerprint : fingerprints) {
                double distance = calculateEuclideanDistance(wifiData, fingerprint.getWifiData());
                results.add(new DistanceResult(locationId, fingerprint.getPosition(), distance));
            }
        }
        
        // 按距离排序
        Collections.sort(results, new Comparator<DistanceResult>() {
            @Override
            public int compare(DistanceResult r1, DistanceResult r2) {
                return Double.compare(r1.distance, r2.distance);
            }
        });
        
        // 取前K个结果的加权平均位置
        int k = Math.min(kValue, results.size());
        if (k == 0) return null;
        
        double sumX = 0, sumY = 0, sumZ = 0;
        double sumWeights = 0;
        
        for (int i = 0; i < k; i++) {
            Position pos = results.get(i).position;
            double distance = results.get(i).distance;
            double weight = 1.0 / (distance + 0.1); // 避免除以零
            
            sumX += pos.getX() * weight;
            sumY += pos.getY() * weight;
            sumZ += pos.getZ() * weight;
            sumWeights += weight;
        }
        
        return new Position(sumX / sumWeights, sumY / sumWeights, sumZ / sumWeights);
    }
    
    /**
     * 使用欧几里得距离算法估计位置
     */
    private Position estimatePositionByEuclidean(Wifi[] wifiData) {
        double minDistance = Double.MAX_VALUE;
        Position bestPosition = null;
        
        for (Map.Entry<String, List<WifiFingerprint>> entry : fingerprintDatabase.entrySet()) {
            List<WifiFingerprint> fingerprints = entry.getValue();
            
            for (WifiFingerprint fingerprint : fingerprints) {
                double distance = calculateEuclideanDistance(wifiData, fingerprint.getWifiData());
                if (distance < minDistance) {
                    minDistance = distance;
                    bestPosition = fingerprint.getPosition();
                }
            }
        }
        
        return bestPosition;
    }
    
    /**
     * 平滑位置变化
     */
    private Position smoothPosition(Position lastPos, Position currentPos) {
        double alpha = 0.3; // 平滑因子，值越小平滑效果越强
        
        double x = alpha * currentPos.getX() + (1 - alpha) * lastPos.getX();
        double y = alpha * currentPos.getY() + (1 - alpha) * lastPos.getY();
        double z = alpha * currentPos.getZ() + (1 - alpha) * lastPos.getZ();
        
        return new Position(x, y, z);
    }
    
    /**
     * 计算欧几里得距离
     */
    private double calculateEuclideanDistance(Wifi[] current, Wifi[] reference) {
        Map<Long, Integer> currentMap = new HashMap<>();
        for (Wifi wifi : current) {
            currentMap.put(wifi.getBssid(), wifi.getLevel());
        }
        
        Map<Long, Integer> referenceMap = new HashMap<>();
        for (Wifi wifi : reference) {
            referenceMap.put(wifi.getBssid(), wifi.getLevel());
        }
        
        double sumSquared = 0;
        
        // 计算共同AP的信号强度差的平方和
        for (Map.Entry<Long, Integer> entry : currentMap.entrySet()) {
            Long bssid = entry.getKey();
            Integer currentLevel = entry.getValue();
            
            if (referenceMap.containsKey(bssid)) {
                Integer referenceLevel = referenceMap.get(bssid);
                sumSquared += Math.pow(currentLevel - referenceLevel, 2);
            } else {
                // 对于不存在于参考指纹中的AP，使用一个较大的差值
                sumSquared += 400; // 20^2
            }
        }
        
        // 对于参考指纹中存在但当前扫描不存在的AP
        for (Map.Entry<Long, Integer> entry : referenceMap.entrySet()) {
            Long bssid = entry.getKey();
            if (!currentMap.containsKey(bssid)) {
                sumSquared += 400; // 20^2
            }
        }
        
        return Math.sqrt(sumSquared);
    }
    
    /**
     * 添加WiFi指纹
     */
    public void addFingerprint(String locationId, Wifi[] wifiData, Position position) {
        WifiFingerprint fingerprint = new WifiFingerprint(wifiData, position);
        
        if (!fingerprintDatabase.containsKey(locationId)) {
            fingerprintDatabase.put(locationId, new ArrayList<>());
        }
        
        fingerprintDatabase.get(locationId).add(fingerprint);
        Log.d(TAG, "添加指纹: 位置ID=" + locationId + ", WiFi数量=" + wifiData.length);
    }
    
    /**
     * 删除指定位置的所有指纹
     */
    public void removeFingerprints(String locationId) {
        if (fingerprintDatabase.containsKey(locationId)) {
            fingerprintDatabase.remove(locationId);
            Log.d(TAG, "删除位置ID=" + locationId + "的所有指纹");
        }
    }
    
    /**
     * 清空指纹数据库
     */
    public void clearFingerprintDatabase() {
        fingerprintDatabase.clear();
        Log.d(TAG, "清空指纹数据库");
    }
    
    /**
     * 获取当前位置
     */
    public Position getCurrentPosition() {
        return currentPosition;
    }
    
    /**
     * 设置定位算法
     */
    public void setAlgorithm(Algorithm algorithm) {
        this.algorithm = algorithm;
        Log.d(TAG, "设置定位算法: " + algorithm.name());
    }
    
    /**
     * 设置KNN算法的K值
     */
    public void setKValue(int kValue) {
        if (kValue > 0) {
            this.kValue = kValue;
            Log.d(TAG, "设置K值: " + kValue);
        }
    }
    
    /**
     * 启用/禁用位置平滑
     */
    public void setEnableSmoothing(boolean enableSmoothing) {
        this.enableSmoothing = enableSmoothing;
        Log.d(TAG, "位置平滑: " + (enableSmoothing ? "启用" : "禁用"));
    }
    
    /**
     * 注册观察者
     */
    public void registerObserver(Observer observer) {
        observers.add(observer);
    }
    
    /**
     * 通知观察者
     */
    private void notifyObservers() {
        for (Observer observer : observers) {
            observer.update(currentPosition);
        }
    }
    
    /**
     * 保存指纹数据库到文件
     */
    public void saveFingerprintDatabase() {
        // TODO: 实现指纹数据库的持久化存储
        Log.d(TAG, "保存指纹数据库");
    }
    
    /**
     * 从文件加载指纹数据库
     */
    public void loadFingerprintDatabase() {
        // TODO: 实现指纹数据库的加载
        Log.d(TAG, "加载指纹数据库");
    }
    
    /**
     * 位置类
     */
    public static class Position {
        private double x;
        private double y;
        private double z;
        
        public Position(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
        
        @Override
        public String toString() {
            return "Position{" +
                    "x=" + x +
                    ", y=" + y +
                    ", z=" + z +
                    '}';
        }
    }
    
    /**
     * WiFi指纹类
     */
    private static class WifiFingerprint {
        private Wifi[] wifiData;
        private Position position;
        
        public WifiFingerprint(Wifi[] wifiData, Position position) {
            this.wifiData = wifiData;
            this.position = position;
        }
        
        public Wifi[] getWifiData() { return wifiData; }
        public Position getPosition() { return position; }
    }
    
    /**
     * 距离结果类
     */
    private static class DistanceResult {
        private String locationId;
        private Position position;
        private double distance;
        
        public DistanceResult(String locationId, Position position, double distance) {
            this.locationId = locationId;
            this.position = position;
            this.distance = distance;
        }
    }
}