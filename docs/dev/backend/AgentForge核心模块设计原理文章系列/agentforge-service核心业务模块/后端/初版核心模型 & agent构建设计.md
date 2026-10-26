# 背景

AgentForge 首版以框架层（`agentforge-model` / `agentforge-agent-core`）能力为主，已经完成统一的 ChatModel 协议层与通用 ReActAgent 层。

接下来需要落地 `agentforge-service` 业务层：把「模型配置」「Agent 配置」从代码里的硬编码（见 `AgentConfiguration` 中直接 `builder().build()`）下沉为**可持久化、可配置、可管理**的数据，从而支撑「我的模型」「Agent 构建/发布」「对话」等业务能力。

本文先梳理**初版核心模型（ai_model_config）与 Agent（ai_agent_config）两张核心表**的结构设计。表结构设计参照 LangChain4j 的模型/记忆抽象（`ChatModel`、`ChatMemory`、`ChatMemoryStore`）进行建模（AgentForge 统一加 `ai_` 表前缀），并结合 AgentForge 已有核心类（`LlmBasicConfig`、`AgentSettings`、`ReActAgent`）做最小适配。



# 开发分支
agentforge：feat_1.0.x_2



# 整体设计思路

## 分层

```shell
# 持久层：两张核心表
ai_model_config      模型配置表        —— 描述一个可用的 LLM（厂商/地址/密钥/模型名）
ai_agent_config   Agent 配置表      —— 描述一个可运行的 Agent（绑定的模型/工具/提示词/参数）

# 引擎层（已实现）
LlmBasicConfig   ← 由 ai_model_config 行转换而来，交给 LlmFactory 构建 ChatModel
AgentSettings    ← 由 ai_agent_config.agent_config(JSON) 中的参数部分转换而来
ReActAgent       ← 由 Agent 配置 + 构建好的 ChatModel + ToolService 装配而来
```

一句话概括：**`ai_model_config` 决定「用哪个大脑」，`ai_agent_config` 决定「大脑怎么用人格、工具、参数去干活」**。一张 agent 配置通过 `llm_model_id` 引用一个模型配置，运行时装配成 `ReActAgent`。

## 设计原则

1. **配置与运行分离**：表只存配置，运行态的 `ReActAgent` 每次按需装配，便于热更新与多租户隔离。
2. **主配置 JSON 化**：Agent 的可变能力（模型、工具、提示词等）统一收敛到 `agent_config` 大字段，减少后期加字段的 DDL 成本。
3. **逻辑删除 + 公开标记**：`is_deleted` 支持资源软删除，`ai_agent_config.is_public` 支持 Agent 开放共享。
4. **aid 对外唯一标识**：Agent 使用 64 位字符串 `aid` 作为对外/跨模块引用标识，`id` 仅内部使用。



# 核心表结构设计

## 1、ai_model_config 模型配置表

> 参照 LangChain4j 的 `ChatModel` 抽象，结合 AgentForge 当前仅支持 `OPENAI(1)`、`ANTHROPIC(2)` 两种协议做 provider 适配。

```sql
CREATE TABLE `ai_model_config` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(255) NOT NULL COMMENT '自定义命名',
    `provider` INT NOT NULL COMMENT '模型厂商（OpenAI-1，Anthropic-2）',
    `model_type` INT NOT NULL COMMENT '模型类别（llm-0、embedding-1）',
    `modelname` VARCHAR(255) NOT NULL COMMENT '模型名称，如 gpt-4o、claude-3-5-sonnet',
    `url` VARCHAR(500) DEFAULT NULL COMMENT '模型连接地址（baseUrl）',
    `api_key` VARCHAR(500) DEFAULT NULL COMMENT '模型密钥',
    `create_type` INT NOT NULL COMMENT '创建类型（系统内置-0、用户创建-1）',
    `create_user_id` BIGINT NOT NULL DEFAULT 1 COMMENT '创建者ID',
    `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标志（0-未删除，1-已删除）',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM模型配置表';
```

### 字段说明

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT | 主键，自增 |
| `name` | VARCHAR(255) | 自定义命名，展示用（如「我的 GPT-4o」） |
| `provider` | INT | 模型厂商，对应 `LlmEnum.code`：`1=OPENAI`、`2=ANTHROPIC` |
| `model_type` | INT | 模型类别：`0=llm`、`1=embedding`（首版重点 chat） |
| `modelname` | VARCHAR(255) | 真实模型名，透传给协议层 |
| `url` | VARCHAR(500) | 模型连接地址，兼容 OpenAI-compatible（DashScope / Ollama / Xinference 等只需改 url） |
| `api_key` | VARCHAR(500) | 模型密钥 |
| `create_type` | INT | `0=系统内置`、`1=用户创建`，内置模型不可删除 |
| `create_user_id` | BIGINT | 创建者，用于数据隔离 |
| `is_deleted` | TINYINT | 逻辑删除标志 |
| `create_time` / `modify_time` | DATETIME | 创建 / 更新时间 |

### 与核心类的映射

`ai_model_config` 一行 → `cloud.changlu.agentforge.model.registry.config.LlmBasicConfig`：

| 表字段 | LlmBasicConfig 字段 |
| --- | --- |
| `provider` | `provider` |
| `url` | `url` |
| `modelname` | `modelName` |
| `api_key` | `apiKey` |
| （扩展参数） | `props`（temperature、maxTokens 等） |

转换后交给 `LlmFactory` 按 `provider` 选择 `IModel`（`OpenAiModel` / `AnthropicModel`）构建 ChatModel / StreamingChatModel。



## 2、ai_agent_config Agent 配置表

> 参照 LangChain4j 的 Agent 运行参数组织方式（`ChatModel` + `ChatMemory` + 工具集合），字段包含 `create_user_id`、`is_public`、`avatar`、`description`、`exper_config` 等，初版裁剪掉 `biz_type`、`knowledge_id`。

```sql
CREATE TABLE `ai_agent_config` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(255) NOT NULL COMMENT 'agent名称',
    `type` INT NOT NULL COMMENT 'agent类型 1=ReActAgent',
    `agent_config` LONGTEXT DEFAULT NULL COMMENT 'agent配置项(JSON)',
    `exper_config` LONGTEXT DEFAULT NULL COMMENT '对话体验面板配置(JSON)',
    `publish` INT NOT NULL DEFAULT 0 COMMENT '是否发布 0暂未发布、1已发布',
    `last_publish_time` DATETIME DEFAULT NULL COMMENT '最新发布时间',
    `aid` VARCHAR(64) NOT NULL COMMENT 'agent唯一标识ID',
    `is_public` INT NOT NULL DEFAULT 0 COMMENT '是否公开 0不公开、1公开',
    `avatar` VARCHAR(255) DEFAULT '' COMMENT '头像URL',
    `description` VARCHAR(1024) DEFAULT '' COMMENT 'Agent描述，用于在广场中展示',
    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '是否逻辑删除 0未删除、1已删除',
    `create_user_id` BIGINT NOT NULL DEFAULT 1 COMMENT '创建者ID',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_aid` (`aid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通用agent参数配置表';
```

### 字段说明

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT | 主键，自增 |
| `name` | VARCHAR(255) | Agent 名称 |
| `type` | INT | Agent 引擎类型：`1=ReActAgent`（后续可扩展 Single / Plan） |
| `agent_config` | LONGTEXT | Agent 主配置 JSON（模型、工具、提示词、AgentSettings），见下文 |
| `exper_config` | LONGTEXT | 对话体验面板配置 JSON（开场白、推荐问等） |
| `publish` | INT | 是否发布：`0=未发布`、`1=已发布` |
| `last_publish_time` | DATETIME | 最新发布时间 |
| `aid` | VARCHAR(64) | 对外唯一标识，跨模块引用（如对话、监控、渠道）都基于 aid |
| `is_public` | INT | 是否公开 |
| `avatar` | VARCHAR(255) | 头像 URL |
| `description` | VARCHAR(1024) | 描述，广场展示用 |
| `is_deleted` | INT | 逻辑删除标志 |
| `create_user_id` | BIGINT | 创建者，数据隔离用 |
| `create_time` / `modify_time` | DATETIME | 创建 / 更新时间 |

### agent_config 字段(JSON) 结构示例

```json
{
  "chatModels": [{ "llm_model_id": 1 }],
  "systemPrompt": "You are AgentForge Service assistant...",
  "tools": [
    { "type": "local", "name": "webSearch" },
    { "type": "http", "pluginId": 1001 },
    { "type": "mcp", "server": "xxx" }
  ],
  "agentSettings": {
    "maxSteps": 10,
    "aiCallRetry": 1,
    "aiCallRetryDelay": 500,
    "enableConcurrentToolExecution": true
  },
  "chatMemory": { "windowSize": 50 }
}
```

### 与核心类的映射

| agent_config(JSON) 片段 | 核心类 |
| --- | --- |
| `chatModels[].llm_model_id` | 关联 `ai_model_config.id`，构建 ChatModel |
| `agentSettings.*` | `cloud.changlu.agentforge.agent.domain.AgentSettings`（`maxSteps` / `aiCallRetry` / `aiCallRetryDelay` / `enableConcurrentToolExecution`） |
| `systemPrompt` | `ReActAgent.builder().systemPrompt(...)` |
| `tools[]` | `ToolService`（local tool / http tool / mcp tool 统一转换 FunctionCall 协议） |
| `chatMemory.windowSize` | `ChatMemoryProvider.windowChatMemoryProvider(n)` |

运行时装配流程：

```shell
ai_agent_config 一行
   ├── 解析 agent_config(JSON)
   ├── 按 chatModels[].llm_model_id 查 ai_model_config → LlmBasicConfig → LlmFactory → ChatModel / StreamingChatModel
   ├── 解析 tools[] → ToolService
   └── 解析 agentSettings / systemPrompt / chatMemory → ReActAgent.builder()...
```

即：把当前 `AgentConfiguration` 中硬编码的装配逻辑，改为**「读表 → 装配」**，从而实现多 Agent、可配置、可发布。



# 后续扩展

1. **对话会话表**：`ai_agent_chat_session` / `ai_agent_chat_message`（参照 LangChain4j 的 `ChatMemoryStore` 设计）承载多轮会话与消息持久化。
2. **工具/插件表**：http 插件、mcp server 注册表，配合 `tools[]` 引用。
3. **发布与开放**：`publish` + `is_public` + `aid` 支撑 Agent 广场与开放 API。
4. **多租户/权限**：`create_user_id` 与协作者权限表承接数据隔离。
