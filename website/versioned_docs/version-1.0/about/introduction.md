---
title: 认识 AgentForge
sidebar_label: 认识AgentForge
description: 了解 AgentForge 的定位、分层设计、核心模块与最短上手路径
sidebar_position: 1
displayed_sidebar: sdkSidebar
---

# 认识 AgentForge

AgentForge 是一个面向 Java 开发者的开源 Agent Framework。它从 **LLM 协议与模型抽象** 出发，逐层构建工具调用、会话记忆、中间件、推理循环与 Agent Runtime，让应用可以在不绑定单一模型厂商的前提下，把模型能力转化为可执行的任务。

> **Forge Intelligence into Action. 将智能锻造成行动。**

`AgentForge = Agent + Forge`：`Agent` 代表能够理解目标、思考、调用工具并完成任务的智能体；`Forge` 强调通过工程化加工、组合与强化，把模型的原始智能锻造成可靠的应用能力。

## 最新依赖

当前框架版本为 **1.0.0**。推荐通过 BOM 统一管理版本：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>cloud.changlu.agentforge</groupId>
            <artifactId>agentforge-ai-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

随后按需引入模块，无需再写版本号：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
</dependency>
```

如果只想快速跑一个例子，可以直接引入 `agentforge-agent-core`，它会传递引入 `agentforge-model-api` 与 `agentforge-model-core`。

## 为什么选择自底向上

Agent 的记忆、工具、规划和执行循环，最终都会落到模型请求与响应上。AgentForge 先稳定 `ChatModel`、`StreamingChatModel`、`ChatMessage`、`ChatResponse` 等通用契约，再向上组合 Agent 能力，避免业务代码与特定厂商 SDK 紧密绑定。

```text
LLM Provider
    ↓  Message / Request / Response
    ↓  Context / Memory
    ↓  Tool / Skill / MCP
    ↓  Reasoning / Planning
    ↓  Agent Runtime
    ↓  Multi-Agent / Sandbox / Observability
Real Action
```

这种分层方式带来四个直接收益：

1. **Provider-neutral**：上层 Agent 代码只依赖统一契约，换模型时不需要改主循环；
2. **协议适配下沉**：OpenAI / Anthropic 的字段、Header、SSE、finish reason 差异全部封装在 Provider 模块；
3. **可独立替换**：模型、传输、工具、记忆、中间件都可以按需替换；
4. **开箱即用**：既可以直接写代码嵌入 Java 应用，也可以通过 AgentForge Service 一键启动完整工作台。

## 工程结构

```text
AgentForge
├── agentforge-ai-parent          # 统一依赖与插件版本管理
├── agentforge-ai-bom             # 统一 BOM 坐标维护
├── agentforge-model              # 模型层
│   ├── agentforge-model-api      # Provider 无关的模型契约
│   ├── agentforge-model-core     # 默认实现、HTTP 传输与 Tool 执行引擎
│   ├── agentforge-model-openai   # OpenAI / OpenAI-compatible Provider
│   ├── agentforge-model-anthropic# Anthropic Provider
│   └── agentforge-model-registry # 开箱即用的 ChatModel 工厂
├── agentforge-framework
│   ├── agentforge-agent-core     # ReAct Agent 运行时
│   └── agentforge-harness-agent  # Harness 运行时（规划中）
├── agentforge-service            # Vue 前端 + Spring Boot 后端
└── agentforge-examples           # 参考示例
```

## 核心能力

| 能力 | 说明 | 入口 |
| --- | --- | --- |
| 统一模型协议 | 一套 `ChatRequest` / `ChatResponse` 适配 OpenAI、Anthropic 与 OpenAI-compatible 服务 | [Model](../sdk/model) |
| ReAct Agent | 同步与流式共用一套 think → act → 记忆写回内核 | [Agent 智能体](../sdk/agent) |
| 消息与事件 | 统一 Message 模型、流式回调与工具执行事件 | [消息事件](../sdk/message-event) |
| Middleware | 在模型调用、工具执行、Loop 生命周期挂载自定义逻辑 | [Middleware 设计](../sdk/middleware) |
| Tool 扩展 | 本地 Java 方法、HTTP 插件、MCP 服务统一转换为 Function Call | [Tool 扩展设计](../sdk/tool) |
| Service 工作台 | 模型配置、Agent 调试、版本发布、会话与链路追踪 | [Service](#service-工作台) |

## 一次最小调用

```java
ChatModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .build();

String answer = model.chat("用一句话解释什么是 ReAct Agent。");
System.out.println(answer);
```

需要工具与多轮推理时，再叠加 `ReActAgent`：

```java
ToolService toolService = new ToolService();
toolService.tools(new WeatherTools());

ReActAgent agent = ReActAgent.builder()
        .agentName("weather-agent")
        .systemPrompt("你是一个天气助手，需要时调用工具。")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .build();

ChatResult result = agent.run(AgentRequest.builder()
        .memoryId("user-1")
        .question("北京今天天气怎么样？")
        .build());
```

## 选择你的阅读路径

- **第一次接触**：从 [快速开始](../quickstart/getting-started) 跑通第一个 Agent；
- **想理解模型层**：阅读 [Model 模型协议层](../sdk/model)；
- **想搭建完整 Agent**：阅读 [Agent 智能体](../sdk/agent) 与 [Tool 扩展设计](../sdk/tool)；
- **想做可观测与治理**：阅读 [Middleware 设计](../sdk/middleware) 与 [消息事件](../sdk/message-event)；
- **想直接使用成品工作台**：访问 [AgentForge Service](../service/introduction)。

## Service 工作台

AgentForge Service 是框架之上的一套开箱即用工作台，打通了从模型配置到正式发布的完整闭环：

```text
模型配置 → Agent 配置 → 调试 → 版本 → 发布 → 对话与会话历史
```

它适合希望快速体验 AgentForge 能力、又不想从零搭建前后端的团队。详见 [Service 介绍](../service/introduction)。
