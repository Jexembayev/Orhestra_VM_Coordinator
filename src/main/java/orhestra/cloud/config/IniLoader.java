package orhestra.cloud.config;

import org.ini4j.Ini;
import org.ini4j.Profile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Загружает настройки облака и ВМ из INI-файла.
 * Поддерживает секции [AUTH], [NETWORK], [VM], [SSH], [OVPN] (опц.), [S3] (опц.), [COORDINATOR] (опц.).
 */
public class IniLoader {

    /** Полная конфигурация (для экрана/проверок/списков ВМ). */
    public static Optional<CloudConfig> load(File file) {
        try {
            Ini ini = new Ini(file);

            Profile.Section auth = ini.get("AUTH");
            Profile.Section net  = ini.get("NETWORK");
            Profile.Section vm   = ini.get("VM");
            Profile.Section ssh  = ini.get("SSH");
            Profile.Section ovpn = ini.get("OVPN"); // опционально

            if (auth == null || net == null || vm == null || ssh == null)
                return Optional.empty();

            String keyPath = opt(ssh, "public_key_path");
            String keyText = opt(ssh, "public_key");
            if ((keyText == null || keyText.isBlank()) && keyPath != null && !keyPath.isBlank()) {
                keyText = Files.readString(new File(keyPath).toPath()).trim();
            }
            if (keyText == null || keyText.isBlank()) return Optional.empty();

            CloudConfig cfg = new CloudConfig();

            // AUTH
            cfg.oauthToken = opt(auth, "oauth_token"); // можно оставить null — AuthService возьмёт из ENV
            cfg.cloudId    = opt(auth, "cloud_id");
            cfg.folderId   = auth.fetch("folder_id");
            cfg.zoneId     = auth.fetch("zone_id");

            // NETWORK
            cfg.networkId       = opt(net, "network_id");
            cfg.subnetId        = net.fetch("subnet_id");
            cfg.securityGroupId = net.fetch("security_group_id");
            cfg.publicIp        = Boolean.parseBoolean(opt(net, "public_ip", "true"));

            // VM
            cfg.vmCount    = Integer.parseInt(vm.fetch("vm_count"));
            cfg.vmName     = vm.fetch("vm_name");
            cfg.imageId    = vm.fetch("image_id");
            cfg.platformId = vm.fetch("platform_id");
            cfg.cpu        = Integer.parseInt(vm.fetch("cpu"));
            cfg.ramGb      = Integer.parseInt(vm.fetch("ram_gb"));
            cfg.diskGb     = Integer.parseInt(vm.fetch("disk_gb"));

            // SSH
            cfg.sshUser      = ssh.fetch("user");
            cfg.sshPublicKey = keyText;

            // OVPN (опционально)
            if (ovpn != null) {
                String id  = opt(ovpn, "instance_id");
                String tag = opt(ovpn, "tag");
                if (id != null && !id.isBlank())  cfg.ovpnInstanceId = id.trim();
                if (tag != null && !tag.isBlank()) cfg.ovpnTag = tag.trim();
            }

            // S3 / Object Storage (опционально)
            Profile.Section s3 = ini.get("S3");
            if (s3 != null) {
                cfg.s3Endpoint        = opt(s3, "endpoint");
                cfg.s3Bucket          = opt(s3, "bucket");
                cfg.s3AccessKeyId     = opt(s3, "access_key_id");
                cfg.s3SecretAccessKey = opt(s3, "secret_access_key");
            }

            return Optional.of(cfg);
        } catch (IOException | RuntimeException ex) {
            ex.printStackTrace();
            return Optional.empty();
        }
    }

    /** Упрощённая конфигурация только для создания ВМ (используется VMCreator). */
    // IniLoader.java  (метод loadVmConfig)
    // orhestra/cloud/config/IniLoader.java  (фрагмент: метод loadVmConfig)
    public static Optional<VmConfig> loadVmConfig(File file) {
        try {
            Ini ini = new Ini(file);

            Profile.Section auth = ini.get("AUTH");
            Profile.Section net  = ini.get("NETWORK");
            Profile.Section vm   = ini.get("VM");
            Profile.Section ssh  = ini.get("SSH");

            if (auth == null || net == null || vm == null || ssh == null)
                return Optional.empty();

            // прочитаем ключ либо из текста, либо из файла
            String keyPath = opt(ssh, "public_key_path");
            String keyText = opt(ssh, "public_key");
            if ((keyText == null || keyText.isBlank()) && keyPath != null && !keyPath.isBlank()) {
                keyText = Files.readString(new File(keyPath).toPath()).trim();
            }
            if (keyText == null || keyText.isBlank()) return Optional.empty();

            VmConfig cfg = new VmConfig(
                    auth.fetch("folder_id"),
                    auth.fetch("zone_id"),
                    Integer.parseInt(vm.fetch("vm_count")),
                    vm.fetch("vm_name"),
                    vm.fetch("image_id"),
                    vm.fetch("platform_id"),
                    Integer.parseInt(vm.fetch("cpu")),
                    Integer.parseInt(vm.fetch("ram_gb")),
                    Integer.parseInt(vm.fetch("disk_gb")),
                    net.fetch("subnet_id"),
                    opt(net, "security_group_id"),
                    Boolean.parseBoolean(opt(net, "public_ip", "false")),
                    ssh.fetch("user"),
                    Boolean.parseBoolean(opt(vm, "preemptible", "true"))
            );
            cfg.sshKey = keyText.trim();

            // S3 / Object Storage (опционально)
            Profile.Section s3 = ini.get("S3");
            if (s3 != null) {
                cfg.s3Endpoint        = opt(s3, "endpoint");
                cfg.s3AccessKeyId     = opt(s3, "access_key_id");
                cfg.s3SecretAccessKey = opt(s3, "secret_access_key");
            }

            // COORDINATOR (опционально — URL координатора, который SPOT-агент получит в cloud-init)
            Profile.Section coord = ini.get("COORDINATOR");
            if (coord != null) {
                cfg.coordinatorUrl = opt(coord, "url");
            }

            return Optional.of(cfg);
        } catch (IOException | RuntimeException ex) {
            ex.printStackTrace();
            return Optional.empty();
        }
    }



    // ===== helpers =====
    private static String opt(Profile.Section s, String key) {
        return s == null ? null : s.get(key);
    }

    private static String opt(Profile.Section s, String key, String def) {
        String v = opt(s, key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    // ===== внутренняя модель VmConfig =====
    public static class VmConfig {
        public final String folderId;
        public final String zoneId;
        public final int vmCount;
        public final String vmName;
        public final String imageId;
        public final String platformId;
        public String sshKey;
        public final int cpu;
        public final int ramGb;
        public final int diskGb;
        public final String subnetId;
        public final String securityGroupId;
        public final boolean assignPublicIp;
        public final String userName;
        public final boolean preemptible;

        // S3 — опционально, прокидывается в cloud-init
        public String s3Endpoint;
        public String s3AccessKeyId;
        public String s3SecretAccessKey;

        // Coordinator URL — прокидывается в COORDINATOR_URL в /etc/default/spot-agent
        // Читается из [COORDINATOR] url = http://<vm-ip>:8081
        public String coordinatorUrl;

        public VmConfig(String folderId, String zoneId, int vmCount,
                        String vmName, String imageId, String platformId,
                        int cpu, int ramGb, int diskGb,
                        String subnetId, String securityGroupId, boolean assignPublicIp,
                        String userName, boolean preemptible) {
            this.folderId = folderId;
            this.zoneId = zoneId;
            this.vmCount = vmCount;
            this.vmName = vmName;
            this.imageId = imageId;
            this.platformId = platformId;
            this.cpu = cpu;
            this.ramGb = ramGb;
            this.diskGb = diskGb;
            this.subnetId = subnetId;
            this.securityGroupId = securityGroupId;
            this.assignPublicIp = assignPublicIp;
            this.userName = userName;
            this.preemptible = preemptible;
        }

        @Override
        public String toString() {
            return "[VmConfig] " + vmName + " ×" + vmCount +
                    " | CPU=" + cpu + " RAM=" + ramGb + "GB DISK=" + diskGb + "GB";
        }
    }
}


