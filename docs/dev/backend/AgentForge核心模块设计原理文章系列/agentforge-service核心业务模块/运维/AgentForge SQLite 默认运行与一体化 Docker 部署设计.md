# AgentForge SQLite 默认运行与一体化 Docker 部署设计

> 模块：运维 / 数据库 / 部署
> 适用：`agentforge-service`（Spring Boot 单体服务 + Vue SPA）
> 目标：本地默认使用 SQLite 开发运行，一条配置切换到 MySQL；同时提供「服务 + SQLite」一体化镜像，支持一键部署、增量升级、备份与回滚
> 参考：`MySQL + 应用服务 一键 Docker 部署实操 SOP 手册`

---

## 1. 背景与目标

AgentForge Service 早期默认依赖 MySQL，本地启动需要先安装数据库、创建库、执行 SQL。为了降低上手成本，并让服务具备「开箱即用」的交付形态，本次改造确立以下目标：

| 目标 | 说明 |
| --- | --- |
| 默认零依赖 | 本地启动默认使用 SQLite，数据库文件落在 `agentforge-service/data/` |
| 快速切换 | 只改 `AGENTFORGE_DB_TYPE`，即可在 SQLite / MySQL 之间无缝切换 |
| 双方言隔离 | MySQL 与 SQLite 的 SQL 分别维护，互不覆盖，避免为兼容一方而破坏另一方 |
| 增量可追踪 | SQLite 增量 SQL 按版本目录维护，执行成功后在归档目录留档（与 MySQL 一致） |
| 一体化镜像 | 一个镜像包含后端、前端、SQLite，运行时只有一个应用容器 |
| 可升级 | 提供构建、部署、升级、备份、推送脚本，升级时主动执行增量 SQL |

**核心原则：约定优于配置。** 目录、文件名、环境变量保持稳定，脚本与镜像即可复用。

---

## 2. 总体方案

### 2.1 运行时形态

```text
┌──────────────────────────────────────────────┐
│              agentforge 容器                  │
│                                              │
│  Spring Boot API  ──  SQLite (data/agentforge.db)
│  Vue SPA 静态资源                            │
│  部署/升级时执行 sql/sqllite/*.sql            │
└──────────────────────────────────────────────┘
                     │
                     ▼
            宿主机 ./data ./logs ./upload
```

默认只有一个应用容器，不依赖 MySQL、Nginx 或其他中间件。需要 MySQL 时，保持镜像不变，通过环境变量连接外部 MySQL。

### 2.2 数据库切换

```bash
# SQLite（默认）
AGENTFORGE_DB_TYPE=sqlite

# MySQL
AGENTFORGE_DB_TYPE=mysql
AGENTFORGE_DB_URL='jdbc:mysql://127.0.0.1:3306/agentforge?...'
AGENTFORGE_DB_USERNAME=root
AGENTFORGE_DB_PASSWORD=123456
```

配置文件中通过 Spring Boot 多文档 profile 隔离：

- `sqlite` profile：`org.sqlite.JDBC` + 本地文件 URL；
- `mysql` profile：`com.mysql.cj.jdbc.Driver` + MySQL URL。

同时 `sqllite` 作为 `sqlite` 的别名被支持，兼容历史文档里的拼写。

---

## 3. 目录规范

### 3.1 SQL 目录

```text
agentforge-service/
├── data/                                  # 本地运行数据（git 忽略数据库文件）
│   └── agentforge.db                      # SQLite 默认数据库
└── sql/
    ├── increment/                         # MySQL 待执行增量 SQL
    │   └── 1.0.x/
    │       └── 20261014000000_v1.0.x.sql
    ├── local/                             # 已执行记录（git 忽略）
    │   ├── 1.0.x/                         # MySQL 已执行归档
    │   └── sqlite/1.0.x/                  # SQLite 已执行归档
    └── sqllite/                           # SQLite 增量 SQL（按版本，直接维护）
        └── 1.0.x/
            └── 20261014000000_v1.0.x.sql
```

约定：

1. 文件名以时间戳开头，按文件名排序执行；
2. SQLite 与 MySQL 的增量文件同名、同版本，便于对照；
3. SQLite 的脚本目录本身就是维护目录，不再额外嵌套 `increment/sqllite`；
4. 执行成功的 SQL 会复制到归档目录（本地 `sql/local/sqlite/`，容器内 `data/sql-applied/`），归档文件是否存在是「是否已执行」的唯一事实来源，与 MySQL 的 `sql/local` 机制一致。

### 3.2 Mapper 目录

按参考项目的 `mapper/业务/xx.xml`、`sqlliteMapper/业务/xx.xml` 方式维护，两套目录在 `resources` 顶层平级：

```text
src/main/resources/
├── mapper/                                # MySQL Mapper（SQL 内容保持原样）
│   ├── agent/
│   │   ├── AgentConfigMapper.xml
│   │   └── memory/AgentMemoryMapper.xml
│   ├── model/ModelConfigMapper.xml
│   ├── session/...
│   ├── plugins/
│   │   ├── http/...
│   │   └── mcp/...
│   └── trace/...
└── sqlliteMapper/                         # SQLite Mapper（只做方言适配）
    ├── agent/
    │   ├── AgentConfigMapper.xml
    │   └── memory/AgentMemoryMapper.xml
    ├── model/ModelConfigMapper.xml
    ├── session/...
    ├── plugins/
    │   ├── http/...
    │   └── mcp/...
    └── trace/...
```

切换由 `mybatis.mapper-locations` 决定：

| profile | mapper-locations |
| --- | --- |
| `sqlite` | `classpath*:sqlliteMapper/**/*.xml` |
| `mysql` | `classpath*:mapper/**/*.xml` |

这样 MySQL 原有 SQL 完全不受 SQLite 适配影响；新增 SQLite 语法时只改 `sqlliteMapper` 目录，目录层级与业务模块保持一致，便于按业务定位。

---

## 4. 关键实现

### 4.1 数据目录解析

`SystemPropertyUtil` 在 Spring 启动前解析服务主目录，并写入系统属性：

| 系统属性 | 默认位置 | 用途 |
| --- | --- | --- |
| `user.dir.conf` | `<service>/conf/` | 配置文件、前端静态资源 |
| `user.dir.logs` | `<service>/logs/` | 日志 |
| `user.dir.data` | `<service>/data/` | SQLite 数据库 |
| `user.dir.upload` | `<service>/upload/` | 上传文件 |

SQLite URL 直接引用 `user.dir.data`：

```yaml
url: "jdbc:sqlite:${AGENTFORGE_SQLITE_PATH:${user.dir.data}agentforge.db}?busy_timeout=5000&journal_mode=WAL&synchronous=NORMAL&foreign_keys=on"
```

SQLite 连接池默认 `maximum-pool-size=1`，避免多写连接产生 `SQLITE_BUSY`；WAL 模式保证读写并发能力。

### 4.2 SQLite 脚本迁移

服务启动不再执行迁移。`deploy.sh`、`upgrade.sh` 通过一次性容器调用
`/opt/agentforge/bin/upgrade-sql.sh` 执行以下流程：

```text
upgrade-sql.sh 启动
  ├─ AGENTFORGE_DB_TYPE 非 sqlite：直接跳过（MySQL 保持原升级方式）
  └─ sqlite：扫描 sql/sqllite/**/*.sql
       ├─ 按相对路径排序
       ├─ 判断归档目录中是否已存在同名文件
       │    ├─ 已存在：跳过
       │    └─ 不存在：事务内执行 SQL + 成功后复制到归档目录
       └─ 失败时回滚，并保留 .work 工作文件与日志
```

设计要点：

- **脚本驱动**：迁移由 `deploy.sh` / `upgrade.sh` / `upgrade-sql.sh` 主动执行，服务启动不做隐式迁移；
- **幂等**：以「归档目录中是否存在对应 SQL」判断是否已执行，与 MySQL `bin/local/upgrate-sql.sh` 机制一致，重复执行不会重复应用；
- **可恢复**：数据库与归档目录都被删除后，下一次执行会重新应用全部 SQL；若只删数据库而保留归档，会全部跳过，需同时清理归档；
- **可追溯**：归档目录保留了执行当时的 SQL 原文，便于审计与对照；
- **可自定义目录**：`AGENTFORGE_SQLITE_INCREMENT_DIR`（增量目录）、`AGENTFORGE_SQLITE_APPLIED_DIR`（归档目录）；
- **兼容本地**：同一脚本可在本机通过 `bin/local/upgrate-sqlite.sh` 执行，默认归档到 `sql/local/sqlite/`；
- **自动建库**：SQLite 无 `CREATE DATABASE` / `USE` 语句，脚本先 `mkdir` 数据目录、再由 `sqlite3` 打开文件时自动生成 `.db`，因此增量 SQL 中不需要（也不能写）建库语句；
- **脚本保持简单**：增量文件不要自带 `BEGIN/COMMIT`（脚本已统一包事务），也不要放必须在事务外执行的 `PRAGMA`。

> **容器持久化要点**：归档目录就是幂等判断依据，必须落在持久化卷上。Docker 默认
> `AGENTFORGE_SQLITE_APPLIED_DIR=/opt/agentforge/data/sql-applied`，随 `./data` 卷持久化；
> 否则 `docker compose run --rm` 的一次性容器重建后会误判为「全部未执行」。

### 4.3 SQLite SQL 适配点

与 MySQL 相比，SQLite 版本主要调整：

| MySQL 写法 | SQLite 写法 |
| --- | --- |
| `concat('%', #{x}, '%')` | `'%' \|\| #{x} \|\| '%'` |
| `limit #{offset}, #{size}` | `limit #{size} offset #{offset}` |
| `for update` | 去掉（SQLite 写事务串行化） |
| `now()` / `CURRENT_TIMESTAMP(3)` | `CURRENT_TIMESTAMP` |
| `UPDATE ... JOIN ... SET` | `UPDATE ... WHERE id IN (SELECT ...)` |
| `DATETIME ... ON UPDATE` | 由应用显式写入 `modify_time` |
| `BIGINT / TINYINT / LONGTEXT` | `INTEGER / INTEGER / TEXT` |

未使用 `databaseId` 做语句级分支，而是用目录隔离，保证两套 SQL 都能独立演进。

---

## 5. 一体化镜像设计

### 5.1 构建阶段

`agentforge-service/docker/Dockerfile` 使用三阶段构建：

```text
maven:3.9-eclipse-temurin-17  → 编译后端
node:20-alpine                → 构建前端
eclipse-temurin:17-jre-jammy  → 运行时
```

运行时目录：

```text
/opt/agentforge/
├── bin/start.sh
├── bin/upgrade-sql.sh        # SQLite 增量迁移（来自 bin/local/upgrate-sqlite.sh）
├── conf/
│   ├── application.yml       # 由 application.yml.example 固化
│   ├── logback.xml
│   └── static/               # 前端构建产物
├── data/                     # SQLite 数据库 + 已执行归档（挂载）
│   ├── agentforge.db
│   └── sql-applied/          # 已执行 SQL 归档（幂等依据）
├── logs/                     # 日志（挂载）
├── upload/                   # 上传文件（挂载）
├── lib/app.jar
└── sql/
    └── sqllite/              # 镜像内置 SQLite 增量 SQL
```

镜像内置 `curl` 用于健康检查，内置 `sqlite3` 供 `upgrade-sql.sh` 执行迁移与备份排查。

### 5.2 运行时卷

`docker-compose.yml` 默认只启动一个服务：

```yaml
services:
  agentforge:
    image: ${IMAGE_REPO:-agentforge}:${IMAGE_TAG:-1.0.0}
    ports: ["${WEB_PORT:-18080}:8080"]
    volumes:
      - ./data:/opt/agentforge/data
      - ./logs:/opt/agentforge/logs
      - ./upload:/opt/agentforge/upload
      - ./conf/application.yml:/opt/agentforge/conf/application.yml:ro
      - ./conf/logback.xml:/opt/agentforge/conf/logback.xml:ro
```

`docker/conf/` 由 `deploy.sh` / `upgrade.sh` 首次执行时自动生成，宿主机可直接编辑配置后重启容器，
无需重新构建镜像。所有状态都落在 `docker/` 目录，随目录整体备份/迁移。已执行 SQL 归档位于
`data/sql-applied/`，与数据库同在 `./data` 卷内，容器重建后幂等判断不会丢失。

---

## 6. 运维 SOP

### 6.1 本地打包与启动

```bash
cd agentforge-service
cp conf/application.yml.example conf/application.yml
mvn package -pl agentforge-service-web -am -DskipTests
./bin/local/upgrate-sqlite.sh
java -jar agentforge-service-web/target/agentforge-service-web-*.jar
```

`upgrate-sqlite.sh` 默认生成 `agentforge-service/data/agentforge.db` 并执行增量 SQL；
服务启动本身不再自动建表。

### 6.2 构建镜像

```bash
cd agentforge-service/docker
cp .env.example .env
./scripts/build.sh 1.0.0
```

### 6.3 首次部署

```bash
cd agentforge-service/docker
./scripts/deploy.sh
```

访问 `http://127.0.0.1:18080`。`deploy.sh` 会构建镜像、执行 `upgrade-sql.sh` 完成首次迁移，再启动应用容器。

### 6.4 增量升级

```bash
cd agentforge-service/docker
./scripts/upgrade.sh 1.0.1
```

流程：

1. `backup.sh` 备份 SQLite；
2. 更新 `.env` 中的 `IMAGE_TAG`；
3. `docker compose pull`（本地镜像可用 `SKIP_PULL=1`）；
4. 停止应用容器；
5. 用新镜像启动一次性容器执行 `upgrade-sql.sh`；
6. `docker compose up -d --force-recreate` 重建应用容器。

只改 SQL 时可直接执行迁移（随后重建应用）：

```bash
./scripts/upgrade-sql.sh
docker compose up -d --force-recreate agentforge
```

### 6.5 备份与恢复

```bash
./scripts/backup.sh
# 产物：backups/agentforge-YYYYmmdd-HHMMSS.db
```

恢复：

```bash
docker compose down
cp backups/agentforge-YYYYmmdd-HHMMSS.db data/agentforge.db
rm -f data/agentforge.db-wal data/agentforge.db-shm
docker compose up -d
```

### 6.6 推送多架构镜像

```bash
cd agentforge-service/docker
IMAGE_REPO=dockerhub-user/agentforge ./scripts/push.sh 1.0.0
```

默认推送 `linux/amd64` 与 `linux/arm64`，部署端由 Docker 自动选择架构。

### 6.7 切换 MySQL

```bash
cd agentforge-service/docker
# 修改 .env
AGENTFORGE_DB_TYPE=mysql
AGENTFORGE_DB_URL=jdbc:mysql://127.0.0.1:3306/agentforge?...
AGENTFORGE_DB_USERNAME=root
AGENTFORGE_DB_PASSWORD=******
AGENTFORGE_DB_POOL_SIZE=10

docker compose up -d --force-recreate
```

MySQL 表结构继续使用 `sql/increment/1.0.x/*.sql` 和 `bin/local/upgrate-sql.sh` 维护。

---

## 7. 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AGENTFORGE_DB_TYPE` | `sqlite` | `sqlite` / `mysql` |
| `AGENTFORGE_SQLITE_PATH` | `${user.dir.data}agentforge.db` | SQLite 文件路径 |
| `AGENTFORGE_SQLITE_INCREMENT_DIR` | `<service>/sql/sqllite` | SQLite 增量 SQL 目录（脚本读取） |
| `AGENTFORGE_SQLITE_APPLIED_DIR` | `<service>/sql/local/sqlite`；Docker `/opt/agentforge/data/sql-applied` | 已执行 SQL 归档目录（幂等依据，需持久化） |
| `AGENTFORGE_DB_URL` | MySQL 本地默认值 | MySQL JDBC URL |
| `AGENTFORGE_DB_USERNAME` | `root` | MySQL 用户名 |
| `AGENTFORGE_DB_PASSWORD` | `123456` | MySQL 密码 |
| `AGENTFORGE_DB_POOL_SIZE` | SQLite `1` / MySQL `10` | 连接池大小 |
| `WEB_PORT` | `18080` | Docker 宿主机端口 |
| `AGENTFORGE_FILE_VISIT_HOST` | `127.0.0.1` | 上传文件对外地址 |
| `AGENTFORGE_FILE_VISIT_PORT` | `server.port` / Docker 下为 `WEB_PORT` | 上传文件对外端口 |
| `AGENTFORGE_STATIC_LOCATION` | `file:${user.dir.conf}static/` | 前端静态资源目录 |
| `AGENTFORGE_MODEL_API_KEY` | 空 | 模型调用密钥 |

---

## 8. 验证清单

**本地 SQLite**

- [x] 不安装 MySQL 可直接启动；
- [x] `upgrate-sqlite.sh` 生成 `agentforge-service/data/agentforge.db`；
- [x] 首次执行脚本应用 7 个 SQLite 增量脚本；
- [x] 重复执行脚本不重复应用，已执行 SQL 归档到 `sql/local/sqlite/`；
- [x] 增量脚本按版本维护在 `sql/sqllite/1.0.x/`；
- [x] 模型、Agent、会话、MCP、HTTP 插件、Trace 接口可正常查询。

**切换 MySQL**

- [x] `AGENTFORGE_DB_TYPE=mysql` 后加载 `mapper/**/*.xml`；
- [x] MySQL 增量 SQL 保持原有 `bin/local/upgrate-sql.sh` 流程；
- [x] 原 MySQL 功能无回归（模型配置 CRUD / 分页 / 模糊查询实测通过）。

**Docker**

- [x] 镜像构建成功，只有一个应用容器；
- [x] `deploy.sh` 先用一次性容器完成建表，再启动应用；
- [x] 前端页面、API、上传文件访问正常；
- [x] 新增 SQLite 增量 SQL 后执行 `upgrade-sql.sh` 自动应用；
- [x] `upgrade.sh` 升级前完成数据库备份；
- [x] 数据在 `docker/data/`，容器重建不丢失；
- [x] `docker/conf/` 外置配置自动生成并挂载生效。

### 8.1 实测记录

| 场景 | 结果 |
| --- | --- |
| 本地 `upgrate-sqlite.sh` | 首次应用 7 个脚本，重复执行 0 个 |
| 本地 `java -jar` 默认 SQLite 启动 | 启动无迁移日志，模型配置接口正常 |
| 本地模型配置新增/分页/关键字查询 | 200，日期字段正常回读 |
| MySQL 模式启动 + 原增量 SQL | 13 张表创建成功，模型配置 CRUD 正常 |
| 镜像构建 `agentforge:1.0.0` | 成功，单容器运行 |
| `deploy.sh` 首次部署 | 构建 → 一次性容器迁移 7 个 → 启动应用 |
| 容器健康检查 | `healthy` |
| 前端 `/`、`/agents`、`/logo.png` | 200，SPA 回退正常 |
| 文件上传 + 访问 `visitUrl` | 200，落盘到 `docker/upload/` |
| `upgrade.sh` 升级 | 自动备份 SQLite，新镜像迁移 0 个，数据保留 |
| 新增 SQL 后执行 `upgrade-sql.sh` | 首次 8 个，再次 0 个，建表成功 |
| `docker/conf` 挂载 | 首次升级自动生成，容器内可见，静态资源不受影响 |
| `package.sh` 离线打包 | 生成镜像 tar，`docker load` 成功 |

---

## 9. 取舍与风险

| 事项 | 选择 | 说明 |
| --- | --- | --- |
| SQLite 并发 | 单连接 + WAL | 适合单机/小团队；高并发写建议切 MySQL |
| 迁移执行位置 | 部署/升级脚本 | 显式执行，服务启动无隐式副作用 |
| 迁移记录 | 文件归档 | 与 MySQL 一致；容器内归档放在 data 卷以保证幂等 |
| Mapper 维护 | 目录隔离 | 双方言互不影响，代价是部分 SQL 需要同步维护 |
| 前端托管 | 后端同源托管 | 少一个 Nginx 容器，避免跨域 |
| 镜像默认数据库 | SQLite | 开箱即用；MySQL 通过环境变量切换 |

**注意：**

1. SQLite 不适合多副本同时写同一文件，生产多实例请使用 MySQL；
2. 升级前务必执行 `backup.sh`，DDL 无法自动回滚；
3. `data/`、`logs/`、`upload/`、`conf/`、`.env` 必须持久化/备份；
4. 若修改已执行的 SQLite 增量脚本，归档目录中仍是旧文件、不会重新执行；请新增脚本而不是改旧脚本。

---

## 10. 与 MySQL SOP 的对应关系

| MySQL SOP | 本方案 |
| --- | --- |
| 2 容器（应用 + MySQL） | 1 容器（应用 + SQLite） |
| 启动脚本等待 MySQL | 无需等待，部署/升级时先执行迁移 |
| `sql/increment/<版本>` | `sql/sqllite/<版本>` |
| `sql/local` 文件归档 | `sql/local/sqlite`（容器内 `/opt/agentforge/data/sql-applied`） |
| `bin/local/upgrate-sql.sh` | `upgrade-sql.sh`（本地 `upgrate-sqlite.sh`） |
| `docker/scripts/package.sh` | `docker/scripts/build.sh` + `package.sh` |
| `docker/scripts/deploy.sh` | `docker/scripts/deploy.sh` |
| 容器启动时迁移 | `docker/scripts/upgrade-sql.sh`（一次性容器） |
| `docker/scripts/upgrade.sh` | `docker/scripts/upgrade.sh` |
| `docker/scripts/push.sh` | `docker/scripts/push.sh` |

---

## 11. 命令速查

```bash
# 本地 SQLite 启动
cd agentforge-service
cp conf/application.yml.example conf/application.yml
mvn package -pl agentforge-service-web -am -DskipTests
./bin/local/upgrate-sqlite.sh
java -jar agentforge-service-web/target/agentforge-service-web-*.jar

# 切换 MySQL
AGENTFORGE_DB_TYPE=mysql AGENTFORGE_DB_URL='jdbc:mysql://...' java -jar ...

# Docker 构建 / 部署 / 升级 / 仅迁移 / 备份 / 打包 / 推送
cd agentforge-service/docker
./scripts/build.sh 1.0.0
./scripts/deploy.sh
./scripts/upgrade.sh 1.0.1
./scripts/upgrade-sql.sh
./scripts/backup.sh
./scripts/package.sh 1.0.0
IMAGE_REPO=dockerhub-user/agentforge ./scripts/push.sh 1.0.0

# 查看状态
docker compose ps
docker logs -f agentforge
curl -I http://127.0.0.1:18080/
```

---

## 12. 核心 SOP 可落地经验

### 12.1 推荐落地顺序

```text
本地 SQLite 跑通
  → 构建镜像
  → 一键部署
  → 升级 / 备份
  → 离线打包分发
  → 切换 MySQL 回归
```

每一步都要有**可验证结果**，不要只看命令是否退出 0。

### 12.2 本地 SQLite 开发 SOP

```bash
cd agentforge-service
cp conf/application.yml.example conf/application.yml

# 1) 编译
mvn package -pl agentforge-service-web -am -DskipTests

# 2) 预览并执行 SQLite 增量 SQL
./bin/local/upgrate-sqlite.sh --dry-run
./bin/local/upgrate-sqlite.sh

# 3) 启动
java -jar agentforge-service-web/target/agentforge-service-web-*.jar
```

验证：

```bash
ls -lh data/agentforge.db
find sql/local/sqlite -name '*.sql' | sort   # 已执行归档
curl -sS http://127.0.0.1:8080/api/model-config/list
```

经验：

- 服务启动**不再自动建表**，首次运行必须先执行脚本；
- 重复执行脚本应输出「没有需要执行的 SQLite 增量 SQL」；
- 修改已执行脚本不会重新执行（归档已存在），正确做法是新增版本脚本。

### 12.3 Docker 一键构建 / 部署 SOP

```bash
cd agentforge-service/docker
cp .env.example .env

./scripts/deploy.sh
```

`deploy.sh` 实际执行：

```text
compose build
  → upgrade-sql.sh（一次性容器迁移）
  → compose up -d
```

验证：

```bash
docker compose ps
docker inspect -f '{{.State.Health.Status}}' agentforge
docker logs agentforge | tail -30
curl -sS http://127.0.0.1:18080/api/model-config/list
```

预期：

- 迁移日志出现「待执行 SQL（共 7 个）」与「成功执行 7 个 SQL」；
- 应用日志**不应**出现 SQLite 建表日志；
- 容器状态 `healthy`；
- 页面 `/`、`/agents` 可访问。

### 12.4 升级 SOP

```bash
cd agentforge-service/docker
./scripts/upgrade.sh 1.0.1
```

升级脚本顺序：

```text
备份 SQLite
  → 切换 IMAGE_TAG
  → 拉取新镜像
  → 停止应用
  → 新镜像一次性容器执行 upgrade-sql.sh
  → 重建应用容器
```

本地镜像验证可加 `SKIP_PULL=1`：

```bash
SKIP_PULL=1 ./scripts/upgrade.sh 1.0.1
```

验证：

```bash
ls -lh backups/
docker logs agentforge | grep -E "SQLite|Started"
curl -sS http://127.0.0.1:18080/api/model-config/list
```

经验：

- **先备份再升级**，DDL 不能自动回滚；
- 迁移输出「没有需要执行的 SQLite 增量 SQL」表示幂等正常；
- 升级后必须验证原有业务数据仍在。

### 12.5 仅 SQL 变更 SOP

```bash
cd agentforge-service/docker
./scripts/upgrade-sql.sh
docker compose up -d --force-recreate agentforge
```

适用：只新增 `sql/sqllite/<版本>/*.sql`，不换镜像版本。生产环境更推荐走 `upgrade.sh` 发布新镜像。

### 12.6 conf 外置配置 SOP

首次执行 `deploy.sh` / `upgrade.sh` 会生成：

```text
docker/conf/application.yml
docker/conf/logback.xml
```

修改后重启即可生效：

```bash
vi docker/conf/application.yml
docker compose restart agentforge
docker exec agentforge head -20 /opt/agentforge/conf/application.yml
```

经验：

- compose 只挂载 `application.yml` 与 `logback.xml` 两个**文件**；
- 不要直接挂载整个 `conf/` 目录，否则会覆盖镜像内的 `conf/static` 前端资源；
- `docker/conf` 已加入 `.gitignore`，属于部署侧配置，不提交密钥。

### 12.7 备份与恢复 SOP

备份：

```bash
cd agentforge-service/docker
./scripts/backup.sh
# 产物：backups/agentforge-YYYYmmdd-HHMMSS.db
```

恢复：

```bash
docker compose down
cp backups/agentforge-YYYYmmdd-HHMMSS.db data/agentforge.db
rm -f data/agentforge.db-wal data/agentforge.db-shm
docker compose up -d
```

经验：恢复时最好连同归档目录一起恢复（本地 `sql/local/sqlite`、Docker `data/sql-applied`）；只恢复数据库而归档缺失时，下次升级会重复执行，但脚本 DDL 均幂等，重跑安全。

### 12.8 离线打包 / 分发 SOP

```bash
cd agentforge-service/docker
./scripts/package.sh 1.0.0
# 产物：dist/agentforge-1.0.0.tar
```

目标机器：

```bash
docker load -i agentforge-1.0.0.tar
mkdir -p data logs upload

# 先迁移
docker run --rm \
  -v "$PWD/data":/opt/agentforge/data \
  -e AGENTFORGE_SQLITE_APPLIED_DIR=/opt/agentforge/data/sql-applied \
  --entrypoint /opt/agentforge/bin/upgrade-sql.sh \
  agentforge:1.0.0

# 再启动
docker run -d --name agentforge --restart unless-stopped \
  -p 18080:8080 \
  -v "$PWD/data":/opt/agentforge/data \
  -v "$PWD/logs":/opt/agentforge/logs \
  -v "$PWD/upload":/opt/agentforge/upload \
  -e AGENTFORGE_MODEL_API_KEY=your-key \
  -e AGENTFORGE_FILE_VISIT_HOST=127.0.0.1 \
  -e AGENTFORGE_FILE_VISIT_PORT=18080 \
  agentforge:1.0.0
```

### 12.9 切换 MySQL SOP

```bash
# docker/.env
AGENTFORGE_DB_TYPE=mysql
AGENTFORGE_DB_URL=jdbc:mysql://127.0.0.1:3306/agentforge?...
AGENTFORGE_DB_USERNAME=root
AGENTFORGE_DB_PASSWORD=******
AGENTFORGE_DB_POOL_SIZE=10
```

```bash
cd agentforge-service/docker
docker compose up -d --force-recreate agentforge
```

注意：

- 镜像不变，只切环境变量；
- MySQL 表结构继续执行 `sql/increment/1.0.x/*.sql`（本地 `bin/local/upgrate-sql.sh`）；
- Docker 默认 SQLite 的 `upgrade-sql.sh` 在 MySQL 模式下会自动跳过。

### 12.10 高频故障排查

| 症状 | 原因 | 处置 |
| --- | --- | --- |
| 构建报 `proxyconnect ... connection refused` | buildx builder 配置的代理不可用 | 修复代理，或临时 `docker buildx use orbstack` 后构建 |
| `docker compose up` 后接口 500 / `no such table` | 未执行 SQLite 迁移 | 先执行 `./scripts/upgrade-sql.sh` |
| `SQLITE_BUSY` / 写锁冲突 | 多实例写同一 SQLite 文件 | 保持单实例；或切 MySQL |
| 日期解析失败 `Unparseable date` | SQLite 日期格式与默认 TypeHandler 不匹配 | 保留 SQLite 专用 `SqliteDateTypeHandler`，不要改回毫秒写入 |
| 升级时重复执行了已应用的 SQL | 归档目录未持久化或被清空 | 确保 `AGENTFORGE_SQLITE_APPLIED_DIR` 位于持久化 data 卷；脚本 DDL 幂等，重跑安全 |
| `docker/conf/application.yml` 变成目录 | 直接 `docker compose up` 前未生成配置 | 执行 `./scripts/deploy.sh` 或 `./scripts/upgrade-sql.sh` 生成 |
| 上传 URL 端口不对 | 容器内端口 8080 与宿主机端口不一致 | 设置 `AGENTFORGE_FILE_VISIT_PORT=${WEB_PORT}` |
| 健康检查一直 starting | 应用启动慢 / 迁移未完成 / 端口冲突 | 看 `docker logs agentforge`，确认迁移和端口 |

### 12.11 发布验收清单

- [ ] `mvn clean package` 与 Spotless 通过；
- [ ] 本地 `upgrate-sqlite.sh` 首次 7 个、重复 0 个；
- [ ] 本地 `java -jar` 接口正常；
- [ ] 镜像构建成功；
- [ ] `deploy.sh` 迁移后启动，容器 `healthy`；
- [ ] `/`、`/agents`、`/logo.png`、`/api/**` 正常；
- [ ] 上传文件与 `visitUrl` 正常；
- [ ] `upgrade.sh` 备份成功、迁移 0 重复、数据保留；
- [ ] `package.sh` 产物可 `docker load`；
- [ ] `docker/conf` 外置配置生效；
- [ ] MySQL 模式回归通过。

### 12.12 核心经验总结

1. **迁移与启动解耦**：启动只负责运行，迁移由部署/升级脚本显式完成。
2. **升级先备份**：SQLite 备份成本低，必须形成肌肉记忆。
3. **SQLite 单写**：单实例 + WAL + 单连接池；多实例直接切 MySQL。
4. **双方言目录隔离**：`mapper` 与 `sqlliteMapper`、`sql/increment` 与 `sql/sqllite` 各自演进。
5. **归档目录是唯一事实来源**：`sql/local/sqlite`（容器内 `data/sql-applied`）决定是否执行，与 MySQL 一致。
6. **conf 只挂文件**：避免覆盖镜像内的前端静态资源。
7. **离线分发五步**：打包 → 传输 → load → 迁移 → 启动。
8. **构建环境要可复现**：代理、buildx builder、多架构都会影响构建结果。
9. **健康检查要打业务接口**：不要只检查端口，直接请求 `/api/model-config/options`。
10. **增量脚本只增不改**：已执行的脚本视为不可变，变更走新版本文件。

---

## 13. 最佳实践复刻手册（从零落地）

> 目标：换一台机器、换一个项目，也能按本章步骤原样复刻「SQLite 默认运行 + 一体化 Docker」能力。
> 约定：`$SVC` 指 `agentforge-service` 目录，`$ROOT` 指仓库根目录（`$SVC` 的上一级）。

### 13.1 复刻前置检查

| 能力 | 版本要求 | 检查命令 |
| --- | --- | --- |
| JDK | 17 | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Node | 20（仅构建前端镜像时需要） | `node -v` |
| SQLite CLI | 3.x | `sqlite3 --version` |
| Docker | 24+ 且 Compose v2 | `docker version && docker compose version` |
| 前端构建 | `npm ci` 可执行 | `npm -v` |

本地只跑 SQLite 时，**只需要 JDK + Maven + sqlite3**，无需安装 MySQL。

### 13.2 目录与文件骨架

按下面的最小骨架创建/确认（缺一不可）：

```text
$SVC/
├── bin/local/
│   ├── upgrate-sqlite.sh          # 本地 SQLite 增量迁移（文件归档幂等）
│   ├── upgrate-sql.sh             # 本地 MySQL 增量迁移（对照参考）
│   ├── deploy.conf.example
│   └── README.md
├── conf/
│   ├── application.yml.example     # sqlite/mysql 双 profile
│   └── logback.xml
├── data/                           # 本地 SQLite 数据库（git 忽略）
├── sql/
│   ├── increment/<version>/*.sql   # MySQL 待执行
│   ├── sqllite/<version>/*.sql     # SQLite 待执行
│   └── local/
│       ├── <version>/*.sql         # MySQL 已执行归档
│       └── sqlite/<version>/*.sql  # SQLite 已执行归档（幂等依据）
├── docker/
│   ├── Dockerfile
│   ├── docker-compose.yml
│   ├── .env.example
│   ├── conf/                       # 部署侧外置配置（首次自动生成）
│   ├── container/bin/start.sh
│   └── scripts/{build,deploy,upgrade,upgrade-sql,backup,package,push}.sh
└── agentforge-service-web/
    └── src/main/resources/
        ├── mapper/<业务>/*.xml         # MySQL
        └── sqlliteMapper/<业务>/*.xml  # SQLite
```

`.gitignore` 至少忽略：`data/`、`logs/`、`upload/`、`docker/data/`、`docker/logs/`、`docker/upload/`、`docker/conf/`、`.env.*`、`sql/local/`。

### 13.3 配置层落地

1. 复制配置模板：

   ```bash
   cd "$SVC"
   cp conf/application.yml.example conf/application.yml
   ```

2. 确认 Spring profile 以数据库类型驱动：

   ```yaml
   spring:
     profiles:
       active: ${AGENTFORGE_DB_TYPE:sqlite}
       group:
         sqllite: sqlite        # 兼容历史拼写
   ```

3. 确认两套 `mapper-locations` 分别落在 sqlite / mysql profile 中：

   | profile | mapper-locations |
   | --- | --- |
   | `sqlite` | `classpath*:sqlliteMapper/**/*.xml` |
   | `mysql` | `classpath*:mapper/**/*.xml` |

4. 确认 SQLite 连接串、WAL、单连接池：

   ```yaml
   url: "jdbc:sqlite:${AGENTFORGE_SQLITE_PATH:${user.dir.data}agentforge.db}?busy_timeout=5000&journal_mode=WAL&synchronous=NORMAL&foreign_keys=on"
   hikari:
     maximum-pool-size: ${AGENTFORGE_DB_POOL_SIZE:1}
   ```

5. 确认 `SystemPropertyUtil` 在启动前写入 `user.dir.conf/logs/data/upload` 四个系统属性，
   否则 `${user.dir.data}` 无法解析。

### 13.4 增量 SQL 编写规范

1. 文件名：`<时间戳>_v<版本>.sql`，按文件名升序执行，例如 `20261029000000_v1.0.x.sql`。
2. 版本目录：`sql/sqllite/<version>/`（SQLite）与 `sql/increment/<version>/`（MySQL）同名同版本，便于对照。
3. **SQLite 不要写建库语句**：没有 `CREATE DATABASE` / `USE`，脚本打开文件时自动建库。
4. **不要自带 `BEGIN/COMMIT`**：迁移脚本会统一包事务。
5. **不要放事务外 `PRAGMA`**（如 `journal_mode`），连接串已配置。
6. 建表统一 `CREATE TABLE IF NOT EXISTS`；数据初始化用**幂等写法**（`INSERT ... SELECT ... WHERE NOT EXISTS` 或 `INSERT OR IGNORE`）。
7. 已执行脚本视为不可变，变更一律新增文件，不改旧文件。

验证方言差异可对照 §4.3 的 MySQL → SQLite 转换表。

### 13.5 本地 SQLite 迁移脚本落地

核心行为（与 `bin/local/upgrate-sql.sh` 对齐）：

```text
扫描 $SVC/sql/sqllite/**/*.sql（按相对路径排序）
  ├─ 归档目录 $APPLIED/<相对路径> 已存在 → 跳过
  └─ 不存在 → 事务执行 → 成功后复制到归档
$APPLIED 默认：$SVC/sql/local/sqlite；容器内：/opt/agentforge/data/sql-applied
```

落地步骤：

```bash
cd "$SVC"

# 1) 预览（不建库、不写归档）
./bin/local/upgrate-sqlite.sh --dry-run

# 2) 首次执行（自动建 data/agentforge.db，应用全部增量）
./bin/local/upgrate-sqlite.sh

# 3) 验证
ls -lh data/agentforge.db
find sql/local/sqlite -name '*.sql' | sort
./bin/local/upgrate-sqlite.sh          # 应输出：没有需要执行的 SQLite 增量 SQL
```

自定义路径（CI、沙箱、多实例）：

```bash
AGENTFORGE_SQLITE_PATH=/tmp/af/agentforge.db \
AGENTFORGE_SQLITE_INCREMENT_DIR="$SVC/sql/sqllite" \
AGENTFORGE_SQLITE_APPLIED_DIR=/tmp/af/sql-applied \
./bin/local/upgrate-sqlite.sh
```

### 13.6 Mapper 双方言落地

1. MySQL Mapper 原样保留在 `resources/mapper/<业务>/`。
2. 复制一份到 `resources/sqlliteMapper/<业务>/`，只做方言适配（见 §4.3）。
3. 业务代码不要出现方言判断，切库只靠 `AGENTFORGE_DB_TYPE`。
4. SQLite 日期字段统一 `TEXT` + 专用 `SqliteDateTypeHandler`，避免 `Unparseable date`。
5. 新增业务时**两套 Mapper 同步新增**，用 diff 检查是否遗漏。

### 13.7 一体化镜像落地

`docker/Dockerfile` 三阶段：

```text
maven:3.9-eclipse-temurin-17  → 编译 agentforge-service-web
node:20-alpine                → 构建前端 dist
eclipse-temurin:17-jre-jammy  → 运行时
```

运行时关键动作（缺一则镜像跑不起来）：

```dockerfile
ENV APP_HOME=/opt/agentforge AGENTFORGE_DB_TYPE=sqlite
RUN apt-get install -y curl sqlite3 \
 && mkdir -p $APP_HOME/{bin,conf,data,logs,upload,lib} \
             $APP_HOME/sql/sqllite $APP_HOME/sql/local/sqlite $APP_HOME/data/sql-applied

COPY agentforge-service/docker/container/bin/      $APP_HOME/bin/
COPY agentforge-service/bin/local/upgrate-sqlite.sh $APP_HOME/bin/upgrade-sql.sh
COPY agentforge-service/conf/application.yml.example $APP_HOME/conf/application.yml
COPY agentforge-service/sql/sqllite/                $APP_HOME/sql/sqllite/
COPY --from=backend  .../agentforge-service-web-*.jar $APP_HOME/lib/app.jar
COPY --from=frontend .../dist/                      $APP_HOME/conf/static/

RUN chmod +x $APP_HOME/bin/*.sh
ENTRYPOINT ["/opt/agentforge/bin/start.sh"]
```

构建并验证：

```bash
cd "$SVC/docker"
cp .env.example .env
./scripts/build.sh 1.0.0
docker image inspect agentforge-service:1.0.0 >/dev/null && echo "image OK"
```

### 13.8 Compose 与环境变量落地

`docker/docker-compose.yml` 必需项：

```yaml
environment:
  AGENTFORGE_DB_TYPE: ${AGENTFORGE_DB_TYPE:-sqlite}
  AGENTFORGE_SQLITE_PATH: ${AGENTFORGE_SQLITE_PATH:-/opt/agentforge/data/agentforge.db}
  AGENTFORGE_SQLITE_INCREMENT_DIR: ${AGENTFORGE_SQLITE_INCREMENT_DIR:-/opt/agentforge/sql/sqllite}
  # 关键：归档目录落在 data 卷内，容器/一次性容器重建后幂等判断不丢失
  AGENTFORGE_SQLITE_APPLIED_DIR: ${AGENTFORGE_SQLITE_APPLIED_DIR:-/opt/agentforge/data/sql-applied}
volumes:
  - ./data:/opt/agentforge/data
  - ./logs:/opt/agentforge/logs
  - ./upload:/opt/agentforge/upload
  - ./conf/application.yml:/opt/agentforge/conf/application.yml:ro
  - ./conf/logback.xml:/opt/agentforge/conf/logback.xml:ro
healthcheck:
  test: ["CMD", "curl", "-fsS", "http://127.0.0.1:8080/api/model-config/options"]
```

校验：

```bash
cd "$SVC/docker"
docker compose config >/dev/null && echo "compose OK"
```

### 13.9 部署 / 升级 / 备份 / 离线 复刻命令

```bash
cd "$SVC/docker"

# 首次部署：构建镜像 → 一次性容器迁移 → 启动
./scripts/deploy.sh

# 增量升级：备份 → 换 tag → 拉新镜像 → 一次性容器迁移 → 重建
./scripts/upgrade.sh 1.0.1

# 仅新增 SQL、不换镜像
./scripts/upgrade-sql.sh
docker compose up -d --force-recreate agentforge

# 备份
./scripts/backup.sh           # backups/agentforge-YYYYmmdd-HHMMSS.db

# 离线打包 / 分发
./scripts/package.sh 1.0.0    # dist/agentforge-1.0.0.tar

# 推送多架构
IMAGE_REPO=dockerhub-user/agentforge ./scripts/push.sh 1.0.0
```

离线目标机（务必带上归档目录环境变量）：

```bash
docker load -i agentforge-1.0.0.tar
mkdir -p data logs upload
docker run --rm \
  -v "$PWD/data":/opt/agentforge/data \
  -e AGENTFORGE_SQLITE_APPLIED_DIR=/opt/agentforge/data/sql-applied \
  --entrypoint /opt/agentforge/bin/upgrade-sql.sh \
  agentforge-service:1.0.0
```

预期输出：首次「成功执行 N 个 SQL」，再次「没有需要执行的 SQLite 增量 SQL」。

### 13.10 从旧版（历史表机制）平滑迁移

旧版用 `agentforge_schema_history` 记录已执行脚本，新版改为文件归档。二选一：

**方案 A（推荐，简单且安全）**：直接执行一次新脚本。旧库里的表已存在，增量脚本的
`CREATE TABLE IF NOT EXISTS` 与幂等 `INSERT` 保证重跑无副作用，跑完即生成归档：

```bash
./bin/local/upgrate-sqlite.sh
# Docker：
cd docker && AGENTFORGE_SQLITE_APPLIED_DIR="$PWD/data/sql-applied" \
  ./scripts/upgrade-sql.sh
```

**方案 B（不想重跑）**：把已执行脚本回填到归档目录，再执行新脚本：

```bash
cd "$SVC"
for f in sql/sqllite/1.0.x/*.sql; do
  rel="${f#sql/sqllite/}"
  mkdir -p "sql/local/sqlite/$(dirname "$rel")"
  cp -f "$f" "sql/local/sqlite/$rel"
done
# Docker 数据卷
mkdir -p docker/data/sql-applied/1.0.x
cp -f sql/sqllite/1.0.x/*.sql docker/data/sql-applied/1.0.x/
```

> 回填只适用于「确认这些脚本确实已执行过」的情况；不确定就选方案 A。

### 13.11 复刻验收清单

```bash
# 本地
[ ] ./bin/local/upgrate-sqlite.sh --dry-run       # 正确列出待执行
[ ] ./bin/local/upgrate-sqlite.sh                 # 首次成功执行
[ ] ./bin/local/upgrate-sqlite.sh                 # 再次 0 待执行
[ ] ls data/agentforge.db                         # 库文件存在
[ ] find sql/local/sqlite -name '*.sql' | wc -l   # 归档数量 = 脚本数量
[ ] java -jar agentforge-service-web/target/*.jar # 接口正常

# Docker
[ ] ./scripts/build.sh 1.0.0                      # 镜像构建成功
[ ] ./scripts/deploy.sh                           # 迁移 + 启动成功
[ ] docker compose ps                             # healthy
[ ] docker exec agentforge ls /opt/agentforge/data/sql-applied   # 归档存在
[ ] ./scripts/upgrade.sh 1.0.1                    # 备份 + 迁移 0 个 + 数据保留
[ ] ./scripts/package.sh 1.0.0                    # tar 可 docker load
[ ] MySQL 模式回归：AGENTFORGE_DB_TYPE=mysql 启动接口正常
```

### 13.12 复刻高频坑位

| 坑 | 现象 | 正确做法 |
| --- | --- | --- |
| 归档目录没持久化 | 每次升级都「成功执行 N 个」 | `AGENTFORGE_SQLITE_APPLIED_DIR` 放到 `./data` 卷内 |
| SQLite 写了 `CREATE DATABASE` | 迁移直接语法报错 | 删掉建库语句，库文件自动创建 |
| 增量脚本自带 `BEGIN/COMMIT` | 事务嵌套报错 | 删除，由脚本统一包事务 |
| 改了已执行脚本期待生效 | 没有重新执行 | 新增版本文件，不改旧文件 |
| 只删 `agentforge.db` 保留归档 | 启动后 `no such table` | 同时清空归档目录，或删库后重新跑迁移 |
| 多实例写同一 `.db` | `SQLITE_BUSY` | 单实例；多实例切 MySQL |
| 挂载整个 `conf/` 目录 | 前端静态资源丢失 | 只挂载 `application.yml`、`logback.xml` 两个文件 |
| 上传 URL 端口错误 | 上传成功但访问 404 | 设置 `AGENTFORGE_FILE_VISIT_PORT=${WEB_PORT}` |
| 容器启动未迁移 | 接口 500 / `no such table` | 先 `./scripts/upgrade-sql.sh` 再启动 |

### 13.13 复刻完成定义（DoD）

- [ ] 本地零 MySQL 依赖可启动；
- [ ] 增量 SQL 双方言目录各就各位，命名与版本一致；
- [ ] 本地迁移首次全量、再次 0 个，归档目录与脚本一一对应；
- [ ] 镜像单容器包含后端 + 前端 + SQLite；
- [ ] `deploy.sh` / `upgrade.sh` / `upgrade-sql.sh` / `backup.sh` / `package.sh` / `push.sh` 均可执行；
- [ ] 容器重建后幂等判断不丢失（归档在 data 卷）；
- [ ] 切 MySQL 仅改环境变量，Mapper 自动切换，功能回归通过；
- [ ] 旧历史表部署可按 §13.10 平滑迁移，无重复写入。
