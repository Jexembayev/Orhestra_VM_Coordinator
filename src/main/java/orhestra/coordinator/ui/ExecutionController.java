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

    // ---- Stats ----
    @FXML
    private Label lblNew, lblRunning, lblDone, lblFailed;

    // ---- Spot cards ----
    @FXML
    private FlowPane spotCards;
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
        var deps = CoordinatorNettyServer.tryDependencies();
        if (deps == null) {
            taskTable.getItems().clear();
            scheduleRetry();
            return;
        }
        cancelRetryTimer();

        List<TaskInfo> items = deps.taskService().findRecent(200)
                .stream().map(this::toTaskInfo).collect(Collectors.toList());

        taskTable.getItems().setAll(items);
        updateStats(items);
        taskTable.refresh();
    }

    private void refreshSpots() {
        Dependencies deps = CoordinatorNettyServer.tryDependencies();
        if (deps == null)
            return;

        List<Spot> spots = deps.spotService().findAll();

        // Compute per-spot task stats from loaded tasks
        java.util.Map<String, SpotTaskStats> statsMap = new java.util.HashMap<>();
        List<Task> allTasks = deps.taskService().findRecent(500);
        for (Task t : allTasks) {
            String sid = t.assignedTo();
            if (sid == null)
                continue;
            SpotTaskStats prev = statsMap.getOrDefault(sid, new SpotTaskStats(0, 0, 0));
            int r = prev.running(), d = prev.done(), f = prev.failed();
            if (t.status() == TaskStatus.RUNNING)
                r++;
            else if (t.status() == TaskStatus.DONE)
                d++;
            else if (t.status() == TaskStatus.FAILED)
                f++;
            statsMap.put(sid, new SpotTaskStats(r, d, f));
        }

        if (spotCards != null) {
            spotCards.getChildren().clear();
            for (Spot spot : spots) {
                SpotTaskStats stats = statsMap.getOrDefault(spot.id(), new SpotTaskStats(0, 0, 0));
                spotCards.getChildren().add(buildSpotCard(spot, stats));
            }
        }
        if (lblSpotCount != null) {
            lblSpotCount.setText("Spots: " + spots.size());
        }
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

        long age = spot.lastHeartbeat() == null ? Long.MAX_VALUE
                : Math.max(0, Duration.between(spot.lastHeartbeat(), Instant.now()).getSeconds());
        if (age > 5)
            isUp = false;

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

        String beatText = age == Long.MAX_VALUE ? "—" : age + "s ago";
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

        resources.getChildren().addAll(cpuRow, cpuBar, uptimeLabel);

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
        if (algDisplay == null || algDisplay.isBlank())
            algDisplay = extractAlg(task.payload());
        return new TaskInfo(
                task.id(), algDisplay,
                task.status() != null ? task.status().name() : "NEW",
                task.iter(), task.runtimeMs(), task.fopt(),
                task.payload(), task.assignedTo(),
                task.startedAt(), task.finishedAt(),
                task.inputIterations(), task.inputAgents(), task.inputDimension());
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

            List<Task> tasksToCreate = new ArrayList<>();
            var deps = CoordinatorNettyServer.dependencies();

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
        long s = ms / 1000;
        long m = s / 60;
        long sec = s % 60;
        return m > 0 ? m + "m " + sec + "s" : sec + "s";
    }

    private static String extractAlg(String j) {
        if (j == null || j.isBlank())
            return "—";
        try {
            int i = j.indexOf("\"alg\"");
            if (i < 0)
                return "—";
            int c = j.indexOf(':', i);
            int q1 = j.indexOf('"', c);
            int q2 = j.indexOf('"', q1 + 1);
            if (q1 > 0 && q2 > q1)
                return j.substring(q1 + 1, q2);
        } catch (Exception e) {
        }
        return "—";
    }

    private static String extractFunc(String j) {
        if (j == null || j.isBlank())
            return "—";
        try {
            int i = j.indexOf("\"func\"");
            if (i < 0)
                return "—";
            int c = j.indexOf(':', i);
            int q1 = j.indexOf('"', c);
            int q2 = j.indexOf('"', q1 + 1);
            if (q1 > 0 && q2 > q1)
                return j.substring(q1 + 1, q2);
        } catch (Exception e) {
        }
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
        if (j == null)
            return null;
        try {
            int p = j.indexOf("\"iterations\"");
            int bs = (p >= 0) ? p : j.indexOf("\"iterMax\"");
            if (bs < 0)
                return null;
            int mk = j.indexOf("\"max\"", bs);
            if (mk >= 0) {
                int c = j.indexOf(':', mk);
                int e = c < 0 ? -1 : findNumberEnd(j, c + 1);
                if (e > 0) {
                    String n = j.substring(c + 1, e).replaceAll("[^0-9]", "");
                    if (!n.isEmpty())
                        return Integer.parseInt(n);
                }
            }
            int im = j.indexOf("\"iterMax\"");
            if (im >= 0) {
                int c = j.indexOf(':', im);
                int e = c < 0 ? -1 : findNumberEnd(j, c + 1);
                if (e > 0) {
                    String n = j.substring(c + 1, e).replaceAll("[^0-9]", "");
                    if (!n.isEmpty())
                        return Integer.parseInt(n);
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    private static int findNumberEnd(String s, int from) {
        int i = from;
        while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ':'))
            i++;
        while (i < s.length() && Character.isDigit(s.charAt(i)))
            i++;
        return i;
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
