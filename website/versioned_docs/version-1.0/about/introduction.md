---
title: AgentForge 介绍
---
# AgentForge v1.0 介绍

AgentForge 是一个面向 Java 开发者、自底向上构建的开源 Agent Framework。它先统一 LLM 底层契约，再逐层构建工具调用、记忆、中间件、推理与 Agent Runtime。

> **Forge Intelligence into Action. 将智能锻造成行动。**

## 核心思路

Agent 的上层能力最终都会落到模型调用上。AgentForge 让上层只依赖统一的 `ChatModel` 与 `StreamingChatModel` 契约，避免业务代码与特定厂商 SDK 紧密绑定。

```text
LLM → Message / Request / Response → Context / Memory
    → Tool / Reasoning → Agent Runtime → Real Action
```

## v1.0 核心能力

- 统一同步与流式模型契约；
- OpenAI / OpenAI-compatible 与 Anthropic 协议适配；
- Function Calling、`@Tool` 注册与工具执行闭环；
- ReAct Agent、窗口记忆、中间件、重试与取消；
- 模型注册工厂与可替换的 HTTP Transport；
- Spring Boot + Vue 服务及统一 SSE 流式协议。

## 适合谁

AgentForge 适合希望在 Java 技术栈中构建模型接入层、业务工具调用、ReAct Agent 或企业 AI 助手，并需要掌控底层协议与运行时行为的开发者。

下一步：跟随[**快速开始**](../quickstart/getting-started.md)完成第一次构建与运行。
