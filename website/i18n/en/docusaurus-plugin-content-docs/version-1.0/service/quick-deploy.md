---
title: Service Quick Deploy
sidebar_label: Quick Deploy
description: Deploy AgentForge Service with one-click installation or a single Docker command, and configure ports, data directories, and upgrades
sidebar_position: 2
displayed_sidebar: serviceSidebar
---

# Service Quick Deploy

The AgentForge all-in-one image contains the backend, frontend, and SQLite. It is designed for a quick start on Linux or macOS. The default port is `18080`, and runtime data is stored in the deployment directory.

Local deployment supports two modes: **one-click installation deployment** and **Docker one-click deployment**. Choose either one for your scenario.

## 1. Prerequisites

- Docker 20.10+
- Docker Compose v2 (`docker compose version` to check)
- At least 1 GB of available memory
- Firewall / security group allows port `18080`

## 2. Local deployment

### 2.1 One-click installation deployment (recommended)

Best for long-running environments. The installer prepares Compose, `.env`, configuration directories, and the upgrade script.

```bash
mkdir -p agentforge && cd agentforge

curl -fsSL https://raw.githubusercontent.com/agentforge-tech/AgentForge/master/agentforge-service/docker/standalone/install.sh | bash
```

The script downloads deployment files, pulls the image, initializes configuration and SQLite, then starts the service.

Open:

```text
http://127.0.0.1:18080
```

For remote servers, replace `127.0.0.1` with the server IP or domain.

> The installer only downloads the Compose file, environment variables, and upgrade script, not the full source. If installation is interrupted, run the same command again in the same directory; existing `.env` and `conf/` are preserved.

### 2.2 Docker one-click deployment

Best for quick trials or temporary validation. No extra scripts are installed; start the image directly with a single `docker run` command:

```bash
mkdir -p agentforge && cd agentforge

docker run -d \
  --name agentforge-service \
  --restart unless-stopped \
  -p 18080:8080 \
  -v "$PWD/data":/opt/agentforge/data \
  -v "$PWD/logs":/opt/agentforge/logs \
  -v "$PWD/upload":/opt/agentforge/upload \
  -v "$PWD/conf":/opt/agentforge/host-conf \
  -e AGENTFORGE_FILE_VISIT_HOST=127.0.0.1 \
  -e AGENTFORGE_FILE_VISIT_PORT=18080 \
  -e AGENTFORGE_SQLITE_APPLIED_DIR=/opt/agentforge/data/sql-applied \
  --entrypoint /bin/bash \
  codercl/agentforge-service:1.0.0 \
  -c 'cp -n /opt/agentforge/conf/application.yml /opt/agentforge/host-conf/application.yml && cp -n /opt/agentforge/conf/logback.xml /opt/agentforge/host-conf/logback.xml && cp /opt/agentforge/host-conf/application.yml /opt/agentforge/conf/application.yml && cp /opt/agentforge/host-conf/logback.xml /opt/agentforge/conf/logback.xml && /opt/agentforge/bin/upgrade-sql.sh && exec /opt/agentforge/bin/start.sh'
```

On first start, the default configuration is exported to the host `conf/` directory, and subsequent starts load configuration from there. Idempotent SQLite migrations run before the service starts.

> Do not mount an empty host `conf/` directory directly to `/opt/agentforge/conf`; it would hide the default configuration and frontend assets. The command above uses `/opt/agentforge/host-conf` as an intermediate directory.

## 3. Change the port

For example, to use `8088`, change both the port mapping and the file access port:

```bash
-p 8088:8080
-e AGENTFORGE_FILE_VISIT_PORT=8088
```

When uploaded files are accessed through a public IP or domain, also set:

```bash
-e AGENTFORGE_FILE_VISIT_HOST=agentforge.example.com
-e AGENTFORGE_FILE_VISIT_PROTOCOL=https
```

## 4. Common operations

```bash
docker ps --filter name=agentforge-service       # Status
docker logs -f agentforge-service                # Live logs
docker restart agentforge-service                # Restart
docker stop agentforge-service                   # Stop
docker start agentforge-service                  # Start
```

## 5. Data directories

| Directory | Purpose |
| --- | --- |
| `conf/` | Application and logging configuration |
| `data/` | SQLite database |
| `data/sql-applied/` | Applied incremental SQL archive (idempotency marker; do not delete) |
| `logs/` | Application logs |
| `upload/` | User uploads |
| `backups/` | Manual or pre-upgrade database backups |

When migrating, back up `conf/`, `data/` (including `data/sql-applied/`), and `upload/`.

## 6. Upgrade

After installing with the one-click method, run from the deployment directory:

```bash
cd agentforge

./upgrade.sh            # Upgrade to latest
./upgrade.sh 1.0.1      # Upgrade to a specific version
```

The upgrade script pulls the target image, stops the old container, backs up SQLite, applies incremental SQL, and recreates the container. If any step fails, it attempts to restore the old container. Backups are stored in `backups/`.

## 7. Build from source

For customization:

```bash
git clone https://github.com/agentforge-tech/AgentForge.git
cd AgentForge
mvn clean install -DskipTests
```

The frontend and backend are under `agentforge-service/agentforge-service-ui` and `agentforge-service/agentforge-service-web`.

## 8. Troubleshooting

| Symptom | What to check |
| --- | --- |
| Page does not open | Container status, port mapping, and security group |
| Configuration changes do not apply | Edit `conf/application.yml`, then run `docker restart agentforge-service` |
| SQLite migration fails | Keep `data/` and `data/sql-applied/`, inspect container logs |
| Uploaded files are inaccessible | Check `AGENTFORGE_FILE_VISIT_HOST` / `PORT` / `PROTOCOL` |
| Roll back after upgrade | Use the database backup in `backups/` and the previous image tag |
