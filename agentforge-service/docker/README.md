# AgentForge 一体化镜像（应用 + SQLite）

默认镜像只包含 **AgentForge 服务 + 内置 SQLite**，不依赖外部数据库，适合单机开箱即用。
通过 `AGENTFORGE_DB_TYPE=mysql` 可无缝切换到外部 MySQL。

> 面向普通用户的一键安装、Docker 直接启动及升级说明，请参阅 [QUICK_DEPLOY.md](./QUICK_DEPLOY.md)。

## 1. 目录说明

```text
docker/
├── Dockerfile                 # 多阶段构建：后端 + 前端 + JRE 运行时
├── docker-compose.yml         # 默认 SQLite 单容器
├── .env.example               # 部署参数模板
├── container/bin/start.sh     # 容器入口
├── scripts/
    ├── build.sh               # 本地构建镜像
    ├── deploy.sh              # 首次部署（先迁移，再启动）
    ├── upgrade.sh             # 升级镜像（备份 + 迁移 + 重建）
    ├── upgrade-sql.sh         # 只执行 SQLite 增量 SQL
    ├── backup.sh              # SQLite 备份
    ├── package.sh             # 构建并导出镜像 tar（离线分发）
    └── push.sh                # 多架构构建并推送仓库
└── standalone/                # curl | bash 独立安装与升级文件
```

镜像内还包含 `/opt/agentforge/bin/upgrade-sql.sh`（由 `bin/local/upgrate-sqlite.sh` 复制），
用于在一次性容器中执行 SQLite 增量 SQL。

运行时数据落在 `docker/` 目录下：

```text
docker/
├── conf/
│   ├── application.yml        # 外置配置（首次 deploy/upgrade 自动生成）
│   └── logback.xml            # 外置日志配置
├── data/agentforge.db         # SQLite 数据库
├── data/agentforge.db-wal     # WAL 日志
├── logs/                      # 应用日志
├── upload/                    # 上传文件
└── backups/                   # 数据库备份
```

`docker/conf/application.yml` 与 `docker/conf/logback.xml` 通过 bind mount 覆盖镜像内配置，
修改后重启应用即可生效，无需重新构建镜像。

## 2. 本地构建与运行

```bash
cd agentforge-service/docker
cp .env.example .env          # 按需修改端口等配置
./scripts/build.sh 1.0.0
./scripts/deploy.sh
```

访问 `http://127.0.0.1:18080`。首次执行脚本会生成 `docker/conf/application.yml` 与
`docker/conf/logback.xml`，可直接编辑，重启容器后生效。

也可以直接使用 Docker Compose，但需要先执行迁移：

```bash
cd agentforge-service/docker
docker compose build
./scripts/upgrade-sql.sh
docker compose up -d
docker compose ps
docker compose logs -f agentforge
```

> 服务启动本身不会自动建表；直接 `docker compose up` 前必须确保已执行过 `upgrade-sql.sh`。

## 3. 切换 MySQL

保持镜像不变，只改环境变量：

```bash
AGENTFORGE_DB_TYPE=mysql
AGENTFORGE_DB_URL=jdbc:mysql://127.0.0.1:3306/agentforge?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
AGENTFORGE_DB_USERNAME=root
AGENTFORGE_DB_PASSWORD=your-password
AGENTFORGE_DB_POOL_SIZE=10
```

MySQL 表结构继续使用 `sql/increment/1.0.x/*.sql` 与 `bin/local/upgrate-sql.sh` 维护；
SQLite 使用 `sql/sqllite/1.0.x/*.sql`，由 `./scripts/upgrade-sql.sh` 或 `./scripts/upgrade.sh` 执行。

## 4. 升级

```bash
cd agentforge-service/docker
./scripts/upgrade.sh 1.0.1
```

升级流程：

1. 自动备份 `data/agentforge.db` 到 `backups/`；
2. 切换 `.env` 中的 `IMAGE_TAG`；
3. 拉取新镜像（本地镜像可设置 `SKIP_PULL=1`）；
4. 停止应用容器；
5. 用**新镜像**启动一次性容器执行 `upgrade-sql.sh`，按 `agentforge_schema_history` 幂等执行增量 SQL；
6. 重建应用容器。

仅执行 SQL 迁移（不重建应用）：

```bash
cd agentforge-service/docker
./scripts/upgrade-sql.sh
docker compose up -d --force-recreate agentforge
```

## 5. 备份

```bash
./scripts/backup.sh
```

优先使用宿主机 `sqlite3 .backup` 做一致性备份；宿主机没有 `sqlite3` 时会短暂停止容器后复制数据库文件。

## 6. 推送多架构镜像

```bash
cd agentforge-service/docker
IMAGE_REPO=dockerhub-user/agentforge-service ./scripts/push.sh 1.0.0
```

默认构建 `linux/amd64` 与 `linux/arm64` 并推送 `1.0.0` 与 `latest`。

## 7. 离线打包与分发

```bash
cd agentforge-service/docker
./scripts/package.sh 1.0.0
# 产物：docker/dist/agentforge-1.0.0.tar
```

目标机器：

```bash
docker load -i agentforge-1.0.0.tar
mkdir -p data logs upload
# 1) 先执行 SQLite 迁移（一次性容器）
docker run --rm \
  -v "$PWD/data":/opt/agentforge/data \
  --entrypoint /opt/agentforge/bin/upgrade-sql.sh \
  agentforge-service:1.0.0
# 2) 启动服务
docker run -d --name agentforge --restart unless-stopped \
  -p 18080:8080 \
  -v "$PWD/data":/opt/agentforge/data \
  -v "$PWD/logs":/opt/agentforge/logs \
  -v "$PWD/upload":/opt/agentforge/upload \
  -e AGENTFORGE_FILE_VISIT_HOST=127.0.0.1 \
  -e AGENTFORGE_FILE_VISIT_PORT=18080 \
  agentforge-service:1.0.0
```

## 8. 常用环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `WEB_PORT` | `18080` | 宿主机访问端口 |
| `AGENTFORGE_DB_TYPE` | `sqlite` | `sqlite` 或 `mysql` |
| `AGENTFORGE_SQLITE_PATH` | `/opt/agentforge/data/agentforge.db` | 容器内 SQLite 文件 |
| `AGENTFORGE_SQLITE_INCREMENT_DIR` | `/opt/agentforge/sql/sqllite` | 容器内增量 SQL 目录 |
| `AGENTFORGE_FILE_VISIT_HOST` | `127.0.0.1` | 上传文件对外地址 |
| `AGENTFORGE_FILE_VISIT_PORT` | `WEB_PORT` | 上传文件对外端口 |
| `JAVA_OPTS` | `-Xms256m -Xmx512m` | JVM 参数 |
