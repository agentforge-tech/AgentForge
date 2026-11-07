

# Agent框架：AgentForge-core

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

- [x] OpenAI协议：[OpenAI协议02、AgentForge 适配OpenAI接入核心实践](../dev/backend/AgentForge核心模块设计原理文章系列/01、model模型协议层/chatmodel/openai/OpenAI协议02、AgentForge%20适配OpenAI接入核心实践.md)
- [x] Anthropic协议：[Anthropic协议02、AgentForge 适配Anthropic接入核心实践](../dev/backend/AgentForge核心模块设计原理文章系列/01、model模型协议层/chatmodel/anthropic/Anthropic协议02、AgentForge%20适配Anthropic接入核心实践.md)
- [ ] ...其他待续



**协议层抽象设计：**

- [x] 协议层function call & 执行器抽象层设计实现：[统一ChatModel协议层02、Function Calling 工具调用转换与执行扩展实现：Tool 契约 + ToolService 执行循环](../dev/backend/AgentForge核心模块设计原理文章系列/01、model模型协议层/chatmodel/统一ChatModel协议层02、Function%20Calling%20工具调用转换与执行扩展实现：Tool%20契约%20%2B%20ToolService%20执行循环.md)



**Embedding Model**：向量模型层

- [ ] OpenAI协议



### Agent 框架层

#### 核心ReAct框架组件

> **核心代码**：
>
> ​	ReAct Agent：[agentforge-agent-core](../../agentforge-framework/agentforge-agent-core)的src源码包cloud.changlu.agentforge.agent.ReActAgent
>
> ​    middleware设计：[agentforge-agent-core](../../agentforge-framework/agentforge-agent-core)的src源码包cloud.changlu.agentforge.agent.component.middleware

- [x] 核心ReAct 框架设计，支持非流式、流式 一套调用：
  - 实现：`ReActAgent` 承载非流式主循环，`stream.ReActTokenStream` 承载流式主循环，二者共用 think→act→写回记忆 的步进语义与中断、步长上限控制 → `agentforge-agent-core` / `cloud.changlu.agentforge.agent`
  - [x] Agent state状态持久化设计：
  - [x] AgentForge Agent运行恢复设计方案：
  - [x] AgentForge ReAct Agent停止设计方案：
- [x] middleware接口扩展实现：
  - 实现：`IAgentMiddleware` 定义 loop、AI重试、工具执行等阶段回调，`IStreamingIAgentMiddleware` 承载流式事件，`AgentMiddlewareManager` 负责注册排序与分发 → `...agent.component.middleware`
- [x] agent标准会话记忆设计与实现：[Agent会话记忆接口设计与实现](../dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/会话记忆/Agent会话记忆接口设计与实现.md)







#### 外部协议转Function Call协议 & 执行

> **核心代码**： [agentforge-agent-core](../../agentforge-framework/agentforge-agent-core)的src源码包cloud.changlu.agentforge.agent.tool 

- [x] 支持本地Local Tool（Java代码）转换：[Java代码转换FunctionCall协议01、AgentForge Local工具模块核心设计原理](../dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/functioncall核心扩展业务tool/local/Java代码转换FunctionCall协议01、AgentForge%20Local工具模块核心设计原理.md)
  - 实现：`LocalToolFactory` 将本地方法映射为 `ToolSpecification`，`LocalToolExecutor` 负责入参转换与反射调用 → `...agent.tool.local`
- [x] 支持HTTP外部接口转换：[适配HTTP协议转换FunctionCall协议01、AgentForge HTTP工具模块核心设计原理](../dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/functioncall核心扩展业务tool/http/适配HTTP协议转换FunctionCall协议01、AgentForge%20HTTP工具模块核心设计原理.md)
  - 实现：`HttpToolFactory` 把 HTTP 插件定义转为工具描述，`HttpToolExecutor` 执行请求，另含 cURL ↔ HttpPlugin 双向转换 → `...agent.tool.http`
- [x] 支持MCP服务协议接口转换：[MCP协议转换FunctionCall协议02、MCP框架调研与AgentForge接入实现](../dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/functioncall核心扩展业务tool/mcp/MCP协议转换FunctionCall协议02、MCP框架调研与AgentForge接入实现.md)
  - 实现：`McpToolFactory` 拉取 MCP tools 并映射为 `ToolSpecification`，`McpToolExecutor` 走 JSON-RPC 调用，传输层支持 stdio 与 streamable-http → `...agent.tool.mcp`
- [ ] ...其他规划



**优化设计：**

- [x] 插件工具统一function call标准注入思考字段：[插件工具统一FunctionCall标准与思考字段注入设计](../dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/functioncall核心扩展业务tool/插件工具统一FunctionCall标准与思考字段注入设计.md)



#### 外部中断工具调用场景

- [ ] 中断事件，外部构造返回的tool工具调用结果（例如：askUser tool）
- [ ] 中断审核确认，支持两种确认、拒绝完成工具是否执行审核【例如：rm审批等，可见HITL部分】



#### 扩展实现-会话记忆

- [x] 窗口会话记忆扩展实现：[会话记忆扩展-窗口会话记忆设计与实现](../dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/会话记忆/会话记忆扩展-窗口会话记忆设计与实现.md)



#### 扩展线-middlware插件

- [x] 日志middlware（支持快速本地调试）：[内置middleware扩展：日志Loggingmiddlware实现](../dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/middleware/内置middleware扩展：日志Loggingmiddlware实现.md)
  - 实现：`LoggingIAgentMiddleware` 打印 loop、模型调用、工具执行关键节点并截断超长内容，按 memoryId 统计模型耗时、工具耗时与 token 消耗 → `...agent.extend.middlewares`
- [x] Agent Trace middlware（Agent Loop 监控，包含token消耗记录）：[内置middlware扩展：Agent Loop TraceMiddlware实现](../dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/middleware/内置middlware扩展：Agent%20Loop%20TraceMiddlware实现.md)
- [x] Agent 对话追加middleware（Agent Loop过程中追加消息）：[内置middleware扩展：追加对话到Agent.md](../dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/middleware/内置middleware扩展：追加对话到Agent.md)
- [x] 上下文压缩middleware（阈值比例压缩机制）：[内置middlware扩展：上下文压缩middlware（阈值比例压缩机制）](../dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/middleware/内置middlware扩展：上下文压缩middlware（阈值比例压缩机制）.md)
  - 实现：`CompactSummarizationMiddleware` 在 `beforeModelCall` 按阈值压缩历史并重建请求，在 `afterToolExecution` / `afterConcurrentToolExecution` 截断超大工具结果，通过 `CompactLifecycleHook` 向业务层发布压缩事件 → `...agent.extend.middlewares.compact`
- [x] 支持任务计划middleware：



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



# Agent基础服务：AgentForge Service

## 资源组件设计

### 模型配置

- [x] 核心模型统一协议层设计方案文档：[统一ChatModel协议层01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现](../dev/backend/AgentForge核心模块设计原理文章系列/01、model模型协议层/chatmodel/统一ChatModel协议层01、ChatModel%20核心协议层设计：统一%20API%20契约封装与多协议扩展实现.md)



### HTTP插件工具

- [x] 核心Agent插件之HTTP插件设计：[AgentForge Service 接入 HTTP 插件设计方案](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/我的资源/http插件/AgentForge%20Service%20接入%20HTTP%20插件设计方案.md)



### MCP插件服务

- [x] 核心Agent插件之MCP插件设计：[AgentForge MCP 方案设计与实现说明](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/我的资源/MCP插件/AgentForge%20MCP%20方案设计与实现说明.md)



## Agent核心设计实现

### Agent构建

#### Agent类型

##### 1）通用Agent配置设计实现

- [x] 核心基础通用Agent装配设计，包含配置模型、对接http、mcp插件工具：[核心基础通用Agent装配设计，包含配置模型、对接http、mcp插件工具](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/agent构建/Agent类型/通用agent/核心基础通用Agent装配设计，包含配置模型、对接http、mcp插件工具.md)




##### 2）沙箱Coding Agent配置设计实现

- [ ] 核心通用Agent配置设计 & 扩展实现

+ 场景：对接沙箱运行时，具备沙箱运行时的一套tool组件核心设计

- [ ] ... todo



#### Agent核心组成部分

- [x] 基于MySQL实现Agent的窗口会话记忆上下文持久化：[基于MySQL实现Agent的窗口会话记忆上下文持久化](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/agent构建/Agent记忆/基于MySQL实现Agent的窗口会话记忆上下文持久化.md)





### Agent页面会话对话交互

**会话交互设计：**

- [x] AgentForge标准Agent会话与消息核心表设计：[AgentForge标准Agent会话与消息核心表设计](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/AgentForge标准Agent会话与消息核心表设计.md)
- [x] agentforge-service核心标准agent事件协议设计定义：[agentforge-service核心事件协议设计定义](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/agentforge-service核心事件协议设计定义.md)

1）普通一次对话流式（一段式，无法恢复前端流式效果）：

- [x] AgentForge一段式流式对话（SSE长连接）设计实现：[AgentForge一段式流式对话（SSE长连接）设计实现](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/普通流式对话一段式设计实现/AgentForge一段式流式对话（SSE长连接）设计实现.md)

- [x] AgentForge一段式流式对话停止（signal）设计实现：[AgentForge一段式流式对话停止（signal）设计实现](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/普通流式对话一段式设计实现/AgentForge一段式流式对话停止（signal）设计实现.md)

2）支持并行恢复流式渲染对话（二段式）

- [x] AgentForge流式对话支持重连二段式设计方案：[AgentForge流式对话支持重连二段式设计方案](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/流式对话支持重连二段式设计实现/AgentForge流式对话支持重连二段式设计方案.md)

- [x] AgentForge二段式流式对话停止与超时取消设计方案：[AgentForge二段式流式对话停止与超时取消设计方案](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/流式对话支持重连二段式设计实现/AgentForge二段式流式对话停止与超时取消设计方案.md)



**交互优化**：

- [x] Agent工具调用思考参数与前端优雅交互设计：[Agent工具调用思考参数与前端优雅交互设计](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/Agent工具调用思考参数与前端优雅交互设计.md)
- [x] Agent追加事件 + 插入Agent loop对话交互设计：[Agent追加事件 + 插入Agent loop对话设计](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/Agent追加事件%20%2B%20插入Agent%20loop对话设计.md)
- [x] Agent支持任务计划todo列表展示：[Agent支持任务计划todo列表展示](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/Agent支持任务计划todo列表展示.md)



**长程任务对话优化：**

- [x] Agent 上下文阈值压缩交互设计：[Agent 上下文阈值压缩交互设计](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/Agent%20上下文阈值压缩交互设计.md)
  - 实现：`ServiceCompactSummarizationMiddleware` 将 Core 压缩钩子转为 `compact` SSE 事件，经 `ServiceStreamingMiddleware` 实时推送、`SessionLifecycleMiddleware` 落库回放；前端调试模式展示压缩前/后两条，发布模式合并为一条 → `...agent.middleware.compact`
- [ ] ...



### Agent版本控制与发布

- [x] 核心Agent版本设计与发布设计实现：[Agent版本设计与Agent发布方案](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/Agent版本控制与发布/Agent版本设计与Agent发布方案.md)
- [x] 核心Agent调试与发布对话设计与实现：[核心Agent调试与发布对话设计与实现](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/Agent版本控制与发布/核心Agent调试与发布对话设计与实现.md)



## Agent基础设施

核心对接Agent基础设施场景，如定时任务、三方渠道、Agent数据监控



### Agent Loop Trace监控

> 包含Agent的核心执行链路追踪场景

- [x] Agent Loop Trace业务层监测设计实现：[Agent链路追踪设计实现（火焰图 & 流程图）](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent基础设施/agent链路追踪/Agent链路追踪设计实现（火焰图%20%26%20流程图）.md)；[Agent Trace推理链路画布组件选型与设计方案](../dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/前端/agent基础设施/agent链路追踪/Agent%20Trace推理链路画布组件选型与设计方案.md)



### Agent Data Monitor 监控

> 包含Agent的执行记录、执行耗时、消耗token情况等

- [ ] 核心通用Agent Data Monitor 监控设计实现



### Agent 定时任务设计

> 包含Agent的定时任务对接方式

- [ ] 标准Agent定时任务对接扩展



### Agent 渠道绑定对接设计

- [ ] 对接标准渠道【钉钉】
- [ ] ...飞书bot等渠道





# 未来扩展：基于AgentForge长出来的Agent应用（平台、cli、桌面）

```text
+--------------------------------------------------------------------------------------+
|                         agentforge-core / agentforge-harness                         |
|                                                                                      |
|   LLM Model Protocol        Permission        HITL           Runtime                 |
|          |                      |               |               |                     |
|          +----------+-----------+---------------+               |                     |
|                     v                                           v                     |
|               ReAct Agent  -------------------------------->  Harness Agent           |
|                                                                                      |
+---------------------------------------+----------------------------------------------+
                                        |
              +-------------------------+-------------------------+
              |                         |                         |
              v                         |                         |
+-------------------------------+       |                         |
|     agentforge-service        |       |                         |
|  (可水平部署 / 多实例)         |       |                         |
+---------------+---------------+       |                         |
                |                       |                         |
                v                       v                         v
        +-------------+         +-------------+           +-------------+
        |  Platform   |         |     CLI     |           |   Desktop   |
        +-------------+         +-------------+           +-------------+
```

**核心未来扩展多形态：**

1）Platform【AgentForge-Platform】：AgentForge-Platform：提供标准的一套平台运行时，支持多用户 + 租户来进行绑定设计 会直接对接agentforge-service，支持对接纳管所有的agent系列

2）CLI【AgentForge-CLI】：基于agentforge-core/agentforge-harness，来提供核心标准的一套本地CLI Agent设计，对标cli为opencode、codex、claude。

3）Desktop【AgentForge-Desktop】：基于agentforge-core/agentforge-harness，来提供核心标准的一套本地工作台设计，对标workbuddy、codex桌面版设计。
