# AgentForge Service

## Database modes

The service starts on **SQLite by default**; no external database is required:

```text
agentforge-service/data/agentforge.db
```

The service does **not** create tables at startup. Run the migration script once before the first
start (and after each SQL change):

```bash
cd agentforge-service
./bin/local/upgrate-sqlite.sh
```

Switch to MySQL without changing code:

```bash
# SQLite (default)
AGENTFORGE_DB_TYPE=sqlite

# MySQL
AGENTFORGE_DB_TYPE=mysql
AGENTFORGE_DB_URL='jdbc:mysql://127.0.0.1:3306/agentforge?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
AGENTFORGE_DB_USERNAME=root
AGENTFORGE_DB_PASSWORD=123456
```

Mapper XMLs are maintained per dialect:

| Database | Mapper location | Incremental SQL |
| --- | --- | --- |
| SQLite | `src/main/resources/sqlliteMapper/<业务>/*.xml` | `sql/sqllite/<version>/*.sql` (`bin/local/upgrate-sqlite.sh`) |
| MySQL | `src/main/resources/mapper/<业务>/*.xml` | `sql/increment/<version>/*.sql` (`bin/local/upgrate-sql.sh`) |

SQLite applied scripts are recorded in `agentforge_schema_history`. The service does **not** migrate
at startup; local runs and Docker deploy/upgrade execute the SQLite migration script explicitly.

## Start backend

配置与日志统一放在 `agentforge-service/conf/` 目录（由 `SystemPropertyUtil` 在启动时解析）：

```bash
# 1. Prepare the local config from the template
cp agentforge-service/conf/application.yml.example \
   agentforge-service/conf/application.yml

# 2. Build and run (run from the agentforge-service dir so conf/ and logs/ are resolved)
export AGENTFORGE_MODEL_API_KEY=your-api-key
mvn package -pl agentforge-service/agentforge-service-web -am -DskipTests
cd agentforge-service
./bin/local/upgrate-sqlite.sh
java -jar agentforge-service-web/target/agentforge-service-web-1.0.0-SNAPSHOT.jar
```

启动时读取 `${user.dir.conf}/application.yml` 与 `${user.dir.conf}/logback.xml`；日志写入 `logs/agentforge-service.log`（历史滚动到 `logs/history/`），LLM 模块日志单独写入 `logs/llm.log`。

The default [OI]-compatible endpoint is `https://tokenrhythm.studio/v1` and the default model is `deepseek-flash`.

`conf/application.yml` is generated from `conf/application.yml.example` and is git-ignored; edit it locally or override any item with environment variables:

| Variable | Default | Description |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | Backend HTTP port |
| `AGENTFORGE_DB_TYPE` | `sqlite` | Database mode: `sqlite` or `mysql` |
| `AGENTFORGE_SQLITE_PATH` | `data/agentforge.db` | SQLite database file |
| `AGENTFORGE_SQLITE_INCREMENT_DIR` | `sql/sqllite` | SQLite incremental SQL directory |
| `AGENTFORGE_DB_URL` | local MySQL URL | MySQL JDBC URL |
| `AGENTFORGE_DB_USERNAME` | `root` | MySQL username |
| `AGENTFORGE_DB_PASSWORD` | `123456` | MySQL password |
| `AGENTFORGE_MODEL_BASE_URL` | `https://tokenrhythm.studio/v1` | OpenAI-compatible endpoint |
| `AGENTFORGE_MODEL_API_KEY` | *(empty)* | API key, required at call time |
| `AGENTFORGE_MODEL_NAME` | `deepseek-flash` | Model name |
| `AGENTFORGE_MODEL_TEMPERATURE` | `0.3` | Sampling temperature |
| `OPENREACH_BASE_URL` | `http://openreach.changlu.cloud` | OpenReach service base URL; leave empty to disable the web tools |

### Web tools (OpenReach, local mode)

When `OPENREACH_BASE_URL` (or `agentforge.openreach.base-url`) is configured, the agent gains four
local `@Tool` methods scanned by `LocalToolFactory`:

| Tool | Description |
| --- | --- |
| `webSearch` | Public web search, returns candidate results |
| `webImageSearch` | Image search, returns validated `imageUrl`s |
| `webRead` | Read the main text of a public web page |
| `webCurl` | Safe read-only GET/HEAD for GitHub API / raw source / public JSON |

Live tests hit the real service by default; skip with `-Dagentforge.openreach.live=false`, or point
elsewhere with `-Dagentforge.openreach.base-url=<url>`.

## Docker (all-in-one)

The image contains the backend, frontend and SQLite. Runtime data stays under `docker/data/`:

```bash
cd agentforge-service/docker
cp .env.example .env
./scripts/deploy.sh        # build + migrate + start on http://127.0.0.1:18080
./scripts/upgrade.sh 1.0.1 # backup + migrate + recreate with the new tag
./scripts/upgrade-sql.sh   # only execute SQLite incremental SQL
./scripts/package.sh 1.0.0 # export an offline image tar
```

`docker/conf/application.yml` and `docker/conf/logback.xml` are generated on first run and mounted
into the container, so they can be edited without rebuilding. See `docker/README.md` for details.

## Start frontend

```bash
cd agentforge-service/agentforge-service-ui
npm install
npm run dev
```

前端为单入口 Vue Router SPA：`/` 我的模型、`/agents` Agent 列表、`/mcp` MCP 插件、`/http-plugins` HTTP 插件、`/agent-trace` Agent Trace、`/chat` 与 `/agent/published/:aid` 对话页（`npm run build` 统一产出到 `dist/`）。生产环境使用 history 路由时，需要将未知路径回退到 `index.html`（例如 Nginx `try_files $uri $uri/ /index.html`）。

前端目录：`src/router` 路由、`src/api` 接口模块、`src/pages` 路由页面、`src/components` 共享组件、`src/styles` 全局与后台样式。页面内仍保留 `.vue` 的 scoped 样式，构建时 Vite 会按路由动态 `import()` 自动拆出 `AgentTracePage-*.js/.css` 等按需加载 chunk；这不是多页面入口，首屏只加载公共 vendor 与当前路由资源。

The UI sends `POST /api/chat/stream` and consumes the unified SSE events: `session`, `step`, `think`, `tool_call`, `resp`, `resp_end`, and `error`.
