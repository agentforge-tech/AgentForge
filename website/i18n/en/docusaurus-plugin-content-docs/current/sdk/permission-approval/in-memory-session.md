---
title: Default In-Memory Conversation Approval
sidebar_label: In-Memory Conversation Approval
description: Learn how InMemorySessionPermissionApprovalStore isolates APPROVE_ALWAYS by memoryId and how allow once, always allow, and reject differ
sidebar_position: 1
displayed_sidebar: sdkSidebar
---

# Default In-Memory Conversation Approval

`InMemorySessionPermissionApprovalStore` is AgentForge's built-in permission approval store. It is enabled automatically with `PermissionInterruptModule`. Its key guarantee is straightforward: selecting “always allow” in conversation A must not automatically allow the same Tool Call in conversation B.

The default store is process-local, making it suitable for local applications, single-node services, and initial integrations. Replace it with a custom `PermissionApprovalStore` when grants must survive restarts or integrate with a business approval service.

## 1. Three approval operations

Permission interrupts currently support three `ApprovalAction` values:

```java
public enum ApprovalAction {
    APPROVE_ONCE,
    APPROVE_ALWAYS,
    REJECT
}
```

| User choice | Current frozen Tool Call | Default store behavior | Later identical calls |
| --- | --- | --- | --- |
| `APPROVE_ONCE` | Execute | Do not save a grant | Run permission evaluation again and possibly ask again |
| `APPROVE_ALWAYS` | Execute | Save a grant for the current conversation | Allow directly in the same conversation |
| `REJECT` | Do not execute; write a rejection Tool Result | Do not save a grant | Run permission evaluation again |

With the default store, “always allow” is not a system-wide permanent grant. It means: **while the current process remains alive, keep allowing the exact same Tool Call for the current `memoryId`.**

## 2. Extension interface

The default implementation uses `PermissionApprovalStore`:

```java
public interface PermissionApprovalStore {

    boolean isApproved(
            AgentChatContext context,
            ToolExecutionRequest request);

    void addApproval(
            AgentChatContext context,
            ToolExecutionRequest request,
            ApprovalAction action);
}
```

### `isApproved`

Every permission preflight calls `isApproved`. The current user approval has not happened yet, so this method does not receive an `ApprovalAction`:

```text
Tool Call
  -> isApproved(context, request)
  -> prior APPROVE_ALWAYS grant found: ALLOW directly
  -> no match: continue through PermissionEngine
```

### `addApproval`

When the user resumes an interrupt, the framework passes the actual action, the recovery `AgentChatContext`, and the original Tool Call frozen in the snapshot:

```text
InterruptResponse
  -> resolve(command) validates and claims the batch
  -> create the recovery AgentChatContext
  -> addApproval(context, frozenRequest, action)
  -> Core executes the frozen call or writes a rejection result
```

All three actions reach `addApproval`, allowing custom stores to audit them consistently. The default store writes a grant only for `APPROVE_ALWAYS`.

## 3. Conversation-scoped data structure

The default implementation uses a thread-safe two-level structure:

```java
private final ConcurrentMap<Object, Set<String>> approvedCallsBySession =
        new ConcurrentHashMap<Object, Set<String>>();
```

Its logical shape is:

```text
memoryId
  -> Set<toolName + "\n" + rawArguments>
```

For example:

```text
conversation-a
  -> shell + {"command":"npm test"}

conversation-b
  -> empty
```

Conversation A now allows the exact same `shell` call directly, while conversation B still suspends for approval.

The fingerprint uses the tool name and raw argument string. Both must match:

- `ToolExecutionRequest.name()`;
- The raw `ToolExecutionRequest.arguments()` string.

Semantically equivalent JSON with different field ordering or whitespace is treated as a different call and requires approval again.

## 4. Core default behavior

Grant lookup:

```java
@Override
public boolean isApproved(
        AgentChatContext context,
        ToolExecutionRequest request) {
    Object memoryId = requiredMemoryId(context);
    Set<String> approvedCalls = approvedCallsBySession.get(memoryId);
    return approvedCalls != null
            && approvedCalls.contains(fingerprint(request));
}
```

Approval processing:

```java
@Override
public void addApproval(
        AgentChatContext context,
        ToolExecutionRequest request,
        ApprovalAction action) {
    if (action == null) {
        throw new IllegalArgumentException(
                "approval action must not be null");
    }
    if (action != ApprovalAction.APPROVE_ALWAYS) {
        return;
    }

    Object memoryId = requiredMemoryId(context);
    // Get or create the conversation's concurrent Set and store the call fingerprint.
}
```

If `chatContext` or `memoryId` is null, the default implementation throws instead of degrading to an Agent-instance-wide grant.

## 5. SDK configuration

No explicit store registration is needed for the default behavior:

```java
PermissionInterruptModule module =
        PermissionInterruptModule.safeDefaults()
                .ask("shell", ".*")
                .build();
```

When no custom store is provided, the module creates:

```java
new InMemorySessionPermissionApprovalStore()
```

Inject the module into the Agent:

```java
ReActAgent agent =
        ReActAgent.builder()
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .toolService(toolService)
                .permissionInterruptModule(module)
                .build();
```

One module can serve multiple conversations because the default store isolates them by `memoryId`.

## 6. Identical streaming and non-streaming behavior

Non-streaming recovery:

```java
ChatResult resumed = agent.resumeInterrupt(command);
```

Streaming recovery:

```java
TokenStream resumed = agent.resumeInterruptStream(command);
```

Both entry points share `ReActAgent.applyInterruptBatch`. Before executing frozen Tool Calls, the framework invokes:

```java
permissionInterruptModule.applyApprovals(resolved, context);
```

Selecting `APPROVE_ALWAYS` through either entry point therefore allows the same Tool Call later in the same `memoryId`, while other conversations still require their own approval.

## 7. Lifecycle and boundaries

The default in-memory store:

- Applies only within the current Java process;
- Loses grants when the service restarts;
- Does not synchronize across nodes;
- Provides no TTL, revocation list, or management API;
- Uses exact matching on the raw argument string;
- Should not be used to “always allow” dangerous, destructive, or irreversible operations.

For database persistence, user- or project-level scope, expiration, or approval audit, implement a custom `PermissionApprovalStore` and inject it with:

```java
.permissionApprovalStore(customStore)
```

See [Permission Design & HITL](../permission-hitl#53-custom-approval-store-take-control-of-approval-records) for the complete interface and extension example.
