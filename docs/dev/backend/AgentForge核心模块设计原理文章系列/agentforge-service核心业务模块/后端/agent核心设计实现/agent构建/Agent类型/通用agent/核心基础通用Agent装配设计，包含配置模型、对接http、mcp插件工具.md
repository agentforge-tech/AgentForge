# 核心基础通用 Agent 装配设计：配置模型、HTTP 工具与 MCP 插件工具

> 适用版本：release_1.x
> 适用模块：`agentforge-service/agentforge-service-web`
> 包前缀：`cloud.changlu.agentforge.service.modules.agent`
> 维护者：长路
> 系列定位：本文属于「AgentForge 核心模块设计原理文章系列 / agentforge-service 核心业务模块 / 后端 / agent 基础设施 / agent-core」。

一句话结论：

> **框架层只回答「Agent 怎么跑」，业务层回答「用哪个模型跑、挂哪些工具跑、实例从哪里来」；`ask` 与 `stream` 最终都落到同一个 `ReActAgent` 的 `run()` / `runStream()`。**

<br/>

## 文档导航

| 文档 | 说明 |
|---|---|
| 当前文章 | 业务层的配置模型、`ReActAgentFactory` 装配、HTTP / MCP 工具加载、`AgentManagerFactory` 分发与扩展 |
| [核心 ReAct 框架设计：非流式与流式一套调用](../../../../../../02、通用ReActagent层/核心ReAct%20框架设计，支持非流式、流式%20一套调用.md) | 第 02 篇：`Agent.run()` / `ReActTokenStream` 如何共用 `think → act` 内核 |
| [适配 HTTP 协议转换 FunctionCall 协议](../../../../../../02、通用ReActagent层/functioncall核心扩展业务tool/http/适配HTTP协议转换FunctionCall协议01、AgentForge%20HTTP工具模块核心设计原理.md) | HTTP 工具模块的声明式建模与执行器细节 |
| [MCP 框架调研与 AgentForge 接入实现](../../../../../../02、通用ReActagent层/functioncall核心扩展业务tool/mcp/MCP协议转换FunctionCall协议02、MCP框架调研与AgentForge接入实现.md) | MCP 协议、传输层与工具发现 |
| [初版核心模型 & Agent 构建设计](../../../../初版核心模型%20&%20agent构建设计.md) | `ai_agent_config` / `ai_model_config` 两张核心表 |

<br/>

## 目录

- [一、背景：内核有了，业务缺什么](#一背景内核有了业务缺什么)
- [二、总体分层：内核、包装、分发](#二总体分层内核包装分发)
- [三、配置模型：把 Agent 能力变成可持久化 JSON](#三配置模型把-agent-能力变成可持久化-json)
- [四、装配核心：`ReActAgentFactory` 做了什么](#四装配核心reactagentfactory-做了什么)
- [五、HTTP 插件工具：从选择关系到 Function Calling](#五http-插件工具从选择关系到-function-calling)
- [六、MCP 插件工具：构建期动态发现](#六mcp-插件工具构建期动态发现)
- [七、业务层如何扩展 Agent 实现](#七业务层如何扩展-agent-实现)
- [八、复用核心 ReAct：业务层不重复写循环](#八复用核心-react业务层不重复写循环)
- [九、缓存与失效：实例复用、会话隔离](#九缓存与失效实例复用会话隔离)
- [十、边界与设计取舍](#十边界与设计取舍)
- [十一、总结](#十一总结)

---

## 一、背景：内核有了，业务缺什么

在框架层，`agentforge-agent-core` 已经把 ReAct 内核做得相当完整：

```text
IAgent
  └── BaseAgent
        └── Agent                  run() / runStream() 模板方法
              └── BaseReActAgent   step() / stepStream()
                    └── ReActAgent builder() 装配模型、记忆、工具、中间件
```

它对外只暴露两个执行入口：

```java
ChatResult run(AgentRequest request);
TokenStream runStream(AgentRequest request);
```

但要把这套内核变成「可创建、可发布、可对话」的产品能力，业务层至少要补齐四件事：

1. **配置可持久化**：模型、提示词、记忆、工具不能写死在代码里，要能存在 `ai_agent_config.agent_config` 这样的 JSON 字段中；
2. **能力可装配**：配置里的「插件选择关系」要能在运行时还原成真实的 `ToolSpecification + ToolExecutor`；
3. **实例可管理**：同一个 Agent 配置会被多次对话复用，需要缓存、按调试/正式隔离、可失效；
4. **类型可扩展**：今天是 `StandardReActAgent`，明天可能是 Plan Agent、Workflow Agent，新增类型不能改对话入口。

业务层的全部设计，都是围绕这四个问题展开的。

<br/>

## 二、总体分层：内核、包装、分发

业务层与框架层的关系可以概括成一条很清晰的边界：

```text
┌────────────────────────────────────────────────────────────┐
│ 接入层  AgentChatController(/api/agent/chat)               │
│         ChatController(/api/chat/stream)                   │
└───────────────────────────┬────────────────────────────────┘
                            │ 按 type 取 Manager
┌───────────────────────────▼────────────────────────────────┐
│ 分发层  AgentManagerFactory                                │
│         Map<type, IAgentManager>                           │
└───────────────────────────┬────────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────────┐
│ 运行时  StandardReActAgentManager                          │
│         AbstractAgentManager（缓存 / 创建 / 调试隔离）      │
└───────────────────────────┬────────────────────────────────┘
                            │ 创建
┌───────────────────────────▼────────────────────────────────┐
│ 装配层  ReActAgentFactory                                  │
│         配置模型 + 模型工厂 + 记忆工厂 + 工具加载器 + 中间件 │
└───────────────────────────┬────────────────────────────────┘
                            │ builder()
┌───────────────────────────▼────────────────────────────────┐
│ 内核层  ReActAgent（run / runStream）                      │
│         think → act → 记忆 / 工具 / 中间件 / 取消           │
└────────────────────────────────────────────────────────────┘
```

对应到代码，四个关键类各司其职：

| 类 | 职责 | 关键词 |
|---|---|---|
| `StandardAgentConfig` 等配置类 | 描述 Agent 可配置能力 | 持久化、JSON |
| `ReActAgentFactory` | 把配置装配成 `ReActAgent` | 模型、记忆、工具、中间件 |
| `AbstractAgentManager` / `StandardReActAgentManager` | 创建、缓存、执行、取消 | `aid`、调试隔离 |
| `AgentManagerFactory` | 按 `type` 分发到具体 Manager | 可扩展、Spring 注入 |

一个重要的分层原则是：**业务层不重新实现 ReAct 循环，只做「装配」和「编排」。** 循环、状态机、工具执行、取消、重试仍然由框架层的 `Agent` / `BaseReActAgent` / `ReActTokenStream` 负责。

<br/>

## 三、配置模型：把 Agent 能力变成可持久化 JSON

### 3.1、配置对象的组成

`ai_agent_config.agent_config` 落库的是一段 JSON，反序列化后的根对象是 `StandardAgentConfig`：

```java
public class StandardAgentConfig {
    private String systemPrompt;            // 系统提示词
    private AgentModelConfig chatModel;     // 对话模型引用
    private ChatMemoryConfig chatMemoryConfig; // 记忆配置
    private PluginConfig pluginConfig;      // 插件（HTTP / MCP）选择关系
}
```

它下面挂四组子配置：

```text
StandardAgentConfig
├── systemPrompt
├── AgentModelConfig
│     ├── llm_model_id        // 引用 ai_model_config.id
│     ├── maxInputToken
│     ├── maxOutputToken
│     └── params
├── ChatMemoryConfig
│     ├── type = 1            // 记忆类型，当前为窗口记忆
│     └── memoryConfig        // 记忆自己的 JSON 配置
└── PluginConfig
      ├── httpPlugins[]  -> HttpToolConfig
      │     ├── pluginId
      │     └── pluginMethodIds[]
      └── mcpPlugins[]   -> McpToolConfig
            ├── pluginId
            └── serverIds[]
```

对应一段真实配置大致是：

```json
{
  "systemPrompt": "你是一个严谨的技术助手……",
  "chatModel": {
    "llm_model_id": 12,
    "maxInputToken": 32000,
    "maxOutputToken": 4096
  },
  "chatMemoryConfig": {
    "type": 1,
    "memoryConfig": "{\"maxMessage\": 1000}"
  },
  "pluginConfig": {
    "httpPlugins": [
      { "pluginId": 3, "pluginMethodIds": [101, 102] }
    ],
    "mcpPlugins": [
      { "pluginId": 7, "serverIds": [21, 22] }
    ]
  }
}
```

### 3.2、三个关键设计点

**第一，模型只存引用，不存密钥。**

`AgentModelConfig` 里只有 `llm_model_id`，真正的 `url` / `api_key` / `modelname` 存在 `ai_model_config`。这样模型配置可以集中管理、复用，Agent 配置本身不携带敏感信息。

**第二，HTTP 与 MCP 都只存「选择关系」。**

- `HttpToolConfig` 只记录 `pluginId + pluginMethodIds`；
- `McpToolConfig` 只记录 `pluginId + serverIds`。

HTTP 的方法清单、参数定义在插件表里；MCP 更是连工具清单都不落库——**工具在 Agent 构建时通过 `tools/list` 动态发现**。这带来两个直接好处：

- 插件侧新增/下线方法，不需要回写 Agent 配置；
- MCP Server 的工具变化，下次构建 Agent 时自动生效。

**第三，配置与运行分离。**

`agent_config` 只是「蓝图」。每次 `getAgentAndInitAgent` 命中/未命中缓存时，才由 `ReActAgentFactory` 把它装配成运行态对象。运行态对象不反向修改配置，便于热更新与多实例隔离。

`StandardAgentConfig` 自带 `toJson` / `fromJson`，统一用 Spring 的 `ObjectMapper` 做序列化，解析失败会抛出 `IllegalArgumentException`，避免脏配置进入运行时。

<br/>

## 四、装配核心：`ReActAgentFactory` 做了什么

`ReActAgentFactory` 是整篇文章的「装配总线」。它的输入是一份 `StandardAgentConfig`，输出是一个可直接运行的 `ReActAgent`。

### 4.1、装配流程

```text
StandardAgentConfig
   │
   ├─ 1. 校验 chatModel 非空
   ├─ 2. 按 llm_model_id 查 ai_model_config
   ├─ 3. ModelConfigTransfer.toBasicConfig  -> LlmBasicConfig
   ├─ 4. 覆盖 ChatModelProperties（timeout / maxTokens / returnThinking）
   ├─ 5. LlmFactory.buildChatModel        -> ChatModel
   ├─ 6. LlmFactory.buildStreamChatModel  -> StreamingChatModel
   ├─ 7. ChatMemoryProviderFactory        -> ChatMemoryProvider
   ├─ 8. buildToolService                 -> ToolService
   ├─ 9. 注册中间件（日志 / 会话 / 流式 / Trace）
   └─ 10. builder().build()               -> ReActAgent
```

核心代码（节选）：

```java
public ReActAgent createReActAgent(StandardAgentConfig standardAgentConfig) {
    if (standardAgentConfig == null || standardAgentConfig.getChatModel() == null) {
        throw new IllegalArgumentException("StandardAgentConfig.chatModel 不能为空");
    }
    AgentModelConfig chatModelConfig = standardAgentConfig.getChatModel();
    ModelConfigPojo modelConfig = modelConfigMapper.selectById(chatModelConfig.getLlmModelId());
    if (modelConfig == null) {
        throw new IllegalArgumentException("对话模型不存在或已被删除");
    }

    LlmBasicConfig llmConfig = ModelConfigTransfer.toBasicConfig(modelConfig);

    ChatModelProperties chatModelProperties =
            ChatModelProperties.builder()
                    .timeout(2 * 60 * 60)
                    .maxTokens(chatModelConfig.getMaxOutputToken())
                    .returnThinking(true)
                    .build();
    llmConfig.setProps(chatModelProperties.toProperties());

    return ReActAgent.builder()
            .agentName("StandardReActAgent")
            .systemPrompt(standardAgentConfig.getSystemPrompt())
            .chatModel(LlmFactory.buildChatModel(llmConfig))
            .streamingChatModel(LlmFactory.buildStreamChatModel(llmConfig))
            .chatMemoryProvider(
                    chatMemoryProviderFactory.getChatMemoryProvider(
                            standardAgentConfig.getChatMemoryConfig()))
            .middleware(new LoggingIAgentMiddleware())
            .middleware(sessionLifecycleMiddleware)
            .middleware(streamingMiddleware)
            .middleware(agentTraceMiddleware)
            .toolService(buildToolService(standardAgentConfig))
            .build();
}
```

### 4.2、为什么同时构建 `ChatModel` 和 `StreamingChatModel`

这是「一套调用」在业务层的落点。

框架层的 `ReActAgent` 同时持有两个驱动器所需的模型：

- `run()` 走 `ChatModel.chat(...)`；
- `runStream()` 走 `StreamingChatModel`，由 `ReActTokenStream` 驱动。

两者由**同一份 `LlmBasicConfig`** 构建，因此模型地址、密钥、参数、思考开关完全一致。业务层不需要为流式单独维护一套模型配置，只需要在工厂里多调一次 `buildStreamChatModel`。

### 4.3、模型参数的业务化覆盖

`modelConfig` 本身可以带通用参数，但业务层在这里做了三处显式覆盖：

| 参数 | 值 | 原因 |
|---|---|---|
| `timeout` | 2 小时 | 长任务、长思考链路需要足够长的读超时 |
| `maxTokens` | `AgentModelConfig.maxOutputToken` | 由 Agent 配置控制输出上限 |
| `returnThinking` | `true` | 默认返回思考内容，供前端展示与 Trace 记录 |

这是一种「模型配置提供底座、Agent 配置覆盖行为」的组合方式，避免每个 Agent 都复制一份模型配置。

### 4.4、中间件的业务化装配

工厂里注册了四个中间件，顺序即执行顺序：

```text
LoggingIAgentMiddleware      // 日志：每轮模型/工具调用
SessionLifecycleMiddleware   // 会话：AI 消息状态推进、事件聚合、一次性收口
ServiceStreamingMiddleware   // 流式：把核心回调转成 SSE 事件
AgentForgeTraceMiddleware    // 链路：记录 Trace 快照
```

这里体现了框架层中间件机制的价值：**核心内核不知道「会话表」「SSE」「Trace」的存在，业务能力全部通过中间件挂上去。** 非流式与流式共用同一套回调协议，所以这四个中间件在两条路径上都能工作。

### 4.5、`AgentSettings` 的缺口与扩展点

当前工厂没有显式设置 `agentSettings`，因此 `ReActAgent` 使用 `AgentSettings.defaultSettings()`：

| 配置 | 默认值 |
|---|---|
| `maxSteps` | 10 |
| `aiCallRetry` | 3 |
| `aiCallRetryDelay` | 2000ms |
| `enableConcurrentToolExecution` | true |

如果未来需要按 Agent 配置步长、重试、并发开关，最自然的扩展方式是：在 `StandardAgentConfig` 增加 `agentSettings` 字段，然后在工厂的 builder 链上补一行 `.agentSettings(...)`。**核心内核不需要任何改动。**

<br/>

## 五、HTTP 插件工具：从选择关系到 Function Calling

HTTP 工具的完整建模在框架层（见 HTTP 工具模块文章），业务层要解决的是「**怎么把用户在页面上勾选的插件方法，还原成当前 Agent 的工具表**」。

### 5.1、加载入口

`ReActAgentFactory.buildToolService` 里：

```java
PluginConfig pluginConfig = standardAgentConfig.getPluginConfig();

Map<ToolSpecification, ToolExecutor> httpTools =
        httpAgentToolLoader.loadWithThought(
                pluginConfig == null ? null : pluginConfig.getHttpPlugins(), true);
if (!httpTools.isEmpty()) {
    toolService.tools(httpTools);
}
```

`withThought = true` 表示给每个工具 Schema 追加必填的「思考参数」，让模型在调用工具前先说明意图。这个能力由 `ToolSpecificationThoughts.withThought(...)` 统一处理，HTTP 与 MCP 共用。

### 5.2、`HttpAgentToolLoader` 的加载链路

```text
loadWithThought(configs, true)
  │
  ├─ configs 为空 -> 空表
  ├─ 遍历每个 HttpToolConfig
  │    ├─ pluginId 为空 -> 跳过
  │    ├─ 查 ai_plugin_http：不存在 / 未启用 -> 跳过
  │    ├─ resolveMethods：
  │    │    ├─ pluginMethodIds 为空 -> 取该插件全部启用方法
  │    │    └─ 否则按 id 批量查，再过滤 pluginId + status=1
  │    ├─ 按 methodId 分组参数
  │    ├─ HttpPluginConverter.convert -> HttpPlugin
  │    └─ HttpToolFactory.buildHttpToolsWithThought -> 工具表
  ├─ 工具名去重（重复只保留第一个，WARN）
  └─ 单个插件异常 -> WARN 后继续下一个
```

两个容错点值得强调：

1. **按插件隔离失败**：某个插件配置损坏、SQL 查不到、转换异常，只会跳过这个插件，不会让整个 Agent 构建失败；
2. **工具名去重**：不同插件可能配置了同名工具，加载器用 `Set<String>` 做兜底，避免 `ToolService` 因重名抛异常。

### 5.3、从 `HttpPlugin` 到 `ToolSpecification + ToolExecutor`

`HttpToolFactory` 是 HTTP 工具对外的唯一构建入口：

```java
ToolSpecification toolSpecification =
        buildToolSpecification(
                httpPluginMethod.getMethodName(),
                httpPluginMethod.getMethodDescription(),
                httpPluginMethod.getParameters());

HttpToolExecutor httpToolExecutor =
        new HttpToolExecutor(
                baseUrl + httpPluginMethod.getUri(),
                httpPluginMethod.getHttpMethodType(),
                staticHeaders,
                parameterConfigs,
                httpTransport);
```

- `ToolSpecification`：工具名、描述、参数 JSON Schema，发给模型；
- `HttpToolExecutor`：真正发起 HTTP 请求的执行器，按参数配置把模型给的值填进 URL / Query / Header / Body。

工具名在业务层由 `HttpPluginConverter` 统一加前缀：

```text
{plugin.alias}__{method.methodName}
```

这样多个 HTTP 插件的同名方法可以共存于同一个 `ToolService`。

### 5.4、执行期的参数映射

`HttpToolExecutor.execute()` 做了这些事：

```text
ToolExecutionRequest.arguments (JSON)
  │
  ├─ processArguments：补默认值、校验必填
  ├─ buildUrlWithPathParams：替换 {pathParam}
  ├─ addHeaderParameters：HEADER 类型参数写入请求头
  ├─ appendQueryParameters：QUERY 类型参数拼到 URL
  ├─ shouldUseJsonBody：BODY 类型参数组装 JSON 请求体
  └─ JdkHttpTransport 发起请求
```

模型看到的参数名是 `methodParamName`，真正发给接口的是 `mappedName`。**内外解耦**让页面可以用更友好的参数名，而接口侧仍保持原有命名。

<br/>

## 六、MCP 插件工具：构建期动态发现

MCP 与 HTTP 最大的不同是：**工具清单不在配置里，而在远端 Server 里。**

### 6.1、配置只保存选择关系

```java
public class McpToolConfig {
    private Long pluginId;
    private Long[] serverIds;
}
```

`McpToolConfig` 的注释写得很清楚：

> 只保存选择关系，工具清单在 Agent 构建时通过 `tools/list` 动态发现。

这意味着配置层不需要感知某个 Server 当前暴露了哪些工具，也就不会出现「Server 加了工具，Agent 配置没同步」的问题。

### 6.2、`McpAgentToolLoader` 的加载链路

```text
loadWithThought(configs, true)
  │
  ├─ 遍历每个 McpToolConfig
  │    ├─ 归一化 serverIds
  │    ├─ 查 ai_plugin_mcp：不存在 / 未启用 / 已删除 -> 跳过
  │    ├─ loadServers：按 id 批量查，再过滤
  │    │    ├─ 必须属于当前 pluginId
  │    │    ├─ status = 1
  │    │    └─ is_deleted = 0
  │    └─ 对每个 Server：loadServer
  │         ├─ alias = mcp_{pluginId}_{serverName}
  │         ├─ DefaultMcpClient + StreamableHttpMcpTransport
  │         ├─ McpToolFactory.buildToolsWithThought
  │         └─ putAll 到总工具表
  └─ 单个 Server 失败 -> WARN 后跳过，不阻断 Agent 构建
```

关键代码（节选）：

```java
String alias = buildAlias(plugin.getId(), server.getServerName());
McpClient client = DefaultMcpClient.builder()
        .serverAlias(alias)
        .transport(
                StreamableHttpMcpTransport.builder()
                        .endpoint(server.getServerUrl())
                        .headers(McpPluginTransfer.readHeaders(server.getHeaders(), objectMapper))
                        .build())
        .build();

Map<ToolSpecification, ToolExecutor> serverTools =
        McpToolFactory.buildToolsWithThought(client, alias, withThought);
tools.putAll(serverTools);
```

### 6.3、工具命名与 Client 生命周期

`McpToolFactory` 会把远端工具名加上 Server 别名：

```text
{serverAlias}__{remoteToolName}
例如：mcp_7_github__create_issue
```

`ToolSpecification` 还会写入两份元数据：

```text
mcp.server = serverAlias
mcp.tool   = remoteToolName
```

这为后续 Trace、审计、按 Server 统计提供了稳定的标识。

Client 生命周期有一个细节：**加载成功的 Client 不能被关闭**，因为 `McpToolExecutor` 后续还要用它执行 `tools/call`；只有加载失败时才在 `finally` 中回收。Streamable HTTP 本身是无状态的，Client 不持有长连接，因此成功加载后由 Agent 运行态隐式持有即可。

### 6.4、HTTP 与 MCP 的对比

| 维度 | HTTP 插件 | MCP 插件 |
|---|---|---|
| 工具定义来源 | 本地插件表（方法 + 参数） | 远端 `tools/list` |
| 配置内容 | `pluginId + pluginMethodIds` | `pluginId + serverIds` |
| 工具清单是否落库 | 是 | 否 |
| 工具名 | `{alias}__{method}` | `{alias}__{remoteTool}` |
| 失败隔离粒度 | 插件级 | Server 级 |
| 执行方式 | `HttpToolExecutor` 拼 URL/Header/Body | `McpToolExecutor` 发 `tools/call` |

两者最终都产出 `Map<ToolSpecification, ToolExecutor>`，被同一个 `ToolService` 接管。**这就是「插件工具统一 Function Calling 标准」在装配层的体现。**

<br/>

## 七、业务层如何扩展 Agent 实现

这部分是本文的重点：**新增一种 Agent 类型，到底要改哪里？**

### 7.1、`AgentManagerFactory`：唯一的类型分发点

```java
@Component
public class AgentManagerFactory {

    private final Map<String, IAgentManager<?, ?>> managers = new HashMap<>();

    public AgentManagerFactory(List<IAgentManager<?, ?>> agentManagers) {
        for (IAgentManager<?, ?> agentManager : agentManagers) {
            managers.put(String.valueOf(agentManager.getType()), agentManager);
        }
    }

    public <T, F> IAgentManager<T, F> getAgentManagerByType(Integer type) {
        IAgentManager<?, ?> manager = managers.get(String.valueOf(type));
        if (manager == null) {
            throw new IllegalArgumentException("No AgentManager found for type: " + type);
        }
        return (IAgentManager<T, F>) manager;
    }
}
```

设计要点：

- 构造器注入 `List<IAgentManager<?, ?>>`，Spring 会把所有实现自动收集进来；
- 以 `getType()` 为 key 建索引；
- 上层只依赖 `type`，不依赖具体 Manager 类。

因此新增 Agent 类型时，**接入层（Controller）完全不需要改**。

### 7.2、`IAgentManager`：类型无关的运行时协议

```java
public interface IAgentManager<T, F> {
    Integer getType();

    T createAndStoreAgent(String aid, String agentConfig, boolean isDebug);
    T createAndStoreAgent(AgentInitContext context);

    T getAgent(String aid, boolean isDebug);
    T getAgent(AgentInitContext context);

    T getAgentAndInitAgent(String aid, String agentConfig, boolean isDebug);
    T getAgentAndInitAgent(AgentInitContext context);

    void removeAgent(String aid, boolean isDebug);
    void removeAgent(String aid);
    void clearAgents();

    ChatResult ask(T agent, AgentChatReq req);
    TokenStream stream(T agent, AgentChatReq req);
    void cancel(T agent, String sessionId);
}
```

`T` 是具体 Agent 类型，`F` 是解析后的配置类型。`ask` / `stream` / `cancel` 把「运行方式」与「具体 Agent」解耦。

### 7.3、`AbstractAgentManager`：把通用流程沉淀到父类

`StandardReActAgentManager` 只实现四个抽象点，其余流程都在父类：

```java
public abstract class AbstractAgentManager<T, F> implements IAgentManager<T, F> {

    private final Map<String, T> agents = new ConcurrentHashMap<>();
    private final Map<String, F> agentConfigs = new ConcurrentHashMap<>();

    @Override
    public final T createAndStoreAgent(AgentInitContext context) {
        T agent = createAgent(context);
        F parsedConfig = parseAgentConf(context.getAgentConfig());
        String agentKey = buildAgentKey(context);
        agents.put(agentKey, agent);
        agentConfigs.put(agentKey, parsedConfig);
        return agent;
    }

    @Override
    public final T getAgentAndInitAgent(AgentInitContext context) {
        String agentKey = buildAgentKey(context);
        T agent = getAgent(context);
        if (agent == null) {
            synchronized (agentKey.intern()) {
                agent = getAgent(context);
                if (agent == null) agent = createAndStoreAgent(context);
            }
        }
        return agent;
    }

    protected abstract F parseAgentConf(String agentConfig);
    protected abstract T createAgent(AgentInitContext context);
}
```

父类负责：

- **缓存**：`agents` 存运行态实例，`agentConfigs` 存解析后的配置；
- **懒创建 + 双重检查**：避免同一 `agentKey` 并发构建多个 Agent；
- **查询 / 删除 / 清空**：按 key 前缀批量移除；
- **key 规则**：

```text
agentKey = [temp_]{aid}::{channelId}

正式态：aid::channelId
调试态：temp_aid::channelId
```

调试实例加 `temp_` 前缀，天然与正式实例隔离；删除时也按前缀批量清理，不需要额外维护映射。

### 7.4、`StandardReActAgentManager`：只回答四个问题

```java
@Component
public class StandardReActAgentManager
        extends AbstractAgentManager<ReActAgent, StandardAgentConfig> {

    private static final int STANDARD_AGENT_TYPE = 1;

    @Override
    public Integer getType() {
        return STANDARD_AGENT_TYPE;
    }

    @Override
    protected StandardAgentConfig parseAgentConf(String agentConfig) {
        return StandardAgentConfig.fromJson(agentConfig, objectMapper);
    }

    @Override
    protected ReActAgent createAgent(AgentInitContext context) {
        StandardAgentConfig agentConfig = parseAgentConf(context.getAgentConfig());
        return reActAgentFactory.createReActAgent(agentConfig);
    }

    @Override
    public ChatResult ask(ReActAgent agent, AgentChatReq req) {
        return agent.run(toAgentRequest(req));
    }

    @Override
    public TokenStream stream(ReActAgent agent, AgentChatReq req) {
        return agent.runStream(toAgentRequest(req));
    }

    @Override
    public void cancel(ReActAgent agent, String sessionId) {
        agent.cancel(sessionId);
    }
}
```

它只做三件事：

1. 声明自己的 `type = 1`；
2. 把 JSON 解析成 `StandardAgentConfig`，再交给工厂构建；
3. 把业务请求 `AgentChatReq` 翻译成框架请求 `AgentRequest`。

### 7.5、`toAgentRequest`：业务上下文如何进入内核

```java
private AgentRequest toAgentRequest(AgentChatReq req) {
    String sessionId = req.getSessionId();
    boolean isDebug = Boolean.TRUE.equals(req.getIsDebug());
    if (sessionId == null || sessionId.trim().isEmpty()) {
        sessionId = (isDebug ? "debug-" : "chat-") + req.getAid();
    }

    Map<String, Object> extensions = new HashMap<>();
    extensions.put(SessionLifecycleMiddleware.EXT_SESSION_ID, sessionId);
    extensions.put(AgentTraceAttributes.AID, req.getAid());
    extensions.put(AgentTraceAttributes.DEBUG, isDebug);
    if (req.getAiMessageId() != null) {
        extensions.put(SessionLifecycleMiddleware.EXT_AI_MESSAGE_ID, req.getAiMessageId());
    }
    if (req.getSessionPk() != null) {
        extensions.put("session.sessionPk", req.getSessionPk());
    }

    return AgentRequest.builder()
            .memoryId(sessionId)
            .question(req.getMessage().trim())
            .extensions(extensions)
            .build();
}
```

两个关键约定：

- **`memoryId = sessionId`**：会话记忆按会话隔离，而不是按 Agent 隔离；
- **业务字段走 `extensions`**：会话主键、AI 消息主键、Trace 的 `aid/debug` 都通过扩展字段传入，核心内核不需要认识这些业务概念。

### 7.6、新增一种 Agent 类型，需要做什么

假设要新增 `PlanAgent`（`type = 2`），推荐按下面的路径扩展：

```text
1. 新建配置类      PlanAgentConfig（可复用 StandardAgentConfig 的部分子对象）
2. 新建装配工厂    PlanAgentFactory（把配置装配成 PlanAgent）
3. 新建运行时管理  PlanAgentManager extends AbstractAgentManager<PlanAgent, PlanAgentConfig>
                 - getType() 返回 2
                 - parseAgentConf / createAgent
                 - ask / stream / cancel
4. 标注 @Component，交给 Spring 收集
5. ai_agent_config.type 写入 2
```

完成以上步骤后：

- `AgentManagerFactory` 自动发现新 Manager；
- `AgentConfigServiceImpl` 通过 `getAgentManagerByType(pojo.getType())` 做清理，无需改动；
- `AgentChatController` / `ChatController` 仍按 `config.getType()` 取 Manager，无需改动。

这就是「**新增实现类，而不是修改调用方**」的扩展方式。

### 7.7、其他扩展点

| 扩展目标 | 扩展方式 | 是否改核心 |
|---|---|---|
| 新增记忆类型 | 实现 `ChatMemoryProviderFactoryExtension`，声明 `getType()` | 否 |
| 新增中间件 | 实现 `IAgentMiddleware` / `IStreamingIAgentMiddleware`，在工厂注册 | 否 |
| 新增本地工具 | 使用 `LocalToolFactory`，在 `buildToolService` 注册 | 否 |
| 新增模型协议 | 扩展 `LlmFactory` / `IModel` | 否（业务层） |
| 新增工具类型 | 产出 `Map<ToolSpecification, ToolExecutor>` 即可 | 否 |

记忆扩展尤其典型：`ChatMemoryProviderFactory` 按 `type` 从 `List<ChatMemoryProviderFactoryExtension>` 中选实现，和 `AgentManagerFactory` 是同一种「按 type 分发」的思路。

<br/>

## 八、复用核心 ReAct：业务层不重复写循环

### 8.1、两个入口，一个内核

`StandardReActAgentManager` 的 `ask` / `stream` 分别对应：

```text
ask    -> agent.run(request)        -> ChatResult
stream -> agent.runStream(request)  -> TokenStream
```

而 `ReActAgent` 的两个方法继承自 `Agent`：

```text
run()       : while (step <= maxSteps) { step() }        同步驱动器
runStream() : new ReActTokenStream(...)                  异步驱动器
step()      : checkCancel -> think -> checkCancel -> act
```

也就是说，业务层只做了两行委托，**没有复制任何循环、状态机、工具执行或取消逻辑**。

### 8.2、非流式与流式的业务差异

| 维度 | `ask` | `stream` |
|---|---|---|
| 内核入口 | `agent.run(request)` | `agent.runStream(request)` |
| 返回 | `ChatResult` | `TokenStream` |
| 前端通道 | HTTP JSON | SSE |
| 事件出口 | 中间件回调（无增量） | `ServiceStreamingMiddleware` 逐事件发送 |
| 启动方式 | 同步返回 | `stream(agent, req).ignoreErrors().start()` |

差异只在「怎么消费结果」，不在「怎么执行 ReAct」。

### 8.3、中间件把内核回调翻译成业务事件

以流式为例，`ChatController` 只做三件事：

```java
streamingMiddleware.bind(sessionId, emitter);
activeStops.put(sessionId, () -> agentManager.cancel(agent, sessionId));
agentManager.stream(agent, chatReq).ignoreErrors().start();
```

真正的业务事件由中间件在核心回调点产生：

```text
onInitComplete                  -> sessionCreated
onPartialThinking               -> think 事件
onPartialResponse               -> 正文增量
beforeToolExecution             -> 工具开始卡片
afterToolExecution              -> 工具完成卡片
onStop / onStopWithError        -> 结束 / 错误
```

`SessionLifecycleMiddleware` 则在同一批回调里做另一件事：流式期间只在内存聚合事件，`onStop` 时一次性写 `content / events / elapsed_ms / 终态`。**两条路径、多个业务关注点，共用同一套回调协议。**

### 8.4、「一套调用」在业务层的完整闭环

```text
配置 JSON
   │  StandardAgentConfig.fromJson
   ▼
ReActAgentFactory
   ├─ 同一份 LlmBasicConfig -> ChatModel + StreamingChatModel
   ├─ ChatMemoryProvider
   ├─ HTTP / MCP -> ToolService
   └─ 中间件
   ▼
ReActAgent
   ├─ run()      -> ChatResult
   └─ runStream() -> TokenStream
   ▼
AgentManager
   ├─ ask    -> run()
   └─ stream -> runStream()
```

框架层的「非流式/流式共用内核」，在业务层被进一步放大为「**共用配置、共用装配、共用实例、共用中间件**」。

<br/>

## 九、缓存与失效：实例复用、会话隔离

### 9.1、缓存 key 的设计

```text
agentKey = [temp_]{aid}::{channelId}
```

- `aid`：Agent 对外唯一标识；
- `channelId`：渠道维度，当前 Web 端固定为 `-1`；
- `temp_`：调试态前缀，避免调试配置污染正式实例。

因此同一个 `aid` 在正式态和调试态下是两个独立实例，互不影响。

### 9.2、实例复用与会话隔离

这里有一个容易误解的点：**Agent 实例按 `aid` 复用，但记忆按 `memoryId`（即 `sessionId`）隔离。**

```text
agents:        aid::channelId -> ReActAgent（共享）
ChatMemory:    sessionId      -> 一段独立会话记忆
```

`ReActAgent.initMessages` 每次运行都会从 `ChatMemoryProvider.get(memoryId)` 取当前会话记忆，重建系统提示词后再写入用户消息。所以：

- 多个用户/多个会话可以复用同一个 Agent 实例；
- 每个会话的上下文互不串扰；
- 取消标志也按 `memoryId` 维护。

### 9.3、配置变更后的失效

Agent 实例持有的是「构建时」的工具表。插件变更后，如果继续使用旧实例，模型看到的工具声明就会过期。

HTTP 插件侧的处理是 `HttpPluginAgentCacheService`：

```java
Runnable callback = () -> {
    IAgentManager<Object, Object> manager =
            agentManagerFactory.getAgentManagerByType(STANDARD_AGENT_TYPE);
    manager.clearAgents();
};

if (!TransactionSynchronizationManager.isSynchronizationActive()) {
    callback.run();
    return;
}
TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                callback.run();
            }
        });
```

两个细节：

1. **只清运行态缓存，不维护 Agent-插件关联表**：简单、可靠；
2. **事务提交后再清**：避免事务回滚导致缓存被误清。

**当前边界**：MCP 插件变更尚未接入同等的缓存失效逻辑；如果需要 MCP 工具变更即时生效，可以复用同样的 `clearAgents` 机制，或在构建时引入版本号校验。

<br/>

## 十、边界与设计取舍

### 10.1、已明确的边界

1. **同一 `memoryId` 不支持并发运行。**
   这是框架层 `BaseAgent.prepareForNewRun` 的既定前提，业务层用 `sessionId` 作为 `memoryId`，需要保证同一会话不并发发起两次对话。

2. **Agent 缓存不区分配置版本。**
   缓存 key 只有 `aid + channelId + debug`，同一 `aid` 在配置更新后需要显式失效（HTTP 插件通过 `clearAgents`，Agent 配置更新走 `AgentConfigServiceImpl` 的清理逻辑）。

3. **MCP 工具发现发生在构建期。**
   如果 Server 在 Agent 构建后新增工具，需要等下一次构建/缓存失效才能被模型看到。

4. **工具加载失败是「降级」而不是「失败」。**
   HTTP 单插件失败、MCP 单 Server 失败都只记 WARN 并跳过，Agent 仍可构建。这提升了可用性，但也意味着「工具缺失」需要在日志/监控侧可观测。

5. **`AgentSettings` 尚未配置化。**
   当前统一使用默认值；步长、重试、并发开关需要后续下沉到 `StandardAgentConfig`。

### 10.2、为什么这样取舍

- **配置只存选择关系**：避免配置与插件定义双写，减少不一致；
- **装配集中在工厂**：所有「配置 → 运行态」的转换只有一处，便于排查；
- **缓存按 aid 而非按会话**：Agent 是无状态的执行器，会话状态在 `ChatMemory` 里，实例可以安全复用；
- **类型分发用 Spring 集合注入**：新增 Manager 不需要改工厂，符合开闭原则；
- **工具统一成 `Map<ToolSpecification, ToolExecutor>`**：HTTP / MCP / Local 在 `ToolService` 面前完全同构，Agent 内核不需要知道工具来源。

<br/>

## 十一、总结

回到开头的问题：业务层如何把「通用 ReAct 内核」变成「可配置、可扩展、可运行的 Agent」？

AgentForge 的答案是四层收口：

1. **配置收口**：`StandardAgentConfig` 把模型引用、提示词、记忆、插件选择关系收敛成一份可持久化 JSON；
2. **装配收口**：`ReActAgentFactory` 把配置翻译成 `ChatModel + StreamingChatModel + ChatMemoryProvider + ToolService + Middleware`；
3. **工具收口**：HTTP 走本地声明式映射，MCP 走构建期 `tools/list` 动态发现，最终都变成同一张工具表；
4. **运行收口**：`AgentManagerFactory` 按 `type` 分发，`AbstractAgentManager` 负责缓存与生命周期，`StandardReActAgentManager` 只把 `ask/stream` 委托给 `run/runStream`。

最终形成的扩展模型是：

```text
新增 Agent 类型   -> 新增 Manager（实现 IAgentManager / 继承 AbstractAgentManager）
新增记忆类型      -> 新增 ChatMemoryProviderFactoryExtension
新增工具来源      -> 产出 Map<ToolSpecification, ToolExecutor>
新增横切能力      -> 新增 Middleware
新增模型协议      -> 扩展 LlmFactory / IModel
```

而这一切都不需要修改 ReAct 内核，也不需要修改对话入口。

这正是「核心基础通用 Agent 装配设计」的核心：**把变化留在业务层的装配与分发，把稳定留给框架层的 ReAct 内核。**

<br/>

## 参考资料

- 业务装配：`agentforge-service-web/src/main/java/cloud/changlu/agentforge/service/modules/agent/runtime/factory/AgentManagerFactory.java`
- 业务装配：`.../runtime/mode/react/ReActAgentFactory.java`
- 业务运行时：`.../runtime/mode/react/StandardReActAgentManager.java`
- 通用缓存：`.../runtime/base/AbstractAgentManager.java`
- 配置模型：`.../agent/domain/config/StandardAgentConfig.java`
- HTTP 加载：`.../plugins/http/component/agent/HttpAgentToolLoader.java`
- MCP 加载：`.../plugins/mcp/component/agent/McpAgentToolLoader.java`
- 框架内核：`agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent`
- 同系列：[核心 ReAct 框架设计：非流式与流式一套调用](../../../../../../02、通用ReActagent层/核心ReAct%20框架设计，支持非流式、流式%20一套调用.md)
