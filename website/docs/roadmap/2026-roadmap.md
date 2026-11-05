---
id: roadmap
title: 2026 年 AgentForge RoadMap
sidebar_label: 2026 年 RoadMap
description: AgentForge 2026 年度技术路线图与核心能力规划
toc_max_heading_level: 4
displayed_sidebar: roadmapSidebar
---

# 2026 年 AgentForge RoadMap

AgentForge 以“自底向上”的方式构建 Java Agent 技术栈：先稳定模型协议与核心契约，再逐层扩展 ReAct Agent、Harness Runtime 以及不同形态的 Agent 应用。

当前框架版本：**1.0.0**。本文所有引用链接均在新窗口打开。

## 核心工程结构

```text
AgentForge
├── agentforge-ai-parent           # 统一依赖与插件版本管理
├── agentforge-ai-bom              # 统一 BOM 坐标维护
├── agentforge-model               # 模型层
│   ├── agentforge-model-api       # Provider 无关的模型契约与统一 req/vo
│   ├── agentforge-model-core      # 无厂商依赖的默认实现与执行引擎
│   ├── agentforge-model-openai    # OpenAI / OpenAI-compatible Provider
│   ├── agentforge-model-anthropic # Anthropic Provider
│   └── agentforge-model-registry  # 开箱即用的 ChatModel 工厂
├── agentforge-framework
│   ├── agentforge-agent-core      # ReAct Agent 运行时
│   └── agentforge-harness-agent   # Harness 运行时（权限 / 审批 / 沙箱 / 可恢复执行）
├── agentforge-service             # 开箱即用服务（Vue 前端 + Spring Boot 后端）
│   ├── agentforge-service-ui      # Vue 2 + Vite 前端
│   └── agentforge-service-web     # Spring Boot 后端
├── agentforge-examples
├── pom.xml
└── README.md
```

工程分为两块核心部分：

1. **Agent 框架层**：提供 ReAct 核心、链路监控、Token 统计、Agent Team 等基础组件；
2. **Agent 标准 Service 业务层**：提供可扩展的 Agent 工作台，把框架能力变成可配置、可调试、可发布的产品能力。

## 模型协议层

### LLM Model

- [x] OpenAI 协议：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/01%E3%80%81model%E6%A8%A1%E5%9E%8B%E5%8D%8F%E8%AE%AE%E5%B1%82/chatmodel/openai/OpenAI%E5%8D%8F%E8%AE%AE02%E3%80%81AgentForge%20%E9%80%82%E9%85%8DOpenAI%E6%8E%A5%E5%85%A5%E6%A0%B8%E5%BF%83%E5%AE%9E%E8%B7%B5.md" target="_blank" rel="noopener noreferrer">OpenAI协议02、AgentForge 适配OpenAI接入核心实践</a>
- [x] Anthropic 协议：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/01%E3%80%81model%E6%A8%A1%E5%9E%8B%E5%8D%8F%E8%AE%AE%E5%B1%82/chatmodel/anthropic/Anthropic%E5%8D%8F%E8%AE%AE02%E3%80%81AgentForge%20%E9%80%82%E9%85%8DAnthropic%E6%8E%A5%E5%85%A5%E6%A0%B8%E5%BF%83%E5%AE%9E%E8%B7%B5.md" target="_blank" rel="noopener noreferrer">Anthropic协议02、AgentForge 适配Anthropic接入核心实践</a>
- [ ] ...其他待续

### 协议层抽象设计

- [x] 协议层 Function Calling 与执行器抽象层设计实现：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/01%E3%80%81model%E6%A8%A1%E5%9E%8B%E5%8D%8F%E8%AE%AE%E5%B1%82/chatmodel/%E7%BB%9F%E4%B8%80ChatModel%E5%8D%8F%E8%AE%AE%E5%B1%8202%E3%80%81Function%20Calling%20%E5%B7%A5%E5%85%B7%E8%B0%83%E7%94%A8%E8%BD%AC%E6%8D%A2%E4%B8%8E%E6%89%A7%E8%A1%8C%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0%EF%BC%9ATool%20%E5%A5%91%E7%BA%A6%20%2B%20ToolService%20%E6%89%A7%E8%A1%8C%E5%BE%AA%E7%8E%AF.md" target="_blank" rel="noopener noreferrer">统一ChatModel协议层02、Function Calling 工具调用转换与执行扩展实现：Tool 契约 + ToolService 执行循环</a>

### Embedding Model

- [ ] OpenAI Embedding 协议
- [ ] 向量检索与长期记忆接入

## Agent 框架层

### 核心 ReAct 框架组件

> **核心代码**：
>
> ReAct Agent：<a href="https://github.com/agentforge-tech/AgentForge/tree/master/agentforge-framework/agentforge-agent-core" target="_blank" rel="noopener noreferrer">agentforge-agent-core</a> 的 src 源码包 `cloud.changlu.agentforge.agent.ReActAgent`
>
> Middleware 设计：<a href="https://github.com/agentforge-tech/AgentForge/tree/master/agentforge-framework/agentforge-agent-core" target="_blank" rel="noopener noreferrer">agentforge-agent-core</a> 的 src 源码包 `cloud.changlu.agentforge.agent.component.middleware`

- [x] 核心 ReAct 框架设计，支持非流式与流式一套调用
  - 实现：`ReActAgent` 承载非流式主循环，`stream.ReActTokenStream` 承载流式主循环，二者共用 think → act → 写回记忆的步进语义与中断、步长上限控制
- [x] Middleware 接口扩展实现
  - 实现：`IAgentMiddleware` 定义 loop、AI 重试、工具执行等阶段回调，`IStreamingIAgentMiddleware` 承载流式事件，`AgentMiddlewareManager` 负责注册排序与分发
- [x] Agent 标准会话记忆设计与实现：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86/Agent%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E6%8E%A5%E5%8F%A3%E8%AE%BE%E8%AE%A1%E4%B8%8E%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">Agent会话记忆接口设计与实现</a>

### 外部协议转 Function Call 协议与执行

> **核心代码**：<a href="https://github.com/agentforge-tech/AgentForge/tree/master/agentforge-framework/agentforge-agent-core" target="_blank" rel="noopener noreferrer">agentforge-agent-core</a> 的 src 源码包 `cloud.changlu.agentforge.agent.tool`

- [x] 支持本地 Local Tool（Java 代码）转换：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/functioncall%E6%A0%B8%E5%BF%83%E6%89%A9%E5%B1%95%E4%B8%9A%E5%8A%A1tool/local/Java%E4%BB%A3%E7%A0%81%E8%BD%AC%E6%8D%A2FunctionCall%E5%8D%8F%E8%AE%AE01%E3%80%81AgentForge%20Local%E5%B7%A5%E5%85%B7%E6%A8%A1%E5%9D%97%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86.md" target="_blank" rel="noopener noreferrer">Java代码转换FunctionCall协议01、AgentForge Local工具模块核心设计原理</a>
  - 实现：`LocalToolFactory` 将本地方法映射为 `ToolSpecification`，`LocalToolExecutor` 负责入参转换与反射调用
- [x] 支持 HTTP 外部接口转换：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/functioncall%E6%A0%B8%E5%BF%83%E6%89%A9%E5%B1%95%E4%B8%9A%E5%8A%A1tool/http/%E9%80%82%E9%85%8DHTTP%E5%8D%8F%E8%AE%AE%E8%BD%AC%E6%8D%A2FunctionCall%E5%8D%8F%E8%AE%AE01%E3%80%81AgentForge%20HTTP%E5%B7%A5%E5%85%B7%E6%A8%A1%E5%9D%97%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86.md" target="_blank" rel="noopener noreferrer">适配HTTP协议转换FunctionCall协议01、AgentForge HTTP工具模块核心设计原理</a>
  - 实现：`HttpToolFactory` 把 HTTP 插件定义转为工具描述，`HttpToolExecutor` 执行请求，另含 cURL ↔ HttpPlugin 双向转换
- [x] 支持 MCP 服务协议接口转换：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/functioncall%E6%A0%B8%E5%BF%83%E6%89%A9%E5%B1%95%E4%B8%9A%E5%8A%A1tool/mcp/MCP%E5%8D%8F%E8%AE%AE%E8%BD%AC%E6%8D%A2FunctionCall%E5%8D%8F%E8%AE%AE02%E3%80%81MCP%E6%A1%86%E6%9E%B6%E8%B0%83%E7%A0%94%E4%B8%8EAgentForge%E6%8E%A5%E5%85%A5%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">MCP协议转换FunctionCall协议02、MCP框架调研与AgentForge接入实现</a>
  - 实现：`McpToolFactory` 拉取 MCP tools 并映射为 `ToolSpecification`，`McpToolExecutor` 走 JSON-RPC 调用，传输层支持 stdio 与 streamable-http
- [ ] ...其他规划

**优化设计：**

- [x] 插件工具统一 Function Call 标准注入思考字段：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/functioncall%E6%A0%B8%E5%BF%83%E6%89%A9%E5%B1%95%E4%B8%9A%E5%8A%A1tool/%E6%8F%92%E4%BB%B6%E5%B7%A5%E5%85%B7%E7%BB%9F%E4%B8%80FunctionCall%E6%A0%87%E5%87%86%E4%B8%8E%E6%80%9D%E8%80%83%E5%AD%97%E6%AE%B5%E6%B3%A8%E5%85%A5%E8%AE%BE%E8%AE%A1.md" target="_blank" rel="noopener noreferrer">插件工具统一FunctionCall标准与思考字段注入设计</a>

### 外部中断工具调用场景

- [ ] 中断事件：外部构造并返回工具调用结果（例如 `askUser` tool）
- [ ] 中断审核确认：支持确认/拒绝工具是否执行（例如 `rm` 审批，见 HITL）

### 扩展实现-会话记忆

- [x] 窗口会话记忆扩展实现：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86/%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E6%89%A9%E5%B1%95-%E7%AA%97%E5%8F%A3%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E8%AE%BE%E8%AE%A1%E4%B8%8E%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">会话记忆扩展-窗口会话记忆设计与实现</a>

### 扩展线-Middleware 插件

- [x] 日志 Middleware（支持快速本地调试）：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/middleware/%E5%86%85%E7%BD%AEmiddleware%E6%89%A9%E5%B1%95%EF%BC%9A%E6%97%A5%E5%BF%97Loggingmiddlware%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">内置middleware扩展：日志Loggingmiddlware实现</a>
  - 实现：`LoggingIAgentMiddleware` 打印 loop、模型调用、工具执行关键节点并截断超长内容，按 memoryId 统计模型耗时、工具耗时与 token 消耗
- [x] Agent Trace Middleware（Agent Loop 监控，包含 token 消耗记录）：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/middleware/%E5%86%85%E7%BD%AEmiddlware%E6%89%A9%E5%B1%95%EF%BC%9AAgent%20Loop%20TraceMiddlware%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">内置middlware扩展：Agent Loop TraceMiddlware实现</a>
- [x] Agent 对话追加 Middleware（Agent Loop 过程中追加消息）：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/middleware/%E5%86%85%E7%BD%AEmiddleware%E6%89%A9%E5%B1%95%EF%BC%9A%E8%BF%BD%E5%8A%A0%E5%AF%B9%E8%AF%9D%E5%88%B0Agent.md" target="_blank" rel="noopener noreferrer">内置middleware扩展：追加对话到Agent</a>
  - 实现：`UserMessageAppendMiddleware` 在运行期接收用户追加消息，排队后在下一轮 `beforeModelCall` 注入 Memory 与 ChatRequest
- [x] 上下文压缩 Middleware（阈值比例压缩机制）：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/middleware/%E5%86%85%E7%BD%AEmiddlware%E6%89%A9%E5%B1%95%EF%BC%9A%E4%B8%8A%E4%B8%8B%E6%96%87%E5%8E%8B%E7%BC%A9middlware%EF%BC%88%E9%98%88%E5%80%BC%E6%AF%94%E4%BE%8B%E5%8E%8B%E7%BC%A9%E6%9C%BA%E5%88%B6%EF%BC%89.md" target="_blank" rel="noopener noreferrer">内置middlware扩展：上下文压缩middlware（阈值比例压缩机制）</a>
  - 实现：`CompactSummarizationMiddleware` 在 `beforeModelCall` 按阈值压缩历史并重建请求，在 `afterToolExecution` / `afterConcurrentToolExecution` 截断超大工具结果，通过 `CompactLifecycleHook` 向业务层发布压缩事件

### Agent Loop 稳定性优化

- [ ] 支持模型协议对话时进行异常动态重试（区分各类场景）
- [ ] 流式路径接入统一重试策略

## Agent Harness

### 沙箱运行时设计

- [ ] 支持 Docker 控制远程沙箱协议（本机与外部）
- [ ] 支持对接 E2B 沙箱环境
- [ ] 支持对接 K8S 容器沙箱环境

### Human in the Loop（审核确认机制）

- [ ] Agent 审核权限设计
- [ ] 中断审核确认：Agent 层提供标准 HITL 审核确认（例如权限审批工具拦截、外部注入工具权限规则）

## Agent 基础服务：AgentForge Service

### 资源组件设计

#### 模型配置

- [x] 核心模型统一协议层设计方案文档：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/01%E3%80%81model%E6%A8%A1%E5%9E%8B%E5%8D%8F%E8%AE%AE%E5%B1%82/chatmodel/%E7%BB%9F%E4%B8%80ChatModel%E5%8D%8F%E8%AE%AE%E5%B1%8201%E3%80%81ChatModel%20%E6%A0%B8%E5%BF%83%E5%8D%8F%E8%AE%AE%E5%B1%82%E8%AE%BE%E8%AE%A1%EF%BC%9A%E7%BB%9F%E4%B8%80%20API%20%E5%A5%91%E7%BA%A6%E5%B0%81%E8%A3%85%E4%B8%8E%E5%A4%9A%E5%8D%8F%E8%AE%AE%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">统一ChatModel协议层01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现</a>

#### HTTP 插件工具

- [x] 核心 Agent 插件之 HTTP 插件设计：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/%E6%88%91%E7%9A%84%E8%B5%84%E6%BA%90/http%E6%8F%92%E4%BB%B6/AgentForge%20Service%20%E6%8E%A5%E5%85%A5%20HTTP%20%E6%8F%92%E4%BB%B6%E8%AE%BE%E8%AE%A1%E6%96%B9%E6%A1%88.md" target="_blank" rel="noopener noreferrer">AgentForge Service 接入 HTTP 插件设计方案</a>

#### MCP 插件服务

- [x] 核心 Agent 插件之 MCP 插件设计：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/%E6%88%91%E7%9A%84%E8%B5%84%E6%BA%90/MCP%E6%8F%92%E4%BB%B6/AgentForge%20MCP%20%E6%96%B9%E6%A1%88%E8%AE%BE%E8%AE%A1%E4%B8%8E%E5%AE%9E%E7%8E%B0%E8%AF%B4%E6%98%8E.md" target="_blank" rel="noopener noreferrer">AgentForge MCP 方案设计与实现说明</a>

### Agent 核心设计实现

#### Agent 构建

##### 1）通用 Agent 配置设计实现

- [x] 核心基础通用 Agent 装配设计：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/agent%E6%9E%84%E5%BB%BA/Agent%E7%B1%BB%E5%9E%8B/%E9%80%9A%E7%94%A8agent/%E6%A0%B8%E5%BF%83%E5%9F%BA%E7%A1%80%E9%80%9A%E7%94%A8Agent%E8%A3%85%E9%85%8D%E8%AE%BE%E8%AE%A1%EF%BC%8C%E5%8C%85%E5%90%AB%E9%85%8D%E7%BD%AE%E6%A8%A1%E5%9E%8B%E3%80%81%E5%AF%B9%E6%8E%A5http%E3%80%81mcp%E6%8F%92%E4%BB%B6%E5%B7%A5%E5%85%B7.md" target="_blank" rel="noopener noreferrer">核心基础通用Agent装配设计，包含配置模型、对接http、mcp插件工具</a>
- [ ] 沙箱 Coding Agent 配置与扩展

##### 2）Agent 核心组成部分

- [x] 基于 MySQL 实现 Agent 的窗口会话记忆上下文持久化：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/agent%E6%9E%84%E5%BB%BA/Agent%E8%AE%B0%E5%BF%86/%E5%9F%BA%E4%BA%8EMySQL%E5%AE%9E%E7%8E%B0Agent%E7%9A%84%E7%AA%97%E5%8F%A3%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E4%B8%8A%E4%B8%8B%E6%96%87%E6%8C%81%E4%B9%85%E5%8C%96.md" target="_blank" rel="noopener noreferrer">基于MySQL实现Agent的窗口会话记忆上下文持久化</a>

#### Agent 页面会话对话交互

- [x] AgentForge 标准 Agent 会话与消息核心表设计：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/Agent%E9%A1%B5%E9%9D%A2%E4%BC%9A%E8%AF%9D%E5%AF%B9%E8%AF%9D%E4%BA%A4%E4%BA%92/AgentForge%E6%A0%87%E5%87%86Agent%E4%BC%9A%E8%AF%9D%E4%B8%8E%E6%B6%88%E6%81%AF%E6%A0%B8%E5%BF%83%E8%A1%A8%E8%AE%BE%E8%AE%A1.md" target="_blank" rel="noopener noreferrer">AgentForge标准Agent会话与消息核心表设计</a>
- [x] AgentForge Service 核心标准 Agent 事件协议设计定义：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/Agent%E9%A1%B5%E9%9D%A2%E4%BC%9A%E8%AF%9D%E5%AF%B9%E8%AF%9D%E4%BA%A4%E4%BA%92/agentforge-service%E6%A0%B8%E5%BF%83%E4%BA%8B%E4%BB%B6%E5%8D%8F%E8%AE%AE%E8%AE%BE%E8%AE%A1%E5%AE%9A%E4%B9%89.md" target="_blank" rel="noopener noreferrer">agentforge-service核心事件协议设计定义</a>
- [x] Agent 工具调用思考参数与前端优雅交互设计：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/Agent%E9%A1%B5%E9%9D%A2%E4%BC%9A%E8%AF%9D%E5%AF%B9%E8%AF%9D%E4%BA%A4%E4%BA%92/Agent%E5%B7%A5%E5%85%B7%E8%B0%83%E7%94%A8%E6%80%9D%E8%80%83%E5%8F%82%E6%95%B0%E4%B8%8E%E5%89%8D%E7%AB%AF%E4%BC%98%E9%9B%85%E4%BA%A4%E4%BA%92%E8%AE%BE%E8%AE%A1.md" target="_blank" rel="noopener noreferrer">Agent工具调用思考参数与前端优雅交互设计</a>
- [x] Agent 追加事件 + 插入 Agent Loop 对话：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/Agent%E9%A1%B5%E9%9D%A2%E4%BC%9A%E8%AF%9D%E5%AF%B9%E8%AF%9D%E4%BA%A4%E4%BA%92/Agent%E8%BF%BD%E5%8A%A0%E4%BA%8B%E4%BB%B6%20%2B%20%E6%8F%92%E5%85%A5Agent%20loop%E5%AF%B9%E8%AF%9D%E8%AE%BE%E8%AE%A1.md" target="_blank" rel="noopener noreferrer">Agent追加事件 + 插入Agent loop对话设计</a>
- [x] Agent 上下文阈值压缩交互设计：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/Agent%E9%A1%B5%E9%9D%A2%E4%BC%9A%E8%AF%9D%E5%AF%B9%E8%AF%9D%E4%BA%A4%E4%BA%92/Agent%20%E4%B8%8A%E4%B8%8B%E6%96%87%E9%98%88%E5%80%BC%E5%8E%8B%E7%BC%A9%E4%BA%A4%E4%BA%92%E8%AE%BE%E8%AE%A1.md" target="_blank" rel="noopener noreferrer">Agent 上下文阈值压缩交互设计</a>
  - 实现：`ServiceCompactSummarizationMiddleware` 将 Core 压缩钩子转为 `compact` SSE 事件，经 `ServiceStreamingMiddleware` 实时推送、`SessionLifecycleMiddleware` 落库回放；前端调试模式展示压缩前/后两条，发布模式合并为一条

#### Agent 版本控制与发布

- [x] 核心 Agent 版本设计与发布设计实现：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/Agent%E7%89%88%E6%9C%AC%E6%8E%A7%E5%88%B6%E4%B8%8E%E5%8F%91%E5%B8%83/Agent%E7%89%88%E6%9C%AC%E8%AE%BE%E8%AE%A1%E4%B8%8EAgent%E5%8F%91%E5%B8%83%E6%96%B9%E6%A1%88.md" target="_blank" rel="noopener noreferrer">Agent版本设计与Agent发布方案</a>
- [x] 核心 Agent 调试与发布对话设计与实现：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/Agent%E7%89%88%E6%9C%AC%E6%8E%A7%E5%88%B6%E4%B8%8E%E5%8F%91%E5%B8%83/%E6%A0%B8%E5%BF%83Agent%E8%B0%83%E8%AF%95%E4%B8%8E%E5%8F%91%E5%B8%83%E5%AF%B9%E8%AF%9D%E8%AE%BE%E8%AE%A1%E4%B8%8E%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">核心Agent调试与发布对话设计与实现</a>

## Agent 基础设施

### Agent Loop Trace 监控

- [x] Agent Loop Trace 业务层监测设计实现：<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E5%9F%BA%E7%A1%80%E8%AE%BE%E6%96%BD/agent%E9%93%BE%E8%B7%AF%E8%BF%BD%E8%B8%AA/Agent%E9%93%BE%E8%B7%AF%E8%BF%BD%E8%B8%AA%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0%EF%BC%88%E7%81%AB%E7%84%B0%E5%9B%BE%20%26%20%E6%B5%81%E7%A8%8B%E5%9B%BE%EF%BC%89.md" target="_blank" rel="noopener noreferrer">Agent链路追踪设计实现（火焰图 &amp; 流程图）</a>；<a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%89%8D%E7%AB%AF/agent%E5%9F%BA%E7%A1%80%E8%AE%BE%E6%96%BD/agent%E9%93%BE%E8%B7%AF%E8%BF%BD%E8%B8%AA/Agent%20Trace%E6%8E%A8%E7%90%86%E9%93%BE%E8%B7%AF%E7%94%BB%E5%B8%83%E7%BB%84%E4%BB%B6%E9%80%89%E5%9E%8B%E4%B8%8E%E8%AE%BE%E8%AE%A1%E6%96%B9%E6%A1%88.md" target="_blank" rel="noopener noreferrer">Agent Trace推理链路画布组件选型与设计方案</a>

### Agent Data Monitor 监控

- [ ] 核心通用 Agent Data Monitor 监控设计实现

### Agent 定时任务设计

- [ ] 标准 Agent 定时任务对接扩展

### Agent 渠道绑定对接设计

- [ ] 对接标准渠道【钉钉】
- [ ] 飞书 Bot 等渠道

## 未来扩展：基于 AgentForge 长出来的 Agent 应用

```text
                         +--------------------------------------+
                         |          agentforge-core             |
                         |                                      |
                         |          LLM Model Protocol          |
                         |                  |                   |
                         |            ReAct Agent               |
                         |                  |                   |
                         |           Harness Agent              |
                         +------------------+-------------------+
                                            |
                          +-----------------+-----------------+
                          |        agentforge-service         |
                          +-----------------+-------------------+
                                            |
                  +-------------------------+-------------------------+
                  |                         |                         |
                  v                         v                         v
               Platform                    CLI                     Desktop
```

1. **Platform（AgentForge-Platform）**：多用户、多租户运行时，支持纳管所有 Agent；
2. **CLI（AgentForge-CLI）**：本地 CLI Agent，对标 opencode、Codex、Claude Code；
3. **Desktop（AgentForge-Desktop）**：本地工作台，对标 WorkBuddy、Codex 桌面版；
4. **Harness Agent**：在 ReAct 之上提供权限、审批、沙箱与可恢复执行。

## 参与共建

- 功能建议与设计讨论：<a href="https://github.com/agentforge-tech/AgentForge/discussions" target="_blank" rel="noopener noreferrer">GitHub Discussions</a>
- Bug 与需求：<a href="https://github.com/agentforge-tech/AgentForge/issues" target="_blank" rel="noopener noreferrer">GitHub Issues</a>
- 贡献代码：<a href="../quickstart/contributing" target="_blank" rel="noopener noreferrer">贡献指南</a>

路线图会随实现进展持续更新，勾选状态以仓库最新代码为准。
