package com.aryan.pqciotframework.config;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public final class RunContext {

    private static final DateTimeFormatter RUN_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static volatile String currentRunId = "legacy";

    private RunContext() {
    }

    public static synchronized String resolveOrCreate(String prefix) {
        String envRunId = System.getenv("PQC_RUN_ID");
        if (envRunId != null && !envRunId.isBlank()) {
            currentRunId = envRunId.trim();
            return currentRunId;
        }
        if (currentRunId == null || currentRunId.isBlank() || "legacy".equals(currentRunId)) {
            currentRunId = generateRunId(prefix);
        }
        return currentRunId;
    }

    public static synchronized void setCurrentRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        currentRunId = runId.trim();
    }

    public static String getCurrentRunId() {
        return currentRunId == null || currentRunId.isBlank() ? "legacy" : currentRunId;
    }

    public static String generateRunId(String prefix) {
        String normalizedPrefix = (prefix == null || prefix.isBlank()) ? "run" : prefix.trim().toLowerCase();
        String timePart = LocalDateTime.now().format(RUN_TIME_FORMAT);
        String randomPart = UUID.randomUUID().toString().substring(0, 8);
        return normalizedPrefix + "-" + timePart + "-" + randomPart;
    }
}
