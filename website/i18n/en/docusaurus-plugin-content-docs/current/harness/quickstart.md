---
title: Build a HarnessAgent
sidebar_label: Build a HarnessAgent
description: Add HarnessAgent with Maven, understand how it differs from a plain ReActAgent, and quickly build a local-runtime Harness
sidebar_position: 1
displayed_sidebar: sdkSidebar
---

# Build a HarnessAgent

`HarnessAgent` is not a new reasoning engine. It still exposes the standard `IAgent` interface, composes a `ReActAgent` internally, and binds a `Runtime` to gain workspace boundaries, engineering tools, and runtime resource lifecycle.

The quick build has three steps: add the dependency → create a Workspace and LocalRuntime → assemble the model and memory with HarnessAgent.

## 1. Add the dependency

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-harness-agent</artifactId>
    <version>1.0.2-SNAPSHOT</version>
</dependency>
```

`agentforge-harness-agent` transitively brings `agentforge-agent-core`, `agentforge-model-api`, and `agentforge-model-core`. At runtime you also need at least one model Provider, for example:

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-openai</artifactId>
    <version>1.0.2-SNAPSHOT</version>
</dependency>
```

With the BOM, versions can be omitted:

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

## 2. Differences from a plain Agent

A plain Agent targets `ReActAgent` directly: the caller assembles the model, memory, and tools. HarnessAgent adds an "execution environment" abstraction on top:

| Dimension | Plain `ReActAgent` | `HarnessAgent` |
| --- | --- | --- |
| Positioning | General ReAct reasoning loop | Workspace-oriented engineering Agent |
| Construction | Configure model, memory, and ToolService directly | Must pass a `Runtime` through the Builder |
| Tool source | Registered by the business | Runtime provides `read / write / edit / glob / grep / rg / bash` |
| Safety boundary | No built-in Workspace constraint | Bound to one `Workspace`; files and commands are constrained by path boundaries |
| System prompt | Provided by the user | Uses the Runtime default prompt when the user value is empty |
| Resource lifecycle | No extra runtime resources | `AutoCloseable`; releases command executors and active processes on close |
| Extension direction | Tools, middleware, custom Agent | Add a Runtime to support Docker, E2B, or remote environments |
| Public API | `IAgent` | Also `IAgent`; delegates to ReActAgent internally |

Three things need special attention:

1. **One HarnessAgent is bound to one Workspace** and does not switch between workspaces dynamically;
2. **HarnessAgent does not branch on Runtime type**; Docker, E2B, and other environments are added through new Runtime implementations;
3. **The first release enforces safety boundaries in Workspace and tool code**, not in prompts; `bash` still runs with the operating-system user that started AgentForge.

## 3. Quickly build a local HarnessAgent

### 3.1 Create a Workspace

The root directory must already exist and be readable, and is converted to an absolute real path:

```java
import cloud.changlu.agentforge.harness.runtime.local.workspace.Workspace;

import java.nio.file.Paths;

Workspace workspace =
        Workspace.builder()
                .id("my-project")
                .root(Paths.get("/path/to/project"))
                .build();
```

`id` is used in logs and Trace; when omitted, the directory name is used. `workingDirectory` can be omitted and defaults to `root`.

### 3.2 Create a LocalRuntime

```java
import cloud.changlu.agentforge.harness.runtime.local.LocalRuntime;
import cloud.changlu.agentforge.harness.runtime.local.config.LocalRuntimeOptions;

LocalRuntime runtime =
        LocalRuntime.builder()
                .workspace(workspace)
                .options(LocalRuntimeOptions.defaults())
                .build();
```

`options` can be omitted. Default command parameters:

| Parameter | Default | Description |
| --- | --- | --- |
| `shell` | `/bin/sh` | Runs foreground commands through `shell -lc` |
| `commandTimeoutMillis` | `30000` | Forcefully destroys the process after timeout |
| `maxCommandOutputChars` | `20000` | Combined output limit; excess is truncated |

### 3.3 Create a HarnessAgent

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

`runtime` and `chatMemoryProvider` are required; provide at least one of `chatModel` and `streamingChatModel`. When `systemPrompt` is not provided, HarnessAgent uses `runtime.defaultSystemPrompt()`.

### 3.4 Run and close

```java
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.step.ChatResult;

try {
    ChatResult result =
            agent.run(
                    AgentRequest.builder()
                            .memoryId("my-project:conversation:1")
                            .question("Inspect the project structure first, then explain how to run the tests.")
                            .build());

    System.out.println(result.getRes());
} finally {
    agent.close();
}
```

Streaming uses the same assembly; switch to `runStream`:

```java
agent.runStream(
                AgentRequest.builder()
                        .memoryId("my-project:conversation:1")
                        .question("Find the cause of the most recent failing test.")
                        .build())
        .onPartialResponse(System.out::print)
        .onToolExecuted(tool ->
                System.out.println("\n[tool] " + tool.request().name()))
        .onCompleteResponse(response ->
                System.out.println("\n[done] " + response.aiMessage().text()))
        .onError(error -> System.err.println("[error] " + error.getMessage()))
        .start();
```

When execution is canceled, HarnessAgent propagates cancellation to the Runtime and terminates local commands:

```java
agent.cancel(memoryId, executionId);
```

## 4. What the local runtime provides

`LocalRuntimeTools` initially includes:

| Tool | Purpose | Key constraints |
| --- | --- | --- |
| `read` | Read UTF-8 text line by line | Limits file size, line count, and line length |
| `write` | Create or overwrite a file | Existing files must be read first |
| `edit` | Replace text exactly | File already read; old text must match uniquely |
| `glob` | Find files by pattern | Scans only the Workspace; limits result count |
| `grep` | Search content by regex | Scans only the Workspace; limits file size and results |
| `rg` | Invoke local ripgrep | Returns a clear error when `rg` is missing |
| `bash` | Run foreground commands in the Workspace | Background tasks forbidden; timeout and output limits |

All path operations go through `Workspace.resolveExisting` or `Workspace.resolveForCreate`, rejecting `..` escapes and symlink escapes. After `read` succeeds, `write` to an existing file and `edit` are allowed.

## 5. Minimal complete example

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
                            .question("Summarize the project structure.")
                            .build());
    System.out.println(result.getRes());
} finally {
    agent.close();
}
```

> `bash` permissions are equivalent to the operating-system user that started AgentForge. Before exposing it to untrusted users, add approval or further isolation; Workspace boundaries cannot replace an OS-level sandbox.

Next: learn about Runtime, Workspace, and RuntimeTools responsibilities and multi-runtime extension by reading [Runtime Design & Implementation](./runtime).