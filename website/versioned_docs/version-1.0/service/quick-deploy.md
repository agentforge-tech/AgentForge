---
title: Service 快速部署
sidebar_label: 快速部署
description: AgentForge Service 一键安装与 Docker 一键部署，并完成端口、数据目录与升级配置
sidebar_position: 2
displayed_sidebar: serviceSidebar
---

# Service 快速部署

AgentForge 一体化镜像已包含后端、前端与 SQLite，适合在 Linux 或 macOS 上快速启动。默认访问端口为 `17080`，运行数据保存在当前部署目录。

本地部署提供两种方式：**一键安装部署**与 **Docker 一键部署**，可按场景任选其一。

## 1. 环境准备

- Docker 20.10+
- Docker Compose v2（`docker compose version` 可检查）
- 至少 1 GB 可用内存
- 防火墙 / 安全组放行 `17080` 端口

## 2. 本地部署

### 2.1 一键安装部署（推荐）

适合长期运行，安装器会同时准备 Compose、`.env`、配置目录与升级脚本。

```bash
mkdir -p agentforge && cd agentforge

curl -fsSL https://raw.githubusercontent.com/agentforge-tech/AgentForge/master/agentforge-service/docker/standalone/install.sh | bash
```

脚本会自动下载部署文件、拉取镜像、初始化配置与 SQLite，然后启动服务。

启动完成后访问：

```text
http://127.0.0.1:17080
```

部署在远程服务器时，把 `127.0.0.1` 替换为服务器 IP 或域名。

> 安装脚本只下载运行所需的 Compose、环境变量与升级脚本，不下载完整源码。如果安装中断，可以在原目录重新执行同一条命令，已有 `.env` 与 `conf/` 会保留。

### 2.2 Docker 一键部署

适合快速体验或临时验证，不安装额外脚本，直接用一条 `docker run` 命令启动镜像：

```bash
mkdir -p agentforge && cd agentforge

docker run -d \
  --name agentforge-service \
  --restart unless-stopped \
  -p 17080:8080 \
  -v "$PWD/data":/opt/agentforge/data \
  -v "$PWD/logs":/opt/agentforge/logs \
  -v "$PWD/upload":/opt/agentforge/upload \
  -v "$PWD/conf":/opt/agentforge/host-conf \
  -e AGENTFORGE_FILE_VISIT_HOST=127.0.0.1 \
  -e AGENTFORGE_FILE_VISIT_PORT=17080 \
  -e AGENTFORGE_SQLITE_APPLIED_DIR=/opt/agentforge/data/sql-applied \
  --entrypoint /bin/bash \
  codercl/agentforge-service:1.0.0 \
  -c 'cp -n /opt/agentforge/conf/application.yml /opt/agentforge/host-conf/application.yml && cp -n /opt/agentforge/conf/logback.xml /opt/agentforge/host-conf/logback.xml && cp /opt/agentforge/host-conf/application.yml /opt/agentforge/conf/application.yml && cp /opt/agentforge/host-conf/logback.xml /opt/agentforge/conf/logback.xml && /opt/agentforge/bin/upgrade-sql.sh && exec /opt/agentforge/bin/start.sh'
```

首次启动会把镜像内默认配置导出到宿主机 `conf/`，以后每次启动都加载该目录中的配置。随后执行幂等的 SQLite 增量迁移并启动服务。

> 不要把空的宿主机 `conf/` 直接挂载到 `/opt/agentforge/conf`，这会遮蔽镜像内的默认配置与前端静态资源。上面的命令使用 `/opt/agentforge/host-conf` 作为中转目录。

## 3. 修改端口

例如改用 `8088`，需要同时修改端口映射和文件访问端口：

```bash
-p 8088:8080
-e AGENTFORGE_FILE_VISIT_PORT=8088
```

通过公网 IP 或域名访问上传文件时，再设置：

```bash
-e AGENTFORGE_FILE_VISIT_HOST=agentforge.example.com
-e AGENTFORGE_FILE_VISIT_PROTOCOL=https
```

## 4. 常用运维命令

```bash
docker ps --filter name=agentforge-service       # 查看状态
docker logs -f agentforge-service                # 实时日志
docker restart agentforge-service                # 重启
docker stop agentforge-service                   # 停止
docker start agentforge-service                  # 启动
```

## 5. 数据目录

| 目录 | 用途 |
| --- | --- |
| `conf/` | 应用与日志配置 |
| `data/` | SQLite 数据库 |
| `data/sql-applied/` | 已执行增量 SQL 归档（幂等判断依据，勿随意删除） |
| `logs/` | 应用运行日志 |
| `upload/` | 用户上传的文件 |
| `backups/` | 手动或升级前的数据库备份 |

迁移服务时，至少完整备份 `conf/`、`data/`（含 `data/sql-applied/`）与 `upload/`。

## 6. 升级

使用一键安装方式部署后，进入原目录执行：

```bash
cd agentforge

./upgrade.sh            # 升级到 latest
./upgrade.sh 1.0.1      # 升级到指定版本
```

升级脚本会自动拉取目标镜像、停止旧容器、备份 SQLite、执行增量 SQL 并重建容器。任一步骤失败时会尝试恢复旧容器，备份位于 `backups/`。

## 7. 从源码构建

需要二次开发时：

```bash
git clone https://github.com/agentforge-tech/AgentForge.git
cd AgentForge
mvn clean install -DskipTests
```

前端与后端分别位于 `agentforge-service/agentforge-service-ui` 与 `agentforge-service/agentforge-service-web`。

## 8. 常见问题

| 现象 | 排查方向 |
| --- | --- |
| 页面打不开 | 检查容器状态、端口映射与安全组 |
| 启动后配置不生效 | 修改 `conf/application.yml` 后执行 `docker restart agentforge-service` |
| SQLite 迁移失败 | 保留 `data/` 与 `data/sql-applied/`，查看容器日志定位失败脚本 |
| 上传文件无法访问 | 检查 `AGENTFORGE_FILE_VISIT_HOST` / `PORT` / `PROTOCOL` |
| 升级后回滚 | 使用 `backups/` 中的数据库备份与旧镜像标签重新启动 |
