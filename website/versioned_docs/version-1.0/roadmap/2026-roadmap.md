---
title: 2026 年 AgentForge RoadMap
sidebar_label: 2026 年 RoadMap
description: AgentForge 2026 年度技术路线图与核心能力规划
toc_max_heading_level: 4
---

# 2026 年 AgentForge RoadMap

AgentForge 以“自底向上”的方式构建 Java Agent 技术栈：先稳定模型协议与核心契约，再逐层扩展 ReAct Agent、Harness Runtime 以及不同形态的 Agent 应用。

## 核心工程结构

```text
AgentForge
├── agentforge-ai-parent          # 统一依赖与插件版本管理
├── agentforge-ai-bom             # 统一 BOM 坐标维护
├── agentforge-model              # 模型层
│   ├── agentforge-model-api       # Provider 无关的模型契约与统一 req/vo
│   ├── agentforge-model-core      # 无厂商依赖的默认实现与执行引擎
│   ├── agentforge-model-openai    # OpenAI / OpenAI-compatible Provider
│   ├── agentforge-model-anthropic # Anthropic Provider
│   └── agentforge-model-registry  # 开箱即用的 ChatModel 工厂
├── agentforge-framework
│   ├── agentforge-agent-core      # ReAct Agent 运行时
│   └── agentforge-harness-agent   # Harness 运行时
├── agentforge-service            # Vue 前端 + Spring Boot 后端
│   ├── agentforge-service-ui
│   └── agentforge-service-web
└── agentforge-examples           # 参考示例
```

工程初步划分为两个核心部分：

1. **Agent 框架层**：提供 ReAct、工具调用、上下文、链路监控、Token 计算与 Agent Team 等基础能力。
2. **Agent 标准服务层**：提供开箱即用、可扩展的框架对接方式，作为 Agent 组件的标准使用与服务化参考。

## 核心功能能力

### 模型协议层

#### LLM Model

- [x] OpenAI 协议
- [x] Anthropic 协议
- [ ] 扩展更多模型协议

#### 协议抽象

- [x] Function Calling 协议层与执行器抽象设计

#### Embedding Model

- [ ] OpenAI 协议

### Agent 框架层

#### 核心 ReAct 框架组件

**核心代码：**

- ReAct Agent：[`agentforge-agent-core`](https://github.com/agentforge-tech/AgentForge/tree/main/agentforge-framework/agentforge-agent-core) 中的 `cloud.changlu.agentforge.agent.ReActAgent`。
- Middleware：`agentforge-agent-core` 中的 `cloud.changlu.agentforge.agent.component.middleware`。

- [x] ReAct 核心框架，统一支持非流式与流式调用
- [x] Middleware 接口与扩展机制

#### 外部协议转 Function Calling 及执行

**核心代码：** `agentforge-agent-core` 中的 `cloud.changlu.agentforge.agent.tool`。

- [x] Local Tool：将 Java 代码转换为可调用工具
- [x] HTTP Tool：将外部 HTTP 接口转换为可调用工具
- [x] MCP Tool：将 MCP 服务协议转换为可调用工具
- [ ] 扩展其他工具协议

#### 外部中断与工具调用

- [ ] 支持中断事件，允许外部构造工具调用结果，例如 `askUser` Tool
- [ ] 支持工具执行前的确认、拒绝与审核

#### Middleware 场景扩展

- [ ] 日志 Middleware：支持本地调试与 Message 内容记录
- [ ] 上下文压缩 Middleware：支持可配置的上下文压缩策略
- [ ] Agent Trace Middleware：监控 Agent Loop 并记录 Token 消耗

#### Agent Loop 稳定性

- [ ] 根据不同异常场景，支持模型对话动态重试

### Agent Harness

#### 沙箱运行时

- [ ] 支持 Docker 控制本机与远程沙箱协议
- [ ] 支持 E2B 沙箱环境
- [ ] 支持 Kubernetes 容器沙箱环境

#### Human in the Loop

- [ ] Agent 工具权限与审核规则设计
- [ ] 提供标准的 Human in the Loop 审核确认机制

## 基于 AgentForge 的 Agent 应用

```text
                         +--------------------------------------+
                         |              AgentForge              |
                         |                                      |
                         |          LLM Model Protocol          |
                         |                  |                   |
                         |            ReAct Agent               |
                         |                  |                   |
                         |           Harness Agent              |
                         |                                      |
                         +------------------+-------------------+
                                            |
                  +-------------------------+-------------------------+
                  |                         |                         |
                  v                         v                         v
               Platform                    CLI                     Desktop
```

### Platform

**AgentForge Platform** 计划提供标准的平台运行时，支持多用户、多租户与 Agent 统一纳管。

### CLI

**AgentForge CLI** 计划提供标准的本地 CLI Agent，为开发者提供可扩展的终端智能助手。

### Desktop

**AgentForge Desktop** 计划提供本地 Agent 工作台，承载任务、工具、运行记录与人机协作体验。
