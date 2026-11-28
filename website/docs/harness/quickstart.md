---
title: 快速构建 HarnessAgent
sidebar_label: 快速构建HarnessAgent
description: 通过 Maven 引入 HarnessAgent，理解它与普通 ReActAgent 的区别，并快速构建一个本地运行时 Harness
sidebar_position: 1
displayed_sidebar: sdkSidebar
---

# 快速构建 HarnessAgent

`HarnessAgent` 不是新的推理引擎。它对外仍是标准 `IAgent`，对内组合 `ReActAgent`，再绑定一个 `Runtime`，从而获得 Workspace 边界、工程工具集和运行资源生命周期。

快速构建分三步：引入依赖 → 创建 Workspace 与 LocalRuntime → 用 HarnessAgent 装配模型和记忆。

## 1. POM 快速引入

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-harness-agent</artifactId>
    <version>1.0.2-SNAPSHOT</version>
</dependency>
```

`agentforge-harness-agent` 会传递引入 `agentforge-agent-core`、`agentforge-model-api` 和 `agentforge-model-core`。实际运行时还需要至少一个模型 Provider，例如：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-openai</artifactId>
    <version>1.0.2-SNAPSHOT</version>
</dependency>
```

使用 BOM 时可以省略版本号：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>cloud.changlu.agentforge</groupId>
            <artifactId>agentforge-ai-bom</artifactId>
            <version>1.0.2-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## 2. 和普通 Agent 的区别

普通 Agent 直接面向 `ReActAgent`：模型、记忆、工具由调用方自行装配。HarnessAgent 在此基础上增加一层「执行环境」抽象：

| 维度 | 普通 `ReActAgent` | `HarnessAgent` |
| --- | --- | --- |
| 定位 | 通用 ReAct 推理循环 | 面向工作区的工程智能体 |
| 构建方式 | 直接配置模型、记忆、ToolService | 必须通过 Builder 传入一个 `Runtime` |
| 工具来源 | 业务自行注册 | Runtime 提供 `read / write / edit / glob / grep / rg / bash` |
| 安全边界 | 无内置 Workspace 约束 | 固定绑定一个 `Workspace`，文件与命令受路径边界限制 |
| 系统提示词 | 用户提供 | 用户值为空时使用 Runtime 默认提示词 |
| 资源生命周期 | 无额外运行时资源 | `AutoCloseable`，关闭时释放命令执行器与活动进程 |
| 扩展方向 | 工具、中间件、自定义 Agent | 新增 Runtime 即可接入 Docker、E2B 或远程环境 |
| 对外 API | `IAgent` | 同为 `IAgent`，内部透明委托给 ReActAgent |

三点需要特别注意：

1. **一个 HarnessAgent 固定绑定一个 Workspace**，不支持在多个工作区之间动态切换；
2. **HarnessAgent 不做 Runtime 类型分支**，Docker、E2B 等环境通过新增 Runtime 实现接入；
3. **首版安全边界由 Workspace 和工具代码强制执行**，不依赖提示词；`bash` 仍使用启动 AgentForge 的操作系统用户权限。

## 3. 快速构建本地 HarnessAgent

### 3.1 创建 Workspace

根目录必须已存在、可读，并会被转换为绝对真实路径：

```java
import cloud.changlu.agentforge.harness.runtime.local.workspace.Workspace;

import java.nio.file.Paths;

Workspace workspace =
        Workspace.builder()
                .id("my-project")
                .root(Paths.get("/path/to/project"))
                .build();
```

`id` 用于日志与 Trace，省略时取目录名。`workingDirectory` 可省略，默认等于 `root`。

### 3.2 创建 LocalRuntime

```java
import cloud.changlu.agentforge.harness.runtime.local.LocalRuntime;
import cloud.changlu.agentforge.harness.runtime.local.config.LocalRuntimeOptions;

LocalRuntime runtime =
        LocalRuntime.builder()
                .workspace(workspace)
                .options(LocalRuntimeOptions.defaults())
                .build();
```

`options` 可省略。默认命令参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `shell` | `/bin/sh` | 通过 `shell -lc` 执行前台命令 |
| `commandTimeoutMillis` | `30000` | 超时后强制销毁进程 |
| `maxCommandOutputChars` | `20000` | 合并输出上限，超出截断 |

### 3.3 创建 HarnessAgent

```java
import cloud.changlu.agentforge.agent.extend.memory.window.MessageWindowChatMemoryProvider;
import cloud.changlu.agentforge.harness.agent.HarnessAgent;

HarnessAgent agent =
        HarnessAgent.builder()
                .runtime(runtime)
                .agentName("local-harness-agent")
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
                .build();
```

`runtime`、`chatMemoryProvider` 必填；`chatModel` 与 `streamingChatModel` 至少提供一个。未传 `systemPrompt` 时，HarnessAgent 使用 `runtime.defaultSystemPrompt()`。

### 3.4 运行并关闭

```java
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.step.ChatResult;

try {
    ChatResult result =
            agent.run(
                    AgentRequest.builder()
                            .memoryId("my-project:conversation:1")
                            .question("先查看项目结构，再说明如何运行测试。")
                            .build());

    System.out.println(result.getRes());
} finally {
    agent.close();
}
```

流式调用保持同样的装配，只是改用 `runStream`：

```java
agent.runStream(
                AgentRequest.builder()
                        .memoryId("my-project:conversation:1")
                        .question("定位最近一次失败测试的原因。")
                        .build())
        .onPartialResponse(System.out::print)
        .onToolExecuted(tool ->
                System.out.println("\n[tool] " + tool.request().name()))
        .onCompleteResponse(response ->
                System.out.println("\n[done] " + response.aiMessage().text()))
        .onError(error -> System.err.println("[error] " + error.getMessage()))
        .start();
```

取消执行时，HarnessAgent 会把取消继续传播到 Runtime，终止本地命令：

```java
agent.cancel(memoryId, executionId);
```

## 4. 本地运行时提供什么

`LocalRuntimeTools` 首批内置：

| 工具 | 作用 | 关键约束 |
| --- | --- | --- |
| `read` | 按行读取 UTF-8 文本 | 限制文件大小、行数和单行长度 |
| `write` | 创建或覆盖文件 | 已有文件必须先读取 |
| `edit` | 精确替换文本 | 已读取；旧文本唯一匹配 |
| `glob` | 按模式查找文件 | 仅扫描 Workspace；限制结果数 |
| `grep` | 正则搜索内容 | 仅扫描 Workspace；限制文件大小与结果数 |
| `rg` | 调用本机 ripgrep | 缺少 `rg` 时返回可理解错误 |
| `bash` | 在 Workspace 执行前台命令 | 禁止后台任务；限制超时和输出量 |

所有路径操作都会经过 `Workspace.resolveExisting` 或 `Workspace.resolveForCreate`，拒绝 `..` 越界和符号链接逃逸。`read` 成功后，`write` 已有文件与 `edit` 才被允许。

## 5. 最小完整示例

```java
Workspace workspace =
        Workspace.builder()
                .id("my-project")
                .root(Paths.get("/path/to/project"))
                .build();

LocalRuntime runtime =
        LocalRuntime.builder()
                .workspace(workspace)
                .build();

HarnessAgent agent =
        HarnessAgent.builder()
                .runtime(runtime)
                .chatModel(chatModel)
                .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
                .build();

try {
    ChatResult result =
            agent.run(
                    AgentRequest.builder()
                            .memoryId("my-project:conversation:1")
                            .question("总结项目结构。")
                            .build());
    System.out.println(result.getRes());
} finally {
    agent.close();
}
```

> `bash` 的权限等同于启动 AgentForge 的操作系统用户。面向不可信用户开放前，必须增加审批或进一步隔离；Workspace 边界不能替代操作系统级沙箱。

下一步：了解 Runtime、Workspace 与 RuntimeTools 的职责边界及多运行时扩展方式，请阅读 [运行时设计实现](./runtime)。