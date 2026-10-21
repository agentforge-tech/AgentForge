# 本地增量 SQL

在 `agentforge-service` 目录执行。

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
