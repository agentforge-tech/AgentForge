# AgentForge Service 接入 HTTP 插件设计方案

> 文档定位：AgentForge Service 业务层落地 HTTP 插件的可执行设计基线。
>
> 设计基线：
>
> - 业务层：`agentforge-service/agentforge-service-web`
> - 框架层：`agentforge-framework/agentforge-agent-core`
> - 模型层：`agentforge-model`
> - 参考设计：`documents/standdev/backend/http插件/0、HTTP插件系统设计文档.md`
>
> 目标不是把原系统的四张表原样搬过来，而是把「HTTP API 变成 Agent 可调用工具」这件事，拆成 **Service 管理面** 与 **框架执行面** 两层，并与 AgentForge 现有的 `ToolService`、`StandardAgentConfig`、`ReActAgentFactory`、Agent 配置版本/发布机制对齐。

---

## 1. 背景与目标

### 1.1 背景

AgentForge 框架层已经具备 HTTP 工具能力：

| 能力 | 现有实现 |
| --- | --- |
| HTTP 插件领域模型 | `HttpPlugin`、`HttpPluginMethod`、`HttpToolParameter` |
| HTTP 工具构建 | `HttpToolFactory` |
| HTTP 工具执行 | `HttpToolExecutor` |
| 参数类型/位置枚举 | `HttpPluginEnums` |
| curl 解析与转换 | `CurlParser`、`CurlToHttpPluginConverter` |
| 插件反向导出 curl | `HttpPluginToCurlConverter` |
| 工具统一注册 | `ToolService`、`ToolSpecification`、`ToolExecutor` |

但业务层 `agentforge-service` 目前只有模型配置、Agent 配置、会话与消息，没有 HTTP 插件的持久化、管理接口、Agent 装配和运行期缓存失效机制。也就是说：

```text
框架层：能把 HttpPlugin 变成 ToolSpecification + ToolExecutor
业务层：还没有地方创建、保存、选择、装配 HttpPlugin
```

因此本文要补齐的，是 **Service 接入层**。

### 1.2 目标

1. 用户可以在 AgentForge Service 中创建 HTTP 插件，并配置多个工具方法。
2. 每个方法可以配置 Query / Body / Path / Header 四类参数，支持 String / Integer / Number / Boolean / Array / Object 六种类型。
3. 支持从 curl 命令导入插件和工具，降低接入成本。
4. 支持对工具做在线试运行。
5. Agent 配置可以选择插件下的部分或全部工具，并在运行时装配进 `ToolService`。
6. 插件或工具变更后，相关 Agent 的运行实例可以被可靠失效，避免继续使用旧工具定义。
7. 表结构、接口、代码结构与现有 `agentforge-service` 的规范保持一致，可直接拆成开发任务。

### 1.3 V1 边界（非目标）

以下能力不在 V1 范围内，但表结构和扩展点需要预留：

| 能力 | V1 策略 |
| --- | --- |
| 插件市场、安装、评分 | 不实现；保留 `is_public` 字段，暂不开放跨用户安装 |
| 插件导出/导入 JSON、脱敏配置 | 框架已有 curl 反向导出能力，V1 可先不做完整导出；后续复用 `HttpPluginToCurlConverter` 或扩展 JSON 导出 |
| OAuth2 / 动态 Token 刷新 | 不实现；V1 只支持静态 Header |
| multipart/form-data、XML、form-urlencoded | 不实现；V1 只支持 JSON Body |
| 插件多版本快照 | 不实现；V1 采用「插件定义实时生效 + Agent 缓存失效」 |
| 调用日志落库 | 可作为 P1；先保证框架日志脱敏，再补结构化调用日志 |

### 1.4 术语

| 术语 | 含义 |
| --- | --- |
| HTTP 插件 | 一个 HTTP 服务的基础配置，包含 `baseUrl` 与静态请求头 |
| 工具方法 | 插件下的一个 HTTP API 端点，最终变成一个模型可调用的 Tool |
| 工具参数 | 方法参数的模型侧名称、API 侧映射名、位置、类型、默认值、是否必填 |
| 暴露工具名 | 最终注册进 `ToolService` 的函数名，格式为 `{alias}__{methodName}` |
| Agent 工具引用 | `ai_agent_config.agent_config.pluginConfig` 中对 HTTP/MCP 插件的引用 |
| 运行期装配 | 根据 Agent 配置读取插件定义，转换为 `ToolSpecification + ToolExecutor` 并注册进 `ToolService` |

---

## 2. 调研结论

### 2.1 原 HTTP 插件系统的核心设计

原系统用四张表完成插件、工具、参数和市场统计：

```text
ai_plugin_http 1 ────── N ai_plugin_http_method 1 ────── N ai_plugin_http_parameter
       │
       └── 0..1 ai_plugin_http_market
```

> 注：原系统表名带 `http` 标识；本方案统一收敛为 `ai_plugin_http_*` 前缀，下文均以新前缀为准。

核心设计要点：

1. **插件与方法分离**：插件保存 `base_url` 和公共请求头，方法保存具体 `uri`、HTTP Method。
2. **参数映射**：`method_param_name` 是模型调用名，`mapped_name` 是真实 API 参数名，解决驼峰/下划线差异。
3. **参数位置**：Query / Body / Path / Header 四类，由 `use_type_value` 控制。
4. **参数类型**：String / Integer / Number / Boolean / Array / Object，由 `data_type_value` 控制。
5. **执行引擎**：`HttpToolExecutor` 负责参数校验、类型转换、默认值填充、URL/Header/Body 组装，最终发起 HTTP 请求。
6. **curl 导入**：`CurlParser` + `CurlToHttpPluginConverter` 自动推断 Method、Query、Body、Header，并生成方法参数。
7. **市场统计**：`ai_plugin_http_market` 记录引用数、下载数、评分。

原系统值得直接继承的部分：

- 插件/方法/参数三层模型；
- 参数位置与类型枚举；
- `method_param_name` 与 `mapped_name` 分离；
- curl 导入与在线试运行；
- `HttpPlugin -> ToolSpecification` 的转换思路。

原系统需要针对 AgentForge 调整的部分：

| 原设计 | AgentForge 调整 | 原因 |
| --- | --- | --- |
| `method_name` 同时承担展示名和工具名 | 拆成 `method_name`（工具名后缀）+ `display_name`（展示名） | 模型工具名需要稳定、可校验、避免中文和空格 |
| 工具名直接使用方法名 | 最终工具名增加插件别名前缀 `{alias}__{methodName}` | 一个 Agent 可挂多个插件，`ToolService` 对重复工具名会直接抛异常 |
| Agent 与插件关联 | 直接保存在 `agent_config.pluginConfig` | Agent 配置 JSON 作为唯一事实源，避免双写关联表 |
| `publish` 标记控制插件发布 | V1 只保留 `status`，不引入插件版本 | 避免首版引入插件版本快照，先用「实时定义 + 缓存失效」 |
| 市场统计表 | V1 不建表，保留 `is_public` 字段 | AgentForge 暂无插件市场/安装体系 |
| `static_headers` 明文存储、日志直接打印 | API 返回脱敏；框架日志脱敏；后续加密 | 防止 Token 泄漏 |

### 2.2 AgentForge 现状

#### 2.2.1 框架层已有能力

`HttpToolFactory` 的职责非常明确：

```text
HttpPlugin
  ├── baseUrl
  ├── staticHeaders
  └── pluginMethods[]
        ├── methodName        -> ToolSpecification.name
        ├── methodDescription -> ToolSpecification.description
        ├── httpMethodType
        ├── uri
        └── parameters[]
              ├── methodParamName -> JSON Schema 属性名
              ├── mappedName
              ├── useTypeValue
              ├── dataTypeValue
              ├── defaultValue
              └── required
```

最终输出：

```java
Map<ToolSpecification, ToolExecutor>
```

再通过：

```java
toolService.tools(HttpToolFactory.buildHttpTools(httpPlugin));
```

注册进 Agent。

#### 2.2.2 Service 层现状

当前 Agent 运行时装配入口在：

```text
agentforge-service/agentforge-service-web/src/main/java/cloud/changlu/agentforge/service/modules/agent/runtime/mode/react/ReActAgentFactory.java
```

现有 `buildToolService()` 只注册了本地工具：

```java
private ToolService buildToolService() {
    ToolService toolService = new ToolService();
    toolService.tools(
            LocalToolFactory.buildLocalToolsWithThought(
                    Collections.<Object>singletonList(openReachTools), true));
    return toolService;
}
```

`StandardAgentConfig` 目前只有：

```java
private String systemPrompt;
private AgentModelConfig chatModel;
private ChatMemoryConfig chatMemoryConfig;
```

没有 `tools` 字段。因此 HTTP 插件接入不仅是「建表 + CRUD」，还必须同步改造 Agent 配置结构。

### 2.3 差距清单

| 差距 | 影响 | 落地动作 |
| --- | --- | --- |
| 没有 HTTP 插件表 | 无法持久化 | 新增 3 张核心表 |
| 没有插件管理接口 | 无法配置 | 新增 Controller / Service / Mapper |
| `StandardAgentConfig` 没有 `tools` | Agent 无法引用插件 | 扩展配置 JSON 与 Java 模型 |
| `ReActAgentFactory` 只装配本地工具 | 运行时不加载插件 | 新增 `HttpPluginRuntimeService` |
| 插件变更后 Agent 缓存不失效 | 继续使用旧工具 | 事务提交后统一失效标准 Agent 缓存 |
| 框架 `HttpToolExecutor` 有若干限制 | 部分场景不可用 | 在接入前修复框架问题，见第 10 章 |

---

## 3. 总体方案

### 3.1 分层架构

```text
┌────────────────────────────────────────────────────────────────────┐
│                         前端管理页面                                │
│  插件列表 / 插件详情 / 工具详情 / 参数配置 / 试运行 / curl 导入       │
└───────────────────────────────┬────────────────────────────────────┘
                                │ REST
┌───────────────────────────────▼────────────────────────────────────┐
│                  AgentForge Service 管理面                          │
│                                                                    │
│  HttpPluginController        HttpPluginMethodController             │
│  HttpPluginService           HttpPluginMethodService                │
│  HttpPluginParameterService  HttpPluginCurlService                  │
│  HttpPluginRuntimeService    HttpPluginAgentCacheService            │
│                                                                    │
│  ai_plugin_http / ai_plugin_http_method / ai_plugin_http_parameter │
└───────────────────────────────┬────────────────────────────────────┘
                                │ 转换为 HttpPlugin
┌───────────────────────────────▼────────────────────────────────────┐
│                    AgentForge 框架执行面                             │
│                                                                    │
│  HttpPlugin -> HttpToolFactory -> ToolSpecification + ToolExecutor  │
│                              │                                     │
│                              ▼                                     │
│                          ToolService                                │
│                              │                                     │
│                              ▼                                     │
│                        ReActAgent / ChatModel                       │
└────────────────────────────────────────────────────────────────────┘
```

职责边界：

| 层 | 职责 | 不做什么 |
| --- | --- | --- |
| Service 管理面 | 插件/方法/参数 CRUD、校验、权限、curl 导入、试运行、缓存失效 | 不实现 HTTP 协议细节 |
| 框架执行面 | 参数类型转换、请求组装、HTTP 调用、结果返回 | 不关心数据库、用户、权限 |
| Agent 运行时 | 根据 Agent 配置选择插件和方法，装配工具 | 不直接拼 HTTP 请求 |

### 3.2 设计原则

1. **配置与运行分离**：表只存配置，运行期按 Agent 配置装配 `HttpPlugin`，不缓存数据库实体到框架层。
2. **插件定义与 Agent 引用分离**：插件是共享资源，Agent 只在 `pluginConfig.httpPlugins` 中保存 `pluginId + pluginMethodIds` 引用，不复制参数定义。
3. **工具名命名空间化**：最终工具名必须带插件别名前缀，避免多个插件、MCP、本地工具之间重名。
4. **失败隔离**：某个插件加载失败、某个方法配置非法，不应阻断整个 Agent 构建；跳过并记录日志。
5. **变更可失效**：插件/方法/参数变更后，V1 直接清理标准 Agent 运行时缓存，不为反查再建派生关联表。
6. **逻辑删除 + 数据隔离**：所有业务表使用 `is_deleted`、`create_user_id`，与现有表规范一致。
7. **敏感信息不落前端、不落日志**：静态 Header 在查询接口中脱敏，框架日志中屏蔽敏感 Header。

### 3.3 关键决策

| 决策点 | 方案 | 说明 |
| --- | --- | --- |
| 表名 | `ai_plugin_http*` | 与现有 `ai_agent_config`、`ai_model_config` 一致 |
| 核心表数量 | 3 张核心表 + 可选调用日志表 | Agent 引用保存在自身配置 JSON，不新建关联表 |
| 工具名 | `{alias}__{method_name}` | 与 MCP 的 `{server_alias}__{tool_name}` 保持一致 |
| 方法标识 | `method_name` 存模型可见后缀，`display_name` 存展示名 | 避免展示名变更导致工具名漂移 |
| Agent 引用 | `agent_config.pluginConfig.httpPlugins[]` | HTTP 与 MCP 都收敛在技能配置中 |
| 发布语义 | Agent 快照保存引用，插件定义实时生效 | V1 不引入插件版本；需要冻结时复制插件 |
| 市场能力 | V1 不做 | 保留 `is_public`，后续再加安装/市场表 |
| 参数默认值 | 统一按字符串存 `default_value` | Array/Object 以 JSON 字符串存储，执行期转换 |

---

## 4. 核心表结构设计

### 4.1 ER 关系

```text
┌──────────────────────────────────┐
│          ai_plugin_http          │
├──────────────────────────────────┤
│ id (PK)                          │
│ name                             │
│ alias                            │
│ base_url                         │
│ static_headers                   │
│ status / is_public               │
│ create_user_id / is_deleted      │
└───────────────┬──────────────────┘
                │ 1
                │ N
┌───────────────▼──────────────────┐
│      ai_plugin_http_method       │
├──────────────────────────────────┤
│ id (PK)                          │
│ plugin_id (FK)                   │
│ method_name                      │
│ display_name                     │
│ http_method_type                 │
│ uri                              │
│ status / sort_order              │
└───────────────┬──────────────────┘
                │ 1
                │ N
┌───────────────▼──────────────────┐
│     ai_plugin_http_parameter     │
├──────────────────────────────────┤
│ id (PK)                          │
│ method_id (FK)                   │
│ method_param_name                │
│ mapped_name                      │
│ use_type_value                   │
│ data_type_value                  │
│ default_value / required         │
└──────────────────────────────────┘

```

关系说明：

- 一个插件包含多个方法；
- 一个方法包含多个参数；
- 一个 Agent 可以引用多个插件；
- Agent 与插件的引用关系只以 `ai_agent_config.agent_config` 为准；
- 删除插件时，逻辑删除其下方法和参数，并失效 Agent 运行时缓存。

### 4.2 插件主表 `ai_plugin_http`

```sql
CREATE TABLE IF NOT EXISTS `ai_plugin_http` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name`           VARCHAR(128)  NOT NULL COMMENT '插件名称，展示用',
    `alias`          VARCHAR(64)   NOT NULL COMMENT '工具名前缀，同一用户下未删除记录唯一，创建后不建议修改',
    `base_url`       VARCHAR(1024) NOT NULL COMMENT '服务基础地址，例如 https://api.github.com',
    `static_headers` TEXT                   DEFAULT NULL COMMENT '静态请求头JSON，例如 {"Accept":"application/json"}',
    `description`    VARCHAR(1024)          DEFAULT NULL COMMENT '插件描述',
    `icon`           VARCHAR(512)           DEFAULT NULL COMMENT '插件图标地址',
    `status`         TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `is_public`      TINYINT       NOT NULL DEFAULT 0 COMMENT '是否公开：0私有 1公开，V1仅预留',
    `create_user_id` BIGINT        NOT NULL DEFAULT 1 COMMENT '创建者ID',
    `is_deleted`     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HTTP插件表';
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `name` | 展示名，允许中文；不要求全局唯一 |
| `alias` | 工具名前缀，建议 `^[A-Za-z][A-Za-z0-9_]{1,15}$`；同一用户下未删除插件唯一 |
| `base_url` | 只允许 `http/https`，保存前校验；末尾 `/` 统一去掉 |
| `static_headers` | JSON 对象；敏感 Header 在 API 返回时脱敏，后续加密存储 |
| `status` | 插件级启停；禁用后其下方法不再参与 Agent 装配 |
| `is_public` | V1 只做展示/预留，不开放跨用户引用 |
| `create_user_id` | 数据隔离字段 |
| `is_deleted` | 逻辑删除 |

> 为什么不给 `(create_user_id, alias)` 建数据库唯一键：逻辑删除后重新创建同名插件会与历史删除记录冲突。与 MCP 表设计一致，唯一性由 Service 校验 `is_deleted = 0` 的记录。

### 4.3 方法表 `ai_plugin_http_method`

```sql
CREATE TABLE IF NOT EXISTS `ai_plugin_http_method` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `plugin_id`          BIGINT        NOT NULL COMMENT '所属插件ID',
    `method_name`        VARCHAR(64)   NOT NULL COMMENT '模型可见工具名后缀，例如 get_user_repos',
    `display_name`       VARCHAR(128)           DEFAULT NULL COMMENT '展示名称，例如 获取用户仓库',
    `method_description` VARCHAR(1024)          DEFAULT NULL COMMENT '模型可见工具描述',
    `http_method_type`   TINYINT       NOT NULL COMMENT 'HTTP方法：1GET 2POST 3PUT 4DELETE 5PATCH',
    `uri`                VARCHAR(1024) NOT NULL COMMENT '请求URI，例如 /users/{username}/repos',
    `status`             TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `sort_order`         INT           NOT NULL DEFAULT 0 COMMENT '展示排序',
    `is_deleted`         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    `create_time`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HTTP插件方法表';
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `plugin_id` | 关联 `ai_plugin_http.id`，逻辑外键 |
| `method_name` | 工具名后缀，必须稳定；建议 `^[A-Za-z][A-Za-z0-9_]{0,39}$` |
| `display_name` | 页面展示名，可中文；不参与工具名计算 |
| `method_description` | 最终进入 `ToolSpecification.description`，直接影响模型选择工具，必须写清楚 |
| `http_method_type` | 复用框架 `HttpPluginEnums.HttpMethod`：1GET / 2POST / 3PUT / 4DELETE / 5PATCH |
| `uri` | 与 `base_url` 拼接；Path 参数占位符必须使用 `mapped_name`，例如 `/users/{username}` |
| `status` | 方法级启停；禁用后不参与装配 |
| `sort_order` | 页面展示排序，不影响执行 |

校验规则：

1. 同一插件下 `method_name` 在未删除记录中唯一。
2. `method_name` 不要包含 `__`，因为 `__` 是工具名命名空间分隔符。
3. `uri` 必须以 `/` 开头；完整 URL = `base_url + uri`。
4. 每个 `PATH` 参数必须在 `uri` 中存在 `{mapped_name}` 占位符。
5. `GET` / `DELETE` 方法不允许配置 `BODY` 参数，因为现有 `HttpToolExecutor` 对 GET/DELETE 强制不使用 JSON Body。
6. `QUERY` 参数只允许 String / Integer / Number / Boolean；Array/Object 只允许 `BODY`。

### 4.4 参数表 `ai_plugin_http_parameter`

```sql
CREATE TABLE IF NOT EXISTS `ai_plugin_http_parameter` (
    `id`                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `method_id`                BIGINT       NOT NULL COMMENT '所属方法ID',
    `method_param_name`        VARCHAR(64)  NOT NULL COMMENT '模型侧参数名',
    `method_param_description` VARCHAR(512)          DEFAULT NULL COMMENT '参数描述',
    `mapped_name`              VARCHAR(128) NOT NULL COMMENT '真实API参数名',
    `use_type_value`           TINYINT      NOT NULL COMMENT '参数位置：1Query 2Body 3Path 4Header',
    `data_type_value`          TINYINT      NOT NULL COMMENT '参数类型：1String 2Integer 3Number 4Boolean 5Array 6Object',
    `default_value`            VARCHAR(2048)         DEFAULT NULL COMMENT '默认值；Array/Object 存JSON字符串',
    `required`                 TINYINT      NOT NULL DEFAULT 0 COMMENT '是否必填：0否 1是',
    `sort_order`               INT          NOT NULL DEFAULT 0 COMMENT '展示排序',
    `is_deleted`               TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    `create_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HTTP插件方法参数表';
```

枚举值（直接复用框架 `HttpPluginEnums`）：

| 字段 | 值 | 含义 |
| --- | --- | --- |
| `use_type_value` | 1 | Query |
| | 2 | Body |
| | 3 | Path |
| | 4 | Header |
| `data_type_value` | 1 | String |
| | 2 | Integer |
| | 3 | Number |
| | 4 | Boolean |
| | 5 | Array |
| | 6 | Object |

参数校验规则：

1. 同一方法下 `method_param_name` 唯一。
2. `method_param_name` 建议 `^[A-Za-z_][A-Za-z0-9_]*$`，避免模型生成的参数名不合法。
3. `mapped_name` 不能为空；Header 参数不能使用 `Host`、`Content-Length` 等由 HTTP 客户端管理的头。
4. `PATH` 参数的 `mapped_name` 必须能匹配 `uri` 中的占位符。
5. `required = 1` 且没有默认值时，执行期缺少参数会抛 `IllegalArgumentException`。
6. `default_value` 统一按字符串存；Array/Object 使用 JSON 字符串，例如 `["a","b"]`、`{"taskName":"","searchType":1}`。
7. Body 参数的 `mapped_name` 会作为 JSON Body 的 key；同名 key 会被后者覆盖，保存时校验同方法下 Body 参数 `mapped_name` 唯一。

### 4.5 Agent 引用与缓存失效

HTTP 插件引用直接保存在 Agent 的 `pluginConfig.httpPlugins` 中，不建立 `ai_plugin_http_agent_ref`。V1 中插件、方法或参数变更后，事务提交成功再清理标准 Agent 的运行时缓存，下次请求按 Agent 配置 JSON 重新装配工具。

### 4.6 调用日志表 `ai_plugin_http_call_log`（P1 可选）

```sql
CREATE TABLE IF NOT EXISTS `ai_plugin_http_call_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `request_id`      VARCHAR(64)           DEFAULT NULL COMMENT '单次工具调用ID',
    `aid`             VARCHAR(64)           DEFAULT NULL COMMENT 'Agent唯一标识',
    `session_id`      VARCHAR(64)           DEFAULT NULL COMMENT '会话ID',
    `plugin_id`       BIGINT       NOT NULL COMMENT '插件ID',
    `method_id`       BIGINT       NOT NULL COMMENT '方法ID',
    `tool_name`       VARCHAR(128) NOT NULL COMMENT '实际注册的工具名',
    `http_method`     VARCHAR(16)  NOT NULL COMMENT 'GET/POST/PUT/DELETE/PATCH',
    `request_url`     VARCHAR(2048)         DEFAULT NULL COMMENT '实际请求URL，Query值需脱敏',
    `request_headers` TEXT                  DEFAULT NULL COMMENT '请求头JSON，敏感值脱敏',
    `request_body`    MEDIUMTEXT            DEFAULT NULL COMMENT '请求体，超长截断',
    `response_status` INT                   DEFAULT NULL COMMENT 'HTTP状态码',
    `response_body`   MEDIUMTEXT            DEFAULT NULL COMMENT '响应体，超长截断',
    `success`         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否成功：0否 1是',
    `error_message`   VARCHAR(2048)         DEFAULT NULL COMMENT '错误信息',
    `duration_ms`     BIGINT                DEFAULT NULL COMMENT '耗时毫秒',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HTTP插件工具调用日志表';
```

说明：

- 现有 `HttpToolExecutor` 只返回响应体字符串，不返回状态码、Header，无法在不改框架的情况下完整落库；
- 建议 P1 通过包装 `ToolExecutor` 记录「工具名、参数、结果、耗时、异常」；
- 状态码、请求 URL、响应体等字段需要在框架层增加执行结果元数据或监听器；
- 日志保留期建议 7~30 天，按 `create_time` 定期清理。

### 4.7 主键与唯一性策略

V1 建表语句每张表只保留 `id` 主键，不建二级索引和数据库唯一键，保持 DDL 最简。

唯一性统一由 Service 层在事务内校验：

| 约束 | 校验范围 |
| --- | --- |
| 插件别名唯一 | 同一 `create_user_id` 下 `is_deleted = 0` 的插件 |
| 方法名唯一 | 同一 `plugin_id` 下 `is_deleted = 0` 的方法 |
| 参数名唯一 | 同一 `method_id` 下 `is_deleted = 0` 的参数 |
| Agent 引用唯一 | 同一 `aid + plugin_id + ref_scope` 的引用记录 |

后续数据量上来后，再根据实际查询和慢日志通过独立 `ALTER TABLE` 补充索引，不放入首版建表语句。

### 4.8 SQL 文件放置

与现有 SQL 规范保持一致，`increment` 与 `local` 两份保持一致：

```text
agentforge-service/sql/increment/1.0.x/2026xxxxxxxxxx_v1.0.x.sql
agentforge-service/sql/local/1.0.x/2026xxxxxxxxxx_v1.0.x.sql
```

脚本头部沿用现有风格：

```sql
USE `agentforge`;
```

### 4.9 数据示例

插件：

```sql
INSERT INTO `ai_plugin_http`
    (`name`, `alias`, `base_url`, `static_headers`, `description`, `status`,
     `is_public`, `create_user_id`, `is_deleted`)
VALUES
    ('GitHub API', 'github', 'https://api.github.com',
     '{"Accept":"application/vnd.github+json","Authorization":"Bearer ghp_xxx"}',
     '用于查询 GitHub 仓库与 Issue', 1, 0, 1, 0);
```

方法：

```sql
INSERT INTO `ai_plugin_http_method`
    (`plugin_id`, `method_name`, `display_name`, `method_description`,
     `http_method_type`, `uri`, `status`, `sort_order`, `is_deleted`)
VALUES
    (1, 'get_user_repos', '获取用户仓库', '获取指定 GitHub 用户的仓库列表',
     1, '/users/{username}/repos', 1, 0, 0);
```

参数：

```sql
-- Path 参数
INSERT INTO `ai_plugin_http_parameter`
    (`method_id`, `method_param_name`, `method_param_description`, `mapped_name`,
     `use_type_value`, `data_type_value`, `default_value`, `required`, `sort_order`, `is_deleted`)
VALUES
    (1, 'username', 'GitHub 用户名', 'username', 3, 1, NULL, 1, 0, 0);

-- Query 参数
INSERT INTO `ai_plugin_http_parameter`
    (`method_id`, `method_param_name`, `method_param_description`, `mapped_name`,
     `use_type_value`, `data_type_value`, `default_value`, `required`, `sort_order`, `is_deleted`)
VALUES
    (1, 'page', '页码，从1开始', 'page', 1, 2, '1', 0, 1, 0);

-- Body 参数（嵌套对象）
INSERT INTO `ai_plugin_http_parameter`
    (`method_id`, `method_param_name`, `method_param_description`, `mapped_name`,
     `use_type_value`, `data_type_value`, `default_value`, `required`, `sort_order`, `is_deleted`)
VALUES
    (2, 'searchCondition', '搜索条件对象', 'searchCondition', 2, 6,
     '{"taskName":"","searchType":1}', 0, 0, 0);
```

### 4.10 表到框架模型的映射

| 表字段 | 框架模型 | 说明 |
| --- | --- | --- |
| `ai_plugin_http.base_url` | `HttpPlugin.baseUrl` | 末尾 `/` 统一处理 |
| `ai_plugin_http.static_headers` | `HttpPlugin.staticHeaders` | JSON 解析为 `Map<String,String>` |
| `ai_plugin_http_method.method_name` | `HttpPluginMethod.methodName` | 需要加 `{alias}__` 前缀 |
| `ai_plugin_http_method.method_description` | `HttpPluginMethod.methodDescription` | 为空时回退 `display_name` |
| `ai_plugin_http_method.http_method_type` | `HttpPluginMethod.httpMethodType` | 直接透传 |
| `ai_plugin_http_method.uri` | `HttpPluginMethod.uri` | 直接透传 |
| `ai_plugin_http_parameter.method_param_name` | `HttpToolParameter.methodParamName` | 模型参数名 |
| `ai_plugin_http_parameter.method_param_description` | `HttpToolParameter.methodParamDescription` | JSON Schema description |
| `ai_plugin_http_parameter.mapped_name` | `HttpToolParameter.mappedName` | 真实 API 参数名 |
| `ai_plugin_http_parameter.use_type_value` | `HttpToolParameter.useTypeValue` | 直接透传 |
| `ai_plugin_http_parameter.data_type_value` | `HttpToolParameter.dataTypeValue` | 直接透传 |
| `ai_plugin_http_parameter.default_value` | `HttpToolParameter.defaultValue` | 字符串原样传递，执行期转换 |
| `ai_plugin_http_parameter.required` | `HttpToolParameter.required` | 0/1 |

---

## 5. Agent 配置扩展

### 5.1 `agent_config` JSON 结构

在现有 `StandardAgentConfig` 上新增统一的 `pluginConfig` 技能配置：

```json
{
  "systemPrompt": "你是一名专业的 AI 助手。",
  "chatModel": {
    "llm_model_id": 1,
    "maxOutputToken": 4096
  },
  "chatMemoryConfig": {
    "type": 1,
    "memoryConfig": "{\"maxMessage\":20}"
  },
  "pluginConfig": {
    "httpPlugins": [
      { "pluginId": 1001, "pluginMethodIds": [11, 12] },
      { "pluginId": 1002, "pluginMethodIds": [] }
    ],
    "mcpPlugins": [
      { "pluginId": 2001, "serverIds": [101] }
    ]
  }
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `httpPlugins` | HTTP 插件选择列表 |
| `pluginId` | HTTP/MCP 插件 ID |
| `pluginMethodIds` | HTTP 方法 ID；空数组或 `null` 表示全部启用方法 |
| `mcpPlugins` | MCP 插件选择列表 |
| `serverIds` | MCP Server ID 列表 |

兼容性：

- `pluginConfig` 为 `null` 时只装配默认本地工具；
- 初版不考虑历史字段兼容，不再使用顶层 `httpPlugins` / `mcpPlugins`；
- `agent_config` 整体仍写入 Agent 草稿、版本和发布快照表。

### 5.2 Java 模型调整

新增：

```java
package cloud.changlu.agentforge.service.modules.agent.domain.config;

import java.util.List;

public class HttpToolConfig {
    private Long pluginId;
    private Long[] pluginMethodIds;
}

public class PluginConfig {
    private List<HttpToolConfig> httpPlugins;
    private List<McpToolConfig> mcpPlugins;
}
```

`StandardAgentConfig` 增加：

```java
private PluginConfig pluginConfig;
```

`AgentConfigUpsertReq` / `AgentConfigVo` 增加同名 `pluginConfig` 字段，供前端编辑与回显。

### 5.3 工具名校验

最终工具名：

```text
exposedName = plugin.alias + "__" + method.method_name
```

校验规则：

1. `alias` 建议 `^[A-Za-z][A-Za-z0-9_]{1,15}$`；
2. `method_name` 建议 `^[A-Za-z][A-Za-z0-9_]{0,39}$`；
3. `exposedName` 总长度不超过 64，兼容主流模型 Tool Calling 限制；
4. 同一个 Agent 内所有来源（local/http/mcp）的最终工具名必须唯一；
5. 保存 Agent 时做一次完整校验，运行期再做一次兜底校验，冲突时跳过并记录日志。

### 5.4 发布语义

AgentForge 的发布快照保存的是 `agent_config` 的完整 JSON，因此：

- 发布时会把 `pluginConfig` 一起写入 `ai_agent_config_published`；
- 但 `pluginConfig` 只保存引用，不保存插件定义；
- 插件方法后续被修改，已发布 Agent 下次重新构建时会加载新定义；
- 如果业务需要「发布后冻结插件定义」，应复制一个新插件、修改 Agent 引用后重新发布，而不是修改原插件。

这是 V1 明确的取舍，与 MCP Server 的「工具清单不落库、运行时发现」保持一致。

---

## 6. 模块与代码结构

### 6.1 包结构

```text
cloud.changlu.agentforge.service.modules.plugin.http
├── controller
│   ├── HttpPluginController.java
│   └── HttpPluginMethodController.java
├── dao
│   ├── HttpPluginMapper.java
│   ├── HttpPluginMethodMapper.java
│   ├── HttpPluginParameterMapper.java
├── domain
│   ├── HttpPluginPojo.java
│   ├── HttpPluginMethodPojo.java
│   ├── HttpPluginParameterPojo.java
│   ├── req
│   │   ├── HttpPluginQueryReq.java
│   │   ├── HttpPluginUpsertReq.java
│   │   ├── HttpPluginMethodUpsertReq.java
│   │   ├── HttpPluginParameterReq.java
│   │   ├── HttpPluginTestRunReq.java
│   │   └── HttpPluginCurlImportReq.java
│   └── vo
│       ├── HttpPluginVo.java
│       ├── HttpPluginDetailVo.java
│       ├── HttpPluginMethodVo.java
│       ├── HttpPluginMethodDetailVo.java
│       ├── HttpPluginOptionVo.java
│       └── HttpPluginTestRunVo.java
├── service
│   ├── IHttpPluginService.java
│   ├── IHttpPluginMethodService.java
│   ├── HttpPluginRuntimeService.java
│   ├── HttpPluginAgentCacheService.java
│   └── impl
│       ├── HttpPluginServiceImpl.java
│       ├── HttpPluginMethodServiceImpl.java
│       └── HttpPluginCurlServiceImpl.java
├── support
│   ├── HttpPluginConverter.java
│   ├── HttpPluginValidator.java
│   └── SecretCipher.java
└── mapper
    ├── HttpPluginMapper.xml
    ├── HttpPluginMethodMapper.xml
    ├── HttpPluginParameterMapper.xml
```

> MyBatis XML 放在 `src/main/resources/cloud/changlu/agentforge/service/modules/plugin/http/mapper/` 下，现有配置 `classpath*:cloud/changlu/agentforge/service/modules/**/mapper/*.xml` 可以直接扫描到。

### 6.2 类职责

| 类 | 职责 |
| --- | --- |
| `HttpPluginController` | 插件分页、详情、新增/修改、删除、启停、curl 导入预览/保存、Agent 选择项 |
| `HttpPluginMethodController` | 方法列表、详情、新增/修改、删除、启停、试运行 |
| `HttpPluginServiceImpl` | 插件 CRUD、别名唯一校验、删除级联、引用失效 |
| `HttpPluginMethodServiceImpl` | 方法 CRUD、参数整体替换、方法/参数校验 |
| `HttpPluginCurlServiceImpl` | 调用 `CurlParser` / `CurlToHttpPluginConverter`，完成预览与保存 |
| `HttpPluginRuntimeService` | 根据 `AgentToolRef` 加载插件、方法、参数，构建 `HttpPlugin` 并调用 `HttpToolFactory` |
| `HttpPluginAgentCacheService` | 插件变更事务提交后失效标准 Agent 运行时缓存 |
| `HttpPluginConverter` | PO -> 框架 `HttpPlugin` / `HttpPluginMethod` / `HttpToolParameter` |
| `HttpPluginValidator` | 别名、方法名、URI 占位符、参数位置/类型、Header 合法性校验 |
| `SecretCipher` | 静态 Header 加密/解密（P1），V1 可先留接口 |

### 6.3 转换器核心代码

```java
@Component
public class HttpPluginConverter {

    public HttpPlugin toHttpPlugin(
            HttpPluginPojo plugin,
            List<HttpPluginMethodPojo> methods,
            Map<Long, List<HttpPluginParameterPojo>> paramsByMethodId,
            String aliasOverride) {

        String prefix = StringUtils.hasText(aliasOverride)
                ? aliasOverride.trim()
                : plugin.getAlias();

        List<HttpPluginMethod> pluginMethods = new ArrayList<HttpPluginMethod>();
        for (HttpPluginMethodPojo method : methods) {
            List<HttpPluginParameterPojo> params =
                    paramsByMethodId.getOrDefault(method.getId(), Collections.emptyList());

            List<HttpToolParameter> toolParams = new ArrayList<HttpToolParameter>();
            for (HttpPluginParameterPojo param : params) {
                toolParams.add(toToolParameter(param));
            }

            pluginMethods.add(
                    HttpPluginMethod.builder()
                            .methodName(prefix + "__" + method.getMethodName())
                            .methodDescription(resolveDescription(method))
                            .httpMethodType(method.getHttpMethodType())
                            .uri(method.getUri())
                            .parameters(toolParams)
                            .build());
        }

        return HttpPlugin.builder()
                .baseUrl(normalizeBaseUrl(plugin.getBaseUrl()))
                .staticHeaders(parseHeaders(plugin.getStaticHeaders()))
                .pluginMethods(pluginMethods)
                .build();
    }

    private HttpToolParameter toToolParameter(HttpPluginParameterPojo param) {
        return HttpToolParameter.builder()
                .methodParamName(param.getMethodParamName())
                .methodParamDescription(param.getMethodParamDescription())
                .mappedName(param.getMappedName())
                .useTypeValue(param.getUseTypeValue())
                .dataTypeValue(param.getDataTypeValue())
                .defaultValue(param.getDefaultValue())
                .required(param.getRequired())
                .build();
    }

    private String resolveDescription(HttpPluginMethodPojo method) {
        if (StringUtils.hasText(method.getMethodDescription())) {
            return method.getMethodDescription();
        }
        return StringUtils.hasText(method.getDisplayName())
                ? method.getDisplayName()
                : method.getMethodName();
    }
}
```

### 6.4 运行时装配代码

```java
@Service
public class HttpPluginRuntimeService {

    private final HttpPluginMapper httpPluginMapper;
    private final HttpPluginMethodMapper httpPluginMethodMapper;
    private final HttpPluginParameterMapper httpPluginParameterMapper;
    private final HttpPluginConverter httpPluginConverter;

    public Map<ToolSpecification, ToolExecutor> buildHttpTools(List<AgentToolRef> toolRefs) {
        Map<ToolSpecification, ToolExecutor> result =
                new LinkedHashMap<ToolSpecification, ToolExecutor>();
        Set<String> registeredNames = new HashSet<String>();
        if (toolRefs == null || toolRefs.isEmpty()) {
            return result;
        }

        for (AgentToolRef ref : toolRefs) {
            if (!AgentToolRef.TYPE_HTTP.equals(ref.getType()) || ref.getPluginId() == null) {
                continue;
            }
            try {
                Map<ToolSpecification, ToolExecutor> tools =
                        buildOnePlugin(ref);
                for (Map.Entry<ToolSpecification, ToolExecutor> entry : tools.entrySet()) {
                    String toolName = entry.getKey().name();
                    if (registeredNames.contains(toolName)) {
                        log.warn("HTTP工具名冲突，已跳过: " + toolName);
                        continue;
                    }
                    registeredNames.add(toolName);
                    result.put(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                // 单个插件失败不影响整个 Agent
                log.error("加载HTTP插件失败, pluginId=" + ref.getPluginId(), e);
            }
        }
        return result;
    }

    private Map<ToolSpecification, ToolExecutor> buildOnePlugin(AgentToolRef ref) {
        HttpPluginPojo plugin = httpPluginMapper.selectById(ref.getPluginId());
        if (plugin == null || plugin.getStatus() == null || plugin.getStatus() != 1) {
            return Collections.emptyMap();
        }

        List<HttpPluginMethodPojo> methods =
                resolveMethods(ref.getPluginId(), ref.getMethodIds());
        if (methods.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, List<HttpPluginParameterPojo>> paramsByMethodId =
                loadParams(methods);

        HttpPlugin httpPlugin =
                httpPluginConverter.toHttpPlugin(
                        plugin, methods, paramsByMethodId, ref.getAlias());
        return HttpToolFactory.buildHttpTools(httpPlugin);
    }
}
```

### 6.5 接入 `ReActAgentFactory`

```java
private ToolService buildToolService(StandardAgentConfig agentConfig) {
    ToolService toolService = new ToolService();

    // 1. 本地工具
    toolService.tools(
            LocalToolFactory.buildLocalToolsWithThought(
                    Collections.<Object>singletonList(openReachTools), true));

    // 2. HTTP 插件工具
    if (agentConfig != null && agentConfig.getTools() != null) {
        toolService.tools(httpPluginRuntimeService.buildHttpTools(agentConfig.getTools()));
    }

    return toolService;
}
```

对应修改：

```java
.toolService(buildToolService(standardAgentConfig));
```

### 6.6 缓存失效

现有 `AbstractAgentManager` 用内存 Map 缓存 Agent 实例：

```text
agents: aid/debug + channelId -> ReActAgent
```

因此插件变更后必须清理 Agent 运行时缓存。V1 不维护引用关系表，直接清理标准 Agent 缓存：

```java
@Service
public class HttpPluginAgentCacheService {

    public void evictAllAfterCommit() {
        afterCommit(() -> {
            IAgentManager<Object, Object> manager =
                    agentManagerFactory.getAgentManagerByType(STANDARD_AGENT_TYPE);
            manager.clearAgents();
        });
    }
}
```

触发点：

- 插件更新/删除/启停；
- 方法更新/删除/启停；
- 参数整体替换；
- curl 导入覆盖已有方法。

注意：

- `clearAgents()` 会同时清理调试态和发布态；
- 必须在事务提交后执行，避免回滚后实例已被清理；
- 缓存清理只影响内存中的 Agent 实例与配置快照，不修改 Agent 配置和会话数据。

### 6.7 事务边界

| 操作 | 事务内容 |
| --- | --- |
| 新增/修改插件 | 写插件表 + 校验别名 |
| 删除插件 | 查询引用并失效 Agent + 逻辑删除插件 + 逻辑删除方法 + 逻辑删除参数 + 清理引用关系 |
| 新增/修改方法 | 写方法表 + 整体替换参数 |
| 删除方法 | 逻辑删除方法 + 逻辑删除参数 + 清理引用 |
| curl 导入 | 写插件 + 写方法 + 写参数（同一事务） |
| Agent 保存 | 写 Agent 配置 + 重建引用关系 |
| Agent 发布 | 写发布快照 + 重建发布引用 |

---

## 7. API 设计

统一前缀：`/api/http-plugin`、`/api/http-plugin-method`。

### 7.1 插件管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/http-plugin/page` | 分页查询插件 |
| GET | `/api/http-plugin/{id}` | 插件详情 |
| POST | `/api/http-plugin/upsert` | 新增/修改插件 |
| DELETE | `/api/http-plugin/{id}` | 删除插件（逻辑删除） |
| PATCH | `/api/http-plugin/{id}/status` | 启用/禁用插件 |
| POST | `/api/http-plugin/curl/preview` | 解析 curl，返回预览结构，不落库 |
| POST | `/api/http-plugin/curl/import` | 解析并保存 curl 为插件/方法 |
| GET | `/api/http-plugin/options` | 返回 Agent 编辑器可选的插件与方法 |

分页请求：

```text
GET /api/http-plugin/page?keyword=github&status=1&page=1&pageSize=12
```

分页响应沿用 `PageResult`：

```json
{
  "currentPage": 1,
  "pageSize": 12,
  "totalCount": 1,
  "totalPage": 1,
  "data": [
    {
      "id": 1001,
      "name": "GitHub API",
      "alias": "github",
      "baseUrl": "https://api.github.com",
      "description": "用于查询 GitHub 仓库与 Issue",
      "status": 1,
      "methodCount": 3,
      "createTime": "2026-10-27 18:00:00",
      "modifyTime": "2026-10-27 18:00:00"
    }
  ]
}
```

新增/修改请求：

```json
{
  "id": null,
  "name": "GitHub API",
  "alias": "github",
  "baseUrl": "https://api.github.com",
  "staticHeaders": {
    "Accept": "application/vnd.github+json",
    "Authorization": "Bearer ghp_xxx"
  },
  "description": "用于查询 GitHub 仓库与 Issue",
  "icon": "",
  "status": 1
}
```

详情响应中的敏感 Header 处理：

```json
{
  "id": 1001,
  "name": "GitHub API",
  "alias": "github",
  "baseUrl": "https://api.github.com",
  "staticHeaders": {
    "Accept": "application/vnd.github+json",
    "Authorization": "******"
  },
  "sensitiveHeaderKeys": ["Authorization"],
  "description": "用于查询 GitHub 仓库与 Issue",
  "status": 1
}
```

更新规则：

- 前端提交的 Header 值为 `******` 时，表示不修改原值；
- 提交新值则覆盖；
- 删除 Header 通过不提交该 key 实现；
- 后端不得把明文密钥返回给前端。

### 7.2 方法管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/http-plugin/{pluginId}/methods` | 查询插件下的方法列表 |
| GET | `/api/http-plugin-method/{id}` | 方法详情（含参数） |
| POST | `/api/http-plugin-method/upsert` | 新增/修改方法，参数整体替换 |
| DELETE | `/api/http-plugin-method/{id}` | 删除方法 |
| PATCH | `/api/http-plugin-method/{id}/status` | 启用/禁用方法 |
| POST | `/api/http-plugin-method/{id}/test-run` | 在线试运行 |

新增/修改方法请求：

```json
{
  "id": null,
  "pluginId": 1001,
  "methodName": "get_user_repos",
  "displayName": "获取用户仓库",
  "methodDescription": "获取指定 GitHub 用户的仓库列表",
  "httpMethodType": 1,
  "uri": "/users/{username}/repos",
  "status": 1,
  "sortOrder": 0,
  "parameters": [
    {
      "methodParamName": "username",
      "methodParamDescription": "GitHub 用户名",
      "mappedName": "username",
      "useTypeValue": 3,
      "dataTypeValue": 1,
      "defaultValue": null,
      "required": 1,
      "sortOrder": 0
    },
    {
      "methodParamName": "page",
      "methodParamDescription": "页码，从1开始",
      "mappedName": "page",
      "useTypeValue": 1,
      "dataTypeValue": 2,
      "defaultValue": "1",
      "required": 0,
      "sortOrder": 1
    }
  ]
}
```

参数整体替换逻辑：

1. 校验方法归属、方法名唯一、URI 占位符、参数类型/位置；
2. 逻辑删除该 `method_id` 下旧参数；
3. 批量插入新参数；
4. 事务提交后失效引用该方法的 Agent。

### 7.3 在线试运行

请求：

```text
POST /api/http-plugin-method/{id}/test-run
```

```json
{
  "arguments": {
    "username": "octocat",
    "page": 1
  }
}
```

响应：

```json
{
  "success": true,
  "toolName": "github__get_user_repos",
  "durationMs": 320,
  "result": "[{\"id\":1,\"name\":\"Hello-World\"}]"
}
```

实现方式：

```java
HttpPlugin httpPlugin = converter.toHttpPlugin(plugin, singletonList(method), params, null);
Map<ToolSpecification, ToolExecutor> tools = HttpToolFactory.buildHttpTools(httpPlugin);
ToolExecutor executor = tools.values().iterator().next();
String result = executor.execute(
        ToolExecutionRequest.from("test-run", toolName, toJson(arguments)),
        "http-plugin-test");
```

注意：

- V1 只能返回响应体字符串，状态码需要框架扩展；
- 试运行使用与运行期完全相同的 `HttpToolExecutor`，避免「测试通过、运行失败」；
- 试运行请求也要做敏感信息脱敏和日志限制。

### 7.4 curl 导入

预览请求：

```json
{
  "curl": "curl -X POST 'https://api.example.com/search?page=1' -H 'Content-Type: application/json' -d '{\"keyword\":\"agent\",\"filter\":{\"type\":1}}'"
}
```

预览响应：

```json
{
  "baseUrl": "https://api.example.com",
  "uri": "/search",
  "methodName": "search",
  "httpMethodType": 2,
  "staticHeaders": {
    "Content-Type": "application/json"
  },
  "parameters": [
    {
      "methodParamName": "page",
      "mappedName": "page",
      "useTypeValue": 1,
      "dataTypeValue": 2,
      "required": 0
    },
    {
      "methodParamName": "keyword",
      "mappedName": "keyword",
      "useTypeValue": 2,
      "dataTypeValue": 1,
      "required": 0
    },
    {
      "methodParamName": "filter",
      "mappedName": "filter",
      "useTypeValue": 2,
      "dataTypeValue": 6,
      "required": 0
    }
  ]
}
```

保存请求：

```json
{
  "pluginId": 1001,
  "curl": "curl -X POST ...",
  "methodName": "search",
  "displayName": "搜索",
  "methodDescription": "按关键词搜索"
}
```

说明：

- 解析复用框架 `CurlParser` 与 `CurlToHttpPluginConverter`；
- 预览阶段允许用户修改方法名、描述、参数；
- 保存阶段重新解析或使用预览结构，避免前后端结构不一致；
- 导入时如果插件已存在，默认新增方法；覆盖已有方法需要显式参数控制。

### 7.5 Agent 编辑器选择项

```text
GET /api/http-plugin/options
```

响应：

```json
[
  {
    "pluginId": 1001,
    "pluginName": "GitHub API",
    "alias": "github",
    "status": 1,
    "methods": [
      {
        "methodId": 11,
        "methodName": "get_user_repos",
        "displayName": "获取用户仓库",
        "description": "获取指定 GitHub 用户的仓库列表",
        "toolName": "github__get_user_repos",
        "status": 1
      }
    ]
  }
]
```

该接口只返回启用插件下的启用方法，供前端生成 `AgentToolRef`。

### 7.6 错误响应

沿用现有 `GlobalExceptionHandler`：

```json
{
  "code": 400,
  "message": "同一用户下插件别名已存在: github"
}
```

建议错误场景：

| 场景 | 错误信息 |
| --- | --- |
| 别名重复 | `同一用户下插件别名已存在: github` |
| 方法名重复 | `同一插件下方法名已存在: get_user_repos` |
| URI 缺少 Path 占位符 | `Path参数 username 在URI中缺少占位符 {username}` |
| GET 配置 Body 参数 | `GET/DELETE 方法不允许配置 Body 参数` |
| Query 配置 Array/Object | `Query/Header 参数只支持基础类型` |
| 删除被引用插件 | 默认允许删除，但提示「删除后引用该插件的 Agent 将跳过该工具」；也可配置为强校验 |

---

## 8. 运行时调用链

### 8.1 完整链路

```text
用户发起对话
    │
    ▼
AgentChatController.chat
    │
    ▼
AgentRuntimeConfigResolver.resolve(aid, isDebug)
    │  读取 ai_agent_config / ai_agent_config_published
    ▼
AgentManager.getAgentAndInitAgent
    │
    ▼
ReActAgentFactory.createReActAgent(StandardAgentConfig)
    │
    ├── 构建 ChatModel / ChatMemory
    │
    └── buildToolService(agentConfig)
            │
            ├── LocalToolFactory -> 本地工具
            │
            └── HttpPluginRuntimeService.buildHttpTools(tools)
                    │
                    ├── 遍历 pluginConfig.httpPlugins
                    ├── 读取 ai_plugin_http
                    ├── 读取 ai_plugin_http_method
                    ├── 读取 ai_plugin_http_parameter
                    ├── PO -> HttpPlugin
                    ├── HttpToolFactory.buildHttpTools
                    └── 合并到 ToolService
    │
    ▼
ReActAgent.run / runStream
    │
    ▼
模型返回 ToolExecutionRequest
    │
    ▼
ToolService 按工具名找到 HttpToolExecutor
    │
    ▼
HttpToolExecutor 组装 URL / Header / Body 并发起 HTTP 请求
    │
    ▼
返回响应文本给模型
```

### 8.2 加载顺序与冲突处理

建议顺序：

1. 本地工具（`OpenReachTools` 等）；
2. HTTP 插件工具；
3. MCP 工具（后续）。

冲突处理：

- 保存 Agent 时做静态校验，尽量在配置阶段发现；
- 运行期如果仍冲突，保留先注册的工具，跳过后者并记录 `WARN`；
- 不允许因为工具名冲突导致整个 Agent 构建失败。

### 8.3 单个插件失败隔离

```text
加载插件 A
  ├── 插件不存在 / 已删除 / 已禁用 -> 跳过
  ├── 方法列表为空 -> 跳过
  ├── 参数配置非法 -> 跳过该参数或该方法
  ├── HttpPlugin 构建失败 -> 跳过该插件
  └── 注册成功 -> 继续下一个
```

原则：**插件级失败不阻断 Agent 级构建**。

### 8.4 参数转换与执行细节

现有 `HttpToolExecutor` 的行为：

| 场景 | 行为 |
| --- | --- |
| Path 参数 | 用 `mappedName` 替换 URI 中的 `{mappedName}` |
| Query 参数 | 追加到 URL，key 使用 `mappedName` |
| Header 参数 | 写入请求头，覆盖同名静态 Header |
| Body 参数 | POST/PUT/PATCH 且存在 Body 参数时，组装为 JSON |
| 默认值 | 模型未传时使用 `defaultValue` |
| 必填校验 | 缺失且无默认值时抛异常 |
| 类型转换 | String/Integer/Number/Boolean/Array/Object |
| GET/DELETE | 强制不使用 Body |
| 重试 | GET/DELETE 遇到 connection reset 重试一次 |

已知限制（需要在接入前修复或规避）：

1. **有 Body 时 Query 参数丢失**：当前 `HttpToolExecutor` 在 `shouldUseJsonBody` 为 true 时不会调用 `appendQueryParameters`。如果 POST 同时有 Query 和 Body 参数，Query 不会拼接到 URL。
2. **Path 参数未编码**：直接字符串替换，特殊字符可能破坏 URL。
3. **Query 的 Array/Object 被 `String.valueOf` 处理**：不支持重复 key 或 JSON 编码。
4. **超时硬编码**：连接 10 分钟、读取 15 分钟，无法按插件配置。
5. **不校验 HTTP 状态码**：4xx/5xx 也按成功返回响应体。
6. **日志直接打印请求头**：可能泄漏 `Authorization`、`Cookie`、`X-API-Key`。

这些问题的处理方案见第 10 章。

---

## 9. 安全与权限设计

### 9.1 SSRF 防护

HTTP 插件允许用户填写任意 `base_url`，服务端会发起请求，存在 SSRF 风险。

V1 最低要求：

1. 只允许 `http` / `https`；
2. 禁止 `file://`、`gopher://`、`ftp://` 等协议；
3. 默认禁止访问 localhost、127.0.0.0/8、10.0.0.0/8、172.16.0.0/12、192.168.0.0/16、169.254.0.0/16、IPv6 回环/链路本地地址；
4. 如果业务确实需要访问内网，通过配置白名单显式放开；
5. 保存时校验一次，运行前建议再做一次 DNS 解析后的 IP 校验，防止 DNS Rebinding。

建议配置：

```yaml
agentforge:
  http-plugin:
    allow-private-network: false
    allowed-hosts: []
    connect-timeout-ms: 10000
    read-timeout-ms: 60000
    max-response-bytes: 1048576
```

### 9.2 敏感 Header 处理

V1：

- `static_headers` 存储为 JSON 文本；
- 查询详情接口返回 `******`，并返回 `sensitiveHeaderKeys`；
- 更新时 `******` 表示保留原值；
- 框架日志必须屏蔽 `Authorization`、`Cookie`、`Set-Cookie`、`X-API-Key`、`X-Auth-Token`、`Proxy-Authorization` 等敏感头；
- 试运行和调用日志中的请求头同样脱敏。

P1：

- 引入 `SecretCipher`，使用 AES/GCM 加密 `static_headers` 中的敏感值；
- 密钥来自环境变量，不写入代码和数据库；
- 数据格式建议：`enc:v1:{base64}`；
- 读取时解密，写入时加密，前端永远只看到掩码。

### 9.3 Header 注入防护

- Header 参数值禁止包含 `\r`、`\n`；
- Header 参数名限制为合法 token；
- 禁止覆盖 `Host`、`Content-Length`、`Transfer-Encoding`、`Connection` 等由客户端管理的头；
- `static_headers` 与动态 Header 合并时，动态参数优先，但保留上述禁用名单。

### 9.4 响应体限制

- 设置最大响应字节数，避免模型上下文被超大响应撑爆；
- 超限时截断并追加 `...[truncated]`；
- 建议默认 1 MB，可按插件配置；
- 二进制响应（图片、文件）不建议直接返回给模型，V1 直接拒绝或只返回元信息。

### 9.5 权限与数据隔离

- 所有查询/修改都带 `create_user_id = 当前用户`；
- V1 默认用户 ID 为 1，与现有 Agent/Model 模块保持一致；
- `is_public = 1` 仅表示可展示，不自动允许其他用户引用；
- 删除、修改方法前校验方法所属插件归属；
- Agent 引用插件时校验插件归属，避免引用他人私有插件。

### 9.6 审计

- 插件/方法的创建、修改、删除记录操作人和时间；
- 试运行记录调用日志（P1）；
- 敏感字段变更单独审计（P1）。

---

## 10. 框架层配套改造

HTTP 插件能否稳定落地，很大程度取决于框架层 `HttpToolExecutor` 的几个细节。建议在 Service 接入前完成以下改造。

### 10.1 必改项

| 编号 | 问题 | 改造建议 | 优先级 |
| --- | --- | --- | --- |
| F1 | POST 同时有 Query 和 Body 时 Query 丢失 | 先 `appendQueryParameters`，再根据是否有 Body 参数构建 JSON Body | P0 |
| F2 | Path 参数未 URL 编码 | 对 Path 参数值按 path segment 编码，避免 `/`、空格、中文破坏 URL | P0 |
| F3 | 请求头日志泄漏密钥 | 日志输出前对敏感 Header 做掩码 | P0 |
| F4 | 超时不可配置 | 增加 `HttpToolExecutor.Builder` 或构造参数，支持 connect/read timeout | P1 |
| F5 | 不校验 HTTP 状态码 | 4xx/5xx 返回 `ToolExecutionResult.isError = true` 或明确标记失败 | P1 |
| F6 | 响应体无上限 | 增加 `maxResponseBytes`，超限截断 | P1 |
| F7 | 无结构化调用元数据 | `ToolExecutionResult` 增加状态码、URL、耗时等 metadata，或增加执行监听器 | P2 |

### 10.2 F1 示例

当前逻辑：

```java
String requestBody = "";
if (shouldUseJsonBody(finalArguments)) {
    // 只构建 Body
} else {
    url = appendQueryParameters(url, finalArguments);
}
```

建议逻辑：

```java
url = appendQueryParameters(url, finalArguments);

String requestBody = "";
if (shouldUseJsonBody(finalArguments)) {
    requestBody = buildJsonBody(finalArguments);
    headers.put("Content-Type", "application/json");
}
```

### 10.3 F2 示例

```java
private String encodePathSegment(String value) {
    // 使用 URI 编码，保留 path segment 语义
    return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("%2F", "%252F");
}
```

> 具体编码实现需要结合 `HttpRequest` 的 URL 处理方式，核心目标是：Path 参数不会改变 URL 层级结构。

### 10.4 F3 示例

```java
private static final Set<String> SENSITIVE_HEADERS = new HashSet<String>(Arrays.asList(
        "authorization", "cookie", "set-cookie", "x-api-key",
        "x-auth-token", "proxy-authorization"));

private Map<String, String> maskHeaders(Map<String, String> headers) {
    Map<String, String> masked = new LinkedHashMap<String, String>();
    for (Map.Entry<String, String> entry : headers.entrySet()) {
        if (SENSITIVE_HEADERS.contains(entry.getKey().toLowerCase())) {
            masked.put(entry.getKey(), "******");
        } else {
            masked.put(entry.getKey(), entry.getValue());
        }
    }
    return masked;
}
```

### 10.5 改造后的收益

- Service 层不需要绕过框架重复实现 HTTP 调用；
- 试运行与正式运行行为一致；
- 日志、超时、响应大小、状态码处理统一收口；
- 后续 MCP、本地工具也能复用统一的错误/日志模型。

---

## 11. 实施计划与验证

### 11.1 阶段划分

| 阶段 | 内容 | 产出 |
| --- | --- | --- |
| P0 | 表结构 + 插件/方法/参数 CRUD + 校验 | 可创建、编辑、删除插件与方法 |
| P0 | Agent 配置扩展 `pluginConfig.httpPlugins` + 运行时装配 | Agent 可调用 HTTP 工具 |
| P0 | curl 导入预览/保存 | 可从 curl 快速创建插件 |
| P0 | 在线试运行 | 配置后可直接验证 |
| P1 | 引用关系 + 缓存失效 | 插件变更后 Agent 立即使用新定义 |
| P1 | 框架 F1/F2/F3/F4/F5/F6 | 稳定性与安全性达标 |
| P1 | 调用日志 | 可排障、可审计 |
| P2 | 静态 Header 加密、导出/导入、插件市场 | 生态与安全增强 |

### 11.2 后端任务清单

1. 新增 SQL 脚本（`increment` + `local`）。
2. 新增 `modules/plugin/http` 包及 Pojo/Mapper/XML。
3. 新增 Controller/Service/Converter/Validator。
4. 扩展 `StandardAgentConfig`、`AgentConfigUpsertReq`、`AgentConfigVo`。
5. 修改 `AgentConfigServiceImpl.buildAgentConfig` 持久化 `tools`。
6. 修改 `ReActAgentFactory.buildToolService` 装配 HTTP 工具。
7. 新增 `HttpPluginAgentCacheService`，插件变更事务提交后清理标准 Agent 缓存。
8. 插件/方法/参数变更后调用缓存失效。
9. 增加 curl 导入接口与试运行接口。
10. 补充框架层 F1/F2/F3 修复及单元测试。

### 11.3 前端任务清单（简述）

| 页面 | 功能 |
| --- | --- |
| 插件列表 | 卡片/表格、搜索、新增、编辑、删除、启停、curl 导入 |
| 插件详情 | 基础信息编辑、方法列表、新增/编辑/删除方法 |
| 方法详情 | URI、Method、描述、参数表格（位置/类型/必填/默认值/映射名） |
| 试运行弹窗 | 按参数生成表单，Array/Object 使用 JSON 编辑器并校验 |
| Agent 编辑器 | 工具选择弹窗，按插件树选择方法，展示最终 toolName |

### 11.4 测试方案

单元测试：

- `HttpPluginConverterTest`：PO -> `HttpPlugin` 字段映射、别名前缀、描述回退；
- `HttpPluginValidatorTest`：别名、方法名、URI 占位符、GET/Body、Query/Array 等校验；
- `HttpPluginRuntimeServiceTest`：禁用插件跳过、单插件失败隔离、工具名冲突跳过；
- `HttpPluginCurlServiceTest`：curl 预览与保存转换。

集成测试：

- 使用 JDK `com.sun.net.httpserver.HttpServer` 启动本地测试服务；
- 覆盖 GET Query、POST JSON Body、Path 参数、Header 参数、默认值、必填缺失、Array/Object Body；
- 覆盖 4xx/5xx、超时、超大响应、敏感 Header 脱敏。

手工验证：

1. 创建一个 GitHub 插件，配置 `get_user_repos` 工具；
2. 在 Agent 编辑器中勾选该工具；
3. 调试对话，模型调用工具并返回仓库列表；
4. 修改工具描述或参数，确认 Agent 使用新定义；
5. 禁用插件，确认 Agent 跳过该工具且不报错；
6. 用 curl 导入一个 POST JSON 接口，试运行成功。

### 11.5 验收标准

- [ ] 插件、方法、参数可完整增删改查；
- [ ] 参数四类位置、六种类型可配置并生效；
- [ ] curl 导入可生成正确的 Method/URI/Header/Query/Body 参数；
- [ ] Agent 可选择插件方法并在对话中调用；
- [ ] 插件变更后 Agent 运行实例被失效；
- [ ] 单个插件异常不影响 Agent 其他工具；
- [ ] 敏感 Header 不出现在接口响应和日志中；
- [ ] GET/DELETE + Body、Query + Array/Object 等非法配置在保存时被拦截；
- [ ] 单元测试与集成测试通过。

### 11.6 风险与应对

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 框架 F1/F2/F3 未修复 | 部分接口行为错误或密钥泄漏 | 作为 P0 前置任务 |
| 工具名冲突 | Agent 构建失败或工具不可用 | 强制别名前缀 + 保存时校验 + 运行期跳过 |
| 插件变更影响已发布 Agent | 线上行为变化 | 明确「实时生效」语义；需要冻结时复制插件 |
| 引用关系与配置不一致 | 缓存未失效 | 保存/发布/切换版本时事务内重建；提供重建脚本 |
| SSRF | 内网探测 | URL/IP 校验 + 白名单 + 超时/响应限制 |
| 大响应撑爆上下文 | 模型调用失败 | 响应大小限制 + 截断 |
| 插件配置复杂 | 用户配置错误 | curl 导入 + 试运行 + 参数校验 |

---

## 12. 附录

### 12.1 枚举常量

```java
// HTTP 方法
public enum HttpMethod {
    GET(1, "GET"),
    POST(2, "POST"),
    PUT(3, "PUT"),
    DELETE(4, "DELETE"),
    PATCH(5, "PATCH");
}

// 参数位置
public enum ParameterUseType {
    QUERY(1, "query"),
    BODY(2, "body"),
    PATH(3, "path"),
    HEADER(4, "header");
}

// 参数类型
public enum ParameterType {
    STRING(1, "string"),
    INTEGER(2, "integer"),
    NUMBER(3, "number"),
    BOOLEAN(4, "boolean"),
    ARRAY(5, "array"),
    OBJECT(6, "object");
}
```

以上枚举直接复用 `cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums`，Service 层不要重复定义，避免两套值不一致。

### 12.2 参数位置与类型组合矩阵

| 位置 | String | Integer | Number | Boolean | Array | Object |
| --- | --- | --- | --- | --- | --- | --- |
| Query | 支持 | 支持 | 支持 | 支持 | 不支持（V1） | 不支持（V1） |
| Body | 支持 | 支持 | 支持 | 支持 | 支持 | 支持 |
| Path | 支持 | 支持 | 支持 | 支持 | 不支持 | 不支持 |
| Header | 支持 | 支持 | 支持 | 支持 | 不支持 | 不支持 |

### 12.3 完整 Agent 配置示例

```json
{
  "systemPrompt": "你是一名 GitHub 助手，请优先使用工具查询真实数据。",
  "chatModel": {
    "llm_model_id": 1,
    "maxOutputToken": 4096
  },
  "chatMemoryConfig": {
    "type": 1,
    "memoryConfig": "{\"maxMessage\":20}"
  },
  "pluginConfig": {
    "httpPlugins": [
      {
        "pluginId": 1001,
        "pluginMethodIds": [11, 12]
      }
    ],
    "mcpPlugins": []
  }
}
```

### 12.4 工具定义示例

最终注册到 `ToolService` 的工具：

```json
{
  "name": "github__get_user_repos",
  "description": "获取指定 GitHub 用户的仓库列表",
  "parameters": {
    "type": "object",
    "properties": {
      "username": {
        "type": "string",
        "description": "GitHub 用户名"
      },
      "page": {
        "type": "integer",
        "description": "页码，从1开始"
      }
    },
    "required": ["username"]
  }
}
```

### 12.5 关键文件清单

| 文件 | 说明 |
| --- | --- |
| `agentforge-framework/.../tool/http/domain/HttpPlugin.java` | 框架插件模型 |
| `agentforge-framework/.../tool/http/domain/HttpPluginMethod.java` | 框架方法模型 |
| `agentforge-framework/.../tool/http/domain/HttpToolParameter.java` | 框架参数模型 |
| `agentforge-framework/.../tool/http/HttpToolFactory.java` | 工具构建入口 |
| `agentforge-framework/.../tool/http/HttpToolExecutor.java` | HTTP 执行器 |
| `agentforge-framework/.../tool/http/enums/HttpPluginEnums.java` | 枚举 |
| `agentforge-framework/.../tool/http/parser/curl/CurlParser.java` | curl 解析 |
| `agentforge-framework/.../tool/http/parser/curl/CurlToHttpPluginConverter.java` | curl 转插件 |
| `agentforge-service/.../agent/domain/config/StandardAgentConfig.java` | Agent 配置模型 |
| `agentforge-service/.../agent/runtime/mode/react/ReActAgentFactory.java` | Agent 工具装配入口 |
| `agentforge-service/.../agent/service/impl/AgentConfigServiceImpl.java` | Agent 配置持久化 |
| `agentforge-service/.../agent/runtime/base/AbstractAgentManager.java` | Agent 实例缓存 |

### 12.6 与原设计文档的差异总结

| 维度 | 原设计 | 本方案 |
| --- | --- | --- |
| 表数量 | 4 张（含市场表） | 3 张核心 + 可选日志 |
| 方法命名 | `method_name` 即工具名 | `method_name` 为工具名后缀，增加 `display_name` |
| 工具命名 | 直接使用方法名 | `{alias}__{method_name}` 命名空间 |
| Agent 关系 | 文档未展开 | `agent_config.pluginConfig.httpPlugins[]`，不建关联表 |
| 发布控制 | 插件 `publish` 字段 | V1 不引入插件版本，Agent 发布快照保存引用 |
| 市场能力 | `ai_plugin_http_market` | V1 不做，保留 `is_public` |
| 安全 | 文档提及日志 | 明确 SSRF、Header 脱敏、响应限制、密钥处理 |
| 框架适配 | 原系统自研执行器 | 复用 AgentForge `HttpToolFactory` / `HttpToolExecutor` |

---

## 13. 结论

AgentForge 接入 HTTP 插件的关键不是「再写一个 HTTP 客户端」，而是把已有的框架能力接到 Service 的配置体系上：

1. **三张核心表**承载插件、方法、参数；
2. **不建 Agent-HTTP 关联表**，插件变更后统一失效标准 Agent 缓存；
3. **扩展 `StandardAgentConfig.pluginConfig.httpPlugins[]`** 让 Agent 可以引用插件方法；
4. **`HttpPluginRuntimeService`** 在运行时完成 PO -> `HttpPlugin` -> `ToolSpecification + ToolExecutor` 的转换；
5. **命名空间、校验、失败隔离、安全限制**保证多插件场景可用；
6. **框架层 F1/F2/F3 修复**是上线前的硬性前置条件。

按 P0 -> P1 -> P2 推进，可以在不破坏现有 Agent/Model/Session 模块的前提下，平滑落地 HTTP 插件能力。
