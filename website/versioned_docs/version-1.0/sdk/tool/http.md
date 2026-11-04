---
title: HTTP 对接
sidebar_label: HTTP 对接
description: 将 REST API 或 curl 描述转换为受控的 Agent 工具
sidebar_position: 2
displayed_sidebar: sdkSidebar
---

# HTTP 对接

HTTP Tool 适合接入不值得为每个接口编写 Java 方法的外部服务。它不是把 URL、Method 和 Body 直接交给模型，而是先由开发者声明接口，再生成固定地址、固定参数位置和固定 JSON Schema 的工具。

```text
HttpPlugin
  └── HttpPluginMethod
        └── HttpToolParameter
              -> ToolSpecification + HttpToolExecutor
              -> ToolService
              -> ReActAgent
```

## 1. 设计思路：用声明限制模型能力

HTTP Tool 有三个设计目标：

1. **地址可控**：模型只能调用预先配置的 baseUrl 和 uri；
2. **参数可控**：模型只能填写 Schema 中声明的参数；
3. **协议统一**：最终产物仍然是 `ToolSpecification + ToolExecutor`，与 JavaLocalTool、MCP Tool 使用同一套 Agent 接口。

一个参数由四个维度描述：

| 维度 | 可选值 | 作用 |
| --- | --- | --- |
| 使用位置 | `QUERY` / `BODY` / `PATH` / `HEADER` | 决定参数落到 URL、JSON、路径还是请求头 |
| 数据类型 | `STRING` / `INTEGER` / `NUMBER` / `BOOLEAN` / `ARRAY` / `OBJECT` | 生成 JSON Schema 并做运行时转换 |
| 映射名 | `mappedName` | 模型参数名与真实接口名解耦 |
| 约束 | `defaultValue` / `required` | 缺省值和必填校验 |

## 2. 从 curl 快速生成工具

```java
String curl = "curl -X GET 'https://api.example.com/weather?city=beijing'";

CurlParseResult parsed = new CurlParser().parse(curl);
HttpPlugin plugin = new CurlToHttpPluginConverter().convert(parsed);

ToolService toolService = new ToolService();
toolService.tools(HttpToolFactory.buildHttpTools(plugin));
```

实际项目中建议把转换后的 `HttpPlugin` 保存为配置，而不是每次启动时依赖原始 curl。这样可以审查 URL、静态请求头、参数类型和权限范围。

## 3. 直接声明 HttpPlugin

```java
HttpToolParameter city = HttpToolParameter.builder()
        .methodParamName("city")
        .methodParamDescription("城市名称")
        .mappedName("city")
        .useTypeValue(HttpPluginEnums.ParameterUseType.QUERY.getValue())
        .dataTypeValue(HttpPluginEnums.ParameterType.STRING.getValue())
        .required(HttpPluginEnums.RequiredStatus.REQUIRED.getCode())
        .build();

HttpPluginMethod method = HttpPluginMethod.builder()
        .methodName("getWeather")
        .methodDescription("查询指定城市的天气")
        .httpMethodType(HttpPluginEnums.HttpMethod.GET.getValue())
        .uri("/weather")
        .parameters(Collections.singletonList(city))
        .build();

HttpPlugin plugin = HttpPlugin.builder()
        .baseUrl("https://api.example.com")
        .staticHeaders(Collections.singletonMap("Accept", "application/json"))
        .pluginMethods(Collections.singletonList(method))
        .build();

ToolService toolService = new ToolService();
toolService.tools(HttpToolFactory.buildHttpTools(plugin));
```

接入 Agent：

```java
ReActAgent agent = ReActAgent.builder()
        .agentName("weather-agent")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .build();
```

## 4. 请求构建规则

`HttpToolExecutor` 会按固定顺序处理模型参数：

```text
arguments(JSON)
  -> 读取实参 / 默认值 / 必填校验 / 类型转换
  -> 替换 PATH 参数
  -> 写入 HEADER 参数
  -> GET/DELETE：QUERY 参数
  -> POST/PUT/PATCH：有 BODY 参数时生成 JSON Body，否则使用 Query
  -> HttpTransport.execute
```

GET 和 DELETE 不发送 JSON Body；即使配置了 BODY 参数，也应按 Query 语义重新建模。公共鉴权头放在 `staticHeaders`，用户可控参数不要直接拼进 URL 字符串。

## 5. 如何扩展 HTTP Tool

### 自定义传输实现

`HttpToolFactory` 支持传入 `HttpTransport`：

```java
HttpTransport transport = new ObservedHttpTransport(delegate);
Map<ToolSpecification, ToolExecutor> tools =
        HttpToolFactory.buildHttpTools(
                Collections.singletonList(plugin), transport);
```

可以在传输层统一加入超时、代理、指标和脱敏，但不要让模型决定任意 URL。

### 自定义参数映射

如果第三方 API 的字段命名与业务概念不同，使用：

```text
methodParamName = "customerId"   # 模型看到的名字
mappedName      = "customer_id"  # 真实 HTTP 字段
```

这比要求模型记住下划线、大小写和路径占位符更稳定。

### 错误处理

HTTP 工具的错误信息最终会回到模型上下文。建议：

- 在工具描述中写明权限、频率和失败条件；
- 对 4xx 参数错误返回可修正的信息；
- 对 5xx、超时和网络错误区分可重试与不可重试；
- 对删除、转账、发消息等副作用请求增加审批或幂等键；
- 不把 Token、Cookie 和 Authorization 写进日志或模型可见结果。

## 6. 设计边界

HTTP Tool 解决的是“声明式把一个外部接口接入 Agent”，不是完整的 API Gateway。OAuth 刷新、租户鉴权、限流和敏感数据审计应由宿主 Service 或 `HttpTransport` 装饰器承担。

多 HTTP 插件可以合并到同一个 `ToolService`。工具名必须保持稳定且不冲突；启动时可通过 `toolService.toolSpecifications()` 检查模型最终看到的工具清单。

下一步：阅读 [MCP 对接](./mcp) 或 [Tool 扩展设计总览](../tool)。
