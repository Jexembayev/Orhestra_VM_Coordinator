package orhestra.coordinator.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import orhestra.coordinator.config.Dependencies;
import orhestra.coordinator.core.AppBus;
import orhestra.coordinator.api.v1.dto.CreateJobRequest;
import orhestra.coordinator.model.ArtifactRef;
import orhestra.coordinator.model.Job;
import orhestra.coordinator.model.Spot;
import orhestra.coordinator.model.Task;
import orhestra.coordinator.model.TaskStatus;
import orhestra.coordinator.server.CoordinatorNettyServer;
import orhestra.coordinator.simulation.SimulationService;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Unified controller for the Execution tab.
 * Top: SPOT worker cards. Bottom: Task table with progress/SpotId.
 */
public class ExecutionController {

    // ---- Task table ----
    @FXML
    private TableView<TaskInfo> taskTable;
    @FXML
    private TableColumn<TaskInfo, String> idColumn, algColumn, funcColumn, runtimeColumn, statusColumn, progressColumn;
    @FXML
    private TableColumn<TaskInfo, String> iterColumn;
    @FXML
    private TableColumn<TaskInfo, String> agentsColumn, dimensionColumn;
    @FXML
    private TableColumn<TaskInfo, String> spotIdColumn;
    @FXML
    private TableColumn<TaskInfo, String> foptColumn;

    private static final com.fasterxml.jackson.databind.ObjectMapper PAYLOAD_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ---- Stats ----
    @FXML
    private Label lblNew, lblRunning, lblDone, lblFailed;

    // ---- Spot cards ----
    @FXML
    private HBox spotCards;
    @FXML
    private Label lblSpotCount;

    // ---- Simulation ----
    @FXML
    private Spinner<Integer> simWorkers;
    @FXML
    private TextField simDelayMin, simDelayMax, simFailRate;
    @FXML
    private Button btnStartSim, btnStopSim;

    // ---- JSON files ----
    @FXML
    private ComboBox<String> recentJsonCombo;

    private Timer retryTimer;
    private SimulationService simulationService;
    private final RecentJsonManager recentJson = new RecentJsonManager();

    // Guards preventing concurrent DB loads from piling up on the FX thread
    private final java.util.concurrent.atomic.AtomicBoolean tasksLoading = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean spotsLoading  = new java.util.concurrent.atomic.AtomicBoolean(false);

    @FXML
    private void initialize() {
        // ---- Task table columns ----
        idColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().id()));
        algColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().algId() != null ? c.getValue().algId() : "—"));
        funcColumn.setCellValueFactory(c -> new SimpleStringProperty(extractFunc(c.getValue().payload())));

        iterColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().inputIterations() == null ? "—" : String.valueOf(c.getValue().inputIterations())));

        if (agentsColumn != null) {
            agentsColumn.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().inputAgents() == null ? "—" : String.valueOf(c.getValue().inputAgents())));
        }
        if (dimensionColumn != null) {
            dimensionColumn.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().inputDimension() == null ? "—" : String.valueOf(c.getValue().inputDimension())));
        }
        if (spotIdColumn != null) {
            spotIdColumn.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().assignedTo() == null ? "—" : c.getValue().assignedTo()));
        }

        runtimeColumn.setCellValueFactory(c -> new SimpleStringProperty(formatRuntime(c.getValue().runtimeMs())));

        // Status badge
        statusColumn.setCellValueFactory(c -> new SimpleStringProperty(nz(c.getValue().status())));
        statusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String st, boolean empty) {
                super.updateItem(st, empty);
                if (empty || st == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label badge = new Label(st);
                badge.getStyleClass().add("status-badge");
                switch (st) {
                    case "DONE" -> badge.getStyleClass().add("status-done");
                    case "FAILED" -> badge.getStyleClass().add("status-failed");
                    case "RUNNING" -> badge.getStyleClass().add("status-running");
                    case "NEW" -> badge.getStyleClass().add("status-new");
                    default -> {
                    }
                }
                setGraphic(badge);
                setText(null);
            }
        });

        // Progress (status-aware)
        progressColumn.setCellValueFactory(c -> new SimpleStringProperty(
                calcProgress(c.getValue().status(), c.getValue().payload(), c.getValue().iter())));

        if (foptColumn != null) {
            foptColumn.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().fopt() != null ? String.format("%.6g", c.getValue().fopt()) : "—"));
        }

        // ---- Simulation spinner ----
        if (simWorkers != null) {
            simWorkers.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 200, 20));
        }

        // ---- Recent JSON ComboBox ----
        if (recentJsonCombo != null) {
            recentJsonCombo.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(String p, boolean empty) {
                    super.updateItem(p, empty);
                    setText(empty || p == null ? null : RecentJsonManager.displayName(p));
                }
            });
            recentJsonCombo.setButtonCell(new ListCell<>() {
                @Override
                protected void updateItem(String p, boolean empty) {
                    super.updateItem(p, empty);
                    setText(empty || p == null ? null : RecentJsonManager.displayName(p));
                }
            });
            refreshJsonCombo();
            String lastJson = recentJson.getLast();
            if (lastJson != null && new File(lastJson).isFile()) {
                recentJsonCombo.setValue(lastJson);
            }
        }

        // ---- Event listeners ----
        AppBus.onTasksChanged(() -> Platform.runLater(this::refreshTasks));
        AppBus.onSpotsChanged(() -> Platform.runLater(this::refreshSpots));

        // Initial load
        refreshAll();
    }

    // ================== Refresh ==================

    private void refreshAll() {
        refreshTasks();
        refreshSpots();
    }

    @FXML
    public void refreshTasks() {
        // Skip if a load is already in progress to avoid DB query pile-up on FX thread
        if (!tasksLoading.compareAndSet(false, true)) return;

        Thread t = new Thread(() -> {
            try {
                var deps = CoordinatorNettyServer.tryDependencies();
                if (deps == null) {
                    Platform.runLater(() -> {
                        taskTable.getItems().clear();
                        scheduleRetry();
                    });
                    return;
                }
                // DB work on background thread
                List<TaskInfo> items = deps.taskService().findRecent(200)
                        .stream().map(this::toTaskInfo).collect(Collectors.toList());

                // Apply to UI on FX thread — fast, no DB calls here
                Platform.runLater(() -> {
                    cancelRetryTimer();
                    taskTable.getItems().setAll(items);
                    updateStats(items);
                    taskTable.refresh();
                });
            } catch (Exception ignored) {
            } finally {
                tasksLoading.set(false);
            }
        }, "refresh-tasks");
        t.setDaemon(true);
        t.start();
    }

    private void refreshSpots() {
        // Skip if a load is already in progress
        if (!spotsLoading.compareAndSet(false, true)) return;

        Thread t = new Thread(() -> {
            try {
                Dependencies deps = CoordinatorNettyServer.tryDependencies();
                if (deps == null) return;

                // Sort by registration time so cards don't jump on heartbeat updates
                List<Spot> spots = deps.spotService().findAll().stream()
                        .sorted(java.util.Comparator
                                .comparing((Spot s) -> s.registeredAt() != null ? s.registeredAt() : Instant.EPOCH)
                                .thenComparing(Spot::id))
                        .collect(Collectors.toList());

                // Compute per-spot task stats (DB query on background thread)
                java.util.Map<String, SpotTaskStats> statsMap = new java.util.HashMap<>();
                List<Task> allTasks = deps.taskService().findRecent(500);
                for (Task task : allTasks) {
                    String sid = task.assignedTo();
                    if (sid == null) continue;
                    SpotTaskStats prev = statsMap.getOrDefault(sid, new SpotTaskStats(0, 0, 0));
                    int r = prev.running(), d = prev.done(), f = prev.failed();
                    if (task.status() == TaskStatus.RUNNING)      r++;
                    else if (task.status() == TaskStatus.DONE)    d++;
                    else if (task.status() == TaskStatus.FAILED)  f++;
                    statsMap.put(sid, new SpotTaskStats(r, d, f));
                }

                // Build cards off-thread, then swap atomically on FX thread (no flash)
                List<javafx.scene.Node> cards = new ArrayList<>();
                for (Spot spot : spots) {
                    SpotTaskStats stats = statsMap.getOrDefault(spot.id(), new SpotTaskStats(0, 0, 0));
                    cards.add(buildSpotCard(spot, stats));
                }
                final int spotCount = spots.size();

                Platform.runLater(() -> {
                    if (spotCards != null) {
                        spotCards.getChildren().setAll(cards); // atomic swap — no empty-frame flash
                    }
                    if (lblSpotCount != null) {
                        lblSpotCount.setText("Spots: " + spotCount);
                    }
                });
            } catch (Exception ignored) {
            } finally {
                spotsLoading.set(false);
            }
        }, "refresh-spots");
        t.setDaemon(true);
        t.start();
    }

    // ================== Spot Cards ==================

    private VBox buildSpotCard(Spot spot, SpotTaskStats stats) {
        VBox card = new VBox(0);
        card.setPrefWidth(270);
        card.setMinWidth(270);
        card.setMaxWidth(270);
        card.getStyleClass().add("spot-card");

        String status = spot.status() != null ? spot.status().name() : "DOWN";
        boolean isUp = "UP".equals(status);

        long ageSec = spot.lastHeartbeat() == null ? -1
                : Math.max(0, Duration.between(spot.lastHeartbeat(), Instant.now()).getSeconds());

        // Determine card color state
        double cpu = spot.cpuLoad();
        int running = stats.running();
        if (!isUp) {
            card.getStyleClass().add("spot-card-down");
        } else if (running == 0 && cpu < 20) {
            card.getStyleClass().add("spot-card-idle"); // green — idle
        } else if (cpu >= 80) {
            card.getStyleClass().add("spot-card-overloaded"); // red — overloaded
        } else {
            card.getStyleClass().add("spot-card-working"); // yellow — working
        }

        // === Header: ID + Status ===
        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 10, 4, 10));

        Label idLabel = new Label(spot.id());
        idLabel.getStyleClass().add("spot-card-id");

        Label statusBadge = new Label(isUp ? "● UP" : "● DOWN");
        statusBadge.getStyleClass().addAll("spot-card-status", isUp ? "spot-status-up" : "spot-status-down");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        String beatText = ageSec < 0 ? "—" : formatAge(ageSec);
        Label beatLabel = new Label(beatText);
        beatLabel.getStyleClass().add("spot-card-beat");

        header.getChildren().addAll(idLabel, statusBadge, spacer, beatLabel);

        // === Resources section ===
        VBox resources = new VBox(3);
        resources.setPadding(new Insets(4, 10, 4, 10));

        // CPU bar
        ProgressBar cpuBar = new ProgressBar(cpu / 100.0);
        cpuBar.setPrefWidth(250);
        cpuBar.setPrefHeight(12);
        cpuBar.getStyleClass().add("spot-cpu-bar");
        if (cpu >= 80)
            cpuBar.getStyleClass().add("cpu-high");
        else if (cpu >= 40)
            cpuBar.getStyleClass().add("cpu-medium");
        else
            cpuBar.getStyleClass().add("cpu-low");

        HBox cpuRow = new HBox(6);
        cpuRow.setAlignment(Pos.CENTER_LEFT);
        Label cpuLabel = new Label(String.format("CPU  %.0f%%", cpu));
        cpuLabel.getStyleClass().add("spot-card-metric");
        Label coresLabel = new Label("Cores: " + spot.totalCores());
        coresLabel.getStyleClass().add("spot-card-metric-dim");
        Region sp2 = new Region();
        HBox.setHgrow(sp2, Priority.ALWAYS);
        cpuRow.getChildren().addAll(cpuLabel, sp2, coresLabel);

        // Uptime
        long uptimeSec = spot.registeredAt() == null ? 0
                : Duration.between(spot.registeredAt(), Instant.now()).getSeconds();
        String uptimeText = uptimeSec >= 3600 ? (uptimeSec / 3600) + "h " + ((uptimeSec % 3600) / 60) + "m"
                : uptimeSec >= 60 ? (uptimeSec / 60) + "m " + (uptimeSec % 60) + "s"
                        : uptimeSec + "s";
        Label uptimeLabel = new Label("Uptime: " + uptimeText);
        uptimeLabel.getStyleClass().add("spot-card-metric-dim");

        // RAM bar (only if data available)
        long ramUsed = spot.ramUsedMb();
        long ramTotal = spot.ramTotalMb();
        if (ramTotal > 0) {
            double ramPct = (double) ramUsed / ramTotal;
            HBox ramRow = new HBox(6);
            ramRow.setAlignment(Pos.CENTER_LEFT);
            Label ramLabel = new Label(String.format("RAM  %d/%d MB", ramUsed, ramTotal));
            ramLabel.getStyleClass().add("spot-card-metric");
            Region sp3 = new Region();
            HBox.setHgrow(sp3, Priority.ALWAYS);
            Label ramPctLabel = new Label(String.format("%.0f%%", ramPct * 100));
            ramPctLabel.getStyleClass().add("spot-card-metric-dim");
            ramRow.getChildren().addAll(ramLabel, sp3, ramPctLabel);

            ProgressBar ramBar = new ProgressBar(ramPct);
            ramBar.setPrefWidth(250);
            ramBar.setPrefHeight(10);
            ramBar.getStyleClass().add("spot-cpu-bar");
            if (ramPct >= 0.85)
                ramBar.getStyleClass().add("cpu-high");
            else if (ramPct >= 0.60)
                ramBar.getStyleClass().add("cpu-medium");
            else
                ramBar.getStyleClass().add("cpu-low");

            resources.getChildren().addAll(cpuRow, cpuBar, ramRow, ramBar, uptimeLabel);
        } else {
            resources.getChildren().addAll(cpuRow, cpuBar, uptimeLabel);
        }

        // === Separator ===
        Separator sep = new Separator();
        sep.setPadding(new Insets(3, 0, 3, 0));

        // === Tasks section ===
        VBox tasksSection = new VBox(3);
        tasksSection.setPadding(new Insets(0, 10, 6, 10));

        Label tasksTitle = new Label("Tasks");
        tasksTitle.getStyleClass().add("spot-card-section-title");

        // Stats row
        HBox statsRow = new HBox(10);
        statsRow.setAlignment(Pos.CENTER_LEFT);

        Label runLabel = mkStatLabel("▶ " + running, "spot-stat-running");
        Label doneLabel = mkStatLabel("✓ " + stats.done(), "spot-stat-done");
        Label failLabel = mkStatLabel("✗ " + stats.failed(), "spot-stat-failed");

        statsRow.getChildren().addAll(runLabel, doneLabel, failLabel);

        // Total processed
        Label totalLabel = new Label("Total: " + stats.total());
        totalLabel.getStyleClass().add("spot-card-metric-dim");

        // Stacked bar
        HBox stackedBar = buildStackedBar(stats);

        tasksSection.getChildren().addAll(tasksTitle, statsRow, stackedBar, totalLabel);

        card.getChildren().addAll(header, resources, sep, tasksSection);
        return card;
    }

    /**
     * Format heartbeat age in human-readable form.
     * Package-private for testing.
     */
    static String formatAge(long seconds) {
        if (seconds < 60)
            return seconds + "s ago";
        if (seconds < 3600)
            return (seconds / 60) + "m" + (seconds % 60) + "s ago";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return h + "h" + m + "m ago";
    }

    private Label mkStatLabel(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().add(styleClass);
        return l;
    }

    private HBox buildStackedBar(SpotTaskStats stats) {
        HBox bar = new HBox(0);
        bar.setPrefHeight(6);
        bar.setMinHeight(6);
        bar.setMaxHeight(6);
        bar.getStyleClass().add("spot-stacked-bar");

        int total = Math.max(1, stats.total());
        double rFrac = stats.running() / (double) total;
        double dFrac = stats.done() / (double) total;
        double fFrac = stats.failed() / (double) total;

        if (stats.running() > 0) {
            Region r = new Region();
            r.getStyleClass().add("bar-running");
            HBox.setHgrow(r, Priority.NEVER);
            r.prefWidthProperty().bind(bar.widthProperty().multiply(rFrac));
            bar.getChildren().add(r);
        }
        if (stats.done() > 0) {
            Region d = new Region();
            d.getStyleClass().add("bar-done");
            d.prefWidthProperty().bind(bar.widthProperty().multiply(dFrac));
            bar.getChildren().add(d);
        }
        if (stats.failed() > 0) {
            Region f = new Region();
            f.getStyleClass().add("bar-failed");
            f.prefWidthProperty().bind(bar.widthProperty().multiply(fFrac));
            bar.getChildren().add(f);
        }

        if (stats.total() == 0) {
            Region empty = new Region();
            empty.getStyleClass().add("bar-empty");
            HBox.setHgrow(empty, Priority.ALWAYS);
            bar.getChildren().add(empty);
        }

        return bar;
    }

    // ================== Stats ==================

    private void updateStats(List<TaskInfo> items) {
        long nw = items.stream().filter(t -> "NEW".equals(t.status())).count();
        long ru = items.stream().filter(t -> "RUNNING".equals(t.status())).count();
        long dn = items.stream().filter(t -> "DONE".equals(t.status())).count();
        long fl = items.stream().filter(t -> "FAILED".equals(t.status())).count();
        if (lblNew != null)
            lblNew.setText(String.valueOf(nw));
        if (lblRunning != null)
            lblRunning.setText(String.valueOf(ru));
        if (lblDone != null)
            lblDone.setText(String.valueOf(dn));
        if (lblFailed != null)
            lblFailed.setText(String.valueOf(fl));
    }

    // ================== Task model mapping ==================

    private TaskInfo toTaskInfo(Task task) {
        String algDisplay = task.algorithm();
        Integer iters     = task.inputIterations();
        Integer agents    = task.inputAgents();
        Integer dim       = task.inputDimension();

        // Parse new payload format: {"params":{"algorithm.function":"sphere","run.iterations":100,...}}
        // algColumn shows the algorithm name (e.g. COA), funcColumn shows algorithm.function (e.g. sphere)
        try {
            if (task.payload() != null && !task.payload().isBlank()) {
                com.fasterxml.jackson.databind.JsonNode root = PAYLOAD_MAPPER.readTree(task.payload());
                com.fasterxml.jackson.databind.JsonNode params = root.path("params");
                if (params.isObject()) {
                    if (iters  == null && params.has("run.iterations")) iters  = params.get("run.iterations").asInt();
                    if (agents == null && params.has("run.agents"))     agents = params.get("run.agents").asInt();
                    if (dim    == null && params.has("run.dimension"))  dim    = params.get("run.dimension").asInt();
                }
                // Derive algorithm name from artifact key (e.g. "coa-algo.jar" → "COA")
                if ((algDisplay == null || algDisplay.isBlank()) && root.has("artifactKey")) {
                    String key = root.get("artifactKey").asText("");
                    String fname = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                    fname = fname.replaceAll("(?i)-jar-with-dependencies\\.jar$", "")
                                 .replaceAll("(?i)\\.jar$", "");
                    if (fname.contains("-")) fname = fname.substring(0, fname.indexOf('-'));
                    if (!fname.isBlank()) algDisplay = fname.toUpperCase();
                }
            }
        } catch (Exception ignored) {}

        if (algDisplay == null || algDisplay.isBlank())
            algDisplay = extractAlg(task.payload());

        return new TaskInfo(
                task.id(), algDisplay,
                task.status() != null ? task.status().name() : "NEW",
                task.iter(), task.runtimeMs(), task.fopt(),
                task.payload(), task.assignedTo(),
                task.startedAt(), task.finishedAt(),
                iters, agents, dim,
                task.result());
    }

    // ================== Simulation ==================

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

        if (btnStartSim != null)
            btnStartSim.setDisable(true);
        if (btnStopSim != null)
            btnStopSim.setDisable(false);
    }

    @FXML
    private void handleStopSim() {
        if (simulationService != null) {
            simulationService.stop();
            simulationService = null;
        }
        if (btnStartSim != null)
            btnStartSim.setDisable(false);
        if (btnStopSim != null)
            btnStopSim.setDisable(true);
    }

    // ================== JSON file handling ==================

    @FXML
    private void onAddJsonFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Выберите JSON с задачами");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON files", "*.json"));
        String currentVal = recentJsonCombo != null ? recentJsonCombo.getValue() : null;
        if (currentVal != null) {
            File dir = new File(currentVal).getParentFile();
            if (dir != null && dir.isDirectory())
                fc.setInitialDirectory(dir);
        }
        File f = fc.showOpenDialog(taskTable.getScene().getWindow());
        if (f == null)
            return;

        recentJson.addPath(f.getAbsolutePath());
        refreshJsonCombo();
        if (recentJsonCombo != null)
            recentJsonCombo.setValue(f.getAbsolutePath());
        loadJsonFile(f);
    }

    @FXML
    private void handleLoadSelectedJson() {
        if (recentJsonCombo == null)
            return;
        String selected = recentJsonCombo.getValue();
        if (selected == null || selected.isBlank()) {
            new Alert(Alert.AlertType.WARNING, "Выберите JSON файл из списка", ButtonType.OK).showAndWait();
            return;
        }
        File f = new File(selected);
        if (!f.isFile()) {
            new Alert(Alert.AlertType.WARNING, "Файл не найден: " + selected, ButtonType.OK).showAndWait();
            recentJson.removePath(selected);
            refreshJsonCombo();
            return;
        }
        loadJsonFile(f);
    }

    @FXML
    private void handleRemoveJson() {
        if (recentJsonCombo == null)
            return;
        String selected = recentJsonCombo.getValue();
        if (selected != null) {
            recentJson.removePath(selected);
            refreshJsonCombo();
        }
    }

    @FXML
    private void handleClearJson() {
        recentJson.clearAll();
        refreshJsonCombo();
    }

    private void refreshJsonCombo() {
        if (recentJsonCombo == null)
            return;
        recentJsonCombo.getItems().setAll(recentJson.getRecent());
    }

    private void loadJsonFile(File f) {
        try {
            String txt = java.nio.file.Files.readString(f.toPath());
            com.fasterxml.jackson.databind.ObjectMapper M = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = M.readTree(txt);

            var deps = CoordinatorNettyServer.dependencies();

            // New-style Job JSON for /api/v1/jobs (dynamic params + S3 artifact).
            // Detect by presence of artifactBucket + artifactKey + parameters.
            if (root.has("artifactBucket") && root.has("artifactKey") && root.has("parameters")) {
                CreateJobRequest req = M.treeToValue(root, CreateJobRequest.class);
                req.validate();

                ArtifactRef artifact = new ArtifactRef(
                        req.artifactBucket(),
                        req.artifactKey(),
                        req.artifactEndpoint());

                Job job = deps.jobService().createJob(
                        artifact,
                        req.mainClass(),
                        req.config(),
                        req.payloads());

                AppBus.fireTasksChanged();
                refreshTasks();

                new Alert(Alert.AlertType.INFORMATION,
                        "Создан job: " + job.id() + "\nЗадач: " + job.totalTasks(),
                        ButtonType.OK).showAndWait();

                recentJson.addPath(f.getAbsolutePath());
                refreshJsonCombo();
                return;
            }

            // Legacy task JSON formats (direct task creation).
            List<Task> tasksToCreate = new ArrayList<>();

            if (root.has("algorithms")) {
                var algs = M.convertValue(root.get("algorithms"),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {
                        });
                var iters = expandRange(root.path("iterations"));
                var dims = expandRange(root.path("dimension"));
                if (dims.isEmpty())
                    dims = java.util.List.of(2);
                if (iters.isEmpty())
                    iters = java.util.List.of(100);

                for (String alg : algs) {
                    for (Integer iterMax : iters) {
                        for (Integer dim : dims) {
                            var payload = new java.util.LinkedHashMap<String, Object>();
                            payload.put("alg", alg);
                            payload.put("iterations", java.util.Map.of("max", iterMax));
                            payload.put("dimension", dim);
                            String payloadJson = M.writeValueAsString(payload);
                            tasksToCreate.add(Task.builder()
                                    .id(UUID.randomUUID().toString())
                                    .payload(payloadJson).status(TaskStatus.NEW).priority(0)
                                    .algorithm(alg).inputIterations(iterMax).inputDimension(dim)
                                    .build());
                        }
                    }
                }
            } else if (root.isArray()) {
                for (var n : root) {
                    String payloadJson = n.toString();
                    String pAlg = null;
                    Integer pIter = null, pAgents = null, pDim = null;
                    try {
                        if (n.has("alg") && !n.get("alg").isNull())
                            pAlg = n.get("alg").asText();
                        var iterN = n.path("iterations");
                        if (iterN.isObject() && iterN.has("max"))
                            pIter = iterN.get("max").asInt();
                        else if (iterN.isNumber())
                            pIter = iterN.asInt();
                        if (n.has("agents") && n.get("agents").isNumber())
                            pAgents = n.get("agents").asInt();
                        if (n.has("dimension") && n.get("dimension").isNumber())
                            pDim = n.get("dimension").asInt();
                    } catch (Exception ignored) {
                    }

                    tasksToCreate.add(Task.builder()
                            .id(UUID.randomUUID().toString())
                            .payload(payloadJson).status(TaskStatus.NEW).priority(0)
                            .algorithm(pAlg).inputIterations(pIter).inputAgents(pAgents).inputDimension(pDim)
                            .build());
                }
            } else {
                throw new IllegalArgumentException("Неизвестный формат JSON.");
            }

            if (!tasksToCreate.isEmpty())
                deps.taskService().createTasks(tasksToCreate);
            recentJson.addPath(f.getAbsolutePath());
            refreshJsonCombo();
            AppBus.fireTasksChanged();
            refreshTasks();
            new Alert(Alert.AlertType.INFORMATION, "Добавлено задач: " + tasksToCreate.size(), ButtonType.OK)
                    .showAndWait();
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "Ошибка загрузки: " + e.getMessage(), ButtonType.OK).showAndWait();
        }
    }

    // ================== CSV Export ==================

    @FXML
    private void handleExportCsv() {
        var items = taskTable.getItems();
        if (items == null || items.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Нет задач для экспорта.", ButtonType.OK).showAndWait();
            return;
        }

        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Сохранить результаты");
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV files", "*.csv"));
        fc.setInitialFileName("orhestra_results.csv");
        File out = fc.showSaveDialog(taskTable.getScene().getWindow());
        if (out == null) return;

        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(out), java.nio.charset.StandardCharsets.UTF_8))) {

            // BOM for Excel UTF-8 compatibility
            pw.print('\uFEFF');
            pw.println("task_id,algorithm,function,iterations_param,agents,dimension," +
                       "spot,runtime_ms,status,iter_actual,fopt,best_position");

            for (TaskInfo t : items) {
                pw.println(String.join(",",
                        csvEsc(t.id()),
                        csvEsc(t.algId()),
                        csvEsc(extractFunc(t.payload())),
                        t.inputIterations() != null ? String.valueOf(t.inputIterations()) : "",
                        t.inputAgents()     != null ? String.valueOf(t.inputAgents())     : "",
                        t.inputDimension()  != null ? String.valueOf(t.inputDimension())  : "",
                        csvEsc(t.assignedTo()),
                        t.runtimeMs() != null ? String.valueOf(t.runtimeMs()) : "",
                        csvEsc(t.status()),
                        t.iter()  != null ? String.valueOf(t.iter())  : "",
                        t.fopt()  != null ? String.valueOf(t.fopt())  : "",
                        csvEsc(formatBestPos(t.result()))
                ));
            }

            new Alert(Alert.AlertType.INFORMATION,
                    "Экспортировано " + items.size() + " задач:\n" + out.getAbsolutePath(),
                    ButtonType.OK).showAndWait();

        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "Ошибка экспорта: " + e.getMessage(), ButtonType.OK).showAndWait();
        }
    }

    private static String csvEsc(String s) {
        if (s == null || s.isBlank() || "—".equals(s)) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * Converts bestPos JSON array "[0.12, -0.34, 5.6]" to semicolon-separated
     * "0.12; -0.34; 5.6" so it fits safely in a single CSV cell.
     */
    private static String formatBestPos(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) return "";
        try {
            com.fasterxml.jackson.databind.JsonNode node = PAYLOAD_MAPPER.readTree(resultJson);
            if (node.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < node.size(); i++) {
                    if (i > 0) sb.append("; ");
                    sb.append(node.get(i).asText());
                }
                return sb.toString();
            }
            // non-array result: return raw (stripped of outer braces for readability)
            return resultJson;
        } catch (Exception e) {
            return resultJson;
        }
    }

    // ================== Retry timer ==================

    private void scheduleRetry() {
        if (retryTimer != null)
            return;
        retryTimer = new Timer("exec-retry", true);
        retryTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> {
                    retryTimer = null;
                    refreshAll();
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

    // ================== Helpers ==================

    private static java.util.List<Integer> expandRange(com.fasterxml.jackson.databind.JsonNode node) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull())
            return out;
        if (node.isObject()) {
            int min = node.path("min").asInt(0);
            int max = node.path("max").asInt(min);
            int step = Math.max(1, node.path("step").asInt(1));
            for (int v = min; v <= max; v += step)
                out.add(v);
        } else if (node.isInt()) {
            out.add(node.asInt());
        }
        return out;
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String formatRuntime(Long ms) {
        if (ms == null || ms <= 0)
            return "—";
        return ms + " ms";
    }

    private static String extractAlg(String j) {
        if (j == null || j.isBlank()) return "—";
        try {
            com.fasterxml.jackson.databind.JsonNode root = PAYLOAD_MAPPER.readTree(j);
            if (root.has("alg") && !root.get("alg").isNull())
                return root.get("alg").asText("—");
        } catch (Exception ignored) {}
        return "—";
    }

    private static String extractFunc(String j) {
        if (j == null || j.isBlank()) return "—";
        try {
            com.fasterxml.jackson.databind.JsonNode root = PAYLOAD_MAPPER.readTree(j);
            // New format: params["algorithm.function"]
            com.fasterxml.jackson.databind.JsonNode params = root.path("params");
            if (params.isObject() && params.has("algorithm.function"))
                return params.get("algorithm.function").asText("—");
            // Legacy format: "func" key
            if (root.has("func") && !root.get("func").isNull())
                return root.get("func").asText("—");
        } catch (Exception ignored) {}
        return "—";
    }

    private static String calcProgress(String status, String payload, Integer iter) {
        if ("DONE".equals(status))
            return "100%";
        if ("FAILED".equals(status))
            return "—";
        if (iter == null)
            return "—";
        Integer max = extractIterationsMax(payload);
        if (max == null || max <= 0)
            return iter + " it";
        int pct = (int) Math.max(0, Math.min(100, Math.round(iter * 100.0 / max)));
        return pct + "% (" + iter + "/" + max + ")";
    }

    private static Integer extractIterationsMax(String j) {
        if (j == null || j.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode root = PAYLOAD_MAPPER.readTree(j);
            // New format: params["run.iterations"]
            com.fasterxml.jackson.databind.JsonNode params = root.path("params");
            if (params.isObject() && params.has("run.iterations"))
                return params.get("run.iterations").asInt();
            // Legacy: iterations.max
            com.fasterxml.jackson.databind.JsonNode iterNode = root.path("iterations");
            if (iterNode.isObject() && iterNode.has("max")) return iterNode.get("max").asInt();
            if (iterNode.isNumber()) return iterNode.asInt();
            // Legacy: iterMax
            com.fasterxml.jackson.databind.JsonNode iterMax = root.path("iterMax");
            if (iterMax.isNumber()) return iterMax.asInt();
        } catch (Exception ignored) {}
        return null;
    }

    private static int parseIntOr(TextField tf, int fb) {
        if (tf == null || tf.getText() == null || tf.getText().isBlank())
            return fb;
        try {
            return Integer.parseInt(tf.getText().trim());
        } catch (NumberFormatException e) {
            return fb;
        }
    }

    private static double parseDoubleOr(TextField tf, double fb) {
        if (tf == null || tf.getText() == null || tf.getText().isBlank())
            return fb;
        try {
            return Double.parseDouble(tf.getText().trim());
        } catch (NumberFormatException e) {
            return fb;
        }
    }
}
