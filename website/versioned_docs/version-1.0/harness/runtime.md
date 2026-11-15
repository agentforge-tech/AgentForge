---
title: Harness 运行时设计实现
sidebar_label: 运行时设计实现
description: 从 Runtime 核心组件、Harness 层组合职责出发，理解本地运行时实现与多运行时扩展方式
sidebar_position: 2
displayed_sidebar: sdkSidebar
---

# Harness 运行时设计实现

Harness 的核心设计可以概括为一句话：**Runtime 描述执行环境，Harness 层负责按环境选择并组合工具、权限策略、提示词和 Agent 能力。**

推理循环仍然由 `ReActAgent` 承担。Harness 不重新实现 think → act，而是在 Runtime 之上完成装配，让同一套 Agent 能力可以运行在本地目录、容器沙箱或远程环境中。

初版只落地本地运行时。本文先讲清 Runtime 的设计理论、核心组件和 Harness 层的组合职责，再说明当前 `LocalRuntime` 的实现情况与后续运行时扩展方式。

## 1. 设计理论

### 1.1 三层职责拆分

```text
业务调用方
    │ IAgent.run / IAgent.runStream
    ▼
Harness 层（组合根）
    │ 根据 Runtime 选择：工具集 / 权限策略 / 提示词 / 模型 / 记忆
    ▼
ReActAgent（推理内核）
    │ think → act → 写回记忆
    ▼
Runtime（执行环境）
    │ Workspace / RuntimeTools / RuntimeOptions / 资源生命周期
    ▼
Local / Docker / E2B / Remote
```

| 层 | 核心职责 | 不应该承担 |
| --- | --- | --- |
| Harness 层 | 读取 Runtime 能力，选择组件并组装 Agent | 不实现推理循环，不直接执行命令 |
| ReActAgent | 模型调用、工具调用编排、记忆写回、取消与状态 | 不感知具体执行环境 |
| Runtime | 定义环境语义、工具集、默认提示词和资源生命周期 | 不解析模型响应，不决定 Agent 策略 |

拆分带来三个直接收益：

1. **同一推理内核适配不同环境**：本地、容器、远程只替换 Runtime；
2. **环境差异集中收敛**：路径语义、命令能力、隔离级别由 Runtime 自己负责；
3. **组合关系可审计**：工具与权限策略由 Harness 层显式选择，不隐藏在 Agent 内部的条件分支里。

### 1.2 Harness 层是组合根

“组合根”意味着对象创建和依赖选择发生在 Harness 构建阶段，而不是运行期散落在各组件内部：

```text
Runtime
  ├── workspace()            -> 决定文件工具能访问哪里
  ├── tools()                -> 决定向模型暴露哪些工具
  ├── options()              -> 决定超时、输出、隔离等执行参数
  ├── defaultSystemPrompt()  -> 决定环境相关行为约束
  └── type()                 -> 决定默认权限配置和扩展能力
        │
        ▼
Harness 层组合
  ├── ToolService(runtime.tools + additionalTools)
  ├── PermissionInterruptModule(按 Runtime 能力选择)   <- 规划接入
  ├── ReActAgent(model / memory / middleware / prompt)
  └── HarnessAgent(runtime + delegate)
```

当前初版已经完成工具集、默认提示词、模型、记忆和中间件的组合；权限组件的选择入口尚未在 `HarnessAgent.Builder` 暴露，属于下一阶段。Agent 层已经具备 `PermissionInterruptModule`，后续可以直接在 Harness 构建阶段按 Runtime 类型选择并注入。

### 1.3 按 Runtime 选择工具与权限

Runtime 的能力不同，适合的工具和权限策略也不同：

| Runtime 形态 | 典型工具集 | 建议权限策略 | 设计理由 |
| --- | --- | --- | --- |
| 本地可写工作区 | `read/write/edit/glob/grep/rg/bash` | `DEFAULT` + 写操作与 Shell `ASK`，危险命令 `DENY` | 有人在场，需要保留确认能力 |
| 受控容器沙箱 | 文件、搜索、命令 | `BYPASS` + 显式红线 `DENY` | 环境可销毁，重点拦截不可接受操作 |
| 只读远程工作区 | `read/glob/grep` | `DONT_ASK` + 只读白名单 | 无人值守，不能产生等待 |
| CI / 批处理 | 构建、测试类工具 | `DONT_ASK` + 最小白名单 | 运行期间没有人工处理审批 |

这就是“基于 Runtime 在 Harness 层搭配组合”的含义：

- Runtime 不直接持有权限组件，避免执行环境与治理策略耦合；
- Harness 层根据 `RuntimeType`、`RuntimeOptions` 和可用工具选择默认权限画像；
- 调用方仍可显式覆盖默认策略；
- 工具集与权限策略必须一起设计，不能只放工具不设边界。

> 当前状态：工具与提示词组合已实现；权限画像选择和 `PermissionInterruptModule` 注入属于 Harness 的下一步扩展。本文中的权限搭配是设计方向，不是已暴露的 Builder API。

## 2. 核心 Runtime 组件

### 2.1 Runtime 契约

```java
public interface Runtime extends AutoCloseable {

    RuntimeType type();

    RuntimeOptions options();

    Workspace workspace();

    RuntimeTools tools();

    String defaultSystemPrompt();

    void cancelAll();

    @Override
    void close();
}
```

| 成员 | 职责 | 在 Harness 组合中的作用 |
| --- | --- | --- |
| `type` | 稳定的运行时类型标识 | 选择默认工具与权限画像 |
| `options` | 当前环境的执行参数 | 控制超时、输出、隔离等行为 |
| `workspace` | 工作区语义与安全边界 | 约束文件工具与命令目录 |
| `tools` | 与当前环境匹配的工具集合 | 转换为 `ToolService` |
| `defaultSystemPrompt` | 环境相关的完整提示词 | 用户提示词为空时使用 |
| `cancelAll` | 取消当前运行时的活动任务 | 传播 Agent 取消 |
| `close` | 释放进程、线程池与会话 | Agent 关闭时级联释放 |

Runtime 是轻量级的能力聚合与生命周期边界，不负责 Agent 推理。

### 2.2 默认提示词归 Runtime 所有

`HarnessAgent` 不维护所有运行时共用的默认提示词，因为 Agent 的角色描述、工作方式和工具边界需要与真实环境同步演进。本地运行时的路径语义、可用命令和隔离级别，与容器或远程运行时并不相同。

选择规则：

- `systemPrompt` 非空：原样使用用户值；
- `systemPrompt` 为空或仅空白：使用 `runtime.defaultSystemPrompt()`；
- 两者不追加、不合并。

本地默认模板分为两段：

```text
# Harness Agent

<角色、工作流程和通用行为约束>

# Runtime

<当前环境、Workspace、工具能力和执行边界>
```

模板中的 `${workspaceId}` 与 `${workspaceRoot}` 在 Runtime 构建阶段替换。缺少模板或变量时构建直接失败。

### 2.3 RuntimeType 与 RuntimeOptions

`RuntimeType` 为配置、持久化和 Harness 默认策略提供稳定编码，不允许依赖 `ordinal()`：

```java
LOCAL(1, "local", "Local workspace runtime");

RuntimeType.fromCode(1);
RuntimeType.fromName("local");
```

`RuntimeOptions` 与 `RuntimeType` 由具体 Runtime 保证配对。`LocalRuntimeOptions` 只承载本地执行参数：

```java
public final class LocalRuntimeOptions implements RuntimeOptions {

    private final String shell;                  // 默认 /bin/sh
    private final long commandTimeoutMillis;     // 默认 30000
    private final int maxCommandOutputChars;     // 默认 20000
}
```

未来 Docker、E2B 或远程 Runtime 各自提供 Options 实现，不需要修改 `HarnessAgent` 的公开结构。

## 3. Harness 层的搭配组合

### 3.1 当前已实现的装配流程

```text
HarnessAgent.Builder.build()
  -> 校验 runtime / model / chatMemoryProvider
  -> LocalToolFactory.buildLocalTools(runtime.tools().toolObjects())
  -> 合并 additionalTool / additionalTools
  -> 选择用户 systemPrompt 或 runtime.defaultSystemPrompt()
  -> 构建 ReActAgent(chatModel / streamingChatModel / memory / settings / middleware)
  -> 返回 HarnessAgent(runtime + delegate)
```

对应代码：

```java
HarnessAgent agent =
        HarnessAgent.builder()
                .runtime(runtime)
                .agentName("local-harness-agent")
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
                .build();
```

组合边界：

| Runtime 提供 | Harness 层处理 |
| --- | --- |
| `workspace()` | 交给 Runtime 工具执行路径校验 |
| `tools()` | 通过 `LocalToolFactory` 转为 `ToolService` |
| `defaultSystemPrompt()` | 用户未传提示词时注入 ReActAgent |
| `type()` / `options()` | 当前用于观测；未来用于选择默认权限画像 |
| `cancelAll()` / `close()` | 由 HarnessAgent 在取消和关闭时调用 |

### 3.2 权限组件的组合位置

权限属于 Agent 治理能力，不属于执行环境。合理的组合方式是：

```text
Runtime 能力
  ├── type = LOCAL
  ├── 工具包含 write / bash
  └── 环境可回滚、有人在现场
        │
        ▼
Harness 层选择默认权限画像
  ├── allow 读操作
  ├── ask 写操作与 Shell
  ├── deny 破坏性命令
  └── enableAskUser
        │
        ▼
PermissionInterruptModule 注入 ReActAgent
```

这样可以做到：

1. 本地 Runtime 默认保留人工审核；
2. 沙箱 Runtime 可以切到 `BYPASS`，只保留硬红线；
3. 远程只读 Runtime 可以切到 `DONT_ASK`，只放白名单；
4. 调用方显式配置始终覆盖默认画像。

当前 `HarnessAgent.Builder` 尚未暴露 `permissionInterruptModule(...)`。下一阶段应在 Harness 构建阶段完成该选择，而不是让 Runtime 持有权限组件或让 Agent 内部按类型分支。

## 4. 当前实现：LocalRuntime

### 4.1 构建过程

```java
LocalRuntime runtime =
        LocalRuntime.builder()
                .workspace(Workspace.builder()
                        .id("my-project")
                        .root(Paths.get("/path/to/project"))
                        .build())
                .options(LocalRuntimeOptions.defaults())
                .build();
```

装配步骤：

1. 校验 Workspace 非空，并补齐默认 `LocalRuntimeOptions`；
2. 渲染本地运行时默认提示词；
3. 创建 `LocalRuntimeTools` 与 `CommandExecutor`；
4. 固定 `type()` 为 `RuntimeType.LOCAL`。

`LocalRuntime` 不负责推理、不解析模型响应，也不感知调用入口。

### 4.2 Workspace 边界

```java
public final class Workspace {
    private final String id;
    private final Path root;
    private final Path workingDirectory;
}
```

- `root` 构建时转换为绝对、规范化、真实路径；
- `workingDirectory` 必须位于 `root` 内，用作命令默认目录；
- `resolveExisting` 用于已存在路径，解析真实路径并拒绝符号链接逃逸；
- `resolveForCreate` 用于待创建路径，检查最近已存在父目录的真实路径。

所有接收路径的工具都必须经过 Workspace 解析。不允许用字符串前缀判断父子关系：

```java
// 错误：无法区分 /workspace-a 与 /workspace-a2
path.toString().startsWith(root.toString());

// 正确：按路径层级判断
resolved.startsWith(root);
```

### 4.3 RuntimeTools 与本地工具

```java
public interface RuntimeTools extends AutoCloseable {

    RuntimeType type();

    Collection<Object> toolObjects();

    void cancelAll();

    @Override
    void close();
}
```

`HarnessAgent` 只依赖 `RuntimeTools`，构建时通过 `LocalToolFactory` 扫描 `toolObjects()`。本地首批工具：

| 工具 | 作用 | 安全约束 |
| --- | --- | --- |
| `read` | 按行读取 UTF-8 文本 | 文件大小、行数、单行长度限制 |
| `write` | 创建或覆盖文件 | 已有文件必须先读取；父目录受控创建 |
| `edit` | 精确替换文本 | 已读取；旧文本唯一匹配 |
| `glob` | 按模式查找文件 | 仅扫描 Workspace；限制结果数 |
| `grep` | 正则搜索内容 | 仅扫描 Workspace；限制文件大小与结果数 |
| `rg` | 调用本机 ripgrep | 缺少 `rg` 时返回可理解错误 |
| `bash` | 执行前台命令 | 固定工作目录；超时与输出限制 |

`read` 成功后登记规范化绝对路径，后续 `write` 已有文件或 `edit` 文件时必须命中该集合：

```text
read(path) 成功
    -> readFiles.add(canonicalPath)

write(existingPath)
    -> readFiles.contains(canonicalPath) 或拒绝

edit(path)
    -> readFiles.contains(canonicalPath) 或拒绝
```

该状态当前跟随 `LocalRuntimeTools` 的生命周期，用于降低模型覆盖未读文件的风险；按 `executionId` 隔离属于后续并发安全增强。

### 4.4 本地命令执行

`CommandExecutor` 使用 `ProcessBuilder` 执行前台命令：

- 以 `shell -lc <command>` 启动；
- `directory` 固定在 Workspace 的 `workingDirectory`；
- stdout 与 stderr 合并消费，避免缓冲区阻塞；
- 超时后强制销毁进程；
- 输出超过上限时截断并标注；
- 返回退出码、是否超时和输出文本；
- `cancelAll` 终止当前 Runtime 的活动进程。

初版不提供后台任务、守护进程或远程命令。`bash` 的命令字符串交给系统 Shell 执行，其权限等同于启动 AgentForge 的操作系统用户。

## 5. 生命周期与并发

### 5.1 生命周期

```text
LocalRuntime.Builder 创建 Runtime
    -> HarnessAgent.Builder 接收 Runtime
    -> HarnessAgent 构建成功，接管 Runtime 生命周期
    -> HarnessAgent.close()
          -> Runtime.close()
              -> RuntimeTools.close()
                  -> CommandExecutor.close()
```

- 一个 HarnessAgent 对应一个 Workspace，可被多个请求复用；
- `close()` 后拒绝新请求，并取消尚未结束的命令；
- 不要在每次请求时新建 Runtime，否则已读状态、命令执行器和默认提示词都会重复创建。

### 5.2 并发边界

当前实现已经保证：

- `readFiles` 使用并发 Set，容器线程安全；
- 活动进程使用并发 Set，`cancelAll` 可安全遍历；
- 同一 Runtime 内的命令可以被统一终止。

当前尚未承诺：

- `readFiles` 与活动进程按 `executionId` 隔离；
- 同一文件的并发写入具有事务一致性；
- 多个 HarnessAgent 共享同一个 Runtime；
- 跨节点调度与分布式锁。

后续可将文件访问状态与活动进程调整为 `ConcurrentMap<executionId, ...>`，使取消只作用于目标执行；同一文件的并发写入可采用临时文件加原子移动，或进程内文件锁。

### 5.3 异常模型

工具层应返回模型可理解、但不泄露主机敏感信息的错误：

```text
WORKSPACE_PATH_DENIED
FILE_NOT_READ
FILE_TOO_LARGE
SEARCH_LIMIT_EXCEEDED
COMMAND_TIMEOUT
COMMAND_CANCELLED
COMMAND_EXECUTOR_CLOSED
```

编程错误和不可恢复的初始化问题抛出异常；可预期的工具失败转换为结构化结果，让 ReActAgent 有机会调整操作。

## 6. 其他运行时扩展

新增运行时只需实现三个契约，`HarnessAgent` 与 `IAgent` API 保持不变：

```text
Runtime
├── LocalRuntime    已实现
├── DockerRuntime   规划
├── E2BRuntime      规划
└── RemoteRuntime   规划
```

扩展步骤：

1. 新增 `RuntimeOptions` 实现，描述该环境的配置；
2. 新增 `RuntimeTools` 实现，提供与执行环境匹配的工具对象；
3. 新增 `Runtime` 实现，声明 `RuntimeType`、Workspace 语义、默认提示词和生命周期；
4. 由该 Runtime 自己的 Builder 完成校验与装配；
5. 在 Harness 层按 Runtime 能力选择工具与默认权限画像；
6. 通过 `HarnessAgent.Builder.runtime(...)` 载入。

不需要：

- 修改 `HarnessAgent` 的公开入口；
- 在 `HarnessAgent` 内增加 `RuntimeType` 分支；
- 修改 `ReActAgent` 主循环；
- 让 `agent-core` 依赖 Harness。

### 6.1 远程运行时的额外约束

当前 `Runtime.workspace()` 返回本地 `Path`，服务于本地版兼容和观测。引入远程运行时后，不能再假设 `java.nio.file.Path` 对应本机路径。首个远程 Runtime 落地前，应将 Workspace 收敛为不泄露本地路径的描述对象或文件会话抽象。

远程运行时的工具也不应直接复用本地 `ProcessBuilder`。文件读写、搜索和命令执行应通过 Runtime 暴露的会话能力完成，并由 Runtime 负责超时、取消和资源释放。

### 6.2 初版明确不包含

- Docker、E2B、SSH 或其他远程 Runtime；
- 跨机器文件同步与远程命令执行；
- 同一个 HarnessAgent 在多个 Workspace 间动态切换；
- 完整的人机审批、操作回滚和多租户资源调度；
- 直接兼容 AgentX 的注解、会话或 Sandbox API。

这些能力保留扩展边界，不进入首版实现。

## 7. 职责边界总结

| 能力 | Harness 层 | Runtime / RuntimeTools |
| --- | --- | --- |
| 选择模型与记忆 | 是 | 否 |
| 组装 ReActAgent | 是 | 否 |
| 选择工具集 | 是 | 提供候选工具 |
| 选择权限画像 | 是（规划） | 提供环境能力信息 |
| 追加业务工具 | 是 | 否 |
| 选择用户提示词 | 是 | 提供默认值 |
| 路径安全边界 | 否 | 是 |
| 文件读写与检索 | 通过工具调用 | 提供并执行 |
| 命令执行与取消 | 传播取消 | 执行与终止进程 |
| 资源释放 | 触发 close | 释放具体资源 |

一句话概括：**Runtime 描述「在哪里、能用什么」，Harness 层决定「给模型什么工具、配什么权限」，ReActAgent 负责「如何推理与行动」。**

下一步：回到 [快速构建 HarnessAgent](./quickstart) 完成第一个本地工程智能体。