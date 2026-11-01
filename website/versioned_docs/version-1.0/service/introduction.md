---
title: AgentForge Service
sidebar_label: Service 介绍
description: 开箱即用的 Agent 工作台：模型配置、Agent 调试、版本发布、会话与链路追踪
sidebar_position: 1
displayed_sidebar: serviceSidebar
---

# AgentForge Service

AgentForge Service 是构建在 AgentForge 框架之上的标准 Agent 工作台，打通了从模型配置到正式发布的完整闭环：

```text
模型配置 → Agent 配置 → 调试 → 版本 → 发布 → 对话与会话历史
```

它适合希望快速体验 AgentForge、又不想从零搭建前后端的团队，也适合作为企业内部 Agent 应用的起点。

## 它解决什么问题

直接使用 SDK 时，你需要自己解决：

- 模型密钥如何管理、如何切换 Provider；
- Agent 配置如何持久化、如何做版本与发布；
- 对话会话如何保存、如何重放；
- 流式事件如何推送到前端；
- 工具调用链路如何观测。

AgentForge Service 把这些能力做成了一套可运行的产品：

| 能力 | 当前实现 |
| --- | --- |
| 模型管理 | OpenAI / Anthropic / OpenAI-compatible endpoint、API Key、baseUrl 与模型名 |
| Agent 配置 | Prompt、对话模型、窗口记忆与插件引用统一持久化 |
| 调试与预览 | 调试使用当前编辑配置，支持 `debug` 与 `elegant` 两种事件密度 |
| 版本历史 | 版本快照、版本备注、历史切换与左右对比 |
| 发布快照 | 线上配置与编辑中配置隔离，发布/取消发布自动失效运行时缓存 |
| 会话与重放 | 保存会话标题、置顶、消息与 SSE 事件快照 |
| ChatMemory | 可插拔 `ChatMemoryStore`，默认 SQLite，可切 MySQL |
| HTTP 插件 | 插件 / 方法 / 参数三层模型，支持 curl 与 JSON 导入、在线试运行 |
| MCP 插件 | Server 管理、`mcpServers` 导入导出、工具探测与多 Server 聚合 |
| 流式交互 | 统一 SSE 事件：`session` / `think` / `resp` / `tool_call` / `resp_end` / `error` |
| 链路追踪 | Agent Loop Trace、火焰图与流程图 |

## 技术结构

```text
agentforge-service
├── agentforge-service-ui     # Vue 2 + Vite 前端
└── agentforge-service-web    # Spring Boot 后端
    └── modules
        ├── model             # 模型配置
        ├── agent             # Agent 配置、版本、发布与运行时装配
        ├── session           # 会话、消息与 SSE 事件重放
        └── plugins           # HTTP / MCP 插件
```

前端负责工作台交互与流式 Markdown 渲染，后端负责配置持久化、Agent 装配、事件推送与链路追踪。两者共享同一套 Agent 运行时。

## 适用场景

- 想快速体验 AgentForge 的完整能力；
- 需要一套可私有化部署的 Agent 工作台；
- 想把 Agent 配置交给非开发人员维护；
- 需要调试、版本、发布、会话与追踪的完整闭环。

## 快速启动

最快的方式是使用 Docker 一键安装：

```bash
mkdir -p agentforge && cd agentforge
curl -fsSL https://raw.githubusercontent.com/agentforge-tech/AgentForge/master/agentforge-service/docker/standalone/install.sh | bash
```

启动后访问 `http://127.0.0.1:18080`。

完整步骤、端口修改、数据目录与升级方式见 [Service 快速部署](./quick-deploy)。

## Service 与 SDK 的关系

```text
AgentForge SDK
    ├── agentforge-model-*       统一模型协议
    └── agentforge-agent-core    ReAct / Middleware / Tool
                │
                ▼
AgentForge Service
    ├── 配置持久化
    ├── Agent 装配与运行时
    ├── SSE 事件与会话
    └── 工作台 UI
```

- 想嵌入自己的 Java 应用：使用 [SDK 文档](../about/introduction)；
- 想直接使用成品工作台：使用 AgentForge Service；
- 想二次开发 Service：从源码构建，复用同一套 Agent 运行时。
