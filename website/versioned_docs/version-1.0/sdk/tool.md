---
title: Tool 扩展接口设计
sidebar_label: Tool扩展接口设计
description: AgentForge Function Call、ToolExecutor 与工具调用实体的核心设计
sidebar_position: 5
displayed_sidebar: sdkSidebar
---

# Tool 扩展接口设计

AgentForge 将所有工具统一抽象为两部分：`ToolSpecification` 告诉模型“可以调用什么”，`ToolExecutor` 负责“如何执行”。Agent Loop 只处理统一的 Function Call 实体，不关心能力来自 Java 方法、HTTP 接口还是 MCP Server。

本文只介绍 Tool 核心实现。具体接入用法请阅读 [Java本地Tool 对接](./tool/java-local)、[HTTP 对接](./tool/http) 和 [MCP 对接](./tool/mcp)。

## 1. 整体执行链路

```text
ToolSpecification
        ↓ 随模型请求发送
模型返回 ToolExecutionRequest
        ↓ 按 name 查找执行器
ToolExecutor.executeWithResult(...)
        ↓
ToolExecutionResult
        ↓ 转换并写回会话
ToolExecutionResultMessage
```

`ToolService` 维护工具说明与执行器的注册关系，`AgentToolExecutor` 在 Agent Loop 的 Act 阶段完成调度、Middleware 回调、事件记录和结果写回。

## 2. Function Call 的统一封装

不同模型厂商的 Function Call 报文并不相同。AgentForge 在模型层定义提供商无关的实体，由各模型适配器负责协议转换，避免 Agent Loop 直接依赖厂商格式。

### 2.1 ToolSpecification：函数声明

`ToolSpecification` 描述模型可调用的函数：

| 字段 | 作用 |
| --- | --- |
| `name` | 工具唯一名称，也是执行器查找键 |
| `description` | 告诉模型何时、如何使用工具 |
| `parameters` | 基于 JSON Schema 的参数定义 |
| `strict` | 是否要求严格遵循参数 Schema |
| `metadata` | 保存核心逻辑之外的扩展信息 |

```java
ToolSpecification specification = ToolSpecification.builder()
        .name("query_weather")
        .description("查询指定城市的天气")
        .parameters(ToolParameters.builder()
                .addProperty("city", "string", "城市名称", true)
                .build())
        .strict(true)
        .build();
```

`ToolParameters` 使用普通 `Map` 保存 JSON Schema，使 API 层不依赖具体 JSON 库。模型适配器再将它转换为 OpenAI、Anthropic 等模型的请求结构。

### 2.2 ToolExecutionRequest：函数调用

模型返回 Function Call 后，适配器将其转换为统一请求：

```java
ToolExecutionRequest request = ToolExecutionRequest.builder()
        .id("call_001")
        .name("query_weather")
        .arguments("{\"city\":\"杭州\"}")
        .build();
```

- `id` 用来关联调用与返回结果；
- `name` 用来定位 `ToolExecutor`；
- `arguments` 保留模型生成的原始 JSON。

参数不在公共实体层提前反序列化，而是交给具体执行器解析。这样本地、HTTP、MCP 等执行器可以使用各自的参数语义，核心 API 也不必绑定某一种 JSON 实现。

## 3. ToolExecutor 执行器

`ToolExecutor` 是扩展工具能力的最小契约：

```java
@FunctionalInterface
public interface ToolExecutor {
    String execute(ToolExecutionRequest request, Object memoryId);

    default ToolExecutionResult executeWithResult(
            ToolExecutionRequest request, Object memoryId) {
        String text = execute(request, memoryId);
        return ToolExecutionResult.builder().text(text).build();
    }
}
```

`execute(...)` 适合只返回文本的简单执行器；`executeWithResult(...)` 则支持原始对象、错误状态和模型文本。默认实现会把文本包装成成功结果，复杂执行器可以覆盖它：

```java
public ToolExecutionResult executeWithResult(
        ToolExecutionRequest request, Object memoryId) {
    try {
        Object rawResult = invokeCapability(request.arguments());
        return ToolExecutionResult.builder()
                .result(rawResult)
                .text(toModelText(rawResult))
                .build();
    } catch (Exception error) {
        return ToolExecutionResult.failure("工具执行失败：" + error.getMessage(), error);
    }
}
```

`result` 面向程序内部和观测逻辑，`text` 才会进入模型上下文。两者分离后，执行器既能保留结构化原始对象，也能控制发送给模型的格式与长度。

## 4. Function Call 实体实现类

核心过程由四个不可变实体共同表达：

### 4.1 ToolExecutionRequest

表示模型希望执行的工具及参数，是执行链路输入。它提供 Builder、`from(...)` 和 `toBuilder()`，便于模型适配器构造和复制。

### 4.2 ToolExecutionResult

表示执行器内部返回值：

| 字段 | 作用 |
| --- | --- |
| `isError` | 本次执行是否失败 |
| `result` | 原始对象或异常，不直接发送给模型 |
| `text` | 写回模型上下文的文本 |

`success(...)` 和 `failure(...)` 用于构造标准结果。可预期的执行失败也作为结果返回，使模型能够根据错误提示修正参数或选择其他工具。

### 4.3 ToolExecutionResultMessage

表示写入对话历史的工具消息。它通过请求 `id` 关联原始 Function Call，并携带 `toolName`、`text`、`isError` 和扩展 `attributes`。

```java
ToolExecutionResultMessage message = ToolExecutionResultMessage.builder()
        .id(request.id())
        .toolName(request.name())
        .text(result.text())
        .isError(result.isError())
        .build();
```

模型下一轮推理看到的是该消息，而不是 Java 原始返回对象。

### 4.4 ToolExecution

表示一次完整的执行记录，组合 `ToolExecutionRequest` 和 `ToolExecutionResult`，并记录开始时间、结束时间、耗时和 `memoryId`。它用于流式监听、日志、Trace 和业务事件，不参与模型协议转换。

## 5. ToolService 注册中心

`ToolService` 内部维护两份核心数据：

```java
List<ToolSpecification> toolSpecifications;
Map<String, ToolExecutor> toolExecutors;
```

前者随请求发送给模型，后者只存在于应用内部。注册时通过工具名称建立对应关系：

```java
ToolService toolService = new ToolService();
toolService.tool(specification, executor);
```

模型只能看到工具说明，运行时则根据模型返回的 `name` 找到真实执行器。因此工具名是稳定的 Function Call 契约，应保持唯一并避免随意变更。

## 6. AgentToolExecutor 统一调度

`AgentToolExecutor` 位于 Agent Loop 的 Act 阶段，主要负责：

1. 从 `ToolService.toolExecutors()` 按名称查找执行器；
2. 调用 `executeWithResult(request, memoryId)`；
3. 将异常转换为失败的 `ToolExecutionResult`；
4. 触发执行前后及异常 Middleware；
5. 构造 `ToolExecutionResultMessage` 并写入 ChatMemory；
6. 构造带耗时和会话信息的 `ToolExecution` 并通知监听器；
7. 根据 Agent 配置选择顺序或并发执行。

并发模式下，同一轮工具最多使用 8 个线程执行，但结果仍按请求顺序写回，保证调用与结果稳定对应。工具不存在、执行异常或返回空文本也会在这一层统一处理。

## 7. 后续扩展方式

扩展 Tool 的核心不是修改 Agent Loop，而是实现新的“工具来源适配器”。适配器最终统一产出：

```java
Map<ToolSpecification, ToolExecutor>
```

其中 `ToolSpecification` 解决“如何让模型理解能力”，`ToolExecutor` 解决“如何调用真实能力”。推荐拆成三层。

### 7.1 规格转换器

从外部能力定义中提取工具名、描述和参数 Schema，转换成 `ToolSpecification`。它只负责声明，不执行工具，因而可以独立测试模型最终看到的 Function Call Schema。

### 7.2 专用执行器

不同能力来源通过不同执行器实现多态：

| 执行器类型 | 核心职责 |
| --- | --- |
| 本地执行器 | JSON 参数绑定、类型转换、反射调用、返回值序列化 |
| HTTP 执行器 | 参数映射到 path/query/header/body，处理请求、状态码和响应体 |
| MCP 执行器 | 发起 MCP 工具调用，处理传输与协议错误，提取远程结果 |
| 自定义执行器 | 对接 RPC、消息队列、脚本、工作流等其他能力 |

它们接收相同的 `ToolExecutionRequest` 并返回相同的 `ToolExecutionResult`，因此能够复用 Agent Loop、Middleware、并发策略和结果写回逻辑。

### 7.3 工厂或注册器

工厂负责把规格与执行器配对：

```java
public Map<ToolSpecification, ToolExecutor> buildTools(SourceDefinition source) {
    Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
    for (Capability capability : source.capabilities()) {
        tools.put(toSpecification(capability), createExecutor(capability));
    }
    return tools;
}
```

结果统一交给 `ToolService.tools(...)` 注册。新增协议时无需改动 `AgentToolExecutor`。

### 7.4 扩展边界

1. Schema 生成与执行逻辑分离；
2. 执行器自行负责参数解析与协议细节；
3. 原始结果放入 `result`，模型可读内容放入 `text`；
4. 可预期失败返回 `isError=true`，编程错误再抛异常；
5. 鉴权信息封装在执行器配置中，不放进 `ToolSpecification`；
6. 执行器尽量无状态且线程安全，以支持并发调用；
7. 日志、Trace 和结果改写优先通过 Middleware 实现。

## 8. 设计总结

```text
统一规格描述 + 统一调用实体 + 多态执行器 + Agent 层统一调度
```

Function Call 实体屏蔽模型厂商差异，`ToolExecutor` 屏蔽能力来源差异，`AgentToolExecutor` 屏蔽顺序、并发、Middleware 和结果写回差异。后续新增工具类型时，只需要扩展规格转换器和执行器，不需要重新实现 Agent Loop。
