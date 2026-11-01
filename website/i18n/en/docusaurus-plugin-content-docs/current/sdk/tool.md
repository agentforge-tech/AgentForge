---
title: Tool Extensions
sidebar_label: Tool Extensions
description: Turn local Java methods, HTTP plugins, and MCP services into Function Call tools the model can invoke
sidebar_position: 5
displayed_sidebar: sdkSidebar
---

# Tool Extensions

The Tool layer turns real-world capabilities into Function Calls the model understands: it declares tool names, descriptions, parameter schemas, and executors, then binds arguments, executes the tool, and writes results back when the model requests a call.

AgentForge supports three tool sources:

| Source | Use case | Factory |
| --- | --- | --- |
| Local Tool | Existing Java methods and internal services | `ToolService.tools(...)` scans `@Tool` |
| HTTP Plugin | Third-party REST APIs and curl scripts | `HttpToolFactory` |
| MCP Server | Standard MCP services over stdio / streamable-http | `McpToolFactory` |

All three register into the same `ToolService` and are transparent to the Agent.

## Dependency (v1.0.0)

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

`ToolService`, `@Tool`, `@P`, and the HTTP / MCP factories are all included in this module and its transitive dependencies.

## 1. Local Java tools

### 1.1 Declare a tool

```java
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

public class WeatherTools {

    @Tool("Get the current weather for a city")
    public String getWeather(
            @P(name = "city", description = "City name, for example: Beijing") String city) {
        return "{\"city\":\"" + city + "\",\"weather\":\"Sunny\",\"temperature\":\"26C\"}";
    }

    @Tool(value = "Convert Celsius to Fahrenheit", name = "celsiusToFahrenheit")
    public double celsiusToFahrenheit(
            @P(name = "celsius", description = "Celsius") double celsius) {
        return celsius * 9 / 5 + 32;
    }
}
```

### 1.2 @Tool

| Attribute | Description |
| --- | --- |
| `value` | Tool description; multiple strings are joined with newlines |
| `name` | Tool name; defaults to the method name when blank |
| `returnBehavior` | Result handling strategy, `TO_LLM` by default |

Return value rules:

- `String`: used as the tool result as-is;
- `void`: the literal `Success` is written;
- Any other type: serialized to a JSON string.

### 1.3 @P

| Attribute | Description |
| --- | --- |
| `name` | Parameter name exposed to the model; defaults to the method parameter name |
| `value` / `description` | Parameter description; the two are equivalent |

> If compilation does not enable `-parameters`, reflection may see `arg0`, `arg1`, and so on. Set `@P(name = "...")` explicitly in that case.

### 1.4 Register with ToolService

```java
import cloud.changlu.agentforge.model.tool.execution.ToolService;

ToolService toolService = new ToolService();
toolService.tools(new WeatherTools());
```

Register a single method:

```java
toolService.tool(new WeatherTools(), "getWeather");
```

Register multiple objects:

```java
toolService.tools(Arrays.asList(new WeatherTools(), new OrderTools()));
```

> If an object has no `@Tool` methods, `tools(Object)` throws `IllegalArgumentException`, which surfaces configuration errors early.

## 2. Use ToolService directly at the model layer

`ToolService` includes a complete tool-calling loop for cases where you want tool use without building an Agent:

```java
ToolChatResult result = toolService.chat(chatModel, parameters, messages);
```

When the model returns tool calls, it executes them, appends `ToolExecutionResultMessage` entries, and calls the model again until no tools are requested or `maxToolCallingRoundTrips` is reached.

## 3. HTTP plugin tools

HTTP plugins turn a group of REST methods into tools. curl conversion is supported:

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

You can also build an `HttpPlugin` from the admin JSON configuration and pass it to the factory.

### Tool naming

HTTP plugins generate tool names from plugin alias and method name to avoid collisions. Inspect what the model sees:

```java
toolService.toolSpecifications().forEach(spec ->
        System.out.println(spec.name() + " -> " + spec.description()));
```

### Thought parameter injection

To require the model to state its intent before calling a tool, enable `__thought` injection:

```java
toolService.tools(HttpToolFactory.buildHttpTools(plugin, null, true));
```

MCP tools support the same option:

```java
McpToolFactory.buildToolsWithThought(client, "weather", true);
```

## 4. MCP tools

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

### 4.3 Filtering and namespacing

MCP tools are named `{serverAlias}__{toolName}` by default, for example `weather__get_forecast`. Filter as needed:

```java
Map<ToolSpecification, ToolExecutor> tools = McpToolFactory.buildTools(
        client,
        "weather",
        new HashSet<>(Arrays.asList("get_forecast", "get_alerts")), // allow list
        null);
```

Multiple MCP servers can register into one `ToolService`; aliases prevent name collisions.

## 5. ToolService execution strategy

### 5.1 Error handling

Default behavior:

- Argument parsing failure: rethrow;
- Tool execution failure: send the error to the model as a tool result so it can correct itself.

Custom handlers:

```java
import cloud.changlu.agentforge.model.tool.error.ToolErrorHandlerResult;

toolService.executionErrorHandler((error, context) ->
        ToolErrorHandlerResult.text("Tool execution failed: " + error.getMessage()));

toolService.argumentsErrorHandler((error, context) ->
        ToolErrorHandlerResult.text("Invalid arguments. Check the JSON structure: " + error.getMessage()));
```

### 5.2 Hallucinated tool names

When the model requests a tool that does not exist, the default is to throw. You can return a message instead:

```java
toolService.hallucinatedToolNameStrategy(request ->
        ToolExecutionResultMessage.builder()
                .id(request.id())
                .toolName(request.name())
                .text("Tool not found: " + request.name())
                .isError(true)
                .build());
```

### 5.3 Maximum tool round trips

```java
toolService.maxToolCallingRoundTrips(20);
```

This only affects direct `ToolService.chat(...)` loops. At the Agent layer, the limit is `AgentSettings.maxSteps`.

## 6. Concurrent execution

Tool execution is concurrent by default in the Agent:

```java
AgentSettings.builder()
        .enableConcurrentToolExecution(true)
        .build();
```

- Concurrency applies only when the model returns multiple tool calls in one round;
- Result order still matches request order;
- Disable it when strict serial execution is required.

## 7. Tool design recommendations

1. **Descriptions matter more than implementation**: the model only sees names, descriptions, and schemas; explain when to use the tool;
2. **Keep parameters flat**: nested JSON increases argument errors;
3. **Return structured text**: prefer JSON strings with stable field names;
4. **Limit result size**: tool results enter the context; truncate or summarize long output;
5. **Make errors readable**: error messages should help the model correct itself;
6. **Keep tool names stable**: tool names are part of the model contract;
7. **Guard sensitive operations**: combine high-risk tools such as delete or transfer with approval middleware or human confirmation.

Next: read [Agent](./agent) to wire tools into the ReAct loop.
