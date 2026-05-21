package com.aryan.pqciotframework;

import com.aryan.pqciotframework.database.DatabaseManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class DataAnalysisAndReporting extends Application {

    private static final int PADDING = 16;
    private static final int SPACING = 12;
    private static final int AUTO_REFRESH_SECONDS = 10;
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Label statusLabel;
    private Label refreshInfoLabel;

    private LineChart<String, Number> sensorChart;
    private Label sensorSummaryLabel;
    private Label sensorStateLabel;

    private FlowPane anomalyPane;
    private Label anomalySummaryLabel;
    private Label anomalyStateLabel;

    private BarChart<String, Number> performanceChart;
    private Label performanceSummaryLabel;
    private Label performanceStateLabel;
    private Label metricAvgLabel;
    private Label metricCpuLabel;
    private Label metricMemoryLabel;
    private Label metricLatestLabel;

    private LineChart<Number, Number> latencyChart;
    private LineChart<Number, Number> cpuChart;
    private Label scalabilitySummaryLabel;
    private Label scalabilityStateLabel;

    private LineChart<String, Number> rotationChart;
    private Label rotationSummaryLabel;
    private Label rotationStateLabel;

    private TableView<LogEntry> logsTable;
    private Label logSummaryLabel;
    private Label logStateLabel;

    private Timeline refreshTimeline;
    private String activeRunId = "legacy";

    @Override
    public void start(Stage primaryStage) {
        try {
            BorderPane root = new BorderPane();
            root.setPadding(new Insets(PADDING));
            root.setTop(createHeader());
            root.setCenter(createTabs());

            Scene scene = new Scene(root, 1280, 820);
            primaryStage.setTitle("PQC-IoT Framework - Live Analytics Dashboard");
            primaryStage.setScene(scene);

            Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
            primaryStage.setWidth(screenBounds.getWidth());
            primaryStage.setHeight(screenBounds.getHeight());
            primaryStage.setX(screenBounds.getMinX());
            primaryStage.setY(screenBounds.getMinY());
            primaryStage.setMaximized(true);
            primaryStage.setMinWidth(1024);
            primaryStage.setMinHeight(720);
            primaryStage.setOnCloseRequest(event -> stopRefreshLoop());
            primaryStage.show();

            refreshDashboard();
            startRefreshLoop();
        } catch (Exception exception) {
            exception.printStackTrace();
            showError("Initialization Error", "Failed to start dashboard: " + exception.getMessage());
        }
    }

    private VBox createHeader() {
        Label titleLabel = new Label("PQC-IoT Framework - Live Analytics Dashboard");
        titleLabel.setStyle("-fx-font-size: 25; -fx-font-weight: bold;");

        statusLabel = new Label("Database status: checking...");
        statusLabel.setStyle("-fx-font-size: 12; -fx-text-fill: #1f7a1f; -fx-font-weight: bold;");

        refreshInfoLabel = new Label("Auto refresh every " + AUTO_REFRESH_SECONDS + "s");
        refreshInfoLabel.setStyle("-fx-font-size: 12; -fx-text-fill: #666666;");

        Button refreshButton = new Button("Refresh Now");
        refreshButton.setStyle("-fx-background-color: #1f6feb; -fx-text-fill: white; -fx-font-weight: bold;");
        refreshButton.setOnAction(event -> refreshDashboard());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox metaRow = new HBox(10, statusLabel, new Separator(), refreshInfoLabel, spacer, refreshButton);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(8, titleLabel, metaRow);
        header.setPadding(new Insets(6, 6, 14, 6));
        header.setStyle("-fx-border-color: #d8d8d8; -fx-border-width: 0 0 1 0;");
        return header;
    }

    private TabPane createTabs() {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getTabs().addAll(
                createSensorViewTab(),
                createAnomalyPanelTab(),
                createPerformanceTab(),
                createScalabilityTab(),
                createKeyRotationTab(),
                createSecurityLogsTab()
        );
        return tabPane;
    }

    private Tab createSensorViewTab() {
        sensorSummaryLabel = createSectionSummaryLabel();
        sensorStateLabel = createStateLabel();

        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel("Reading sequence");
        yAxis.setLabel("Sensor value");

        sensorChart = new LineChart<>(xAxis, yAxis);
        sensorChart.setAnimated(false);
        sensorChart.setCreateSymbols(false);
        sensorChart.setTitle("Recent Sensor Readings by Device");
        sensorChart.setMinHeight(520);

        StackPane chartPane = new StackPane(sensorChart, sensorStateLabel);
        VBox.setVgrow(chartPane, Priority.ALWAYS);

        return new Tab("Sensor View", createTabContainer(
                createSectionTitle("Real-Time Sensor Readings", sensorSummaryLabel),
                chartPane
        ));
    }

    private Tab createAnomalyPanelTab() {
        anomalySummaryLabel = createSectionSummaryLabel();
        anomalyStateLabel = createStateLabel();

        Label anomalyLegendLabel = new Label("Score legend (0-100): 0-40 Low  |  40-70 Moderate  |  70-100 High/Severe");
        anomalyLegendLabel.setWrapText(true);
        anomalyLegendLabel.setStyle("-fx-text-fill: #555555; -fx-font-size: 12px;");

        anomalyPane = new FlowPane();
        anomalyPane.setHgap(SPACING);
        anomalyPane.setVgap(SPACING);
        anomalyPane.setPadding(new Insets(4));
        anomalyPane.prefWrapLengthProperty().set(1040);

        ScrollPane scrollPane = new ScrollPane(anomalyPane);
        scrollPane.setFitToWidth(true);
        scrollPane.setPannable(true);

        StackPane body = new StackPane(scrollPane, anomalyStateLabel);
        VBox.setVgrow(body, Priority.ALWAYS);

        return new Tab("Anomaly Panel", createTabContainer(
                createSectionTitle("Detected Anomalies", anomalySummaryLabel),
            anomalyLegendLabel,
                body
        ));
    }

    private Tab createPerformanceTab() {
        performanceSummaryLabel = createSectionSummaryLabel();
        performanceStateLabel = createStateLabel();

        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel("Recorded operation");
        yAxis.setLabel("Average time (ms)");

        performanceChart = new BarChart<>(xAxis, yAxis);
        performanceChart.setAnimated(false);
        performanceChart.setLegendVisible(false);
        performanceChart.setTitle("Measured Performance Metrics");
        performanceChart.setMinHeight(420);

        metricAvgLabel = createMetricValueLabel();
        metricCpuLabel = createMetricValueLabel();
        metricMemoryLabel = createMetricValueLabel();
        metricLatestLabel = createMetricValueLabel();

        HBox metricCards = new HBox(
                SPACING,
                createMetricCard("Avg Execution", metricAvgLabel, "#1f7a1f"),
                createMetricCard("Avg CPU", metricCpuLabel, "#0b7285"),
                createMetricCard("Avg Memory", metricMemoryLabel, "#b26b00"),
                createMetricCard("Latest Sample", metricLatestLabel, "#7b2cbf")
        );

        Label noteLabel = new Label("Current database stores measured pipeline operations only. No classical baseline is stored yet, so this tab now shows real recorded timings instead of placeholder comparisons.");
        noteLabel.setWrapText(true);
        noteLabel.setStyle("-fx-text-fill: #555555;");

        StackPane chartPane = new StackPane(performanceChart, performanceStateLabel);
        VBox.setVgrow(chartPane, Priority.ALWAYS);

        return new Tab("PQC vs Classical", createTabContainer(
                createSectionTitle("PQC vs Classical", performanceSummaryLabel),
                noteLabel,
                chartPane,
                metricCards
        ));
    }

    private Tab createScalabilityTab() {
        scalabilitySummaryLabel = createSectionSummaryLabel();
        scalabilityStateLabel = createStateLabel();

        NumberAxis latencyXAxis = new NumberAxis();
        NumberAxis latencyYAxis = new NumberAxis();
        latencyXAxis.setLabel("Device count");
        latencyYAxis.setLabel("Avg latency (ms)");
        latencyChart = new LineChart<>(latencyXAxis, latencyYAxis);
        latencyChart.setAnimated(false);
        latencyChart.setCreateSymbols(true);
        latencyChart.setTitle("Latency vs Device Count");

        NumberAxis cpuXAxis = new NumberAxis();
        NumberAxis cpuYAxis = new NumberAxis();
        cpuXAxis.setLabel("Device count");
        cpuYAxis.setLabel("CPU usage (%)");
        cpuChart = new LineChart<>(cpuXAxis, cpuYAxis);
        cpuChart.setAnimated(false);
        cpuChart.setCreateSymbols(true);
        cpuChart.setTitle("CPU vs Device Count");

        HBox charts = new HBox(SPACING, latencyChart, cpuChart);
        HBox.setHgrow(latencyChart, Priority.ALWAYS);
        HBox.setHgrow(cpuChart, Priority.ALWAYS);
        latencyChart.setMinWidth(420);
        cpuChart.setMinWidth(420);

        StackPane body = new StackPane(charts, scalabilityStateLabel);
        VBox.setVgrow(body, Priority.ALWAYS);

        return new Tab("Scalability Charts", createTabContainer(
                createSectionTitle("System Scalability Analysis", scalabilitySummaryLabel),
                body
        ));
    }

    private Tab createKeyRotationTab() {
        rotationSummaryLabel = createSectionSummaryLabel();
        rotationStateLabel = createStateLabel();

        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel("Timestamp");
        yAxis.setLabel("Execution time (ms)");

        rotationChart = new LineChart<>(xAxis, yAxis);
        rotationChart.setAnimated(false);
        rotationChart.setCreateSymbols(true);
        rotationChart.setTitle("Performance Timeline with Key Rotation Overlay");

        Label noteLabel = new Label("This chart overlays performance metric samples with recorded key rotation events. If rotation logs are empty, the tab shows the performance timeline and an explicit no-data notice.");
        noteLabel.setWrapText(true);
        noteLabel.setStyle("-fx-text-fill: #555555;");

        StackPane body = new StackPane(rotationChart, rotationStateLabel);
        VBox.setVgrow(body, Priority.ALWAYS);

        return new Tab("Key Rotation Timeline", createTabContainer(
                createSectionTitle("Key Rotation Timeline", rotationSummaryLabel),
                noteLabel,
                body
        ));
    }

    private Tab createSecurityLogsTab() {
        logSummaryLabel = createSectionSummaryLabel();
        logStateLabel = createStateLabel();

        logsTable = new TableView<>();
        logsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<LogEntry, String> timestampColumn = new TableColumn<>("Timestamp");
        timestampColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().timestamp));
        timestampColumn.setMinWidth(170);

        TableColumn<LogEntry, String> eventColumn = new TableColumn<>("Event Data");
        eventColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().entry));
        eventColumn.setMinWidth(420);

        TableColumn<LogEntry, String> hashColumn = new TableColumn<>("Hash");
        hashColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().hash));
        hashColumn.setMinWidth(320);

        logsTable.getColumns().addAll(timestampColumn, eventColumn, hashColumn);

        StackPane body = new StackPane(logsTable, logStateLabel);
        VBox.setVgrow(body, Priority.ALWAYS);

        return new Tab("Security Logs", createTabContainer(
                createSectionTitle("Immutable Security Logs", logSummaryLabel),
                body
        ));
    }

    private VBox createTabContainer(Node... nodes) {
        VBox box = new VBox(SPACING);
        box.setPadding(new Insets(PADDING));
        box.getChildren().addAll(nodes);
        return box;
    }

    private VBox createSectionTitle(String title, Label summaryLabel) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 18; -fx-font-weight: bold;");
        return new VBox(4, titleLabel, summaryLabel);
    }

    private Label createSectionSummaryLabel() {
        Label label = new Label("Loading...");
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #666666;");
        return label;
    }

    private Label createStateLabel() {
        Label label = new Label();
        label.setVisible(false);
        label.setManaged(false);
        label.setWrapText(true);
        label.setMaxWidth(520);
        label.setStyle("-fx-background-color: rgba(255,255,255,0.93); -fx-border-color: #d0d0d0; -fx-padding: 16; -fx-text-fill: #444444;");
        StackPane.setAlignment(label, Pos.CENTER);
        return label;
    }

    private VBox createMetricCard(String title, Label valueLabel, String accentColor) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #666666;");
        VBox card = new VBox(6, titleLabel, valueLabel);
        card.setPadding(new Insets(12));
        card.setPrefWidth(220);
        card.setStyle("-fx-background-color: #f8f9fb; -fx-border-color: " + accentColor + "; -fx-border-radius: 6; -fx-background-radius: 6;");
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private Label createMetricValueLabel() {
        Label label = new Label("--");
        label.setWrapText(true);
        label.setStyle("-fx-font-size: 17; -fx-font-weight: bold;");
        return label;
    }

    private void startRefreshLoop() {
        stopRefreshLoop();
        refreshTimeline = new Timeline(new KeyFrame(Duration.seconds(AUTO_REFRESH_SECONDS), event -> refreshDashboard()));
        refreshTimeline.setCycleCount(Timeline.INDEFINITE);
        refreshTimeline.play();
    }

    private void stopRefreshLoop() {
        if (refreshTimeline != null) {
            refreshTimeline.stop();
        }
    }

    private void refreshDashboard() {
        refreshInfoLabel.setText("Refreshing...");
        boolean connected = DatabaseManager.testConnection();
        activeRunId = DatabaseManager.resolveLatestRunId();
        statusLabel.setText((connected ? "Database connected" : "Database disconnected")
            + " | Run scope: " + activeRunId
            + " | Last updated: " + nowClock());

        loadSensorData(activeRunId);
        loadAnomalies(activeRunId);
        loadPerformanceMetrics(activeRunId);
        loadScalabilityData(activeRunId);
        loadRotationTimeline(activeRunId);
        loadSecurityLogs(activeRunId);

        refreshInfoLabel.setText("Auto refresh every " + AUTO_REFRESH_SECONDS + "s | Last refresh: " + nowClock());
    }

    private void loadSensorData(String runId) {
        runAsync(() -> {
            Map<String, List<SensorPoint>> pointsByDevice = new LinkedHashMap<>();
            int totalPoints = 0;

            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement deviceStatement = connection.prepareStatement("SELECT DISTINCT device_id FROM sensor_data WHERE run_id = ? ORDER BY device_id LIMIT 4")) {

                deviceStatement.setString(1, runId);
                try (ResultSet deviceResult = deviceStatement.executeQuery()) {

                    List<String> deviceIds = new ArrayList<>();
                    while (deviceResult.next()) {
                        deviceIds.add(deviceResult.getString("device_id"));
                    }

                    String query = "SELECT timestamp, sensor_value FROM sensor_data WHERE run_id = ? AND device_id = ? ORDER BY timestamp DESC LIMIT 15";
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        for (String deviceId : deviceIds) {
                            statement.setString(1, runId);
                            statement.setString(2, deviceId);
                            try (ResultSet resultSet = statement.executeQuery()) {
                                List<SensorPoint> points = new ArrayList<>();
                                while (resultSet.next()) {
                                    points.add(new SensorPoint(resultSet.getLong("timestamp"), resultSet.getDouble("sensor_value")));
                                }
                                Collections.reverse(points);
                                if (!points.isEmpty()) {
                                    totalPoints += points.size();
                                    pointsByDevice.put(deviceId, points);
                                }
                            }
                        }
                    }
                }
            }

            int deviceCount = pointsByDevice.size();
            int sampleCount = totalPoints;
            Platform.runLater(() -> {
                sensorChart.getData().clear();
                if (pointsByDevice.isEmpty()) {
                    sensorSummaryLabel.setText("No sensor samples available.");
                    setState(sensorStateLabel, "No sensor data is available yet.", true);
                    return;
                }

                setState(sensorStateLabel, "", false);
                for (Map.Entry<String, List<SensorPoint>> entry : pointsByDevice.entrySet()) {
                    XYChart.Series<String, Number> series = new XYChart.Series<>();
                    series.setName(entry.getKey());
                    int index = 1;
                    for (SensorPoint point : entry.getValue()) {
                        series.getData().add(new XYChart.Data<>("R" + index++, point.value()));
                    }
                    sensorChart.getData().add(series);
                }

                sensorSummaryLabel.setText(deviceCount + " devices, " + sampleCount + " recent readings loaded.");
                installSensorTooltips(pointsByDevice);
            });
        }, error -> Platform.runLater(() -> {
            sensorChart.getData().clear();
            sensorSummaryLabel.setText("Failed to load sensor data.");
            setState(sensorStateLabel, error.getMessage(), true);
        }));
    }

    private void installSensorTooltips(Map<String, List<SensorPoint>> pointsByDevice) {
        int seriesIndex = 0;
        for (Map.Entry<String, List<SensorPoint>> entry : pointsByDevice.entrySet()) {
            XYChart.Series<String, Number> series = sensorChart.getData().get(seriesIndex++);
            List<SensorPoint> points = entry.getValue();
            for (int i = 0; i < series.getData().size() && i < points.size(); i++) {
                XYChart.Data<String, Number> data = series.getData().get(i);
                SensorPoint point = points.get(i);
                if (data.getNode() != null) {
                    Tooltip.install(data.getNode(), new Tooltip(entry.getKey() + "\n" + formatDateTime(point.timestamp()) + "\nValue: " + formatDouble(point.value())));
                }
            }
        }
    }

    private void loadAnomalies(String runId) {
        runAsync(() -> {
            List<AnomalyCardData> anomalies = new ArrayList<>();
            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT device_id, detection_method, anomaly_score, sensor_value, timestamp FROM anomaly_events WHERE run_id = ? AND is_anomaly = true ORDER BY timestamp DESC LIMIT 24")) {

                statement.setString(1, runId);
                try (ResultSet resultSet = statement.executeQuery()) {

                    while (resultSet.next()) {
                        anomalies.add(new AnomalyCardData(
                                resultSet.getString("device_id"),
                                resultSet.getString("detection_method"),
                                resultSet.getDouble("anomaly_score"),
                                resultSet.getDouble("sensor_value"),
                                resultSet.getLong("timestamp")
                        ));
                    }
                }
            }

            Platform.runLater(() -> {
                anomalyPane.getChildren().clear();
                if (anomalies.isEmpty()) {
                    anomalySummaryLabel.setText("No anomaly records are available.");
                    setState(anomalyStateLabel, "No anomaly events have been recorded yet.", true);
                    return;
                }

                setState(anomalyStateLabel, "", false);
                for (AnomalyCardData anomaly : anomalies) {
                    anomalyPane.getChildren().add(createAnomalyCard(anomaly));
                }
                anomalySummaryLabel.setText(anomalies.size() + " latest anomaly events shown.");
            });
        }, error -> Platform.runLater(() -> {
            anomalyPane.getChildren().clear();
            anomalySummaryLabel.setText("Failed to load anomaly events.");
            setState(anomalyStateLabel, error.getMessage(), true);
        }));
    }

    private VBox createAnomalyCard(AnomalyCardData anomaly) {
        Label deviceLabel = new Label(anomaly.deviceId());
        deviceLabel.setStyle("-fx-font-size: 14; -fx-font-weight: bold;");

        Label methodLabel = new Label("Method: " + safeText(anomaly.method()));
        methodLabel.setWrapText(true);

        Label scoreLabel = new Label("Score: " + formatDouble(anomaly.score()));
        scoreLabel.setStyle(colorForScore(anomaly.score()));

        Label sensorLabel = new Label("Sensor value: " + formatDouble(anomaly.sensorValue()));
        Label timeLabel = new Label("Time: " + formatDateTime(anomaly.timestamp()));
        timeLabel.setStyle("-fx-text-fill: #666666;");

        VBox card = new VBox(6, deviceLabel, methodLabel, scoreLabel, sensorLabel, timeLabel);
        card.setPadding(new Insets(12));
        card.setPrefWidth(280);
        card.setMaxWidth(320);
        card.setStyle("-fx-background-color: #fbfbfd; -fx-border-color: #d6d9de; -fx-background-radius: 8; -fx-border-radius: 8;");
        return card;
    }

    private void loadPerformanceMetrics(String runId) {
        runAsync(() -> {
            List<PerformanceRow> rows = new ArrayList<>();
            long latestTimestamp = 0;
            double totalTime = 0;
            double totalCpu = 0;
            double totalMemory = 0;
            int cpuCount = 0;
            int memoryCount = 0;

            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT operation, execution_time_ms, cpu_usage_percent, memory_usage_mb, timestamp FROM performance_metrics WHERE run_id = ? AND operation NOT LIKE 'Dashboard_Benchmark_%' ORDER BY timestamp ASC")) {

                statement.setString(1, runId);
                try (ResultSet resultSet = statement.executeQuery()) {

                    while (resultSet.next()) {
                        PerformanceRow row = new PerformanceRow(
                                resultSet.getString("operation"),
                                resultSet.getDouble("execution_time_ms"),
                                resultSet.getDouble("cpu_usage_percent"),
                                resultSet.getDouble("memory_usage_mb"),
                                resultSet.getLong("timestamp")
                        );
                        rows.add(row);
                        totalTime += row.executionMs();
                        if (row.cpuPercent() > 0) {
                            totalCpu += row.cpuPercent();
                            cpuCount++;
                        }
                        if (row.memoryMb() > 0) {
                            totalMemory += row.memoryMb();
                            memoryCount++;
                        }
                        latestTimestamp = Math.max(latestTimestamp, row.timestamp());
                    }
                }
            }

            Map<String, double[]> timeByOperation = new LinkedHashMap<>();
            for (PerformanceRow row : rows) {
                double[] aggregate = timeByOperation.computeIfAbsent(row.operation(), ignored -> new double[] {0.0, 0.0});
                aggregate[0] += row.executionMs();
                aggregate[1] += 1.0;
            }

            double averageTime = rows.isEmpty() ? 0 : totalTime / rows.size();
            double averageCpu = cpuCount == 0 ? 0 : totalCpu / cpuCount;
            double averageMemory = memoryCount == 0 ? 0 : totalMemory / memoryCount;
            int finalCpuCount = cpuCount;
            int finalMemoryCount = memoryCount;
            long finalLatestTimestamp = latestTimestamp;

            Platform.runLater(() -> {
                performanceChart.getData().clear();
                if (rows.isEmpty()) {
                    performanceSummaryLabel.setText("No performance metrics are available.");
                    metricAvgLabel.setText("--");
                    metricCpuLabel.setText("--");
                    metricMemoryLabel.setText("--");
                    metricLatestLabel.setText("--");
                    setState(performanceStateLabel, "No performance samples have been recorded yet.", true);
                    return;
                }

                setState(performanceStateLabel, "", false);
                XYChart.Series<String, Number> series = new XYChart.Series<>();
                for (Map.Entry<String, double[]> entry : timeByOperation.entrySet()) {
                    double[] aggregate = entry.getValue();
                    double count = Math.max(1.0, aggregate[1]);
                    double avgMs = aggregate[0] / count;
                    series.getData().add(new XYChart.Data<>(entry.getKey(), avgMs));
                }
                performanceChart.getData().add(series);

                performanceSummaryLabel.setText(rows.size() + " samples across " + timeByOperation.size() + " recorded operations.");
                metricAvgLabel.setText(formatDouble(averageTime) + " ms");
                metricCpuLabel.setText(finalCpuCount == 0 ? "N/A" : formatDouble(averageCpu) + " %");
                metricMemoryLabel.setText(finalMemoryCount == 0 ? "N/A" : formatDouble(averageMemory) + " MB");
                metricLatestLabel.setText(finalLatestTimestamp == 0 ? "N/A" : formatDateTime(finalLatestTimestamp));
            });
        }, error -> Platform.runLater(() -> {
            performanceChart.getData().clear();
            performanceSummaryLabel.setText("Failed to load performance metrics.");
            metricAvgLabel.setText("--");
            metricCpuLabel.setText("--");
            metricMemoryLabel.setText("--");
            metricLatestLabel.setText("--");
            setState(performanceStateLabel, error.getMessage(), true);
        }));
    }

    private void loadScalabilityData(String runId) {
        runAsync(() -> {
            List<ScalabilityPoint> points = new ArrayList<>();
            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT device_count, avg_latency_ms, cpu_usage_percent FROM scalability_results WHERE run_id = ? ORDER BY device_count ASC, timestamp ASC")) {

                statement.setString(1, runId);
                try (ResultSet resultSet = statement.executeQuery()) {

                    while (resultSet.next()) {
                        points.add(new ScalabilityPoint(
                                resultSet.getInt("device_count"),
                                resultSet.getDouble("avg_latency_ms"),
                                resultSet.getDouble("cpu_usage_percent")
                        ));
                    }
                }
            }

            Platform.runLater(() -> {
                latencyChart.getData().clear();
                cpuChart.getData().clear();
                if (points.isEmpty()) {
                    scalabilitySummaryLabel.setText("No scalability runs are available.");
                    setState(scalabilityStateLabel, "Run the scalability workflow to populate latency and CPU charts.", true);
                    return;
                }

                setState(scalabilityStateLabel, "", false);
                XYChart.Series<Number, Number> latencySeries = new XYChart.Series<>();
                latencySeries.setName("Avg latency");
                XYChart.Series<Number, Number> cpuSeries = new XYChart.Series<>();
                cpuSeries.setName("CPU usage");

                for (ScalabilityPoint point : points) {
                    latencySeries.getData().add(new XYChart.Data<>(point.deviceCount(), point.avgLatency()));
                    cpuSeries.getData().add(new XYChart.Data<>(point.deviceCount(), point.cpuPercent()));
                }

                latencyChart.getData().add(latencySeries);
                cpuChart.getData().add(cpuSeries);
                scalabilitySummaryLabel.setText(points.size() + " scalability samples loaded.");
            });
        }, error -> Platform.runLater(() -> {
            latencyChart.getData().clear();
            cpuChart.getData().clear();
            scalabilitySummaryLabel.setText("Failed to load scalability results.");
            setState(scalabilityStateLabel, error.getMessage(), true);
        }));
    }

    private void loadRotationTimeline(String runId) {
        runAsync(() -> {
            List<PerformanceRow> metrics = new ArrayList<>();
            List<Long> rotations = new ArrayList<>();

            try (Connection connection = DatabaseManager.getConnection()) {
                try (PreparedStatement perfStatement = connection.prepareStatement("SELECT operation, execution_time_ms, cpu_usage_percent, memory_usage_mb, timestamp FROM performance_metrics WHERE run_id = ? AND operation NOT LIKE 'Dashboard_Benchmark_%' ORDER BY timestamp ASC LIMIT 20")) {
                    perfStatement.setString(1, runId);
                    try (ResultSet perfResult = perfStatement.executeQuery()) {
                        while (perfResult.next()) {
                            metrics.add(new PerformanceRow(
                                    perfResult.getString("operation"),
                                    perfResult.getDouble("execution_time_ms"),
                                    perfResult.getDouble("cpu_usage_percent"),
                                    perfResult.getDouble("memory_usage_mb"),
                                    perfResult.getLong("timestamp")
                            ));
                        }
                    }
                }

                try (PreparedStatement rotationStatement = connection.prepareStatement("SELECT timestamp FROM key_rotation_logs WHERE run_id = ? ORDER BY timestamp ASC LIMIT 20")) {
                    rotationStatement.setString(1, runId);
                    try (ResultSet rotationResult = rotationStatement.executeQuery()) {
                        while (rotationResult.next()) {
                            rotations.add(rotationResult.getLong("timestamp"));
                        }
                    }
                }
            }

            Platform.runLater(() -> {
                rotationChart.getData().clear();
                if (metrics.isEmpty()) {
                    rotationSummaryLabel.setText("No performance timeline data is available.");
                    setState(rotationStateLabel, "No performance metrics are available to build the timeline.", true);
                    return;
                }

                XYChart.Series<String, Number> perfSeries = new XYChart.Series<>();
                perfSeries.setName("Execution time");
                for (PerformanceRow metric : metrics) {
                    perfSeries.getData().add(new XYChart.Data<>(formatClock(metric.timestamp()), metric.executionMs()));
                }
                rotationChart.getData().add(perfSeries);

                if (!rotations.isEmpty()) {
                    XYChart.Series<String, Number> rotationSeries = new XYChart.Series<>();
                    rotationSeries.setName("Key rotation");
                    double markerHeight = metrics.stream().mapToDouble(PerformanceRow::executionMs).max().orElse(1) * 1.05;
                    for (Long rotation : rotations) {
                        rotationSeries.getData().add(new XYChart.Data<>(formatClock(rotation), markerHeight));
                    }
                    rotationChart.getData().add(rotationSeries);
                    rotationSummaryLabel.setText(metrics.size() + " performance samples with " + rotations.size() + " key rotation events.");
                    setState(rotationStateLabel, "", false);
                } else {
                    rotationSummaryLabel.setText(metrics.size() + " performance samples loaded. No key rotation events have been recorded yet.");
                    setState(rotationStateLabel, "No key rotation entries are present in key_rotation_logs yet.", true);
                }
            });
        }, error -> Platform.runLater(() -> {
            rotationChart.getData().clear();
            rotationSummaryLabel.setText("Failed to load timeline data.");
            setState(rotationStateLabel, error.getMessage(), true);
        }));
    }

    private void loadSecurityLogs(String runId) {
        runAsync(() -> {
            ObservableList<LogEntry> entries = FXCollections.observableArrayList();
            boolean validChain = true;
            int count = 0;
            String previousHash = null;

            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT timestamp, event_data, log_hash, previous_hash FROM system_logs WHERE run_id = ? ORDER BY timestamp ASC")) {

                statement.setString(1, runId);
                try (ResultSet resultSet = statement.executeQuery()) {

                    while (resultSet.next()) {
                        String hash = resultSet.getString("log_hash");
                        String chainPreviousHash = resultSet.getString("previous_hash");
                        if (count > 0 && previousHash != null && chainPreviousHash != null && !previousHash.equals(chainPreviousHash)) {
                            validChain = false;
                        }
                        entries.add(new LogEntry(
                                formatDateTime(resultSet.getLong("timestamp")),
                                resultSet.getString("event_data"),
                                hash
                        ));
                        previousHash = hash;
                        count++;
                    }
                }
            }

            FXCollections.reverse(entries);
            boolean finalValidChain = validChain;
            int finalCount = count;

            Platform.runLater(() -> {
                logsTable.setItems(entries);
                if (entries.isEmpty()) {
                    logSummaryLabel.setText("No log entries are available.");
                    setState(logStateLabel, "No security logs have been recorded yet.", true);
                    return;
                }

                logSummaryLabel.setText("Hash chain status: " + (finalValidChain ? "VALID" : "BROKEN") + " | Entries: " + finalCount);
                setState(logStateLabel, "", false);
            });
        }, error -> Platform.runLater(() -> {
            logsTable.setItems(FXCollections.observableArrayList());
            logSummaryLabel.setText("Failed to load security logs.");
            setState(logStateLabel, error.getMessage(), true);
        }));
    }

    private void runAsync(ThrowingRunnable task, Consumer<Exception> onError) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (Exception exception) {
                exception.printStackTrace();
                onError.accept(exception);
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void setState(Label label, String text, boolean visible) {
        label.setText(text);
        label.setVisible(visible);
        label.setManaged(visible);
    }

    private String nowClock() {
        return LocalDateTime.now().format(CLOCK_FORMAT);
    }

    private String formatClock(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(CLOCK_FORMAT);
    }

    private String formatDateTime(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DATE_TIME_FORMAT);
    }

    private String formatDouble(double value) {
        return String.format("%.2f", value);
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "Unknown" : value;
    }

    private String colorForScore(double score) {
        if (score >= 70.0) {
            return "-fx-text-fill: #b42318; -fx-font-weight: bold;";
        }
        if (score >= 40.0) {
            return "-fx-text-fill: #b26b00; -fx-font-weight: bold;";
        }
        return "-fx-text-fill: #1f7a1f; -fx-font-weight: bold;";
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record SensorPoint(long timestamp, double value) { }

    private record AnomalyCardData(String deviceId, String method, double score, double sensorValue, long timestamp) { }

    private record PerformanceRow(String operation, double executionMs, double cpuPercent, double memoryMb, long timestamp) { }

    private record ScalabilityPoint(int deviceCount, double avgLatency, double cpuPercent) { }

    private static final class LogEntry {
        private final String timestamp;
        private final String entry;
        private final String hash;

        private LogEntry(String timestamp, String entry, String hash) {
            this.timestamp = timestamp;
            this.entry = entry;
            this.hash = hash;
        }
    }
}
