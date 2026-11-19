---
title: 默认内存级会话审批确认
sidebar_label: 内存级会话审批确认
description: 理解 InMemorySessionPermissionApprovalStore 如何按 memoryId 隔离 APPROVE_ALWAYS，以及确认一次、始终允许和拒绝的执行差异
sidebar_position: 1
displayed_sidebar: sdkSidebar
---

# 默认内存级会话审批确认

`InMemorySessionPermissionApprovalStore` 是 AgentForge 内置的权限审批 Store。启用 `PermissionInterruptModule` 后，无需额外配置即可使用。它解决的是一个明确问题：用户在会话 A 中选择“始终允许”，不能导致会话 B 中的相同 Tool Call 被自动放行。

默认实现只保存在当前进程内，适合本地应用、单节点服务和首期接入。需要跨重启或接入业务审批中心时，可以替换为自定义 `PermissionApprovalStore`。

## 1. 三种审核确认操作

权限中断目前只支持三种 `ApprovalAction`：

```java
public enum ApprovalAction {
    APPROVE_ONCE,
    APPROVE_ALWAYS,
    REJECT
}
```

| 用户操作 | 当前冻结 Tool Call | 默认 Store 行为 | 后续相同调用 |
| --- | --- | --- | --- |
| `APPROVE_ONCE`，确认一次执行 | 执行 | 不保存授权 | 再次进入权限判断，可能重新询问 |
| `APPROVE_ALWAYS`，确认永远执行 | 执行 | 保存当前会话授权 | 同一会话直接放行 |
| `REJECT`，拒绝执行 | 不执行，写入拒绝 Tool Result | 不保存授权 | 再次进入权限判断 |

“确认永远执行”在默认实现中不是系统全局永久授权。它的准确含义是：**在当前进程存活期间，对当前 `memoryId` 会话中的完全相同 Tool Call 持续放行。**

## 2. 扩展接口

默认实现基于 `PermissionApprovalStore`：

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

每次权限预检都会调用 `isApproved`。此时还没有发生本次用户审批，所以方法不接收 `ApprovalAction`：

```text
Tool Call
  -> isApproved(context, request)
  -> 已有 APPROVE_ALWAYS 授权：直接 ALLOW
  -> 未命中：继续进入 PermissionEngine
```

### `addApproval`

用户恢复中断时，框架把真实 `ApprovalAction`、恢复阶段的 `AgentChatContext` 和快照中冻结的原始 Tool Call 一起传入：

```text
InterruptResponse
  -> resolve(command) 校验并抢占批次
  -> 构造恢复阶段 AgentChatContext
  -> addApproval(context, frozenRequest, action)
  -> Core 执行冻结调用或写入拒绝结果
```

三种 Action 都会进入 `addApproval`，便于自定义实现统一审计；默认实现只有在 `APPROVE_ALWAYS` 时写入授权。

## 3. 会话隔离的数据结构

默认实现使用线程安全的两级结构：

```java
private final ConcurrentMap<Object, Set<String>> approvedCallsBySession =
        new ConcurrentHashMap<Object, Set<String>>();
```

逻辑结构是：

```text
memoryId
  -> Set<toolName + "\n" + rawArguments>
```

例如：

```text
conversation-a
  -> shell + {"command":"npm test"}

conversation-b
  -> 空
```

此时会话 A 再次执行完全相同的 `shell` 调用会直接放行，会话 B 仍会产生审批中断。

当前指纹使用工具名与原始参数字符串，因此下面两项必须都相同：

- `ToolExecutionRequest.name()`；
- `ToolExecutionRequest.arguments()` 原始字符串。

参数含义相同但 JSON 字段顺序或空白不同，也会被视为不同调用并重新审批。

## 4. 默认实现的核心逻辑

查询授权：

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

记录审批：

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
    // 取得或创建当前会话的并发 Set，然后写入调用指纹。
}
```

如果 `chatContext` 或 `memoryId` 为空，默认实现直接抛出异常，不会退化成 Agent 实例全局授权。

## 5. SDK 配置

默认使用不需要显式注册 Store：

```java
PermissionInterruptModule module =
        PermissionInterruptModule.safeDefaults()
                .ask("shell", ".*")
                .build();
```

构建时没有传入自定义 Store，模块会自动创建：

```java
new InMemorySessionPermissionApprovalStore()
```

再注入 Agent：

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

同一个 Module 可以由多个会话复用，默认 Store 会通过 `memoryId` 隔离它们。

## 6. 非流式与流式行为一致

非流式恢复：

```java
ChatResult resumed = agent.resumeInterrupt(command);
```

流式恢复：

```java
TokenStream resumed = agent.resumeInterruptStream(command);
```

两种入口共用 `ReActAgent.applyInterruptBatch`。框架在执行冻结 Tool Call 前统一调用：

```java
permissionInterruptModule.applyApprovals(resolved, context);
```

因此无论从非流式还是流式入口选择 `APPROVE_ALWAYS`，后续同一 `memoryId` 中的相同 Tool Call 都能命中授权；其他会话仍然需要独立确认。

## 7. 生命周期与边界

默认内存实现具有以下边界：

- 只在当前 Java 进程内生效；
- 服务重启后授权丢失；
- 不跨节点同步；
- 不提供 TTL、撤销列表或管理接口；
- 使用原始参数字符串进行精确匹配；
- 高风险、破坏性或不可逆操作不应提供 `APPROVE_ALWAYS`。

如果需要数据库持久化、用户级授权、项目级授权、过期时间或审批审计，请实现自定义 `PermissionApprovalStore`，并通过：

```java
.permissionApprovalStore(customStore)
```

注入模块。完整接口和扩展示例参见 [权限设计 & HITL](../permission-hitl#53-自定义审批授权-store接管审核确认记录)。
