# 本地增量 SQL

在 `agentforge-service` 目录执行。

## SQLite（默认）

```bash
# 预览待执行 SQL（不修改数据库）
./bin/local/upgrate-sqlite.sh --dry-run

# 执行 SQLite 增量 SQL
./bin/local/upgrate-sqlite.sh
```

默认数据库：`data/agentforge.db`；默认脚本目录：`sql/sqllite`。
可用环境变量覆盖：

```bash
AGENTFORGE_SQLITE_PATH=/path/to/agentforge.db \
AGENTFORGE_SQLITE_INCREMENT_DIR=/path/to/sqllite \
./bin/local/upgrate-sqlite.sh
```

执行记录写入 `agentforge_schema_history`，重复执行安全。服务启动本身不会自动迁移，
本地首次运行或升级后需要先执行该脚本。

## MySQL

```bash
# 首次使用：创建本机数据库配置
cp bin/local/deploy.conf.example bin/local/deploy.conf

# 查看待执行的增量 SQL，不连接或修改数据库
./bin/local/upgrate-sql.sh --dry-run

# 执行本地增量 SQL
./bin/local/upgrate-sql.sh
```

首次执行时，如果配置的数据库不存在，脚本会自动创建数据库。

如需临时指定其他数据库配置：

```bash
LOCAL_UPGRADE_CONFIG=/path/to/deploy.conf ./bin/local/upgrate-sql.sh --dry-run
```
