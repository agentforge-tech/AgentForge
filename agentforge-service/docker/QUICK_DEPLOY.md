# AgentForge Docker 快速部署与升级

AgentForge 一体化镜像已包含后端、前端和 SQLite，适合在 Linux 或 macOS 上快速启动。默认访问端口为 `18080`，运行数据会保存在当前部署目录中。

## 部署前准备

请先确认已安装：

- Docker 20.10 或更高版本
- Docker Compose v2（使用 `docker compose version` 检查）
- 至少 1 GB 可用内存
- 服务器防火墙/安全组放行 `18080` 端口

## 方式一：一键安装并启动（推荐）

先创建一个独立的部署目录：

```bash
mkdir -p agentforge && cd agentforge
```

在该目录内执行一键安装脚本。它会自动下载部署文件、拉取镜像、初始化配置和 SQLite，然后启动 AgentForge：

```bash
curl -fsSL https://raw.githubusercontent.com/agentforge-tech/AgentForge/master/agentforge-service/docker/standalone/install.sh | bash
```

启动完成后访问：

```text
http://127.0.0.1:18080
```

部署在远程服务器时，将 `127.0.0.1` 替换为服务器 IP。

> 安装脚本只会下载运行所需的 Compose、环境变量和升级脚本，不会下载完整源码。

### 升级

部署后无需重新执行安装命令。进入原部署目录，直接执行：

```bash
cd agentforge

./upgrade.sh            # 升级到 latest
./upgrade.sh 1.0.1      # 升级到指定版本
```

升级脚本会自动拉取目标镜像、停止旧容器、备份 SQLite、执行增量 SQL 并重建容器。任何升级步骤失败时，脚本会尝试恢复旧容器。备份位于 `backups/`。

## 方式二：一条 Docker 命令启动

如果不想下载源码，可以直接使用已发布的镜像。先创建并进入数据目录：

```bash
mkdir -p agentforge && cd agentforge
```

执行下面这一条 `docker run` 命令：

```bash
docker run -d \
  --name agentforge \
  --restart unless-stopped \
  -p 18080:8080 \
  -v "$PWD/data":/opt/agentforge/data \
  -v "$PWD/logs":/opt/agentforge/logs \
  -v "$PWD/upload":/opt/agentforge/upload \
  -v "$PWD/conf":/opt/agentforge/host-conf \
  -e AGENTFORGE_FILE_VISIT_HOST=127.0.0.1 \
  -e AGENTFORGE_FILE_VISIT_PORT=18080 \
  --entrypoint /bin/bash \
  codercl/agentforge-service:1.0.0 \
  -c 'cp -n /opt/agentforge/conf/application.yml /opt/agentforge/host-conf/application.yml && cp -n /opt/agentforge/conf/logback.xml /opt/agentforge/host-conf/logback.xml && cp /opt/agentforge/host-conf/application.yml /opt/agentforge/conf/application.yml && cp /opt/agentforge/host-conf/logback.xml /opt/agentforge/conf/logback.xml && /opt/agentforge/bin/upgrade-sql.sh && exec /opt/agentforge/bin/start.sh'
```

该命令首次启动时会将镜像内的默认配置导出到宿主机 `conf/` 目录，以后每次启动都会加载该目录中的 `application.yml` 和 `logback.xml`。随后执行幂等的 SQLite 增量迁移并启动 AgentForge。

`conf`、`data`、`logs` 和 `upload` 目录会自动创建并持久化到宿主机。修改 `conf/` 下的配置后，执行 `docker restart agentforge` 即可生效。

> 不要将空的宿主机 `conf/` 目录直接挂载到 `/opt/agentforge/conf`。这会遮蔽镜像内的默认配置和前端静态资源。上面的命令使用 `/opt/agentforge/host-conf` 作为中转目录，可避免该问题。

### 升级镜像

一条 `docker run` 命令适合临时体验，不会在宿主机安装升级脚本。需要持续运行和一键升级时，建议使用“方式一”安装，然后通过 `./upgrade.sh [版本]` 升级。

## 常用运维命令

```bash
docker ps --filter name=agentforge       # 查看状态
docker logs -f agentforge                # 查看实时日志，Ctrl+C 退出
docker restart agentforge                # 重启
docker stop agentforge                   # 停止
docker start agentforge                  # 启动
```

## 修改端口和对外地址

例如改用 `8088` 端口，需要同时修改端口映射和文件访问端口：

```bash
-p 8088:8080
-e AGENTFORGE_FILE_VISIT_PORT=8088
```

如果需要通过公网 IP 或域名访问上传文件，请同时设置：

```bash
-e AGENTFORGE_FILE_VISIT_HOST=agentforge.example.com
-e AGENTFORGE_FILE_VISIT_PROTOCOL=https
```

## 数据目录

| 目录 | 用途 |
| --- | --- |
| `conf/` | 应用与日志配置 |
| `data/` | SQLite 数据库 |
| `logs/` | 应用运行日志 |
| `upload/` | 用户上传的文件 |
| `backups/` | 手动或升级前的数据库备份 |

迁移服务时，请至少完整备份 `conf/`、`data/` 和 `upload/` 目录。
