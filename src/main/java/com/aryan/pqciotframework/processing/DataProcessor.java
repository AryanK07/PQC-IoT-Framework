package com.aryan.pqciotframework.processing;

import com.aryan.pqciotframework.model.SensorData;

import java.util.ArrayList;
import java.util.List;

public class DataProcessor {

    // ===== DATA CLEANING =====

    public List<SensorData> cleanData(List<SensorData> rawData) {
        List<SensorData> cleaned = new ArrayList<>();
        for (SensorData data : rawData) {
            // Remove null or invalid entries
            if (data != null && data.getDeviceId() != null && !data.getDeviceId().isEmpty()) {
                cleaned.add(data);
            }
        }
        System.out.println("Cleaned " + cleaned.size() + " records from " + rawData.size());
        return cleaned;
    }

    // ===== OUTLIER DETECTION =====

    public SensorData removeOutliers(SensorData data, double lowerBound, double upperBound) {
        if (data.getSensorValue() < lowerBound || data.getSensorValue() > upperBound) {
            return null; // Mark as outlier
        }
        return data;
    }

    // ===== NORMALIZATION =====

    public double normalize(double value, double min, double max) {
        if (max == min) return 0.0;
        return (value - min) / (max - min);
    }

    public List<SensorData> normalizeData(List<SensorData> data) {
        if (data.isEmpty()) return data;

        // Find min and max
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (SensorData d : data) {
            if (d.getSensorValue() < min) min = d.getSensorValue();
            if (d.getSensorValue() > max) max = d.getSensorValue();
        }

        // Normalize
        List<SensorData> normalized = new ArrayList<>();
        for (SensorData d : data) {
            double normalizedValue = normalize(d.getSensorValue(), min, max);
            d.setSensorValue(normalizedValue);
            normalized.add(d);
        }

        System.out.println("Normalized " + normalized.size() + " records (min=" + min + ", max=" + max + ")");
        return normalized;
    }

    // ===== FEATURE EXTRACTION =====

    public double extractRollingAverage(List<SensorData> data, int windowSize) {
        if (data.size() < windowSize) return 0.0;
        double sum = 0;
        for (int i = data.size() - windowSize; i < data.size(); i++) {
            sum += data.get(i).getSensorValue();
        }
        return sum / windowSize;
    }

    public double extractStandardDeviation(List<SensorData> data) {
        if (data.isEmpty()) return 0.0;

        double mean = data.stream().mapToDouble(SensorData::getSensorValue).average().orElse(0.0);
        double variance = data.stream()
                .mapToDouble(d -> Math.pow(d.getSensorValue() - mean, 2))
                .average()
                .orElse(0.0);
        return Math.sqrt(variance);
    }

    // ===== PIPELINE =====

    public List<SensorData> processDataPipeline(List<SensorData> rawData) {
        System.out.println("\n=== Processing Pipeline Started ===");

        // Step 1: Clean
        List<SensorData> cleaned = cleanData(rawData);

        // Step 2: Normalize
        List<SensorData> normalized = normalizeData(cleaned);

        // Step 3: Calculate features
        if (!normalized.isEmpty()) {
            double rollingAvg = extractRollingAverage(normalized, 5);
            double stdDev = extractStandardDeviation(normalized);
            System.out.println("Rolling Average (window=5): " + String.format("%.4f", rollingAvg));
            System.out.println("Standard Deviation: " + String.format("%.4f", stdDev));
        }

        System.out.println("=== Processing Complete ===\n");
        return normalized;
    }

    public static void main(String[] args) {
        DataProcessor processor = new DataProcessor();

        // Create test data
        List<SensorData> testData = new ArrayList<>();
        testData.add(new SensorData("device-1", 22.5, System.currentTimeMillis()));
        testData.add(new SensorData("device-1", 23.1, System.currentTimeMillis() + 1000));
        testData.add(new SensorData("device-1", 21.8, System.currentTimeMillis() + 2000));
        testData.add(new SensorData("device-1", 24.2, System.currentTimeMillis() + 3000));
        testData.add(new SensorData("device-1", 22.9, System.currentTimeMillis() + 4000));

        // Process
        List<SensorData> processed = processor.processDataPipeline(testData);

        // Show results
        System.out.println("Processed records:");
        for (SensorData data : processed) {
            System.out.println(data);
        }
    }
}