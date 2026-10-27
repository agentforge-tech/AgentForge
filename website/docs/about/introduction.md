---
title: AgentForge 介绍
sidebar_label: AgentForge 介绍
description: 了解 AgentForge 的定位、设计理念与模块结构
---
# AgentForge 介绍

AgentForge 是一个面向 Java 开发者的开源 Agent Framework。它从 **LLM 底层协议与模型抽象** 出发，逐层构建工具调用、上下文、记忆、中间件、推理与 Agent Runtime，让应用可以在不绑定单一模型厂商的前提下，把模型能力转化为可执行的任务。

> **Forge Intelligence into Action. 将智能锻造成行动。**

`AgentForge = Agent + Forge`：`Agent` 代表能理解目标、思考、调用工具并完成任务的智能体；`Forge` 强调通过工程化加工、组合和强化，把模型的原始智能锻造成可靠的应用能力。

## 为什么选择自底向上

Agent 的记忆、工具、规划和执行循环，最终都会落到模型请求与响应上。AgentForge 先稳定 `ChatModel` 与 `StreamingChatModel` 等通用契约，再向上组合 Agent 能力，避免业务代码与特定厂商 SDK 紧密绑定。

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

这种分层方式让开发者可以独立替换模型、传输实现、工具系统或 Agent 执行策略，不必重写整个应用。

## 核心能力

- **统一模型抽象**：统一消息、请求、响应、同步与流式调用契约。
- **多模型协议适配**：提供 OpenAI / OpenAI-compatible 与 Anthropic 实现，上层代码保持厂商中立。
- **Tool Calling**：支持 `@Tool` 反射注册、Function Calling 转换、工具执行与多轮闭环。
- **ReAct Agent Runtime**：支持同步与流式执行、窗口记忆、中间件、重试与任务取消。
- **Harness Runtime**：扩展权限、工具审批、沙箱、追踪与可恢复执行能力。
- **开箱即用的服务层**：提供 Spring Boot 后端、Vue 前端与统一 SSE 流式事件协议。

## 模块结构

| 模块 | 主要职责 |
| --- | --- |
| `agentforge-model-api` | 模型、消息、请求、响应、工具与 Transport SPI 契约 |
| `agentforge-model-core` | 默认实现、HTTP Transport、JSON、`@Tool` 执行与推理-工具循环 |
| `agentforge-model-openai` | OpenAI Chat Completions、流式输出与 Function Calling |
| `agentforge-model-anthropic` | Anthropic Messages API、流式输出与 Tool Use |
| `agentforge-model-registry` | 模型配置、注册与 `LlmFactory` 工厂 |
| `agentforge-agent-core` | ReAct Agent、Memory、Middleware、重试与取消 |
| `agentforge-harness-agent` | 权限、审批、沙箱、追踪与可恢复执行 |
| `agentforge-service` | Vue + Spring Boot 可运行服务与统一 SSE 协议 |
| `agentforge-examples` | 模型、工具与 Agent 的参考示例 |

## 设计原则

1. **自底向上**：先稳定 LLM 与消息契约，再构建 Agent 上层能力。
2. **厂商中立**：核心业务不与单一模型协议耦合。
3. **模块化**：抽象、Provider、Agent Runtime 和服务层可独立演进。
4. **轻量可嵌入**：控制底层依赖，可用于 Spring Boot、普通 Java 与桌面应用。
5. **面向生产**：不只停留在 Demo，持续完善可控、可观测、可恢复的执行能力。

## 适用场景

AgentForge 适合需要在 Java 技术栈中构建模型接入层、业务工具调用、ReAct Agent、企业内部 AI 助手或可控 Agent 平台的项目。如果你希望深入理解模型协议、掌控运行时行为，或按需替换底层组件，这种架构会尤其合适。

准备开始时，请继续阅读[**快速开始**](../quickstart/getting-started.md)；如果想了解底层设计与具体实现，可前往[**AgentForge 博客**](/blog)。
