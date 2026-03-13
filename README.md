# Orhestra — Distributed Optimization Platform

Система распределённого запуска оптимизационных алгоритмов. Coordinator управляет заданиями (Jobs), создаёт задачи (Tasks), раздаёт их SPOT-воркерам через HTTP и хранит результаты в SQLite/H2 БД.

---

## Содержание

1. [Архитектура](#архитектура)
2. [Быстрый старт](#быстрый-старт)
3. [Конфигурация](#конфигурация)
4. [Публичный API (`/api/v1/`)](#публичный-api-apiv1)
5. [Внутренний API (`/internal/v1/`)](#внутренний-api-internalv1)
6. [Схема базы данных](#схема-базы-данных)
7. [Модель параметров задачи](#модель-параметров-задачи)
8. [Создание SPOT-воркеров в Yandex Cloud](#создание-spot-воркеров-в-yandex-cloud)
9. [Формат `config.ini`](#формат-configini)
10. [Структура проекта](#структура-проекта)
11. [Примеры запросов (curl)](#примеры-запросов-curl)

---

## Архитектура

```
┌─────────────────────────────────────────────────────────┐
│                   Coordinator (JavaFX + Netty)           │
│                                                          │
│  ┌──────────┐  ┌──────────────┐  ┌────────────────────┐ │
│  │ /api/v1/ │  │/internal/v1/ │  │  SQLite/H2 + Pool  │ │
│  │ (clients)│  │  (SPOT agents│  │  jobs / tasks /    │ │
│  └────┬─────┘  └──────┬───────┘  │  spots tables      │ │
│       │               │          └────────────────────┘ │
│  ┌────▼───────────────▼──────────────────────────────┐  │
│  │  Services: JobService · TaskService · SpotService │  │
│  └────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────┐   │
│  │  Scheduler: TaskReaper · SpotReaper               │   │
│  └───────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
         ▲                          ▲
         │ POST /api/v1/jobs        │ claim / complete / heartbeat
   ┌─────┴──────┐            ┌──────┴──────────────────────────┐
   │  UI / curl │            │  SPOT Agents (cloud VMs)         │
   │  (creates  │            │  · download JAR from S3          │
   │   jobs)    │            │  · run algorithm                 │
   └────────────┘            │  · report fopt / bestPos         │
                             └──────────────────────────────────┘
```

**Поток выполнения:**
1. Клиент отправляет `POST /api/v1/jobs` с параметрами (S3-артефакт + диапазоны параметров).
2. `PayloadGenerator` раскладывает диапазоны в декартово произведение → создаёт N задач.
3. SPOT-агент вызывает `POST /internal/v1/tasks/claim`, получает payload задачи.
4. SPOT скачивает JAR из S3, запускает его, передаёт параметры через `-Dgroup.param=value`.
5. Алгоритм печатает `RESULT_JSON={"iter":100,"fopt":0.165,"bestPos":[...]}` в stdout.
6. SPOT отправляет `POST /internal/v1/tasks/{id}/complete` с `iter`, `fopt`, `bestPos`.
7. Результаты доступны через `GET /api/v1/jobs/{id}/results` или экспорт CSV в UI.

---

## Быстрый старт

### Требования

- **Java** 17+
- **Maven** 3.9+

### Запуск координатора

```bash
# Из корня проекта — запускает координатор + JavaFX UI
mvn javafx:run

# Или скомпилировать и запустить JAR
mvn package -DskipTests
java -jar target/OrhestraV2-*.jar
```

Coordinator стартует на **`http://127.0.0.1:8081`** по умолчанию.

> Порт настраивается через `ORHESTRA_PORT` или из UI вкладки Cloud: кнопка «Запустить координатор».

### Создание первого задания

```bash
curl -X POST http://localhost:8081/api/v1/jobs \
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
# → {"success":true,"jobId":"...","totalTasks":6}
# 2 функции × 3 значения итераций = 6 задач
```

---

## Конфигурация

### Переменные окружения

| Переменная | По умолчанию | Описание |
|---|---|---|
| `ORHESTRA_PORT` | `8081` | Порт HTTP-сервера |
| `ORHESTRA_DB_URL` | `jdbc:h2:file:./data/orhestra;...` | JDBC URL базы данных |
| `ORHESTRA_AGENT_KEY` | *(не задан)* | Если задан — все `/internal/` запросы должны содержать заголовок `X-Orhestra-Key: <value>` |
| `ORHESTRA_MAX_ATTEMPTS` | `3` | Макс. число попыток на задачу до перевода в FAILED |
| `ORHESTRA_S3_ENDPOINT` | `http://localhost:9000` | S3/MinIO endpoint по умолчанию |
| `ORHESTRA_S3_BUCKET` | `orhestra-algorithms` | Bucket по умолчанию |
| `OAUTH_TOKEN` | *(не задан)* | OAuth-токен Yandex Cloud (для создания VM) |

**Жёсткие дефолты (не переопределяются):**
- Слушает на `0.0.0.0`
- Задача считается «зависшей» через **5 минут** без обновления
- TaskReaper запускается каждые **30 секунд**
- SPOT считается оффлайн через **10 секунд** без heartbeat
- SpotReaper запускается каждые **5 секунд**
- HikariCP: 10 max connections, 2 min idle

---

## Публичный API `/api/v1/`

### `GET /api/v1/health`

Проверка состояния сервера.

**Ответ 200:**
```json
{
  "status":       "healthy",
  "version":      "2.0.0",
  "uptime":       "3h 12m",
  "activeSpots":  2,
  "pendingTasks": 4,
  "runningTasks": 2,
  "database":     "ok"
}
```

**Ответ 503** (при сбое БД):
```json
{ "status": "unhealthy", "database": "<error message>" }
```

---

### `GET /api/v1/spots`

Список всех зарегистрированных SPOT-воркеров.

**Ответ 200:**
```json
{
  "spots": [
    {
      "spotId":        "1",
      "status":        "UP",
      "ipAddress":     "10.128.0.35",
      "cpuLoad":       12.5,
      "runningTasks":  1,
      "totalCores":    2,
      "lastHeartbeat": "2026-03-13T10:00:00Z"
    }
  ]
}
```

---

### `POST /api/v1/jobs`

Создать новое задание. `PayloadGenerator` раскладывает диапазоны параметров в декартово произведение → создаётся по одной задаче на каждую комбинацию.

**Тело запроса:**
```json
{
  "artifactBucket":   "my-bucket",
  "artifactKey":      "path/to/algorithm.jar",
  "artifactEndpoint": "https://storage.yandexcloud.kz",
  "mainClass":        "com.example.Main",
  "parameters": [
    {
      "groupId": "algorithm",
      "params": {
        "<paramId>": <ParameterValue>
      }
    }
  ]
}
```

**Типы ParameterValue:**

| `type` | Поля | Описание |
|---|---|---|
| `CONSTANT` | `value` | Одно фиксированное значение. Задача 1 штука. |
| `INT_RANGE` | `min`, `max`, `step` | Целые числа от min до max с шагом. `step` по умолч. 1. |
| `FLOAT_RANGE` | `min`, `max`, `step` | Вещественные числа с шагом. |
| `ENUM_LIST` | `values: [...]` | Список строк. По одной задаче на каждое значение. |

**Ответ 201:**
```json
{
  "success":    true,
  "jobId":      "550e8400-e29b-41d4-a716-446655440000",
  "totalTasks": 6
}
```

**Ошибки:**
- `400` — не заполнены обязательные поля (`artifactBucket`, `artifactKey`, `mainClass`)
- `400` — `parameters` пусты или дают 0 комбинаций

---

### `GET /api/v1/jobs/{jobId}`

Статус задания.

**Ответ 200:**
```json
{
  "jobId":           "550e8400-...",
  "status":          "RUNNING",
  "artifactBucket":  "my-bucket",
  "artifactKey":     "path/to/algorithm.jar",
  "artifactEndpoint":"https://storage.yandexcloud.kz",
  "mainClass":       "com.example.Main",
  "totalTasks":      6,
  "completedTasks":  4,
  "failedTasks":     0,
  "createdAt":       "2026-03-13T10:00:00Z",
  "startedAt":       "2026-03-13T10:00:05Z",
  "finishedAt":      null
}
```

Статусы задания: `PENDING` → `RUNNING` → `COMPLETED` / `FAILED`

**Ошибки:** `404` если задание не найдено.

---

### `GET /api/v1/jobs/{jobId}/results`

Полные результаты всех задач задания.

**Ответ 200:**
```json
{
  "jobId":          "550e8400-...",
  "totalCompleted": 6,
  "results": [
    {
      "taskId":       "abc-123",
      "status":       "DONE",
      "algorithm":    "sphere",
      "iterations":   100,
      "agents":       30,
      "dimension":    10,
      "runtimeMs":    1234,
      "iter":         100,
      "fopt":         0.165696,
      "assignedTo":   "1",
      "startedAt":    "2026-03-13T10:00:10Z",
      "finishedAt":   "2026-03-13T10:00:11Z",
      "errorMessage": null
    }
  ]
}
```

| Поле | Описание |
|---|---|
| `algorithm` | Значение `algorithm.function` из payload |
| `iterations` | Параметр `run.iterations` из payload (что задали) |
| `iter` | Реальное число итераций от алгоритма |
| `fopt` | Лучшее найденное значение функции |

**Ошибки:** `404` если задание не найдено.

---

### `GET /api/v1/parameter-schema`

Схема параметров в формате JSON — используется UI для авторендеринга форм.

**Ответ 200** — содержимое файла `src/main/resources/parameters.json`:
```json
{
  "groups": [
    {
      "id": "algorithm",
      "label": "Algorithm",
      "parameters": [
        {
          "id": "function",
          "label": "Test Function",
          "type": "ENUM",
          "options": ["sphere", "ackley", "rosenbrock", "rastrigin", "michalewicz"]
        }
      ]
    },
    {
      "id": "run",
      "label": "Run Config",
      "parameters": [
        { "id": "iterations", "label": "Iterations", "type": "INT_RANGE" },
        { "id": "agents",     "label": "Agents",     "type": "INT_RANGE" },
        { "id": "dimension",  "label": "Dimension",  "type": "INT_RANGE" }
      ]
    }
  ]
}
```

Чтобы добавить новый алгоритм или параметр — отредактировать `parameters.json` без перекомпиляции.

---

## Внутренний API `/internal/v1/`

> Используется только SPOT-агентами. Если задана переменная `ORHESTRA_AGENT_KEY`, все запросы должны содержать заголовок `X-Orhestra-Key: <key>`.

---

### `POST /internal/v1/hello`

Регистрация SPOT-агента при старте.

**Тело запроса** (можно передать пустое):
```json
{
  "spotInfo": {
    "host":          "10.128.0.35",
    "cpuCores":      2,
    "ramMb":         3914,
    "maxConcurrent": 1
  }
}
```

**Ответ 200:**
```json
{
  "spotId":              "1",
  "coordinatorVersion":  "2.0.0"
}
```

`spotId` — целое число в строке, назначается порядково (1, 2, 3 ...).

---

### `POST /internal/v1/heartbeat`

Периодический пинг от SPOT (каждые ~5 секунд). Если heartbeat не приходит **10 секунд** — SPOT переводится в DOWN.

**Тело запроса:**
```json
{
  "spotId":       "1",
  "cpuLoad":      12.5,
  "runningTasks": 1,
  "totalCores":   2,
  "ramUsedMb":    489,
  "ramTotalMb":   3914
}
```

**Ответ 200:** `{"success": true}`

---

### `POST /internal/v1/tasks/claim`

SPOT запрашивает задачи для выполнения.

**Тело запроса:**
```json
{
  "spotId":   "1",
  "maxTasks": 1
}
```

**Ответ 200:**
```json
{
  "tasks": [
    {
      "taskId":  "abc-123",
      "jobId":   "550e8400-...",
      "payload": {
        "artifactBucket":   "testtest",
        "artifactKey":      "coa-algorithm-jar-with-dependencies.jar",
        "artifactEndpoint": "https://storage.yandexcloud.kz",
        "mainClass":        "algoritm.Optimizer",
        "params": {
          "algorithm.function": "sphere",
          "run.iterations":     100,
          "run.agents":         30,
          "run.dimension":      10
        }
      }
    }
  ]
}
```

SPOT передаёт `params` алгоритму как Java system properties: `-Dalgorithm.function=sphere -Drun.iterations=100 ...`

---

### `POST /internal/v1/tasks/{taskId}/complete`

SPOT сообщает об успешном завершении. Идемпотентен.

**Тело запроса:**
```json
{
  "spotId":    "1",
  "runtimeMs": 1234,
  "iter":      100,
  "fopt":      0.165696,
  "bestPos":   [0.001, -0.002, 0.0003]
}
```

| Поле | Описание |
|---|---|
| `runtimeMs` | Время работы алгоритма в мс |
| `iter` | Итерация, на которой найдено лучшее значение |
| `fopt` | Лучшее значение функции (чем меньше — тем лучше) |
| `bestPos` | Координаты найденного минимума (вектор длиной `dimension`) |

**Ответы:**
- `200 {"success": true}` — принято
- `200 {"success": true, "message": "already completed"}` — уже было, идемпотент
- `404 {"success": false, "error": "task not found"}`
- `409 {"success": false, "error": "task not assigned to this spot"}`

---

### `POST /internal/v1/tasks/{taskId}/fail`

SPOT сообщает об ошибке. Идемпотентен.

**Тело запроса:**
```json
{
  "spotId":    "1",
  "error":     "Exit code 1: java.lang.OutOfMemoryError",
  "retriable": true
}
```

**Ответы:**
- `200 {"success": true, "willRetry": true}` — задача поставлена в очередь повторно (если `attempts < maxAttempts`)
- `200 {"success": true, "willRetry": false}` — задача окончательно FAILED
- `200 {"success": true, "message": "already terminal"}` — уже была завершена

---

## Схема базы данных

> База: **H2** (embedded, файл `./data/orhestra.mv.db`). При каждом старте данные **удаляются** (clean slate).  
> При смене на PostgreSQL — достаточно поменять `ORHESTRA_DB_URL`.

### Таблица `jobs`

| Колонка | Тип | Описание |
|---|---|---|
| `id` | `VARCHAR(64) PK` | UUID задания |
| `artifact_bucket` | `VARCHAR(256)` | S3 bucket с JAR-файлом |
| `artifact_key` | `VARCHAR(1024)` | Путь к JAR в S3 |
| `artifact_endpoint` | `VARCHAR(512)` | URL S3-эндпоинта |
| `jar_path` | `VARCHAR(1024)` | Устаревшее поле (null для новых заданий) |
| `main_class` | `VARCHAR(256) NOT NULL` | Полное имя главного класса |
| `config` | `CLOB NOT NULL` | JSON с параметрами задания |
| `status` | `VARCHAR(20)` | `PENDING` / `RUNNING` / `COMPLETED` / `FAILED` |
| `total_tasks` | `INT` | Всего задач создано |
| `completed_tasks` | `INT` | Успешно завершено |
| `failed_tasks` | `INT` | Завершено с ошибкой |
| `created_at` | `TIMESTAMP` | Время создания |
| `started_at` | `TIMESTAMP` | Время начала выполнения первой задачи |
| `finished_at` | `TIMESTAMP` | Время завершения последней задачи |

Индекс: `idx_jobs_status (status)`

---

### Таблица `tasks`

| Колонка | Тип | Описание |
|---|---|---|
| `id` | `VARCHAR(64) PK` | UUID задачи |
| `job_id` | `VARCHAR(64)` | Ссылка на `jobs.id` |
| `payload` | `CLOB NOT NULL` | JSON с конкретными значениями параметров (артефакт + params) |
| `status` | `VARCHAR(20)` | `NEW` / `RUNNING` / `DONE` / `FAILED` / `CANCELLED` |
| `assigned_to` | `VARCHAR(64)` | `spotId` или `null` |
| `priority` | `INT` | Приоритет (больше = первее) |
| `attempts` | `INT` | Сколько раз пытались выполнить |
| `max_attempts` | `INT` | Макс. попыток (по умолч. 3) |
| `error_message` | `VARCHAR(2048)` | Сообщение последней ошибки |
| `created_at` | `TIMESTAMP` | Время создания |
| `started_at` | `TIMESTAMP` | Время взятия в работу |
| `finished_at` | `TIMESTAMP` | Время завершения |
| `runtime_ms` | `BIGINT` | Время выполнения алгоритма, мс |
| `iter` | `INT` | Итерация с лучшим значением (от алгоритма) |
| `fopt` | `DOUBLE` | Лучшее значение целевой функции |
| `result` | `CLOB` | JSON-массив `bestPos` |
| `algorithm` | `VARCHAR(128)` | Устаревшее: имя алгоритма (null для новых) |
| `input_iterations` | `INT` | Устаревшее: параметр итераций |
| `input_agents` | `INT` | Устаревшее: параметр агентов |
| `input_dimension` | `INT` | Устаревшее: параметр размерности |

> Для новых заданий `payload` — это **источник истины** для всех параметров.

Индексы:
- `idx_tasks_status_priority (status, priority DESC, created_at)` — используется при claim
- `idx_tasks_job_status (job_id, status)` — агрегация по заданию
- `idx_tasks_assigned_running (assigned_to, status)` — поиск зависших задач

---

### Таблица `spots`

| Колонка | Тип | Описание |
|---|---|---|
| `id` | `VARCHAR(64) PK` | Порядковый номер SPOT (строка "1", "2", ...) |
| `ip_address` | `VARCHAR(64)` | IP-адрес воркера |
| `cpu_load` | `DOUBLE` | Загрузка CPU, % |
| `running_tasks` | `INT` | Текущих задач в работе |
| `total_cores` | `INT` | Ядер CPU |
| `ram_used_mb` | `BIGINT` | Использованная RAM, МБ |
| `ram_total_mb` | `BIGINT` | Всего RAM, МБ |
| `max_concurrent` | `INT` | Макс. параллельных задач |
| `capabilities_json` | `CLOB` | JSON с возможностями (оптимизаторы, функции) |
| `labels` | `VARCHAR(512)` | Метки через запятую |
| `status` | `VARCHAR(20)` | `UP` / `DOWN` |
| `last_heartbeat` | `TIMESTAMP` | Время последнего heartbeat |
| `registered_at` | `TIMESTAMP` | Время регистрации |

Индекс: `idx_spots_heartbeat (last_heartbeat)`

---

## Модель параметров задачи

### Формат payload задачи (хранится в `tasks.payload`)

```json
{
  "artifactBucket":   "testtest",
  "artifactKey":      "coa-algorithm-jar-with-dependencies.jar",
  "artifactEndpoint": "https://storage.yandexcloud.kz",
  "mainClass":        "algoritm.Optimizer",
  "params": {
    "algorithm.function": "sphere",
    "run.iterations":     100,
    "run.agents":         30,
    "run.dimension":      10
  }
}
```

SPOT-агент передаёт `params` алгоритму как JVM system properties:
```
java -Dalgorithm.function=sphere \
     -Drun.iterations=100 \
     -Drun.agents=30 \
     -Drun.dimension=10 \
     -cp algorithm.jar algoritm.Optimizer
```

### Формат RESULT_JSON (что алгоритм печатает в stdout)

Алгоритм должен напечатать **одну строку** в stdout следующего вида (в конце):
```
RESULT_JSON={"iter":100,"fopt":0.165696,"bestPos":[0.001,-0.002,0.0003,...]}
```

| Поле | Тип | Описание |
|---|---|---|
| `iter` | int | Итерация лучшего найденного значения |
| `fopt` | double | Лучшее значение целевой функции |
| `bestPos` | array of double | Координаты найденного минимума |

SPOT-агент парсит эту строку и отправляет данные на `/internal/v1/tasks/{id}/complete`.

---

## Создание SPOT-воркеров в Yandex Cloud

Кнопка **"Создать SPOT"** во вкладке Cloud запускает `VMCreator.createMany()`.

**Процесс создания одной VM:**

1. Генерируется имя: `{vm_name}-{UUID}`.
2. Формируется файл `/etc/default/spot-agent` с переменными среды:
   ```
   COORDINATOR_URL=http://<vpn_ip>:8081
   S3_ENDPOINT=https://storage.yandexcloud.kz   # если задан в [S3]
   S3_ACCESS_KEY_ID=...                          # если задан
   S3_SECRET_ACCESS_KEY=...                      # если задан
   ```
3. Формируется `cloud-init` YAML, который при первой загрузке VM:
   - Создаёт пользователя `spot` с sudo-правами
   - Записывает SSH-ключ в `~/.ssh/authorized_keys`
   - Пишет `/etc/default/spot-agent`
   - Создаёт systemd-сервис `/etc/systemd/system/spot-agent.service`:
     ```ini
     [Unit]
     After=network-online.target
     
     [Service]
     User=spot
     EnvironmentFile=/etc/default/spot-agent
     WorkingDirectory=/home/spot
     ExecStart=/usr/bin/java -jar /home/spot/spot-agent-jar-with-dependencies.jar
     Restart=always
     RestartSec=5
     ```
   - Запускает: `systemctl enable --now spot-agent`
4. Отправляется gRPC-запрос в Yandex Cloud Compute API.
5. Ожидается завершение операции (таймаут 5 минут).

**Что должно быть на образе VM (image_id):**
- Java 17+
- Файл `/home/spot/spot-agent-jar-with-dependencies.jar`

---

## Формат `config.ini`

```ini
[AUTH]
oauth_token   = y0__xCxx...      # OAuth-токен Yandex Cloud
cloud_id      = b1gXXXXXXXXX     # ID облака
folder_id     = b1gXXXXXXXXX     # ID каталога
zone_id       = kz1-a            # Зона доступности

[NETWORK]
network_id        = enpXXXXXXXX  # VPC сеть
subnet_id         = e9bXXXXXXXX  # Подсеть (обязательно)
security_group_id = enpXXXXXXXX  # Группа безопасности
public_ip         = true         # Назначать ли публичный IP

[VM]
vm_count    = 2              # Сколько SPOT VM создать
vm_name     = spot           # Префикс имени (+ UUID-суффикс)
image_id    = fd8XXXXXXXXXX  # ID образа диска
platform_id = standard-v3    # Платформа (standard-v1/v2/v3)
cpu         = 2              # vCPU
ram_gb      = 4              # RAM, ГБ
disk_gb     = 20             # Диск, ГБ

[SSH]
user        = spot
public_key  = ssh-rsa AAAAB3... user@host
# или
public_key_path = ~/.ssh/id_rsa.pub

[OVPN]
instance_id = b4emXXXXXXXX   # ID ВМ с OpenVPN AS (для управления)

[S3]
endpoint          = https://storage.yandexcloud.kz
access_key_id     = YCXXXXXXXXXX   # Статический ключ Yandex IAM
secret_access_key = YCXXXXXXXXXX
```

> Секция `[S3]` опциональна. Если не указана — SPOT-агент использует анонимный доступ (для публичных бакетов).

**Эндпоинты Yandex Object Storage:**

| Регион | Endpoint |
|---|---|
| Россия (ru-central1) | `https://storage.yandexcloud.net` |
| Казахстан (kz-north-2) | `https://storage.yandexcloud.kz` |

---

## Структура проекта

```
src/main/java/orhestra/
├── coordinator/
│   ├── api/
│   │   ├── v1/                         # Публичный API
│   │   │   ├── JobController.java      # POST /api/v1/jobs, GET /jobs/{id}[/results]
│   │   │   ├── SpotController.java     # GET /api/v1/spots
│   │   │   ├── HealthController.java   # GET /api/v1/health
│   │   │   ├── ParameterSchemaController.java  # GET /api/v1/parameter-schema
│   │   │   └── dto/                    # CreateJobRequest, JobResponse, TaskResultResponse...
│   │   └── internal/v1/               # Внутренний API для SPOT-агентов
│   │       ├── HeartbeatController.java  # /hello, /heartbeat
│   │       ├── TaskController.java       # /tasks/claim, /complete, /fail
│   │       └── dto/                    # ClaimTasksRequest, TaskCompleteRequest...
│   ├── config/
│   │   ├── CoordinatorConfig.java      # Настройки из env vars
│   │   └── Dependencies.java           # DI-контейнер (wires all services)
│   ├── core/
│   │   ├── AppBus.java                 # Pub-sub для обновления UI
│   │   └── CoordinatorCore.java        # Тонкий фасад для spot/task операций
│   ├── model/                          # Job, Task, Spot, ArtifactRef, enums...
│   ├── server/
│   │   ├── CoordinatorNettyServer.java # Netty HTTP pipeline
│   │   └── RouterHandler.java          # Маршрутизация, auth, error handling
│   ├── service/
│   │   ├── JobService.java             # Создание заданий + PayloadGenerator
│   │   ├── TaskService.java            # claim / complete / fail / findRecent
│   │   ├── SpotService.java            # register / heartbeat / findAll
│   │   └── SpotTaskBlacklist.java      # Запрет повторной выдачи задачи тому же SPOT
│   ├── store/
│   │   ├── Database.java               # HikariCP + schema init
│   │   ├── JdbcJobRepository.java
│   │   ├── JdbcTaskRepository.java
│   │   └── JdbcSpotRepository.java
│   ├── scheduler/
│   │   └── Scheduler.java              # TaskReaper + SpotReaper (background threads)
│   └── ui/                             # JavaFX контроллеры
│       ├── ExecutionController.java    # Вкладка Execution (таблица задач + SPOT-карточки)
│       ├── CloudController.java        # Вкладка Cloud (создание VM, статус окружения)
│       └── ...
├── cloud/
│   ├── auth/AuthService.java           # OAuth → IAM токен + gRPC-клиент
│   ├── config/
│   │   ├── IniLoader.java              # Парсинг config.ini
│   │   └── CloudConfig.java            # Модель конфига
│   ├── creator/VMCreator.java          # gRPC: создание VM + cloud-init
│   └── manager/VMManager.java          # Запрос статуса / IP VM

src/main/resources/
├── parameters.json                     # Схема параметров для UI (без перекомпиляции)
└── orhestra/ui/
    ├── main.fxml                       # Главное окно (TabPane)
    ├── execution.fxml                  # Вкладка Execution
    ├── cloud.fxml                      # Вкладка Cloud
    └── ...
```

---

## Примеры запросов (curl)

```bash
BASE=http://localhost:8081

# Здоровье
curl "$BASE/api/v1/health" | jq .

# Схема параметров
curl "$BASE/api/v1/parameter-schema" | jq .

# Список SPOT-воркеров
curl "$BASE/api/v1/spots" | jq .

# Создать задание (6 задач = 2 функции × 3 итерации)
JOB=$(curl -s -X POST "$BASE/api/v1/jobs" \
  -H 'Content-Type: application/json' \
  -d '{
    "artifactBucket":   "testtest",
    "artifactKey":      "coa-algorithm-jar-with-dependencies.jar",
    "artifactEndpoint": "https://storage.yandexcloud.kz",
    "mainClass":        "algoritm.Optimizer",
    "parameters": [
      {
        "groupId": "algorithm",
        "params": { "function": { "type": "ENUM_LIST", "values": ["sphere","ackley"] } }
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
  }')
echo "$JOB" | jq .
JOB_ID=$(echo "$JOB" | jq -r .jobId)

# Статус задания
curl "$BASE/api/v1/jobs/$JOB_ID" | jq .

# Результаты (когда все задачи DONE)
curl "$BASE/api/v1/jobs/$JOB_ID/results" | jq .

# Результаты в CSV — через UI: кнопка "⬇ Экспорт CSV" в вкладке Execution
```
