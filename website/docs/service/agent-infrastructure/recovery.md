---
title: Agent 恢复能力设计
sidebar_label: Agent 恢复能力设计
description: 页面之外的后台重启自恢复能力：服务被杀、OOM、容器重建后，把没跑完的 Agent 任务拉起来继续执行
sidebar_position: 2
displayed_sidebar: serviceSidebar
---

# Agent 恢复能力设计（重启自恢复）

前面几篇讲的都是「页面上能看到」的能力：对话、流式输出、停止、Trace。本篇讲一个用户几乎感知不到、但决定了系统是否可靠的能力——**服务重启后，把上次没跑完的 Agent 任务自动拉起来继续跑**。

它没有独立的页面入口，也没有按钮。用户在页面上只会看到：昨天那个还在执行的长任务，今天重新打开时仍然在继续，或者已经补完了。要做到这一点，背后需要一整套恢复编排。

## 1. 背景：为什么需要它

二段式流式对话把「启动任务」和「消费事件」拆开了：`submit` 之后 Agent Run 在后台线程池里跑，事件先写进事件存储，前端通过 `stream + afterSeq` 反复订阅。这一层解决的是「**页面刷新、断网、重连不影响任务**」。

但它解决不了另一类问题：

> Agent 任务本身依赖 JVM 里的执行线程。进程被 `kill -9`、OOM、容器重建、机器重启之后，内存里的执行线程、取消句柄、RunState 全部消失，而数据库里的消息还停在 `RUNNING`。此时：

- 页面上这条 AI 消息永远显示运行中；
- 事件不再增长，前端重连也等不到新内容；
- 用户只能在页面上点「停止」，或者永远看着它转圈。

**重启自恢复要解决的就是这个断点**：让服务在启动时识别出「上次没跑完的任务」，基于已经持久化的会话记忆（ChatMemory）和来源执行 `executionId`，把这一轮重新续起来，并继续向同一个 `messageId` 写事件。前端完全无感，重新打开会话时按 `afterSeq` 续读即可看到结果。

## 2. 面对的场景

恢复不是「所有异常都救」，而是先分清故障形态：

| 故障形态 | 数据现场 | 处理方式 |
| --- | --- | --- |
| 模型报错 | 消息 `FAILED(3)` | 已收口，不在候选集，不自动恢复 |
| 用户主动停止 | 消息 `CANCELLED(4)` / `TIMEOUT_CANCELLED(6)` | 已收口，不自动恢复，尊重用户意图 |
| 等待人工审批（HITL） | 消息 `WAITING_HITL(8)` | 不自动恢复，必须由用户提交审批后走中断恢复 |
| 进程被强杀 / OOM / 容器重建 | 消息卡在 `CREATED(1)` / `RUNNING(2)`，会话 `status=1`，执行注册表里没有它 | **本能力负责** |
| 优雅停机 | 停机前已正常收口 | 不在候选集，自然不处理 |
| 工具执行到一半崩溃 | 无终态、无工具账本 | 当前不支持，需要检查点与工具幂等账本 |

一句话概括判定：

> **消息是运行态（`CREATED` / `RUNNING`），但运行注册表里没有它的执行记录 → 重新注册并恢复。**

中断态一律不碰，终态一律不碰；工具执行中崩溃这种「无法判断副作用是否已经发生」的场景，明确留到后续能力。

## 3. 判定锚点是消息，不是会话

会话状态和消息状态是两次独立写库（先更消息、再更会话），崩溃点可能正好夹在中间，所以恢复**以消息为准，会话状态只当索引用**：

```sql
s.is_deleted = 0
AND s.status = 1                    -- 会话运行中，缩小扫描面
AND s.modify_time >= :since         -- 默认最近 5 天
AND m.is_deleted = 0
AND m.role_type = 2                 -- AI 消息
AND m.status IN (1, 2)              -- CREATED / RUNNING 才是"没跑完"
AND m.execution_id IS NOT NULL AND m.execution_id <> ''
AND m.id = (该会话最后一条 AI 消息)   -- 只看尾部
```

其中 `execution_id` 是「这一轮到底跑过」的唯一锚点：它由 `SessionLifecycleMiddleware` 在 `onInitComplete` 时落库，是恢复时必须传回框架的来源执行 ID。

`m.status IN (1,2)` 这一条同时完成了三件事：只取运行态、天然排除 `WAITING_HITL(8)`、天然排除所有终态，所以中断与已完成任务根本进不了候选集。

## 4. 整体编排

```text
应用启动
   │
   ▼
AgentRecoveryRunner（ApplicationRunner，延迟 delay 毫秒，异步线程）
   │
   ▼
AgentRecoveryService
   │  ① 一条 SQL 捞候选（最近 N 天 + 会话运行中 + 尾部 AI 消息 CREATED/RUNNING + execution_id 非空）
   │  ② 逐条：解析运行时配置 → 初始化/复用 Agent 实例
   │  ③ 查 CacheExecuteManager：该 messageId 已在跑则跳过
   ▼
AgentRecoveryExecutor.resume(task)
   │  ① resumeStream(memoryId, sourceExecutionId) → 生成新 executionId
   │  ② executeManager.register(sessionId, messageId, newExecutionId, cancelHandle)
   │  ③ streamingMiddleware.bind(memoryId, eventFactory.createSink(messageId))
   │  ④ lifecycleMiddleware.registerRun(memoryId, sessionId, messageId)
   │  ⑤ taskExecutor 提交 start()
   ▼
后台续跑，事件继续写同一个 messageId
   │  正常结束 → 消息 SUCCEEDED(5) / 会话 FINISHED
   │  模型报错 → 消息 FAILED(3)
   └  用户停止 → 走新 executionId 精确取消
```

几个关键设计点：

- **必须走 `resumeStream`，不能走 `submit`**：`submit` 会插入新的用户消息，导致问题重复、上下文重复；恢复只消费已有 ChatMemory。
- **运行注册表 `CacheExecuteManager`**：以 `messageId` 为键，记录 `sessionId + messageId + executionId`，用于「这条消息是否正在跑」的判定，并托管取消句柄。重启后它是空的，所以正常候选天然满足「未注册」。
- **预注册 RunState**：恢复执行器先调 `lifecycleMiddleware.registerRun(...)`，让状态推进与一次性收口能命中内存 Map，避免消息卡在 RUNNING。
- **事件出口与页面无关**：恢复绑定的是事件存储 Sink（MySQL / InMemory），不持有任何 SSE 连接。恢复跑出来的增量继续写同一个 `messageId`，前端重新连接后按 `afterSeq` 续读即可。

## 5. 恢复后的表现

```text
进程被强杀
   → 消息卡在 RUNNING，SSE 断开，前端保留 messageId + 最后 afterSeq
服务重启
   → 恢复组件识别候选，resumeStream 续跑
   → 新事件写入同一 messageId，eventSeq 从库里最大值继续递增，不重号不跳号
前端重新打开会话
   → 先查历史详情，看到最后一条仍是运行态 → 重新订阅 stream(afterSeq)
   → 先补齐断档事件，再实时跟到新增量 → 用户看到"接着往下输出"
```

对页面来说，这条链路和普通的「断线重连」完全一致；用户不需要知道中间发生过一次服务重启。

## 6. 配置项

```yaml
agentforge:
  agent:
    recovery:
      enabled: ${AGENTFORGE_AGENT_RECOVERY_ENABLED:true}          # 是否开启启动自恢复
      lookback-days: ${AGENTFORGE_AGENT_RECOVERY_LOOKBACK_DAYS:5}  # 只恢复最近 N 天活跃的会话
      startup-delay-ms: ${AGENTFORGE_AGENT_RECOVERY_STARTUP_DELAY_MS:3000}  # 延迟启动，等连接池/Mapper 就绪
```

启动恢复使用独立线程池 `agentRecoveryTaskExecutor`，与正常对话的 `chatTaskExecutor` 隔离，避免恢复任务挤占用户请求。

## 7. 前置条件与边界

要「刷新/重启后能继续」，三个前提缺一不可：

| 前提 | 原因 |
| --- | --- |
| 事件存储使用 MySQL（`agentforge.chat.event-store.type=mysql`） | InMemory 一重启就空，断档事件全丢 |
| `eventSeq` 连续 | Sink 初始化时从库内 `maxSeq` 继续递增，保证不重号、不跳号 |
| 消息仍是 `RUNNING(2)` | 订阅端只在非终态时才继续等待新事件；恢复跑完收口后自然结束订阅 |

当前已知限制（都是有意接受的一期边界）：

| 限制 | 表现 | 说明 |
| --- | --- | --- |
| 多实例同时启动 | 两个实例可能恢复同一会话 | 当前按单实例部署设计；后续靠分布式注册表/抢占收敛 |
| 会话 `status=1` 但消息已终态 | 显示运行中，但不在候选集 | 属于脏数据，需要一条修数据 SQL 收口 |
| `execution_id` 为空的 `CREATED` 消息 | 不恢复也不收口 | 只存在于 `submit` 到 `start()` 的毫秒级窗口，概率极低 |
| 崩溃循环 | 每次启动都重试 | 结构化日志可见；「尝试次数上限」属于二期 |
| 工具执行中崩溃 | 不自动恢复 | 无终态、无法判断副作用，需检查点与幂等账本 |

## 8. 如何确认它真的工作了

只看页面是看不出恢复过程的，排查落在日志与数据库：

```bash
# 1) 发起一轮长任务，等消息进入 RUNNING
# 2) 确认库里留下现场
mysql -e "SELECT s.session_id, s.status, m.id, m.status, m.execution_id
          FROM ai_agent_session s JOIN ai_agent_session_message m ON m.session_id = s.id
          WHERE m.role_type = 2 ORDER BY m.id DESC LIMIT 3;"
# 期望：m.status = 2，m.execution_id 非空，s.status = 1

# 3) 强杀进程（确保不是优雅停机）
kill -9 <pid>

# 4) 重启后看日志
#    [AgentRecovery] 命中候选=1
#    [AgentRecovery] sessionId=..., aiMessageId=..., sourceExecutionId=...
#    [AgentRecovery] 已接管 ... newExecutionId=...
#    [AgentRecovery] 完成 { scanned=1, resumed=1, failed=0 }
```

关键校验点：

- 该消息最终变为 `SUCCEEDED(5)`，会话变为 `FINISHED`；
- `events` 有新增事件，前端打开会话能看到完整回答；
- 记忆里没有多出一条 UserMessage（确认走的是 `resume` 而不是 `submit`）；
- 如果是中断态，候选集里不会出现它，用户提交审批后仍走中断恢复。

## 9. 和「前端重连」的区别

这两个能力经常被混淆，实际负责完全不同的层次：

| 维度 | 前端断线重连 | 后端重启自恢复 |
| --- | --- | --- |
| 触发者 | 浏览器 / 页面 | 服务进程启动 |
| JVM 是否存活 | 存活 | 已重启 |
| 依据 | `messageId + afterSeq` | ChatMemory + 来源 `executionId` |
| 做什么 | 重新订阅事件，不重跑任务 | 重新拉起任务，继续向同一消息写事件 |
| 页面感知 | 直接可见 | 基本无感，表现为「任务继续了」 |

前端负责「连回来」，后端负责「活过来」，两者配合，才让用户无论刷新页面还是服务重启都能拿到完整结果。
