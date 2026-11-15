---
title: Harness Runtime Design & Implementation
sidebar_label: Runtime Design & Implementation
description: Understand the local runtime implementation and multi-runtime extension from Runtime components and Harness composition responsibilities
sidebar_position: 2
displayed_sidebar: sdkSidebar
---

# Harness Runtime Design & Implementation

The core Harness design can be summarized in one sentence: **Runtime describes the execution environment, and the Harness layer selects and composes tools, permission policies, prompts, and Agent capabilities for that environment.**

The reasoning loop is still handled by `ReActAgent`. Harness does not reimplement think → act; it performs assembly on top of Runtime so the same Agent capabilities can run in a local directory, a container sandbox, or a remote environment.

The first release only implements the local runtime. This article explains Runtime design theory, core components, and Harness composition responsibilities, then describes the current `LocalRuntime` implementation and how future runtimes extend it.

## 1. Design theory

### 1.1 Three-layer responsibility split

```text
Business caller
    │ IAgent.run / IAgent.runStream
    ▼
Harness layer (composition root)
    │ Select by Runtime: tools / permission policy / prompt / model / memory
    ▼
ReActAgent (reasoning kernel)
    │ think → act → write back memory
    ▼
Runtime (execution environment)
    │ Workspace / RuntimeTools / RuntimeOptions / resource lifecycle
    ▼
Local / Docker / E2B / Remote
```

| Layer | Core responsibility | Should not handle |
| --- | --- | --- |
| Harness layer | Read Runtime capabilities, select components, assemble the Agent | Must not implement the reasoning loop or execute commands directly |
| ReActAgent | Model calls, tool-call orchestration, memory write-back, cancellation, state | Must not be aware of the concrete execution environment |
| Runtime | Define environment semantics, tools, default prompt, and resource lifecycle | Must not parse model responses or decide Agent policy |

The split has three direct benefits:

1. **One reasoning kernel adapts to different environments**: local, container, and remote only replace Runtime;
2. **Environment differences are centralized**: path semantics, command capabilities, and isolation levels are owned by Runtime;
3. **Composition is auditable**: tools and permission policies are explicitly selected by the Harness layer instead of hidden in conditional branches inside the Agent.

### 1.2 The Harness layer is the composition root

"Composition root" means object creation and dependency selection happen during Harness construction, not scattered across components at runtime:

```text
Runtime
  ├── workspace()            -> decides where file tools can access
  ├── tools()                -> decides which tools are exposed to the model
  ├── options()              -> decides timeout, output, isolation and other execution parameters
  ├── defaultSystemPrompt()  -> decides environment-related behavior constraints
  └── type()                 -> decides default permission profile and extension capabilities
        │
        ▼
Harness layer composition
  ├── ToolService(runtime.tools + additionalTools)
  ├── PermissionInterruptModule(selected by Runtime capability)   <- planned
  ├── ReActAgent(model / memory / middleware / prompt)
  └── HarnessAgent(runtime + delegate)
```

The current first release already composes tools, default prompt, model, memory, and middleware. The permission component selection entry is not yet exposed on `HarnessAgent.Builder`; it belongs to the next stage. The Agent layer already has `PermissionInterruptModule`, so it can later be selected and injected at Harness build time by Runtime type.

### 1.3 Select tools and permissions by Runtime

Different Runtime capabilities suit different tools and permission policies:

| Runtime form | Typical tools | Recommended policy | Rationale |
| --- | --- | --- | --- |
| Local writable workspace | `read/write/edit/glob/grep/rg/bash` | `DEFAULT` + `ASK` for writes and Shell, `DENY` for dangerous commands | Someone is present and confirmation is useful |
| Controlled container sandbox | Files, search, commands | `BYPASS` + explicit red-line `DENY` | The environment is disposable; block unacceptable operations |
| Read-only remote workspace | `read/glob/grep` | `DONT_ASK` + read-only allowlist | Unattended; must not wait |
| CI / batch processing | Build and test tools | `DONT_ASK` + minimal allowlist | No human is available to approve during the run |

This is what "compose at the Harness layer based on Runtime" means:

- Runtime does not hold permission components directly, avoiding coupling between execution environment and governance policy;
- The Harness layer selects a default permission profile from `RuntimeType`, `RuntimeOptions`, and available tools;
- Callers can still explicitly override the default policy;
- Tool sets and permission policies must be designed together; never expose tools without boundaries.

> Current state: tool and prompt composition is implemented; permission-profile selection and `PermissionInterruptModule` injection are the next Harness extension. The permission pairings in this article describe the design direction, not an exposed Builder API.

## 2. Core Runtime components

### 2.1 Runtime contract

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

| Member | Responsibility | Role in Harness composition |
| --- | --- | --- |
| `type` | Stable runtime type identifier | Select default tools and permission profile |
| `options` | Execution parameters for the current environment | Control timeout, output, isolation, and similar behavior |
| `workspace` | Workspace semantics and safety boundary | Constrain file tools and command directories |
| `tools` | Tool set matching the current environment | Converted into `ToolService` |
| `defaultSystemPrompt` | Complete environment-related prompt | Used when the user prompt is empty |
| `cancelAll` | Cancel active tasks in the current runtime | Propagate Agent cancellation |
| `close` | Release processes, thread pools, and sessions | Cascade release when the Agent closes |

Runtime is a lightweight capability aggregate and lifecycle boundary; it does not handle Agent reasoning.

### 2.2 The default prompt belongs to Runtime

`HarnessAgent` does not maintain one default prompt shared by all runtimes, because the Agent's role description, working style, and tool boundaries must evolve with the real environment. Local path semantics, available commands, and isolation level differ from container or remote runtimes.

Selection rules:

- `systemPrompt` is non-empty: use the user value as-is;
- `systemPrompt` is empty or whitespace only: use `runtime.defaultSystemPrompt()`;
- The two are not appended or merged.

The local default template has two parts:

```text
# Harness Agent

<role, workflow and general behavior constraints>

# Runtime

<current environment, Workspace, tool capabilities and execution boundaries>
```

`${workspaceId}` and `${workspaceRoot}` are replaced during Runtime construction. A missing template or variable fails construction immediately.

### 2.3 RuntimeType and RuntimeOptions

`RuntimeType` provides stable codes for configuration, persistence, and Harness defaults; do not depend on `ordinal()`:

```java
LOCAL(1, "local", "Local workspace runtime");

RuntimeType.fromCode(1);
RuntimeType.fromName("local");
```

`RuntimeOptions` and `RuntimeType` are paired by the concrete Runtime. `LocalRuntimeOptions` only carries local execution parameters:

```java
public final class LocalRuntimeOptions implements RuntimeOptions {

    private final String shell;                  // default /bin/sh
    private final long commandTimeoutMillis;     // default 30000
    private final int maxCommandOutputChars;     // default 20000
}
```

Future Docker, E2B, or remote runtimes each provide their own Options implementation without changing the public structure of `HarnessAgent`.

## 3. Harness-layer composition

### 3.1 Current assembly flow

```text
HarnessAgent.Builder.build()
  -> Validate runtime / model / chatMemoryProvider
  -> LocalToolFactory.buildLocalTools(runtime.tools().toolObjects())
  -> Merge additionalTool / additionalTools
  -> Choose user systemPrompt or runtime.defaultSystemPrompt()
  -> Build ReActAgent(chatModel / streamingChatModel / memory / settings / middleware)
  -> Return HarnessAgent(runtime + delegate)
```

Corresponding code:

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

Composition boundaries:

| Provided by Runtime | Handled by Harness layer |
| --- | --- |
| `workspace()` | Passed to Runtime tools for path validation |
| `tools()` | Converted to `ToolService` through `LocalToolFactory` |
| `defaultSystemPrompt()` | Injected into ReActAgent when the user did not provide a prompt |
| `type()` / `options()` | Currently used for observation; later used to select default permission profiles |
| `cancelAll()` / `close()` | Called by HarnessAgent on cancellation and close |

### 3.2 Where permission components belong

Permissions are Agent governance capabilities, not execution-environment capabilities. The sensible composition is:

```text
Runtime capabilities
  ├── type = LOCAL
  ├── tools include write / bash
  └── environment is rollback-friendly and someone is present
        │
        ▼
Harness layer selects default permission profile
  ├── allow reads
  ├── ask writes and Shell
  ├── deny destructive commands
  └── enableAskUser
        │
        ▼
PermissionInterruptModule injected into ReActAgent
```

This enables:

1. The local Runtime keeps human approval by default;
2. A sandbox Runtime can switch to `BYPASS` and keep only hard red lines;
3. A read-only remote Runtime can switch to `DONT_ASK` and allow only the allowlist;
4. Explicit caller configuration always overrides the default profile.

`HarnessAgent.Builder` does not yet expose `permissionInterruptModule(...)`. The next stage should complete this selection during Harness construction, rather than making Runtime hold permission components or making the Agent branch on type internally.

## 4. Current implementation: LocalRuntime

### 4.1 Construction

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

Assembly steps:

1. Validate that Workspace is non-null and fill in default `LocalRuntimeOptions`;
2. Render the local-runtime default prompt;
3. Create `LocalRuntimeTools` and `CommandExecutor`;
4. Fix `type()` to `RuntimeType.LOCAL`.

`LocalRuntime` does not handle reasoning, parse model responses, or know about the caller entry point.

### 4.2 Workspace boundary

```java
public final class Workspace {
    private final String id;
    private final Path root;
    private final Path workingDirectory;
}
```

- `root` is converted to an absolute, normalized, real path at construction;
- `workingDirectory` must be inside `root` and is the default command directory;
- `resolveExisting` handles existing paths, resolves real paths, and rejects symlink escapes;
- `resolveForCreate` handles paths to be created and checks the real path of the nearest existing parent.

Every tool that accepts a path must go through Workspace resolution. String prefix checks are not allowed for parent-child relationships:

```java
// Wrong: cannot distinguish /workspace-a from /workspace-a2
path.toString().startsWith(root.toString());

// Correct: compare by path hierarchy
resolved.startsWith(root);
```

### 4.3 RuntimeTools and local tools

```java
public interface RuntimeTools extends AutoCloseable {

    RuntimeType type();

    Collection<Object> toolObjects();

    void cancelAll();

    @Override
    void close();
}
```

`HarnessAgent` depends only on `RuntimeTools`; at build time it scans `toolObjects()` through `LocalToolFactory`. The first local tool set:

| Tool | Purpose | Safety constraints |
| --- | --- | --- |
| `read` | Read UTF-8 text by line | File size, line count, and line length limits |
| `write` | Create or overwrite files | Existing files must be read first; controlled parent creation |
| `edit` | Replace text exactly | File already read; old text must match uniquely |
| `glob` | Find files by pattern | Scans only the Workspace; limits result count |
| `grep` | Search content by regex | Scans only the Workspace; limits file size and results |
| `rg` | Call local ripgrep | Returns a clear error if `rg` is missing |
| `bash` | Run foreground commands | Fixed working directory; timeout and output limits |

After a successful `read`, the canonical absolute path is recorded. Later writes to an existing file or edits must hit that set:

```text
read(path) succeeds
    -> readFiles.add(canonicalPath)

write(existingPath)
    -> readFiles.contains(canonicalPath) or reject

edit(path)
    -> readFiles.contains(canonicalPath) or reject
```

This state currently follows the `LocalRuntimeTools` lifecycle and reduces the risk of overwriting unread files. Per-`executionId` isolation is a later concurrency-safety enhancement.

### 4.4 Local command execution

`CommandExecutor` uses `ProcessBuilder` to run foreground commands:

- Starts with `shell -lc <command>`;
- Fixes `directory` to the Workspace `workingDirectory`;
- Consumes stdout and stderr together to avoid buffer blocking;
- Forcefully destroys the process after timeout;
- Truncates and marks output beyond the limit;
- Returns the exit code, timeout flag, and output text;
- `cancelAll` terminates active processes in the current Runtime.

The first release provides no background tasks, daemons, or remote commands. The `bash` command string is executed by the system shell with the same permissions as the operating-system user that started AgentForge.

## 5. Lifecycle and concurrency

### 5.1 Lifecycle

```text
LocalRuntime.Builder creates Runtime
    -> HarnessAgent.Builder receives Runtime
    -> HarnessAgent builds successfully and takes over Runtime lifecycle
    -> HarnessAgent.close()
          -> Runtime.close()
              -> RuntimeTools.close()
                  -> CommandExecutor.close()
```

- One HarnessAgent corresponds to one Workspace and can be reused by multiple requests;
- After `close()`, new requests are rejected and unfinished commands are canceled;
- Do not create a new Runtime per request, or read state, command executors, and the default prompt will be recreated repeatedly.

### 5.2 Concurrency boundaries

The current implementation guarantees:

- `readFiles` uses a concurrent set, and the container is thread-safe;
- Active processes use a concurrent set, and `cancelAll` can iterate safely;
- Commands in the same Runtime can be terminated together.

Not yet guaranteed:

- `readFiles` and active processes isolated by `executionId`;
- Transactional consistency for concurrent writes to the same file;
- Multiple HarnessAgents sharing one Runtime;
- Cross-node scheduling and distributed locks.

Later, file access state and active processes can become `ConcurrentMap<executionId, ...>` so cancellation affects only the target execution. Concurrent writes to the same file can use a temporary file plus atomic move, or an in-process file lock.

### 5.3 Error model

Tools should return errors the model can understand without leaking sensitive host information:

```text
WORKSPACE_PATH_DENIED
FILE_NOT_READ
FILE_TOO_LARGE
SEARCH_LIMIT_EXCEEDED
COMMAND_TIMEOUT
COMMAND_CANCELLED
COMMAND_EXECUTOR_CLOSED
```

Programming errors and unrecoverable initialization problems throw exceptions; expected tool failures become structured results so ReActAgent can adjust its operations.

## 6. Extending other runtimes

Adding a runtime only requires implementing three contracts; the `HarnessAgent` and `IAgent` APIs stay unchanged:

```text
Runtime
├── LocalRuntime    implemented
├── DockerRuntime   planned
├── E2BRuntime      planned
└── RemoteRuntime   planned
```

Extension steps:

1. Add a `RuntimeOptions` implementation describing the environment configuration;
2. Add a `RuntimeTools` implementation providing tool objects that match the environment;
3. Add a `Runtime` implementation declaring `RuntimeType`, Workspace semantics, default prompt, and lifecycle;
4. Let the Runtime's own Builder handle validation and assembly;
5. Select tools and default permission profiles at the Harness layer based on Runtime capabilities;
6. Load it through `HarnessAgent.Builder.runtime(...)`.

Not required:

- Change the public HarnessAgent entry point;
- Add `RuntimeType` branches inside HarnessAgent;
- Change the ReActAgent main loop;
- Make `agent-core` depend on Harness.

### 6.1 Extra constraints for remote runtimes

`Runtime.workspace()` currently returns a local `Path` for local compatibility and observation. Once remote runtimes are introduced, `java.nio.file.Path` can no longer be assumed to map to a local path. Before the first remote Runtime lands, Workspace should become a descriptor or file-session abstraction that does not expose local paths.

Remote runtime tools should also not reuse local `ProcessBuilder` directly. File reads/writes, search, and command execution should go through session capabilities exposed by the Runtime, with the Runtime responsible for timeout, cancellation, and resource release.

### 6.2 Explicitly out of scope for the first release

- Docker, E2B, SSH, or other remote runtimes;
- Cross-machine file synchronization and remote command execution;
- Dynamically switching one HarnessAgent across multiple Workspaces;
- Full human approval, operation rollback, and multi-tenant resource scheduling;
- Direct compatibility with AgentX annotations, sessions, or Sandbox APIs.

These capabilities keep their extension boundaries and are not part of the first release.

## 7. Responsibility summary

| Capability | Harness layer | Runtime / RuntimeTools |
| --- | --- | --- |
| Select model and memory | Yes | No |
| Assemble ReActAgent | Yes | No |
| Select tool set | Yes | Provide candidate tools |
| Select permission profile | Yes (planned) | Provide environment capability information |
| Add business tools | Yes | No |
| Select user prompt | Yes | Provide default value |
| Path safety boundary | No | Yes |
| File read/write and search | Through tool calls | Provide and execute |
| Command execution and cancellation | Propagate cancellation | Execute and terminate processes |
| Resource release | Trigger close | Release concrete resources |

In one sentence: **Runtime describes "where and with what"; the Harness layer decides "which tools and permissions the model gets"; ReActAgent handles "how to reason and act".**

Next: return to [Build a HarnessAgent](./quickstart) to complete your first local engineering Agent.