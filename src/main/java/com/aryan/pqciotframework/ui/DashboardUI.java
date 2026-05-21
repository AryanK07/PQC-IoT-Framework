package com.aryan.pqciotframework.ui;

import com.aryan.pqciotframework.config.RunContext;
import com.aryan.pqciotframework.database.DatabaseManager;
import com.aryan.pqciotframework.model.PerformanceMetric;
import com.aryan.pqciotframework.security.ClassicalSecurityService;
import com.aryan.pqciotframework.security.CryptoKeyBundle;
import com.aryan.pqciotframework.security.PQCService;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DashboardUI extends Application {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final int SENSOR_POINTS_LIMIT = 120;
	private static final int TABLE_LIMIT = 150;
	private static final int BASE_REFRESH_SECONDS = 10;
	private static final int MAX_REFRESH_BACKOFF_SECONDS = 30;
	private static final double MAX_REASONABLE_LATENCY_MS = 1_000_000;
	private static final double MAX_REASONABLE_CPU_PERCENT = 100;
	private static final double MAX_REASONABLE_EXECUTION_MS = 60_000;
	private static final double MAX_REASONABLE_MINUTES = 24 * 60;
	private static final double MAX_REASONABLE_SENSOR_VALUE = 10_000;
	private static final int ROTATION_MARKER_MAX_POINTS = 60;
	private static final long ROTATION_MARKER_MIN_BUCKET_MS = 500;
	private static final long PERFORMANCE_TIMELINE_TARGET_POINTS = 120;
	private static final long PERFORMANCE_TIMELINE_MIN_BUCKET_MS = 250;
	private static final int SCALABILITY_FALLBACK_MAX_POINTS = 12;
	private static final int CONSOLE_BATCH_LINES = 40;
	private static final int CONSOLE_MAX_CHARS = 120_000;
	private static final int CONSOLE_TRIM_TO_CHARS = 80_000;

	private final ExecutorService loaderExecutor = Executors.newSingleThreadExecutor();
	private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
	private final ScheduledExecutorService refreshExecutor = Executors.newSingleThreadScheduledExecutor();
	private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
	private final AtomicBoolean retryRefreshScheduled = new AtomicBoolean(false);
	private final AtomicInteger consecutiveRefreshFailures = new AtomicInteger(0);
	private final Timeline runnerTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateRunnerMetrics()));

	private final ComboBox<String> deviceSelector = new ComboBox<>();
	private final Label statusLabel = new Label("Connecting to database...");
	private final Label runScopeLabel = new Label("Run scope: resolving...");
	private final Label sensorSummaryLabel = new Label("Waiting for data");
	private final Label benchmarkLabel = new Label("Benchmark pending");
	private final Label overheadSummaryLabel = new Label("PQC overhead vs classical: pending");
	private final Label anomalySummaryLabel = new Label("Waiting for anomaly stream");
	private final Label logSummaryLabel = new Label("Waiting for security logs");
	private final Label scalabilitySummaryLabel = new Label("Waiting for scalability data");
	private final Label rotationSummaryLabel = new Label("Waiting for key rotation data");
	private final Label sensorNoDataLabel = createNoDataOverlayLabel("No sensor data yet");
	private final Label anomalyNoDataLabel = createNoDataOverlayLabel("No anomaly events yet");
	private final Label scalabilityNoDataLabel = createNoDataOverlayLabel("No scalability data yet");
	private final Label rotationNoDataLabel = createNoDataOverlayLabel("No key rotation timeline data yet");
	private final Label runnerStatusLabel = new Label("No active workload");
	private final Label runnerNameValueLabel = new Label("Idle");
	private final Label runnerDurationValueLabel = new Label("00:00");
	private final Label runnerPidValueLabel = new Label("-");
	private final Label runnerExitValueLabel = new Label("N/A");
	private final TextArea executionConsole = new TextArea();
	private final Button runPipelineButton = new Button("Run Main Pipeline");
	private final Button runStressButton = new Button("Run Final Stress Test");
	private final Button runScalabilityButton = new Button("Run Scalability Test");
	private final Button clearAnalyticsButton = new Button("Clear Analytical Tables");
	private final Button resetLogsButton = new Button("Reset Security Logs");
	private final Button stopJobButton = new Button("Stop Active Job");

	private final LineChart<Number, Number> sensorChart = createLineChart("Sensor Telemetry", "Seconds", "Sensor Value");
	private final LineChart<Number, Number> latencyChart = createLineChart("Latency vs Device Count", "Device Count", "Latency (ms)");
	private final LineChart<Number, Number> cpuChart = createLineChart("CPU vs Device Count", "Device Count", "CPU Usage (%)");
	private final LineChart<Number, Number> rotationPerformanceChart = createLineChart("Key Rotation Timeline", "Minutes", "Execution Time (ms)");
	private final BarChart<String, Number> cryptoComparisonChart = createBarChart();

	private final TableView<AnomalyRow> anomalyTable = createAnomalyTable();
	private final TableView<SecurityLogRow> logTable = createLogTable();
	private volatile Process activeProcess;
	private volatile String activeJobName;
	private volatile long activeJobStartMillis = -1;
	private volatile Long activeProcessPid;
	private volatile Integer lastExitCode;
	private volatile String lastJobName = "Idle";
	private volatile Long lastProcessPid;
	private volatile Long lastJobDurationSeconds;
	private volatile String activeRunId = "legacy";
	private volatile String lastRotationChartSignature = "";

	public static void main(String[] args) {
		launch(args);
	}

	@Override
	public void start(Stage stage) {
		BorderPane root = new BorderPane();
		root.setPadding(new Insets(18));
		root.setStyle("-fx-background-color: linear-gradient(to bottom, #f6f8fb, #edf3f7);");

		root.setTop(createHeader());
		root.setCenter(createTabs());

		Scene scene = new Scene(root, 1500, 980, Color.web("#f6f8fb"));
		stage.setTitle("PQC-IoT Live Analytical Dashboard");
		stage.setScene(scene);
		stage.show();

		deviceSelector.setOnAction(event -> refreshDashboard(true));
		configureControlActions();
		updateControlState();
		setActiveRunId(DatabaseManager.resolveLatestRunId());
		runnerTimeline.setCycleCount(Animation.INDEFINITE);
		runnerTimeline.play();
		refreshDashboard(true);
		refreshExecutor.scheduleAtFixedRate(() -> refreshDashboard(false), BASE_REFRESH_SECONDS, BASE_REFRESH_SECONDS, TimeUnit.SECONDS);
		loaderExecutor.submit(this::runCryptoBenchmark);
	}

	@Override
	public void stop() {
		stopActiveProcess(false);
		runnerTimeline.stop();
		refreshExecutor.shutdownNow();
		loaderExecutor.shutdownNow();
		commandExecutor.shutdownNow();
	}

	private HBox createHeader() {
		Label title = new Label("PQC-IoT Command Dashboard");
		title.setFont(Font.font("Segoe UI Semibold", 28));
		title.setStyle("-fx-text-fill: #19324d;");

		Label subtitle = new Label("Live telemetry, anomaly detection, cryptographic benchmarks, scalability trends, and immutable audit logs.");
		subtitle.setStyle("-fx-text-fill: #52657a; -fx-font-size: 14px;");

		VBox titleBox = new VBox(4, title, subtitle);

		Button refreshButton = new Button("Refresh");
		refreshButton.setStyle(buttonStyle("#0f766e"));
		refreshButton.setOnAction(event -> {
			statusLabel.setText("Refreshing dashboard...");
			refreshDashboard(true);
			loaderExecutor.submit(this::runCryptoBenchmark);
		});

		deviceSelector.setPromptText("Sensor device");
		deviceSelector.setMinWidth(180);

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);

		VBox statusBox = new VBox(8,
				decorateBadge(statusLabel, "#d9f3ef", "#0f766e"),
				decorateBadge(runScopeLabel, "#e8edff", "#3730a3"),
				decorateBadge(benchmarkLabel, "#fff4da", "#9a6700"));
		statusBox.setAlignment(Pos.CENTER_RIGHT);

		HBox header = new HBox(14, titleBox, spacer, deviceSelector, refreshButton, statusBox);
		header.setAlignment(Pos.CENTER_LEFT);
		header.setPadding(new Insets(0, 0, 18, 0));
		return header;
	}

	private VBox createControlCenter() {
		runPipelineButton.setStyle(buttonStyle("#1d4ed8"));
		runStressButton.setStyle(buttonStyle("#7c3aed"));
		runScalabilityButton.setStyle(buttonStyle("#0f766e"));
		clearAnalyticsButton.setStyle(buttonStyle("#c2410c"));
		resetLogsButton.setStyle(buttonStyle("#6d28d9"));
		stopJobButton.setStyle(buttonStyle("#b91c1c"));

		executionConsole.setEditable(false);
		executionConsole.setWrapText(true);
		executionConsole.setPrefRowCount(18);
		executionConsole.setMinHeight(420);
		executionConsole.setStyle("-fx-control-inner-background: #0f172a; -fx-font-family: 'Consolas'; -fx-highlight-fill: #334155; -fx-highlight-text-fill: white; -fx-text-fill: #e2e8f0;");

		Label title = createSectionTitle("Control Center");
		Label note = new Label("Launch workloads, run maintenance, and monitor output from one place. The layout is split to keep controls and logs readable without overlapping content.");
		note.setStyle("-fx-text-fill: #52657a; -fx-font-size: 13px;");
		note.setWrapText(true);

		HBox statusCards = new HBox(12,
				createStatusCard("Active Job", runnerNameValueLabel),
				createStatusCard("Duration", runnerDurationValueLabel),
				createStatusCard("Process ID", runnerPidValueLabel),
				createStatusCard("Last Exit", runnerExitValueLabel));
		statusCards.setAlignment(Pos.CENTER_LEFT);

		FlowPane primaryActions = new FlowPane();
		primaryActions.setHgap(12);
		primaryActions.setVgap(12);
		primaryActions.getChildren().addAll(runPipelineButton, runStressButton, runScalabilityButton, stopJobButton);

		FlowPane maintenanceActions = new FlowPane();
		maintenanceActions.setHgap(12);
		maintenanceActions.setVgap(12);
		maintenanceActions.getChildren().addAll(clearAnalyticsButton, resetLogsButton);

		Label maintenanceNote = new Label("Maintenance actions clear runtime tables or reset the immutable log chain before a fresh demonstration run.");
		maintenanceNote.setStyle("-fx-text-fill: #52657a; -fx-font-size: 13px;");
		maintenanceNote.setWrapText(true);

		VBox controlsPane = createCardPane(
				title,
				note,
				statusCards,
				createInfoStrip(runnerStatusLabel, "#ddeafe", "#1d4ed8"),
				new Separator(),
				createSectionTitle("Run Workloads"),
				primaryActions,
				new Separator(),
				createSectionTitle("Maintenance"),
				maintenanceNote,
				maintenanceActions);
		controlsPane.setPrefWidth(520);
		controlsPane.setMinWidth(420);

		Label consoleNote = new Label("Live stdout and stderr from the launched framework jobs.");
		consoleNote.setStyle("-fx-text-fill: #52657a; -fx-font-size: 13px;");
		consoleNote.setWrapText(true);

		VBox consolePane = createCardPane(
				createSectionTitle("Execution Console"),
				consoleNote,
				executionConsole);
		VBox.setVgrow(executionConsole, Priority.ALWAYS);

		HBox layout = new HBox(18, controlsPane, consolePane);
		layout.setAlignment(Pos.TOP_LEFT);
		HBox.setHgrow(consolePane, Priority.ALWAYS);

		ScrollPane scrollPane = new ScrollPane(layout);
		scrollPane.setFitToWidth(true);
		scrollPane.setFitToHeight(true);
		scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

		VBox box = new VBox(scrollPane);
		VBox.setVgrow(scrollPane, Priority.ALWAYS);
		return box;
	}

	private void configureControlActions() {
		runPipelineButton.setOnAction(event -> launchWorkload("Main Pipeline", "com.aryan.pqciotframework.MainApp"));
		runStressButton.setOnAction(event -> launchWorkload("Final Stress Test", "com.aryan.pqciotframework.FinalIntegrationTest"));
		runScalabilityButton.setOnAction(event -> launchWorkload("Scalability Test", "com.aryan.pqciotframework.performance.ScalabilityRunner"));
		clearAnalyticsButton.setOnAction(event -> requestClearAnalyticalTables());
		resetLogsButton.setOnAction(event -> requestResetSecurityLogs());
		stopJobButton.setOnAction(event -> stopActiveProcess(true));
	}

	private TabPane createTabs() {
		TabPane tabPane = new TabPane(
				createTab("Control Center", createControlCenter()),
				createTab("Sensor View", createSensorTab()),
				createTab("Anomaly Panel", createAnomalyTab()),
				createTab("PQC vs Classical", createComparisonTab()),
				createTab("Scalability Charts", createScalabilityTab()),
				createTab("Key Rotation Timeline", createRotationTab()),
				createTab("Security Logs", createLogsTab())
		);
		tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
		tabPane.setStyle("-fx-font-size: 13px;");
		return tabPane;
	}

	private VBox createSensorTab() {
		sensorChart.setPrefHeight(620);
		StackPane contentPane = new StackPane(sensorChart, sensorNoDataLabel);
		VBox box = createCardPane(
				createSectionTitle("Real-time sensor values from sensor_data"),
				createInfoStrip(sensorSummaryLabel, "#d9ecff", "#1d4ed8"),
				contentPane);
		VBox.setVgrow(contentPane, Priority.ALWAYS);
		return box;
	}

	private VBox createAnomalyTab() {
		anomalyTable.setPrefHeight(680);
		StackPane contentPane = new StackPane(anomalyTable, anomalyNoDataLabel);
		Label legend = new Label("Score legend (0-100): 0-40 Low | 40-70 Moderate | 70-100 High/Severe");
		legend.setStyle("-fx-text-fill: #52657a; -fx-font-size: 12px;");
		legend.setWrapText(true);
		VBox box = createCardPane(
				createSectionTitle("Detected anomaly events and anomaly scores"),
				createInfoStrip(anomalySummaryLabel, "#ffe1df", "#c2410c"),
				legend,
				contentPane);
		VBox.setVgrow(contentPane, Priority.ALWAYS);
		return box;
	}

	private VBox createComparisonTab() {
		Label note = new Label("Live cryptographic benchmarks run against the project’s PQC and classical security services.");
		note.setStyle("-fx-text-fill: #52657a; -fx-font-size: 13px;");
		note.setWrapText(true);
		cryptoComparisonChart.setPrefHeight(650);
		VBox box = createCardPane(
				createSectionTitle("PQC vs Classical cryptography timings"),
				note,
				createInfoStrip(overheadSummaryLabel, "#efe6ff", "#6d28d9"),
				cryptoComparisonChart);
		VBox.setVgrow(cryptoComparisonChart, Priority.ALWAYS);
		return box;
	}

	private VBox createScalabilityTab() {
		GridPane grid = new GridPane();
		grid.setHgap(18);
		grid.setVgap(18);
		grid.add(latencyChart, 0, 0);
		grid.add(cpuChart, 1, 0);
		GridPane.setHgrow(latencyChart, Priority.ALWAYS);
		GridPane.setHgrow(cpuChart, Priority.ALWAYS);
		GridPane.setVgrow(latencyChart, Priority.ALWAYS);
		GridPane.setVgrow(cpuChart, Priority.ALWAYS);
		latencyChart.setPrefHeight(620);
		cpuChart.setPrefHeight(620);
		latencyChart.setMinWidth(520);
		cpuChart.setMinWidth(520);
		StackPane contentPane = new StackPane(grid, scalabilityNoDataLabel);

		VBox box = createCardPane(
				createSectionTitle("Scalability curves from scalability_results"),
				createInfoStrip(scalabilitySummaryLabel, "#e8f3ff", "#1d4ed8"),
				contentPane);
		VBox.setVgrow(contentPane, Priority.ALWAYS);
		return box;
	}

	private VBox createRotationTab() {
		StackPane contentPane = new StackPane(rotationPerformanceChart, rotationNoDataLabel);
		rotationPerformanceChart.setPrefHeight(680);
		rotationPerformanceChart.setCreateSymbols(true);

		Label hint = new Label("Rotation markers are plotted over recent performance execution times using key_rotation_logs and performance_metrics.");
		hint.setStyle("-fx-text-fill: #52657a; -fx-font-size: 13px;");
		hint.setWrapText(true);

		VBox box = createCardPane(
				createSectionTitle("Key rotation events overlaid on performance activity"),
				hint,
				createInfoStrip(rotationSummaryLabel, "#fff1df", "#b45309"),
				contentPane);
		VBox.setVgrow(contentPane, Priority.ALWAYS);
		return box;
	}

	private VBox createLogsTab() {
		logTable.setPrefHeight(700);
		VBox box = createCardPane(
				createSectionTitle("Immutable security log viewer"),
				createInfoStrip(logSummaryLabel, "#efe6ff", "#6d28d9"),
				logTable);
		VBox.setVgrow(logTable, Priority.ALWAYS);
		return box;
	}

	private void refreshDashboard(boolean includeDevices) {
		if (!refreshInProgress.compareAndSet(false, true)) {
			return;
		}
		String selectedDevice = deviceSelector.getValue();
		String runId = activeRunId;
		loaderExecutor.submit(() -> loadDashboardSnapshot(selectedDevice, includeDevices, runId));
	}

	private void loadDashboardSnapshot(String selectedDevice, boolean includeDevices, String runId) {
		try (Connection connection = DatabaseManager.getConnection()) {
			DashboardSnapshot snapshot = new DashboardSnapshot();
			if (includeDevices) {
				snapshot.devices = queryDeviceIds(connection, runId);
			}
			snapshot.sensorReadings = querySensorReadings(connection, selectedDevice, runId);
			snapshot.anomalies = queryAnomalies(connection, runId);
			snapshot.performancePoints = queryPerformance(connection, runId);
			snapshot.scalabilityPoints = queryScalability(connection, runId);
			if (snapshot.scalabilityPoints.isEmpty() && !snapshot.performancePoints.isEmpty()) {
				snapshot.scalabilityPoints = deriveScalabilityFallback(snapshot.performancePoints);
				snapshot.scalabilityDerivedFromPerformance = !snapshot.scalabilityPoints.isEmpty();
			}
			snapshot.keyRotations = queryKeyRotations(connection, runId);
			snapshot.securityLogs = querySecurityLogs(connection, runId);

			consecutiveRefreshFailures.set(0);
			retryRefreshScheduled.set(false);
			Platform.runLater(() -> applySnapshot(snapshot, selectedDevice));
		} catch (Exception ex) {
			handleRefreshFailure(ex);
		} finally {
			refreshInProgress.set(false);
		}
	}

	private void handleRefreshFailure(Exception ex) {
		int failures = consecutiveRefreshFailures.incrementAndGet();
		int delaySeconds = Math.min(MAX_REFRESH_BACKOFF_SECONDS, 1 << Math.min(failures - 1, 5));
		String message = summarizeRefreshError(ex);
		Platform.runLater(() -> statusLabel.setText(message + " Retrying in " + delaySeconds + "s"));
		scheduleRetryRefresh(delaySeconds);
	}

	private void scheduleRetryRefresh(int delaySeconds) {
		if (!retryRefreshScheduled.compareAndSet(false, true)) {
			return;
		}
		refreshExecutor.schedule(() -> {
			retryRefreshScheduled.set(false);
			refreshDashboard(false);
		}, delaySeconds, TimeUnit.SECONDS);
	}

	private String summarizeRefreshError(Exception ex) {
		String errorText = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
		if (errorText.contains("socket") || errorText.contains("unable to create connection") || errorText.contains("communications link failure")) {
			return "Database temporarily unreachable.";
		}
		if (errorText.contains("access denied")) {
			return "Database authentication failed.";
		}
		if (errorText.contains("too many connections") || errorText.contains("max_connections")) {
			return "Database connection limit reached.";
		}
		return "Dashboard refresh failed.";
	}

	private void applySnapshot(DashboardSnapshot snapshot, String selectedDevice) {
		if (snapshot.devices != null && !snapshot.devices.isEmpty()) {
			List<String> options = new ArrayList<>();
			options.add("All Devices");
			options.addAll(snapshot.devices);
			deviceSelector.setItems(FXCollections.observableArrayList(options));
			if (selectedDevice == null || !options.contains(selectedDevice)) {
				deviceSelector.setValue(options.get(0));
			} else {
				deviceSelector.setValue(selectedDevice);
			}
		}

		updateSensorChart(snapshot.sensorReadings, deviceSelector.getValue());
		anomalyTable.setItems(FXCollections.observableArrayList(snapshot.anomalies));
		boolean hasAnomalies = !snapshot.anomalies.isEmpty();
		anomalyNoDataLabel.setVisible(!hasAnomalies);
		anomalyNoDataLabel.setManaged(!hasAnomalies);
		updateScalabilityCharts(snapshot.scalabilityPoints);
		if (snapshot.scalabilityDerivedFromPerformance) {
			scalabilitySummaryLabel.setText("Scalability table is empty; showing fallback trend from recent performance metrics.");
		}
		updateRotationChart(snapshot.performancePoints, snapshot.keyRotations);
		logTable.setItems(FXCollections.observableArrayList(snapshot.securityLogs));

		anomalySummaryLabel.setText(snapshot.anomalies.isEmpty()
				? "No anomaly events recorded"
				: String.format("%d anomaly records loaded. Highest score %.2f", snapshot.anomalies.size(),
				snapshot.anomalies.stream().mapToDouble(AnomalyRow::score).max().orElse(0)));
		logSummaryLabel.setText(snapshot.securityLogs.isEmpty()
				? "No immutable log entries available"
				: String.format("Showing %d latest log entries. Most recent event: %s",
				snapshot.securityLogs.size(), snapshot.securityLogs.get(0).eventType()));
		statusLabel.setText("Live at " + LocalDateTime.now().format(TIME_FORMAT));
	}

	private void runCryptoBenchmark() {
		try {
			Map<String, Double> pqc = benchmarkService(new PQCService());
			Map<String, Double> classical = benchmarkService(new ClassicalSecurityService());
			persistBenchmarkMetrics(pqc, classical);
			Platform.runLater(() -> updateComparisonChart(pqc, classical));
		} catch (Exception ex) {
			Platform.runLater(() -> benchmarkLabel.setText("Benchmark error: " + ex.getMessage()));
		}
	}

	private void launchWorkload(String jobName, String mainClass) {
		if (isProcessRunning()) {
			runnerStatusLabel.setText("Stop the current workload before starting another one.");
			return;
		}
		String runId = generateRunIdForJob(jobName);
		setActiveRunId(runId);

		appendConsoleLine("");
		appendConsoleLine("=== Launching " + jobName + " at " + LocalDateTime.now().format(TIME_FORMAT) + " ===");
		appendConsoleLine("=== Run ID: " + runId + " ===");
		runnerStatusLabel.setText(jobName + " is starting...");
		commandExecutor.submit(() -> runWorkloadProcess(jobName, mainClass, runId));
	}

	private void runWorkloadProcess(String jobName, String mainClass, String runId) {
		Process process = null;
		try {
			File workingDirectory = new File(System.getProperty("user.dir"));
			String runtimeClassPath = buildRuntimeClassPath(workingDirectory);
			appendConsoleLine("[" + jobName + "] Working directory: " + workingDirectory.getAbsolutePath());
			appendConsoleLine("[" + jobName + "] Java executable: " + resolveJavaExecutable());
			appendConsoleLine("[" + jobName + "] Runtime classpath configured.");
			ProcessBuilder builder = new ProcessBuilder(
					resolveJavaExecutable(),
					"-cp",
					runtimeClassPath,
					mainClass
			);
			builder.directory(workingDirectory);
			builder.redirectErrorStream(true);
			builder.environment().put("PQC_RUN_ID", runId);

			process = builder.start();
			activeProcess = process;
			activeJobName = jobName;
			activeJobStartMillis = System.currentTimeMillis();
			activeProcessPid = process.pid();
			lastJobName = jobName;
			lastProcessPid = activeProcessPid;
			lastJobDurationSeconds = null;
			Platform.runLater(() -> {
				runnerStatusLabel.setText(jobName + " is running...");
				runnerNameValueLabel.setText(jobName);
				runnerPidValueLabel.setText(String.valueOf(activeProcessPid));
				updateControlState();
			});

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				boolean streamImmediately = "Scalability Test".equals(jobName) || "Final Stress Test".equals(jobName);
				List<String> buffer = new ArrayList<>(CONSOLE_BATCH_LINES);
				while ((line = reader.readLine()) != null) {
					if (!shouldDisplayConsoleLine(jobName, line)) {
						continue;
					}
					String prefixedLine = "[" + jobName + "] " + line;
					if (streamImmediately) {
						appendConsoleLine(prefixedLine);
						if (line.contains("[Setup]") || line.contains("[Progress]")) {
							refreshDashboard(false);
						}
						continue;
					}
					buffer.add(prefixedLine);
					if (buffer.size() >= CONSOLE_BATCH_LINES) {
						appendConsoleLines(buffer);
						buffer = new ArrayList<>(CONSOLE_BATCH_LINES);
					}
				}
				if (!buffer.isEmpty()) {
					appendConsoleLines(buffer);
				}
			}

			int exitCode = process.waitFor();
			lastExitCode = exitCode;
			lastJobDurationSeconds = activeJobStartMillis > 0
					? Math.max(0, (System.currentTimeMillis() - activeJobStartMillis) / 1000)
					: null;
			appendConsoleLine("=== " + jobName + " exited with code " + exitCode + " ===");
			Platform.runLater(() -> {
				runnerStatusLabel.setText(jobName + " finished with exit code " + exitCode);
				runnerExitValueLabel.setText(String.valueOf(exitCode));
				refreshDashboard(true);
				loaderExecutor.submit(this::runCryptoBenchmark);
			});
		} catch (Exception ex) {
			appendConsoleLine("[" + jobName + "] ERROR: " + ex.getMessage());
			lastExitCode = -1;
			Platform.runLater(() -> runnerStatusLabel.setText(jobName + " failed: " + ex.getMessage()));
		} finally {
			activeProcess = null;
			activeJobName = null;
			activeJobStartMillis = -1;
			activeProcessPid = null;
			Platform.runLater(this::updateControlState);
			if (process != null) {
				process.destroy();
			}
		}
	}

	private String buildRuntimeClassPath(File workingDirectory) {
		List<String> entries = new ArrayList<>();
		String currentClassPath = System.getProperty("java.class.path", "");
		if (currentClassPath != null && !currentClassPath.isBlank()) {
			for (String part : currentClassPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
				if (part != null && !part.isBlank()) {
					entries.add(part);
				}
			}
		}

		File targetClassesDir = new File(workingDirectory, "target" + File.separator + "classes");
		if (targetClassesDir.exists()) {
			String targetClassesPath = targetClassesDir.getAbsolutePath();
			boolean alreadyPresent = entries.stream().anyMatch(entry -> entry.equalsIgnoreCase(targetClassesPath));
			if (!alreadyPresent) {
				entries.add(targetClassesPath);
			}
		}

		File dependencyDir = new File(workingDirectory, "target" + File.separator + "dependency");
		if (dependencyDir.exists()) {
			String dependencyWildcard = dependencyDir.getAbsolutePath() + File.separator + "*";
			boolean alreadyPresent = entries.stream().anyMatch(entry -> entry.equalsIgnoreCase(dependencyWildcard));
			if (!alreadyPresent) {
				entries.add(dependencyWildcard);
			}
		}

		if (entries.isEmpty()) {
			return currentClassPath == null ? "" : currentClassPath;
		}
		return String.join(File.pathSeparator, entries);
	}

	private void stopActiveProcess(boolean userRequested) {
		Process process = activeProcess;
		String jobName = activeJobName == null ? "Active workload" : activeJobName;
		if (process == null || !process.isAlive()) {
			Platform.runLater(() -> {
				runnerStatusLabel.setText("No active workload to stop.");
				updateControlState();
			});
			return;
		}

		process.destroy();
		appendConsoleLine("=== Stop requested for " + jobName + " ===");
		if (userRequested) {
			Platform.runLater(() -> runnerStatusLabel.setText("Stopping " + jobName + "..."));
		}
		Thread stopper = new Thread(() -> {
			try {
				if (!process.waitFor(3, TimeUnit.SECONDS)) {
					process.destroyForcibly();
					process.waitFor();
				}
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}, "dashboard-process-stop");
		stopper.setDaemon(true);
		stopper.start();
	}

	private boolean isProcessRunning() {
		return activeProcess != null && activeProcess.isAlive();
	}

	private void updateControlState() {
		boolean running = isProcessRunning();
		runPipelineButton.setDisable(running);
		runStressButton.setDisable(running);
		runScalabilityButton.setDisable(running);
		clearAnalyticsButton.setDisable(running);
		resetLogsButton.setDisable(running);
		stopJobButton.setDisable(!running);
		runnerExitValueLabel.setText(lastExitCode == null ? "N/A" : String.valueOf(lastExitCode));
		String displayJobName = running ? activeJobName : lastJobName;
		if (displayJobName == null || displayJobName.isBlank()) {
			displayJobName = "Idle";
		}
		runnerNameValueLabel.setText(displayJobName);
		if (running) {
			runnerPidValueLabel.setText(activeProcessPid == null ? "-" : String.valueOf(activeProcessPid));
		} else {
			runnerPidValueLabel.setText(lastProcessPid == null ? "-" : String.valueOf(lastProcessPid));
		}
	}

	private void updateRunnerMetrics() {
		if (isProcessRunning() && activeJobStartMillis > 0) {
			long elapsedSeconds = Math.max(0, (System.currentTimeMillis() - activeJobStartMillis) / 1000);
			runnerDurationValueLabel.setText(formatDuration(elapsedSeconds));
			return;
		}
		if (lastJobDurationSeconds != null) {
			runnerDurationValueLabel.setText(formatDuration(lastJobDurationSeconds));
		} else {
			runnerDurationValueLabel.setText("00:00");
		}
	}

	private void appendConsoleLine(String line) {
		appendConsoleLines(List.of(line));
	}

	private void appendConsoleLines(List<String> lines) {
		if (lines == null || lines.isEmpty()) {
			return;
		}
		List<String> snapshot = new ArrayList<>(lines);
		Platform.runLater(() -> {
			StringBuilder builder = new StringBuilder();
			for (String line : snapshot) {
				builder.append(line).append(System.lineSeparator());
			}
			executionConsole.appendText(builder.toString());
			int length = executionConsole.getLength();
			if (length > CONSOLE_MAX_CHARS) {
				int from = Math.max(0, length - CONSOLE_TRIM_TO_CHARS);
				executionConsole.setText(executionConsole.getText(from, length));
			}
			executionConsole.positionCaret(executionConsole.getLength());
		});
	}

	private boolean shouldDisplayConsoleLine(String jobName, String line) {
		if (!"Scalability Test".equals(jobName) && !"Final Stress Test".equals(jobName)) {
			return true;
		}
		return !(line.contains("PACKET_PROCESSED") || line.contains("Processed packet from "));
	}

	private void requestClearAnalyticalTables() {
		if (!confirmAction("Clear Analytical Tables", "Delete sensor, anomaly, performance, scalability, and key rotation data before the next run?")) {
			return;
		}
		executeMaintenanceTask("Clear Analytical Tables", () -> {
			try (Connection connection = DatabaseManager.getConnection(); Statement statement = connection.createStatement()) {
				statement.executeUpdate("DELETE FROM anomaly_events");
				statement.executeUpdate("DELETE FROM sensor_data");
				statement.executeUpdate("DELETE FROM performance_metrics");
				statement.executeUpdate("DELETE FROM scalability_results");
				statement.executeUpdate("DELETE FROM key_rotation_logs");
			}
		});
	}

	private void requestResetSecurityLogs() {
		if (!confirmAction("Reset Security Logs", "Delete all immutable log rows and start a fresh hash chain?")) {
			return;
		}
		executeMaintenanceTask("Reset Security Logs", () -> {
			try (Connection connection = DatabaseManager.getConnection(); Statement statement = connection.createStatement()) {
				statement.executeUpdate("DELETE FROM system_logs");
			}
		});
	}

	private void executeMaintenanceTask(String taskName, SqlTask task) {
		if (isProcessRunning()) {
			runnerStatusLabel.setText("Stop the current workload before running maintenance.");
			return;
		}
		appendConsoleLine("");
		appendConsoleLine("=== " + taskName + " started at " + LocalDateTime.now().format(TIME_FORMAT) + " ===");
		runnerStatusLabel.setText(taskName + " is running...");
		commandExecutor.submit(() -> {
			try {
				task.run();
				lastExitCode = 0;
				appendConsoleLine("=== " + taskName + " completed successfully ===");
				Platform.runLater(() -> {
					runnerStatusLabel.setText(taskName + " completed successfully");
					runnerExitValueLabel.setText("0");
					refreshDashboard(true);
				});
			} catch (Exception ex) {
				lastExitCode = -1;
				appendConsoleLine("[" + taskName + "] ERROR: " + ex.getMessage());
				Platform.runLater(() -> {
					runnerStatusLabel.setText(taskName + " failed: " + ex.getMessage());
					runnerExitValueLabel.setText("-1");
				});
			}
		});
	}

	private boolean confirmAction(String title, String message) {
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
		alert.setTitle(title);
		alert.setHeaderText(title);
		Optional<ButtonType> result = alert.showAndWait();
		return result.isPresent() && result.get() == ButtonType.OK;
	}

	private String resolveJavaExecutable() {
		return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java.exe";
	}

	private void setActiveRunId(String runId) {
		String normalized = (runId == null || runId.isBlank()) ? "legacy" : runId;
		activeRunId = normalized;
		RunContext.setCurrentRunId(normalized);
		Platform.runLater(() -> runScopeLabel.setText("Run scope: " + normalized));
	}

	private String generateRunIdForJob(String jobName) {
		String prefix = switch (jobName) {
			case "Main Pipeline" -> "main";
			case "Final Stress Test" -> "integration";
			case "Scalability Test" -> "scalability";
			default -> "run";
		};
		String timePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		String randomPart = UUID.randomUUID().toString().substring(0, 8);
		return prefix + "-" + timePart + "-" + randomPart;
	}

	private Map<String, Double> benchmarkService(Object service) throws Exception {
		byte[] payload = "dashboard-benchmark-payload".getBytes(StandardCharsets.UTF_8);
		LinkedHashMap<String, Double> results = new LinkedHashMap<>();
		int samples = 5;
		double keyGen = 0;
		double encrypt = 0;
		double sign = 0;
		double verify = 0;

		for (int i = 0; i < samples; i++) {
			if (service instanceof PQCService pqcService) {
				TimedResult<CryptoKeyBundle> keyBundle = measureTimed(() -> pqcService.generateKeyBundle());
				keyGen += keyBundle.elapsedMs();
				encrypt += measureMs(() -> pqcService.encryptData(keyBundle.value().getEncryptionKeyPair().getPublic(), payload));
				TimedResult<byte[]> signature = measureTimed(() -> pqcService.signData(keyBundle.value().getSignatureKeyPair().getPrivate(), payload));
				sign += signature.elapsedMs();
				verify += measureMs(() -> pqcService.verifySignature(keyBundle.value().getSignatureKeyPair().getPublic(), payload, signature.value()));
			} else if (service instanceof ClassicalSecurityService classicalService) {
				TimedResult<CryptoKeyBundle> keyBundle = measureTimed(() -> classicalService.generateKeyBundle());
				keyGen += keyBundle.elapsedMs();
				encrypt += measureMs(() -> classicalService.encryptData(keyBundle.value().getEncryptionKeyPair().getPublic(), payload));
				TimedResult<byte[]> signature = measureTimed(() -> classicalService.signData(keyBundle.value().getSignatureKeyPair().getPrivate(), payload));
				sign += signature.elapsedMs();
				verify += measureMs(() -> classicalService.verifySignature(keyBundle.value().getSignatureKeyPair().getPublic(), payload, signature.value()));
			}
		}

		results.put("KeyGen", keyGen / samples);
		results.put("Encrypt", encrypt / samples);
		results.put("Sign", sign / samples);
		results.put("Verify", verify / samples);
		return results;
	}

	private void updateComparisonChart(Map<String, Double> pqc, Map<String, Double> classical) {
		XYChart.Series<String, Number> pqcSeries = new XYChart.Series<>();
		pqcSeries.setName("PQC");
		XYChart.Series<String, Number> classicalSeries = new XYChart.Series<>();
		classicalSeries.setName("Classical");

		for (String metric : List.of("KeyGen", "Encrypt", "Sign", "Verify")) {
			pqcSeries.getData().add(new XYChart.Data<>(metric, pqc.getOrDefault(metric, 0.0)));
			classicalSeries.getData().add(new XYChart.Data<>(metric, classical.getOrDefault(metric, 0.0)));
		}

		cryptoComparisonChart.getData().setAll(pqcSeries, classicalSeries);
		double pqcEncryptMs = pqc.getOrDefault("Encrypt", 0.0);
		double classicalEncryptMs = classical.getOrDefault("Encrypt", 0.0);
		String overheadText = formatEncryptOverheadText(pqcEncryptMs, classicalEncryptMs);
		benchmarkLabel.setText(String.format("Benchmarked at %s | PQC Encrypt %.2f ms | Classical Encrypt %.2f ms | %s",
				LocalDateTime.now().format(TIME_FORMAT),
				pqcEncryptMs,
				classicalEncryptMs,
				overheadText));
		overheadSummaryLabel.setText(String.format("PQC overhead %% vs classical (encrypt): %s", overheadText));
	}

	private void persistBenchmarkMetrics(Map<String, Double> pqc, Map<String, Double> classical) {
		long now = System.currentTimeMillis();
		double pqcEncryptMs = pqc.getOrDefault("Encrypt", 0.0);
		double classicalEncryptMs = classical.getOrDefault("Encrypt", 0.0);
		Double overheadPercent = calculateEncryptOverheadPercent(pqcEncryptMs, classicalEncryptMs);

		saveBenchmarkMetric("Dashboard_Benchmark_PQC_Encrypt_ms", pqcEncryptMs, now);
		saveBenchmarkMetric("Dashboard_Benchmark_Classical_Encrypt_ms", classicalEncryptMs, now);
		if (overheadPercent != null) {
			saveBenchmarkMetric("Dashboard_Benchmark_PQC_Overhead_Percent", overheadPercent, now);
		}
	}

	private void saveBenchmarkMetric(String operation, double value, long timestamp) {
		if (!Double.isFinite(value) || value < 0) {
			return;
		}
		DatabaseManager.savePerformanceMetric(new PerformanceMetric(
				operation,
				Math.round(value),
				0,
				0,
				timestamp));
	}

	private String formatEncryptOverheadText(double pqcEncryptMs, double classicalEncryptMs) {
		Double overheadPercent = calculateEncryptOverheadPercent(pqcEncryptMs, classicalEncryptMs);
		if (overheadPercent == null) {
			return "N/A";
		}
		if (Math.abs(overheadPercent) < 0.05) {
			return "0.00% (near parity)";
		}
		if (overheadPercent > 0) {
			return String.format("+%.2f%% slower", overheadPercent);
		}
		return String.format("%.2f%% faster", Math.abs(overheadPercent));
	}

	private Double calculateEncryptOverheadPercent(double pqcEncryptMs, double classicalEncryptMs) {
		if (!Double.isFinite(pqcEncryptMs) || !Double.isFinite(classicalEncryptMs) || classicalEncryptMs <= 0) {
			return null;
		}
		return ((pqcEncryptMs - classicalEncryptMs) / classicalEncryptMs) * 100.0;
	}

	private void updateSensorChart(List<SensorReadingRow> readings, String selectedDevice) {
		XYChart.Series<Number, Number> series = new XYChart.Series<>();
		series.setName(selectedDevice == null ? "All Devices" : selectedDevice);
		List<SensorReadingRow> validRows = new ArrayList<>();
		for (SensorReadingRow row : readings) {
			long normalizedTs = normalizeEpochMillis(row.timestamp());
			double value = row.sensorValue();
			if (normalizedTs <= 0 || !Double.isFinite(value) || value < 0 || value > MAX_REASONABLE_SENSOR_VALUE) {
				continue;
			}
			validRows.add(new SensorReadingRow(row.deviceId(), value, normalizedTs));
		}
		validRows.sort((left, right) -> Long.compare(left.timestamp(), right.timestamp()));
		if (!validRows.isEmpty()) {
			long base = validRows.get(0).timestamp();
			long last = validRows.get(validRows.size() - 1).timestamp();
			boolean collapsedTimeline = (last - base) <= 0;
			NumberAxis xAxis = (NumberAxis) sensorChart.getXAxis();
			xAxis.setLabel(collapsedTimeline ? "Sample" : "Seconds");
			for (int index = 0; index < validRows.size(); index++) {
				SensorReadingRow row = validRows.get(index);
				double x = collapsedTimeline ? (index + 1) : (row.timestamp() - base) / 1000.0;
				if (Double.isFinite(x) && x >= 0 && x <= MAX_REASONABLE_MINUTES * 60) {
					series.getData().add(new XYChart.Data<>(x, row.sensorValue()));
				}
			}
		}
		sensorChart.getData().setAll(series);
		boolean hasSensorData = !series.getData().isEmpty();
		sensorNoDataLabel.setVisible(!hasSensorData);
		sensorNoDataLabel.setManaged(!hasSensorData);
		if (!hasSensorData) {
			setDefaultAxisRange(sensorChart, 0, 10, 0, 100);
			sensorSummaryLabel.setText("No valid sensor data available.");
			return;
		}
		setAxisAutoRange(sensorChart);
		SensorReadingRow latest = validRows.get(validRows.size() - 1);
		sensorSummaryLabel.setText(String.format("Showing %d recent points for %s. Latest %.2f at %s",
				series.getData().size(),
				Objects.requireNonNullElse(selectedDevice, "All Devices"),
				latest.sensorValue(),
				formatTimestamp(latest.timestamp())));
	}

	private void updateScalabilityCharts(List<ScalabilityRow> rows) {
		XYChart.Series<Number, Number> latencySeries = new XYChart.Series<>();
		latencySeries.setName("Latency");
		XYChart.Series<Number, Number> cpuSeries = new XYChart.Series<>();
		cpuSeries.setName("CPU");

		for (ScalabilityRow row : rows) {
			if (row.deviceCount() <= 0) {
				continue;
			}
			if (Double.isFinite(row.avgLatencyMs()) && row.avgLatencyMs() >= 0 && row.avgLatencyMs() <= MAX_REASONABLE_LATENCY_MS) {
				latencySeries.getData().add(new XYChart.Data<>(row.deviceCount(), row.avgLatencyMs()));
			}
			if (Double.isFinite(row.cpuUsagePercent()) && row.cpuUsagePercent() >= 0 && row.cpuUsagePercent() <= MAX_REASONABLE_CPU_PERCENT) {
				cpuSeries.getData().add(new XYChart.Data<>(row.deviceCount(), row.cpuUsagePercent()));
			}
		}

		latencyChart.getData().setAll(latencySeries);
		cpuChart.getData().setAll(cpuSeries);
		boolean hasScalabilityData = !latencySeries.getData().isEmpty() || !cpuSeries.getData().isEmpty();
		scalabilityNoDataLabel.setVisible(!hasScalabilityData);
		scalabilityNoDataLabel.setManaged(!hasScalabilityData);
		scalabilitySummaryLabel.setText(hasScalabilityData
				? String.format("Loaded %d latency points and %d CPU points.", latencySeries.getData().size(), cpuSeries.getData().size())
				: "No valid scalability data yet. Run workload to populate scalability_results.");
		if (latencySeries.getData().isEmpty()) {
			setDefaultAxisRange(latencyChart, 0, 10, 0, 100);
		} else {
			setAxisAutoRange(latencyChart);
		}
		if (cpuSeries.getData().isEmpty()) {
			setDefaultAxisRange(cpuChart, 0, 10, 0, 100);
		} else {
			setAxisAutoRange(cpuChart);
		}
	}

	private void setDefaultAxisRange(LineChart<Number, Number> chart, double xLower, double xUpper, double yLower, double yUpper) {
		NumberAxis xAxis = (NumberAxis) chart.getXAxis();
		NumberAxis yAxis = (NumberAxis) chart.getYAxis();
		xAxis.setAutoRanging(false);
		yAxis.setAutoRanging(false);
		xAxis.setLowerBound(xLower);
		xAxis.setUpperBound(xUpper);
		xAxis.setTickUnit(Math.max(1, (xUpper - xLower) / 5));
		yAxis.setLowerBound(yLower);
		yAxis.setUpperBound(yUpper);
		yAxis.setTickUnit(Math.max(1, (yUpper - yLower) / 5));
	}

	private void setAxisAutoRange(LineChart<Number, Number> chart) {
		NumberAxis xAxis = (NumberAxis) chart.getXAxis();
		NumberAxis yAxis = (NumberAxis) chart.getYAxis();
		xAxis.setAutoRanging(true);
		yAxis.setAutoRanging(true);
	}

	private void updateRotationChart(List<PerformanceRow> performanceRows, List<KeyRotationRow> keyRotations) {
		String chartSignature = buildRotationChartSignature(performanceRows, keyRotations);
		if (chartSignature.equals(lastRotationChartSignature)) {
			return;
		}
		lastRotationChartSignature = chartSignature;

		XYChart.Series<Number, Number> pqcSeries = new XYChart.Series<>();
		pqcSeries.setName("PQC Execution");
		XYChart.Series<Number, Number> classicalSeries = new XYChart.Series<>();
		classicalSeries.setName("Classical Execution");
		XYChart.Series<Number, Number> fallbackSeries = new XYChart.Series<>();
		fallbackSeries.setName("Execution Time");
		XYChart.Series<Number, Number> rotationSeries = new XYChart.Series<>();
		rotationSeries.setName("Key Rotations");
		NumberAxis yAxis = (NumberAxis) rotationPerformanceChart.getYAxis();
		List<PerformanceRow> validPerformanceRows = new ArrayList<>();
		List<KeyRotationRow> validKeyRotations = new ArrayList<>();
		List<KeyRotationRow> plottedRotationRows = new ArrayList<>();
		for (PerformanceRow row : performanceRows) {
			long ts = normalizeEpochMillis(row.timestamp());
			double executionMs = row.executionTimeMs();
			if (ts <= 0 || !Double.isFinite(executionMs) || executionMs < 0 || executionMs > MAX_REASONABLE_EXECUTION_MS) {
				continue;
			}
			if (!isExecutionTimeOperation(row.operation())) {
				continue;
			}
			validPerformanceRows.add(new PerformanceRow(row.operation(), (long) executionMs, row.cpuUsagePercent(), ts));
		}
		validPerformanceRows.sort((left, right) -> Long.compare(left.timestamp(), right.timestamp()));

		for (KeyRotationRow rotation : keyRotations) {
			long ts = normalizeEpochMillis(rotation.timestamp());
			if (ts <= 0) {
				continue;
			}
			validKeyRotations.add(new KeyRotationRow(
					rotation.deviceId(),
					rotation.keyVersion(),
					rotation.rotationReason(),
					rotation.modeName(),
					ts));
		}
		validKeyRotations.sort((left, right) -> Long.compare(left.timestamp(), right.timestamp()));

		if (!validPerformanceRows.isEmpty()) {
			yAxis.setLabel("Execution Time (ms)");
			long base = validPerformanceRows.get(0).timestamp();
			if (!validKeyRotations.isEmpty()) {
				base = Math.min(base, validKeyRotations.get(0).timestamp());
			}
			List<PerformanceRow> pqcRows = new ArrayList<>();
			List<PerformanceRow> classicalRows = new ArrayList<>();
			List<PerformanceRow> fallbackRows = new ArrayList<>();
			for (PerformanceRow row : validPerformanceRows) {
				if (isPqcOperation(row.operation())) {
					pqcRows.add(row);
				} else if (isClassicalOperation(row.operation())) {
					classicalRows.add(row);
				} else {
					fallbackRows.add(row);
				}
			}

			pqcRows = collapseDuplicatePerformanceTimestamps(pqcRows);
			classicalRows = collapseDuplicatePerformanceTimestamps(classicalRows);
			fallbackRows = collapseDuplicatePerformanceTimestamps(fallbackRows);

			List<PerformanceRow> timelinePqcRows = aggregatePerformanceRows(pqcRows, base);
			List<PerformanceRow> timelineClassicalRows = aggregatePerformanceRows(classicalRows, base);
			List<PerformanceRow> timelineFallbackRows = aggregatePerformanceRows(fallbackRows, base);
			List<PerformanceRow> timelinePerformanceRows = new ArrayList<>();
			timelinePerformanceRows.addAll(timelinePqcRows);
			timelinePerformanceRows.addAll(timelineClassicalRows);
			timelinePerformanceRows.addAll(timelineFallbackRows);
			timelinePerformanceRows.sort((left, right) -> Long.compare(left.timestamp(), right.timestamp()));
			double fallbackY = timelinePerformanceRows.stream().mapToDouble(PerformanceRow::executionTimeMs).max().orElse(1.0);
			List<KeyRotationRow> condensedRotations = condenseRotationMarkers(validKeyRotations, base);
			for (PerformanceRow row : timelinePqcRows) {
				double minutes = (row.timestamp() - base) / 60000.0;
				if (Double.isFinite(minutes) && minutes >= 0 && minutes <= MAX_REASONABLE_MINUTES) {
					pqcSeries.getData().add(new XYChart.Data<>(minutes, row.executionTimeMs()));
				}
			}
			for (PerformanceRow row : timelineClassicalRows) {
				double minutes = (row.timestamp() - base) / 60000.0;
				if (Double.isFinite(minutes) && minutes >= 0 && minutes <= MAX_REASONABLE_MINUTES) {
					classicalSeries.getData().add(new XYChart.Data<>(minutes, row.executionTimeMs()));
				}
			}
			for (PerformanceRow row : timelineFallbackRows) {
				double minutes = (row.timestamp() - base) / 60000.0;
				if (Double.isFinite(minutes) && minutes >= 0 && minutes <= MAX_REASONABLE_MINUTES) {
					fallbackSeries.getData().add(new XYChart.Data<>(minutes, row.executionTimeMs()));
				}
			}
			for (KeyRotationRow rotation : condensedRotations) {
				long rotationTs = rotation.timestamp();
				double minutes = (rotationTs - base) / 60000.0;
				double y = findNearestExecutionTime(rotationTs, timelinePerformanceRows, fallbackY);
				if (Double.isFinite(minutes) && Double.isFinite(y) && minutes >= 0 && minutes <= MAX_REASONABLE_MINUTES) {
					XYChart.Data<Number, Number> point = new XYChart.Data<>(minutes, y);
					rotationSeries.getData().add(point);
					plottedRotationRows.add(rotation);
				}
			}
		} else if (!validKeyRotations.isEmpty()) {
			yAxis.setLabel("Key Version");
			long base = validKeyRotations.get(0).timestamp();
			for (KeyRotationRow rotation : validKeyRotations) {
				long rotationTs = rotation.timestamp();
				double minutes = (rotationTs - base) / 60000.0;
				double y = Math.max(1, rotation.keyVersion());
				if (Double.isFinite(minutes) && Double.isFinite(y) && minutes >= 0 && minutes <= MAX_REASONABLE_MINUTES) {
					rotationSeries.getData().add(new XYChart.Data<>(minutes, y));
					plottedRotationRows.add(rotation);
				}
			}
		} else {
			yAxis.setLabel("Execution Time (ms)");
		}

		List<XYChart.Series<Number, Number>> chartSeries = new ArrayList<>();
		if (!pqcSeries.getData().isEmpty()) {
			chartSeries.add(pqcSeries);
		}
		if (!classicalSeries.getData().isEmpty()) {
			chartSeries.add(classicalSeries);
		}
		if (!fallbackSeries.getData().isEmpty()) {
			chartSeries.add(fallbackSeries);
		}
		chartSeries.add(rotationSeries);
		rotationPerformanceChart.getData().setAll(chartSeries);
		int executionPointCount = pqcSeries.getData().size() + classicalSeries.getData().size() + fallbackSeries.getData().size();
		boolean hasRotationData = executionPointCount > 0 || !rotationSeries.getData().isEmpty();
		rotationNoDataLabel.setVisible(!hasRotationData);
		rotationNoDataLabel.setManaged(!hasRotationData);
		rotationSummaryLabel.setText(hasRotationData
				? (executionPointCount == 0
					? String.format("Loaded %d rotation markers. Performance metrics will appear after the run completes.", rotationSeries.getData().size())
					: String.format("Loaded %d PQC points, %d Classical points, and %d/%d rotation markers.",
							pqcSeries.getData().size(), classicalSeries.getData().size(), rotationSeries.getData().size(), validKeyRotations.size()))
				: "No valid key rotation timeline data yet.");
		if (!hasRotationData) {
			setDefaultAxisRange(rotationPerformanceChart, 0, 10, 0, 100);
		} else {
			setAxisAutoRange(rotationPerformanceChart);
		}
		Platform.runLater(() -> {
			stylePerformanceSeriesSymbols(pqcSeries);
			stylePerformanceSeriesSymbols(classicalSeries);
			stylePerformanceSeriesSymbols(fallbackSeries);
			styleRotationMarkerSeries(rotationSeries);
			installRotationTooltips(rotationSeries, plottedRotationRows);
		});
	}

	private void styleExecutionSeriesLine(XYChart.Series<Number, Number> series, String colorHex) {
		if (series.getNode() != null) {
			series.getNode().setStyle("-fx-stroke: " + colorHex + "; -fx-stroke-width: 2.2px;");
		} else {
			series.nodeProperty().addListener((observable, oldNode, newNode) -> {
				if (newNode != null) {
					newNode.setStyle("-fx-stroke: " + colorHex + "; -fx-stroke-width: 2.2px;");
				}
			});
		}
	}

	private boolean isPqcOperation(String operation) {
		if (operation == null) {
			return false;
		}
		String normalized = operation.toLowerCase();
		if (!isExecutionTimeOperation(normalized)) {
			return false;
		}
		return normalized.contains("pqc") || normalized.contains("kyber") || normalized.contains("dilithium");
	}

	private boolean isClassicalOperation(String operation) {
		if (operation == null) {
			return false;
		}
		String normalized = operation.toLowerCase();
		if (!isExecutionTimeOperation(normalized)) {
			return false;
		}
		return normalized.contains("classical") || normalized.contains("rsa") || normalized.contains("ecc");
	}

	private boolean isExecutionTimeOperation(String operation) {
		if (operation == null || operation.isBlank()) {
			return false;
		}
		String normalized = operation.toLowerCase();
		if (normalized.contains("overhead") || normalized.contains("percent")
				|| normalized.contains("cpu") || normalized.contains("memory")) {
			return false;
		}
		return normalized.contains("encrypt")
				|| normalized.contains("execution")
				|| normalized.contains("latency")
				|| normalized.contains("stress");
	}

	private void stylePerformanceSeriesSymbols(XYChart.Series<Number, Number> performanceSeries) {
		for (XYChart.Data<Number, Number> point : performanceSeries.getData()) {
			if (point.getNode() != null) {
				point.getNode().setStyle("-fx-background-color: transparent; -fx-padding: 0px;");
			} else {
				point.nodeProperty().addListener((observable, oldNode, newNode) -> {
					if (newNode != null) {
						newNode.setStyle("-fx-background-color: transparent; -fx-padding: 0px;");
					}
				});
			}
		}
	}

	private void styleRotationMarkerSeries(XYChart.Series<Number, Number> rotationSeries) {
		if (rotationSeries.getNode() != null) {
			rotationSeries.getNode().setStyle("-fx-stroke: transparent;");
		}
		for (XYChart.Data<Number, Number> point : rotationSeries.getData()) {
			if (point.getNode() != null) {
				applyRotationMarkerStyle(point.getNode());
			} else {
				point.nodeProperty().addListener((observable, oldNode, newNode) -> {
					if (newNode != null) {
						applyRotationMarkerStyle(newNode);
					}
				});
			}
		}
	}

	private void applyRotationMarkerStyle(Node node) {
		node.setStyle("-fx-background-color: #57b757; -fx-background-radius: 1px; -fx-padding: 12px 1.6px 12px 1.6px;");
	}

	private List<KeyRotationRow> condenseRotationMarkers(List<KeyRotationRow> rotations, long baseTimestamp) {
		if (rotations.size() <= ROTATION_MARKER_MAX_POINTS) {
			return rotations;
		}

		long lastTimestamp = rotations.get(rotations.size() - 1).timestamp();
		long spanMillis = Math.max(1, lastTimestamp - baseTimestamp);
		long bucketSizeMillis = Math.max(ROTATION_MARKER_MIN_BUCKET_MS, spanMillis / ROTATION_MARKER_MAX_POINTS);

		LinkedHashMap<Long, KeyRotationRow> buckets = new LinkedHashMap<>();
		for (KeyRotationRow row : rotations) {
			long bucketIndex = (row.timestamp() - baseTimestamp) / bucketSizeMillis;
			buckets.putIfAbsent(bucketIndex, row);
		}
		return new ArrayList<>(buckets.values());
	}

	private List<PerformanceRow> aggregatePerformanceRows(List<PerformanceRow> rows, long baseTimestamp) {
		if (rows.size() <= PERFORMANCE_TIMELINE_TARGET_POINTS) {
			return rows;
		}

		long first = rows.get(0).timestamp();
		long last = rows.get(rows.size() - 1).timestamp();
		long spanMillis = Math.max(1, last - first);
		long bucketSizeMillis = Math.max(PERFORMANCE_TIMELINE_MIN_BUCKET_MS, spanMillis / PERFORMANCE_TIMELINE_TARGET_POINTS);

		Map<Long, double[]> buckets = new LinkedHashMap<>();
		for (PerformanceRow row : rows) {
			long bucketIndex = (row.timestamp() - baseTimestamp) / bucketSizeMillis;
			double[] aggregate = buckets.computeIfAbsent(bucketIndex, ignored -> new double[] {0.0, 0.0, 0.0});
			aggregate[0] += row.executionTimeMs();
			aggregate[1] += row.cpuUsagePercent();
			aggregate[2] += 1.0;
		}

		List<PerformanceRow> aggregated = new ArrayList<>(buckets.size());
		for (Map.Entry<Long, double[]> entry : buckets.entrySet()) {
			double[] aggregate = entry.getValue();
			double count = Math.max(1.0, aggregate[2]);
			long bucketTimestamp = baseTimestamp + (entry.getKey() * bucketSizeMillis);
			aggregated.add(new PerformanceRow(
					"Aggregated",
					Math.round(aggregate[0] / count),
					aggregate[1] / count,
					bucketTimestamp));
		}
		return aggregated;
	}

	private List<PerformanceRow> collapseDuplicatePerformanceTimestamps(List<PerformanceRow> rows) {
		if (rows.size() <= 1) {
			return rows;
		}

		Map<Long, double[]> buckets = new LinkedHashMap<>();
		for (PerformanceRow row : rows) {
			double[] aggregate = buckets.computeIfAbsent(row.timestamp(), ignored -> new double[] {0.0, 0.0, 0.0});
			aggregate[0] += row.executionTimeMs();
			aggregate[1] += row.cpuUsagePercent();
			aggregate[2] += 1.0;
		}

		List<PerformanceRow> collapsed = new ArrayList<>(buckets.size());
		for (Map.Entry<Long, double[]> entry : buckets.entrySet()) {
			double[] aggregate = entry.getValue();
			double count = Math.max(1.0, aggregate[2]);
			collapsed.add(new PerformanceRow(
					"Collapsed",
					Math.round(aggregate[0] / count),
					aggregate[1] / count,
					entry.getKey()));
		}
		return collapsed;
	}

	private long normalizeEpochMillis(long value) {
		if (value <= 0) {
			return -1;
		}
		if (value < 100_000_000_000L) {
			return value * 1000;
		}
		return value;
	}

	private String buildRotationChartSignature(List<PerformanceRow> performanceRows, List<KeyRotationRow> keyRotations) {
		long performanceHash = 17;
		for (PerformanceRow row : performanceRows) {
			performanceHash = 31 * performanceHash + normalizeEpochMillis(row.timestamp());
			performanceHash = 31 * performanceHash + Double.doubleToLongBits(row.executionTimeMs());
		}

		long rotationHash = 17;
		for (KeyRotationRow row : keyRotations) {
			rotationHash = 31 * rotationHash + normalizeEpochMillis(row.timestamp());
			rotationHash = 31 * rotationHash + row.keyVersion();
		}

		return activeRunId + "|p=" + performanceRows.size() + "|k=" + keyRotations.size()
				+ "|ph=" + performanceHash + "|kh=" + rotationHash;
	}

	private List<ScalabilityRow> deriveScalabilityFallback(List<PerformanceRow> performanceRows) {
		List<ScalabilityRow> fallbackRows = new ArrayList<>();
		int startIndex = Math.max(0, performanceRows.size() - SCALABILITY_FALLBACK_MAX_POINTS);
		int deviceCount = 10;
		for (int index = startIndex; index < performanceRows.size(); index++) {
			PerformanceRow row = performanceRows.get(index);
			double latency = row.executionTimeMs();
			double cpu = row.cpuUsagePercent();
			if (!Double.isFinite(latency) || latency < 0 || !Double.isFinite(cpu) || cpu < 0) {
				continue;
			}
			fallbackRows.add(new ScalabilityRow(deviceCount, latency, Math.min(cpu, 100.0), row.timestamp()));
			deviceCount += 10;
		}
		return fallbackRows;
	}

	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}

	private <T> double measureMs(ThrowingSupplier<T> operation) throws Exception {
		long startNs = System.nanoTime();
		operation.get();
		return (System.nanoTime() - startNs) / 1_000_000.0;
	}

	private <T> TimedResult<T> measureTimed(ThrowingSupplier<T> operation) throws Exception {
		long startNs = System.nanoTime();
		T value = operation.get();
		double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
		return new TimedResult<>(value, elapsedMs);
	}

	private Label createNoDataOverlayLabel(String text) {
		Label label = new Label(text);
		label.setStyle("-fx-background-color: rgba(255,255,255,0.85); -fx-text-fill: #475569; -fx-font-size: 15px; -fx-font-weight: 700; -fx-padding: 12 16 12 16; -fx-background-radius: 12;");
		label.setVisible(false);
		label.setManaged(false);
		return label;
	}

	private void installRotationTooltips(XYChart.Series<Number, Number> series, List<KeyRotationRow> keyRotations) {
		for (int index = 0; index < series.getData().size() && index < keyRotations.size(); index++) {
			XYChart.Data<Number, Number> point = series.getData().get(index);
			KeyRotationRow row = keyRotations.get(index);
			if (point.getNode() != null) {
				Tooltip.install(point.getNode(), new Tooltip(
						row.deviceId() + "\nVersion " + row.keyVersion() + "\n" + row.rotationReason()));
				applyRotationMarkerStyle(point.getNode());
			} else {
				point.nodeProperty().addListener((observable, oldNode, newNode) -> {
					if (newNode != null) {
						Tooltip.install(newNode, new Tooltip(
								row.deviceId() + "\nVersion " + row.keyVersion() + "\n" + row.rotationReason()));
						applyRotationMarkerStyle(newNode);
					}
				});
			}
		}
	}

	private double findNearestExecutionTime(long timestamp, List<PerformanceRow> rows, double fallbackY) {
		long bestDelta = Long.MAX_VALUE;
		double bestValue = fallbackY;
		for (PerformanceRow row : rows) {
			long delta = Math.abs(row.timestamp() - timestamp);
			if (delta < bestDelta) {
				bestDelta = delta;
				bestValue = row.executionTimeMs();
			}
		}
		return bestValue;
	}

	private List<String> queryDeviceIds(Connection connection, String runId) throws Exception {
		List<String> deviceIds = new ArrayList<>();
		String sql = "SELECT DISTINCT device_id FROM sensor_data WHERE run_id = ? ORDER BY device_id";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, runId);
			try (ResultSet resultSet = statement.executeQuery()) {
			while (resultSet.next()) {
				deviceIds.add(resultSet.getString("device_id"));
			}
			}
		}
		return deviceIds;
	}

	private List<SensorReadingRow> querySensorReadings(Connection connection, String selectedDevice, String runId) throws Exception {
		List<SensorReadingRow> rows = new ArrayList<>();
		boolean allDevices = selectedDevice == null || selectedDevice.isBlank() || "All Devices".equals(selectedDevice);
		String sql = allDevices
				? "SELECT device_id, sensor_value, timestamp FROM sensor_data WHERE run_id = ? ORDER BY timestamp DESC LIMIT ?"
				: "SELECT device_id, sensor_value, timestamp FROM sensor_data WHERE run_id = ? AND device_id = ? ORDER BY timestamp DESC LIMIT ?";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			if (allDevices) {
				statement.setString(1, runId);
				statement.setInt(2, SENSOR_POINTS_LIMIT);
			} else {
				statement.setString(1, runId);
				statement.setString(2, selectedDevice);
				statement.setInt(3, SENSOR_POINTS_LIMIT);
			}
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					rows.add(new SensorReadingRow(
							resultSet.getString("device_id"),
							resultSet.getDouble("sensor_value"),
							resultSet.getLong("timestamp")));
				}
			}
		}
		if (!allDevices && rows.isEmpty()) {
			try (PreparedStatement fallbackStatement = connection.prepareStatement("SELECT device_id, sensor_value, timestamp FROM sensor_data WHERE run_id = ? ORDER BY timestamp DESC LIMIT ?")) {
				fallbackStatement.setString(1, runId);
				fallbackStatement.setInt(2, SENSOR_POINTS_LIMIT);
				try (ResultSet resultSet = fallbackStatement.executeQuery()) {
					while (resultSet.next()) {
						rows.add(new SensorReadingRow(
								resultSet.getString("device_id"),
								resultSet.getDouble("sensor_value"),
								resultSet.getLong("timestamp")));
					}
				}
			}
		}
		rows.sort((left, right) -> Long.compare(left.timestamp(), right.timestamp()));
		return rows;
	}

	private List<AnomalyRow> queryAnomalies(Connection connection, String runId) throws Exception {
		List<AnomalyRow> rows = new ArrayList<>();
		String sql = "SELECT device_id, sensor_value, anomaly_score, detection_method, timestamp FROM anomaly_events WHERE run_id = ? AND is_anomaly = true ORDER BY timestamp DESC LIMIT ?";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, runId);
			statement.setInt(2, TABLE_LIMIT);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					rows.add(new AnomalyRow(
							resultSet.getString("device_id"),
							resultSet.getDouble("sensor_value"),
							resultSet.getDouble("anomaly_score"),
							resultSet.getString("detection_method"),
							resultSet.getLong("timestamp")));
				}
			}
		}
		return rows;
	}

	private List<ScalabilityRow> queryScalability(Connection connection, String runId) throws Exception {
		Map<Integer, ScalabilityRow> deduped = new LinkedHashMap<>();
		String sql = "SELECT device_count, avg_latency_ms, cpu_usage_percent, timestamp FROM scalability_results WHERE run_id = ? ORDER BY device_count, timestamp";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, runId);
			try (ResultSet resultSet = statement.executeQuery()) {
			while (resultSet.next()) {
				deduped.put(resultSet.getInt("device_count"), new ScalabilityRow(
						resultSet.getInt("device_count"),
						resultSet.getDouble("avg_latency_ms"),
						resultSet.getDouble("cpu_usage_percent"),
						resultSet.getLong("timestamp")));
			}
			}
		}
		return new ArrayList<>(deduped.values());
	}

	private List<PerformanceRow> queryPerformance(Connection connection, String runId) throws Exception {
		List<PerformanceRow> rows = new ArrayList<>();
		String sql = "SELECT operation, execution_time_ms, cpu_usage_percent, timestamp FROM performance_metrics WHERE run_id = ? ORDER BY timestamp DESC LIMIT ?";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, runId);
			statement.setInt(2, TABLE_LIMIT);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					rows.add(new PerformanceRow(
							resultSet.getString("operation"),
							resultSet.getLong("execution_time_ms"),
							resultSet.getDouble("cpu_usage_percent"),
							resultSet.getLong("timestamp")));
				}
			}
		}
		rows.sort((left, right) -> Long.compare(left.timestamp(), right.timestamp()));
		return rows;
	}

	private List<KeyRotationRow> queryKeyRotations(Connection connection, String runId) throws Exception {
		List<KeyRotationRow> rows = new ArrayList<>();
		String sql = "SELECT device_id, key_version, rotation_reason, mode_name, timestamp FROM key_rotation_logs WHERE run_id = ? ORDER BY timestamp DESC LIMIT ?";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, runId);
			statement.setInt(2, TABLE_LIMIT);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					rows.add(new KeyRotationRow(
							resultSet.getString("device_id"),
							resultSet.getInt("key_version"),
							resultSet.getString("rotation_reason"),
							resultSet.getString("mode_name"),
							resultSet.getLong("timestamp")));
				}
			}
		}
		rows.sort((left, right) -> Long.compare(left.timestamp(), right.timestamp()));
		return rows;
	}

	private List<SecurityLogRow> querySecurityLogs(Connection connection, String runId) throws Exception {
		List<SecurityLogRow> rows = new ArrayList<>();
		String sql = "SELECT event_type, event_data, log_hash, previous_hash, timestamp FROM system_logs WHERE run_id = ? ORDER BY id DESC LIMIT ?";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, runId);
			statement.setInt(2, TABLE_LIMIT);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					rows.add(new SecurityLogRow(
							resultSet.getString("event_type"),
							resultSet.getString("event_data"),
							resultSet.getString("log_hash"),
							resultSet.getString("previous_hash"),
							resultSet.getLong("timestamp")));
				}
			}
		}
		return rows;
	}

	private LineChart<Number, Number> createLineChart(String title, String xLabel, String yLabel) {
		NumberAxis xAxis = new NumberAxis();
		xAxis.setLabel(xLabel);
		NumberAxis yAxis = new NumberAxis();
		yAxis.setLabel(yLabel);
		LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
		chart.setTitle(title);
		chart.setAnimated(false);
		chart.setCreateSymbols(false);
		chart.setLegendVisible(true);
		chart.setStyle("-fx-background-color: white; -fx-padding: 10; -fx-background-radius: 16; -fx-border-radius: 16; -fx-border-color: #d8e2eb;");
		return chart;
	}

	private BarChart<String, Number> createBarChart() {
		CategoryAxis xAxis = new CategoryAxis();
		xAxis.setLabel("Operation");
		NumberAxis yAxis = new NumberAxis();
		yAxis.setLabel("Execution Time (ms)");
		BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
		chart.setAnimated(false);
		chart.setCategoryGap(26);
		chart.setBarGap(8);
		chart.setLegendVisible(true);
		chart.setStyle("-fx-background-color: white; -fx-padding: 12; -fx-background-radius: 16; -fx-border-radius: 16; -fx-border-color: #d8e2eb;");
		return chart;
	}

	private TableView<AnomalyRow> createAnomalyTable() {
		TableView<AnomalyRow> table = new TableView<>();
		table.getColumns().add(stringColumn("Device", AnomalyRow::deviceId, 130));
		table.getColumns().add(numberColumn("Sensor", AnomalyRow::sensorValue, 120));
		table.getColumns().add(numberColumn("Score", AnomalyRow::score, 120));
		table.getColumns().add(stringColumn("Method", AnomalyRow::detectionMethod, 160));
		table.getColumns().add(stringColumn("Timestamp", row -> formatTimestamp(row.timestamp()), 180));
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		return table;
	}

	private TableView<SecurityLogRow> createLogTable() {
		TableView<SecurityLogRow> table = new TableView<>();
		table.getColumns().add(stringColumn("Event", SecurityLogRow::eventType, 140));
		table.getColumns().add(stringColumn("Payload", SecurityLogRow::eventData, 360));
		table.getColumns().add(stringColumn("Hash", row -> abbreviate(row.logHash()), 240));
		table.getColumns().add(stringColumn("Previous", row -> abbreviate(row.previousHash()), 240));
		table.getColumns().add(stringColumn("Timestamp", row -> formatTimestamp(row.timestamp()), 180));
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		return table;
	}

	private <T> TableColumn<T, String> stringColumn(String title, StringValueExtractor<T> extractor, double width) {
		TableColumn<T, String> column = new TableColumn<>(title);
		column.setCellValueFactory(cell -> new SimpleStringProperty(extractor.extract(cell.getValue())));
		column.setPrefWidth(width);
		return column;
	}

	private <T> TableColumn<T, Number> numberColumn(String title, DoubleValueExtractor<T> extractor, double width) {
		TableColumn<T, Number> column = new TableColumn<>(title);
		column.setCellValueFactory(cell -> new SimpleDoubleProperty(extractor.extract(cell.getValue())));
		column.setPrefWidth(width);
		return column;
	}

	private Label createSectionTitle(String text) {
		Label label = new Label(text);
		label.setFont(Font.font("Segoe UI Semibold", 18));
		label.setStyle("-fx-text-fill: #19324d;");
		return label;
	}

	private HBox createInfoStrip(Label label, String bg, String fg) {
		label.setStyle("-fx-text-fill: " + fg + "; -fx-font-size: 13px;");
		label.setWrapText(true);
		HBox box = new HBox(label);
		box.setPadding(new Insets(10, 14, 10, 14));
		box.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 14;");
		return box;
	}

	private VBox createCardPane(javafx.scene.Node... nodes) {
		VBox box = new VBox(14, nodes);
		box.setPadding(new Insets(18));
		box.setStyle("-fx-background-color: white; -fx-background-radius: 18; -fx-border-color: #d8e2eb; -fx-border-radius: 18;");
		return box;
	}

	private VBox createStatusCard(String title, Label valueLabel) {
		Label titleLabel = new Label(title);
		titleLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px; -fx-font-weight: 700;");
		valueLabel.setStyle("-fx-text-fill: #19324d; -fx-font-size: 18px; -fx-font-weight: 700;");
		VBox box = new VBox(8, titleLabel, valueLabel);
		box.setPadding(new Insets(14));
		box.setPrefWidth(120);
		box.setMinWidth(110);
		box.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(box, Priority.ALWAYS);
		box.setStyle("-fx-background-color: #f8fbff; -fx-background-radius: 16; -fx-border-color: #d8e2eb; -fx-border-radius: 16;");
		return box;
	}

	private Label decorateBadge(Label label, String bg, String fg) {
		label.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; -fx-padding: 8 12 8 12; -fx-background-radius: 999;");
		return label;
	}

	private String buttonStyle(String color) {
		return "-fx-background-color: " + color + "; -fx-text-fill: white; -fx-font-size: 13px; -fx-font-weight: 700; -fx-padding: 10 16 10 16; -fx-background-radius: 999;";
	}

	private String formatDuration(long elapsedSeconds) {
		long minutes = elapsedSeconds / 60;
		long seconds = elapsedSeconds % 60;
		return String.format("%02d:%02d", minutes, seconds);
	}

	private String formatTimestamp(long timestamp) {
		return Instant.ofEpochMilli(timestamp)
				.atZone(ZoneId.systemDefault())
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	}

	private String abbreviate(String value) {
		if (value == null || value.isBlank()) {
			return "-";
		}
		return value.length() <= 18 ? value : value.substring(0, 10) + "..." + value.substring(value.length() - 6);
	}

	private Tab createTab(String title, javafx.scene.Node node) {
		Tab tab = new Tab(title, node);
		tab.setClosable(false);
		return tab;
	}

	@FunctionalInterface
	private interface StringValueExtractor<T> {
		String extract(T value);
	}

	@FunctionalInterface
	private interface DoubleValueExtractor<T> {
		double extract(T value);
	}

	@FunctionalInterface
	private interface SqlTask {
		void run() throws Exception;
	}

	private static final class DashboardSnapshot {
		private List<String> devices = new ArrayList<>();
		private List<SensorReadingRow> sensorReadings = new ArrayList<>();
		private List<AnomalyRow> anomalies = new ArrayList<>();
		private List<ScalabilityRow> scalabilityPoints = new ArrayList<>();
		private List<PerformanceRow> performancePoints = new ArrayList<>();
		private List<KeyRotationRow> keyRotations = new ArrayList<>();
		private List<SecurityLogRow> securityLogs = new ArrayList<>();
		private boolean scalabilityDerivedFromPerformance = false;
	}

	private record SensorReadingRow(String deviceId, double sensorValue, long timestamp) {}

	private record AnomalyRow(String deviceId, double sensorValue, double score, String detectionMethod, long timestamp) {}

	private record ScalabilityRow(int deviceCount, double avgLatencyMs, double cpuUsagePercent, long timestamp) {}

	private record PerformanceRow(String operation, long executionTimeMs, double cpuUsagePercent, long timestamp) {}

	private record KeyRotationRow(String deviceId, int keyVersion, String rotationReason, String modeName, long timestamp) {}

	private record SecurityLogRow(String eventType, String eventData, String logHash, String previousHash, long timestamp) {}

	private record TimedResult<T>(T value, double elapsedMs) {}
}
