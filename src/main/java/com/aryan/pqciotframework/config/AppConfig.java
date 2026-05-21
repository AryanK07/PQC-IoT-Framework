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
        return properties.getProperty(key);
    }
}