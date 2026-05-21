package com.aryan.pqciotframework.anomaly;

import com.aryan.pqciotframework.model.AnomalyEvent;
import com.aryan.pqciotframework.model.SensorData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AnomalyDetector {

    private static final double EPSILON = 1e-9;

    // ===== MOVING AVERAGE METHOD =====

    public double calculateMovingAverage(List<Double> values, int windowSize) {
        if (values.size() < windowSize) return 0.0;
        double sum = 0;
        for (int i = values.size() - windowSize; i < values.size(); i++) {
            sum += values.get(i);
        }
        return sum / windowSize;
    }

    public AnomalyEvent detectAnomalyMA(SensorData data, double movingAvg, double threshold) {
        double deviation = Math.abs(data.getSensorValue() - movingAvg);
        double deviationRatio = deviation / (Math.abs(movingAvg) + EPSILON);
        boolean isAnomaly = deviationRatio > threshold;
        double anomalyScore = toBoundedScore(deviationRatio, threshold);

        return new AnomalyEvent(
                data.getDeviceId(),
                data.getSensorValue(),
                anomalyScore,
                isAnomaly,
                "MovingAverage",
                data.getTimestamp()
        );
    }

    public List<AnomalyEvent> detectUsingMovingAverage(List<SensorData> data, int windowSize, double threshold) {
        List<AnomalyEvent> anomalies = new ArrayList<>();
        List<Double> window = new ArrayList<>();

        for (SensorData sensor : data) {
            window.add(sensor.getSensorValue());
            if (window.size() > windowSize) {
                window.remove(0);
            }

            if (window.size() >= windowSize) {
                double movingAvg = calculateMovingAverage(window, windowSize);
                AnomalyEvent event = detectAnomalyMA(sensor, movingAvg, threshold);
                anomalies.add(event);
            }
        }

        System.out.println("MA Detection: found " + anomalies.stream().filter(AnomalyEvent::isAnomaly).count()
                + " anomalies out of " + anomalies.size());
        return anomalies;
    }

    // ===== Z-SCORE METHOD =====

    public double calculateMean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    public double calculateStandardDeviation(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        double mean = calculateMean(values);
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);
        return Math.sqrt(variance);
    }

    public double calculateZScore(double value, double mean, double stdDev) {
        if (stdDev == 0) return 0.0;
        return Math.abs((value - mean) / stdDev);
    }

    public AnomalyEvent detectAnomalyZScore(SensorData data, double zScore, double threshold) {
        boolean isAnomaly = zScore > threshold;
        double anomalyScore = toBoundedScore(zScore, threshold);

        return new AnomalyEvent(
                data.getDeviceId(),
                data.getSensorValue(),
                anomalyScore,
                isAnomaly,
                "ZScore",
                data.getTimestamp()
        );
    }

    public List<AnomalyEvent> detectUsingZScore(List<SensorData> data, double threshold) {
        List<AnomalyEvent> anomalies = new ArrayList<>();

        // Get all values
        List<Double> values = new ArrayList<>();
        for (SensorData d : data) {
            values.add(d.getSensorValue());
        }

        double mean = calculateMean(values);
        double stdDev = calculateStandardDeviation(values);

        // Detect anomalies
        for (SensorData sensor : data) {
            double zScore = calculateZScore(sensor.getSensorValue(), mean, stdDev);
            AnomalyEvent event = detectAnomalyZScore(sensor, zScore, threshold);
            anomalies.add(event);
        }

        System.out.println("ZScore Detection: found " + anomalies.stream().filter(AnomalyEvent::isAnomaly).count()
                + " anomalies out of " + anomalies.size());
        return anomalies;
    }

    // ===== ENSEMBLE DETECTION =====

    public List<AnomalyEvent> detectAnomalies(List<SensorData> data,
                                              int maWindowSize, double maThreshold,
                                              double zScoreThreshold) {
        List<AnomalyEvent> maAnomalies = detectUsingMovingAverage(data, maWindowSize, maThreshold);
        List<AnomalyEvent> zsAnomalies = detectUsingZScore(data, zScoreThreshold);

        // Ensemble: flag if either method detects anomaly
        List<AnomalyEvent> combined = new ArrayList<>();
        for (int i = 0; i < maAnomalies.size(); i++) {
            AnomalyEvent ma = maAnomalies.get(i);
            AnomalyEvent zs = zsAnomalies.get(i);

            boolean eitherAnomaly = ma.isAnomaly() || zs.isAnomaly();
            AnomalyEvent ensemble = new AnomalyEvent(
                    ma.getDeviceId(),
                    ma.getSensorValue(),
                    (ma.getAnomalyScore() + zs.getAnomalyScore()) / 2,
                eitherAnomaly,
                    "Ensemble(MA+ZScore)",
                    ma.getTimestamp()
            );
            combined.add(ensemble);
        }

        System.out.println("\nEnsemble Detection: found " + combined.stream().filter(AnomalyEvent::isAnomaly).count()
                + " anomalies (either method detected)");
        return combined;
    }

    private double toBoundedScore(double value, double threshold) {
        double safeThreshold = Math.max(EPSILON, threshold);
        double normalized = (value / safeThreshold) * 100.0;
        if (Double.isNaN(normalized) || Double.isInfinite(normalized)) {
            return 100.0;
        }
        return Math.max(0.0, Math.min(100.0, normalized));
    }

    public static void main(String[] args) {
        AnomalyDetector detector = new AnomalyDetector();

        // Create test data with one outlier
        List<SensorData> testData = new ArrayList<>();
        testData.add(new SensorData("device-1", 22.0, System.currentTimeMillis()));
        testData.add(new SensorData("device-1", 22.1, System.currentTimeMillis() + 1000));
        testData.add(new SensorData("device-1", 22.2, System.currentTimeMillis() + 2000));
        testData.add(new SensorData("device-1", 50.0, System.currentTimeMillis() + 3000)); // Outlier
        testData.add(new SensorData("device-1", 22.3, System.currentTimeMillis() + 4000));

        // Detect using ensemble
        List<AnomalyEvent> anomalies = detector.detectAnomalies(testData, 3, 5.0, 2.0);

        System.out.println("\nDetected Anomalies:");
        for (AnomalyEvent event : anomalies) {
            if (event.isAnomaly()) {
                System.out.println(event);
            }
        }
    }
}