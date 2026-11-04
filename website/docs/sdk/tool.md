---
title: Tool 扩展接口设计
sidebar_label: Tool扩展接口设计
description: 将本地 Java 方法、HTTP 插件与 MCP 服务统一转换为模型可调用的 Function Call 工具
sidebar_position: 5
displayed_sidebar: sdkSidebar
---

# Tool 扩展接口设计

Tool 层负责把真实世界的能力转换成模型可理解的 Function Call：声明工具名、描述、参数 Schema 与执行器，并在模型请求调用时完成参数绑定、执行和结果写回。

AgentForge 支持三类工具来源：

| 来源 | 适用场景 | 工厂 |
| --- | --- | --- |
| Local Tool | 已有 Java 方法、内部服务 | `ToolService.tools(...)` 自动扫描 `@Tool` |
| HTTP Plugin | 三方 REST API、curl 脚本 | `HttpToolFactory` |
| MCP Server | 标准 MCP 服务、stdio / streamable-http | `McpToolFactory` |

三类工具最终都会注册进同一个 `ToolService`，对 Agent 完全透明。

本文是 Tool 扩展总览，负责说明统一契约、注册方式和执行策略。需要落地某一种工具时，按下面的专题阅读“设计思路 → Agent SDK 用法 → 如何继续扩展”：

| 专题 | 适用场景 |
| --- | --- |
| [JavaLocalTool 对接](./tool/java-local) | 把已有 Java 方法封装成类型明确、可测试的本地工具 |
| [HTTP 对接](./tool/http) | 把 REST API 或 curl 描述转换为受控工具 |
| [MCP 对接](./tool/mcp) | 连接 stdio 或 Streamable HTTP MCP Server |

## 依赖（v1.0.0）

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

`ToolService`、`@Tool`、`@P`、HTTP / MCP 工具工厂均包含在该模块及其传递依赖中。

## 1. 本地 Java 工具

### 1.1 声明工具

```java
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

public class WeatherTools {

    @Tool("查询指定城市的实时天气")
    public String getWeather(
            @P(name = "city", description = "城市名称，例如：北京") String city) {
        return "{\"city\":\"" + city + "\",\"weather\":\"晴\",\"temperature\":\"26℃\"}";
    }

    @Tool(value = "把摄氏度转换为华氏度", name = "celsiusToFahrenheit")
    public double celsiusToFahrenheit(
            @P(name = "celsius", description = "摄氏度") double celsius) {
        return celsius * 9 / 5 + 32;
    }
}
```

### 1.2 @Tool

| 属性 | 说明 |
| --- | --- |
| `value` | 工具描述，多个字符串按换行拼接 |
| `name` | 工具名，为空时使用方法名 |
| `returnBehavior` | 返回值处理策略，默认 `TO_LLM` |

返回值规则：

- `String`：原样作为工具结果；
- `void`：写入字面量 `Success`；
- 其他类型：序列化为 JSON 字符串。

### 1.3 @P

| 属性 | 说明 |
| --- | --- |
| `name` | 暴露给模型的参数名，为空时使用方法参数名 |
| `value` / `description` | 参数说明，两者等价 |

> 如果编译时没有开启 `-parameters`，反射拿到的参数名可能是 `arg0`、`arg1`，此时应显式填写 `@P(name = "...")`。

### 1.4 注册到 ToolService

```java
import cloud.changlu.agentforge.model.tool.execution.ToolService;

ToolService toolService = new ToolService();
toolService.tools(new WeatherTools());
```

注册单个方法：

```java
toolService.tool(new WeatherTools(), "getWeather");
```

批量注册多个对象：

```java
toolService.tools(Arrays.asList(new WeatherTools(), new OrderTools()));
```

> 如果对象中没有任何 `@Tool` 方法，`tools(Object)` 会抛出 `IllegalArgumentException`，便于尽早发现配置错误。

## 2. 直接在模型层使用 ToolService

`ToolService` 自带完整的工具调用循环，适合不构建 Agent、只想让模型调用工具的场景：

```java
ToolChatResult result = toolService.chat(chatModel, parameters, messages);
```

它会在模型返回工具调用时执行工具、追加 `ToolExecutionResultMessage`，并重新调用模型，直到没有工具请求或达到 `maxToolCallingRoundTrips`。

## 3. HTTP 插件工具

HTTP 插件把一组 REST 方法转换为工具。支持从 curl 一键转换：

```java
import cloud.changlu.agentforge.agent.tool.http.HttpToolFactory;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpPlugin;
import cloud.changlu.agentforge.agent.tool.http.parser.curl.CurlParser;
import cloud.changlu.agentforge.agent.tool.http.parser.curl.CurlToHttpPluginConverter;

String curl = "curl -X GET 'https://api.example.com/weather?city=beijing'";

HttpPlugin plugin = new CurlToHttpPluginConverter()
        .convert(new CurlParser().parse(curl));

toolService.tools(HttpToolFactory.buildHttpTools(plugin));
```

也可以从管理后台的 JSON 配置构建 `HttpPlugin`，再交给工厂。

### 工具命名

HTTP 插件会按「插件别名 + 方法名」生成工具名，避免多个插件之间冲突。注册后可以通过以下方式确认模型看到的工具：

```java
toolService.toolSpecifications().forEach(spec ->
        System.out.println(spec.name() + " -> " + spec.description()));
```

### 思考参数注入

如果希望模型在调用工具前显式说明意图，可以开启 `__thought` 参数注入：

```java
toolService.tools(HttpToolFactory.buildHttpTools(plugin, null, true));
```

MCP 工具同样支持：

```java
McpToolFactory.buildToolsWithThought(client, "weather", true);
```

## 4. MCP 工具

### 4.1 Streamable HTTP

```java
import cloud.changlu.agentforge.agent.tool.mcp.DefaultMcpClient;
import cloud.changlu.agentforge.agent.tool.mcp.McpClient;
import cloud.changlu.agentforge.agent.tool.mcp.McpToolFactory;
import cloud.changlu.agentforge.agent.tool.mcp.transport.StreamableHttpMcpTransport;

McpClient client = DefaultMcpClient.builder()
        .serverAlias("weather")
        .transport(StreamableHttpMcpTransport.builder()
                .endpoint("https://mcp.example.com/mcp")
                .header("Authorization", "Bearer " + System.getenv("MCP_TOKEN"))
                .build())
        .build();

toolService.tools(McpToolFactory.buildTools(client));
```

### 4.2 stdio

```java
import cloud.changlu.agentforge.agent.tool.mcp.transport.StdioMcpTransport;

McpClient client = DefaultMcpClient.builder()
        .serverAlias("filesystem")
        .transport(StdioMcpTransport.of(
                "npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"))
        .build();

toolService.tools(McpToolFactory.buildTools(client));
```

### 4.3 工具过滤与命名空间

MCP 工具默认命名为 `{serverAlias}__{toolName}`，例如 `weather__get_forecast`。可以按需过滤：

```java
Map<ToolSpecification, ToolExecutor> tools = McpToolFactory.buildTools(
        client,
        "weather",
        new HashSet<>(Arrays.asList("get_forecast", "get_alerts")), // 只启用
        null);
```

多个 MCP Server 可以注册进同一个 `ToolService`，通过别名隔离名称冲突。

## 5. ToolService 执行策略

### 5.1 错误处理

默认行为：

- 参数解析失败：重新抛出；
- 工具执行失败：把错误信息作为工具结果返回模型，让模型自行修正。

可以自定义：

```java
import cloud.changlu.agentforge.model.tool.error.ToolErrorHandlerResult;

toolService.executionErrorHandler((error, context) ->
        ToolErrorHandlerResult.text("工具执行失败：" + error.getMessage()));

toolService.argumentsErrorHandler((error, context) ->
        ToolErrorHandlerResult.text("参数不合法，请检查 JSON 结构：" + error.getMessage()));
```

### 5.2 模型编造工具名

当模型请求一个不存在的工具时，默认直接抛异常。可以改为返回提示文本：

```java
toolService.hallucinatedToolNameStrategy(request ->
        ToolExecutionResultMessage.builder()
                .id(request.id())
                .toolName(request.name())
                .text("工具不存在：" + request.name())
                .isError(true)
                .build());
```

### 5.3 最大工具轮次

```java
toolService.maxToolCallingRoundTrips(20);
```

该配置只影响直接使用 `ToolService.chat(...)` 的循环；Agent 层的最大轮次由 `AgentSettings.maxSteps` 控制。

## 6. 并发执行

Agent 默认开启工具并发执行：

```java
AgentSettings.builder()
        .enableConcurrentToolExecution(true)
        .build();
```

- 仅当模型在同一轮返回多个工具调用时才并发；
- 并发结果的顺序仍与请求顺序一致；
- 需要严格串行时关闭该开关。

## 7. 工具设计建议

1. **描述比实现更重要**：模型只能看到工具名、描述和参数 Schema，描述要说明“什么时候用”；
2. **参数尽量扁平**：嵌套 JSON 会增加模型填错参数的概率；
3. **返回结构化文本**：优先返回 JSON 字符串，字段名稳定、可解析；
4. **控制返回长度**：工具结果会进入上下文，过长内容应截断或摘要；
5. **错误可读**：错误信息要能让模型理解并自行修正；
6. **工具名稳定**：工具名是模型调用契约，不要随意重命名；
7. **敏感操作加中间件**：删除、转账等高风险工具应结合审批中间件或人工确认。

下一步：阅读 [Agent 智能体](./agent) 将工具接入 ReAct 循环。
