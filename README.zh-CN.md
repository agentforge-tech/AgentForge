<p align="center">
  <img src="assets/agentforge-banner.png" alt="AgentForge — 开源 AI Agent 框架" width="460">
</p>

# AgentForge

> **将智能锻造成行动。**

[![Docs](https://img.shields.io/badge/docs-online-blue?style=flat-square)](https://agentforge-tech.github.io/AgentForge/)
[![License: MIT](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-orange?style=flat-square)](https://agentforge-tech.github.io/AgentForge/docs/quickstart/getting-started)
[![GitHub stars](https://img.shields.io/github/stars/agentforge-tech/AgentForge?style=flat-square&logo=github)](https://github.com/agentforge-tech/AgentForge/stargazers)

[English](README.md) | **简体中文**

AgentForge 是一个面向 Java 开发者、从 **LLM 最底层能力开始构建** 的开源 Agent Framework。

它不从已经高度封装的 Agent API 起步，而是先建立稳定、统一、可扩展的模型抽象，再逐层向上构造
Tool、Memory、Middleware、Reasoning 与 Agent Runtime 等能力。

> **大模型提供原始智能，AgentForge 将这些智能能力逐层工程化，最终锻造成能够真正执行任务的 Agent。**

`AgentForge = Agent + Forge`：`Agent` 代表能理解目标、进行推理、调用工具并完成任务的智能体；`Forge`
强调把原始材料经过持续加工、塑形、强化，最终打造为真正可用的产品。

**文档站**：<https://agentforge-tech.github.io/AgentForge/>

**路线图**：<https://github.com/agentforge-tech/AgentForge/issues/1>

**核心特性**

- **统一模型协议层** — Provider-neutral 的 `ChatModel` / `StreamingChatModel` 契约，内置 OpenAI-compatible 与 Anthropic 实现，以及统一的 Function Calling / Tool 执行器抽象
- **ReAct Agent 运行时** — 同步与流式一套调用，窗口 ChatMemory，可插拔 Middleware 链路（日志、Agent Loop Trace），支持步长上限、取消与重试
- **工具生态** — 本地 Java `@Tool` 方法、HTTP 插件与 MCP Server 统一转换为 Function Call 协议，并为每次工具调用注入思考字段
- **AgentForge Service** — 可运行的单用户 Agent 工作台，覆盖模型配置、Agent 构建 / 调试 / 版本 / 发布、会话历史与重放，基于 SQLite / MySQL 持久化
- **流式与可观测** — 统一 SSE 事件协议，`debug` / `elegant` 双渲染模式，会话事件重放，以及包含火焰图与流程图的 Agent Loop Trace
- **后续规划** — Harness 运行时（沙箱、HITL 审核、可恢复执行）、上下文压缩、分场景异常重试、定时任务、钉钉 / 飞书渠道，以及 CLI / Desktop / Platform 多形态

---

## 架构

AgentForge 遵循 [Roadmap](https://github.com/agentforge-tech/AgentForge/issues/1) 中的两部分设计：

1. **Agent 框架层** —— 可复用的核心基础组件：统一模型协议、通用 ReAct Agent 运行时、Harness Agent 运行时（权限、沙箱、HITL 审核、可恢复执行），以及工具、记忆、Middleware、链路追踪与 Token 统计。
2. **Agent Service 业务层** —— `agentforge-service`，标准 Agent 基础设施最佳实践的开箱即用实现：模型配置、Agent 构建 / 调试 / 版本 / 发布，以及会话运营。

`agentforge-agent-core` 是框架的核心，它把大模型的原始智能逐层锻造成真实行动：

```text
LLM
 ↓  Message / Request / Response
 ↓  Context / Memory
 ↓  Tool / Skill / MCP
 ↓  Reasoning / Planning
 ↓  Agent Runtime
 ↓  Multi-Agent / Sandbox / Observability
Real Action
```

| 层级 | `agentforge-agent-core` 的实现 |
| --- | --- |
| LLM | Provider-neutral 的 `ChatModel` / `StreamingChatModel`，OpenAI / Anthropic 通过模型模块接入 |
| Message / Request / Response | 统一消息模型、`AgentRequest` / `ChatResult`，同步与流式共用一套请求响应语义 |
| Context / Memory | `ChatMemory` / `ChatMemoryStore`、窗口记忆与按 `memoryId` 隔离的会话 |
| Tool / Skill / MCP | `ToolService` 与统一的工具描述 / 执行器 SPI：本地 `@Tool`、HTTP 插件与 MCP Server |
| Reasoning / Planning | ReAct think → act → observe 循环，支持步长上限与中断检查 |
| Agent Runtime | 同步 `ReActAgent`、流式 `ReActTokenStream`、Middleware 链路、重试与取消 |
| Multi-Agent / Sandbox / Observability | Agent Loop Trace Middleware 与 Token 统计；Agent Team 与 Harness 沙箱在 Roadmap 中 |
| Real Action | 通过工具执行器产生真实副作用；审批与沙箱能力随 Harness 运行时落地 |

### 工程结构

```text
AgentForge
├── agentforge-ai-parent          # 统一依赖与插件版本管理
├── agentforge-ai-bom             # 统一 BOM 坐标维护
├── agentforge-model              # 模型层
│   ├── agentforge-model-api      # Provider 无关的模型契约与统一 req/vo
│   ├── agentforge-model-core     # 无厂商依赖的默认实现与执行引擎
│   ├── agentforge-model-openai   # OpenAI / OpenAI-compatible Provider
│   ├── agentforge-model-anthropic # Anthropic Provider
│   └── agentforge-model-registry # 开箱即用的 ChatModel 工厂
├── agentforge-framework
│   ├── agentforge-agent-core     # ReAct Agent 运行时
│   └── agentforge-harness-agent  # Harness 运行时（设计阶段）
├── agentforge-service            # 开箱即用服务（Vue 前端 + Spring Boot 后端）
│   ├── agentforge-service-ui     # Vue 2 + Vite 前端
│   └── agentforge-service-web    # Spring Boot 后端
├── agentforge-examples
├── pom.xml
└── README.md
```

### AgentForge Service

`agentforge-service` 是标准 Agent 基础设施最佳实践的可运行参考实现，不是 Demo 外壳。它打通了从模型配置到正式发布的完整闭环：

```text
模型配置 → Agent 配置 → 调试 → 版本 → 发布 → 对话与会话历史
```

| 能力 | 当前实现 |
| --- | --- |
| 模型管理 | OpenAI / Anthropic / OpenAI-compatible endpoint、API Key、baseUrl 与模型名，运行时由 `LlmFactory` 解析 |
| Agent 配置 | Prompt、对话模型、窗口记忆与插件引用统一持久化到 `ai_agent_config` |
| 调试与预览 | 调试请求使用当前编辑配置；同一套事件流可切换高信息密度 `debug` 模式与面向用户的 `elegant` 模式 |
| 版本历史 | `ai_agent_config_version` 版本快照、可编辑版本备注、历史版本切换与左右对比 |
| 发布快照 | `ai_agent_config_published` 隔离线上配置与编辑中配置；发布 / 取消发布会失效运行时实例缓存 |
| 会话与重放 | `ai_agent_session` / `ai_agent_session_message` 保存会话标题、置顶、消息与 SSE 事件快照，支持历史重放 |
| ChatMemory | `ai_agent_memrory` 通过可插拔 `ChatMemoryStore` 持久化模型上下文（默认内存实现，Service 默认 SQLite，可切 MySQL） |
| HTTP 插件 | HTTP 插件 / 方法 / 参数三层模型，支持 curl 与 JSON 导入、在线试运行、工具名 `{alias}__{method}` |
| MCP 插件 | MCP 插件 / Server 管理、`mcpServers` 一键导入导出、工具探测、多 Server 聚合与单 Server 失败隔离 |
| 流式交互 | 统一 SSE 事件（`session` / `think` / `resp` / `tool_call` / `resp_end` / `error`）、Markdown 流式渲染与 `__thought` 工具意图 |

后端按业务模块组织：

```text
agentforge-service-web
└── modules
    ├── model      # 模型配置
    ├── agent      # Agent 配置、版本、发布与运行时装配
    ├── session    # 会话、消息与 SSE 事件重放
    └── plugins    # HTTP / MCP 插件
```

### 设计原则

1. **Bottom-up** — 先稳定 LLM / Message / Request / Response 等基础抽象，再构建 Agent。
2. **Provider-neutral** — 上层框架不被任何一家模型厂商协议绑定。
3. **Modular** — 核心抽象与 Provider / Framework / Agent Runtime 分模块演进。
4. **Lightweight** — 底层尽量减少不必要依赖，可被 Spring Boot、普通 Java、桌面端甚至嵌入式工程复用。
5. **Production-oriented** — 目标不是 Demo Agent，而是可以真正进入生产环境的 Agent Runtime。

---

## 快速开始

AgentForge 有两种使用方式：作为框架嵌入自己的 Java 应用，或一键在本地启动开箱即用的 Agent Service。

### 第一部分：框架使用方式

#### 依赖引入

在首个 Maven Central 版本发布前，请使用本地构建的 `1.0.0-SNAPSHOT` 产物：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

其他常用模块：`agentforge-model-openai`、`agentforge-model-anthropic`，或完整的 `agentforge-service`。

#### 环境要求

> **推荐 JDK 17，兼容 JDK 8。**

- 日常开发与 CI 默认推荐 **JDK 17**；公共模块编译目标为 **Java 8 bytecode**，JDK 8 可直接依赖运行；
- 统一根包名与 `groupId` 均为 `cloud.changlu.agentforge`。

#### 调用模型

```java
ChatModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .build();

String answer = model.chat("Hello AgentForge");
```

`baseUrl` 可配置，因此也可作为 OpenAI-compatible Provider 使用（DashScope / Ollama / Xinference 等）。
需要流式输出时，使用 `StreamingChatModel` + `StreamingChatResponseHandler`。

#### 构建 Agent

```java
ToolService toolService = new ToolService();
toolService.tools(new WeatherTools());   // 扫描对象中所有 @Tool 方法

ReActAgent agent = ReActAgent.builder()
        .agentName("weather-react-agent")
        .systemPrompt("你是一个天气助手。")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(ChatMemoryProvider.windowChatMemoryProvider(50))
        .toolService(toolService)
        .build();

ChatResult result = agent.run(AgentRequest.builder()
        .memoryId("demo")
        .question("北京今天的天气怎么样？")
        .build());
```

流式执行通过 `agent.runStream(request)` 返回 `TokenStream`，可订阅文本 / 思考增量、工具执行与完成事件；
`agent.cancel(memoryId)` 用于取消任务。模型、记忆、工具与中间件均通过 Builder 注入，详见文档站。

#### 源码构建

```bash
git clone https://github.com/agentforge-tech/AgentForge.git
cd AgentForge
mvn clean install -DskipTests
```

构建会将 `1.0.0-SNAPSHOT` 产物安装到本地 Maven 仓库。

### 第二部分：AgentForge Service 快捷本地一键启动

`agentforge-service` 是标准的 Agent 工作台，打通了从模型配置到正式发布的完整闭环。本地体验最快的方式是一键安装脚本（需要 Docker 20.10+ 与 Docker Compose v2）：

```bash
mkdir -p agentforge && cd agentforge
curl -fsSL https://raw.githubusercontent.com/agentforge-tech/AgentForge/master/agentforge-service/docker/standalone/install.sh | bash
```

脚本会拉取一体化镜像（后端 + 前端 + SQLite）、初始化数据库并启动服务：

```text
http://127.0.0.1:18080
```

安装器只下载 Compose 文件、环境变量模板与升级脚本；`conf/`、`data/`、`logs/`、`upload/` 会持久化在当前目录。重复执行同一条命令是安全的，后续可通过 `./upgrade.sh [版本]` 升级。

#### 源码模式启动

需要先完成第一部分的源码构建：

```bash
# 后端（Spring Boot，默认 8080）
cp agentforge-service/conf/application.yml.example agentforge-service/conf/application.yml
export AGENTFORGE_MODEL_API_KEY=your-api-key
mvn package -pl agentforge-service/agentforge-service-web -am -DskipTests
cd agentforge-service
./bin/local/upgrate-sqlite.sh
java -jar agentforge-service-web/target/agentforge-service-web-1.0.0-SNAPSHOT.jar

# 前端（Vue 2 + Vite，默认 5173，代理 /api 到 8080）
cd agentforge-service/agentforge-service-ui
npm install && npm run dev
```

#### 构建一体化镜像

```bash
cd agentforge-service/docker && ./scripts/deploy.sh
```

服务默认使用 SQLite（`agentforge-service/data/agentforge.db`），设置 `AGENTFORGE_DB_TYPE=mysql`
可切换 MySQL。服务启动不会自动建表，首次启动前需执行一次 SQLite 迁移脚本。

前端页面包括模型管理（`/`）、Agent（`/agents`）、HTTP 插件（`/http-plugins`）、MCP 插件（`/mcp`）与发布对话页（`/agent/published/{aid}`）。详见 [agentforge-service/README.md](agentforge-service/README.md)。

### 测试

```bash
mvn clean test
```

单测默认不访问真实模型服务，通过可替换的 `HttpTransport` 与脚本化模型验证请求与响应，CI 无需配置 API Key。

---

## 文档

文档站是最佳的起点，提供简体中文与英文两个版本，涵盖整体架构、各模块设计原理与端到端使用指南。

| 资源 | 链接 |
| --- | --- |
| 文档站 | <https://agentforge-tech.github.io/AgentForge/> |
| AgentForge 介绍 | <https://agentforge-tech.github.io/AgentForge/docs/about/introduction> |
| 快速开始 | <https://agentforge-tech.github.io/AgentForge/docs/quickstart/getting-started> |
| 贡献指南 | <https://agentforge-tech.github.io/AgentForge/docs/quickstart/contributing> |
| 博客 | <https://agentforge-tech.github.io/AgentForge/blog> |
| 源码（GitHub） | <https://github.com/agentforge-tech/AgentForge> |
| 镜像（Gitee） | <https://gitee.com/changluJava/agent-forge> |

---

## 问题反馈

如果使用中遇到问题，欢迎通过以下方式反馈：

- GitHub Issue：<https://github.com/agentforge-tech/AgentForge/issues>
- Gitee Issue：<https://gitee.com/changluJava/agent-forge/issues>
- 文档站：<https://agentforge-tech.github.io/AgentForge/>

提 Issue 时请尽量附上环境信息（JDK / Maven 版本）、复现步骤与完整报错日志，便于快速定位。

---

## 参与贡献

欢迎参与 AgentForge 的建设，无论是代码、文档还是使用反馈。

1. Fork 本仓库并从开发分支切出特性分支；
2. 遵循项目代码规范（提交前会执行 Spotless 格式校验）；
3. 新增或修改能力时同步补充单元测试，并确保 `mvn clean test` 通过；
4. 提交 Pull Request，说明改动背景、方案与影响范围。

> 请勿将真实 API Key 写入源码或提交到仓库。

---

## 贡献者

| 贡献者 | 说明 |
| --- | --- |
| [changlu](https://github.com/changluya) | 作者 / Maintainer |

欢迎你的名字出现在这里。

---

## 许可证

AgentForge 基于 [MIT License](LICENSE) 开源。

---

> **模型提供智能，AgentForge 负责将它一步步锻造成真正能够行动的 Agent。**
