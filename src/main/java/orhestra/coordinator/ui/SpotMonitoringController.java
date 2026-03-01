package orhestra.coordinator.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import orhestra.coordinator.config.Dependencies;
import orhestra.coordinator.core.AppBus;
import orhestra.coordinator.model.Task;
import orhestra.coordinator.server.CoordinatorNettyServer;
import orhestra.coordinator.simulation.SimulationService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

public class SpotMonitoringController {

    @FXML private FlowPane cardsPane;
    @FXML private Label lblTotal;
    @FXML private Label lblUp;

    // Simulation controls
    @FXML private Spinner<Integer> simWorkers;
    @FXML private TextField simDelayMin, simDelayMax, simFailRate;
    @FXML private Button btnStartSim, btnStopSim;

    private final ObservableList<SpotInfo> data = FXCollections.observableArrayList();
    private Timer retryTimer;
    private SimulationService simulationService;

    @FXML
    private void initialize() {
        lblTotal.textProperty().bind(Bindings.size(data).asString());

        if (simWorkers != null) {
            simWorkers.setValueFactory(
                    new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 200, 20));
        }

        AppBus.onSpotsChanged(() -> Platform.runLater(this::refreshSpots));
        refreshSpots();
    }

    private void refreshSpots() {
        Dependencies deps = CoordinatorNettyServer.tryDependencies();
        if (deps == null) {
            data.clear();
            cardsPane.getChildren().clear();
            Label placeholder = new Label("Сервер ещё не запущен. Ожидание…");
            placeholder.getStyleClass().add("muted");
            cardsPane.getChildren().add(placeholder);
            scheduleRetry();
            return;
        }

        cancelRetryTimer();

        List<SpotInfo> snap = deps.spotService().findAll().stream()
                .map(this::toSpotInfo)
                .collect(Collectors.toList());

        data.setAll(new ArrayList<>(snap));

        long upCount = snap.stream()
                .filter(s -> "UP".equalsIgnoreCase(s.status()))
                .count();
        if (lblUp != null) lblUp.setText(String.valueOf(upCount));

        cardsPane.getChildren().clear();
        if (snap.isEmpty()) {
            Label empty = new Label("Нет подключённых SPOT-воркеров. Ожидание heartbeat…");
            empty.getStyleClass().add("muted");
            empty.setStyle("-fx-font-size: 14;");
            cardsPane.getChildren().add(empty);
        } else {
            for (SpotInfo spot : snap) {
                List<Task> running = deps.taskService().findRunningForSpot(spot.spotId());
                cardsPane.getChildren().add(buildCard(spot, running));
            }
        }
    }

    // ---- Card builder ----

    private VBox buildCard(SpotInfo spot, List<Task> runningTasks) {
        boolean isDown = !"UP".equalsIgnoreCase(spot.status());
        boolean isOverloaded = spot.cpuLoad() != null && spot.cpuLoad() > 80;
        boolean isWorking = spot.runningTasks() != null && spot.runningTasks() > 0;

        VBox card = new VBox(7);
        card.setPrefWidth(240);
        card.setPadding(new Insets(12));
        card.getStyleClass().add("spot-card");

        if (isDown) card.getStyleClass().add("spot-card-down");
        else if (isOverloaded) card.getStyleClass().add("spot-card-overloaded");
        else if (isWorking) card.getStyleClass().add("spot-card-working");
        else card.getStyleClass().add("spot-card-idle");

        card.getChildren().addAll(
                buildHeader(spot, isDown),
                new Separator(),
                buildCpuRow(spot),
                buildRamRow(spot),
                new Separator(),
                buildTasksSection(spot, runningTasks)
        );

        return card;
    }

    private HBox buildHeader(SpotInfo spot, boolean isDown) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);

        Label dot = new Label("●");
        dot.setStyle("-fx-font-size: 10;");
        dot.getStyleClass().add(isDown ? "spot-status-down" : "spot-status-up");

        Label statusLbl = new Label(isDown ? "DOWN" : "UP");
        statusLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 11;");
        statusLbl.getStyleClass().add(isDown ? "spot-status-down" : "spot-status-up");

        Label idLbl = new Label(shortenId(spot.spotId()));
        idLbl.getStyleClass().add("spot-card-id");
        HBox.setHgrow(idLbl, Priority.ALWAYS);
        idLbl.setMaxWidth(Double.MAX_VALUE);

        String ageStr = spot.lastSeen() == null ? "—"
                : Duration.between(spot.lastSeen(), Instant.now()).getSeconds() + "s ago";
        Label ageLbl = new Label(ageStr);
        ageLbl.getStyleClass().add("spot-card-beat");

        row.getChildren().addAll(dot, statusLbl, idLbl, ageLbl);
        return row;
    }

    private VBox buildCpuRow(SpotInfo spot) {
        double pct = spot.cpuLoad() != null ? spot.cpuLoad() : 0.0;
        return buildProgressRow("CPU", pct, String.format("%.0f%%", pct), cpuStyleClass(pct));
    }

    private VBox buildRamRow(SpotInfo spot) {
        if (spot.ramTotalMb() == null || spot.ramTotalMb() == 0) {
            return buildProgressRow("RAM", 0, "—", "cpu-low");
        }
        double pct = (double) spot.ramUsedMb() / spot.ramTotalMb() * 100.0;
        String label = spot.ramUsedMb() + " / " + spot.ramTotalMb() + " MB";
        return buildProgressRow("RAM", pct, label, cpuStyleClass(pct));
    }

    private VBox buildProgressRow(String name, double pct, String rightLabel, String barStyle) {
        VBox box = new VBox(3);

        HBox labelRow = new HBox();
        labelRow.setAlignment(Pos.CENTER_LEFT);
        Label nameLbl = new Label(name);
        nameLbl.getStyleClass().add("spot-card-section-title");
        nameLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #aaa; -fx-font-size: 10;");

        Label valLbl = new Label(rightLabel);
        valLbl.getStyleClass().add("spot-card-metric");
        valLbl.setStyle("-fx-font-size: 10; -fx-text-fill: #bbb;");
        HBox.setHgrow(nameLbl, Priority.ALWAYS);
        nameLbl.setMaxWidth(Double.MAX_VALUE);

        labelRow.getChildren().addAll(nameLbl, valLbl);

        ProgressBar bar = new ProgressBar(Math.max(0, Math.min(1, pct / 100.0)));
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(8);
        bar.getStyleClass().addAll("spot-cpu-bar", barStyle);

        box.getChildren().addAll(labelRow, bar);
        return box;
    }

    private VBox buildTasksSection(SpotInfo spot, List<Task> runningTasks) {
        VBox section = new VBox(5);

        // Header row: "Tasks"  running / maxConcurrent / 0
        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);

        Label tasksTitle = new Label("Tasks");
        tasksTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #ccc; -fx-font-size: 12;");
        HBox.setHgrow(tasksTitle, Priority.ALWAYS);
        tasksTitle.setMaxWidth(Double.MAX_VALUE);

        int running = spot.runningTasks() != null ? spot.runningTasks() : 0;
        int maxC = spot.maxConcurrent() != null && spot.maxConcurrent() > 0
                ? spot.maxConcurrent() : running;

        Label runLbl = new Label(String.valueOf(running));
        runLbl.getStyleClass().add("spot-stat-running");

        Label sep1 = new Label(" / ");
        sep1.setStyle("-fx-text-fill: #666;");

        Label totalLbl = new Label(String.valueOf(maxC));
        totalLbl.setStyle("-fx-font-size: 11; -fx-text-fill: #888; -fx-font-weight: bold;");

        Label sep2 = new Label(" / ");
        sep2.setStyle("-fx-text-fill: #666;");

        Label failedLbl = new Label("0");
        failedLbl.getStyleClass().add("spot-stat-failed");

        header.getChildren().addAll(tasksTitle, runLbl, sep1, totalLbl, sep2, failedLbl);

        Label subTitle = new Label("current tasks");
        subTitle.setStyle("-fx-text-fill: #555; -fx-font-size: 10;");
        subTitle.setAlignment(Pos.CENTER_RIGHT);
        subTitle.setMaxWidth(Double.MAX_VALUE);

        section.getChildren().addAll(header, subTitle);

        if (runningTasks.isEmpty() && running == 0) {
            Label idle = new Label("idle");
            idle.setStyle("-fx-text-fill: #555; -fx-font-size: 11; -fx-font-style: italic;");
            section.getChildren().add(idle);
        } else {
            for (Task t : runningTasks) {
                section.getChildren().add(buildTaskRow(t));
            }
        }

        return section;
    }

    private HBox buildTaskRow(Task t) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);

        long ageMs = t.startedAt() != null
                ? Duration.between(t.startedAt(), Instant.now()).toSeconds() : 0;

        String algo = t.algorithm() != null ? t.algorithm() : "—";
        String shortId = t.id().length() > 8 ? t.id().substring(t.id().length() - 6) : t.id();

        Label algoLbl = new Label(algo);
        algoLbl.setStyle("-fx-text-fill: #e6a817; -fx-font-size: 10; -fx-font-weight: bold; -fx-min-width: 40;");

        Label idLbl = new Label(shortId);
        idLbl.setStyle("-fx-text-fill: #999; -fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 10;");
        HBox.setHgrow(idLbl, Priority.ALWAYS);
        idLbl.setMaxWidth(Double.MAX_VALUE);

        Label timeLbl = new Label(ageMs + "s ●");
        timeLbl.setStyle("-fx-text-fill: #e6a817; -fx-font-size: 10;");

        row.getChildren().addAll(algoLbl, idLbl, timeLbl);
        return row;
    }

    // ---- Helpers ----

    private String shortenId(String id) {
        if (id == null) return "—";
        // Use last segment after last '-' if it looks like "spot-abc123" → "Spot #abc123"
        int dash = id.lastIndexOf('-');
        String suffix = dash >= 0 && dash < id.length() - 1 ? id.substring(dash + 1) : id;
        return "Spot #" + suffix;
    }

    private String cpuStyleClass(double pct) {
        if (pct >= 80) return "cpu-high";
        if (pct >= 50) return "cpu-medium";
        return "cpu-low";
    }

    private SpotInfo toSpotInfo(orhestra.coordinator.model.Spot spot) {
        return new SpotInfo(
                spot.id(),
                spot.cpuLoad(),
                spot.runningTasks(),
                spot.status() != null ? spot.status().name() : "DOWN",
                spot.lastHeartbeat(),
                spot.totalCores(),
                spot.ipAddress(),
                spot.ramUsedMb(),
                spot.ramTotalMb(),
                spot.maxConcurrent());
    }

    // ---- Retry logic ----

    private void scheduleRetry() {
        if (retryTimer != null) return;
        retryTimer = new Timer("spot-monitor-retry", true);
        retryTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> {
                    retryTimer = null;
                    refreshSpots();
                });
            }
        }, 2000);
    }

    private void cancelRetryTimer() {
        if (retryTimer != null) {
            retryTimer.cancel();
            retryTimer = null;
        }
    }

    // ---- Simulation handlers ----

    @FXML
    private void handleStartSim() {
        Dependencies deps = CoordinatorNettyServer.tryDependencies();
        if (deps == null) {
            new Alert(Alert.AlertType.WARNING, "Server not started", ButtonType.OK).showAndWait();
            return;
        }

        int workers = simWorkers != null ? simWorkers.getValue() : 20;
        int delayMin = parseIntOr(simDelayMin, 200);
        int delayMax = parseIntOr(simDelayMax, 1200);
        double failRate = parseDoubleOr(simFailRate, 0.0);

        simulationService = new SimulationService(deps.spotService(), deps.taskService());
        simulationService.start(workers, delayMin, delayMax, failRate);

        if (btnStartSim != null) btnStartSim.setDisable(true);
        if (btnStopSim != null) btnStopSim.setDisable(false);
    }

    @FXML
    private void handleStopSim() {
        if (simulationService != null) {
            simulationService.stop();
            simulationService = null;
        }
        if (btnStartSim != null) btnStartSim.setDisable(false);
        if (btnStopSim != null) btnStopSim.setDisable(true);
    }

    private static int parseIntOr(TextField tf, int fallback) {
        if (tf == null || tf.getText() == null || tf.getText().isBlank()) return fallback;
        try { return Integer.parseInt(tf.getText().trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static double parseDoubleOr(TextField tf, double fallback) {
        if (tf == null || tf.getText() == null || tf.getText().isBlank()) return fallback;
        try { return Double.parseDouble(tf.getText().trim()); }
        catch (NumberFormatException e) { return fallback; }
    }
}
