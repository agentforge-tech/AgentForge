[toc]



# Agent框架：AgentForge

## 核心工程结构

本工程结构目录如下：

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
│   └── agentforge-harness-agent  # Harness 运行时（权限 / 审批 / 沙箱 / 可恢复执行）
├── agentforge-service            # 开箱即用服务（Vue 前端 + Spring Boot 后端）
│   ├── agentforge-service-ui     # Vue 2 + Vite 前端
│   └── agentforge-service-web    # Spring Boot 后端
├── agentforge-examples
├── pom.xml
└── README.md
```

**本工程初步设计核心两块部分：**

1）Agent框架层：提供一系列Agent的核心基础组件，涉及到ReAct核心层、链路监控、token计算、Agent Team等。

2）Agent 标准Service业务层（开箱即用可扩展），核心Agent一系列组件对接配置使用方式，相当于一个对接使用Agent框架的使用对接准则。【设计为单用户场景，非platform，若是platform】

## 核心功能能力扩展实现

### 模型协议层

**llm Model**：大语言模型层

- [x] OpenAI协议
- [x] Anthropic协议
- [ ] ...其他待续



**协议层抽象设计：**

- [x] 协议层function call & 执行器抽象层设计实现：https://blog.changlu.cloud/blog/520



**Embedding Model**：向量模型层

- [ ] OpenAI协议



### Agent 框架层

#### 核心ReAct框架组件

> **核心代码**：
>
> ​	ReAct Agent：[agentforge-agent-core](../../agentforge-framework/agentforge-agent-core)的src源码包cloud.changlu.agentforge.agent.ReActAgent
>
> ​    middleware设计：[agentforge-agent-core](../../agentforge-framework/agentforge-agent-core)的src源码包cloud.changlu.agentforge.agent.component.middleware

- [x] 核心ReAct 框架设计，支持非流式、流式 一套调用
- [x] middleware接口扩展实现



#### 外部协议转Function Call协议 & 执行

> **核心代码**： [agentforge-agent-core](../../agentforge-framework/agentforge-agent-core)的src源码包cloud.changlu.agentforge.agent.tool 

- [x] 支持本地Local Tool（Java代码）转换：
- [x] 支持HTTP外部接口转换
- [x] 支持MCP服务协议接口转换
- [ ] ...其他规划



#### 外部中断工具调用场景

- [ ] 中断事件，外部构造返回的tool工具调用结果（例如：askUser tool）
- [ ] 中断审核确认，支持两种确认、拒绝完成工具是否执行审核【例如：rm审批等，可见HITL部分】



#### 场景功能扩展（middlware）

- [ ] 日志middlware（支持快速本地调试、记录message内容UA情况）
- [ ] 上下文压缩middlware（支持策略来设计上下文压缩）
- [ ] Agent Trace middlware（Agent Loop 监控，包含token消耗记录）



#### Agent Loop稳定性优化

- [ ] 支持模型协议对话时进行异常支持动态重试（区分各类场景）





### Agent Harness

#### 沙箱运行时设计

- [ ] 支持docker控制远程沙箱协议（本机 & 外部）
- [ ] 支持对接E2B沙箱环境
- [ ] 支持对接K8S容器沙箱环境



#### Human in the Loop（审核确认机制）

- [ ] Agent审核权限设计
- [ ] 中断审核确认，Agent层提供标准的一套human in the loop审核确认（例如：权限审批工具拦截，外部注入配置对应的工具权限规则设定）





# 基于AgentForge长出来的Agent应用（平台、cli、桌面）

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

**核心未来扩展多形态：**

1）Platform【AgentForge-Platform】：AgentForge-Platform：提供标准的一套平台运行时，支持多用户 + 租户来进行绑定设计，支持对接纳管所有的agent系列

2）CLI【AgentForge-CLI】：提供核心标准的一套本地CLI Agent设计，对标cli为opencode、codex、claude。

3）Desktop【AgentForge-Desktop】：提供核心标准的一套本地工作台设计，对标workbuddy、codex桌面版设计。
