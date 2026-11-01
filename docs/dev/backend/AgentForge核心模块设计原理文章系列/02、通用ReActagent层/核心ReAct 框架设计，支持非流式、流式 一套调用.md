# AgentForge ReAct 层01、核心 ReAct 框架设计：非流式与流式共用一套调用内核

> 适用版本：release_1.x
> 适用模块：`agentforge-framework/agentforge-agent-core`
> 包前缀：`cloud.changlu.agentforge.agent`
> 维护者：长路

系列定位：本文属于「AgentForge 核心模块设计原理文章系列 / 02、通用 ReAct Agent 层」，是 ReAct 内核的第 01 篇。全文只回答两个问题：

1. ReAct 的「思考 → 行动 → 再思考」循环，如何被抽象成一套稳定的模板方法；
2. 非流式 `run()` 与流式 `runStream()`，如何共用同一套上下文、记忆、工具执行、中间件和取消机制。

一句话结论：

> **`Agent.run()` 是同步驱动器，`ReActTokenStream` 是异步驱动器；两者驱动的是同一个 `step → think → act` 内核。**


<br/>

## 文档导航

| 文档 | 说明 |
|---|---|
| 当前文章（ReAct 原理01） | 分层结构、非流式主循环、流式回调循环、状态机与错误收敛 |
| [middleware/标准Agent的middleware接口扩展实现.md](middleware/%E6%A0%87%E5%87%86Agent%E7%9A%84middleware%E6%8E%A5%E5%8F%A3%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0.md) | 第 02 篇：中间件契约、分发器、流式事件与非流式回调的统一接入 |
| [middleware/内置middleware扩展：日志Loggingmiddlware实现.md](middleware/%E5%86%85%E7%BD%AEmiddleware%E6%89%A9%E5%B1%95%EF%BC%9A%E6%97%A5%E5%BF%97Loggingmiddlware%E5%AE%9E%E7%8E%B0.md) | 第 03 篇：日志中间件、会话级统计、耗时与 token 汇总 |

<br/>

## 目录

- [一、背景：为什么非流式和流式最容易写成两套代码](#一背景为什么非流式和流式最容易写成两套代码)
- [二、分层设计：五个类，一条继承链](#二分分层设计五个类一条继承链)
- [三、非流式主循环：`run()` 的模板方法](#三非流式主循环run-的模板方法)
- [四、`think` 与 `act`：ReAct 的两个原子动作](#四think-与-actreact-的两个原子动作)
- [五、流式主循环：`ReActTokenStream` 的递归驱动](#五流式主循环reacttokenstream-的递归驱动)
- [六、“一套调用”：共用什么，差异在哪](#六一套调用共用什么差异在哪)
- [七、状态机：StepResult 与 AgentRunState 的分工](#七状态机stepresult-与-agentrunstate-的分工)
- [八、完整用法：同一个 Agent，两种消费方式](#八完整用法同一个-agent两种消费方式)
- [九、事件顺序：测试如何证明两条路径一致](#九事件顺序测试如何证明两条路径一致)
- [十、边界与设计取舍](#十边界与设计取舍)
- [十一、总结](#十一总结)

---

## 一、背景：为什么非流式和流式最容易写成两套代码

在接入大模型时，非流式和流式看起来只是「要不要逐字返回」的差别，但一旦落进 ReAct 循环，很容易演化成两套完全独立的代码：

```text
非流式：while (step <= maxSteps) { think(); if (toolCalls) act(); else break; }
流式  ：model.chat(request, handler);   // 回调里再判断工具
        handler.onComplete -> 执行工具 -> 再发起下一轮 chat
```

两条路径各自维护一份状态机之后，问题会接踵而来：

- **行为漂移**：非流式支持最大步数、取消、重试，流式可能漏掉其中一个；
- **工具执行重复**：两套代码都要处理「查执行器、执行、把结果写回记忆、并发/串行」；
- **中间件割裂**：日志、Trace、脱敏在一个路径生效，另一个路径失效；
- **测试成本翻倍**：同一段 ReAct 语义要写两组断言。

AgentForge 的选择是：**把循环骨架和领域对象收口到父类，把「怎么发起一次模型调用」下沉为两种驱动器。**

<br/>

## 二、分层设计：五个类，一条继承链

核心类全部位于 `cloud.changlu.agentforge.agent` 包：

```text
IAgent                      接口：run / runStream / cancel / clearContext
  ▲
BaseAgent                   会话上下文、用户消息、取消标志
  ▲
Agent                       模板方法：同步主循环 + 流式入口；声明 step / stepStream
  ▲
BaseReActAgent              ReAct 单步：check cancel -> think -> act；构造 ReActTokenStream
  ▲
ReActAgent                  具体实现：模型、工具、记忆、系统提示词、中间件装配
```

对应职责如下：

| 类 | 关键职责 | 关键代码 |
|---|---|---|
| `IAgent` | 对外统一契约，屏蔽执行方式 | `IAgent.java:12` |
| `BaseAgent` | `AgentChatContext` 初始化、用户消息写入、按 `memoryId` 维护取消标志 | `BaseAgent.java:33`、`BaseAgent.java:109` |
| `Agent` | 非流式 `run()` 模板方法；流式 `runStream()` 入口；参数校验 | `Agent.java:44`、`Agent.java:152` |
| `BaseReActAgent` | 定义 `think` / `act` 抽象方法；实现单步 `step()`；构造 `ReActTokenStream` | `BaseReActAgent.java:34`、`BaseReActAgent.java:40` |
| `ReActAgent` | 组装 `ChatModel` / `StreamingChatModel` / `ToolService` / `ChatMemoryProvider` / `AgentMiddlewareManager` | `ReActAgent.java:55` |

支撑这套内核的领域对象：

- `AgentChatContext`：一次运行的请求、记忆、模型、扩展字段；
- `ThinkResult`：本轮思考结论（是否结束、是否需要行动、要执行哪些工具）；
- `StepResult`：一轮 `think + act` 之后的步状态；
- `AgentSettings`：`maxSteps`、重试次数、重试退避、是否并发执行工具；
- `TokenStream` / `ReActTokenStream`：流式执行的句柄与实现；
- `AgentToolExecutor`：非流式与流式共用的工具批次执行器。

这套分层的核心思想是 **模板方法 + 策略下沉**：

- `Agent.run()` 固定「准备上下文 → 循环 → 收敛状态 → 触发停止回调」；
- `BaseReActAgent.step()` 固定「取消检查 → 思考 → 思考后取消检查 → 结束或行动」；
- 子类只需要回答三个问题：**用哪个模型、有哪些工具、本轮思考结果是什么。**

<br/>

## 三、非流式主循环：`run()` 的模板方法

### 3.1、完整骨架

`Agent.run()` 是整个非流式路径的入口（`Agent.java:44`），核心骨架如下（节选）：

```java
@Override
public ChatResult run(AgentRequest request) {
    Object memoryId = request.getMemoryId();
    String question = request.getQuestion();
    prepareForNewRun(memoryId);
    AgentChatContext chatContext = init(request);

    validateRunParams();

    AgentMiddlewareManager middlewareManager = getMiddlewareManager();
    if (middlewareManager != null) {
        middlewareManager.triggerOnInitComplete(chatContext);
    }

    int currentStep = 1;
    int maxSteps = getAgentSettings().getMaxSteps();

    String runRes = "";
    String cancelReason = null;
    boolean isFinished = false;
    AgentRunState runState = null;

    try {
        StepResult curStep = StepResult.toRunning();
        while (currentStep <= maxSteps) {
            if (middlewareManager != null) {
                middlewareManager.triggerBeforeLoop(currentStep, chatContext);
            }
            curStep = step(currentStep, chatContext);
            if (middlewareManager != null) {
                middlewareManager.triggerAfterLoop(currentStep, curStep, chatContext);
            }

            if (curStep.getState().equals(StepState.STOP)) {
                isFinished = true;
                runRes = curStep.getRes();
                runState = curStep.getRunState() == null
                        ? AgentRunState.SUCCESS
                        : curStep.getRunState();
                break;
            }
            currentStep++;
        }
    } catch (CancelException e) {
        cancelReason = "任务已被用户取消";
        runState = AgentRunState.CANCEL;
        // 触发 onLoopError
    } catch (Exception e) {
        // 线程中断按取消处理；其他异常 onLoopError + onStopWithError 后抛出 AgentException
    } finally {
        clearCancelFlag(memoryId);
    }

    // 取消 / 最大步数 / 正常结束，分别触发 onStop
    return ChatResult.toFinished(question, runRes,
            runState == null ? AgentRunState.SUCCESS : runState);
}
```

### 3.2、这一段代码在解决什么

**第一，入口先做「运行前清理」。**

`prepareForNewRun(memoryId)` 会清掉上一轮可能残留的取消标记（`BaseAgent.java:101`）。原因是 Agent 实例通常由上层缓存复用，如果同一个 `memoryId` 上一轮被取消过，`cancelFlags` 里的标记可能残留到下一轮正式启动之前。

**第二，`init(request)` 是两条路径共用的上下文工厂。**

```java
protected AgentChatContext init(AgentRequest request) {
    AgentChatContext chatContext = initParams(request);
    initMessages(chatContext);
    return chatContext;
}
```

`initParams` 做三件事（`BaseAgent.java:41`）：

1. 通过 `ChatMemoryProvider.get(memoryId)` 取会话记忆；
2. 把本次 `AgentRequest`、`ChatModel`、`ChatMemory` 装进 `AgentChatContext`；
3. 复制一份 `extensions`，让业务扩展字段只在本轮上下文中流转。

`initMessages` 则由具体 Agent 决定。`ReActAgent` 会先做一次系统提示词重建（`ReActAgent.java:120`）：

```java
@Override
protected void initMessages(AgentChatContext chatContext) {
    ChatMemory chatMemory = chatContext.getChatMemory();
    List<ChatMessage> messages = chatMemory.messages();

    List<ChatMessage> nonSystemMessages = new ArrayList<>();
    for (ChatMessage message : messages) {
        if (message.type() != ChatMessageType.SYSTEM) {
            nonSystemMessages.add(message);
        }
    }

    List<ChatMessage> newMessages = new ArrayList<>();
    if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
        newMessages.add(SystemMessage.from(systemPrompt));
    }
    newMessages.addAll(nonSystemMessages);

    chatMemory.clear();
    for (ChatMessage message : newMessages) {
        chatMemory.add(message);
    }

    super.initMessages(chatContext);  // 再写入本轮 UserMessage
}
```

这段逻辑保证：**系统提示词永远位于记忆最前面，且不会因为多轮运行重复堆积。** 用户消息由 `BaseAgent.buildUserMessage()` 统一构建，空问题会退化为「请结合上下文回答。」（`BaseAgent.java:66`）。

**第三，循环只认 `StepState.STOP`。**

每一轮调 `step(currentStep, chatContext)`，只要结果不是 `STOP`，就继续下一步。这里没有 `break` 条件分散在各处，结束语义被收敛成一个枚举判断。

**第四，异常按「取消 / 中断 / 真实错误」三分。**

- `CancelException`：用户主动取消，收敛为 `AgentRunState.CANCEL`；
- 线程中断相关异常：同样按取消处理，避免把 `InterruptedException` 抛给上层；
- 其他异常：先触发 `onLoopError` 与 `onStopWithError`，再包装成 `AgentException` 抛出。

**第五，`finally` 必须清理取消标志。**

取消标志是「一次运行内有效」的临时状态，不清理会污染下一轮。

<br/>

## 四、`think` 与 `act`：ReAct 的两个原子动作

### 4.1、`BaseReActAgent.step()`：一步的固定节奏

`BaseReActAgent` 只做编排，不关心模型和工具细节（`BaseReActAgent.java:39`）：

```java
@Override
protected StepResult step(int currentStep, AgentChatContext chatContext) {
    // 初始检查
    checkIsCancelled(chatContext);
    // 思考！
    ThinkResult think = think(currentStep, chatContext);
    // 思考后立即检查
    checkIsCancelled(chatContext);

    Boolean isFinish = think.getIsFinish();
    if (isFinish != null && isFinish) {
        String res = think.getRunRes();
        AgentRunState runState = think.getState() == null
                ? AgentRunState.SUCCESS : think.getState();
        return StepResult.toStop(res, runState);
    }

    Boolean shouldAct = think.getNeedAct() != null && think.getNeedAct();
    if (shouldAct) {
        return act(currentStep, think.getCurActTools(), chatContext);
    }

    return StepResult.builder()
            .state(StepState.RUNNING)
            .res("任务未结束，继续执行下一步，暂无行动指南")
            .build();
}
```

这段代码定义了 ReAct 的「原子节奏」：

```text
检查取消
   │
   ▼
 think（模型推理：要不要调用工具？）
   │
   ▼
检查取消
   │
   ├── isFinish = true  ──► StepResult.toStop(res, state)
   │
   ├── needAct = true   ──► act（执行工具，结果写回记忆）
   │
   └── 其他             ──► StepState.RUNNING，进入下一轮
```

注意两个细节：

1. **取消检查放在 `think` 前后各一次。** 因为一次模型调用可能耗时很久，调用前检查可以避免无意义请求，调用后检查可以避免带着已取消的上下文继续执行工具。
2. **`isFinish` 优先于 `needAct`。** 模型如果已经给出最终回答，即使同时返回了工具调用，也以结束为准。

### 4.2、`ThinkResult`：思考结论的显式表达

`ThinkResult` 把「模型说了什么」翻译成「循环该做什么」：

| 字段 | 含义 |
|---|---|
| `state` | 本轮结束时的运行态，可不填，默认 `SUCCESS` |
| `needAct` | 是否需要执行工具 |
| `isFinish` | 整个 Agent 是否结束 |
| `runRes` | 结束时返回给用户的文本 |
| `curActTools` | 本轮要执行的工具请求 |

把控制信号显式建模，而不是让 `think()` 直接操作循环变量，有两个好处：

- 单步逻辑可测试：给定 `ThinkResult` 就能断言 `step()` 的分支；
- 非流式与流式可以各自决定如何消费同一套结论。

### 4.3、`ReActAgent.think()`：一次模型调用的完整边界

`ReActAgent.think()`（`ReActAgent.java:149`）承担了非流式路径里最重的一段逻辑：

```java
protected ThinkResult think(int currentStep, AgentChatContext chatContext) {
    ChatMemory chatMemory = chatContext.getChatMemory();
    List<ChatMessage> curCallMessages = chatMemory.messages();

    int retryCount = getAgentSettings().getAiCallRetry();
    long aiCallRetryDelay = getAgentSettings().getAiCallRetryDelay();

    // 1. 当前记忆 + 全部工具声明
    ChatRequest chatRequest = ChatRequest.builder()
            .messages(curCallMessages)
            .parameters(DefaultChatRequestParameters.builder()
                    .tools(new ArrayList<>(getToolSpecifications()))
                    .build())
            .build();

    // 2. 模型调用前中间件（返回 null 表示中断）
    ChatRequest processedRequest =
            middlewareManager.triggerBeforeModelCall(currentStep, chatRequest, chatContext);
    if (processedRequest == null) {
        return ThinkResult.builder()
                .isFinish(true)
                .needAct(false)
                .runRes("模型调用被中间件中断")
                .curActTools(Collections.emptyList())
                .build();
    }

    // 3. 真实模型调用 + 指数退避重试
    ChatResponse chatResponse;
    try {
        chatResponse = AiCallRetrySupport.execute(
                () -> getChatModel().chat(processedRequest),
                retryCount,
                aiCallRetryDelay,
                (attempt, maxRetries, lastException, delayMs) ->
                        middlewareManager.triggerOnAiCallRetry(
                                currentStep, processedRequest, chatContext,
                                attempt, maxRetries, delayMs, lastException));
    } catch (Exception e) {
        middlewareManager.triggerOnModelCallError(
                currentStep, processedRequest, e, chatContext);
        return ThinkResult.builder()
                .state(AgentRunState.MODEL_CALL_ERROR)
                .isFinish(true)
                .needAct(false)
                .runRes("模型调用失败，请解决请求异常: " + describeError(e))
                .curActTools(Collections.emptyList())
                .build();
    }

    // 4. 模型调用后中间件
    chatResponse = middlewareManager.triggerAfterModelCall(
            currentStep, processedRequest, chatResponse, chatContext);

    AiMessage aiMessage = chatResponse.aiMessage();
    addMessage(chatContext, aiMessage);

    List<ToolExecutionRequest> curActTools = new ArrayList<>();
    boolean isFinish = aiMessage.toolExecutionRequests().isEmpty();
    String runRes = "";
    if (isFinish) {
        runRes = aiMessage.text();
    } else {
        curActTools = aiMessage.toolExecutionRequests();
    }

    return ThinkResult.builder()
            .needAct(!curActTools.isEmpty())
            .isFinish(isFinish)
            .runRes(runRes)
            .curActTools(curActTools)
            .build();
}
```

这里有四个设计点值得强调：

1. **工具声明每轮都随请求带上。** 模型需要知道当前有哪些工具可用，才能决定是否发起 Function Calling；
2. **中间件可以中断模型调用。** `beforeModelCall` 返回 `null` 时，本轮直接以 `isFinish = true` 结束，不再消耗步长；
3. **模型调用失败不抛异常，而是收敛成 `MODEL_CALL_ERROR`。** 这样 `run()` 仍能走正常的停止回调与结果返回；
4. **`AiMessage` 在写回记忆后才构造 `ThinkResult`。** 因为下一轮请求依赖记忆里的工具调用请求与工具结果。

### 4.4、`act`：只负责执行，不负责决定下一轮

`ReActAgent.act()` 很短（`ReActAgent.java:252`）：

```java
@Override
protected StepResult act(
        int currentStep, List<ToolExecutionRequest> curActTools, AgentChatContext chatContext) {
    List<ToolExecutionResultMessage> toolMessages = getToolExecutor().execute(
            currentStep,
            curActTools,
            chatContext,
            null,
            getMiddlewareManager(),
            getAgentSettings().isEnableConcurrentToolExecution());
    return StepResult.toFinished(toolMessages.toString());
}
```

它把工具批次交给 `AgentToolExecutor`，然后返回 `FINISHED`，让外层循环继续下一轮 `think`。工具结果已经由执行器写回 `ChatMemory`，所以下一轮的模型请求自然能看到。

`AgentToolExecutor` 是非流式与流式共用的关键组件（`AgentToolExecutor.java:91`）：

```java
if (enableConcurrent
        && toolRequests != null
        && toolRequests.size() > 1
        && chatContext != null) {
    return executeConcurrently(...);
}
return executeSequentially(...);
```

并发执行时最多使用 8 个线程（`MAX_CONCURRENT_TOOLS`），最终**按请求顺序**把结果写回记忆，避免模型看到乱序的工具结果。这个「统一执行器」正是两条路径行为一致的基础。

<br/>

## 五、流式主循环：`ReActTokenStream` 的递归驱动

### 5.1、`runStream()` 是懒启动

`Agent.runStream()` 本身很轻（`Agent.java:151`）：

```java
@Override
public TokenStream runStream(AgentRequest request) {
    prepareForNewRun(request.getMemoryId());
    AgentChatContext chatContext = init(request);

    validateRunStreamParams();
    return stepStream(chatContext);
}
```

它只做三件事：清理取消标志、初始化上下文、返回一个 `TokenStream` 句柄。**真正的模型调用要等到调用方注册完回调并执行 `start()` 才开始。** 这给了上层一个稳定的窗口去挂载 `onPartialResponse`、`onToolExecuted` 等监听器。

`stepStream()` 由 `BaseReActAgent` 实现，直接构造 `ReActTokenStream`（`BaseReActAgent.java:65`）：

```java
@Override
protected TokenStream stepStream(AgentChatContext chatContext) {
    return new ReActTokenStream(
            chatContext,
            getStreamChatModel(),
            getToolExecutor(),
            getToolSpecifications(),
            getAgentSettings(),
            cancelFlags,
            getMiddlewareManager());
}
```

注意它把 `cancelFlags` 直接传了进去。这意味着**流式和非流式共享同一张取消标志表**，上层调用 `agent.cancel(memoryId)` 时两条路径行为一致。

### 5.2、`start()`：只允许启动一次

```java
@Override
public void start() {
    if (!started.compareAndSet(false, true)) {
        throw new AgentException("TokenStream已经启动，不能重复start");
    }
    if (middlewareManager != null) {
        middlewareManager.triggerOnInitComplete(chatContext);
    }
    try {
        chat(1);
    } catch (RuntimeException e) {
        handleError(1, e);
    }
}
```

`AtomicBoolean` 保证一个流只能启动一次；`triggerOnInitComplete` 保证流式路径也只触发一次初始化回调，与非流式对齐。

### 5.3、`chat(currentStep)`：一轮 = 一次流式请求

流式循环的核心是 `chat(int currentStep)`（`ReActTokenStream.java:159`）：

```java
private void chat(final int currentStep) {
    if (isCancelled()) {
        handleCancel(currentStep, String.format(
                "会话：%s 在第%d步被取消", chatContext.getMemoryId(), currentStep));
        return;
    }

    int maxSteps = agentSettings.getMaxSteps();
    if (currentStep > maxSteps) {
        handleError(currentStep, new AgentException(
                MAX_STEPS_PREFIX + "，请重新询问你的问题！"));
        return;
    }

    ChatRequest chatRequest = ChatRequest.builder()
            .messages(chatContext.getChatMemory().messages())
            .parameters(parameters)
            .build();

    ChatRequest processedRequest = chatRequest;
    if (middlewareManager != null) {
        middlewareManager.triggerBeforeLoop(currentStep, chatContext);
        processedRequest = middlewareManager.triggerBeforeModelCall(
                currentStep, chatRequest, chatContext);
        if (processedRequest == null) {
            notifyStop(currentStep, StopResultState.CANCEL,
                    MODEL_CALL_INTERRUPTED, new AgentException(MODEL_CALL_INTERRUPTED));
            notifyError(new AgentException(MODEL_CALL_INTERRUPTED));
            return;
        }
    }
    currentChatRequest = processedRequest;

    streamingChatModel.chat(processedRequest,
            new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    // 1. 先分发中间件，再分发业务回调
                    ...
                }

                @Override
                public void onPartialThinking(String partialThinking) {
                    PartialThinking thinking = new PartialThinking(partialThinking);
                    ...
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    onRoundComplete(currentStep, completeResponse);
                }

                @Override
                public void onError(Throwable error) {
                    handleError(currentStep, error);
                }
            });
}
```

关键差异在这里：**非流式用 `while` 推进轮次，流式用「`onCompleteResponse` 回调里递归调用 `chat(step + 1)`」推进轮次。**

```text
chat(1)
  ├── model.chat(...)
  ├── onPartialResponse / onPartialThinking  （逐 token）
  ├── onCompleteResponse
  │      ├── 无工具调用 -> 完成
  │      └── 有工具调用 -> execute tools -> chat(2)
  └── onError -> 错误收敛
```

递归的是「轮」，不是「token」。token 级增量通过 `onPartialResponse` / `onPartialThinking` 推送，轮次级推进才用递归。

### 5.4、`onRoundComplete()`：流式的 `act` + 下一轮

`onRoundComplete()`（`ReActTokenStream.java:239`）承担了非流式里 `step()` + `act()` 的职责：

```java
private void onRoundComplete(int currentStep, ChatResponse response) {
    if (middlewareManager != null) {
        response = middlewareManager.triggerAfterModelCall(
                currentStep, currentChatRequest, response, chatContext);
    }

    AiMessage aiMessage = response.aiMessage();
    chatContext.getChatMemory().add(aiMessage);

    if (isCancelled()) {
        handleCancel(currentStep, String.format(
                "会话：%s 流式执行被取消", chatContext.getMemoryId()));
        return;
    }

    // 没有工具调用：本轮就是最终回答
    if (!aiMessage.hasToolExecutionRequests()) {
        if (middlewareManager != null) {
            middlewareManager.triggerAfterLoop(
                    currentStep, StepResult.toStop(aiMessage.text()), chatContext);
            middlewareManager.triggerOnStop(
                    currentStep,
                    stopResult(StopResultState.NORMAL, aiMessage.text()),
                    chatContext);
        }
        handleComplete(response);
        return;
    }

    // 有工具调用：执行 act，并把结果写回记忆
    toolExecutor.execute(
            currentStep,
            aiMessage.toolExecutionRequests(),
            chatContext,
            toolExecutedHandler,
            middlewareManager,
            agentSettings != null && agentSettings.isEnableConcurrentToolExecution());

    if (middlewareManager != null) {
        middlewareManager.triggerOnIntermediateResponse(currentStep, response, chatContext);
    }
    if (intermediateResponseHandler != null) {
        intermediateResponseHandler.accept(response);
    }
    if (middlewareManager != null) {
        // 与非流式循环保持一致：afterLoop 在本轮工具执行完成后触发
        middlewareManager.triggerAfterLoop(currentStep, StepResult.toFinished(""), chatContext);
    }

    chat(currentStep + 1);
}
```

这段代码把流式的「一轮」拆成四件事：

1. **`afterModelCall`**：允许中间件改写最终响应；
2. **写回 `AiMessage`**：无论有没有工具调用，模型的这一轮输出都要进入记忆；
3. **无工具调用 → 结束**：触发 `afterLoop(STOP)`、`onStop(NORMAL)`、`onCompleteResponse`；
4. **有工具调用 → 执行工具 → 中间响应 → `afterLoop(FINISHED)` → `chat(step + 1)`**。

其中 `onIntermediateResponse` 是流式独有的：它携带本轮「带工具调用的模型响应」，让前端可以在工具执行完成后、下一轮思考前渲染工具调用卡片。

### 5.5、流式的错误与取消收敛

流式没有 `ChatResult` 返回值，因此错误必须通过回调送出：

```java
private void handleError(int currentStep, Throwable error) {
    if (middlewareManager != null) {
        middlewareManager.triggerOnLoopError(currentStep, error, chatContext);
        if (isMaxStepsError(error)) {
            middlewareManager.triggerOnStop(currentStep,
                    stopResult(StopResultState.MAX_STEPS, error.getMessage()), chatContext);
        } else if (error instanceof CancelException) {
            middlewareManager.triggerOnStop(currentStep,
                    stopResult(StopResultState.CANCEL, error.getMessage()), chatContext);
        } else {
            middlewareManager.triggerOnStopWithError(currentStep, error, chatContext);
        }
    }
    notifyError(error);
}
```

`notifyError` 的优先级是：

```text
有 onError 回调 -> 交给回调
无回调但 ignoreErrors -> 静默
都没有 -> 抛 AgentException
```

取消则走 `handleCancel()`：先统一触发 `onLoopError + onStop(CANCEL)`，再通过 `notifyError` 把 `CancelException` 交给上层。这样中间件和业务回调都能收到取消信号，且不会重复触发停止回调。

<br/>

## 六、“一套调用”：共用什么，差异在哪

### 6.1、共用清单

| 能力 | 共用实现 | 说明 |
|---|---|---|
| 上下文初始化 | `BaseAgent.init()` | 同一个 `AgentChatContext` 结构 |
| 会话记忆 | `ChatMemoryProvider` + `ChatMemory` | 同一个 `memoryId` 复用同一份记忆 |
| 系统提示词 | `ReActAgent.initMessages()` | 两条路径都会重建并前置系统消息 |
| 工具执行 | `AgentToolExecutor` | 串行/并发、结果写回、中间件回调全部统一 |
| 中间件 | `AgentMiddlewareManager` | 同一套回调、同一条注册顺序 |
| 最大步数 | `AgentSettings.maxSteps` | 同一份配置 |
| 取消 | `BaseAgent.cancelFlags` | 同一个 `cancel(memoryId)` |
| 结束态 | `AgentRunState` / `StopResultState` | 同一套语义 |

### 6.2、差异清单

| 维度 | 非流式 `run()` | 流式 `runStream()` |
|---|---|---|
| 启动方式 | 调用即执行，阻塞到结束 | 返回句柄，`start()` 才发起请求 |
| 循环驱动 | `while (currentStep <= maxSteps)` | `onCompleteResponse` 内递归 `chat(step + 1)` |
| 模型接口 | `ChatModel.chat()` | `StreamingChatModel.chat(request, handler)` |
| 增量输出 | 无 | `onPartialResponse` / `onPartialThinking` |
| 最终结果 | `ChatResult` | `onCompleteResponse(ChatResponse)` |
| 错误交付 | 抛 `AgentException` 或返回 `MODEL_CALL_ERROR` | `onError` 回调；无回调时抛 `AgentException` |
| 最大步数 | 返回 `AgentRunState.MAX_STEPS` | 作为错误交给 `onError`，同时 `onStop(MAX_STEPS)` |
| 重试 | `AiCallRetrySupport` 指数退避 | 当前实现未接入重试 |
| 工具执行进度 | 无单项回调（仅中间件） | `onToolExecuted` |

这张表里最值得注意的是 **重试差异**：非流式 `think()` 调用了 `AiCallRetrySupport`，而流式路径目前直接在 `onError` 里收敛。这是后续演进点，不是「一套内核」的破坏——它只是驱动器层的一个能力缺口。

### 6.3、所谓「一套调用」到底指什么

它不意味着「一个方法同时返回同步结果和流式 token」，而是：

> **同一个 `ReActAgent` 实例、同一套 Builder 配置、同一个 `memoryId`、同一套工具与中间件，可以在 `run()` 与 `runStream()` 之间自由切换。**

例如同一个 Agent：

```java
ReActAgent agent = ReActAgent.builder()
        .agentName("weather-agent")
        .systemPrompt("你是一个天气助手")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(memoryProvider)
        .toolService(toolService)
        .agentSettings(AgentSettings.builder().maxSteps(10).build())
        .middleware(new LoggingIAgentMiddleware())
        .build();
```

非流式调用：

```java
ChatResult result = agent.run(
        AgentRequest.builder().memoryId("user-1").question("杭州天气怎么样？").build());
```

流式调用：

```java
agent.runStream(
        AgentRequest.builder().memoryId("user-1").question("杭州天气怎么样？").build())
        .onPartialResponse(token -> System.out.print(token))
        .onPartialThinking(thinking -> System.out.println("[think] " + thinking.text()))
        .onToolExecuted(execution ->
                System.out.println("[tool] " + execution.request().name()))
        .onCompleteResponse(response ->
                System.out.println("\n[done] " + response.aiMessage().text()))
        .onError(Throwable::printStackTrace)
        .start();
```

两者共享同一个 `memoryId` 的记忆，因此可以在同一会话里混用：上一轮用流式，下一轮用非流式，上下文仍然连续。

<br/>

## 七、状态机：`StepResult` 与 `AgentRunState` 的分工

### 7.1、两层状态，不要混用

AgentForge 把状态拆成两层：

- **`StepState`**：一轮执行完，循环要不要继续；
- **`AgentRunState`**：整个运行最终是什么结果。

```java
public enum StepState {
    RUNNING,    // 本轮没有结论，继续
    FINISHED,   // 本轮行动完成，继续下一轮 think
    STOP,       // 整个运行结束
    CANCEL;     // 预留的取消态（当前主循环主要用异常收敛取消）
}
```

```java
public enum AgentRunState {
    SUCCESS(0, "任务执行成功"),
    MODEL_CALL_ERROR(1001, "模型调用异常"),
    CANCEL(1002, "任务已取消"),
    MAX_STEPS(1003, "执行步长达到上限");
}
```

`StepResult` 提供三个工厂方法，把语义固定下来（`StepResult.java:28`）：

```java
StepResult.toFinished(message);        // 本轮完成，继续下一轮
StepResult.toStop(res);                // 整个结束，默认 SUCCESS
StepResult.toStop(res, runState);      // 整个结束，指定结束态
StepResult.toRunning();                // 初始化占位
```

### 7.2、非流式状态流转

```text
                     ┌─────────────────────────────┐
                     │        StepState.RUNNING    │
                     └──────────────┬──────────────┘
                                    │ 每轮 step()
                                    ▼
              ┌─────────────── StepState ───────────────┐
              │                                          │
        FINISHED                                     STOP
              │                                          │
              ▼                                          ▼
    currentStep++ 继续循环                  isFinished = true
              │                                          │
              │                              ┌───────────┴───────────┐
              │                              │ runState 为空?         │
              │                              │  -> SUCCESS           │
              │                              │ 否则用 think 指定状态   │
              │                              └───────────┬───────────┘
              │                                          ▼
              │                                  ChatResult 返回
              │
              └── 超过 maxSteps ──► MAX_STEPS
```

异常分支：

- `CancelException` / 中断异常 → `CANCEL`；
- 非中断异常 → `onStopWithError` 后抛 `AgentException`；
- 模型调用失败 → `ThinkResult.state = MODEL_CALL_ERROR` → `StepResult.toStop` → `ChatResult.runState = MODEL_CALL_ERROR`。

### 7.3、流式状态流转

流式没有 `StepResult` 返回值可依赖，状态通过「哪个回调被触发」表达：

| 场景 | 中间件回调 | 业务回调 |
|---|---|---|
| 正常回答 | `onStop(NORMAL)` | `onCompleteResponse` |
| 工具轮结束但还有下一轮 | `afterLoop(FINISHED)` | `onIntermediateResponse` |
| 达到最大步数 | `onStop(MAX_STEPS)` | `onError(AgentException)` |
| 用户取消 | `onStop(CANCEL)` | `onError(CancelException)` |
| 模型/网络错误 | `onStopWithError` | `onError(Throwable)` |

<br/>

## 八、完整用法：同一个 Agent，两种消费方式

### 8.1、非流式

```java
ReActAgent agent = ReActAgent.builder()
        .agentName("weather-agent")
        .description("查询天气")
        .systemPrompt("你是一个天气助手，需要时调用工具")
        .chatModel(chatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(100))
        .toolService(toolService)
        .agentSettings(AgentSettings.builder()
                .maxSteps(10)
                .aiCallRetry(3)
                .aiCallRetryDelay(2000)
                .enableConcurrentToolExecution(true)
                .build())
        .middleware(new LoggingIAgentMiddleware())
        .build();

ChatResult result = agent.run(AgentRequest.builder()
        .memoryId("user-1")
        .question("杭州天气怎么样？")
        .build());

System.out.println(result.getRes());
System.out.println(result.getRunState().getDescription());
```

### 8.2、流式

```java
agent.runStream(AgentRequest.builder()
                .memoryId("user-1")
                .question("杭州天气怎么样？")
                .build())
        .onPartialThinking(thinking ->
                System.out.print(thinking.text()))
        .onPartialResponse(token ->
                System.out.print(token))
        .onToolExecuted(execution -> {
            System.out.println("[tool] " + execution.request().name());
            System.out.println("[result] " + execution.result().text());
        })
        .onIntermediateResponse(response ->
                System.out.println("[next-round] toolCalls="
                        + response.aiMessage().toolExecutionRequests().size()))
        .onCompleteResponse(response ->
                System.out.println("\n[done] " + response.aiMessage().text()))
        .onError(error ->
                System.err.println("[error] " + error.getMessage()))
        .start();
```

### 8.3、取消

```java
// 非流式：在另一个线程或工具回调中调用
agent.cancel("user-1");

// 流式：同样调用 cancel，ReActTokenStream 会在下一轮 chat 前检查
agent.runStream(request).onError(...).start();
```

取消是**协作式**的：检查点位于每轮 `think` 前后（非流式）或每次 `chat()` 入口（流式）。已经发出的模型 HTTP 请求或正在执行的工具不会被强制打断，因此取消的延迟上限是「当前这一次模型调用或工具调用」。

<br/>

## 九、事件顺序：测试如何证明两条路径一致

`ReActAgentMiddlewareTest` 用一个「工具轮 + 最终回答」的脚本模型，断言非流式的回调顺序（`ReActAgentMiddlewareTest.java:50`）：

```text
init
beforeLoop:1
beforeModel:1
afterModel:1:1
beforeTool:getWeather
afterTool:getWeather
afterLoop:1:FINISHED
beforeLoop:2
beforeModel:2
afterModel:2:0
afterLoop:2:STOP
stop:2:NORMAL
```

流式测试 `ReActAgentStreamMiddlewareTest` 对同样的「工具轮 + 最终回答」断言（`ReActAgentStreamMiddlewareTest.java:46`）：

```text
init
beforeLoop:1
beforeModel:1
afterModel:1:1
beforeTool:getWeather
afterTool:getWeather
intermediate:1
afterLoop:1:FINISHED
beforeLoop:2
beforeModel:2
partial:2:杭州
partial:2:今天22度
afterModel:2:0
afterLoop:2:STOP
stop:2:NORMAL
```

对比可以看到：

- **轮次级顺序完全一致**：`beforeLoop → beforeModel → afterModel → 工具 → afterLoop`；
- 流式多出的 `partial:*` 与 `intermediate:*`，正好对应「增量 token」与「中间响应」这两个流式专属事件；
- 两条路径的 `init` 都只出现一次，`stop` 都在最后出现一次。

这就是「一套调用」最有力的证据：**不是接口长得像，而是事件语义对齐。**

<br/>

## 十、边界与设计取舍

### 10.1、已明确的设计边界

1. **同一个 `memoryId` 不支持并发运行。**
   `prepareForNewRun` 的注释已经写明：清理残留取消标志的前提是上层保证同一 `memoryId` 不会并发运行（`BaseAgent.java:97`）。

2. **流式是异步回调模型，`start()` 返回不代表执行结束。**
   `start()` 只负责发起第一次 `chat(1)`；后续轮次在模型回调线程里递归推进。业务侧如果需要「等待流结束」，应使用 `CountDownLatch` 或 `onCompleteResponse` / `onError` 自行收敛。

3. **流式没有 `ChatResult`。**
   最终文本从 `onCompleteResponse` 的 `ChatResponse.aiMessage().text()` 取；错误从 `onError` 取。调用方不能像非流式那样拿到统一的 `runState`。

4. **最大步数在两条路径上的表达不同。**
   非流式是正常返回 `AgentRunState.MAX_STEPS`；流式是错误回调 + `onStop(MAX_STEPS)`。这是由「有没有同步返回值」决定的，不是状态机不同。

5. **重试只在非流式生效。**
   `AiCallRetrySupport` 只被 `ReActAgent.think()` 调用；`ReActTokenStream` 没有接入。若业务对流式稳定性要求高，需要在驱动器层补上重试策略。

6. **取消不能中断在途请求。**
   取消标志只在检查点生效，无法强制终止已经发出的 HTTP 请求或工具执行。

### 10.2、为什么这样取舍

- **模板方法放在 `Agent`，而不是接口默认方法**：因为循环骨架需要访问 `getAgentSettings()`、`getMiddlewareManager()` 等抽象能力，放在抽象类里更直接；
- **`think` / `act` 下沉到子类**：不同 Agent 可以有不同的推理与行动策略，但共享同一套循环；
- **流式用递归而不是状态机**：递归天然表达「一轮完成 → 若需工具则执行 → 再发起一轮」，代码更贴近 ReAct 的语义；
- **工具执行器独立成类**：避免非流式和流式各写一遍串行/并发、写记忆、触发中间件的逻辑。

<br/>

## 十一、总结

回到开头的问题：非流式和流式如何共用一套调用？

AgentForge 的答案是三层收口：

1. **领域收口**：`AgentChatContext`、`ChatMemory`、`AgentSettings`、`ThinkResult`、`StepResult` 在两条路径上完全一致；
2. **执行收口**：`AgentToolExecutor` 统一工具批次执行，`AgentMiddlewareManager` 统一横切回调；
3. **驱动分离**：`Agent.run()` 用 `while` 同步推进，`ReActTokenStream` 用 `onCompleteResponse` 递归推进，但都调用同一套 `think` / `act` 语义。

最终形成的结构是：

```text
                    ┌──────────────────────────────┐
                    │        ReAct 内核             │
                    │  think -> act -> 状态收敛      │
                    │  记忆 / 工具 / 中间件 / 取消    │
                    └──────────────┬───────────────┘
                                   │
                ┌──────────────────┴──────────────────┐
                │                                     │
        Agent.run()                        ReActTokenStream
        （同步驱动器）                       （异步驱动器）
                │                                     │
        ChatResult                          TokenStream 回调
```

这套设计带来的直接收益是：

- 新增一个 ReAct Agent，只需要实现 `think` / `act`，循环、取消、最大步数、工具执行全部复用；
- 新增一个横切能力，只需要写一个中间件，两条路径同时生效；
- 新增一种模型，只需要换 `ChatModel` / `StreamingChatModel`，内核不动。

下一篇将展开 `AgentMiddlewareManager` 的接口设计与实现细节：回调如何分发、异常如何隔离、可改写与可中断的边界在哪里，以及如何在非流式/流式两条路径上新增一个稳定的扩展点。

<br/>

## 参考资料

- 源码：`agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent`
- 测试：`ReActAgentTest`、`ReActAgentStreamTest`、`ReActAgentMiddlewareTest`、`ReActAgentStreamMiddlewareTest`
- 同系列：[middleware/标准Agent的middleware接口扩展实现.md](middleware/%E6%A0%87%E5%87%86Agent%E7%9A%84middleware%E6%8E%A5%E5%8F%A3%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0.md)
