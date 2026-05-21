package com.aryan.pqciotframework.config;

public class DeviceConfig {
    public enum Mode { SIMULATED, DATASET }

    public static final Mode CURRENT_MODE = Mode.DATASET;
    public static final String DATASET_PATH = "src/main/resources/UNSW-NB15/UNSW_NB15_training-set.csv";

    public static final int SIMULATED_DEVICE_COUNT = 50;   // 10,20,30,40,50 for experiments
    public static final int READINGS_PER_DEVICE = 20;
    public static final int DATASET_SAMPLE_SIZE = 500;
}