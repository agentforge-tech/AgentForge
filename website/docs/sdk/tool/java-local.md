---
title: JavaLocalTool 对接
sidebar_label: JavaLocalTool 对接
description: 使用 @Tool 把 Java 方法接入 Agent，并理解本地工具的 Schema 与执行器设计
sidebar_position: 1
displayed_sidebar: sdkSidebar
---

# JavaLocalTool 对接

JavaLocalTool 适合已经存在于应用进程内的 Java 能力：查询数据库、调用内部 Service、读取配置或执行一段可控的业务逻辑。开发者用 `@Tool` 描述方法，用 `@P` 描述参数，AgentForge 将方法转换为 `ToolSpecification` 和 `ToolExecutor`，最后统一交给 `ToolService`。

```text
Java 方法 + @Tool/@P
        -> ToolSpecification（给模型看的名称、描述、JSON Schema）
        -> LocalToolExecutor（参数转换、反射调用、结果序列化）
        -> ToolService
        -> ReActAgent
```

## 1. 定义工具

```java
public final class OrderTools {

    @Tool(value = "按订单号查询订单当前状态")
    public OrderStatus getOrderStatus(
            @P(name = "orderId", description = "订单号") String orderId) {
        return orderService.findStatus(orderId);
    }

    @Tool(name = "cancelOrder", value = "取消一个尚未发货的订单")
    public String cancelOrder(
            @P(name = "orderId", description = "订单号") String orderId) {
        orderService.cancel(orderId);
        return "订单已取消";
    }
}
```

工具描述不是注释，而是模型决策依据。应说明什么时候使用、输入需要满足什么条件、返回结果代表什么。

### `@Tool` 的关键属性

| 属性 | 作用 |
| --- | --- |
| `value` | 工具描述；可以写成多段说明 |
| `name` | 暴露给模型的工具名；为空时使用 Java 方法名 |
| `returnBehavior` | 控制返回值是否写回模型，默认写回 |

### `@P` 的关键属性

| 属性 | 作用 |
| --- | --- |
| `name` | 模型看到的参数名 |
| `description` / `value` | 参数描述 |

建议显式填写 `@P(name = ...)`。如果编译没有打开 `-parameters`，反射可能只能得到 `arg0`、`arg1`。

## 2. 接入 Agent SDK

`LocalToolFactory` 负责扫描工具对象并返回统一工具映射：

```java
Map<ToolSpecification, ToolExecutor> localTools =
        LocalToolFactory.buildLocalTools(
                Collections.<Object>singletonList(new OrderTools()));

ToolService toolService = new ToolService();
toolService.tools(localTools);

ReActAgent agent = ReActAgent.builder()
        .agentName("order-agent")
        .systemPrompt("你是订单助手。涉及取消订单时先确认订单状态。")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .build();

ChatResult result = agent.run(AgentRequest.builder()
        .memoryId("session-001")
        .question("帮我看看订单 A-1001 到哪一步了")
        .build());
```

需要同时让模型看到工具前的思考参数时：

```java
Map<ToolSpecification, ToolExecutor> localTools =
        LocalToolFactory.buildLocalToolsWithThought(
                Collections.<Object>singletonList(new OrderTools()), true);
```

这个参数只会出现在工具 Schema 中，不会改变 Java 方法签名；执行器会忽略额外的 `__thought` 参数。

## 3. 参数和返回值如何转换

本地工具的执行链路是：

```text
模型 arguments(JSON)
  -> argumentsAsMap
  -> 按 Java 参数类型转换
  -> 反射调用方法
  -> String 原样返回 / void 返回 Success / 其他类型 JSON 序列化
```

常见类型包括字符串、数字、布尔值、枚举、集合和 Map。为了让模型更容易生成正确参数，建议：

- 参数数量少而明确；
- 复杂对象使用稳定的 DTO；
- 返回结构化对象，而不是拼接不稳定的自然语言；
- 业务异常转换成模型能理解的错误文本；
- 高风险操作拆成“查询状态”和“执行变更”两个工具。

## 4. 如何扩展本地工具

### 4.1 使用自己的执行器

如果默认反射执行器不适合，可以直接实现 `ToolExecutor`，但仍然复用 `ToolSpecification`：

```java
ToolSpecification specification = ToolSpecification.builder()
        .name("queryOrder")
        .description("查询订单")
        .parameters(parameters)
        .build();

ToolExecutor executor = (request, memoryId) -> {
    Map<String, Object> args = parse(request.arguments());
    return orderGateway.query(String.valueOf(args.get("orderId")));
};

toolService.tools(Collections.singletonMap(specification, executor));
```

这种方式适合需要 RPC、审批、异步任务或特殊参数协议的工具。

### 4.2 使用工厂扩展治理能力

`LocalToolFactory.buildLocalToolsWithLogging(...)` 会给本地执行器包一层日志装饰器。更复杂的治理可以使用 Decorator：

```text
ToolExecutor
  -> 参数校验 Decorator
  -> 权限检查 Decorator
  -> 审计 Decorator
  -> 超时/重试 Decorator
  -> LocalToolExecutor
```

治理逻辑不应塞进业务工具方法，否则模型调用、人工调用和测试调用会共享难以拆除的副作用。

## 5. 设计思路与边界

JavaLocalTool 选择“注解声明 + 运行时映射”，是为了让模型契约和 Java 实现解耦：

- 模型只看到 `ToolSpecification`，不感知反射；
- Agent 只依赖 `ToolService`，不感知工具来源；
- 测试可以直接调用 Java 方法，也可以使用 `ToolExecutionRequest` 测试参数转换；
- 未来替换为 HTTP 或 MCP 时，Agent 代码不变。

不要把所有 Java 方法都暴露为工具。工具应是面向模型的最小能力单元，并经过权限、幂等性、超时和结果长度评估。

下一步：阅读 [HTTP 对接](./http) 或 [Tool 扩展设计总览](../tool)。
