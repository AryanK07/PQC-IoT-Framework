package com.aryan.pqciotframework.config;

import java.io.InputStream;
import java.util.Properties;

public class AppConfig {
    private static final Properties properties = new Properties();

    static {
        try (InputStream input = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                throw new RuntimeException("application.properties not found");
            }
            properties.load(input);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load application.properties", e);
        }
    }

    public static String get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }

        // 1) JVM system property override (e.g., -Ddb.password=...)
        String sysProp = System.getProperty(key);
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp;
        }

        // 2) Environment variable override (e.g., DB_PASSWORD for db.password)
        String envKey = toEnvKey(key);
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) {
            return envVal;
        }

        // 3) application.properties fallback
        return properties.getProperty(key);
    }

    private static String toEnvKey(String key) {
        // db.password -> DB_PASSWORD
        // device.interval.ms -> DEVICE_INTERVAL_MS
        return key.trim()
                .replace('.', '_')
                .replace('-', '_')
                .toUpperCase();
    }
}