# Agent 任务服务重启自动恢复设计方案（精简版）

> 目标：agentforge-service 进程被 kill / OOM / 容器重建后，重启时把「上次没跑完的 Agent 任务」自动拉起来继续跑。
>
> 本期定位：**能跑通 + 好维护**。一条 SQL 捞候选，逐条注册 Agent 并 `resume`，不做抢占、不做并发控制、不改框架。

---

## 一、先看结论

| 结论 | 说明 |
| --- | --- |
| 恢复算法已经有了 | `Agent extends BaseAgent implements IResumableAgent`，`resumeStream(memoryId, executionId)` 会从 ChatMemory 续跑并自动生成新 `executionId` |
| 服务层缺的只是编排 | `ReActAgent → BaseReActAgent → Agent implements IResumableAgent`，直接把 agent 强转 `IResumableAgent` 就能 `resumeStream`，**不用给 `IAgentManager` 加方法** |
| **全部是新增组件** | 恢复能力落在独立包 `agent/recovery/`，现有 `IAgentManager` / `AgentChatTaskServiceImpl` / `activeStops` 一行不改（详见 §3.4） |
| **不需要改框架** | 恢复前先 `lifecycleMiddleware.registerRun(...)` 预注册 RunState，`state()` 命中内存 Map，状态推进与收口都正常（详见 §五） |
| **判定 = 运行态 + 未注册** | 消息 `CREATED(1)`/`RUNNING(2)`，且运行注册表里没有这条消息 → 恢复。中断态 `WAITING_HITL(8)` 不恢复，终态不动 |
| **运行注册表用三个 ID 判定** | `CacheExecuteManager` 记 sessionId + messageId + executionId，`isRunning(sessionId, messageId)` 一次查表就够。后续定时任务等新场景可以直接复用 |
| 判定锚点是**消息**不是会话 | 会话 `status=1` 可能是脏数据，真正的锚点是「会话最后一条 AI 消息非终态 + `execution_id` 非空」 |
| 不做并发限流 | 用独立缓存线程池跑恢复，来一个起一个、不排队。同时把 `chatTaskExecutor` 也改成缓存池——两者都承载「整轮执行」，用有界池会让用户在队列里干等 |
| 恢复必须走 `resume` 不能走 `submit` | `submit` 会插入新的 UserMessage，导致问题重复、上下文重复 |

一句话：

```text
ApplicationRunner（延迟 3s，异步）
  → 一条 SQL 捞：会话 status=1 + 尾部 AI 消息 status IN (1,2) + execution_id 非空 + 最近 5 天
  → 逐条：初始化 Agent 实例 → 查运行注册表（在跑则跳过）
        → resumeStream → 注册三元组 → start()
  → 中间件自动把消息收口为 5/3，会话收口为 2
```

`CacheExecuteManager` 是这次新增的基础设施，以后新场景（定时任务等）可以直接复用：

```java
executeManager.register(sessionId, messageId, executionId, cancel);  // 开始前，false 就别跑
executeManager.isRunning(sessionId, messageId);                     // 恢复前的判据
executeManager.takeCancel(messageId);                               // 用户点停止
executeManager.unregister(messageId);                               // 收口 / 异常兜底
```

与原始 1063 行方案的差异（都是删减，没有新增复杂度）：

| 原方案 | 精简版 | 原因 |
| --- | --- | --- |
| 改 `IResumableAgent` + `Agent#prepareResume` 透传 extensions | **不改框架** | 预注册 RunState 后 `state()` 不再走 extension 兜底 |
| `claimForResume` CAS 抢占 | 删除 | 注册表 `putIfAbsent` 已经挡住重复 |
| `concurrency` 配置 + 自建并发控制 | 删除 | 缓存线程池天然并发，且与对话池隔离 |
| 索引迁移脚本 | 降为「数据量大再加」 | 先跑通，不阻塞上线 |
| `AgentRecoveryReport` 埋点体系 | 降为一条汇总日志 | 需要时再加 |
| 改 `IAgentManager` / `AgentChatTaskServiceImpl` 加恢复入口 | **全部新增组件，不改旧代码** | 避免污染既有链路 |

---

## 二、恢复谁 / 不恢复谁

判定就一句话：

> **消息是运行态（`CREATED(1)` / `RUNNING(2)`），但 manager 里没有这条消息 `executionId` 的运行注册 → 重新注册并 resume。**

中断态（`WAITING_HITL(8)`）一律不碰，人工审批走 `resumeInterrupt`。终态（`3/4/5/6`）已收口，也不碰。

### 2.1 故障形态

| 形态 | 现象 | 处理 |
| --- | --- | --- |
| 模型报错 | 消息 `FAILED(3)` | 已收口，不动 |
| 用户停止 | 消息 `CANCELLED(4)` / `TIMEOUT_CANCELLED(6)` | 已收口，不动 |
| 中断等审批 | 消息 `WAITING_HITL(8)`、会话 `WAITING_HITL(5)` | **不恢复**，走已有 `resumeInterrupt` |
| **进程被强杀** | 消息卡 `CREATED(1)`/`RUNNING(2)`，会话卡 `RUNNING(1)`，manager 里没有它的运行注册 | **本方案** |
| 优雅停机 | 停机时已收口 | 不在候选集，自然不碰 |

### 2.2 判定 SQL

会话状态与消息状态是两次独立写库（`SessionLifecycleMiddleware#onInitComplete` 先更消息再更会话），崩溃点可能夹在中间，所以**以消息为准，会话状态只当索引用**：

```sql
s.is_deleted = 0
AND s.status = 1                    -- 缩小扫描面
AND s.modify_time >= :since         -- 默认 now - 5 天
AND m.is_deleted = 0
AND m.role_type = 2                 -- AI 消息
AND m.status IN (1, 2)              -- CREATED / RUNNING 才是"没跑完"
AND m.execution_id IS NOT NULL AND m.execution_id <> ''
AND m.id = (该会话最后一条 AI 消息)  -- 只看尾部
```

`execution_id` 是 `onInitComplete` 落库的（`SessionLifecycleMiddleware.java:85-94`），也就是「这一轮到底跑到哪」的唯一锚点。**恢复永远是针对某会话最新那条 AI 消息的 `execution_id`**，不需要更复杂的历史追溯。

SQL 里 `m.status IN (1, 2)` 这一条同时完成了三件事：只取运行态、天然排除 `WAITING_HITL(8)`、天然排除所有终态。所以**中断态根本进不了候选集，不需要单独处理**。

### 2.3 两条语义边界

1. **恢复 ≠ 重跑**。`prepareResume` 把历史（含工具结果）整体交给模型：工具已执行完就直接继续推理；工具调用悬空由 `MessageChainRepairer.repairDanglingToolCalls` 补 synthetic 结果（`Agent.java:427`）；模型文本已落 ChatMemory 就接着往下走。
2. **中断态不恢复**。`WAITING_HITL(8)` 是人工审批通道，必须由用户提交 `resumeInterrupt`，自动续跑等于绕过审批。

---

## 三、实现

一共 5 处改动、1 个新服务、1 个启动钩子。

### 3.1 配置

`EnvironmentContext` 集中读取，`conf/application.yml` 只做占位（与现有 `agentforge.chat.*` 风格一致）：

```yaml
# conf/application.yml —— 放在 agentforge 下
agentforge:
  agent:
    recovery:
      # 服务重启后是否自动拉起上次未跑完的 Agent 任务
      enabled: ${AGENTFORGE_AGENT_RECOVERY_ENABLED:true}
      # 只恢复最近 N 天内活跃的会话
      lookback-days: ${AGENTFORGE_AGENT_RECOVERY_LOOKBACK_DAYS:5}
      # 启动延迟，等端口 / 连接池 / Mapper 就绪
      startup-delay-ms: ${AGENTFORGE_AGENT_RECOVERY_STARTUP_DELAY_MS:3000}
```

```java
// EnvironmentContext.java —— 新增 3 个方法
private static final boolean DEFAULT_AGENT_RECOVERY_ENABLED = true;
private static final int DEFAULT_AGENT_RECOVERY_LOOKBACK_DAYS = 5;
private static final long DEFAULT_AGENT_RECOVERY_STARTUP_DELAY_MS = 3_000L;

public boolean isAgentRecoveryEnabled() {
    return environment.getProperty(
            "agentforge.agent.recovery.enabled", Boolean.class, DEFAULT_AGENT_RECOVERY_ENABLED);
}

public int getAgentRecoveryLookbackDays() {
    int value = environment.getProperty(
            "agentforge.agent.recovery.lookback-days", Integer.class,
            DEFAULT_AGENT_RECOVERY_LOOKBACK_DAYS);
    return value > 0 ? value : DEFAULT_AGENT_RECOVERY_LOOKBACK_DAYS;
}

public long getAgentRecoveryStartupDelayMs() {
    long value = environment.getProperty(
            "agentforge.agent.recovery.startup-delay-ms", Long.class,
            DEFAULT_AGENT_RECOVERY_STARTUP_DELAY_MS);
    return value >= 0L ? value : DEFAULT_AGENT_RECOVERY_STARTUP_DELAY_MS;
}
```

> 初始阶段不做条数限制（`max-sessions`）：候选数量本来就由 `lookback-days` 与「消息非终态」两个条件天然收窄，不应该再砍。将来真遇到启动风暴，再加这个配置项。
>
> `conf/application.yml` 与 `docker/conf/application.yml` 都要加，否则容器部署读不到。

### 3.2 一条 SQL + 一个 POJO

```java
// session/domain/RecoverableSessionPojo.java —— 新增
public class RecoverableSessionPojo {
    private String sessionId;       // ai_agent_session.session_id，即 memoryId
    private String aid;
    private Integer isDebug;
    private Long userId;            // create_user_id
    private Long aiMessageId;
    private Integer messageStatus;
    private String executionId;
    // getter / setter 略
}
```

```java
// session/dao/AgentSessionMapper.java —— 新增
/** 启动恢复候选：会话运行中 + 尾部 AI 消息非终态 + 有来源执行。 */
List<RecoverableSessionPojo> selectRecoverableSessions(@Param("since") Date since);
```

```xml
<!-- mapper/session/AgentSessionMapper.xml（MySQL）与 sqlliteMapper/session/AgentSessionMapper.xml 同步 -->
<select id="selectRecoverableSessions"
        resultType="cloud.changlu.agentforge.service.modules.session.domain.RecoverableSessionPojo">
    SELECT s.session_id     AS session_id,
           s.aid            AS aid,
           s.is_debug       AS is_debug,
           s.create_user_id AS userId,
           m.id             AS aiMessageId,
           m.status         AS messageStatus,
           m.execution_id   AS executionId
    FROM ai_agent_session s
    JOIN ai_agent_session_message m
      ON m.session_id = s.id
     AND m.is_deleted = 0
     AND m.role_type = 2
     AND m.id = (SELECT MAX(m2.id) FROM ai_agent_session_message m2
                 WHERE m2.session_id = s.id AND m2.is_deleted = 0 AND m2.role_type = 2)
    WHERE s.is_deleted = 0
      AND s.status = 1
      AND s.modify_time &gt;= #{since}
      AND m.status IN (1, 2)
      AND m.execution_id IS NOT NULL
      AND m.execution_id &lt;&gt; ''
    ORDER BY s.modify_time DESC, s.id DESC
</select>
```

字段别名用驼峰 + 全局 `map-underscore-to-camel-case: true`，`resultType` 直接映射，不需要写 resultMap。

> 不加 `LIMIT`：候选集已由「最近 N 天 + 会话运行中 + 尾部 AI 消息非终态」三重条件收窄，正常情况下只有个位数。真出现大批量，应该先查为什么有这么多卡住的任务，而不是静默截断。

### 3.3 新增 `CacheExecuteManager`：统一的运行态注册表

现在「谁在跑」这件事散在两处：`IAgentManager` 里的 Agent 实例（`aid::channel` 粒度，一个 aid 下所有会话共享）和 `AgentChatTaskServiceImpl#activeStops`（`messageId -> 取消句柄`）。两者都不足以回答「某个会话的某条消息是否正在执行」。

补一个运行态注册表，放在 `AgentManagerFactory` 旁边（同一层职责：运行时管理）：

```java
// runtime/execute/CacheExecuteManager.java —— 新增
/**
 * 运行态注册表：sessionId + messageId + executionId。
 *
 * <p>页面会话、HITL 恢复、定时任务、启动恢复统一走这里登记一次，
 * 用它回答"某会话的某条消息是不是正在跑"。
 */
@Component
public class CacheExecuteManager {

    /** key = messageId（全局唯一主键），值里带全部三个 ID + 取消句柄。 */
    private final Map<Long, Execute> executes = new ConcurrentHashMap<>();

    /** 登记一次执行；该消息已在跑则返回 false（调用方拒绝或跳过）。 */
    public boolean register(String sessionId, Long messageId, String executionId, Runnable cancel) {
        if (sessionId == null || messageId == null) {
            return false;
        }
        return executes.putIfAbsent(
                messageId, new Execute(sessionId, messageId, executionId, cancel)) == null;
    }

    /** 该会话的这条消息是否正在跑；三元组任一不符都算没在跑。 */
    public boolean isRunning(String sessionId, Long messageId) {
        if (sessionId == null || messageId == null) {
            return false;
        }
        Execute execute = executes.get(messageId);
        return execute != null && sessionId.equals(execute.sessionId);
    }

    /** 注销，返回是否命中；任务收口、取消、异常兜底都调它。 */
    public boolean unregister(Long messageId) {
        return messageId != null && executes.remove(messageId) != null;
    }

    /** 取取消句柄并注销（用户点停止时用）；没在跑返回 null。 */
    public Runnable takeCancel(Long messageId) {
        Execute execute = messageId == null ? null : executes.remove(messageId);
        return execute == null ? null : execute.cancel;
    }

    /** 一次执行：三个 ID + 取消句柄。 */
    private static final class Execute {
        private final String sessionId;
        private final Long messageId;
        private final String executionId;
        private final Runnable cancel;

        private Execute(String sessionId, Long messageId, String executionId, Runnable cancel) {
            this.sessionId = sessionId;
            this.messageId = messageId;
            this.executionId = executionId;
            this.cancel = cancel;
        }
    }
}
```

四个方法，一个 Map。三个 ID 全部记在 `Execute` 里：`messageId` 当 key（全局唯一，直接 `containsKey` 就是 O(1)），`sessionId` 做归属校验，`executionId` 留给取消和日志。

这样也避开了 `stop` 路径上的 ID 类型陷阱——`AgentSessionMessagePojo#getSessionId()` 返回的是 `ai_agent_session` 主键 `Long`（`AgentSessionMessagePojo.java:9`），而注册时用的是业务 `session_id` 字符串。用 `messageId` 做 key，`stop` 直接 `takeCancel(messageId)` 就行，不需要再查一次会话表。

调用方只需要记住一句：**开始前 `register` 不上就别跑，结束后 `unregister`。**

为什么这样比「查 `agent.getExecutionState(...)`」更好：

| 维度 | 查 `AgentRunRegistry` | `CacheExecuteManager` |
| --- | --- | --- |
| 键 | `memoryId`（= sessionId） | `sessionId + messageId + executionId` 三元组 |
| 能否区分「同一会话的第几轮」 | 不能，只保留当前活跃执行 | 能，按 `messageId` 精确定位 |
| 取消句柄 | 需要另存一份 `activeStops` | 直接托管，少维护一个 Map |
| 与 Manager 的关系 | 藏在 Agent 实例里，需要先拿到 agent | 独立 Bean，恢复服务直接注入 |
| 恢复判据 | 重启后必然为空，等价于「总是要恢复」 | 同上，但语义显式，日志可直接打印 |

> 三个判定点都要经过它：`submit` 拒绝同会话重复提交、`stop` 取句柄下发取消、`recoverOnStartup` 判是否已在跑。

### 3.4 为什么不用改任何现有代码

前几版方案都想通过改 `IAgentManager` / `AgentChatTaskServiceImpl` 来复用恢复能力，这会污染既有链路。核查后确认**完全不需要**：

| 想改的地方 | 实际怎么做 |
| --- | --- |
| `IAgentManager` 加 `resumeStream` | `ReActAgent → BaseReActAgent → Agent implements IResumableAgent`，直接 `((IResumableAgent) agent).resumeStream(memoryId, executionId)` |
| `AgentChatTaskServiceImpl` 加 `resumeTask` | 恢复是独立组件，自己注入 `ServiceStreamingMiddleware` / `SessionLifecycleMiddleware` / `MessageEventFactory` / `MessageEventSnapshotService`，全是现成 Bean |
| `activeStops` 换成 `CacheExecuteManager` | 不动它。恢复组件用自己的注册表，`activeStops` 继续服务原有 submit/stop |

于是恢复能力就是一个新增包 `agent/recovery/` + 一个 `CacheExecuteManager`，**现有文件一行不改**。

代价只有一个：`stop` 接口打不到恢复中的任务，因为 `activeStops` 里没有它的句柄。这个可以接受（重启后用户还没打开页面，一般也不会点停止），二期再让 `stop` 也查一眼注册表即可。

### 3.5 恢复组件自己接管一轮执行

`AgentRecoveryService` 内部完成「绑定 sink → 预注册 RunState → resumeStream → start」，写法照抄正常提交那套，只是不经过 `AgentChatTaskService`：

```java
// agent/recovery/AgentRecoveryExecutor.java —— 新增，一轮恢复的执行器
@Component
public class AgentRecoveryExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentRecoveryExecutor.class);

    private final ServiceStreamingMiddleware streamingMiddleware;
    private final SessionLifecycleMiddleware lifecycleMiddleware;
    private final MessageEventFactory eventFactory;
    private final MessageEventSnapshotService snapshotService;
    private final AgentSessionMessageMapper agentSessionMessageMapper;
    private final AgentSessionMapper agentSessionMapper;
    private final CacheExecuteManager executeManager;
    private final ExecutorService taskExecutor;   // @Qualifier("agentRecoveryTaskExecutor")

    /** 接管一轮恢复；注册不上（已在跑）直接返回 false。 */
    public boolean resume(AgentResumeTask task) {
        Object memoryId = task.getMemoryId();
        long messageId = task.getAiMessageId();

        // ① 登记运行态：新 executionId 在 start() 之前就能读到（TokenStream.java:17）
        //    注意 resumeStream 会抛异常，所以先拿 stream 再登记会漏注册，这里登记后用 boolean 兜住
        TokenStream stream;
        try {
            stream = ((IResumableAgent) task.getAgent())
                    .resumeStream(memoryId, task.getSourceExecutionId());
        } catch (RuntimeException e) {
            // prepareResume 阶段失败（记忆为空 / 同会话已有活跃执行）
            lifecycleMiddleware.finishWithError(memoryId, e);
            return false;
        }

        boolean registered = executeManager.register(
                task.getMemoryId(), messageId, stream.executionId(),
                () -> ((IAgent) task.getAgent()).cancel(String.valueOf(memoryId), stream.executionId()));
        if (!registered) {
            log.info("[AgentRecovery] 已有运行注册，放弃 aiMessageId={}", messageId);
            return false;
        }

        // ② 绑定事件出口：后台模式绑 MySQL Sink，不需要 SSE 长连接
        streamingMiddleware.bind(memoryId, eventFactory.createSink(messageId));

        // ③ 预注册 RunState：保证 onInitComplete / finishState 找得到 aiMessageId
        //    这是"不改框架"的关键，见 §五
        lifecycleMiddleware.registerRun(memoryId, task.getMemoryId(), messageId);

        // ④ 提交线程池，与正常提交同一条链路
        taskExecutor.execute(() -> {
            try {
                stream.onCompleteResponse(response -> finish(messageId))
                        .onError(error -> finish(messageId))
                        .ignoreErrors()
                        .start();
            } catch (RuntimeException e) {
                streamingMiddleware.onError(memoryId, e);
                lifecycleMiddleware.finishWithError(memoryId, e);
                finish(messageId);
            }
        });
        return true;
    }

    /** 收口：解绑 sink + 落快照 + 兜底补状态 + 注销运行态。 */
    private void finish(long messageId) {
        executeManager.unregister(messageId);
        try {
            snapshotService.saveSnapshot(messageId);
        } catch (RuntimeException ignored) {
            // 订阅端会在观察到终态后自愈快照
        }
        closeIfStillRunning(messageId);
    }

    /**
     * 已完成但状态没落库的兜底收口。
     *
     * <p>prepareResume 判定"历史早已完成"时返回 CompletedTokenStream，
     * start() 只回调 onCompleteResponse、不触发 onInitComplete，消息会停在 1/2。
     */
    private void closeIfStillRunning(long messageId) {
        AgentSessionMessagePojo message = agentSessionMessageMapper.selectById(messageId);
        if (message == null || SessionStatusTransfer.isTerminal(message.getStatus())) {
            return;
        }
        Date now = new Date();
        agentSessionMessageMapper.updateStatus(
                messageId, SessionMessageStatusEnum.SUCCEEDED.getCode(), now);
        agentSessionMapper.updateStatus(
                message.getSessionId(), SessionStatusEnum.FINISHED.getCode(), now);
    }
}
```

`AgentResumeTask` 就是装载对象，字段全是恢复需要的最小集合：

```java
public class AgentResumeTask {
    private String memoryId;           // 会话标识，框架里统一叫 memoryId（= ai_agent_session.session_id）
    private String aid;
    private Long aiMessageId;
    private Long userId;
    private Boolean debug;
    private String sourceExecutionId;  // 消息上已有的 execution_id
    private Object agent;              // 已初始化的 Agent 实例
    // builder / getter 略
}
```

> 有个顺序细节值得注意：`resumeStream` 会同步执行 `prepareResume`（读记忆、修消息链），失败会直接抛异常。所以必须先拿到 `stream` 才能知道新的 `executionId`，`register` 只能放在它之后。为避免"已注册但没跑起来"的泄漏，`prepareResume` 抛异常时走 `finishWithError` 收口并返回 `false`，不进注册表。

### 3.6 恢复编排服务

```java
// agent/recovery/AgentRecoveryService.java —— 新增
@Service
public class AgentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(AgentRecoveryService.class);

    private final EnvironmentContext environmentContext;
    private final AgentSessionMapper agentSessionMapper;
    private final AgentRuntimeConfigResolver runtimeConfigResolver;
    private final AgentManagerFactory agentManagerFactory;
    private final AgentRecoveryExecutor recoveryExecutor;
    private final CacheExecuteManager executeManager;

    // 构造注入略

    /** 启动恢复：捞候选 → 逐条初始化 Agent → 交执行器 resume。 */
    public void recoverOnStartup() {
        if (!environmentContext.isAgentRecoveryEnabled()) {
            log.info("[AgentRecovery] 未开启，跳过");
            return;
        }
        int days = environmentContext.getAgentRecoveryLookbackDays();
        Date since = Date.from(Instant.now().minus(days, ChronoUnit.DAYS));

        List<RecoverableSessionPojo> candidates =
                agentSessionMapper.selectRecoverableSessions(since);
        if (candidates.isEmpty()) {
            log.info("[AgentRecovery] 窗口={}天, 无待恢复会话", days);
            return;
        }
        log.info("[AgentRecovery] 窗口={}天, 命中候选={}", days, candidates.size());

        int resumed = 0;
        int failed = 0;
        for (RecoverableSessionPojo candidate : candidates) {
            try {
                if (recoverOne(candidate)) {
                    resumed++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("[AgentRecovery] 恢复失败 sessionId={}", candidate.getSessionId(), e);
            }
        }
        log.info("[AgentRecovery] 完成 { scanned={}, resumed={}, failed={} }",
                candidates.size(), resumed, failed);
    }

    /** @return 是否真的接管了一轮 */
    private boolean recoverOne(RecoverableSessionPojo c) {
        boolean debug = SessionDebugEnum.DEBUG.getCode().equals(c.getIsDebug());

        // ① 解析运行时配置：debug 读草稿表，正式读发布快照表
        AgentRuntimeConfig config = runtimeConfigResolver.resolve(c.getAid(), debug);
        IAgentManager<Object, Object> manager =
                agentManagerFactory.getAgentManagerByType(config.getType());

        // ② 初始化（或复用）Agent 实例。粒度是 aid + channel，一个 aid 下所有会话共享。
        AgentInitContext initContext = AgentInitContext.builder()
                .aid(config.getAid())
                .agentName(config.getName())
                .agentConfig(config.getAgentConfig())
                .debug(debug)
                .channelId(AgentInitContext.WEBSITE_CHANNEL_ID)
                .build();
        Object agent = manager.getAgentAndInitAgent(initContext);

        // ③ 注册检查：这条消息是否已经在跑？重启后注册表必然为空，
        //    所以这一步正常情况下总是"未注册"，只是把"进程内其实还在跑"挡掉。
        if (executeManager.isRunning(c.getSessionId(), c.getAiMessageId())) {
            log.info("[AgentRecovery] 正在执行中，跳过 sessionId={}, aiMessageId={}",
                    c.getSessionId(), c.getAiMessageId());
            return false;
        }

        log.info("[AgentRecovery] 开始恢复 sessionId={}, aid={}, aiMessageId={}, sourceExecutionId={}",
                c.getSessionId(), config.getAid(), c.getAiMessageId(), c.getExecutionId());

        // ④ 交给独立执行器接管这一轮
        return recoveryExecutor.resume(AgentResumeTask.builder()
                .memoryId(c.getSessionId())
                .aid(config.getAid())
                .aiMessageId(c.getAiMessageId())
                .userId(c.getUserId())
                .debug(debug)
                .sourceExecutionId(c.getExecutionId())
                .agent(agent)
                .build());
    }
}
```

有两点必须注意：

- **`AgentUnavailableException` 不能冒泡**：Agent 已删除 / 停止发布时 `resolve` 会抛（`AgentRuntimeConfigResolver.java:34,41`）。上面用 try-catch 接住并记 `failed`，单条失败不影响其它会话，也不影响启动。
- **注册检查查的是 `CacheExecuteManager`，不是「Agent 实例是否存在」**。`AbstractAgentManager#agents` 的粒度是 `aid::channel`（`AbstractAgentManager.java:86-89`），一个 aid 下所有会话共享同一个 Agent 实例，实例存在与否不能作为判据。真正的判据是「这条 messageId 在运行注册表里吗」——注册表是进程内 Map，重启后必然为空，所以恢复条件天然成立；这层检查只是把「进程内其实还在跑」挡掉，并防止同一候选被重复投递起两轮。

### 3.7 启动钩子

```java
@Component
public class AgentRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRecoveryRunner.class);

    private final AgentRecoveryService agentRecoveryService;
    private final EnvironmentContext environmentContext;
    private final Executor taskExecutor;

    public AgentRecoveryRunner(
            AgentRecoveryService agentRecoveryService,
            EnvironmentContext environmentContext,
            @Qualifier("agentRecoveryTaskExecutor") ExecutorService taskExecutor) {
        this.agentRecoveryService = agentRecoveryService;
        this.environmentContext = environmentContext;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public void run(ApplicationArguments args) {
        long delay = environmentContext.getAgentRecoveryStartupDelayMs();
        taskExecutor.execute(() -> {
            try {
                if (delay > 0L) {
                    Thread.sleep(delay);
                }
                agentRecoveryService.recoverOnStartup();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[AgentRecovery] 启动恢复被中断");
            } catch (RuntimeException e) {
                // 恢复是"尽力而为"，任何失败都不能影响服务可用性
                log.error("[AgentRecovery] 启动恢复异常，服务继续对外提供能力", e);
            }
        });
    }
}
```

三条硬约束：**异步**（恢复要真打模型，不能拖住端口就绪）、**不抛异常**（失败不影响启动）、**延迟启动**（等连接池、Mapper、`MessageEventFactory` 就绪）。

> service 模块目前没有任何 `ApplicationRunner` / `@PostConstruct`（已全仓确认），新增不会与现有逻辑冲突。不用 `@PostConstruct` 是因为那时数据源与 Mapper 尚未完全就绪，且会阻塞主线程。

---

## 四、恢复后的状态流转

```
resumeStream()
  → prepareResume：读 ChatMemory → 判是否已完成 → 修悬空工具调用 → 新 executionId
  → start()
      → onInitComplete：消息 1/2 → 2，写入新 execution_id，会话 → RUNNING
      → 增量事件 → Sink → 事件存储 → 前端重连可见
      → 正常结束：onStop → 消息 → 5(SUCCEEDED)，会话 → 2(FINISHED)
      → 模型报错：onStopWithError → 消息 → 3(FAILED)
      → 用户停止：activeStops 里的 cancel 句柄按新 executionId 精确打断
```

关键点：**前端什么都不用做**。恢复发生在后台，事件写进事件存储；用户下次打开会话，`events` 字段重放的就是完整过程。

### 4.1 页面效果：刷新就能继续看到输出

这就是目标场景，逐段对齐一下链路：

```text
① 页面上任务在跑
   浏览器 → GET /api/chat/tasks/stream?messageId=123&afterSeq=48
   ReconnectableChatStreamService 循环 eventTailer.awaitAfter(123, 48, ...) 拉增量

② 后端进程被关掉
   ① 的 SSE 连接断开，前端保持 messageId=123 和最后收到的 afterSeq

③ 服务重启
   AgentRecoveryRunner（延迟 3s）→ 捞出 messageId=123（status=2，execution_id 非空）
   → AgentRecoveryExecutor：bind(MySQL Sink) + registerRun + resumeStream + start
   → 新事件持续写入 message_event 表，seq 从 48 之后接着涨

④ 页面刷新
   前端重新发 GET /api/chat/tasks/stream?messageId=123&afterSeq=48
   → 先吐 49..N 补齐断档，再实时跟到新的增量 → 用户看到"接着往下输出"
```

三个前提，缺一不可：

| 前提 | 为什么必须 |
| --- | --- |
| `agentforge.chat.event-store.type=mysql` | `inmemory` 一重启就空了，前端 49..N 全丢（`conf/application.yml:16-18` 默认已是 mysql） |
| 事件 seq 连续 | `BufferedMessageEventSink` 初始化时 `nextSeq = eventStore.maxSeq(messageId)`（`BufferedMessageEventSink.java:29`），**重启后从库里最大值继续递增**，不会重号也不会跳号 |
| 消息仍是 `RUNNING(2)` | `ReconnectableChatStreamService` 只在非终态时才继续等新事件（`ReconnectableChatStreamService.java:99-108`）；一旦恢复跑完收口成 5，它排空尾事件后正常断开 |

所以「刷新就能继续 stream」这件事，恢复组件唯一要保证的就是：**重启后能把这一轮重新跑起来并继续往同一个 messageId 写事件**。前端不需要任何改动，`afterSeq` 续传机制本来就有。

---

## 五、为什么这次不用改框架

原方案判定「必须改框架」，依据是：`prepareResume` 自建 `AgentRequest` 时没有 `setExtensions`（`Agent.java:434-442`），于是 `SessionLifecycleMiddleware#state(chatContext)` 读到空上下文返回 `null`（`SessionLifecycleMiddleware.java:378-381`），导致 `onInitComplete` 和 `finishState` 都不执行，消息永久卡 `RUNNING`。

但 `state()` 的判定顺序是**先查内存 Map，查不到才回退读 extension**：

```java
// SessionLifecycleMiddleware.java:374-377
RunState existing = states.get(memoryId);
if (existing != null) {
    return existing;          // ← 命中这里，根本不读 extension
}
```

而 `registerRun(memoryId, sessionId, aiMessageId)` 做的就是 `states.putIfAbsent(memoryId, ...)`（`SessionLifecycleMiddleware.java:72-75`）。所以只要在恢复执行器里先预注册，状态推进与一次性收口全部正常，**框架一行不用动**。

这也是正常 `submit` 路径的做法（`AgentChatTaskServiceImpl.java:500-503`），恢复只是照抄。

---

## 六、一期已知限制（都接受，不实现）

| 限制 | 表现 | 为什么不现在做 |
| --- | --- | --- |
| 多实例同时启动 | 两个实例都可能恢复同一会话 | 现在是单实例部署；`AgentRunRegistry` 的 `putIfAbsent`（`AgentRunRegistry.java:29-31`）能挡住进程内重复 |
| 会话 `status=1` 但消息已是终态的脏数据 | 永远显示运行中，但不在恢复候选集里 | 与恢复无关，需要时用一条 SQL 修：`UPDATE ai_agent_session s SET status=2 WHERE status=1 AND NOT EXISTS (SELECT 1 FROM ai_agent_session_message m WHERE m.session_id=s.id AND m.status IN (1,2) AND m.role_type=2)` |
| `execution_id` 为空的 `CREATED` 消息 | 不恢复、也不收口 | 该窗口只有毫秒级（`submit` 到 `start()` 之间），命中概率极低 |
| 崩溃循环 | 每次启动都重试 | 结构化日志可见；加「尝试次数上限」属于二期 |
| 大库扫描慢 | 启动时全表扫会话 | 先跑通；表大了再加索引 `ai_agent_session(status, modify_time)` 与 `ai_agent_session_message(session_id, role_type, id)` |

---

## 七、改动清单

**全部是新增，现有文件一行不改**（除了两个 yml 加配置块、两个 Mapper XML 加一条 select）。

| # | 文件 | 类型 | 内容 |
| --- | --- | --- | --- |
| 1 | `agent/runtime/execute/CacheExecuteManager.java` | 新增 | sessionId + messageId + executionId 运行注册表 |
| 2 | `agent/recovery/AgentRecoveryService.java` | 新增 | 扫描候选 + 逐条编排 |
| 3 | `agent/recovery/AgentRecoveryExecutor.java` | 新增 | 接管一轮恢复：绑定 sink / 预注册 RunState / resumeStream / start |
| 4 | `agent/recovery/AgentRecoveryRunner.java` | 新增 | `ApplicationRunner` 启动钩子 |
| 5 | `agent/recovery/AgentResumeTask.java` | 新增 | 装载对象 |
| 5.1 | `service/config/ChatTaskConfiguration.java` | 改 | `chatTaskExecutor` 改缓存池；新增 `agentRecoveryTaskExecutor`；统一线程工厂 |
| 6 | `session/domain/RecoverableSessionPojo.java` | 新增 | 候选查询结果对象 |
| 7 | `session/dao/AgentSessionMapper.java` | 改 | 加一个 `selectRecoverableSessions` 方法声明 |
| 8 | `resources/mapper/session/AgentSessionMapper.xml`<br>`resources/sqlliteMapper/session/AgentSessionMapper.xml` | 改 | 加对应 `<select>` |
| 9 | `env/EnvironmentContext.java` | 改 | 加 3 个配置读取方法 |
| 10 | `conf/application.yml` + `docker/conf/application.yml` | 改 | 加 `agentforge.agent.recovery.*` |

改动只有「加方法/加配置块」，**没有一处修改既有逻辑**：`IAgentManager`、`StandardReActAgentManager`、`AgentChatTaskService(Impl)`、`activeStops` 全部保持原样。

> MySQL / SQLite 两套 Mapper XML 内容目前一致，必须同步改，否则切换 `AGENTFORGE_DB_TYPE` 会启动期报 `BindingException`。

---

## 八、怎么验证

```bash
# 1) 发起一轮长任务（让 Agent 连续调多次工具），等消息进 RUNNING
# 2) 确认库里留下现场
mysql -e "SELECT s.session_id, s.status, m.id, m.status, m.execution_id
          FROM ai_agent_session s JOIN ai_agent_session_message m ON m.session_id = s.id
          WHERE m.role_type = 2 ORDER BY m.id DESC LIMIT 3;"
# 期望：m.status = 2，m.execution_id 非空，s.status = 1

# 3) 强杀，确保不是优雅停机
kill -9 <pid>

# 4) 重启，看日志
#    [AgentRecovery] 窗口=5天, 命中候选=1
#    [AgentRecovery] sessionId=..., aid=..., aiMessageId=..., sourceExecutionId=...
#    [AgentRecovery] 完成 { scanned=1, resumed=1, failed=0 }

# 5) 校验结果
#    - 该消息 status 最终为 5(SUCCEEDED)，s.status 为 2
#    - events 有新增事件；前端打开会话能看到完整回答
#    - 记忆里没有多出一条 UserMessage（确认走的是 resume 不是 submit）
```

必测回归：

| 场景 | 期望 |
| --- | --- |
| 崩溃在工具执行中 | `MessageChainRepairer` 补 synthetic 结果，恢复后继续，不报 400 |
| 崩溃在模型刚返回文本后 | 恢复继续 ReAct，不重复插 UserMessage |
| 消息是中断态（`WAITING_HITL 8`） | 进不了候选集（`status IN (1,2)`），不恢复，用户提交审批后仍走 `resumeInterrupt` |
| 该消息已在运行注册表 | `isRunning` 返回 true，跳过，不重复起一轮 |
| 同一 messageId 重复投递 | `register` 返回 false，第二轮自行放弃 |
| 用户已点停止（`CANCELLING 7`） | 不在候选集（`status IN (1,2)`），不复活 |
| Agent 已停止发布 | 记 `failed`，不抛异常，不影响启动 |
| 记忆为空 | `prepareResume` 抛异常 → `finishWithError` → 消息 `FAILED`，不卡 RUNNING |
| 尾部历史早已完成 | `CompletedTokenStream` → `closeIfStillRunning` 收口为 `SUCCEEDED` |

排障三问：

1. 消息仍卡 `RUNNING` → 先看有没有 `registerRun`（没预注册就会走 extension 兜底并静默跳过）。
2. 恢复后多了一条 UserMessage → 误走了 `submit`。
3. 日志 `命中候选=0` → 查 `status` / `modify_time` / `execution_id` 三个条件哪个没满足；若日志出现「正在执行中，跳过」而实际没在跑，说明 `CacheExecuteManager` 里有残留注册（收口路径漏了 `unregister`）。

---

## 九、这个注册表以后怎么复用

`CacheExecuteManager` 不是为恢复单独造的，它是「谁在跑」的唯一登记处。以后新增执行入口时，照这三步走即可：

```java
// 任何执行入口（页面会话 / HITL / 定时任务 / 启动恢复）
if (!executeManager.register(sessionId, messageId, executionId, cancelHandle)) {
    return;   // 已经在跑，直接放弃
}
try {
    // ... 绑定 sink、预注册 RunState、start()
} finally {
    // 收口：成功、失败、取消、异常，四条路都要走到
    executeManager.unregister(messageId);
}
```

| 场景 | 怎么接 |
| --- | --- |
| 启动恢复（本次） | 先 `isRunning` 判断，再交 `AgentRecoveryExecutor.resume(...)` |
| 定时任务（后续） | 提交前 `register`，跑完 `unregister`，模式与执行器完全一样 |
| 页面会话（后续可选） | 想让 `isRunning` 覆盖页面会话，只需在 `AgentChatTaskServiceImpl#registerRun` 里补一行 `register`，不动其它逻辑 |
| HITL 恢复（后续可选） | 同上，在 `resume` 私有方法里补一行 |

**当前这一步先不动页面会话**：`submit`/`stop` 继续用原来的 `activeStops`，恢复组件用 `CacheExecuteManager`，两套并存互不影响。所以此刻 `isRunning` 只反映「恢复组件管的那些执行」——这恰好就是恢复需要的语义（重启后它一定是空的）。

等以后把页面会话也接进来时，`isRunning` 才会变成全局视图。那时如果希望恢复前的判断更严格，把它换成「`CacheExecuteManager.isRunning` 或 `AgentRunRegistry` 有活跃执行」的双查即可，仍然是一行改动。

---

## 附录：框架恢复能力速查

```java
// 服务侧最小调用
TokenStream stream = agent.resumeStream(memoryId, sourceExecutionId);
stream.onCompleteResponse(...).onError(...).ignoreErrors().start();
```

`prepareResume` 的判定链（`Agent.java:376-448`）：

```text
memoryId 为空                      → AgentException
executionId 空/空白                → AgentException
同 memoryId 有非终态活跃执行        → AgentException（防同会话双跑）
来源执行快照存在但非终态            → AgentException（重启后快照为空，跳过该检查）
ChatMemoryProvider / 记忆为空       → AgentException
尾部是真实 AI 回答且无工具调用      → ResumePlan.completed（不调模型，返回 CompletedTokenStream）
存在悬空工具调用                   → MessageChainRepairer 补 synthetic 结果
否则                              → 新 executionId + question=尾部用户文本
                                   + extensions(resume=true, resumeSource=RUN_STATE,
                                                previousExecutionId=来源ID)
                                   + prepareMessagesForResume（重建 SystemMessage）
```

> **一句话：框架负责「怎么续」，服务负责「续谁、什么时候续」。判定锚点是消息的 `execution_id`，注册的是 `aid+channel` 粒度的 Agent 实例，续跑必须走 `resumeStream`。**
