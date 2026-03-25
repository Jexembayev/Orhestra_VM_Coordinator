# Orhestra Coordinator — VM Deployment Guide

## 1. Build the fat JAR

```bash
# On your dev machine (in the project root):
mvn package -DskipTests

# The deployable JAR will be at:
# target/OrhestraV2-2.0-SNAPSHOT-jar-with-dependencies.jar
```

---

## 2. Copy to VM

```bash
VM_IP=<your-vm-public-ip>

scp target/OrhestraV2-*-jar-with-dependencies.jar user@$VM_IP:/opt/orhestra/coordinator.jar
scp src/main/resources/parameters.json user@$VM_IP:/opt/orhestra/parameters.json
```

---

## 3. First-time VM setup

SSH into the VM and run:

```bash
# Install Java 17
sudo apt update && sudo apt install -y openjdk-17-jre-headless

# Create service user and directory
sudo useradd -r -s /sbin/nologin orhestra
sudo mkdir -p /opt/orhestra/data
sudo chown -R orhestra:orhestra /opt/orhestra

# Create the env-file
sudo tee /etc/default/orhestra-coordinator <<'EOF'
ORHESTRA_PORT=8081
ORHESTRA_DB_URL=jdbc:h2:file:/opt/orhestra/data/orhestra;AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_UPPER=FALSE
ORHESTRA_S3_ENDPOINT=https://storage.yandexcloud.kz
ORHESTRA_S3_BUCKET=orhestra-algorithms
EOF

# Install the systemd service
sudo cp orhestra-coordinator.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now orhestra-coordinator
```

---

## 4. Start / stop / logs

```bash
sudo systemctl start   orhestra-coordinator
sudo systemctl stop    orhestra-coordinator
sudo systemctl restart orhestra-coordinator

# Live logs
journalctl -u orhestra-coordinator -f
```

---

## 5. Verify it's running

```bash
# From the VM itself:
curl http://localhost:8081/api/v1/health

# From outside:
curl http://<VM_PUBLIC_IP>:8081/api/v1/health
```

Expected response:
```json
{"status":"healthy","version":"2.0.0","activeSpots":0,"pendingTasks":0,...}
```

---

## 6. Required firewall / security group rule

| Port | Protocol | Direction | Who |
|------|----------|-----------|-----|
| `8081` | TCP | **inbound** | IntelliJ plugin + SPOT nodes |

Open this port in Yandex Cloud Console → Virtual Private Cloud → Security Groups.

---

## 7. SPOT node configuration

When creating SPOT VMs, the cloud-init sets:

```
COORDINATOR_URL=http://<coordinator-vm-INTERNAL-ip>:8081
```

Use the **internal IP** for SPOT→Coordinator communication (faster, no egress cost).  
Use the **public IP** from the IntelliJ plugin.

---

## 8. plugin endpoint URLs

Configure the IntelliJ plugin to call:

| Endpoint | Purpose |
|---|---|
| `GET  http://<VM>:8081/api/v1/health` | Health check |
| `POST http://<VM>:8081/api/v1/jobs` | Create job |
| `GET  http://<VM>:8081/api/v1/jobs/{id}` | Job status |
| `GET  http://<VM>:8081/api/v1/jobs/{id}/results` | Results |
| `GET  http://<VM>:8081/api/v1/spots` | List spots |
| `GET  http://<VM>:8081/api/v1/parameter-schema` | Param schema |

---

## 9. Alternative: Docker

```bash
# Build image
docker build -t orhestra-coordinator .

# Run
docker run -d \
  --name coordinator \
  -p 8081:8081 \
  -v $(pwd)/data:/app/data \
  -e ORHESTRA_S3_ENDPOINT=https://storage.yandexcloud.kz \
  -e ORHESTRA_S3_BUCKET=orhestra-algorithms \
  orhestra-coordinator
```

---

## 10. Updating the Coordinator

```bash
# 1. Build new JAR on dev machine
mvn package -DskipTests

# 2. Copy to VM
scp target/OrhestraV2-*-jar-with-dependencies.jar user@$VM_IP:/opt/orhestra/coordinator.jar

# 3. Restart service
ssh user@$VM_IP "sudo systemctl restart orhestra-coordinator"
```
