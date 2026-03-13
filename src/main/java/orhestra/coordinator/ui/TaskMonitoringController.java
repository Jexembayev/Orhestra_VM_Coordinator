package orhestra.coordinator.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import orhestra.coordinator.core.AppBus;
import orhestra.coordinator.model.Task;
import orhestra.coordinator.model.TaskStatus;
import orhestra.coordinator.server.CoordinatorNettyServer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.stream.Collectors;

public class TaskMonitoringController {
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
    private Label lblNew, lblRunning, lblDone, lblFailed;
    @FXML
    private ComboBox<String> recentJsonCombo;

    private final RecentJsonManager recentJson = new RecentJsonManager();

    @FXML
    private void initialize() {
        // ID / ALG
        idColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().id()));
        algColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().algId() != null ? c.getValue().algId() : "—"));

        // FUNC (достанем из payload, если есть "func" в json; иначе "—")
        funcColumn.setCellValueFactory(c -> new SimpleStringProperty(extractFunc(c.getValue().payload())));

        // ITER (input iterations, not result iter)
        iterColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().inputIterations() == null ? "—" : String.valueOf(c.getValue().inputIterations())));

        // AGENTS
        if (agentsColumn != null) {
            agentsColumn.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().inputAgents() == null ? "—" : String.valueOf(c.getValue().inputAgents())));
        }

        // DIMENSION
        if (dimensionColumn != null) {
            dimensionColumn.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().inputDimension() == null ? "—" : String.valueOf(c.getValue().inputDimension())));
        }

        // RUNTIME
        runtimeColumn.setCellValueFactory(c -> new SimpleStringProperty(formatRuntime(c.getValue().runtimeMs())));

        // STATUS (бейдж)
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
                        /* CANCELLED etc */ }
                }
                setGraphic(badge);
                setText(null);
            }
        });

        // PROGRESS (status-aware: DONE→100%, FAILED→"—")
        progressColumn.setCellValueFactory(c -> new SimpleStringProperty(
                calcProgress(c.getValue().status(), c.getValue().payload(), c.getValue().iter())));

        // SPOT ID
        if (spotIdColumn != null) {
            spotIdColumn.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().assignedTo() == null ? "—" : c.getValue().assignedTo()));
        }

        // авто-обновление от координатора
        AppBus.onTasksChanged(() -> Platform.runLater(this::refreshTasks));

        // ---- Recent JSON ComboBox ----
        if (recentJsonCombo != null) {
            // Show only filename in dropdown, but store full path as value
            recentJsonCombo.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(String path, boolean empty) {
                    super.updateItem(path, empty);
                    setText(empty || path == null ? null : RecentJsonManager.displayName(path));
                }
            });
            recentJsonCombo.setButtonCell(new ListCell<>() {
                @Override
                protected void updateItem(String path, boolean empty) {
                    super.updateItem(path, empty);
                    setText(empty || path == null ? null : RecentJsonManager.displayName(path));
                }
            });
            refreshJsonCombo();
        }

        // Auto-restore last JSON path
        String lastJson = recentJson.getLast();
        if (lastJson != null && recentJsonCombo != null) {
            if (new File(lastJson).isFile()) {
                recentJsonCombo.setValue(lastJson);
            }
        }

        refreshTasks();
    }

    private Timer retryTimer;

    @FXML
    public void refreshTasks() {
        var deps = CoordinatorNettyServer.tryDependencies();
        if (deps == null) {
            taskTable.getItems().clear();
            scheduleRetry();
            return;
        }

        cancelRetryTimer();

        List<TaskInfo> items = deps
                .taskService()
                .findRecent(200)
                .stream()
                .map(this::toTaskInfo)
                .collect(Collectors.toList());

        taskTable.getItems().setAll(items);
        updateStats(items);
        taskTable.refresh();
    }

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

    private void scheduleRetry() {
        if (retryTimer != null)
            return; // Already scheduled

        retryTimer = new Timer("task-monitor-retry", true);
        retryTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> {
                    retryTimer = null;
                    refreshTasks();
                });
            }
        }, 2000); // Retry after 2 seconds
    }

    private void cancelRetryTimer() {
        if (retryTimer != null) {
            retryTimer.cancel();
            retryTimer = null;
        }
    }

    /**
     * Convert Task model to TaskInfo for UI display.
     */
    private TaskInfo toTaskInfo(Task task) {
        // Prefer first-class Task.algorithm(); fall back to payload extraction
        String algDisplay = task.algorithm();
        if (algDisplay == null || algDisplay.isBlank()) {
            algDisplay = extractAlg(task.payload());
        }
        return new TaskInfo(
                task.id(),
                algDisplay,
                task.status() != null ? task.status().name() : "NEW",
                task.iter(),
                task.runtimeMs(),
                task.fopt(),
                task.payload(),
                task.assignedTo(),
                task.startedAt(),
                task.finishedAt(),
                task.inputIterations(),
                task.inputAgents(),
                task.inputDimension(),
                task.result());
    }

    /**
     * Extract "alg" field from payload JSON.
     */
    private static String extractAlg(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank())
            return "—";
        try {
            int i = payloadJson.indexOf("\"alg\"");
            if (i < 0)
                return "—";
            int c = payloadJson.indexOf(':', i);
            int q1 = payloadJson.indexOf('"', c);
            int q2 = payloadJson.indexOf('"', q1 + 1);
            if (q1 > 0 && q2 > q1)
                return payloadJson.substring(q1 + 1, q2);
        } catch (Exception ignore) {
        }
        return "—";
    }

    @FXML
    private void onAddJsonFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Выберите JSON с задачами");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON files", "*.json"));

        // Start in directory of last selected file
        String currentVal = recentJsonCombo != null ? recentJsonCombo.getValue() : null;
        if (currentVal != null) {
            File dir = new File(currentVal).getParentFile();
            if (dir != null && dir.isDirectory())
                fc.setInitialDirectory(dir);
        }

        File f = fc.showOpenDialog(taskTable.getScene().getWindow());
        if (f == null)
            return;

        // Save to recent list
        recentJson.addPath(f.getAbsolutePath());
        refreshJsonCombo();
        if (recentJsonCombo != null)
            recentJsonCombo.setValue(f.getAbsolutePath());

        // Immediately load
        loadJsonFile(f);
    }

    @FXML
    private void handleLoadSelectedJson() {
        if (recentJsonCombo == null)
            return;
        String selected = recentJsonCombo.getValue();
        if (selected == null || selected.isBlank()) {
            new Alert(Alert.AlertType.WARNING, "Выберите JSON файл из списка или нажмите \"Выбрать JSON…\"",
                    ButtonType.OK).showAndWait();
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

    /** Core loading logic — extracted from former onAddJsonFile. */
    private void loadJsonFile(File f) {
        try {
            String txt = java.nio.file.Files.readString(f.toPath());
            com.fasterxml.jackson.databind.ObjectMapper M = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = M.readTree(txt);

            List<Task> tasksToCreate = new ArrayList<>();
            var deps = CoordinatorNettyServer.dependencies();

            // Формат 1: диапазоны
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
                            String id = UUID.randomUUID().toString();
                            String payloadJson = M.writeValueAsString(payload);

                            Task task = Task.builder()
                                    .id(id)
                                    .payload(payloadJson)
                                    .status(TaskStatus.NEW)
                                    .priority(0)
                                    .algorithm(alg)
                                    .inputIterations(iterMax)
                                    .inputDimension(dim)
                                    .build();
                            tasksToCreate.add(task);
                        }
                    }
                }
            }
            // Формат 2: список готовых payload-ов
            else if (root.isArray()) {
                for (var n : root) {
                    String payloadJson = n.toString();
                    String id = UUID.randomUUID().toString();

                    // Parse input params from individual payload
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

                    Task task = Task.builder()
                            .id(id)
                            .payload(payloadJson)
                            .status(TaskStatus.NEW)
                            .priority(0)
                            .algorithm(pAlg)
                            .inputIterations(pIter)
                            .inputAgents(pAgents)
                            .inputDimension(pDim)
                            .build();
                    tasksToCreate.add(task);
                }
            } else {
                throw new IllegalArgumentException("Неизвестный формат JSON.");
            }

            // Save tasks using service
            if (!tasksToCreate.isEmpty()) {
                deps.taskService().createTasks(tasksToCreate);
            }

            // Save to recent on success
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

    /**
     * Вспомогалка: вытянуть список значений из {min,max,step} или единственного
     * числа
     */
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

    // ---- helpers ----

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String formatRuntime(Long ms) {
        if (ms == null || ms <= 0)
            return "—";
        long s = ms / 1000;
        long m = s / 60;
        long sec = s % 60;
        if (m > 0)
            return m + "m " + sec + "s";
        return sec + "s";
    }

    private static String extractFunc(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank())
            return "—";
        try {
            int i = payloadJson.indexOf("\"func\"");
            if (i < 0)
                return "—";
            int c = payloadJson.indexOf(':', i);
            int q1 = payloadJson.indexOf('"', c);
            int q2 = payloadJson.indexOf('"', q1 + 1);
            if (q1 > 0 && q2 > q1)
                return payloadJson.substring(q1 + 1, q2);
        } catch (Exception ignore) {
        }
        return "—";
    }

    private static String calcProgress(String status, String payloadJson, Integer iter) {
        if ("DONE".equals(status))
            return "100%";
        if ("FAILED".equals(status))
            return "—";
        if (iter == null)
            return "—";
        Integer max = extractIterationsMax(payloadJson);
        if (max == null || max <= 0)
            return iter + " it";
        int pct = (int) Math.max(0, Math.min(100, Math.round(iter * 100.0 / max)));
        return pct + "% (" + iter + "/" + max + ")";
    }

    private static Integer extractIterationsMax(String payloadJson) {
        if (payloadJson == null)
            return null;
        try {
            int p = payloadJson.indexOf("\"iterations\"");
            int blockStart = (p >= 0) ? p : payloadJson.indexOf("\"iterMax\"");
            if (blockStart < 0)
                return null;

            int maxKey = payloadJson.indexOf("\"max\"", blockStart);
            if (maxKey >= 0) {
                int colon = payloadJson.indexOf(':', maxKey);
                int end = colon < 0 ? -1 : findNumberEnd(payloadJson, colon + 1);
                if (end > 0) {
                    String num = payloadJson.substring(colon + 1, end).replaceAll("[^0-9]", "");
                    if (!num.isEmpty())
                        return Integer.parseInt(num);
                }
            }
            int im = payloadJson.indexOf("\"iterMax\"");
            if (im >= 0) {
                int colon = payloadJson.indexOf(':', im);
                int end = colon < 0 ? -1 : findNumberEnd(payloadJson, colon + 1);
                if (end > 0) {
                    String num = payloadJson.substring(colon + 1, end).replaceAll("[^0-9]", "");
                    if (!num.isEmpty())
                        return Integer.parseInt(num);
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static int findNumberEnd(String s, int from) {
        int i = from;
        while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ':'))
            i++;
        while (i < s.length() && (Character.isDigit(s.charAt(i))))
            i++;
        return i;
    }
}
