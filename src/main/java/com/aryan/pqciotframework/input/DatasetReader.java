package com.aryan.pqciotframework.input;

import com.aryan.pqciotframework.model.SensorData;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DatasetReader {

    private String filePath;
    private boolean hasHeader;
    private int valueColumnIndex;
    private int labelColumnIndex;

    public DatasetReader(String filePath, boolean hasHeader, int valueColumnIndex, int labelColumnIndex) {
        this.filePath = filePath;
        this.hasHeader = hasHeader;
        this.valueColumnIndex = valueColumnIndex;
        this.labelColumnIndex = labelColumnIndex;
    }

    // ===== FILE VALIDATION =====

    public boolean fileExists() {
        return new File(filePath).exists();
    }

    // ===== CSV PARSING =====

    public List<SensorData> readCSV() throws Exception {
        List<SensorData> dataList = new ArrayList<>();

        if (!fileExists()) {
            throw new Exception("File not found: " + filePath);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // Skip header
                if (hasHeader && lineNumber == 1) {
                    continue;
                }

                try {
                    SensorData data = parseCSVLine(line, lineNumber);
                    if (data != null) {
                        dataList.add(data);
                    }
                } catch (Exception e) {
                    System.out.println("Warning: Skipping line " + lineNumber + " - " + e.getMessage());
                }
            }
        }

        System.out.println("Read " + dataList.size() + " records from: " + filePath);
        return dataList;
    }

    private SensorData parseCSVLine(String line, int lineNumber) throws Exception {
        String[] parts = line.split(",");

        if (parts.length <= Math.max(valueColumnIndex, labelColumnIndex)) {
            return null;
        }

        try {
            String deviceId = "device-" + labelColumnIndex; // Use label as device identifier
            double sensorValue = Double.parseDouble(parts[valueColumnIndex].trim());
            long timestamp = System.currentTimeMillis() + (lineNumber * 1000); // Simulate time progression

            return new SensorData(deviceId, sensorValue, timestamp);
        } catch (NumberFormatException e) {
            throw new Exception("Invalid numeric format: " + e.getMessage());
        }
    }

    // ===== DATASET-SPECIFIC READERS =====

    // For NSL-KDD (intrusion detection dataset)
    public static List<SensorData> readNSLKDD(String filePath) throws Exception {
        // NSL-KDD format: 41 features, last column is label
        // Map features to sensor readings (e.g., use feature at index 0 or average)
        DatasetReader reader = new DatasetReader(filePath, false, 0, 40);
        return reader.readCSV();
    }

    // For IoT-23 or similar IoT datasets
    public static List<SensorData> readIoTDataset(String filePath, int valueCol, int labelCol) throws Exception {
        DatasetReader reader = new DatasetReader(filePath, true, valueCol, labelCol);
        return reader.readCSV();
    }

    // For generic CSV with header
    public static List<SensorData> readGenericCSV(String filePath, int valueColumn) throws Exception {
        DatasetReader reader = new DatasetReader(filePath, true, valueColumn, -1);
        return reader.readCSV();
    }

    // ===== DATA SAMPLING =====

    public static List<SensorData> sampleData(List<SensorData> data, int sampleSize) {
        if (data.size() <= sampleSize) {
            return new ArrayList<>(data);
        }

        List<SensorData> sampled = new ArrayList<>();
        int step = data.size() / sampleSize;
        for (int i = 0; i < data.size(); i += step) {
            sampled.add(data.get(i));
            if (sampled.size() >= sampleSize) break;
        }

        return sampled;
    }

    // NSL-KDD specific parser
    // Format: 41 features (no header), last columns are label and difficulty
    public static List<SensorData> readNSLKDDFile(String filePath) throws Exception {
        List<SensorData> dataList = new ArrayList<>();

        if (!new File(filePath).exists()) {
            throw new Exception("File not found: " + filePath);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            int lineNumber = 0;
            int skipped = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                try {
                    String[] parts = line.split(",");
                    if (parts.length < 42) {
                        skipped++;
                        continue;
                    }

                    // Use src_bytes (col 4) as sensor value - represents traffic volume
                    double sensorValue = Double.parseDouble(parts[4].trim());

                    // Use label (col 41) to determine device type
                    String label = parts[41].trim().replaceAll("\\.", "");
                    String deviceId = label.equals("normal") ? "normal-device" : "attack-device";

                    long timestamp = System.currentTimeMillis() + (lineNumber * 100L);
                    dataList.add(new SensorData(deviceId, sensorValue, timestamp));

                } catch (Exception e) {
                    skipped++;
                }
            }
            System.out.println("NSL-KDD: Read " + dataList.size() + " records, skipped " + skipped);
        }

        return dataList;
    }

    public static List<SensorData> readUNSWNB15File(String filePath) throws Exception {
        List<SensorData> dataList = new ArrayList<>();

        if (!new File(filePath).exists()) {
            throw new Exception("File not found: " + filePath);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.trim().isEmpty()) {
                throw new Exception("UNSW-NB15 file is empty: " + filePath);
            }

            String[] headers = headerLine.split(",");
            Map<String, Integer> columnMap = new HashMap<>();
            for (int index = 0; index < headers.length; index++) {
                columnMap.put(headers[index].trim().toLowerCase(), index);
            }

            Integer sbytesIndex = columnMap.get("sbytes");
            Integer attackCategoryIndex = columnMap.get("attack_cat");
            Integer labelIndex = columnMap.get("label");

            if (sbytesIndex == null) {
                throw new Exception("UNSW-NB15 missing required column: sbytes");
            }

            int lineNumber = 1;
            int skipped = 0;
            String line;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                try {
                    String[] parts = line.split(",");
                    if (parts.length <= sbytesIndex) {
                        skipped++;
                        continue;
                    }

                    double sensorValue = Double.parseDouble(parts[sbytesIndex].trim());

                    String deviceId;
                    if (attackCategoryIndex != null && attackCategoryIndex < parts.length) {
                        String attackCategory = parts[attackCategoryIndex].trim();
                        if (attackCategory.isEmpty() || "-".equals(attackCategory)) {
                            deviceId = "normal-device";
                        } else {
                            deviceId = attackCategory.toLowerCase() + "-device";
                        }
                    } else if (labelIndex != null && labelIndex < parts.length) {
                        String labelValue = parts[labelIndex].trim();
                        deviceId = "1".equals(labelValue) ? "attack-device" : "normal-device";
                    } else {
                        deviceId = "unsw-device";
                    }

                    long timestamp = System.currentTimeMillis() + (lineNumber * 100L);
                    dataList.add(new SensorData(deviceId, sensorValue, timestamp));
                } catch (Exception ex) {
                    skipped++;
                }
            }

            System.out.println("UNSW-NB15: Read " + dataList.size() + " records, skipped " + skipped);
        }

        return dataList;
    }

    public static void main(String[] args) {
        System.out.println("=== Dataset Reader Test ===\n");

        // Test with a sample CSV file (you need to provide a real file)
        String testFile = "src/main/resources/sample_data.csv";

        try {
            // Check if test file exists, if not create a demo one
            File file = new File(testFile);
            if (!file.exists()) {
                System.out.println("Test file not found: " + testFile);
                System.out.println("To test with real data:");
                System.out.println("1. Download a dataset (e.g., NSL-KDD, IoT-23)");
                System.out.println("2. Place it in src/main/resources/");
                System.out.println("3. Update the file path and column indices");
                System.out.println("4. Rerun this test\n");

                // Demo with synthetic data instead
                System.out.println("Running demo with synthetic data...");
                List<SensorData> demoData = DeviceSimulator.simulateMultipleDevices(2, 5);
                System.out.println("Generated " + demoData.size() + " demo readings");
                for (SensorData data : demoData) {
                    System.out.println(data);
                }
            } else {
                // Read actual dataset
                DatasetReader reader = new DatasetReader(testFile, true, 0, 1);
                List<SensorData> data = reader.readCSV();

                // Sample to show results
                List<SensorData> sampled = sampleData(data, 10);
                System.out.println("\nFirst 10 records:");
                for (SensorData d : sampled) {
                    System.out.println(d);
                }
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }
}