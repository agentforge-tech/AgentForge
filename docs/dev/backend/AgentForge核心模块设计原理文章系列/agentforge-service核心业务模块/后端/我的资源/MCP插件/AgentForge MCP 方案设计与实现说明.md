# AgentForge MCP 方案设计与实现说明

## 1. 文档定位

本文描述 AgentForge 的 MCP（Model Context Protocol）接入方案，重点落在 **AgentForge Service 的对接设计与实现**：核心表结构、后端模块、Agent 运行时装配，以及前端 MCP 列表页面。AgentForge 框架层的 MCP 协议与工具适配作为 Service 层的依赖一并说明。

阅读重点：

- 第 3～8 章：AgentForge 框架层 MCP 协议、传输与工具适配。
- 第 9 章：AgentForge Service 的 MCP 对接设计，包括 `ai_plugin_mcp` / `ai_plugin_mcp_server`、后端模块、一键导入、Agent 装配和前端选择交互。

本文以仓库中的实际代码为准，框架层核心实现位于：

```text
agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/tool/mcp
```

相关基础契约位于：

```text
agentforge-model/agentforge-model-api/src/main/java/cloud/changlu/agentforge/model/tool
agentforge-model/agentforge-model-core/src/main/java/cloud/changlu/agentforge/model/tool/execution/ToolService.java
agentforge-model/agentforge-model-core/src/main/java/cloud/changlu/agentforge/model/http/JdkHttpTransport.java
```

AgentForge Service 侧的模块设计、表结构与前端交互位于第 9 章。当前仓库已经具备框架层 MCP 能力，Service 侧 MCP 模块尚未落地代码，因此第 9 章是后续实现的对接基线；其数据模型与交互参考 ai-chat 已落地的 `mcp_plugin` 方案，表名适配为 `ai_plugin_mcp`。

## 2. 背景与设计约束

### 2.1 为什么需要 MCP

AgentForge 已有两类工具来源：

- 本地 `@Tool` 方法，通过 `LocalToolFactory` 注册。
- HTTP 接口，通过 `HttpToolFactory` 注册。

MCP 与普通 HTTP 工具的区别在于：

- MCP Server 在运行时通过 `tools/list` 动态声明工具，不需要把每个工具的参数结构手工录入配置。
- MCP Client 需要先完成 `initialize` 握手，再发送 `notifications/initialized`，之后才能调用 `tools/list` 和 `tools/call`。
- 同一个能力域可能由多个 MCP Server 组成，工具需要在 Agent 装配阶段动态发现。
- MCP 可能使用 stdio（本地进程）或 Streamable HTTP（远程服务）两种传输方式。

因此，MCP 不能简单复用 `HttpToolFactory` 的数据结构，需要在框架层增加独立的协议实现和工具适配层。

### 2.2 核心约束

| 约束 | 影响 |
| --- | --- |
| 公共 API 保持 Java 8 兼容 | 不使用 `CompletableFuture`、`HttpClient`、`var` 等 JDK 9+ 能力 |
| 核心模块不引入第三方依赖 | 不依赖官方 MCP Java SDK、Jackson、Reactor、SLF4J |
| 复用框架现有 JSON 与 HTTP 能力 | 使用 `agentforge-model` 内置的 `Json` 与 `HttpTransport` |
| 复用现有 Tool 契约 | MCP 工具最终必须变成 `ToolSpecification + ToolExecutor` |
| 同步调用模型 | 一次请求对应一次响应，不引入异步流式编程模型 |
| 工具调用闭环统一 | MCP 工具与本地工具、HTTP 工具共用同一个 `ToolService` |

### 2.3 设计目标

1. 在框架层提供可独立使用的 MCP Client。
2. 支持 `initialize`、`notifications/initialized`、`tools/list`、`tools/call`。
3. 支持 stdio 与 Streamable HTTP 两种传输。
4. 将 MCP 工具无损转换为 AgentForge 的 `ToolSpecification` 和 `ToolExecutor`。
5. 支持多 Server 工具聚合，并通过别名避免同名冲突。
6. 保持零第三方依赖，并可在离线环境下进行单元测试。

### 2.4 非目标

当前版本明确不包含：

- MCP Resources、Prompts、Sampling、Roots、Elicitation。
- OAuth 或其他动态鉴权流程。
- `Mcp-Session-Id` 会话保持与断线恢复。
- Streamable HTTP 的 GET 长连接支流。
- MCP 配置持久化、管理 API、AgentForge Service 管理页面。
- MCP Server 侧实现。
- 连接池、熔断、限流和自动重试。

## 3. 总体架构

### 3.1 分层结构

```text
ReActAgent / AgentForge Agent
        │
        ▼
ToolService（统一工具注册与调用循环）
        │
        ▼
McpToolFactory / McpToolExecutor（工具适配层）
        │
        ▼
McpClient / DefaultMcpClient（MCP 协议层）
        │
        ├── JsonRpcCodec / McpJson
        ├── McpToolSpecificationMapper
        └── McpResultConverter
        │
        ▼
McpTransport（传输抽象）
        │
        ├── StdioMcpTransport
        └── StreamableHttpMcpTransport
                    │
                    ▼
             HttpTransport / JdkHttpTransport
        │
        ▼
MCP Server
```

### 3.2 各层职责

| 层级 | 职责 | 代码位置 |
| --- | --- | --- |
| 工具适配层 | 把 MCP 工具转换为 `ToolSpecification`，把调用转发给 `McpClient` | `mcp/McpToolFactory.java`、`mcp/McpToolExecutor.java` |
| 协议层 | 管理初始化状态、JSON-RPC 请求 ID、`tools/list`、`tools/call` | `mcp/DefaultMcpClient.java` |
| 传输层 | 承载 JSON-RPC 消息，屏蔽 stdio 与 HTTP 差异 | `mcp/transport` |
| 编解码层 | JSON-RPC 编解码、参数 Schema 转换、结果文本化 | `mcp/support` |
| 领域模型 | MCP 工具、内容块、调用结果、Server 信息与能力 | `mcp/domain` |
| Agent 运行层 | 按工具名路由并驱动模型调用循环 | `ToolService` |
| HTTP 基础层 | 提供与具体 HTTP 库解耦的请求执行能力 | `JdkHttpTransport` |

### 3.3 与现有工具体系的统一

MCP 工具最终进入同一个 `ToolService`：

```text
LocalToolFactory ─┐
HttpToolFactory  ─┼──> Map<ToolSpecification, ToolExecutor> ──> ToolService ──> ChatModel / Agent
McpToolFactory   ─┘
```

这意味着 Agent 侧不需要区分工具来源。模型看到的只有工具名、描述和参数 Schema；`ToolService` 按工具名找到对应 `ToolExecutor` 并执行。

## 4. 核心抽象与契约

### 4.1 ToolExecutor

`McpToolExecutor` 实现框架的 `ToolExecutor`：

```java
public interface ToolExecutor {
    String execute(ToolExecutionRequest request, Object memoryId);

    default ToolExecutionResult executeWithResult(
            ToolExecutionRequest request, Object memoryId) {
        return ToolExecutionResult.builder().text(execute(request, memoryId)).build();
    }
}
```

MCP 适配同时实现两个方法：

- `execute`：返回拼接后的文本，适用于只需要文本结果的场景。
- `executeWithResult`：返回 `isError`、`structuredContent` 和文本，供 `ToolService` 使用。

### 4.2 McpClient

`McpClient` 是协议层接口：

| 方法 | 说明 |
| --- | --- |
| `serverAlias()` | 返回服务别名，用于工具名前缀 |
| `listTools()` | 初始化连接并返回 MCP 工具列表 |
| `callTool(toolName, arguments)` | 调用远程工具，`toolName` 为服务端原始工具名 |
| `close()` | 关闭传输资源 |

`DefaultMcpClient` 额外暴露：

- `serverInfo()`：服务端名称与版本。
- `capabilities()`：服务端能力集合的原始 Map。
- `protocolVersion()`：协商后的协议版本。

这些方法会触发初始化。

### 4.3 McpTransport

`McpTransport` 是传输 SPI：

| 方法 | 说明 |
| --- | --- |
| `send(message)` | 发送 JSON-RPC 通知，不等待响应 |
| `request(message)` | 发送 JSON-RPC 请求，返回匹配 ID 的响应 |
| `isOpen()` | 底层通道或进程是否可用 |
| `close()` | 关闭通道或回收进程 |

设计上刻意保持“一次请求对应一次响应”的同步模型。这样 stdio 与 Streamable HTTP 可以共用同一套上层协议逻辑，也避免把 Reactor 一类异步模型带入 Java 8 核心模块。

传输层只负责消息收发，不解释 MCP 方法语义。`initialize`、`tools/list`、`tools/call` 都由 `DefaultMcpClient` 发起。

### 4.4 领域模型

| 类型 | 说明 |
| --- | --- |
| `McpTool` | 工具名、描述、原始 `inputSchema` |
| `McpCallToolResult` | 内容块列表、结构化内容、`isError` |
| `McpContent` | 单个内容块，保留 `type/text/mimeType/raw` |
| `McpServerInfo` | `initialize` 返回的服务端名称与版本 |
| `McpCapabilities` | 服务端能力原始 Map，可按顶层能力名查询 |
| `McpTool` 列表 | `tools/list` 的解析结果 |

`McpContent.raw()` 会保留原始内容块，避免图片、音频、资源等非文本块在解析阶段直接丢失。当前送入模型的仍是文本，非文本块会被序列化为 JSON 文本。

### 4.5 异常模型

| 异常 | 触发场景 |
| --- | --- |
| `McpProtocolException` | 服务端返回 JSON-RPC `error`，或响应不是合法 JSON |
| `McpTransportException` | HTTP 失败、空响应、进程退出、流中断、传输关闭等 |

`McpProtocolException` 保留 JSON-RPC 错误码，便于上层按错误码处理，例如 `-32601` 表示方法不存在。

## 5. DefaultMcpClient

### 5.1 默认配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `protocolVersion` | `2025-06-18` | 初始化时请求的协议版本 |
| `clientInfo.name` | `agentforge` | 客户端名称 |
| `clientInfo.version` | `1.0.0` | 客户端版本 |
| `serverAlias` | 无 | 工具名前缀，可为空 |
| `transport` | 必填 | stdio 或 Streamable HTTP |

构建方式：

```java
DefaultMcpClient client =
        DefaultMcpClient.builder()
                .serverAlias("fs")
                .transport(transport)
                .build();
```

也可以通过 `protocolVersion(...)` 与 `clientInfo(name, version)` 覆盖默认值。

### 5.2 惰性初始化

客户端不会在构造时连接服务端，而是在第一次调用以下方法时初始化：

- `listTools()`
- `callTool(...)`
- `serverInfo()`
- `capabilities()`
- `protocolVersion()`

初始化使用双重检查锁，保证同一个 Client 只执行一次握手：

```text
ensureInitialized()
    │
    ├── 已初始化：直接返回
    │
    ▼
initialize
    │
    ├── 发送 protocolVersion
    ├── 发送空 capabilities
    ├── 发送 clientInfo
    ├── 读取协商后的 protocolVersion
    ├── 读取 serverInfo
    ├── 读取 capabilities
    └── 将协商版本同步给 StreamableHttpMcpTransport
    │
    ▼
notifications/initialized
    │
    ▼
标记 initialized = true
```

`initialize` 请求示例：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-06-18",
    "capabilities": {},
    "clientInfo": {
      "name": "agentforge",
      "version": "1.0.0"
    }
  }
}
```

服务端返回后，客户端发送：

```json
{
  "jsonrpc": "2.0",
  "method": "notifications/initialized"
}
```

### 5.3 请求 ID 与响应处理

- 每个 `DefaultMcpClient` 内部维护递增的 `AtomicLong` 请求 ID。
- 请求编码由 `JsonRpcCodec` 完成。
- 传输层负责返回匹配当前请求 ID 的消息。
- `JsonRpcCodec.resultOf` 负责识别 `error` 并转换为 `McpProtocolException`。
- 如果响应不是合法 JSON，抛出 `-32700` 协议异常。
- 如果响应没有 `result` 对象，返回空 Map。

### 5.4 tools/list

`listTools()` 发送一次 `tools/list` 请求，并解析 `tools` 数组：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list"
}
```

解析规则：

- 工具名必须非空，否则跳过。
- 描述可以为空。
- `inputSchema` 为空时保留空 Map。
- 当前实现**只读取首页结果，不处理 `nextCursor`**。

### 5.5 tools/call

`callTool(toolName, arguments)` 发送：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "read_file",
    "arguments": {
      "path": "/tmp/a.txt"
    }
  }
}
```

`arguments` 为 `null` 时使用空 Map。

结果解析：

| MCP 字段 | AgentForge 映射 |
| --- | --- |
| `content[]` | `List<McpContent>` |
| `content[].type` | `McpContent.type` |
| `content[].text` | `McpContent.text` |
| `content[].mimeType` | `McpContent.mimeType` |
| 原始内容块 | `McpContent.raw` |
| `structuredContent` | `McpCallToolResult.structuredContent` |
| `isError` | `McpCallToolResult.isError` |

### 5.6 关闭

`close()` 会关闭底层 Transport，并将初始化状态复位。调用方负责在 Agent 销毁、配置刷新或应用关闭时关闭 Client。

## 6. 传输层实现

### 6.1 StreamableHttpMcpTransport

#### 6.1.1 基本行为

- 使用单一 MCP endpoint。
- 每次请求通过 `HttpTransport.execute` 发送 HTTP POST。
- 默认使用 `JdkHttpTransport`，也可以注入自定义 `HttpTransport`。
- 默认连接超时 10 秒，读取超时 60 秒。
- 请求头：

| Header | 值 |
| --- | --- |
| `Content-Type` | `application/json` |
| `Accept` | `application/json, text/event-stream` |
| `MCP-Protocol-Version` | 握手协商后的版本，握手完成前通常不带 |
| 自定义 Headers | 由 Builder 注入，最后写入 |

自定义 Header 同名时会覆盖默认值，AgentForge Service 层应避免覆盖 `Content-Type`、`Accept` 等协议头。

#### 6.1.2 响应解析

服务端可能返回两种响应：

1. `application/json`：响应体本身就是一个 JSON-RPC 消息。
2. `text/event-stream`：请求范围内的 SSE 流，每个 `data:` 事件承载一个 JSON-RPC 消息。

处理流程：

```text
HTTP POST
    │
    ▼
读取响应体
    │
    ├── 以 "{" 开头 ──> 直接作为 JSON-RPC 消息
    │
    └── 否则按 SSE 解析
            │
            ├── 提取 data: 内容
            ├── 按空行分隔事件
            ├── 匹配 JSON-RPC id
            └── 找不到匹配 id 时使用最后一个事件
```

SSE 解析细节：

- 忽略以 `:` 开头的注释行。
- 一个事件内的多条 `data:` 会以换行拼接。
- 忽略 `event:`、`id:` 等非 `data:` 字段。

#### 6.1.3 HTTP 错误处理

| 情况 | 行为 |
| --- | --- |
| 状态码 >= 400 且响应体是 JSON-RPC error | 返回响应体，交由 `JsonRpcCodec` 抛出 `McpProtocolException` |
| 状态码 >= 400 且响应体不是 JSON-RPC error | 抛出 `McpTransportException` |
| 响应体为空 | 对请求抛出 `McpTransportException`；对通知可接受空响应 |
| SSE 中没有可用消息 | 抛出 `McpTransportException` |

#### 6.1.4 当前限制

`StreamableHttpMcpTransport` 当前是**面向无状态 Server 的 P0 实现**：

- 不读取响应 Header，因此**不支持 `Mcp-Session-Id`**。
- 不发起 GET SSE 长连接，因此只支持请求范围内的 SSE 响应。
- 不实现断线恢复、事件重放和会话恢复。
- `close()` 只设置关闭标记，不维护额外的连接资源。

这些属于后续 P1 能力。代码注释中也明确说明：由于 `HttpResponse` 未暴露响应头，Session ID 回传暂未接入。

### 6.2 StdioMcpTransport

#### 6.2.1 进程启动

`StdioMcpTransport` 使用 `ProcessBuilder` 启动本地 MCP Server：

```java
StdioMcpTransport transport =
        StdioMcpTransport.builder()
                .command("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
                .env("TOKEN", "xxx")
                .workingDirectory(new File("/tmp"))
                .build();
```

或使用简写：

```java
StdioMcpTransport transport =
        StdioMcpTransport.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp");
```

进程在第一次 `send` 或 `request` 时惰性启动。

#### 6.2.2 消息分帧

- 每条 JSON-RPC 消息占一行。
- 写入前会移除消息中的换行符。
- 使用 UTF-8 编码。
- 读取时忽略空行和非 JSON 行。
- 只返回 ID 与当前请求匹配的消息。
- 通知、其他请求的响应、迟到响应都会被忽略。

这是同步模型下的简化实现，不维护 `id -> future` 的路由表。

#### 6.2.3 stderr 处理

子进程的 `stderr` 由独立守护线程持续读取，并写入 FINE 级别日志。这样可以避免服务端输出过多诊断信息时管道阻塞。

#### 6.2.4 关闭

`close()` 会：

1. 关闭写入流。
2. 调用 `process.destroy()`。
3. 最多等待 2 秒。
4. 超时后调用 `destroyForcibly()`。

#### 6.2.5 安全边界

框架本身不限制 stdio 命令。AgentForge Service 层如果开放 stdio 配置，必须自行增加：

- 命令白名单。
- 运行用户隔离。
- 工作目录限制。
- 环境变量与密钥管理。
- 进程资源限制。
- 超时与最大进程数控制。

### 6.3 两种传输对比

| 维度 | Stdio | Streamable HTTP |
| --- | --- | --- |
| 部署形态 | 本地子进程 | 远程服务 |
| 分帧方式 | 换行分隔 JSON | HTTP 响应体 / SSE |
| 连接建立 | `ProcessBuilder` | HTTP POST |
| 会话状态 | 进程内隐式保持 | 当前无 Session ID |
| 主要风险 | 本地命令执行、进程泄漏 | SSRF、鉴权、网络超时 |
| 当前状态 | 已支持 | 已支持 |

## 7. 工具适配设计

### 7.1 McpToolFactory

`McpToolFactory` 是把 MCP 工具接入 AgentForge 工具表的入口：

```java
public static Map<ToolSpecification, ToolExecutor> buildTools(McpClient client);
public static Map<ToolSpecification, ToolExecutor> buildTools(
        McpClient client, String serverAlias);
public static Map<ToolSpecification, ToolExecutor> buildTools(
        McpClient client,
        String serverAlias,
        Set<String> enabledTools,
        Set<String> disabledTools);
```

处理流程：

```text
解析 alias
    │
    ├── 显式 alias 非空：使用显式值
    └── 否则使用 client.serverAlias()
    │
    ▼
client.listTools()
    │
    ▼
按 enabledTools / disabledTools 过滤
    │
    ▼
生成 ToolSpecification
    │
    ├── name = alias__原始工具名
    ├── description = MCP description
    ├── parameters = inputSchema 原样透传
    ├── metadata.mcp.server = alias
    └── metadata.mcp.tool = 原始工具名
    │
    ▼
绑定 McpToolExecutor(client, 原始工具名)
    │
    ▼
返回 Map<ToolSpecification, ToolExecutor>
```

### 7.2 工具命名与冲突

工具名前缀规则：

```text
{alias}__{remoteToolName}
```

示例：

```text
fs__read_file
weather__get_weather
```

规则：

- `alias` 为空时不做前缀。
- 原始工具名始终保存在 `McpToolExecutor` 中，调用时使用原始名。
- 不同 Server 必须使用不同 alias，否则同名工具会冲突。
- `ToolService` 注册时如果发现重复工具名，会抛出 `IllegalArgumentException`。

因此，多 Server 场景下建议使用稳定的别名，例如：

```text
mcp_{serverId}__{toolName}
```

### 7.3 工具过滤

- `enabledTools` 非空时，只暴露集合内的远程工具名。
- `enabledTools` 为空或 `null` 时，不启用白名单限制。
- `disabledTools` 命中的远程工具名会被隐藏。
- 过滤依据是**服务端原始工具名**，不是带别名后的工具名。
- 过滤发生在 `tools/list` 之后，不改变 Server 端能力。

### 7.4 Schema 映射

`McpToolSpecificationMapper` 直接透传 MCP 的 JSON Schema：

```java
public static ToolParameters toToolParameters(Map<String, Object> inputSchema) {
    if (inputSchema == null || inputSchema.isEmpty()) {
        return ToolParameters.empty();
    }
    return ToolParameters.from(inputSchema);
}
```

MCP 本身使用 JSON Schema，与 `ToolParameters` 的结构一致，因此不需要字段重写。没有参数的工具会得到：

```json
{
  "type": "object",
  "properties": {}
}
```

### 7.5 McpToolExecutor

`McpToolExecutor` 在构造时绑定：

- 所属 `McpClient`。
- 远程工具的原始名称。

调用流程：

```text
ToolExecutionRequest
    │
    ▼
McpJson.argumentsAsMap(request.arguments())
    │
    ▼
client.callTool(remoteToolName, arguments)
    │
    ▼
McpResultConverter.toText(result)
    │
    ▼
ToolExecutionResult
```

结构化结果映射：

| MCP | ToolExecutionResult |
| --- | --- |
| `isError` | `isError` |
| `structuredContent` | `result` |
| 文本内容 | `text` |

### 7.6 结果转换

`McpResultConverter` 的规则：

1. 依次遍历 `content`。
2. 文本块直接拼接，块之间以换行分隔。
3. 非文本块使用原始内容块的 JSON 文本。
4. 没有任何文本时，使用 `structuredContent` 的 JSON 文本作为兜底。
5. 结果始终非 `null`。

当前不会把图片、音频等块转换为多模态消息，而是以 JSON 文本形式回填给模型。

### 7.7 错误处理与 ToolService

`McpToolExecutor.executeWithResult` 会捕获：

- `McpProtocolException`：返回 `ToolExecutionResult.failure("MCP protocol error: ...", e)`。
- `McpTransportException`：返回 `ToolExecutionResult.failure("MCP transport error: ...", e)`。

其他异常（例如工具参数不是合法 JSON 导致的 `IllegalArgumentException`）会向上抛出，由 `ToolService` 的 `executionErrorHandler` 处理。`ToolService` 默认会把错误文本返回给模型，让模型自行纠正。

`ToolService` 的调用循环会：

1. 把工具列表合并进模型请求。
2. 解析模型返回的 `ToolExecutionRequest`。
3. 按工具名查找 `ToolExecutor`。
4. 调用 `executeWithResult`。
5. 把结果作为 `ToolExecutionResultMessage` 回填对话。
6. 继续下一轮模型调用。

MCP 工具与本地工具、HTTP 工具在这条链路上完全一致。

## 8. 使用方式

### 8.1 接入 stdio MCP Server

```java
McpClient fs =
        DefaultMcpClient.builder()
                .serverAlias("fs")
                .transport(
                        StdioMcpTransport.of(
                                "npx",
                                "-y",
                                "@modelcontextprotocol/server-filesystem",
                                "/tmp"))
                .build();

ToolService toolService = new ToolService();
toolService.tools(McpToolFactory.buildTools(fs));
toolService.tools(new WeatherTools());

ReActAgent agent =
        ReActAgent.builder()
                .chatModel(chatModel)
                .toolService(toolService)
                .build();
```

模型看到的工具名形如：

```text
fs__read_file
fs__write_file
getWeather
```

### 8.2 接入 Streamable HTTP MCP Server

```java
McpClient remote =
        DefaultMcpClient.builder()
                .serverAlias("wiki")
                .transport(
                        StreamableHttpMcpTransport.builder()
                                .endpoint("https://mcp.example.com/mcp")
                                .header("Authorization", "Bearer " + token)
                                .connectTimeoutMillis(10_000)
                                .readTimeoutMillis(60_000)
                                .build())
                .build();

ToolService toolService = new ToolService();
toolService.tools(McpToolFactory.buildTools(remote, "wiki"));
```

工具名形如：

```text
wiki__read_wiki_structure
wiki__read_wiki_contents
wiki__ask_wiki_question
```

### 8.3 多 Server 聚合

```java
ToolService toolService = new ToolService();

McpClient fs = DefaultMcpClient.builder()
        .serverAlias("fs")
        .transport(StdioMcpTransport.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"))
        .build();

McpClient weather = DefaultMcpClient.builder()
        .serverAlias("weather")
        .transport(StreamableHttpMcpTransport.builder()
                .endpoint("https://mcp.example.com/weather")
                .build())
        .build();

toolService.tools(McpToolFactory.buildTools(fs));
toolService.tools(McpToolFactory.buildTools(weather));
```

前提是两个 Server 的别名不同，且工具名在加入前缀后不与其他工具冲突。

### 8.4 工具过滤

```java
Map<ToolSpecification, ToolExecutor> tools =
        McpToolFactory.buildTools(
                client,
                "fs",
                Collections.singleton("read_file"),
                null);
```

只暴露 `fs__read_file`。

### 8.5 关闭

```java
try {
    toolService.tools(McpToolFactory.buildTools(client));
    // 使用 Agent
} finally {
    client.close();
}
```

stdio Client 关闭时会回收子进程；HTTP Client 关闭后不再接受新请求。

## 9. AgentForge Service MCP 对接设计

AgentForge Service 负责 MCP 配置持久化、管理接口、Agent 运行时装配和前端页面；AgentForge 框架负责协议、传输与工具转换。本章是 Service 侧的实现基线，数据模型与交互参考 ai-chat 已落地的 `mcp_plugin` / `mcp_plugin_server` 方案（commit `91a99e44`、`719a15ef`），并适配 AgentForge Service 的 `ai_` 表名前缀。

后端模块统一规划在 `modules/plugin` 下（而非单独建 `modules/mcp`），MCP 作为插件体系的第一个落地类型，详见 9.4。

### 9.1 职责划分

| 能力 | AgentForge 框架层 | AgentForge Service 层 |
| --- | --- | --- |
| MCP 协议与传输 | 提供 | 不关心 |
| Server 配置存储 | 不提供 | 提供 |
| Headers / 密钥管理 | 接收字符串 | 加密存储、解密注入 |
| 工具发现与转换 | 提供 | 触发并接收结果 |
| Agent 工具注册 | 提供 | 在 Agent 构建时调用 |
| 配置管理页面 | 不提供 | 提供 |
| 权限与数据隔离 | 不提供 | 提供 |
| 健康检查与审计 | 不提供 | 提供 |

### 9.2 功能范围（本期）

本期只做 MCP 管理的最小闭环，stdio、SSE 支流、连接池等留待后续。

| 功能 | 说明 |
| --- | --- |
| 新增 / 修改 MCP 插件 | 维护名称、描述、图标 |
| 新增 / 修改 MCP Server | 维护 Server 名称、URL、传输协议、Headers |
| 插件启停 | 插件级 `status` 开关，禁用后其下 Server 不再参与 Agent 构建 |
| Server 启停 | Server 级 `status` 开关，独立于插件 |
| 删除插件 / Server | 插件逻辑删除并级联其下 Server；Server 物理删除 |
| 一键导入 HTTP MCP | 粘贴标准 `mcpServers` JSON，解析后在一个事务内写入插件与多个 Server |
| 工具列表探测 | 临时连接 Server 执行 `tools/list`，供前端预览 |
| Agent 绑定 | Agent 选择插件，再勾选插件下的多个 Server |

**本期不做**：stdio 配置、P1 协议能力、工具级持久化、工具调用审计、连接池。`transport_type` 字段为数字枚举（由 `McpTransportTypeEnum` 维护），本期只允许 1（streamable_http）与 2（sse）。

### 9.3 表设计

#### 9.3.1 表关系

```text
ai_plugin_mcp 1 ────────── N ai_plugin_mcp_server
     │                              │
     │                              ├── 一个 Server 对应一个 MCP endpoint
     │                              ├── 工具清单不落库，运行时通过 tools/list 获取
     │                              └── 被 Agent 配置中的 serverIds 引用
     │
     └── 面向用户的逻辑分组（一个插件含多个 Server）
```

设计原则：

- `ai_plugin_mcp` 是用户可见的逻辑分组，对应 MCP 品牌/能力域（例如「企查查 MCP」）。
- `ai_plugin_mcp_server` 是实际连接单元，Agent 最终选择的是 Server。
- 工具清单、工具描述和 `inputSchema` 不持久化，Agent 构建时动态发现。
- Agent 与 Server 的选择关系保存在 Agent 配置 JSON 中，不新增关系表。
- 插件采用逻辑删除；删除插件时同步逻辑删除其下 Server。

#### 9.3.2 ai_plugin_mcp 建表 SQL

```sql
-- ============================================
-- 1. MCP 插件表
-- 一个插件可以包含多个 MCP Server
-- ============================================
CREATE TABLE `ai_plugin_mcp` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name`           VARCHAR(128) NOT NULL COMMENT '插件名称',
    `description`    VARCHAR(1024)         DEFAULT NULL COMMENT '插件描述',
    `icon`           VARCHAR(512)          DEFAULT NULL COMMENT '插件图标',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `create_user_id` BIGINT       NOT NULL DEFAULT 1 COMMENT '上传人',
    `is_deleted`     INT          NOT NULL DEFAULT 0 COMMENT '是否逻辑删除 0未删除、1已删除',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_mcp_plugin_user` (`create_user_id`, `is_deleted`),
    KEY `idx_mcp_plugin_name` (`create_user_id`, `name`, `is_deleted`),
    KEY `idx_mcp_plugin_status` (`status`, `is_deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = 'MCP插件表';
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `name` | 插件展示名称，同一用户下未删除记录唯一（服务层校验） |
| `description` | 插件说明，用于列表和选择弹窗展示 |
| `icon` | 插件图标地址，可为空 |
| `status` | 插件级启停；禁用后其下 Server 不再参与 Agent 构建 |
| `create_user_id` | 数据隔离字段，普通用户只能操作自己的插件 |
| `is_deleted` | 逻辑删除标记 |

#### 9.3.3 ai_plugin_mcp_server 建表 SQL

```sql
-- ============================================
-- 2. MCP Server 表
-- 一个 MCP 插件可以关联多个 Server
-- ============================================
CREATE TABLE `ai_plugin_mcp_server` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `plugin_id`      BIGINT        NOT NULL COMMENT '所属MCP插件ID',
    `server_name`    VARCHAR(128)  NOT NULL COMMENT 'Server名称',
    `server_url`     VARCHAR(2048) NOT NULL COMMENT 'MCP服务完整URL',
    `transport_type` TINYINT      NOT NULL DEFAULT 1
        COMMENT '传输协议：1streamable_http 2sse',
    `headers`        TEXT                   DEFAULT NULL COMMENT 'HTTP请求头配置（敏感字段加密存储）',
    `status`         TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `create_user_id` BIGINT        NOT NULL DEFAULT 1 COMMENT '上传人',
    `is_deleted`     INT           NOT NULL DEFAULT 0 COMMENT '是否逻辑删除 0未删除、1已删除',
    `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plugin_server_name` (`plugin_id`, `server_name`),
    KEY `idx_mcp_server_plugin` (`plugin_id`, `status`, `is_deleted`),
    KEY `idx_mcp_server_user` (`create_user_id`, `is_deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = 'MCP插件Server配置表';
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `plugin_id` | 关联 `ai_plugin_mcp.id` |
| `server_name` | 同一插件下唯一；同时作为工具别名来源 |
| `server_url` | MCP endpoint，本期必填且为 `http://` / `https://` |
| `transport_type` | 数字枚举（`McpTransportTypeEnum`）：1 = streamable_http（默认）、2 = sse；后续扩展 `stdio` |
| `headers` | HTTP Headers JSON 对象，例如 `{"Authorization":"Bearer ..."}` |
| `status` | Server 级启停；禁用后 Agent 构建时跳过 |
| `create_user_id` | 数据隔离字段 |
| `is_deleted` | 逻辑删除标记（插件级删除时级联） |

#### 9.3.4 示例数据（企查查 MCP）

```text
ai_plugin_mcp
id  name          status
1   企查查 MCP      1

ai_plugin_mcp_server
id   plugin_id  server_name    transport_type    status
101  1          qcc-company    1                 1
102  1          qcc-risk       1                 1
103  1          qcc-ipr        1                 1
```

用户粘贴包含三个 Server 的 `mcpServers` JSON，后端解析后执行**一次事务**，写入一条 `ai_plugin_mcp` 和三条 `ai_plugin_mcp_server`。

#### 9.3.5 关键约束与落地规则

1. **同一插件下 `server_name` 唯一**：由数据库唯一键 `uk_plugin_server_name` 兜底。因此 Server 删除采用**物理删除**，避免逻辑删除后重新添加同名 Server 触发唯一键冲突；同时业务查询仍带 `is_deleted = 0` 过滤，兼容插件级级联逻辑删除。
2. **插件名称同一用户下唯一**：服务层校验 `create_user_id + name + is_deleted = 0`，不建唯一键，避免历史删除记录阻塞。
3. **工具别名在运行时生成**：格式 `mcp_{pluginId}_{normalizedServerName}`，`normalizedServerName` 将非 `[A-Za-z0-9_-]` 字符替换为 `_`。不落库，避免 Server 改名后别名与历史 Agent 配置不一致。
4. **`transport_type` 与配置字段配套**：本期只允许 1（streamable_http）/ 2（sse），两者都用 `server_url`，`stdio` 扩展时再增加 `command/args/env` 字段。
5. **`headers` 不直接存明文密钥**：生产环境应加密存储，查询接口返回脱敏值，运行时解密注入 `StreamableHttpMcpTransport`。
6. **插件删除级联**：`ai_plugin_mcp.is_deleted = 1` 时，同步将其下 `is_deleted = 0` 的 Server 置为 1。
7. **工具清单不落库**：不建 `mcp_tool` 表，避免工具描述、参数 Schema 与服务端不一致。
8. **Agent 选择关系不落独立表**：使用 Agent 配置中的 `mcpPlugins` 数组，结构见 9.7。

#### 9.3.6 SQL 文件放置建议

```text
agentforge-service/sql/increment/1.0.x/2026xxxxxxxxxx_v1.0.x.sql
agentforge-service/sql/local/1.0.x/2026xxxxxxxxxx_v1.0.x.sql
```

`increment` 与 `local` 两份脚本保持一致。

### 9.4 后端模块设计

#### 9.4.1 模块包结构

MCP 属于「插件」大类，统一规划到 `modules/plugin`，与 MCP 并列的后续插件（HTTP 插件等）共用同一模块，避免每类插件新增一个顶层模块。目录结构与现有 `modules/model`、`modules/agent` 保持一致：

```text
cloud.changlu.agentforge.service.modules.plugin
├── controller
│   └── McpPluginController.java
├── service
│   ├── IMcpPluginService.java
│   └── impl
│       └── McpPluginServiceImpl.java
├── dao
│   ├── McpPluginMapper.java
│   └── McpPluginServerMapper.java
├── domain
│   ├── mcp
│   │   ├── pojo
│   │   │   ├── McpPluginPojo.java
│   │   │   └── McpPluginServerPojo.java
│   │   ├── req
│   │   │   ├── McpPluginSaveReq.java
│   │   │   ├── McpServerSaveReq.java
│   │   │   ├── McpStatusReq.java
│   │   │   ├── McpServerPageQueryReq.java
│   │   │   └── McpImportReq.java
│   │   └── vo
│   │       ├── McpPluginVo.java
│   │       └── McpServerVo.java
├── enums
│   └── McpTransportTypeEnum.java
└── component
    └── agent
        └── mcp
            └── McpAgentToolLoader.java
```

模块内按插件类型分子目录（`domain/mcp`），新增插件类型时在 `domain/{pluginType}` 下扩展，Controller、Service 命名也遵循 `{PluginType}PluginController` 约定。

职责划分：

| 类 | 职责 |
| --- | --- |
| `McpPluginController` | 插件与 Server 的统一 REST 入口 |
| `IMcpPluginService` | MCP 管理能力接口 |
| `McpPluginServiceImpl` | 配置校验、持久化、导入导出、工具探测 |
| `McpPluginMapper` | `ai_plugin_mcp` 表访问 |
| `McpPluginServerMapper` | `ai_plugin_mcp_server` 表访问 |
| `McpTransportTypeEnum` | 传输类型数字枚举（1 = streamable_http、2 = sse）、`ofText` 兼容导入字符串与校验 |
| `McpAgentToolLoader` | 根据 Agent 选择的 Server 构建 MCP 工具 |

Mapper XML 放在：

```text
agentforge-service-web/src/main/resources/cloud/changlu/agentforge/service/modules/plugin/mapper
```

> 插件与 Server 共用一个 Controller，路径以 `/server` 区分，与 ai-chat 实现保持一致，减少前端调用面。`modules/plugin` 是插件的统一入口，MCP 只是其中第一个落地类型。

#### 9.4.2 核心接口

统一前缀：`/api/mcp-plugin`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/list` | 查询当前用户 MCP 插件列表（内联 Server），支持 `keyword` |
| GET | `/{id}` | 查询插件详情及其 Server |
| POST | `/upsert` | 新增或修改插件 |
| POST | `/{id}/status` | 启用或禁用插件 |
| DELETE | `/{id}` | 删除插件及其 Server |
| POST | `/import` | 从标准 `mcpServers` JSON 一键导入 HTTP MCP |
| GET | `/{id}/export` | 导出插件下全部 Server 为标准 `mcpServers` JSON |
| POST | `/server/page` | 分页查询 Server |
| POST | `/server/upsert` | 新增或修改 Server |
| POST | `/server/{id}/status` | 启用或禁用 Server |
| DELETE | `/server/{id}` | 删除 Server（物理删除） |
| GET | `/server/{id}/tools` | 临时连接 Server 执行 `tools/list` |

Controller 骨架：

```java
@RestController
@RequestMapping("/api/mcp-plugin")
@RequiredArgsConstructor
public class McpPluginController {

    private final IMcpPluginService mcpPluginService;

    @GetMapping("/list")
    public ResponseResult<List<McpPluginVo>> list(
            @RequestParam(value = "keyword", required = false) String keyword) {
        return ResponseResult.success(mcpPluginService.list(keyword));
    }

    @GetMapping("/{id}")
    public ResponseResult<McpPluginVo> detail(@PathVariable Long id) {
        return ResponseResult.success(mcpPluginService.detail(id));
    }

    @GetMapping("/{id}/export")
    public ResponseResult<String> exportServers(@PathVariable Long id) {
        return ResponseResult.success(mcpPluginService.exportServers(id));
    }

    @PostMapping("/server/page")
    public ResponseResult<PageResult<List<McpServerVo>>> pageServers(
            @RequestBody(required = false) McpServerPageQueryReq query) {
        return ResponseResult.success(mcpPluginService.pageServers(query));
    }

    @PostMapping("/upsert")
    public ResponseResult<McpPluginVo> savePlugin(@RequestBody McpPluginSaveReq req) {
        return ResponseResult.success(mcpPluginService.savePlugin(req));
    }

    @PostMapping("/{id}/status")
    public ResponseResult<Void> updatePluginStatus(
            @PathVariable Long id, @RequestBody McpStatusReq req) {
        mcpPluginService.updatePluginStatus(id, req == null ? null : req.getStatus());
        return ResponseResult.success();
    }

    @DeleteMapping("/{id}")
    public ResponseResult<Void> deletePlugin(@PathVariable Long id) {
        mcpPluginService.deletePlugin(id);
        return ResponseResult.success();
    }

    @PostMapping("/server/upsert")
    public ResponseResult<McpServerVo> saveServer(@RequestBody McpServerSaveReq req) {
        return ResponseResult.success(mcpPluginService.saveServer(req));
    }

    @PostMapping("/server/{id}/status")
    public ResponseResult<Void> updateServerStatus(
            @PathVariable Long id, @RequestBody McpStatusReq req) {
        mcpPluginService.updateServerStatus(id, req == null ? null : req.getStatus());
        return ResponseResult.success();
    }

    @DeleteMapping("/server/{id}")
    public ResponseResult<Void> deleteServer(@PathVariable Long id) {
        mcpPluginService.deleteServer(id);
        return ResponseResult.success();
    }

    @GetMapping("/server/{id}/tools")
    public ResponseResult<List<McpTool>> listServerTools(@PathVariable Long id) {
        return ResponseResult.success(mcpPluginService.listServerTools(id));
    }

    @PostMapping("/import")
    public ResponseResult<McpPluginVo> importServers(@RequestBody McpImportReq req) {
        return ResponseResult.success(mcpPluginService.importServers(req));
    }
}
```

#### 9.4.3 Service 方法规划

```java
public interface IMcpPluginService {

    List<McpPluginVo> list(String keyword);

    McpPluginVo detail(Long id);

    PageResult<List<McpServerVo>> pageServers(McpServerPageQueryReq query);

    McpPluginVo savePlugin(McpPluginSaveReq req);

    void updatePluginStatus(Long id, Integer status);

    void deletePlugin(Long id);

    McpServerVo saveServer(McpServerSaveReq req);

    void updateServerStatus(Long id, Integer status);

    void deleteServer(Long id);

    List<McpTool> listServerTools(Long id);

    McpPluginVo importServers(McpImportReq req);

    String exportServers(Long id);
}
```

#### 9.4.4 请求与响应模型

| 模型 | 关键字段 |
| --- | --- |
| `McpPluginSaveReq` | `id`(可选)、`name`、`description`、`icon`、`status` |
| `McpServerSaveReq` | `id`(可选)、`pluginId`、`serverName`、`serverUrl`、`transportType`、`headers`(Map)、`status` |
| `McpStatusReq` | `status` |
| `McpServerPageQueryReq` | `currentPage`、`pageSize`、`pluginId`、`serverName`、`transportType`、`status` |
| `McpImportReq` | `name`、`description`、`icon`、`mcpServers`(JsonNode) |
| `McpPluginVo` | `id`、`name`、`description`、`icon`、`status`、`createUserId`、`createTime`、`modifyTime`、`servers` |
| `McpServerVo` | `id`、`pluginId`、`serverName`、`serverUrl`、`transportType`、`headers`、`status`、`createTime`、`modifyTime` |

`McpPluginVo` 内联 `servers`，前端列表与 Agent 选择弹窗可一次拿全，避免 N+1 查询。

### 9.5 核心服务实现

#### 9.5.1 配置校验

`savePlugin` / `saveServer` 在写库前统一校验：

| 校验项 | 规则 |
| --- | --- |
| `name` | 非空；长度 ≤ 128；同一用户下未删除记录唯一 |
| `pluginId` | Server 必填；插件存在且未删除 |
| `serverName` | 非空；长度 ≤ 128；同一插件下未删除记录唯一 |
| `serverUrl` | 非空；长度 ≤ 2048；以 `http://` 或 `https://` 开头 |
| `transportType` | 数字枚举：1 = streamable_http、2 = sse；为空按 1；导入片段兼容字符串写法，自动转编码 |
| `headers` | 必须可序列化为 JSON 对象 |
| `status` | 仅 0 / 1 |

写库规则：

- `id` 为空执行 `insert`，写入 `create_user_id`、`is_deleted = 0`、默认 `status = 1`。
- `id` 非空执行 `update`，**不允许修改 `plugin_id`**。
- 插件更新使用显式 `set(...)`，避免 `updateById` 把未变更字段写空。

#### 9.5.2 删除与启停

插件删除（事务）：

```sql
UPDATE ai_plugin_mcp
   SET is_deleted = 1
 WHERE id = ? AND is_deleted = 0;

UPDATE ai_plugin_mcp_server
   SET is_deleted = 1
 WHERE plugin_id = ? AND is_deleted = 0;
```

Server 删除采用物理删除：

```sql
DELETE FROM ai_plugin_mcp_server WHERE id = ?;
```

原因：`uk_plugin_server_name` 是物理唯一键，逻辑删除后再新增同名 Server 会冲突。

启停只更新 `status`，不修改其他配置。

#### 9.5.3 工具列表探测

`listServerTools(serverId)` 使用临时 Client，不注册到任何 Agent：

```java
McpClient client = DefaultMcpClient.builder()
        .serverAlias(server.getServerName())
        .transport(StreamableHttpMcpTransport.builder()
                .endpoint(server.getServerUrl())
                .headers(readHeaders(server.getHeaders()))
                .build())
        .build();
try {
    return client.listTools();
} catch (RuntimeException e) {
    throw new AiServiceException("MCP Server连接或工具查询失败: " + e.getMessage(), e);
} finally {
    client.close();
}
```

- 超时使用 `StreamableHttpMcpTransport` 默认值（连接 10s、读取 60s）。
- `transport_type = 2`（sse）时本期仍复用 Streamable HTTP 传输，SSE 专属传输留待 P1。
- 探测失败不修改 `status`，仅把异常信息返回前端。

#### 9.5.4 一键导入 mcpServers JSON

请求体：

```json
{
  "name": "企查查 MCP",
  "description": "企业信息查询",
  "icon": "/icons/qcc.png",
  "mcpServers": {
    "qcc-company": {
      "url": "https://agent.qcc.com/mcp/company/stream",
      "headers": {
        "Authorization": "Bearer MELecV••••••••••••••••qtPO"
      }
    },
    "qcc-risk": {
      "url": "https://agent.qcc.com/mcp/risk/stream",
      "headers": {
        "Authorization": "Bearer MELecV••••••••••••••••qtPO"
      }
    }
  }
}
```

导入流程：

```text
校验 name 与 mcpServers
    │
    ▼
遍历 mcpServers（collectHttpServers 递归解包）
    │
    ├── 有 url：按 streamable_http 收集
    ├── 无 url 且是对象：递归解包（兼容平台二次包装的导出结构）
    └── 无 url 且非对象：忽略（stdio 条目本期不支持）
    │
    ▼
无可用 HTTP Server ──> 抛业务异常，整批不落库
    │
    ▼
@Transactional：保存插件 ──> 逐条保存 Server
    │
    ▼
返回插件详情（含 servers）
```

要点：

- 整个导入在一个事务内完成，任一步异常全部回滚。
- 只导入 `streamable_http`；`command`/`args`/`env` 条目静默跳过。
- `headers` 必须是 JSON 对象，否则抛异常并回滚。
- `transportType` 缺省为 1（streamable_http）；支持传入数字编码或字符串（`streamable_http`/`sse`），统一转为数字编码存储。

#### 9.5.5 导出 mcpServers JSON

`exportServers(pluginId)` 反向拼装标准配置，便于复用与迁移：

```json
{
  "mcpServers": {
    "qcc-company": {
      "url": "https://agent.qcc.com/mcp/company/stream",
      "headers": {
        "Authorization": "Bearer MELecV••••••••••••••••qtPO"
      }
    }
  }
}
```

> 导出前需按安全要求对 `Authorization` 等敏感 Header 脱敏或直接不导出，具体策略见 9.9。

### 9.6 数据隔离与权限

| 规则 | 说明 |
| --- | --- |
| 用户隔离 | 非管理员仅能看到 / 操作 `create_user_id = currentUserId` 的记录 |
| 管理员 | 可查看和操作全部记录 |
| 插件归属 | Server 的权限跟随所属插件 `create_user_id` |
| 未登录 | 直接返回未登录业务异常 |

所有更新、删除、启停操作都要先 `requirePlugin` / `requireServer` 再 `assertOwner`，禁止仅凭 ID 操作。

### 9.7 Agent 配置与运行时装配

Agent 配置中只保存插件与 Server 的选择关系，不保存工具清单：

```json
{
  "agentConfig": {
    "mcpPlugins": [
      {
        "pluginId": 1,
        "serverIds": [101, 102]
      }
    ]
  }
}
```

与 ai-chat 的约定一致：按插件分组，`serverIds` 为该插件下勾选的 Server。新增字段时保持向后兼容，旧配置缺少 `mcpPlugins` 视为未选择。

前端交互：

1. 打开「选择 MCP 服务」弹窗，按插件展示，插件可整体勾选。
2. 勾选插件后展开其下 Server 支持单独勾选；`serverIds` 为空表示调用该插件全部（含后续新增）服务。
3. 只展示 `status = 1` 的插件与 Server。
4. 确认后回传 `[{ pluginId, serverIds }]`（持久化到 agentConfig.mcpPlugins，仅保留必要字段）。

Agent 构建时 `McpAgentToolLoader` 负责装配：

```text
读取 agentConfig.mcpPlugins
    │
    ▼
按 pluginId 加载插件，过滤 status != 1 或 is_deleted = 1
    │
    ▼
按 serverIds 加载 Server（为空则加载全部），过滤 status = 1 且 is_deleted = 0
    │
    ▼
逐个 Server：
    ├── alias = mcp_{pluginId}_{normalize(serverName)}
    ├── 构建 StreamableHttpMcpTransport（endpoint + headers）
    ├── 构建 DefaultMcpClient
    ├── McpToolFactory.buildTools(client, alias)
    └── 合并进 ToolService
    │
    ▼
单个 Server 失败：关闭 Client、记录 WARN、跳过，不阻断 Agent 构建；
加载成功的 Client 由 ToolExecutor 持有继续服务，不提前 close()
```

关键代码形态：

```java
String alias = "mcp_" + plugin.getId() + "_" + normalizeAlias(server.getServerName());
McpClient client = DefaultMcpClient.builder()
        .serverAlias(alias)
        .transport(StreamableHttpMcpTransport.builder()
                .endpoint(server.getServerUrl())
                .headers(parseHeaders(server.getHeaders()))
                .build())
        .build();
Map<ToolSpecification, ToolExecutor> tools =
        McpToolFactory.buildTools(client, alias, null, null);
toolService.tools(tools);
```

工具名形如 `mcp_1_qcc-company__get_company_info`，其中 `normalizeAlias` 会把非 `[A-Za-z0-9_-]` 字符替换为 `_`。

### 9.8 生命周期与失败隔离

当前框架不提供连接池。AgentForge Service 层：

- Agent 构建时创建 Client。
- Agent 销毁、配置刷新或应用关闭时调用 `close()`。
- 不要跨租户、跨用户共享同一个 Client。
- 配置变更时主动失效旧 Client；如引入缓存，使用 `serverId + modifyTime` 作为版本键。

失败隔离按 Server 粒度：

```text
加载 Server A
    ├── 创建 Transport / Client
    ├── tools/list
    ├── buildTools
    └── 注册到 ToolService
         ├── 成功：继续下一个
         └── 失败：关闭 Client、记录日志、跳过该 Server
```

单个 Server 的握手或工具发现失败不阻断 Agent 构建。`McpProtocolException` 与 `McpTransportException` 由 Service 层捕获并降级。

### 9.9 安全建议

1. `Authorization`、API Key、Cookie 等 Headers 加密存储，查询接口脱敏（如 `Bearer MELecV••••qtPO`），导出接口默认脱敏或需显式授权。
2. 日志中禁止输出完整 Headers 和工具调用参数中的敏感字段。
3. 对 `server_url` 增加 SSRF 防护，限制内网地址、回环地址和元数据服务地址。
4. 为连接、读取和工具调用设置超时上限。
5. 对工具数量、Schema 大小和结果大小设置上限。
6. 对 MCP 工具调用记录审计日志。
7. Service 层不要直接把用户输入拼进 URL 或后续 stdio 命令。
8. 导入时限制 `mcpServers` 条目数量（建议 ≤ 50），避免一次请求创建过多 Server。

## 10. 当前能力矩阵

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| JSON-RPC 2.0 | 已支持 | Request、Notification、Result、Error |
| `initialize` | 已支持 | 默认协议版本 `2025-06-18` |
| `notifications/initialized` | 已支持 | 握手成功后发送 |
| `tools/list` | 已支持 | 仅首页，未处理 `nextCursor` |
| `tools/call` | 已支持 | 支持文本、结构化内容与 `isError` |
| stdio Transport | 已支持 | 子进程、换行分帧、stderr 排空 |
| Streamable HTTP | 已支持 | JSON 与请求范围 SSE |
| HTTP Session ID | 未支持 | 不读取、不回传 `Mcp-Session-Id` |
| HTTP GET SSE 支流 | 未支持 | 仅请求范围 SSE |
| 自动协议探测 | 未支持 | 仅 legacy `initialize` |
| Resources | 未支持 | 仅 Tools |
| Prompts | 未支持 | 仅 Tools |
| Sampling / Roots / Elicitation | 未支持 | 未实现 |
| 工具别名 | 已支持 | `alias__toolName` |
| 工具白名单 / 黑名单 | 已支持 | 按远程工具名过滤 |
| 多 Server 聚合 | 已支持 | 需保证 alias 唯一 |
| 工具元数据 | 已支持 | `mcp.server`、`mcp.tool` |
| 连接池 | 未支持 | 由调用方管理 Client |
| 自动重试 / 熔断 / 限流 | 未支持 | 由 AgentForge Service 层或后续版本提供 |
| MCP 配置管理 | 已设计 | 表、接口与流程见第 9 章，尚未落地代码 |
| 一键导入 HTTP MCP | 已设计 | `mcpServers` JSON 事务导入，见 9.5.4 |
| Agent 绑定多 Server | 已设计 | `agentConfig.mcpPlugins[].serverIds`，见 9.7 |
| stdio 配置管理 | 未支持 | 表字段未预留，属于 P1 |

## 11. 测试与验证

### 11.1 离线单元测试

| 测试 | 覆盖点 |
| --- | --- |
| `DefaultMcpClientTest` | 握手顺序、`tools/list`、`tools/call`、协议异常、关闭传输 |
| `McpToolFactoryTest` | 别名规则、显式别名优先、工具过滤、Schema 映射、元数据 |
| `McpToolExecutorTest` | 文本结果、结构化结果、`isError`、协议/传输异常 |
| `StreamableHttpMcpTransportTest` | JSON 响应、SSE 响应、Headers、HTTP 错误、SSE 解析 |
| `JsonRpcCodecTest` | 请求/通知编码、ID 提取、结果解析、错误转换 |

### 11.2 本地端到端测试

`McpStreamableHttpServerTest` 使用 JDK 内置 `HttpServer` 模拟 MCP Server，覆盖：

- 真实 HTTP socket。
- `JdkHttpTransport`。
- `StreamableHttpMcpTransport`。
- JSON 与 SSE 两种响应。
- 注册到 `ToolService` 并真实执行工具。

### 11.3 真实服务测试

| 测试 | 依赖 | 说明 |
| --- | --- | --- |
| `McpHttpLiveTest` | 公网 | 连接 DeepWiki 等公开 HTTP MCP Server |
| `McpStdioLiveTest` | npx + 网络 | 启动 filesystem MCP Server |
| `McpPublicServersLiveTest` | npx + 网络 | 批量探测公开 stdio / HTTP Server |
| `McpRealHttpTest` | 公网 | 将真实 HTTP MCP 工具注册进 `ToolService` 并调用 |
| `McpRealStudioTest` | npx + 网络 | 真实 stdio MCP 工具 + 本地 `@Tool` + `ReActAgent` |

开关：

```bash
# 显式开启实时测试
mvn test -Dagentforge.mcp.live=true

# 关闭默认开启的真实应用测试
mvn test -Dagentforge.mcp.live=false
```

说明：

- `McpHttpLiveTest`、`McpStdioLiveTest`、`McpPublicServersLiveTest` 默认关闭，需要显式设置 `agentforge.mcp.live=true`。
- `McpRealHttpTest`、`McpRealStudioTest` 默认尝试运行，网络不可达或 `npx` 不存在时会自动跳过。

## 12. 后续演进

### 12.1 协议能力

- 支持 `tools/list` 的 `nextCursor` 分页。
- 支持 `Mcp-Session-Id` 的接收与回传。
- 支持 Streamable HTTP 的 GET SSE 支流与断线恢复。
- 增加现代协议探测（`server/discover`）并回退 legacy `initialize`。
- 支持 Resources、Prompts、Sampling、Roots、Elicitation。

### 12.2 传输能力

- SSE legacy Transport。
- 可配置的连接、读取、工具调用超时。
- HTTP 代理与自定义 SSL 上下文。
- 官方 MCP Java SDK 可选桥接模块，保持核心模块零依赖。

### 12.3 运行治理

- Client 连接池或按配置版本缓存。
- 工具清单缓存与定时刷新。
- 工具调用重试、熔断、限流。
- Server 健康状态与最近错误。
- 工具数量、Schema 大小、结果大小上限。
- 同名工具冲突检测与可读别名管理。

### 12.4 安全与审计

- Headers 加密与脱敏。
- stdio 命令白名单与沙箱。
- SSRF 防护。
- MCP 工具调用审计。
- 参数与结果敏感信息过滤。

## 13. 核心代码索引

### 13.1 MCP 框架层

| 文件 | 职责 |
| --- | --- |
| `mcp/McpClient.java` | MCP Client 接口 |
| `mcp/DefaultMcpClient.java` | 惰性初始化、握手、工具发现、工具调用 |
| `mcp/McpToolFactory.java` | MCP 工具到 `ToolSpecification` 的工厂 |
| `mcp/McpToolExecutor.java` | MCP 工具到 `ToolExecutor` 的适配器 |
| `mcp/McpProtocolException.java` | JSON-RPC 协议异常 |
| `mcp/McpTransportException.java` | 传输异常 |
| `mcp/domain/McpTool.java` | 工具定义 |
| `mcp/domain/McpCallToolResult.java` | 工具调用结果 |
| `mcp/domain/McpContent.java` | 内容块 |
| `mcp/domain/McpServerInfo.java` | 服务端信息 |
| `mcp/domain/McpCapabilities.java` | 服务端能力 |
| `mcp/transport/McpTransport.java` | 传输 SPI |
| `mcp/transport/StdioMcpTransport.java` | stdio 传输 |
| `mcp/transport/StreamableHttpMcpTransport.java` | Streamable HTTP 传输 |
| `mcp/support/JsonRpcCodec.java` | JSON-RPC 编解码 |
| `mcp/support/McpJson.java` | JSON 参数转换 |
| `mcp/support/McpToolSpecificationMapper.java` | JSON Schema 映射 |
| `mcp/support/McpResultConverter.java` | 调用结果文本化 |

### 13.2 依赖的框架契约

| 文件 | 职责 |
| --- | --- |
| `model/tool/ToolExecutor.java` | 工具执行器接口 |
| `model/tool/spec/ToolSpecification.java` | 工具描述 |
| `model/tool/spec/ToolParameters.java` | 工具参数 Schema |
| `model/tool/execution/ToolExecutionResult.java` | 工具执行结果 |
| `model/tool/execution/ToolService.java` | 工具注册与模型调用循环 |
| `model/chat/message/ToolExecutionRequest.java` | 模型产生的工具调用请求 |
| `model/http/HttpTransport.java` | HTTP 传输 SPI |
| `model/http/JdkHttpTransport.java` | 基于 `HttpURLConnection` 的 HTTP 实现 |

### 13.3 测试

| 文件 | 职责 |
| --- | --- |
| `src/test/.../mcp/DefaultMcpClientTest.java` | 协议客户端单元测试 |
| `src/test/.../mcp/McpToolFactoryTest.java` | 工具工厂单元测试 |
| `src/test/.../mcp/McpToolExecutorTest.java` | 工具执行器单元测试 |
| `src/test/.../mcp/transport/StreamableHttpMcpTransportTest.java` | HTTP 传输单元测试 |
| `src/test/.../mcp/support/JsonRpcCodecTest.java` | JSON-RPC 编解码测试 |
| `src/test/.../mcp/McpStreamableHttpServerTest.java` | 本地 HTTP 端到端测试 |
| `src/test/.../mcp/McpStdioLiveTest.java` | 真实 stdio 测试 |
| `src/test/.../mcp/McpHttpLiveTest.java` | 真实 HTTP 测试 |
| `src/test/.../mcp/McpPublicServersLiveTest.java` | 公开 Server 探测 |
| `src/test/.../mcp/real/McpRealHttpTest.java` | 真实 HTTP 工具接入测试 |
| `src/test/.../mcp/real/McpRealStudioTest.java` | 真实 stdio + ReActAgent 测试 |

以上 `src/test/.../mcp` 路径位于：

```text
agentforge-framework/agentforge-agent-core/src/test/java/cloud/changlu/agentforge/agent/tool/mcp
```

## 14. 总结

AgentForge 的 MCP 方案采用“自研轻量协议栈 + 统一工具契约”的路线：

```text
MCP Server
    │
    ▼
McpTransport（stdio / Streamable HTTP）
    │
    ▼
DefaultMcpClient（initialize / tools/list / tools/call）
    │
    ▼
McpToolFactory（别名、过滤、Schema 映射）
    │
    ▼
ToolService（统一工具表）
    │
    ▼
ReActAgent / AgentForge Agent
```

核心特点：

- **零第三方依赖**：不引入官方 SDK、Jackson、Reactor。
- **Java 8 兼容**：适合低版本运行时和嵌入式场景。
- **统一契约**：MCP 工具与本地工具、HTTP 工具共用 `ToolService`。
- **动态发现**：Agent 装配时实时调用 `tools/list`，不持久化工具清单。
- **失败可隔离**：协议异常与传输异常有明确边界，AgentForge Service 层可按 Server 降级。
- **边界清晰**：当前只覆盖 Tools 与两种主要传输，Session、SSE 支流、Resources 等能力留待后续版本。

该方案已经能够支撑 AgentForge 在当前阶段接入主流 MCP Server，并为后续协议扩展、连接治理和安全加固保留了清晰的演进路径。
