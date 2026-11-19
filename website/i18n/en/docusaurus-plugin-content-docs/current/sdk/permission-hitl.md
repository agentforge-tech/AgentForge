---
title: "Permission Design & HITL: Permission System, Interrupt Events, and Recovery"
sidebar_label: Permission Design & HITL
description: Understand AgentForge permission decisions, approval interrupts, AskUser, and recovery from a design perspective, and complete SDK integration and custom extensions
sidebar_position: 7
displayed_sidebar: sdkSidebar
---

# Permission Design & HITL: Permission System, Interrupt Events, and Recovery

AgentForge separates "whether an action may run" from "what to run": the permission system only produces a decision, and the tool executor only executes calls that have been approved. When the decision is `ASK`, or when the model calls `ask_user` because it needs more information, the Agent does not block a thread. Instead, it freezes the tool calls in the current round into a batch and ends the invocation normally as `SUSPENDED`, so the business layer can submit a structured response and resume later.

This mechanism covers both permission approval and Human-in-the-Loop (HITL). They share one interrupt kernel; the differences are only the request payload, response format, and recovery action.

> Current implementation boundary: one interrupt batch can be resumed only once, protected by `batchId` and the `PENDING -> RESUMING -> COMPLETED` state machine. The current resume command is `batchId + responses`; idempotency keys and resume-result replay are not yet provided. This article marks evolution directions where relevant so planned capabilities are not mistaken for implemented APIs.

## 1. Design positioning

Permissions and interrupts are not two branches of one module; they are three cooperating layers:

| Layer | Responsibility | Current implementation |
| --- | --- | --- |
| Permission decision | Produce `ALLOW / DENY / ASK` for each Tool Call and query conversation-scoped grants | `PermissionEngine`, `PermissionRule`, `PermissionMode`, `PermissionApprovalStore` |
| Interrupt runtime | Freeze objects, save snapshots, suspend, validate responses, resume | `PermissionInterruptModule`, `AgentInterruptSnapshot`, `AgentInterruptStore` |
| Interaction adapter | Turn permission approval and AskUser payloads into tool results | `InterruptType`, `AskUserSupport`, caller UI |

Four design principles run through the implementation:

1. **Decision and execution are separate**: the permission system does not execute tools; `AgentToolExecutor` only receives approved calls.
2. **Interrupt objects cannot drift**: recovery consumes the original Tool Call frozen at suspend time; the UI cannot send back or rewrite arguments.
3. **Suspension does not hold a thread**: when an interrupt occurs, the invocation ends normally as `SUSPENDED`; it does not block a Future waiting for the user.
4. **Fail closed by default**: missing responses, mismatched types, missing batches, or disallowed states reject recovery instead of guessing.

Only `ASK` and `ASK_USER` enter the suspended state. `DENY` is not an interrupt: it writes a failure result for the corresponding Tool Call in the current batch, lets the remaining calls continue, and then enters the next think round.

## 2. Core permission system

### 2.1 Three-state decision

```java
public enum PermissionDecision {
    ALLOW,
    DENY,
    ASK
}
```

| Decision | Meaning | Next action |
| --- | --- | --- |
| `ALLOW` | The current call may execute directly | Enter the ToolExecutor |
| `DENY` | The current call is rejected by policy | Do not execute; write a failure Tool Result and continue |
| `ASK` | User confirmation is required | Create a `PERMISSION_APPROVAL` interrupt and freeze the current batch |

`ASK` is the only connection point between the permission system and the interrupt kernel. The permission system does not handle suspension, and the interrupt kernel does not interpret permission rules.

### 2.2 Rules and rule handlers

Permission rules use a "rule type + type handler" extension model. A rule object only describes its type and the decision when matched; matching logic belongs to the handler:

```java
public interface PermissionRule {
    String getId();
    String getType();
    PermissionDecision getDecision();
}

public interface PermissionRuleHandler {
    String type();
    boolean matches(PermissionRule rule, PermissionContext context);
}
```

The only built-in type is `regex`, implemented by `RegexPermissionRule` and `RegexPermissionRuleHandler`:

- `toolRegex` fully matches the tool name;
- `argumentRegex` performs a contains match on the raw JSON arguments;
- Rules run in registration order; the first matching rule wins;
- Module construction validates that every rule has a handler; unknown types fail immediately instead of being silently allowed;
- One handler per type; the built-in `regex` handler cannot be overridden.

Common rules are registered through the fluent API:

```java
PermissionInterruptModule module =
        PermissionInterruptModule.safeDefaults()
                .allow("(read_file|list_files|search_files)", ".*")
                .ask("(write_file|edit_file|delete_file|shell)", ".*")
                .deny("shell", "(?i).*(rm\\s+-rf|git\\s+push\\s+--force).*")
                .build();
```

> Regex rules are good for quick starts, not for path-safety decisions. Structured arguments, workspace escapes, and symlink escapes should be validated by the tool itself, or by a custom rule handler that parses the arguments.

### 2.3 Permission modes: interpret rule results, do not replace rules

`PermissionMode` is a scenario-level policy and does not participate in rule matching. After rules produce a candidate decision, the mode decides how that scenario is handled:

```java
public enum PermissionMode {
    DEFAULT,
    BYPASS,
    DONT_ASK
}
```

| Mode | Rule matches `DENY` | Rule matches `ASK` | Rule matches `ALLOW` | No rule matches |
| --- | --- | --- | --- | --- |
| `DEFAULT` | `DENY` | `ASK` (interrupt) | `ALLOW` | `ALLOW` |
| `BYPASS` | `DENY` | `ALLOW` | `ALLOW` | `ALLOW` |
| `DONT_ASK` | `DENY` | `DENY` | `ALLOW` | `DENY` |

The three modes can be summarized as:

- **`DEFAULT`: rules decide.** Matched rules apply their decision; unmatched calls are allowed. It does not automatically turn unknown operations into approvals, so sensitive tools must explicitly configure `ask`.
- **`BYPASS`: only block red lines.** Explicit `DENY` still applies; everything else is allowed and no permission interrupt is produced.
- **`DONT_ASK`: allowlist only.** Only explicit `ALLOW` passes; everything else is denied, and no interrupt is produced. Suitable for unattended tasks.

`BYPASS` does not disable the permission system, and `DONT_ASK` does not reject everything. Both preserve explicit `DENY`; they differ only in how they handle `ASK` and unmatched rules.

Finer scenario policies are added through `PermissionModePolicy`, not by growing the enum:

```java
public interface PermissionModePolicy {

    PermissionDecision decide(PermissionContext context);

    default PermissionDecision decide(
            PermissionContext context, PermissionDecision candidate) {
        return candidate == null ? decide(context) : candidate;
    }
}
```

### 2.4 Decision order

`PermissionEngine` always uses two stages:

```text
Tool Call
   │
   ▼
PermissionRule + PermissionRuleHandler
   │  first matching rule -> candidate
   ▼
PermissionModePolicy / PermissionMode
   │  combine with candidate for the final decision
   ▼
ALLOW / DENY / ASK
```

At source level:

```java
PermissionDecision candidate = ruleDecision(context);
return effectiveModePolicy.decide(context, candidate);
```

This order guarantees three things:

1. Rules provide fine-grained overrides; mode only interprets the scenario;
2. A custom `PermissionModePolicy` fully takes over the final decision after injection; there are no two parallel decision branches;
3. The same Tool Call is not re-evaluated with different arguments before and after suspension; recovery consumes only the frozen object.

### 2.5 Unified assembly entry

`ReActAgent.Builder` accepts a single `PermissionInterruptModule`. Permission mode, rules, handlers, approval store, interrupt store, and the AskUser switch are all assembled inside the module:

```java
PermissionInterruptModule module =
        PermissionInterruptModule.safeDefaults()
                .enableAskUser()
                .allow("(read_file|list_files|search_files)", ".*")
                .ask("(write_file|edit_file|delete_file|shell)", ".*")
                .deny("shell", "(?i).*(rm\\s+-rf|git\\s+push\\s+--force).*")
                .build();

ReActAgent agent = ReActAgent.builder()
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(chatMemoryProvider)
        .toolService(toolService)
        .permissionInterruptModule(module)
        .build();
```

The module's core runtime entries are:

| Method | Purpose |
| --- | --- |
| `preflight(step, requests, context)` | Evaluate the whole batch of Tool Calls in original order and return allowed, denied, or suspended |
| `resolve(command)` | Validate the whole batch of responses and atomically claim the snapshot |
| `applyApprovals(batch, context)` | Pass all three approval actions and the recovery `AgentChatContext` to the approval store |
| `complete(batch)` | Mark the batch finished after the frozen batch has been applied |

The semantics of `safeDefaults()` must be clear:

- Uses `PermissionMode.DEFAULT`;
- Presets no permission rules, so unmatched calls are allowed;
- Registers the built-in `regex` rule handler;
- Uses the default `InMemoryAgentInterruptStore`;
- Uses the default `InMemorySessionPermissionApprovalStore`, which isolates `APPROVE_ALWAYS` by `memoryId`;
- AskUser is disabled by default; it is enabled by `enableAskUser()`, after which `ReActAgent` automatically registers `AskUserTool`.

By default, `APPROVE_ALWAYS` stores `memoryId + tool name + raw arguments` in process memory. Only an identical call in the same conversation is allowed directly. The grant is not persisted or shared across processes or nodes. Inject a custom `PermissionApprovalStore` when grants must survive restarts or use a wider business scope.

## 3. Interrupt events and recovery

### 3.1 Two interrupt types

```java
public enum InterruptType {
    PERMISSION_APPROVAL,
    ASK_USER
}
```

| Type | Request content | User response | Recovery action |
| --- | --- | --- | --- |
| `PERMISSION_APPROVAL` | Frozen original Tool Call, tool name, arguments | `APPROVE_ONCE / APPROVE_ALWAYS / REJECT` | Execute the original call when approved; write a rejection result when rejected |
| `ASK_USER` | Original `ask_user` Tool Call, structured questions | Selected options or text answers | Validate answers and wrap them as the `ask_user` tool result; other business tools are not executed |

Both types share the same batch, snapshot, state machine, and recovery entry. Permission approval cannot be disguised as `ask_user` to bypass the permission engine, and `ask_user` cannot be used to execute tools.

### 3.2 Batch model

One AI Call may return multiple Tool Calls, including directly allowed, directly denied, permission approval, and AskUser items. Therefore the minimum transaction boundary for suspension and recovery is not a single interrupt item, but the whole **Interrupt Batch**.

```text
InterruptBatchRequest
  batchId = batch_001
  sourceExecutionId = exec_old
  requests[0] interruptId = int_001  type = PERMISSION_APPROVAL
  requests[1] interruptId = int_002  type = ASK_USER
  requests[2] interruptId = int_003  type = PERMISSION_APPROVAL
```

Each key ID has a single responsibility:

| ID | Identifies | Purpose |
| --- | --- | --- |
| `batchId` | The group of interrupts produced by one AI Call | Locate the snapshot and submit the whole batch |
| `interruptId` | A single interrupt item in the batch | Correlate request and response |
| `sourceExecutionId` | The original execution that produced the interrupt | Trace the source; used as previousExecutionId during recovery |
| `toolCallId` | The original Tool Call generated by the model | Bind the approval decision or AskUser answer |

A single interrupt is only the batch special case `requests.size() == 1`; it does not get a separate API.

### 3.3 Snapshot and state machine

`AgentInterruptSnapshot` freezes all information for the round:

- `batchId`, `memoryId`, `sourceExecutionId`, `step`;
- Original Tool Call order in `toolRequests`;
- Pending interrupt items in `interrupts`;
- Directly denied items in `denials`;
- Batch status in `status`.

State transitions are fixed:

```text
Create: PENDING
Resume claim: PENDING -> RESUMING
Result written back: RESUMING -> COMPLETED
```

`resolve` calls `store.claim(batchId)` before applying any tool side effect. The in-memory implementation performs the state CAS in a synchronized block: only `PENDING` can advance to `RESUMING`; `COMPLETED` or `RESUMING` throws immediately.

This guarantees that when two windows submit at the same time, only one request can consume the batch and the other does not execute tools again.

### 3.4 What callers see when suspended

**Non-streaming**:

```java
ChatResult result = agent.run(request);

if (result.getChatResultState() == ChatResultState.SUSPENDED) {
    AgentSuspendInfo suspendInfo = result.getSuspendInfo();
    String batchId = suspendInfo.getBatchId();
    List<InterruptRequest> interrupts = suspendInfo.getInterrupts();
    // Render approval cards or AskUser forms
}
```

When suspended:

- `getChatResultState()` is `SUSPENDED`;
- `getRunState()` is `WAITING_INTERRUPT`;
- `getSuspendInfo()` is the only credential for recovery;
- It is neither a failure nor business completion; the thread and resources have been released.

**Streaming**:

```java
agent.runStream(request)
        .onPartialResponse(ui::appendText)
        .onSuspended(ui::showInterrupt)
        .onCompleteResponse(ui::showFinalAnswer)
        .onError(ui::showError)
        .start();
```

`onSuspended` is the final event of this stream. After it fires, no `onCompleteResponse`, `onError`, or `onToolExecuted` will follow. Recovery must create a new stream; the original stream cannot be restarted.

**Middleware observation**:

If you do not want interrupt checks scattered across business call sites, register an `IAgentMiddleware`:

```java
IAgentMiddleware bridge = new IAgentMiddleware() {
    @Override
    public void onInterruptRequested(
            int currentStep, AgentSuspendInfo suspendInfo, AgentChatContext chatContext) {
        eventBus.publish(chatContext.getMemoryId(), suspendInfo);
    }
};
```

`onInterruptRequested` is only for observation and pushing events. Actual recovery must still call `resumeInterrupt` or `resumeInterruptStream`; middleware must not execute frozen tools directly.

### 3.5 Event protocol design

The public interrupt-observation entries in the current version are:

- Non-streaming: `ChatResultState.SUSPENDED` + `AgentSuspendInfo`;
- Streaming: `TokenStream.onSuspended` / `suspendInfo()`;
- Middleware: `onInterruptRequested`, `onBeforeInterruptResume`, `onAfterInterruptResume`, `onInterruptResumeError`.

A unified event envelope and the event names `agent.interrupt.requested / responded / expired / cancelled` are the evolution direction, intended for future SSE, WebSocket, message queue, and multi-node scheduling integration. The evolved envelope will contain:

```text
eventId          Idempotency ID of the event itself
eventType        Event type
schemaVersion    Protocol version
interruptBatchId Owning batch
memoryId         Conversation identifier
executionId      Source execution
sequence         Monotonically increasing within one run
occurredAt       Occurrence time
payload          Type-specific content
```

One design rule remains: generic lifecycle events distinguish `PERMISSION_APPROVAL` and `ASK_USER` through the `type` inside the payload. The permission module may derive audit events, but the main recovery flow must not depend on a parallel state machine.

### 3.6 Recovery: validate the whole batch first, then apply

Recovery always uses one command:

```java
public final class InterruptBatchResumeCommand {
    private final String batchId;
    private final List<InterruptResponse> responses;
}
```

`resolve` validates in order:

1. A snapshot exists for `batchId`;
2. Responses contain no duplicate `interruptId`;
3. Every `interruptId` in the snapshot has exactly one response;
4. The response count matches the interrupt count; extra items outside the batch are rejected;
5. Permission items must provide an `ApprovalAction`;
6. AskUser items must provide a structured `AskUserResponse` and pass required, option, count, and length validation;
7. Only after all validation passes is the `PENDING -> RESUMING` state claim performed.

Only when validation and the state claim both succeed are results applied in the original Tool Call order:

```text
for each frozen toolRequest in original order:
    Direct denial -> write a denied Tool Result
    ASK_USER  -> validate answers -> write the ask_user Tool Result
    REJECT    -> write a user-rejection Tool Result
    APPROVE   -> execute the frozen original Tool Call -> write the real result
    ALLOW     -> execute the frozen original Tool Call -> write the real result

Write all results back to ChatMemory
batch -> COMPLETED
Generate a new executionId and continue to the next think round
```

Recovery does not append "user approved" or an AskUser answer as an ordinary UserMessage. It must write the Tool Result for the corresponding `toolCallId` so the Function Call message chain stays closed.

The resumed execution uses a new `executionId`; the original execution remains only as `sourceExecutionId`, and the old execution is never revived.

### 3.7 Synchronous semantics of streaming recovery

```java
TokenStream stream = agent.resumeInterruptStream(command);
stream.onPartialResponse(ui::appendText)
      .onSuspended(ui::showInterrupt)
      .onCompleteResponse(ui::showFinalAnswer)
      .onError(ui::showError)
      .start();
```

Note: `resumeInterruptStream` synchronously applies the frozen batch and executes approved Tool Calls during the method call, then returns a streaming handle for the subsequent ReAct round. Tool execution for the frozen batch does not go through that `TokenStream`'s `onToolExecuted` callback.

### 3.8 Concurrency, double clicks, and idempotency boundaries

The current batch state machine prevents concurrent recovery, but callers should still prevent duplicate UI submissions:

| Scenario | Current behavior | Caller handling |
| --- | --- | --- |
| Missing, duplicate, or unknown `interruptId` | Throws `IllegalArgumentException`; batch remains `PENDING` | Correct the form and resubmit |
| Invalid AskUser answer | Throws `IllegalArgumentException`; batch remains `PENDING` | Mark field errors and resubmit |
| Two pages resume at the same time | The later request sees `RESUMING` / `COMPLETED` and throws `IllegalStateException` | Refresh state; do not submit again |
| Retry after the same batch completed | Throws `IllegalStateException` | Treat as handled; query the result instead of executing again |
| Tool error during recovery application | Batch remains `RESUMING` | Record the failure and intervene manually; do not blindly retry with a new request |

The first release has no `idempotencyKey` or resume-result replay. If recovery times out and the result cannot be confirmed, callers should query execution or batch status instead of retrying directly.

### 3.9 AskUser protocol details

`AskUserTool` is the model-visible structured interaction tool, with the fixed tool name `ask_user`. It only exposes the parameter schema to the model; it does not block and wait:

```text
LLM generates an ask_user Tool Call
      │
      ▼
PermissionInterruptModule.preflight identifies tool_name = ask_user
      │
      ▼
AskUserSupport.parse validates the question structure
      │
      ▼
Create an ASK_USER interrupt and freeze the batch
      │
      ▼
SUSPENDED
```

Question structure constraints:

- At most 10 questions per call;
- `id` is unique and stable within a single call;
- `title` is required and at most 200 characters;
- Choice questions must provide 2–20 options, and option `id` must be unique;
- `required` defaults to required;
- Text is at most 4000 characters;
- Single choice submits either a preset option or custom content, never both;
- Multiple choice may submit multiple options plus supplementary content, constrained by `minSelections / maxSelections`.

Recovery uses structured answers:

```java
AskUserResponse answer =
        AskUserResponse.of(
                AskUserAnswer.single("env", "prod"),
                AskUserAnswer.multiple("modules", Arrays.asList("api", "web")),
                AskUserAnswer.text("note", "Canary release first"));

InterruptResponse response =
        InterruptResponse.askUser(interrupt.getInterruptId(), answer);
```

Core re-validates the answer and generates a stable tool result in question order:

```json
{
  "status": "answered",
  "interactionId": "int_xxx",
  "answers": [
    {
      "questionId": "env",
      "selectedOptionIds": ["prod"],
      "customValue": null
    }
  ]
}
```

In the next round, the model sees the `ask_user` Tool Result, not an ordinary user message detached from the Tool Call context.

## 4. Quick integration

### 4.1 Dependency

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.1-SNAPSHOT</version>
</dependency>
```

Permission, interrupt, AskUser, and middleware callback contracts all live in `agentforge-agent-core`.

### 4.2 Assemble the module

```java
PermissionInterruptModule module =
        PermissionInterruptModule.safeDefaults()
                .enableAskUser()
                .allow("(read_file|list_files|search_files)", ".*")
                .ask("(write_file|edit_file|delete_file|shell)", ".*")
                .deny("shell", "(?i).*(rm\\s+-rf|git\\s+push\\s+--force).*")
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .agentName("workspace-agent")
                .systemPrompt("Use ask_user when essential information is missing; never fabricate user answers.")
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .toolService(toolService)
                .permissionInterruptModule(module)
                .build();
```

Rule order matters: specific `deny` rules must come before broad `ask` / `allow` rules because the first matching rule wins.

### 4.3 Non-streaming: run, suspend, resume

```java
AgentRequest request = AgentRequest.builder()
        .memoryId("workspace:1:conversation:1")
        .executionId(UUID.randomUUID().toString())
        .question(userMessage)
        .build();

ChatResult result = agent.run(request);

while (result.getChatResultState() == ChatResultState.SUSPENDED) {
    AgentSuspendInfo suspendInfo = result.getSuspendInfo();

    // The UI collects complete responses from suspendInfo.getInterrupts()
    List<InterruptResponse> responses = ui.collect(suspendInfo);

    result = agent.resumeInterrupt(
            new InterruptBatchResumeCommand(suspendInfo.getBatchId(), responses));
}

ui.showFinalAnswer(result.getRes());
```

`ui.collect` must cover every interrupt item in the batch:

```java
List<InterruptResponse> responses = new ArrayList<InterruptResponse>();

for (InterruptRequest item : suspendInfo.getInterrupts()) {
    if (item.getType() == InterruptType.PERMISSION_APPROVAL) {
        ApprovalAction action = ui.readApproval(item.getInterruptId());
        responses.add(new InterruptResponse(item.getInterruptId(), action, null));
    } else if (item.getType() == InterruptType.ASK_USER) {
        AskUserResponse answer = ui.readAskUserAnswer(item.getInterruptId());
        responses.add(InterruptResponse.askUser(item.getInterruptId(), answer));
    }
}
```

A new ReAct step may produce another batch of interrupts after recovery, so the resumed result must also continue to be checked for `SUSPENDED`.

### 4.4 Streaming: resume with a new stream

```java
agent.runStream(request)
        .onPartialResponse(ui::appendText)
        .onPartialThinking(ui::showThinking)
        .onSuspended(ui::showInterrupt)
        .onCompleteResponse(ui::showFinalAnswer)
        .onError(ui::showError)
        .start();
```

After the user submits:

```java
agent.resumeInterruptStream(
                new InterruptBatchResumeCommand(suspendInfo.getBatchId(), responses))
        .onPartialResponse(ui::appendText)
        .onSuspended(ui::showInterrupt)
        .onCompleteResponse(ui::showFinalAnswer)
        .onError(ui::showError)
        .start();
```

Streaming and non-streaming share the same `PermissionInterruptModule`, `AgentSuspendInfo`, and resume command. Do not build a separate approval protocol for streaming.

### 4.5 AskUser UI mapping

| Question type | UI control | Response construction |
| --- | --- | --- |
| `SINGLE_CHOICE` | Radio buttons / select, with a default "custom answer" input | Preset `optionId` or `customValue`, never both |
| `MULTIPLE_CHOICE` | Checkboxes, with a default "supplementary answer" input | Multiple `optionId` values, optionally with `customValue` |
| `TEXT` | Single-line or multiline input | `AskUserAnswer.text(questionId, text)` |

Always send back option IDs, not the labels shown in the UI. Multi-question scenarios use one question per page; switching questions only modifies the frontend draft and does not consume the batch. Submit the whole batch after all questions are complete.

### 4.6 Lifecycle

Recovery must find the same module instance, the same `AgentInterruptStore`, the same `ChatMemoryProvider`, and the same set of tool instances that created the interrupt. Reuse `ReActAgent` per Workspace or conversation; do not create an empty Agent for each recovery.

```text
WorkspaceRuntime
  ├── ToolService
  ├── PermissionInterruptModule
  ├── ReActAgent
  └── ChatMemoryProvider
```

`memoryId` represents a stable conversation, and `executionId` represents one execution. Recovery generates a new `executionId`; do not reuse the old value.

## 5. Custom integration scenarios

### 5.1 Custom rule types: understand structured arguments

When regex cannot express business risk, add a rule type and handler. For example, block only specific Shell commands:

```java
public final class ShellCommandRule implements PermissionRule {

    private final String id;
    private final String command;
    private final PermissionDecision decision;

    public ShellCommandRule(String id, String command, PermissionDecision decision) {
        this.id = id;
        this.command = command;
        this.decision = decision;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return "shell_command";
    }

    @Override
    public PermissionDecision getDecision() {
        return decision;
    }

    public boolean matches(ToolExecutionRequest request) {
        return "shell".equals(request.name()) && request.arguments().contains(command);
    }
}
```

```java
public final class ShellCommandRuleHandler implements PermissionRuleHandler {

    @Override
    public String type() {
        return "shell_command";
    }

    @Override
    public boolean matches(PermissionRule rule, PermissionContext context) {
        return rule instanceof ShellCommandRule
                && ((ShellCommandRule) rule).matches(context.getRequest());
    }
}
```

Registration:

```java
PermissionInterruptModule module =
        PermissionInterruptModule.safeDefaults()
                .ruleHandler(new ShellCommandRuleHandler())
                .rule(new ShellCommandRule(
                        "rule-shell-rm", "rm -rf", PermissionDecision.DENY))
                .build();
```

Construction validates that every rule type has a registered handler, that handlers are not duplicated, and that decisions are not null. Unregistered types are never silently ignored.

### 5.2 Custom mode policy: scenario-level allow rules

When the built-in modes are not expressive enough, implement `PermissionModePolicy`. For example, "allow reads, require approval for writes, explicit DENY cannot be bypassed":

```java
public final class ReviewWritesPolicy implements PermissionModePolicy {

    @Override
    public PermissionDecision decide(PermissionContext context) {
        return PermissionDecision.ASK;
    }

    @Override
    public PermissionDecision decide(
            PermissionContext context, PermissionDecision candidate) {
        if (candidate == PermissionDecision.DENY) {
            return PermissionDecision.DENY;
        }
        if (context.getRequest().name().startsWith("write_")) {
            return PermissionDecision.ASK;
        }
        return candidate == null ? PermissionDecision.ALLOW : candidate;
    }
}
```

```java
PermissionInterruptModule module =
        PermissionInterruptModule.builder()
                .permissionModePolicy(new ReviewWritesPolicy())
                .build();
```

After a custom policy is injected, it fully takes over the final decision; the built-in `DEFAULT / BYPASS / DONT_ASK` no longer participate. Therefore the policy itself must explicitly preserve the priority of `DENY`.

### 5.3 Custom approval store: take control of approval records

Permission approval still has only three operations:

| `ApprovalAction` | Current frozen Tool Call | Default behavior for later calls |
| --- | --- | --- |
| `APPROVE_ONCE` | Execute | Do not record a grant; evaluate again next time |
| `APPROVE_ALWAYS` | Execute | Record a conversation grant; allow an identical call in the same conversation |
| `REJECT` | Do not execute; write a rejection Tool Result | Do not record a grant |

`PermissionApprovalStore` abstracts both checking an existing grant and processing the current approval response:

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

The two methods run at different points:

```text
Permission preflight for an initial or resumed execution
  -> isApproved(chatContext, toolRequest)
  -> grant found: ALLOW directly
  -> no grant: continue through PermissionEngine

The user submits an interrupt response
  -> resolve(command) validates and claims the batch
  -> ReActAgent creates the recovery AgentChatContext
  -> addApproval(chatContext, frozenToolRequest, actualAction)
  -> Core executes the frozen call or writes a rejection result
```

`isApproved` does not take an `ApprovalAction` because the current approval has not happened during preflight. It queries grants previously created by `APPROVE_ALWAYS`. `addApproval` receives the user's actual action, so a custom implementation can audit all three actions, but only `APPROVE_ALWAYS` should create a grant that later calls can match.

The default `InMemorySessionPermissionApprovalStore` uses:

```text
memoryId -> Set<toolName + rawArguments>
```

The same `PermissionInterruptModule` can therefore serve multiple conversations without allowing conversation B because conversation A selected “always allow.” The default store is process-local and loses grants on restart.

To persist grants or integrate a business approval service, provide a custom store:

```java
public final class JdbcPermissionApprovalStore
        implements PermissionApprovalStore {

    private final PermissionApprovalRepository repository;

    @Override
    public boolean isApproved(
            AgentChatContext context,
            ToolExecutionRequest request) {
        return repository.exists(
                context.getMemoryId(),
                request.name(),
                request.arguments());
    }

    @Override
    public void addApproval(
            AgentChatContext context,
            ToolExecutionRequest request,
            ApprovalAction action) {
        repository.saveAudit(
                context.getMemoryId(), request.id(), action.name());

        if (action != ApprovalAction.APPROVE_ALWAYS) {
            return;
        }

        repository.save(
                context.getMemoryId(),
                request.name(),
                request.arguments());
    }
}
```

Inject it through the builder:

```java
PermissionInterruptModule module =
        PermissionInterruptModule.safeDefaults()
                .ask("shell", ".*")
                .permissionApprovalStore(
                        new JdbcPermissionApprovalStore(repository))
                .build();
```

A custom implementation receives the `AgentChatContext` for the relevant execution phase and can read `memoryId`, `executionId`, `AgentRequest`, and business `extensions`. Keep these boundaries:

- Obtain the conversation identity from the server-side `chatContext`, never from a client-asserted response field;
- Recovery passes the original frozen Tool Call from the snapshot; a custom store must not mutate it before execution;
- `APPROVE_ONCE` and `REJECT` may be audited but must not be expanded into persistent allow grants;
- Exceptions from `isApproved` or `addApproval` fail closed; the framework does not silently allow the call or downgrade `APPROVE_ALWAYS` to `APPROVE_ONCE`;
- Streaming and non-streaming recovery share `applyInterruptBatch`, so both go through the same store.

### 5.4 Custom interrupt store: cross-process and cluster recovery

The default `InMemoryAgentInterruptStore` only works in a single process. To recover across restarts or nodes, implement `AgentInterruptStore`:

```java
public interface AgentInterruptStore {
    void save(AgentInterruptSnapshot snapshot);
    AgentInterruptSnapshot get(String batchId);
    void claim(String batchId);
    void complete(String batchId);
}
```

The core of a database implementation is atomic state advancement:

```sql
UPDATE agent_interrupt_batch
SET status = 'RESUMING'
WHERE batch_id = ?
  AND status = 'PENDING';
```

When the affected row count is 0, it must throw a conflict and must not continue applying tools. `save` should use a unique constraint on `batchId`, and `complete` marks the batch `COMPLETED`.

```java
PermissionInterruptModule module =
        PermissionInterruptModule.builder()
                .interruptStore(new JdbcAgentInterruptStore(dataSource))
                .build();
```

Replacing the store must not change state-machine semantics; in particular, two resume requests must never both move `PENDING` to `RESUMING`. Cross-process recovery also requires ChatMemory to be persisted synchronously.

### 5.5 Tool-layer boundary: permission rules cannot replace path validation

The permission system is one layer of defense in depth, not a replacement for a sandbox. File tools must normalize paths and check workspace boundaries before execution:

```java
Path root = workspaceRoot.toRealPath();
Path target = root.resolve(userPath).normalize();

if (!target.startsWith(root)) {
    throw new SecurityException("path escapes workspace: " + userPath);
}
```

For existing targets, also call `toRealPath()` to prevent symlink escapes. Recommended levels:

| Operation | Recommended decision | Notes |
| --- | --- | --- |
| Read, search, list directories inside the workspace | `ALLOW` | The tool must still normalize paths |
| Write, overwrite, delete, or Shell inside the workspace | `ASK` | The UI shows the frozen tool name and arguments |
| Access outside the workspace, sensitive paths, irreversible high-risk commands | `DENY` or reject in the tool layer | Do not delegate every risk to human confirmation |

### 5.6 Approval event bridging

Desktop apps, IDE plugins, or web backends can push interrupt events through middleware without intruding on business call sites:

```java
IAgentMiddleware bridge = new IAgentMiddleware() {

    @Override
    public void onInterruptRequested(
            int currentStep, AgentSuspendInfo suspendInfo, AgentChatContext chatContext) {
        eventBus.publishInterrupt(
                chatContext.getMemoryId(), currentStep, suspendInfo);
    }

    @Override
    public void onBeforeInterruptResume(
            InterruptResumeContext resumeContext, AgentChatContext chatContext) {
        auditLog.recordResume(resumeContext.getSnapshot(), resumeContext.getResponses());
    }

    @Override
    public void onInterruptResumeError(
            InterruptResumeContext resumeContext,
            Throwable error,
            AgentChatContext chatContext) {
        alertService.notifyResumeFailure(resumeContext.getSnapshot().getBatchId(), error);
    }
};
```

```java
ReActAgent agent =
        ReActAgent.builder()
                .middleware(bridge)
                .permissionInterruptModule(module)
                // other configuration
                .build();
```

Middleware only observes, audits, and pushes events. It must not modify the frozen Tool Call or replace `resumeInterrupt`.

## 6. Boundaries and best practices

### 6.1 Mode selection

| Scenario | Recommended mode | Reason |
| --- | --- | --- |
| Desktop coding assistant, IDE plugin | `DEFAULT` | Someone is present to handle approvals; configure `ask` per tool |
| Trusted sandbox, rollback-friendly test environment | `BYPASS` | Keep only explicit `DENY` red lines |
| Nightly inspection, CI, unattended tasks | `DONT_ASK` | Allowlist only; no waiting is permitted |

Expecting `DEFAULT` to "ask automatically when no rule matches" is a common anti-pattern. Unmatched rules are allowed directly, so sensitive tools must explicitly configure `ask`. Configuring many `ask` rules under `DONT_ASK` is equally dangerous: they all become `DENY`, so the rule intent and runtime result diverge.

### 6.2 Rule configuration checklist

1. Are `deny` rules placed before broader `ask` / `allow` rules?
2. Do sensitive tools explicitly configure `ask` or `deny`?
3. Do regexes match only the tool name and raw JSON, rather than relying on path-string checks?
4. Does every custom rule type have a registered handler?
5. Does a custom `PermissionModePolicy` preserve explicit `DENY`?

### 6.3 Interrupt and recovery checklist

1. Is `SUSPENDED` treated as a normal waiting state rather than a failure?
2. Does the recovery response cover every `interruptId` in the batch with no missing, duplicate, or extra items?
3. Does AskUser use option IDs and structured `AskUserResponse`?
4. Are buttons disabled after the user submits to prevent duplicate recovery?
5. Does the resumed execution continue with a new `executionId`?
6. After a streaming suspension, is a new stream created rather than restarting the original?
7. Are the module and store reused per Workspace / conversation?

### 6.4 Security red lines

- Permission engine errors must not allow tools; exceptions should propagate and terminate the current execution;
- Both `DENY` and `REJECT` must write a Tool Result to close the message chain, but must not execute the tool;
- `APPROVE_ALWAYS` must not be used for dangerous paths, destructive commands, or other high-risk operations; the default implementation is process-local and isolates grants by `memoryId`;
- The UI must not send back or rewrite Tool arguments; recovery always uses the frozen original call;
- Tools must keep their own argument validation and workspace boundary checks; permission approval cannot replace sandboxes or business authorization.

## Next

- Learn about the Agent main loop and streaming entries: [Agent](./agent)
- Learn how interrupt events cooperate with middleware: [Middleware Design](./middleware)
- Learn tool declarations and executor contracts: [Tool Extension Interface Design](./tool)
