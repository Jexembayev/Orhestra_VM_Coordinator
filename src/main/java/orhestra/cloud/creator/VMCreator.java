package orhestra.cloud.creator;

import com.google.protobuf.InvalidProtocolBufferException;
import orhestra.cloud.auth.AuthService;
import orhestra.cloud.config.IniLoader;
import yandex.cloud.api.compute.v1.InstanceOuterClass;
import yandex.cloud.api.compute.v1.InstanceServiceOuterClass;
import yandex.cloud.api.operation.OperationOuterClass;
import yandex.cloud.sdk.utils.OperationUtils;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

// orhestra/cloud/creator/VMCreator.java
public class VMCreator {

        private static final long GB = 1024L * 1024 * 1024;
        private final AuthService auth;

        public VMCreator(AuthService auth) {
                this.auth = auth;
        }

        /** Create exactly 1 SPOT VM regardless of cfg.vmCount (used by AutoScaler). */
        public void createOne(IniLoader.VmConfig cfg, String coordinatorUrl)
                        throws InvalidProtocolBufferException, InterruptedException {
                createMany(cfg, coordinatorUrl, 1);
        }

        public void createMany(IniLoader.VmConfig cfg, String coordinatorUrl)
                        throws InvalidProtocolBufferException, InterruptedException {
                createMany(cfg, coordinatorUrl, cfg.vmCount);
        }

        public void createMany(IniLoader.VmConfig cfg, String coordinatorUrl, int count)
                        throws InvalidProtocolBufferException, InterruptedException {

                for (int i = 0; i < count; i++) {
                        String name = cfg.vmName + "-" + UUID.randomUUID();

                        // Resolve coordinator URL: explicit param wins, then INI [COORDINATOR] url
                        String effectiveCoordinatorUrl = (coordinatorUrl != null && !coordinatorUrl.isBlank())
                                ? coordinatorUrl
                                : cfg.coordinatorUrl;
                        if (effectiveCoordinatorUrl == null || effectiveCoordinatorUrl.isBlank()) {
                                throw new IllegalStateException(
                                        "coordinatorUrl not set — add [COORDINATOR] url = http://<vm-ip>:8081 to config.ini");
                        }

                        // Build /etc/default/spot-agent env file
                StringBuilder envFile = new StringBuilder();
                envFile.append("COORDINATOR_URL=").append(effectiveCoordinatorUrl).append("\n");
                if (cfg.s3Endpoint != null && !cfg.s3Endpoint.isBlank()) {
                    envFile.append("S3_ENDPOINT=").append(cfg.s3Endpoint).append("\n");
                }
                if (cfg.s3AccessKeyId != null && !cfg.s3AccessKeyId.isBlank()) {
                    envFile.append("S3_ACCESS_KEY_ID=").append(cfg.s3AccessKeyId).append("\n");
                    envFile.append("S3_SECRET_ACCESS_KEY=").append(
                            cfg.s3SecretAccessKey != null ? cfg.s3SecretAccessKey : "").append("\n");
                }

                String cloudInit = """
                                        #cloud-config
                                        datasource:
                                          Ec2:
                                            strict_id: false
                                        ssh_pwauth: no

                                        users:
                                          - name: %s
                                            sudo: ALL=(ALL) NOPASSWD:ALL
                                            shell: /bin/bash
                                            ssh_authorized_keys:
                                              - %s

                                        write_files:
                                          - path: /etc/default/spot-agent
                                            permissions: '0600'
                                            content: |
                                        %s
                                        runcmd:
                                          - |
                                            cat >/etc/systemd/system/spot-agent.service <<'EOF'
                                            [Unit]
                                            Description=Orhestra Spot Agent
                                            After=network-online.target
                                            Wants=network-online.target

                                            [Service]
                                            User=spot
                                            EnvironmentFile=/etc/default/spot-agent
                                            WorkingDirectory=/home/spot
                                            ExecStart=/usr/bin/java -jar /home/spot/spot-agent-jar-with-dependencies.jar
                                            Restart=always
                                            RestartSec=5

                                            [Install]
                                            WantedBy=multi-user.target
                                            EOF

                                          - systemctl daemon-reload
                                          - systemctl enable --now spot-agent
                                        """
                                        .formatted(
                                            cfg.userName,
                                            cfg.sshKey,
                                            envFile.toString().indent(8).stripTrailing());

                        InstanceServiceOuterClass.CreateInstanceRequest req = buildCreateInstanceRequest(
                                        cfg.folderId, cfg.zoneId, cfg.platformId,
                                        name, cfg.imageId, cfg.subnetId,
                                        (cfg.securityGroupId == null || cfg.securityGroupId.isBlank())
                                                        ? List.of()
                                                        : List.of(cfg.securityGroupId),
                                        cfg.cpu, cfg.ramGb, cfg.diskGb,
                                        cfg.assignPublicIp,
                                        cloudInit);

                        OperationOuterClass.Operation op = auth.getInstanceService().create(req);

                        String instanceId = op.getMetadata()
                                        .unpack(InstanceServiceOuterClass.CreateInstanceMetadata.class)
                                        .getInstanceId();
                        System.out.printf("[INFO] Create sent: name=%s id=%s%n", name, instanceId);

                        OperationUtils.wait(auth.getOperationService(), op, Duration.ofMinutes(5));
                        System.out.printf("[OK] VM created: %s (id=%s)%n", name, instanceId);
                }
        }

        // «Топорная» сборка запроса строго из конфига
        private static InstanceServiceOuterClass.CreateInstanceRequest buildCreateInstanceRequest(
                        String folderId,
                        String zoneId,
                        String platformId,
                        String name,
                        String imageId,
                        String subnetId,
                        List<String> securityGroupIds,
                        int cpu,
                        int ramGb,
                        int diskGb,
                        boolean assignPublicIp,
                        String cloudInitUserData) {
                var resources = InstanceServiceOuterClass.ResourcesSpec.newBuilder()
                                .setCores(cpu)
                                .setMemory(ramGb * GB)
                                .build();

                var disk = InstanceServiceOuterClass.AttachedDiskSpec.DiskSpec.newBuilder()
                                .setImageId(imageId)
                                .setSize(diskGb * GB)
                                .build();

                var boot = InstanceServiceOuterClass.AttachedDiskSpec.newBuilder()
                                .setAutoDelete(true)
                                .setDiskSpec(disk)
                                .build();

                var nic = InstanceServiceOuterClass.NetworkInterfaceSpec.newBuilder()
                                .setSubnetId(subnetId);

                if (securityGroupIds != null && !securityGroupIds.isEmpty()) {
                        nic.addAllSecurityGroupIds(securityGroupIds);
                }

                var addr = InstanceServiceOuterClass.PrimaryAddressSpec.newBuilder();
                if (assignPublicIp) {
                        addr.setOneToOneNatSpec(
                                        InstanceServiceOuterClass.OneToOneNatSpec.newBuilder()
                                                        .setIpVersion(InstanceOuterClass.IpVersion.IPV4)
                                                        .build());
                }
                nic.setPrimaryV4AddressSpec(addr.build());

                return InstanceServiceOuterClass.CreateInstanceRequest.newBuilder()
                                .setFolderId(folderId)
                                .setName(name)
                                .setZoneId(zoneId)
                                .setPlatformId(platformId)
                                .setResourcesSpec(resources)
                                .setBootDiskSpec(boot)
                                .addNetworkInterfaceSpecs(nic)
                                .putMetadata("user-data", cloudInitUserData)
                                .setSchedulingPolicy(
                                                InstanceOuterClass.SchedulingPolicy.newBuilder()
                                                                .setPreemptible(true)
                                                                .build())
                                .build();
        }
}
