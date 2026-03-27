package orhestra.coordinator.service;

import orhestra.cloud.auth.AuthService;
import orhestra.cloud.config.IniLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory store for the cloud configuration loaded from config.ini.
 *
 * The config can be:
 *  1. Loaded at startup from ORHESTRA_CONFIG_PATH env var (path to config.ini file)
 *  2. Uploaded at runtime via POST /api/v1/admin/config
 *
 * Thread-safe — uses AtomicReference so controllers can read/write concurrently.
 */
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    public record LoadedConfig(
            IniLoader.VmConfig vmConfig,
            AuthService        authService,
            String             rawIni        // original text (secrets present — not served to clients)
    ) {}

    private final AtomicReference<LoadedConfig> current = new AtomicReference<>(null);

    public ConfigService() {
        // Try to auto-load from env var on startup
        String path = System.getenv("ORHESTRA_CONFIG_PATH");
        if (path != null && !path.isBlank()) {
            File f = new File(path.trim());
            if (f.exists()) {
                try {
                    String raw = Files.readString(f.toPath());
                    load(raw, f);
                    log.info("Auto-loaded config from {}", path);
                } catch (IOException e) {
                    log.warn("Failed to auto-load config from {}: {}", path, e.getMessage());
                }
            } else {
                log.warn("ORHESTRA_CONFIG_PATH set but file not found: {}", path);
            }
        }
    }

    /**
     * Parse and store a config.ini given as a raw string.
     * Writes the content to a temp file so IniLoader (which expects a File) can parse it.
     */
    public void load(String rawIni) throws IOException {
        File tmp = File.createTempFile("orhestra-config", ".ini");
        tmp.deleteOnExit();
        Files.writeString(tmp.toPath(), rawIni);
        try {
            load(rawIni, tmp);
        } finally {
            tmp.delete();
        }
    }

    private void load(String rawIni, File file) throws IOException {
        Optional<IniLoader.VmConfig> vmOpt = IniLoader.loadVmConfig(file);
        if (vmOpt.isEmpty()) {
            throw new IllegalArgumentException(
                    "Config parse failed — check that all required sections are present: " +
                    "[AUTH], [NETWORK], [VM], [SSH]");
        }
        IniLoader.VmConfig vmConfig = vmOpt.get();

        // Build AuthService from the parsed config
        AuthService authService;
        try {
            // Detect Yandex Cloud endpoint — KZ zone uses api.yandexcloud.kz
            String endpoint = null;
            if (vmConfig.s3Endpoint != null && vmConfig.s3Endpoint.contains(".kz")) {
                endpoint = "api.yandexcloud.kz";
            }
            // oauthToken comes from [AUTH] oauth_token — stored separately in raw INI
            // We re-read it from the temp file via a lightweight parse
            String oauthToken = extractIniValue(rawIni, "AUTH", "oauth_token");
            authService = new AuthService(oauthToken, endpoint);
        } catch (Exception e) {
            log.warn("AuthService init failed (VM creation won't work): {}", e.getMessage());
            authService = null;
        }

        current.set(new LoadedConfig(vmConfig, authService, rawIni));
        log.info("Config loaded: {}", vmConfig);
    }

    /** True if a config has been loaded. */
    public boolean isLoaded() {
        return current.get() != null;
    }

    /** Get current config, empty if not loaded. */
    public Optional<LoadedConfig> get() {
        return Optional.ofNullable(current.get());
    }

    /**
     * Return a sanitized summary of the config safe to send over HTTP
     * (secrets replaced with "***").
     */
    public String toSafeJson() {
        LoadedConfig cfg = current.get();
        if (cfg == null) {
            return "{\"loaded\":false}";
        }
        IniLoader.VmConfig v = cfg.vmConfig();
        return String.format("""
                {
                  "loaded": true,
                  "vm": {
                    "folderId":   "%s",
                    "zoneId":     "%s",
                    "vmCount":    %d,
                    "vmName":     "%s",
                    "imageId":    "%s",
                    "platformId": "%s",
                    "cpu":        %d,
                    "ramGb":      %d,
                    "diskGb":     %d,
                    "preemptible":%b
                  },
                  "coordinatorUrl": "%s",
                  "s3Endpoint":     "%s",
                  "s3AccessKeyId":  "%s",
                  "s3SecretAccessKey": "***"
                }""",
                nullSafe(v.folderId),
                nullSafe(v.zoneId),
                v.vmCount,
                nullSafe(v.vmName),
                nullSafe(v.imageId),
                nullSafe(v.platformId),
                v.cpu, v.ramGb, v.diskGb,
                v.preemptible,
                nullSafe(v.coordinatorUrl),
                nullSafe(v.s3Endpoint),
                nullSafe(v.s3AccessKeyId)
        );
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Lightweight INI key extractor — avoids needing ini4j for single-key reads.
     * Finds the first occurrence of key= under the given section header.
     */
    static String extractIniValue(String rawIni, String section, String key) {
        boolean inSection = false;
        for (String line : rawIni.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inSection = trimmed.equalsIgnoreCase("[" + section + "]");
                continue;
            }
            if (inSection && trimmed.startsWith(key)) {
                int eq = trimmed.indexOf('=');
                if (eq >= 0) {
                    return trimmed.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }
}
