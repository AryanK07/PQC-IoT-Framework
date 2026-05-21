package com.aryan.pqciotframework.input;

import com.aryan.pqciotframework.model.SensorData;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class DeviceSimulator {

    private String deviceId;
    private double baseValue;
    private double variance;
    private Random random;

    public DeviceSimulator(String deviceId, double baseValue, double variance) {
        this.deviceId = deviceId;
        this.baseValue = baseValue;
        this.variance = variance;
        this.random = new Random();
    }

    // ===== SINGLE READING =====

    public SensorData generateReading() {
        // Normal distribution around baseValue
        double deviation = random.nextGaussian() * variance;
        double sensorValue = baseValue + deviation;
        long timestamp = System.currentTimeMillis();

        return new SensorData(deviceId, sensorValue, timestamp);
    }

    public SensorData generateReadingWithAnomaly() {
        // Occasionally inject anomaly (10% chance)
        if (random.nextDouble() < 0.1) {
            double anomalyValue = baseValue + (random.nextDouble() * variance * 10);
            long timestamp = System.currentTimeMillis();
            return new SensorData(deviceId, anomalyValue, timestamp);
        }
        return generateReading();
    }

    // ===== BATCH GENERATION =====

    public List<SensorData> generateBatch(int count) {
        List<SensorData> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(generateReading());
        }
        return batch;
    }

    public List<SensorData> generateBatchWithAnomalies(int count) {
        List<SensorData> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(generateReadingWithAnomaly());
        }
        return batch;
    }

    // ===== CONTINUOUS SIMULATION =====

    public void simulateDeviceStream(int duration, int readingsPerSecond) throws InterruptedException {
        System.out.println("Starting device simulation: " + deviceId);
        System.out.println("Duration: " + duration + " seconds, Rate: " + readingsPerSecond + " readings/sec");

        long startTime = System.currentTimeMillis();
        long intervalMs = 1000 / readingsPerSecond;

        while (System.currentTimeMillis() - startTime < duration * 1000) {
            SensorData reading = generateReadingWithAnomaly();
            System.out.println("[" + deviceId + "] " + reading.getSensorValue() + " (ts: " + reading.getTimestamp() + ")");

            Thread.sleep(intervalMs);
        }

        System.out.println("Device simulation ended: " + deviceId);
    }

    // ===== MULTI-DEVICE SIMULATION =====

    public static List<SensorData> simulateMultipleDevices(int deviceCount, int readingsPerDevice) {
        List<SensorData> allData = new ArrayList<>();

        for (int i = 1; i <= deviceCount; i++) {
            String deviceId = "device-" + i;
            double baseValue = 20.0 + (i * 2.0); // Different base values
            double variance = 2.0;

            DeviceSimulator simulator = new DeviceSimulator(deviceId, baseValue, variance);
            List<SensorData> deviceData = simulator.generateBatchWithAnomalies(readingsPerDevice);
            allData.addAll(deviceData);
        }

        return allData;
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Device Simulator Test ===\n");

        // Test 1: Single device single reading
        System.out.println("Test 1: Single Reading");
        DeviceSimulator sim1 = new DeviceSimulator("device-1", 22.0, 2.0);
        SensorData reading = sim1.generateReading();
        System.out.println(reading + "\n");

        // Test 2: Batch generation
        System.out.println("Test 2: Generate 5 readings");
        List<SensorData> batch = sim1.generateBatch(5);
        for (SensorData data : batch) {
            System.out.println(data);
        }
        System.out.println();

        // Test 3: Batch with anomalies
        System.out.println("Test 3: Generate 10 readings with anomalies");
        List<SensorData> batchWithAnomalies = sim1.generateBatchWithAnomalies(10);
        for (SensorData data : batchWithAnomalies) {
            System.out.println(data);
        }
        System.out.println();

        // Test 4: Multi-device simulation
        System.out.println("Test 4: Simulate 3 devices");
        List<SensorData> multiDeviceData = simulateMultipleDevices(3, 5);
        System.out.println("Total readings: " + multiDeviceData.size());
        for (SensorData data : multiDeviceData) {
            System.out.println(data);
        }
    }
}