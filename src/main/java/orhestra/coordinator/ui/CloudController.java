package orhestra.coordinator.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import orhestra.cloud.auth.AuthService;
import orhestra.cloud.config.CloudConfig;
import orhestra.cloud.config.IniLoader;
import orhestra.cloud.creator.VMCreator;
import orhestra.coordinator.model.CloudInstanceRow;
import orhestra.coordinator.model.EnvState;
import orhestra.coordinator.service.CloudProbe;
import orhestra.coordinator.service.CoordinatorService;
import orhestra.coordinator.service.OvpnService;
import orhestra.coordinator.service.VpnProbe;
import yandex.cloud.api.compute.v1.InstanceOuterClass;
import yandex.cloud.api.compute.v1.InstanceServiceOuterClass;
import yandex.cloud.sdk.utils.OperationUtils;
import yandex.cloud.api.operation.OperationOuterClass;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public class CloudController {

    // -------- FXML --------
    @FXML
    private TextField iniPathField;
    @FXML
    private Label statusLabel;
    @FXML
    private ComboBox<String> recentIniCombo;

    @FXML
    private Label dotVpn, dotCloud, dotOvpn, dotCoord;
    @FXML
    private Button btnOvpnOn, btnOvpnOff;
    @FXML
    private Button btnCheckVpn;
    @FXML
    private Button btnCheckCloud;
    @FXML
    private Button btnStartCoordinator;
    @FXML
    private Button btnStopCoordinator;
    @FXML
    private Button btnCreateSpot;
    @FXML
    private javafx.scene.control.ProgressIndicator createSpinner;
    @FXML
    private Label createProgressLabel;
    @FXML
    private TextArea coordLogArea;

    @FXML
    private Label labelVpnIp; // отображает текущий IP VPN (tun-интерфейса)
    @FXML
    private Label labelCoordUrl; // отображает URL координатора

    @FXML
    private TableView<CloudInstanceRow> instancesTable;
    @FXML
    private TableColumn<CloudInstanceRow, String> colId;
    @FXML
    private TableColumn<CloudInstanceRow, String> colName;
    @FXML
    private TableColumn<CloudInstanceRow, String> colZone;
    @FXML
    private TableColumn<CloudInstanceRow, String> colStatus;
    @FXML
    private TableColumn<CloudInstanceRow, String> colIP;
    @FXML
    private TableColumn<CloudInstanceRow, String> colCreated;
    @FXML
    private TableColumn<CloudInstanceRow, String> colPreempt;

    // -------- Model/State --------
    private final ObservableList<CloudInstanceRow> data = FXCollections.observableArrayList();
    private final EnvState env = new EnvState();

    private AuthService auth; // лениво: токен из ENV OAUTH_TOKEN
    private CloudConfig cfg; // то, что считали из INI

    /**
     * IP координатора в VPN-сети, определяется автоматически при старте
     * координатора.
     */
    private String coordinatorVpnIp = null;

    // services
    private final VpnProbe vpnProbe = new VpnProbe();
    private final CoordinatorService coordSvc = new CoordinatorService();
    private final RecentIniManager recentIni = new RecentIniManager();
    private CloudProbe cloudProbe; // зависит от auth
    private OvpnService ovpnSvc; // зависит от auth + cfg

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @FXML
    private void handleClearCoordLog() {
        if (coordLogArea != null)
            coordLogArea.clear();
    }

    // =====================================================================
    @FXML
    private void initialize() {
        // биндинги колонок
        colId.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("id"));
        colName.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("name"));
        colZone.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("zone"));
        colStatus.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("status"));
        colIP.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("ip"));
        colCreated.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("createdAt"));
        colPreempt.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("preemptible"));
        instancesTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        instancesTable.setItems(data);

        // реакция на смену статусов: цветные точки и доступность кнопок
        env.vpnProperty().addListener((o, a, b) -> colorize(dotVpn, b));
        env.cloudProperty().addListener((o, a, b) -> colorize(dotCloud, b));
        env.ovpnProperty().addListener((o, a, b) -> colorize(dotOvpn, b));
        env.coordProperty().addListener((o, a, b) -> colorize(dotCoord, b));
        env.anyProperty().addListener((o, a, b) -> updateButtons());

        // лог координатора в TextArea
        coordSvc.setLogSink(s -> javafx.application.Platform.runLater(() -> {
            if (coordLogArea != null)
                coordLogArea.appendText(s);
        }));

        // ---- Recent INI ----
        refreshRecentCombo();

        // ComboBox selection → update path field
        if (recentIniCombo != null) {
            recentIniCombo.setOnAction(e -> {
                String selected = recentIniCombo.getValue();
                if (selected != null && !selected.isBlank()) {
                    iniPathField.setText(selected);
                }
            });
        }

        // Auto-restore last INI on startup
        String lastPath = recentIni.getLast();
        if (lastPath != null && new File(lastPath).isFile()) {
            iniPathField.setText(lastPath);
            if (recentIniCombo != null) {
                recentIniCombo.setValue(lastPath);
            }
            setStatus("Последний конфиг: " + new File(lastPath).getName());
        } else if (lastPath != null) {
            // File no longer exists
            setStatus("⚠ Последний INI не найден: " + lastPath);
        } else {
            setStatus("Загрузите INI-конфиг для начала работы");
        }

        updateButtons();
    }

    // ==================== INI ====================

    @FXML
    private void handleChooseIni() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Выберите INI-файл");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("INI files", "*.ini"));

        // Start in directory of current path if available
        String currentPath = iniPathField.getText();
        if (currentPath != null && !currentPath.isBlank()) {
            File dir = new File(currentPath).getParentFile();
            if (dir != null && dir.isDirectory()) {
                fc.setInitialDirectory(dir);
            }
        }

        File f = fc.showOpenDialog(instancesTable.getScene().getWindow());
        if (f != null) {
            iniPathField.setText(f.getAbsolutePath());
            if (recentIniCombo != null) {
                recentIniCombo.setValue(f.getAbsolutePath());
            }
        }
    }

    @FXML
    private void handleLoadIni() {
        try {
            var path = required(iniPathField.getText(), "INI path");
            var opt = IniLoader.load(new File(path));
            if (opt.isEmpty()) {
                setStatus("❌ INI parse error");
                return;
            }
            cfg = opt.get();

            // Save to recent list
            recentIni.addPath(path);
            refreshRecentCombo();
            if (recentIniCombo != null) {
                recentIniCombo.setValue(path);
            }

            setStatus("✅ INI загружен: " + new File(path).getName());

            // Сбрасываем старый auth — новый INI может быть другой аккаунт
            auth = null;

            // лениво создаём auth и сервисы, зависящие от него
            ensureAuth();
            this.cloudProbe = new CloudProbe(auth);
            this.ovpnSvc = new OvpnService(auth, cfg);

            // сразу проверим доступность облака (тихая проверка)
            env.setCloud(cloudProbe.quickPing(cfg.folderId));
            updateButtons();
        } catch (Exception e) {
            setStatus("INI load error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @FXML
    private void handleRemoveRecent() {
        if (recentIniCombo == null)
            return;
        String selected = recentIniCombo.getValue();
        if (selected != null) {
            recentIni.removePath(selected);
            refreshRecentCombo();
            setStatus("Путь удалён из списка");
        }
    }

    @FXML
    private void handleClearRecent() {
        recentIni.clearAll();
        refreshRecentCombo();
        iniPathField.clear();
        setStatus("Список очищен");
    }

    /** Refresh the ComboBox from stored recent paths. */
    private void refreshRecentCombo() {
        if (recentIniCombo == null)
            return;
        List<String> recent = recentIni.getRecent();
        recentIniCombo.getItems().setAll(recent);
    }

    // ==================== ПРОВЕРКИ ====================

    @FXML
    private void handleCheckVpn() {
        Optional<String> ip = vpnProbe.getVpnIp();
        env.setVpn(ip.isPresent());
        if (ip.isPresent()) {
            if (labelVpnIp != null)
                labelVpnIp.setText(ip.get());
            setStatus("✅ VPN OK (tun IP: " + ip.get() + ")");
        } else {
            if (labelVpnIp != null)
                labelVpnIp.setText("—");
            setStatus("⚠️ VPN не обнаружен (tun-интерфейс не найден)");
        }
    }

    @FXML
    private void handleCheckCloud() {
        try {
            ensureAuth();
            ensureCfg();
            boolean ok = cloudProbe.quickPing(cfg.folderId);
            env.setCloud(ok);
            setStatus(ok ? "✅ Облако доступно" : "❌ Облако недоступно");
        } catch (Exception e) {
            env.setCloud(false);
            setStatus("❌ Облако недоступно: " + e.getMessage());
        }
    }

    // OpenVPN Access Server
    @FXML
    private void handleOvpnOn() {
        callOvpn(true);
    }

    @FXML
    private void handleOvpnOff() {
        callOvpn(false);
    }

    private void callOvpn(boolean on) {
        try {
            ensureAuth();
            ensureCfg();

            // Если ovpnInstanceId отсутствует — предупреждаем
            if (cfg.ovpnInstanceId == null || cfg.ovpnInstanceId.isBlank()) {
                setStatus("⚠️ В INI не указан [OVPN] instance_id — запуск невозможен");
                env.setOvpn(false);
                return;
            }

            boolean ok = on ? ovpnSvc.start() : ovpnSvc.stop();
            env.setOvpn(ok);

            if (ok) {
                setStatus(on ? "✅ OpenVPN Access Server запущен" : "✅ OpenVPN AS остановлен");
            } else {
                setStatus(on ? "❌ Не удалось запустить OpenVPN AS" : "❌ Не удалось остановить OpenVPN AS");
            }
        } catch (Exception e) {
            env.setOvpn(false);
            setStatus("OVPN ошибка: " + e.getMessage());
        }
    }

    @FXML
    private void handleStartCoordinator() {
        Optional<String> vpnIp = vpnProbe.getVpnIp();
        if (vpnIp.isEmpty()) {
            env.setCoord(false);
            setStatus("❌ VPN не поднят — подключитесь к OpenVPN и повторите");
            return;
        }
        boolean ok = coordSvc.start(8081);
        if (ok) {
            coordinatorVpnIp = vpnIp.get();
            String coordUrl = "http://" + coordinatorVpnIp + ":8081";
            env.setCoord(true);
            if (labelVpnIp != null)
                labelVpnIp.setText(coordinatorVpnIp);
            if (labelCoordUrl != null)
                labelCoordUrl.setText(coordUrl);
            setStatus("✅ Координатор запущен на 8081 (VPN IP: " + coordinatorVpnIp + ")");
        } else {
            coordinatorVpnIp = null;
            env.setCoord(false);
            setStatus("❌ Координатор не запустился");
        }
    }

    @FXML
    private void handleStopCoordinator() {
        coordSvc.stop();
        coordinatorVpnIp = null;
        env.setCoord(false);
        if (labelCoordUrl != null)
            labelCoordUrl.setText("—");
        setStatus("Координатор остановлен");
    }

    // ==================== ОБЛАКО ====================

    @FXML
    private void handleListInstances() {
        try {
            ensureAuth();
            ensureCfg();
            var listReq = InstanceServiceOuterClass.ListInstancesRequest.newBuilder()
                    .setFolderId(cfg.folderId)
                    .build();
            var resp = auth.getInstanceService().list(listReq);
            List<InstanceOuterClass.Instance> items = resp.getInstancesList();
            data.setAll(items.stream().map(this::toRow).toList());
            setStatus("Instances: " + items.size());
        } catch (Exception e) {
            setStatus("List error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @FXML
    private void handleCreateSpot() {
        // Проверяем, что координатор был запущен и VPN-IP известен
        if (coordinatorVpnIp == null) {
            setStatus("❌ Сначала запустите координатор (VPN должен быть поднят)");
            return;
        }
        final String coordUrl = "http://" + coordinatorVpnIp + ":8081";

        runAsync("Создание SPOT-инстансов…", () -> {
            try {
                ensureAuth();
                ensureCfg();

                var iniFile = new File(required(iniPathField.getText(), "INI path"));
                var vmCfgOpt = IniLoader.loadVmConfig(iniFile);
                if (vmCfgOpt.isEmpty()) {
                    updateStatusAsync("❌ INI (VmConfig) некорректен — проверь секции [AUTH]/[NETWORK]/[VM]/[SSH]");
                    return;
                }

                var creator = new VMCreator(auth);
                creator.createMany(vmCfgOpt.get(), coordUrl);

                updateStatusAsync("✅ SPOT-инстансы созданы (координатор: " + coordUrl + ")");
                javafx.application.Platform.runLater(this::handleListInstances);
            } catch (Exception e) {
                updateStatusAsync("❌ Ошибка создания: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @FXML
    private void handleDeleteSelected() {
        var selected = instancesTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            setStatus("Nothing selected");
            return;
        }
        try {
            ensureAuth();
            for (CloudInstanceRow row : selected) {
                var delReq = InstanceServiceOuterClass.DeleteInstanceRequest.newBuilder()
                        .setInstanceId(row.getId()).build();
                OperationOuterClass.Operation op = auth.getInstanceService().delete(delReq);
                OperationUtils.wait(auth.getOperationService(), op, java.time.Duration.ofMinutes(5));
            }
            handleListInstances();
            setStatus("Delete: done");
        } catch (Exception e) {
            setStatus("Delete error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================== HELPERS ====================

    private void ensureAuth() {
        if (auth != null)
            return;

        // Определяем endpoint по zone (kz1-a → api.yandexcloud.kz, иначе null = дефолт)
        String endpoint = (cfg != null) ? cfg.resolveEndpoint() : null;

        // Приоритет:
        // 1. Токен из текущего INI (если не пустой) — наивысший приоритет
        if (cfg != null && cfg.oauthToken != null && !cfg.oauthToken.isBlank()) {
            recentIni.setOauthToken(cfg.oauthToken); // обновляем сохранённый
            auth = new AuthService(cfg.oauthToken, endpoint);
            return;
        }

        // 2. Сохранённый токен из Preferences (от предыдущей сессии)
        String savedToken = recentIni.getOauthToken();
        if (savedToken != null && !savedToken.isBlank()) {
            auth = new AuthService(savedToken, endpoint);
            return;
        }

        // 3. ENV: OAUTH_TOKEN
        String envToken = System.getenv("OAUTH_TOKEN");
        auth = new AuthService(envToken, endpoint);

        // Сохранить ENV-токен в Preferences для будущих запусков
        if (envToken != null && !envToken.isBlank()) {
            recentIni.setOauthToken(envToken);
        }
    }

    private void ensureCfg() {
        if (cfg == null)
            throw new IllegalStateException("INI not loaded");
    }

    private CloudInstanceRow toRow(InstanceOuterClass.Instance inst) {
        String id = inst.getId();
        String name = inst.getName();
        String zone = inst.getZoneId();
        String status = inst.getStatus().name();
        String ip = inst.getNetworkInterfacesCount() > 0
                ? inst.getNetworkInterfaces(0).getPrimaryV4Address().getAddress()
                : "";
        String created = inst.hasCreatedAt()
                ? TS.format(Instant.ofEpochSecond(inst.getCreatedAt().getSeconds()))
                : "";
        boolean spot = inst.hasSchedulingPolicy() && inst.getSchedulingPolicy().getPreemptible();
        return CloudInstanceRow.of(id, name, zone, status, ip, created, spot);
    }

    private void setStatus(String s) {
        statusLabel.setText(s);
    }

    private void updateButtons() {
        boolean iniOk = (cfg != null);
        // Создавать SPOT можно без VPN/координатора — лишь бы INI загружен и облако OK
        boolean enableCreate = iniOk && env.isCloud();
        if (btnCreateSpot != null)
            btnCreateSpot.setDisable(!enableCreate);

        // Кнопки координатора
        if (btnStopCoordinator != null)
            btnStopCoordinator.setDisable(!env.isCoord());
        if (btnStartCoordinator != null)
            btnStartCoordinator.setDisable(env.isCoord());
    }

    private static void colorize(Label dot, boolean ok) {
        if (dot == null)
            return;
        dot.setStyle("-fx-text-fill: " + (ok ? "#11aa11" : "#999999") + "; -fx-font-size: 16;");
    }

    private static String required(String v, String name) {
        if (v == null || v.isBlank())
            throw new IllegalArgumentException("Missing: " + name);
        return v;
    }

    /**
     * Базовый helper для фоновых задач с обновлением статуса и блокировкой кнопки
     * Create SPOT.
     */
    private void runAsync(String startMsg, Runnable job) {
        updateStatusAsync(startMsg);
        if (btnCreateSpot != null)
            btnCreateSpot.setDisable(true);
        showSpinner(true, startMsg);
        new Thread(() -> {
            try {
                job.run();
            } finally {
                javafx.application.Platform.runLater(() -> {
                    showSpinner(false, null);
                    updateButtons();
                });
            }
        }, "cloud-ui-worker").start();
    }

    private void showSpinner(boolean visible, String msg) {
        javafx.application.Platform.runLater(() -> {
            if (createSpinner != null) {
                createSpinner.setVisible(visible);
                createSpinner.setManaged(visible);
            }
            if (createProgressLabel != null) {
                createProgressLabel.setVisible(visible && msg != null);
                createProgressLabel.setManaged(visible && msg != null);
                if (msg != null) createProgressLabel.setText(msg);
            }
        });
    }

    private void updateStatusAsync(String msg) {
        javafx.application.Platform.runLater(() -> setStatus(msg));
    }
}
