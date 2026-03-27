# Orhestra Coordinator — Full API Reference

Base URL: `http://<coordinator-vm-ip>:8081`

---

## Public API `/api/v1/`

### `GET /api/v1/health`
**Проверка состояния сервера. Всегда возвращает 200 (нет auth).**

```bash
curl http://<VM>:8081/api/v1/health
```
```json
{
  "status": "healthy",
  "database": "ok",
  "version": "2.0.0",
  "uptime": "2h 15m",
  "activeSpots": 3,
  "pendingTasks": 0,
  "runningTasks": 6
}
```

---

### `GET /api/v1/spots`
**Список всех зарегистрированных SPOT-нод.**

```bash
curl http://<VM>:8081/api/v1/spots
```
```json
{
  "spots": [
    {
      "spotId": "1",
      "status": "UP",
      "ipAddress": "10.128.0.35",
      "cpuLoad": 12.5,
      "runningTasks": 2,
      "totalCores": 2,
      "lastHeartbeat": "2026-03-27T10:00:00Z"
    }
  ]
}
```

---

### `POST /api/v1/jobs`
**Создать задание. `PayloadGenerator` раскладывает параметры в декартово произведение задач.**

```bash
curl -X POST http://<VM>:8081/api/v1/jobs \
  -H 'Content-Type: application/json' \
  -d '{
    "artifactBucket":   "testtest",
    "artifactKey":      "coa-algorithm-jar-with-dependencies.jar",
    "artifactEndpoint": "https://storage.yandexcloud.kz",
    "mainClass":        "algoritm.Optimizer",
    "parameters": [
      {
        "groupId": "algorithm",
        "params": {
          "function": { "type": "ENUM_LIST", "values": ["sphere", "ackley"] }
        }
      },
      {
        "groupId": "run",
        "params": {
          "iterations": { "type": "INT_RANGE", "min": 50, "max": 150, "step": 50 },
          "agents":     { "type": "CONSTANT", "value": 30 },
          "dimension":  { "type": "CONSTANT", "value": 10 }
        }
      }
    ]
  }'
```
```json
{ "success": true, "jobId": "550e8400-...", "totalTasks": 6 }
```

**Типы параметров:**

| `type` | Поля | Пример |
|---|---|---|
| `CONSTANT` | `value` | `{"type":"CONSTANT","value":30}` |
| `INT_RANGE` | `min`, `max`, `step` | `{"type":"INT_RANGE","min":50,"max":150,"step":50}` |
| `FLOAT_RANGE` | `min`, `max`, `step` | `{"type":"FLOAT_RANGE","min":0.1,"max":1.0,"step":0.1}` |
| `ENUM_LIST` | `values: [...]` | `{"type":"ENUM_LIST","values":["sphere","ackley"]}` |

---

### `GET /api/v1/jobs/{jobId}`
**Статус задания.**

```bash
curl http://<VM>:8081/api/v1/jobs/550e8400-...
```
```json
{
  "jobId": "550e8400-...",
  "status": "RUNNING",
  "totalTasks": 6,
  "completedTasks": 4,
  "failedTasks": 0,
  "createdAt": "2026-03-27T10:00:00Z",
  "startedAt":  "2026-03-27T10:00:05Z",
  "finishedAt": null
}
```
Статусы: `PENDING` → `RUNNING` → `COMPLETED` / `FAILED`

---

### `GET /api/v1/jobs/{jobId}/results`
**Полные результаты всех задач задания.**

```bash
curl http://<VM>:8081/api/v1/jobs/550e8400-.../results
```
```json
{
  "jobId": "550e8400-...",
  "totalCompleted": 6,
  "results": [
    {
      "taskId":     "abc-123",
      "status":     "DONE",
      "algorithm":  "sphere",
      "iterations": 100,
      "runtimeMs":  1234,
      "iter":       100,
      "fopt":       0.000165,
      "assignedTo": "1"
    }
  ]
}
```

---

### `GET /api/v1/parameter-schema`
**Схема параметров для рендеринга формы в плагине.**

```bash
curl http://<VM>:8081/api/v1/parameter-schema
```

---

## Admin API `/api/v1/admin/`

> Используется IntelliJ-плагином или curl для управления координатором.

---

### `POST /api/v1/admin/config`
**Загрузить `config.ini` — тело запроса это RAW текст INI-файла.**

Требуется один раз после запуска (или при смене конфига).

```bash
curl -X POST http://<VM>:8081/api/v1/admin/config \
  --data-binary @/path/to/config.ini
```
```json
{ "success": true, "message": "Config loaded successfully" }
```

**Ошибки:**
- `400` — не заполнены обязательные секции `[AUTH]`, `[NETWORK]`, `[VM]`, `[SSH]`

**Альтернатива — env var при запуске сервера:**
```bash
ORHESTRA_CONFIG_PATH=/etc/orhestra/config.ini java -jar coordinator.jar
```
Если файл найден, конфиг загружается автоматически при старте.

---

### `GET /api/v1/admin/config`
**Текущий загруженный конфиг (секреты замаскированы `***`).**

```bash
curl http://<VM>:8081/api/v1/admin/config
```
```json
{
  "loaded": true,
  "vm": {
    "folderId":   "ao7rbcu8sanjgt0j2083",
    "zoneId":     "kz1-a",
    "vmCount":    2,
    "vmName":     "spot",
    "imageId":    "fbnebqlil2b56v15th61",
    "platformId": "standard-v3",
    "cpu":        2,
    "ramGb":      4,
    "diskGb":     20,
    "preemptible": true
  },
  "coordinatorUrl":     "http://10.128.0.10:8081",
  "s3Endpoint":         "https://storage.yandexcloud.kz",
  "s3AccessKeyId":      "YCXXXXXX",
  "s3SecretAccessKey":  "***"
}
```

---

### `POST /api/v1/admin/spots/create`
**Создать SPOT VM через Yandex Cloud API.**

Требует загруженного конфига (`POST /api/v1/admin/config` first).
Возвращает **202 Accepted** немедленно — VM создаётся асинхронно (~2-5 мин).

```bash
# Создать vm_count VM из конфига
curl -X POST http://<VM>:8081/api/v1/admin/spots/create

# Создать конкретное число VM
curl -X POST "http://<VM>:8081/api/v1/admin/spots/create?count=3"
```
```json
{
  "accepted": true,
  "vmCount": 3,
  "message": "VM creation started. Poll /api/v1/admin/spots/create/status for updates."
}
```

**Ошибки:**
- `400` — конфиг не загружен
- `409` — создание уже запущено

---

### `GET /api/v1/admin/spots/create/status`
**Статус последнего/текущего создания VM.**

```bash
curl http://<VM>:8081/api/v1/admin/spots/create/status
```
```json
{
  "state":      "SUCCESS",
  "message":    "Successfully created 2 SPOT VM(s)",
  "vmCount":    2,
  "startedAt":  "2026-03-27T10:05:00Z",
  "finishedAt": "2026-03-27T10:07:30Z"
}
```

Возможные `state`: `IDLE` | `RUNNING` | `SUCCESS` | `ERROR`

---

## Internal API `/internal/v1/`

> Используется **только SPOT-нодами** автоматически.

| Метод | Путь | Описание |
|---|---|---|
| `POST` | `/internal/v1/hello` | Регистрация SPOT при старте |
| `POST` | `/internal/v1/heartbeat` | Пинг каждые 5 сек |
| `POST` | `/internal/v1/tasks/claim` | Получить задачи для выполнения |
| `POST` | `/internal/v1/tasks/{id}/complete` | Сообщить о завершении |
| `POST` | `/internal/v1/tasks/{id}/fail` | Сообщить об ошибке |

---

## Типичный сценарий работы

```
1. Запустить координатор на VM
   → java -jar coordinator.jar

2. Загрузить конфиг (один раз)
   → POST /api/v1/admin/config  (body = content of config.ini)

3. Создать SPOT ноды
   → POST /api/v1/admin/spots/create?count=2
   → GET  /api/v1/admin/spots/create/status  (ждём SUCCESS)

4. SPOT ноды автоматически регаются
   → GET /api/v1/spots  (видим их в статусе UP)

5. Создать задание из плагина/curl
   → POST /api/v1/jobs

6. Следить за выполнением
   → GET /api/v1/jobs/{id}
   → GET /api/v1/jobs/{id}/results
```
