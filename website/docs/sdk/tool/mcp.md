---
title: MCP 对接
sidebar_label: MCP 对接
description: 通过 stdio 或 Streamable HTTP 将 MCP Server 工具接入 ReActAgent
sidebar_position: 3
displayed_sidebar: sdkSidebar
---

# MCP 对接

MCP Tool 让 AgentForge 可以复用生态中的标准 MCP Server。Core 自己维护轻量的 MCP Client、JSON-RPC 和传输抽象，再把 `tools/list` 发现的工具映射为 AgentForge 的 `ToolSpecification` 和 `ToolExecutor`。

```text
McpTransport
  -> DefaultMcpClient（initialize / tools/list / tools/call）
  -> McpToolFactory
  -> ToolService
  -> ReActAgent
```

## 1. Streamable HTTP 接入

```java
McpClient client = DefaultMcpClient.builder()
        .serverAlias("weather")
        .transport(StreamableHttpMcpTransport.builder()
                .endpoint("https://mcp.example.com/mcp")
                .header("Authorization", "Bearer " + token)
                .build())
        .build();

Map<ToolSpecification, ToolExecutor> mcpTools =
        McpToolFactory.buildTools(client);

ToolService toolService = new ToolService();
toolService.tools(mcpTools);

ReActAgent agent = ReActAgent.builder()
        .agentName("mcp-agent")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .build();
```

传输层使用 JDK HTTP 实现，能够处理 JSON 响应和请求范围的 SSE 响应。连接超时、读取超时和自定义请求头可以在 `StreamableHttpMcpTransport.Builder` 中设置。

## 2. stdio 接入

```java
McpClient client = DefaultMcpClient.builder()
        .serverAlias("filesystem")
        .transport(StdioMcpTransport.of(
                "npx", "-y",
                "@modelcontextprotocol/server-filesystem",
                "/tmp/workspace"))
        .build();

ToolService toolService = new ToolService();
toolService.tools(McpToolFactory.buildTools(client));
```

stdio 传输会启动子进程，通过 stdin/stdout 交换逐行 JSON-RPC，并单独消费 stderr，避免 MCP Server 输出过多诊断信息时阻塞管道。

## 3. 工具发现、命名和过滤

MCP 工具默认使用 `{serverAlias}__{toolName}` 命名：

```text
weather__get_forecast
filesystem__read_file
```

多个 MCP Server 可以注册到同一个 `ToolService`，命名空间避免工具冲突。只启用部分工具：

```java
Set<String> enabled = new HashSet<String>(
        Arrays.asList("get_forecast", "get_alerts"));

Map<ToolSpecification, ToolExecutor> tools = McpToolFactory.buildTools(
        client, "weather", enabled, null);
```

也可以传入禁用列表，或使用 `buildToolsWithThought(...)` 给 Schema 统一加入 `__thought` 参数。

## 4. 设计思路

MCP 适配分成三层，避免协议细节泄漏到 Agent：

| 层 | 职责 |
| --- | --- |
| `McpTransport` | 负责进程管道或 HTTP 的收发，不理解工具业务 |
| `DefaultMcpClient` | 负责初始化、请求 ID、JSON-RPC、工具发现和调用 |
| `McpToolFactory` | 把远端工具 Schema 和调用器转换为 AgentForge Tool 契约 |

这样 ReActAgent 只看到 `ToolService`，不会因为工具来自 MCP 而出现另一套执行循环。

## 5. 如何扩展 MCP 对接

### 自定义传输

实现 `McpTransport` 即可接入其他通信方式：

```java
public interface McpTransport {
    void send(String message) throws IOException;
    String request(String message) throws IOException;
    boolean isOpen();
    void close();
}
```

传输实现应保证请求和响应 ID 能正确配对，并明确连接关闭、超时和服务端错误的语义。

### 自定义工具治理

在 `McpToolFactory.buildTools(...)` 后，仍然可以对映射做过滤或装饰：

```text
MCP tools/list
  -> allowlist / denylist
  -> 名称空间校验
  -> 权限/审计 Decorator
  -> ToolService
```

不要把所有远端工具无条件暴露给模型。建议按 Agent、租户和任务场景建立 allowlist，并对文件写入、命令执行、外部发信等高风险工具增加审批。

### 生命周期管理

MCP Client 的连接可以懒初始化，但进程型 stdio 客户端在应用关闭时需要调用 `close()`。长时间运行的 Service 应把 Client 生命周期放到资源管理层，不要在每次 Agent 请求中反复启动子进程。

## 6. 版本与运行边界

当前实现以统一的 MCP Client 契约接入标准工具发现和调用，具体 Server 的协议版本、传输类型和鉴权方式仍需在部署时验证。生产环境建议记录：

- Server alias、endpoint 或启动命令；
- 协议版本和初始化结果；
- 发现到的工具数量和名称；
- 调用耗时、错误类型和连接状态。

下一步：回到 [Tool 扩展设计总览](../tool)，了解三类工具如何组合到同一个 Agent。
