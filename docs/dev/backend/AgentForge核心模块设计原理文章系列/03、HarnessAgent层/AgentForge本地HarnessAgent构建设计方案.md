# AgentForge 本地 HarnessAgent 构建设计方案

## 1. 设计背景

HarnessAgent 面向“能够在工作区内理解、检索并修改工程，同时执行必要命令”的智能体场景。它不是重新实现一套 Agent 推理引擎，而是在 AgentForge 现有 `ReActAgent` 之上增加 Workspace 和本地工具约束。

初版聚焦本地运行：AgentForge 进程、模型调用编排、文件操作和命令执行均发生在同一台主机上。设计参考 AgentX `CoreSandboxTools` 的文件与命令能力，但使用 AgentForge 自身的 Agent、Tool 和流式事件体系。

## 2. 初版目标与边界

### 2.1 目标

- 提供实现 `IAgent` 的 `HarnessAgent`，保持通用 Agent 调用方式不变。
- 内部组合 `ReActAgent`，复用其思考—行动循环、记忆、工具调用、取消和执行状态能力。
- 一个 `HarnessAgent` 实例固定绑定一个 `Workspace`。
- 同时支持非流式 `run` 和流式 `runStream`。
- 首批提供文件读取、写入、编辑、查找、内容搜索和命令执行能力。
- 所有文件与命令操作必须被限制在绑定的 Workspace 中。

### 2.2 暂不包含

- Docker、E2B、SSH 或其他远程 Runtime。
- 跨机器文件同步和远程命令执行。
- 同一个 HarnessAgent 在多个 Workspace 间动态切换。
- 完整的人机审批、操作回滚和多租户资源调度。
- 直接兼容 AgentX 的注解、会话或 Sandbox API。

这些能力只保留扩展边界，不进入初版实现。

## 3. 总体架构

```text
业务调用方
    │ IAgent.run / IAgent.runStream
    ▼
HarnessAgent
    ├── Runtime（由 HarnessAgent Builder 载入）
    │   └── LocalRuntime（由自身 Builder 组装）
    │       ├── RuntimeType.LOCAL
    │       ├── LocalRuntimeOptions
    │       ├── Workspace
    │       └── LocalRuntimeTools
    ├── system prompt（用户值或 Runtime 默认值）
    └── ReActAgent delegate
            ├── ChatModel
            ├── ChatMemory
            ├── Middleware
            └── Map<ToolSpecification, ToolExecutor>
```

核心原则是“组合而非继承”。`Runtime` 负责聚合工作区语义、配置、工具、默认提示词和资源生命周期；`HarnessAgent` 在构建时选择用户 system prompt 或当前 Runtime 的默认 prompt，并将 Runtime 能力装配进 `ReActAgent`。

命名上只让对外角色 `HarnessAgent` 带有 Harness 语义。内部使用 `Runtime`、`Workspace`、`RuntimeOptions` 和 `RuntimeTools` 等领域名称。Runtime 是轻量级的能力聚合与生命周期边界，不负责 Agent 推理。

建议依赖方向：

```text
agentforge-harness-agent
    └── agentforge-agent-core
            └── agentforge-model-api / agentforge-model-core
```

`agentforge-agent-core` 不应反向依赖 Harness 模块。

## 4. 核心对象设计

### 4.1 HarnessAgent

```java
public final class HarnessAgent implements IAgent, AutoCloseable {

    private final Runtime runtime;
    private final ReActAgent delegate;

    @Override
    public ChatResult run(AgentRequest request) {
        ensureOpen();
        return delegate.run(request);
    }

    @Override
    public TokenStream runStream(AgentRequest request) {
        ensureOpen();
        return delegate.runStream(request);
    }

    // cancel、createExecutionId、getExecutionState、clearContext
    // 均保持 IAgent 语义并委托给 delegate。
}
```

职责边界：

- 构建时接收一个已由自身 Builder 完成组装的 Runtime。
- 将 Runtime 的工具能力装配进 ReActAgent。
- 用户传入非空 system prompt 时使用用户值；未传或仅空白时使用当前 Runtime 预设 prompt。
- 不根据 `RuntimeType` 分支，不在 HarnessAgent 中构造具体 RuntimeTools。
- 对 `IAgent` 的执行、取消、状态和上下文接口做透明委托。
- 关闭时释放命令执行器持有的进程、线程池等资源。

`HarnessAgent` 不负责实现 ReAct 循环，也不直接解析模型工具调用。

### 4.2 Runtime

```java
public interface Runtime extends AutoCloseable {
    RuntimeType type();
    RuntimeOptions options();
    Workspace workspace();
    RuntimeTools tools();
    String defaultSystemPrompt();
    void cancelAll();
    void close();
}
```

Runtime 是 `HarnessAgent` 所需执行环境的完整聚合边界：

- `type/options/workspace`：提供可观测的运行时上下文。
- `tools`：提供与该运行时真实匹配的工具集。
- `defaultSystemPrompt`：提供用户未显式配置时，适配当前 Runtime 的完整 HarnessAgent 提示词。
- `cancelAll/close`：统一取消及资源释放。

Runtime 由具体实现的 Builder 创建，再通过 `HarnessAgent.Builder.runtime(...)` 载入。每个 Runtime 独立维护一份完整默认 prompt，在文本内使用 `# Harness Agent` 和 `# Runtime` 标题区分主体角色与环境约束。该 prompt 仅在用户 prompt 为空时生效，不与用户值追加或合并。

这是当前的明确设计决策：HarnessAgent 不再维护一份所有 Runtime 共用的默认 prompt。完整 prompt 归具体 Runtime 所有，因为主体工作方式需要与实际可用工具、路径语义和执行边界同步演进。

### 4.3 Workspace

```java
public final class Workspace {
    private final String id;
    private final Path root;
    private final Path workingDirectory;
}
```

- `id`：稳定的逻辑标识，用于日志、Trace 和会话校验。
- `root`：Workspace 的安全边界，构建时转换为绝对、规范化的真实路径。
- `workingDirectory`：命令默认目录，必须位于 `rootPath` 内；默认等于 `rootPath`。

初版采用“一实例一 Workspace”。同一 `memoryId` 的上下文不得在不同 Workspace 之间复用，以免历史对话中的路径和工程认知污染另一个工作区。建议记忆实现使用 `workspaceId + memoryId` 作为隔离键，或者在入口处维护二者绑定关系并拒绝切换。

`Workspace` 是初版最主要的本地环境封装，位于 `runtime.local.workspace` 包中。它通过 `resolveExisting` 和 `resolveForCreate` 统一负责路径解析与边界判断，工具层不得自行拼接未经校验的路径。

### 4.4 RuntimeType 与 RuntimeOptions

`runtimeType` 和 `runtimeOptions` 不再由 HarnessAgent Builder 分别接收，而是由具体 Runtime 保证配对。初版 `LocalRuntime` 固定为 `RuntimeType.LOCAL`，调用方不传 options 时使用默认值：

```java
public interface RuntimeOptions {
    RuntimeType type();
}

LocalRuntimeOptions.defaults();
```

`RuntimeType` 同时提供稳定数字编码、机器可读 name 和展示描述，配置或持久化不得依赖 `ordinal()`：

```java
LOCAL(1, "local", "Local workspace runtime");

RuntimeType.fromCode(1);
RuntimeType.fromName("local");
```

`LocalRuntimeOptions` 初版包含 Shell、命令超时和最大命令输出等本地执行参数。本地命令能力由 `CommandExecutor` 承担：

- 使用 `ProcessBuilder` 执行本机命令。
- 命令目录由 `Workspace` 校验并限制在工作区内。
- 统一处理超时、退出码、输出截断、进程销毁和取消。

未来接入 Docker、E2B 或远程环境时，分别增加对应的 `RuntimeOptions` 和 `RuntimeTools` 实现，无需修改 HarnessAgent 的公开结构。

### 4.5 SandboxLimits

建议集中承载可调限制：

```java
public final class SandboxLimits {
    private Duration commandTimeout;
    private int maxOutputChars;
    private int maxReadLines;
    private int maxLineChars;
    private long maxFileBytes;
    private int maxSearchResults;
    private boolean requireReadBeforeWrite;
}
```

所有限制应提供保守默认值，并设置不可突破的硬上限。初版 `SandboxLimits` 是模块内部实现，不作为 HarnessAgent Builder 的公共参数，避免调用方任意放宽安全边界。

## 5. RuntimeTools 设计

`RuntimeTools` 是运行时工具集合抽象，HarnessAgent 只依赖该接口。初版由 `LocalRuntimeTools` 实现文件和本地命令能力。工具方法使用 AgentForge 的 `cloud.changlu.agentforge.model.tool.Tool` 注解，通过 `LocalToolFactory.buildLocalTools(...)` 生成 `Map<ToolSpecification, ToolExecutor>` 后交给 ReActAgent。

### 5.1 初版工具清单

| 工具 | 作用 | 关键限制 |
| --- | --- | --- |
| `read` | 按行读取 UTF-8 文本 | 限制文件大小、行数和单行长度 |
| `write` | 创建或覆盖文件 | 已存在文件须先读取；父目录创建受控 |
| `edit` | 精确替换文本 | 已读取；默认要求旧文本唯一匹配 |
| `glob` | 按模式查找文件 | 禁止扫描 Workspace 外路径；限制结果数 |
| `grep` | 正则搜索文件内容 | 限制文件类型、扫描量和结果数 |
| `rg` | 使用 ripgrep 高效检索 | 缺少 `rg` 时返回可理解错误，不绕过路径策略 |
| `bash` | 在 Workspace 执行前台命令 | 禁止后台任务；限制超时和输出量 |

`getServerInfo` 对纯本地首版价值有限，可在需要模型感知 OS、Shell 和工具版本时再补充。

### 5.2 Read-before-write/edit

当前 `LocalRuntimeTools` 实例维护线程安全的已读取文件集合，记录经过规范化后的绝对路径：

```text
read(path) 成功 → readFiles.add(canonicalPath)
write(existingPath) → 必须已存在于 readFiles
edit(path) → 必须已存在于 readFiles
```

该机制用于降低模型在不了解现有内容时覆盖用户文件的风险。新建文件不要求预读，但仍需经过路径校验。当前集合跟随 RuntimeTools 生命周期；按 executionId 隔离及执行结束清理属于后续并发安全增强。

## 6. Workspace 安全策略

### 6.1 路径校验

所有接收路径的工具必须调用 `Workspace.resolveExisting/resolveForCreate`：

1. 相对路径以 Workspace root 解析。
2. 规范化 `.` 和 `..`。
3. 校验解析结果以 Workspace root 为前缀。
4. 对已存在路径解析真实路径，拒绝通过符号链接逃逸。
5. 对待创建路径检查最近已存在父目录的真实路径。
6. 拒绝空字节、设备文件及其他不支持的特殊路径。

不允许仅用字符串前缀判断路径。例如 `/workspace-a2` 不能被视为 `/workspace-a` 的子路径，应使用 `Path.startsWith`。

### 6.2 命令执行

- `ProcessBuilder.directory(...)` 固定在 Workspace 内。
- 初版仅支持前台命令，不提供后台进程或守护进程能力。
- 超时后先正常终止，再强制销毁整个进程树。
- stdout 和 stderr 应持续消费，防止缓冲区阻塞，并按上限截断。
- 返回命令、退出码、耗时、stdout、stderr 和是否超时。
- Agent 的 `cancel` 应向 Runtime 传播，终止该 executionId 下的活动进程。
- 不依赖模型主动遵守提示词，所有限制均在代码层强制执行。

命令字符串本身在首版可交给系统 Shell 执行以满足工程任务，但应在文档和日志中明确其本地权限等同于启动 AgentForge 的操作系统用户。面向不可信用户开放前，必须增加审批或进一步隔离。

### 6.3 搜索和读取限制

- 禁止以空模式对整个磁盘或 Workspace 做无限扫描。
- 默认忽略 `.git`、构建产物和常见依赖目录，并允许显式配置。
- 限制单次返回结果数和总字符数。
- 二进制文件默认不读取、不做内容搜索。
- 超限时返回“结果被截断”及缩小范围建议，而不是静默丢失。

## 7. ReActAgent 委托与请求增强

### 7.1 非流式

```text
调用方 → HarnessAgent.run(request)
       → 校验 Agent 未关闭
       → ReActAgent.run(request)
       → RuntimeTools / LocalRuntimeTools
       → ChatResult
```

### 7.2 流式

```text
调用方 → HarnessAgent.runStream(request)
       → 校验 Agent 未关闭
       → ReActAgent.runStream(request)
       → TokenStream（原样保留 executionId、思考、工具和错误事件）
```

HarnessAgent 不订阅或重新包装整条流，保留 ReActAgent 的背压、异常和完成事件语义。

### 7.3 HarnessAgent 系统提示词

HarnessAgent 的 system prompt 选择规则为：

1. `Builder.systemPrompt(...)` 传入非空文本时，原样传给 ReActAgent。
2. 未传或仅包含空白字符时，使用 `runtime.defaultSystemPrompt()`。
3. LocalRuntime 在自身 Builder 构建阶段渲染完整的 `local-runtime-system-prompt.txt`，注入 `workspaceId` 和 `workspaceRoot`。
4. Workspace 安全边界始终由工具代码强制执行。

默认 prompt 由 Runtime 维护，因此 Local、Docker 或 E2B 可以分别维护适合自己的完整 HarnessAgent 角色、环境和工具边界。

每份 Runtime 默认模板至少分为两个清晰区域：

```text
# Harness Agent

<该 Runtime 下 HarnessAgent 的角色、工作流程和通用行为约束>

# Runtime

<当前环境、Workspace、工具能力和执行边界>
```

LocalRuntime 的完整模板位于 `local-runtime-system-prompt.txt`，其中 `${workspaceId}` 和 `${workspaceRoot}` 由 `PromptTemplateRenderer` 严格替换。缺少模板或变量时 Runtime 构建直接失败。

调用方如需显式感知 Workspace，可在自定义 prompt 中注入：

```text
你工作在本地 Workspace：<workspaceId>，根目录为 <rootPath>。
所有文件读取、检索、修改和命令执行必须位于该 Workspace 内。
修改已有文件前必须先读取；先使用 rg、grep 或 glob 定位，再做最小修改。
不得猜测或访问 Workspace 外文件；工具拒绝访问时应说明原因并调整方案。
```

绝不能以提示词替代代码安全校验。Workspace 路径校验和 RuntimeTools 才是最终边界。

## 8. Builder 与装配草案

```java
LocalRuntime runtime = LocalRuntime.builder()
        .workspace(Workspace.builder()
                .id("agentforge-main")
                .root(Paths.get("/path/to/AgentForge"))
                .build())
        .options(LocalRuntimeOptions.defaults()) // 可省略
        .build();

HarnessAgent agent = HarnessAgent.builder()
        .runtime(runtime) // 必传，HarnessAgent 接管其生命周期
        .chatModel(chatModel)
        .chatMemoryProvider(chatMemoryProvider)
        .middlewares(middlewares)
        .build();
```

构建过程建议固定为：

1. `LocalRuntime.Builder` 校验 Workspace，补齐默认 `LocalRuntimeOptions`。
2. `LocalRuntime.Builder` 渲染该 Runtime 的完整默认 HarnessAgent prompt。
3. `LocalRuntime.Builder` 创建 `LocalRuntimeTools` 和 `CommandExecutor`。
4. `HarnessAgent.Builder` 校验 Runtime 为必传参数。
5. HarnessAgent 使用 `LocalToolFactory` 扫描 `runtime.tools()`。
6. 合并调用方提供的业务工具；工具名冲突时构建失败。
7. 选择用户 system prompt，为空时使用 `runtime.defaultSystemPrompt()`。
8. 创建 `ReActAgent`并返回不可变的 `HarnessAgent`。

Builder 必须拒绝不存在、不是目录、不可读的 Workspace。是否允许自动创建根目录应作为显式选项，默认不创建。

## 9. 包结构建议

```text
cloud.changlu.agentforge.harness
├── agent
│   └── HarnessAgent.java（内含 Builder）
├── runtime
│   ├── Runtime.java
│   ├── RuntimeType.java
│   ├── RuntimeOptions.java
│   ├── RuntimeTools.java
│   ├── prompt
│   │   └── PromptTemplateRenderer.java
│   └── local
│       ├── LocalRuntime.java
│       ├── workspace
│       │   └── Workspace.java
│       ├── config
│       │   └── LocalRuntimeOptions.java
│       ├── tool
│       │   ├── LocalRuntimeTools.java
│       │   └── SandboxLimits.java
│       └── command
│           ├── CommandExecutor.java
│           └── CommandResult.java
└── resources/cloud/changlu/agentforge/harness/runtime/local
    └── local-runtime-system-prompt.txt
```

如果后续工具实现增长，可再拆分为 `FileTools`、`SearchTools` 和 `CommandTools`；初版保留一个入口类更利于统一管理每次执行的 read-before-write 状态。

## 10. 生命周期、并发与异常

### 10.1 生命周期

- 推荐一个 HarnessAgent 对应一个 Workspace，可被多个请求复用。
- Runtime 在传入 HarnessAgent 前由具体 Runtime Builder 创建；HarnessAgent 构建成功后接管其生命周期。
- `HarnessAgent.close()` 调用 `Runtime.close()`，再由 Runtime 关闭 RuntimeTools 和其他资源。
- 当前 CommandExecutor 维护 Runtime 级活动进程集合，`cancelAll()` 会终止该 Runtime 的全部活动进程。
- `close()` 应拒绝新请求，并取消尚未结束的命令。

### 10.2 并发

- 当前 `readFiles` 和活动进程均使用并发 Set 保证容器线程安全，但尚未按 executionId 隔离。
- 后续应将文件访问状态与活动进程调整为 `ConcurrentMap<executionId, ...>`，使 `cancel(memoryId, executionId)` 只取消目标执行。
- 同一文件的并发写入初版不承诺事务一致性；至少应采用临时文件加原子移动，或对目标文件加进程内锁。
- `cancel(memoryId, executionId)` 先通知 ReActAgent，再由 `CommandExecutor` 取消本地进程；任一侧已结束不应导致取消接口报错。

### 10.3 异常模型

工具层应返回模型可理解、但不泄露不必要主机信息的错误：

- `WORKSPACE_PATH_DENIED`
- `FILE_NOT_READ`
- `FILE_TOO_LARGE`
- `SEARCH_LIMIT_EXCEEDED`
- `COMMAND_TIMEOUT`
- `COMMAND_CANCELLED`
- `COMMAND_EXECUTOR_CLOSED`

编程错误和不可恢复的初始化问题抛出异常；可预期的工具失败转换为结构化工具结果，让 ReActAgent 有机会调整操作。

## 11. 测试方案

### 11.1 单元测试

- Workspace 相对路径、绝对路径和 `..` 逃逸校验。
- 已存在与待创建路径的符号链接逃逸。
- read-before-write/edit 状态隔离。
- edit 唯一匹配、零匹配和多匹配。
- glob/grep 结果和扫描上限。
- bash 正常退出、非零退出、超时、输出截断和取消。
- Builder 对非法 Workspace 和重复工具名的拒绝。
- `HarnessAgent` 对全部 `IAgent` 方法的委托。

测试应使用 JUnit 临时目录，不依赖开发者机器上的固定路径。

### 11.2 集成测试

使用可预测的 Fake ChatModel 驱动 ReActAgent：

1. 模型请求 `read` 并返回文件摘要。
2. 模型依次 `read`、`edit`，验证文件内容。
3. 模型执行命令并消费 stdout。
4. `runStream` 能收到工具事件和完整结束事件。
5. 取消流式执行后，ReAct 循环和子进程都结束。
6. 尝试访问 Workspace 外路径时工具明确拒绝。

### 11.3 模块验证

```bash
mvn -pl agentforge-framework/agentforge-harness-agent -am test
mvn -pl agentforge-framework/agentforge-harness-agent -am package -DskipTests
```

发布验证应与真实 Central 发布分离：先完成本地 `test/package/verify`，再执行发布脚本，避免使用远端发布结果判断模块能否编译打包。

## 12. 分阶段实施建议

### 阶段一：可运行骨架

- 实现 Workspace、路径策略和 CommandExecutor。
- 实现 HarnessAgent/Builder 对 ReActAgent 的完整委托。
- 接入 `read`、`glob/grep` 和 `bash`。
- 打通流式与非流式集成测试。

### 阶段二：安全写操作

- 实现 `write`、`edit` 和 read-before-write。
- 补齐符号链接、文件大小、输出量和命令超时限制。
- 打通 cancel 到本地进程树。

### 阶段三：工程化完善

- Trace 中加入 workspaceId、tool、duration、exitCode 等属性。
- 增加敏感命令审批扩展点。
- 完善配置、日志脱敏、指标和故障诊断。

## 13. Runtime 演进

Runtime 聚合边界已在本地版落地。后续引入 Docker、E2B 或远程环境时，保持 `HarnessAgent` 和 `IAgent` API 不变，新增对应 Runtime 实现：

```text
Runtime
├── LocalRuntime
├── DockerRuntime
├── E2BRuntime
└── RemoteRuntime
```

当引入非本地 Runtime 时，文件操作应通过 Runtime 暴露的文件会话完成，不能假设 `java.nio.file.Path` 一定对应本机路径。当前 `Runtime.workspace()` 服务于本地版兼容和观测；引入首个远程 Runtime 前，应将其收敛为不泄露本地 `Path` 的 Workspace 描述或会话抽象。

## 14. 初版完成标准

- `HarnessAgent` 实现 `IAgent`，通用调用方无需感知内部 ReActAgent。
- 一个实例只能绑定一个已规范化的本地 Workspace。
- `run`、`runStream`、`cancel`、执行状态和上下文清理行为可验证。
- ReActAgent 能调用 `read/write/edit/glob/grep/rg/bash` 工具。
- 文件路径无法通过绝对路径、`..` 或符号链接逃逸 Workspace。
- 已有文件无法在未读取时被 write/edit。
- 命令只能在 Workspace 内运行，支持超时、截断和取消。
- 单元测试、集成测试和 Maven package 均通过。
- 不影响 agent-core 的既有 Agent，也不要求 service 模块依赖 Harness。

该版本完成后，AgentForge 将具备一个边界清晰的本地工程 HarnessAgent：对外仍是标准 Agent，对内复用成熟的 ReAct 推理能力，并通过 Workspace 和本地命令执行器将工具能力收敛在可控制范围内。
