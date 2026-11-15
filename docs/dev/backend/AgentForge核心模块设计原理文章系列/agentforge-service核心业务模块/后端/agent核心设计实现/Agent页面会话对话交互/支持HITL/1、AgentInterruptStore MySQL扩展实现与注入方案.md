# AgentInterruptStore MySQL 扩展实现与 PermissionInterruptModule 注入方案

## 结论

首期新增 Service 侧 `MysqlAgentInterruptStore`，实现 Core 的 `AgentInterruptStore` SPI，并把同一个 Store 实例注入 `PermissionInterruptModule`：

- 新增表 `ai_agent_interrupt_snapshot`，只保存 Framework 冻结快照和 3 个状态（`PENDING / RESUMING / COMPLETED`）；
- `status` 列是唯一权威状态，`snapshot_json` 只保存冻结列表，不重复保存可落列字段；
- `claim` 使用单条条件更新完成 CAS，重复恢复由数据库唯一约束和状态条件兜底；
- `AgentConfiguration` 暴露单例 `AgentInterruptStore`，`ReActAgentFactory` 构造 `PermissionInterruptModule` 时调用 `.interruptStore(store)`；
- Core 只增加一个 `AskQuestion` 公开构造器，用于按冻结 payload 反序列化，不改 SPI、不改状态机；
- Service 侧 `ai_agent_interrupt_batch`（页面归属、`messageId`、用户、UI 重建）继续作为二期独立表，不与 Framework 快照混表。

这样做的收益是：快照可跨进程重启保留、可审计、可检测重复消费；但“跨重启直接恢复执行”仍受 Service 侧 Runtime、RunState 与 ChatMemory 限制，本方案不承诺跨重启恢复闭环。

---

## 一、范围与边界

### 1.1 本方案解决

1. 进程重启后 Framework 冻结快照仍然存在，不再因 `InMemoryAgentInterruptStore` 丢失；
2. 同一 `batchId` 在数据库层只允许一次 `PENDING -> RESUMING` 抢占；
3. 多节点共享数据库时，至少能保证“同一批次不会被两个节点同时消费”；
4. 中断批次具备持久审计能力，可查询、可对账、可清理；
5. Runtime 重建但进程未重启时，新 `PermissionInterruptModule` 实例可以继续消费旧批次。

### 1.2 本方案不解决

- 不把 Service 的 `messageId`、`sessionId`、`userId`、`is_debug`、页面展示 JSON 放进 Framework 快照表；
- 不持久化恢复响应和恢复结果；
- 不实现跨重启、跨节点自动恢复执行；
- 不改变 `AgentInterruptStore` 的 4 个方法签名；
- 不删除或替换 `InMemoryAgentInterruptStore`，它仍是 Core 的默认实现和单元测试实现。

### 1.3 为什么不直接把 Service 批次表当 Store 用

`ai_agent_interrupt_batch` 在 HITL 方案中承担的是 Service 语义：归属、`messageId`、事件重建、`FAILED/CANCELLED/EXPIRED`、重试状态。Framework 的 `AgentInterruptSnapshot` 只认识 `PENDING/RESUMING/COMPLETED`，并且恢复时必须读到完整冻结对象。

两者混表会产生三类问题：

1. 状态枚举被迫混合，Core 看到未知状态时无法 fail-closed；
2. Service 在挂起前就需要写入归属字段，而 Framework 快照在 `preflight` 阶段已经保存，写入顺序和事务边界不一致；
3. 二期页面重建 JSON 需要脱敏，而 Framework 快照必须保留原始参数，安全边界不同。

因此拆成两张表：`ai_agent_interrupt_snapshot` 服务 Framework SPI，`ai_agent_interrupt_batch` 服务页面与业务。二者通过 `batch_id` 关联，但各自演进。

---

## 二、现状审查

### 2.1 Core SPI

`agentforge-agent-core` 当前接口：

```java
public interface AgentInterruptStore {
    void save(AgentInterruptSnapshot snapshot);

    AgentInterruptSnapshot get(String batchId);

    void claim(String batchId);

    void complete(String batchId);
}
```

默认实现 `InMemoryAgentInterruptStore` 的语义是本次实现必须对齐的基线：

| 方法 | 现有内存语义 |
| --- | --- |
| `save` | 首次插入；重复 `batchId` 抛 `IllegalStateException("interrupt batch already exists: ...")` |
| `get` | 不存在抛 `IllegalArgumentException("interrupt batch not found: ...")` |
| `claim` | `COMPLETED` 抛 `already completed`；`RESUMING` 抛 `is being resumed`；其余推进为 `RESUMING` |
| `complete` | 存在则直接置为 `COMPLETED`；不存在静默忽略 |

`AgentInterruptSnapshot` 的不可变字段：

```text
batchId
memoryId                // Object；Service 侧实际为 sessionId String
sourceExecutionId
step
status                  // PENDING / RESUMING / COMPLETED
toolRequests            // 本轮全部 Tool Call，保持原始顺序
interrupts              // 需要外部响应或 AskUser 的冻结项
denials                 // 权限引擎直接拒绝项
```

### 2.2 现有装配

`ReActAgentFactory` 当前只在 `askUserEnabled=true` 时创建模块：

```java
if (standardAgentConfig.isAskUserEnabled()) {
    builder.permissionInterruptModule(
            PermissionInterruptModule.builder()
                    .activePermissionMode(PermissionMode.DEFAULT)
                    .enableAskUser()
                    .build());
}
```

由于没有调用 `.interruptStore(...)`，模块内部使用：

```java
this.store = builder.store == null ? new InMemoryAgentInterruptStore() : builder.store;
```

即每个 Agent Runtime 各自持有一份进程内 Store，重启即丢失。

### 2.3 Service 侧双状态机现状

Service 目前有独立的 `AgentInterruptRuntimeRegistry`，负责：

- `PENDING -> RESUMING -> COMPLETED/FAILED/CANCELLED`；
- 归属校验、`messageId`、Runtime/Agent 引用、事件绑定；
- 恢复请求的准入控制。

引入持久化 Store 后会出现两套 CAS：

| 层次 | 状态机 | 作用 |
| --- | --- | --- |
| Service | `AgentInterruptRuntimeRegistry` | 业务准入、归属、事件、Runtime 定位 |
| Framework | `AgentInterruptStore` | 冻结快照消费、防止重复执行工具 |

两者不能互相替代。正确顺序是：**Service 先抢占，Framework 再抢占**；Framework 抢占失败时必须把 Service 批次置为失败并发出 `interrupt_resume_failed`，不能让页面停在 `RESUMING`。

---

## 三、关键设计决策

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| D1 | 新建 `ai_agent_interrupt_snapshot`，不复用 `ai_agent_interrupt_batch` | Framework 与 Service 状态、生命周期、安全边界不同 |
| D2 | `status` 落列并作为唯一权威；JSON 不保存 `status/batchId/memoryId/step` | 避免同一状态两处存储产生漂移 |
| D3 | `snapshot_json` 保存 `toolRequests/interrupts/denials` 全部冻结内容 | 恢复只消费快照，不重新生成等待对象 |
| D4 | ASK_USER 的 `payload` 原样持久化，不在恢复时重新解析 Tool 参数 | 防止升级后解析规则变化导致问题漂移 |
| D5 | Core 仅给 `AskQuestion` 增加公开全参构造器 | 反序列化需要，无 SPI 变更，风险最小 |
| D6 | `claim` 使用 `UPDATE ... WHERE status='PENDING'`，不要求调用方传版本号 | 对齐现有 SPI；单条 UPDATE 已具备原子性 |
| D7 | `version` 仅用于审计和后续乐观锁扩展 | `claim` 的互斥由 `status` 条件保证 |
| D8 | Store 以单例 Bean 注入所有 `PermissionInterruptModule` | 多 Runtime 共享同一持久层，`batchId` 全局唯一 |
| D9 | `memoryId` 仅支持 `String` | Service 侧 `memoryId` 就是 `sessionId`，明确契约比泛型序列化可靠 |
| D10 | 反序列化失败、未知枚举、未知 schemaVersion 一律 fail-closed | 与权限系统“不可恢复时禁止恢复”一致 |

### 3.1 命名说明

类名沿用现有 Service 习惯使用 `MysqlAgentInterruptStore`（与 `MysqlUserMessageAppendStore` 一致），但实现同时支持 MySQL 与 SQLite：

- MySQL 使用 `resources/mapper/agent/AgentInterruptSnapshotMapper.xml`；
- SQLite 使用 `resources/sqlliteMapper/agent/AgentInterruptSnapshotMapper.xml`；
- 两份 XML 的 `namespace`、`id`、参数名完全一致，只有 DDL 分方言维护。

---

## 四、数据模型与增量 SQL

### 4.1 表结构

`ai_agent_interrupt_snapshot`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT | 主键 |
| `batch_id` | VARCHAR(80) | Framework 批次 ID，唯一 |
| `memory_id` | VARCHAR(128) | 会话 ID，对应 `AgentRequest.memoryId` |
| `source_execution_id` | VARCHAR(64) | 产生中断的执行 ID |
| `step` | INT | ReAct 步数 |
| `status` | VARCHAR(16) | `PENDING / RESUMING / COMPLETED`，CAS 权威列 |
| `schema_version` | INT | 快照 JSON 版本，首期固定 1 |
| `snapshot_json` | LONGTEXT | 冻结列表 JSON，见第五章 |
| `version` | BIGINT | 状态推进次数，审计与后续乐观锁 |
| `claimed_at` | DATETIME | 抢占时间 |
| `resolved_at` | DATETIME | 完成时间 |
| `create_time` | DATETIME | 创建时间 |
| `modify_time` | DATETIME | 更新时间 |

索引（首期最小集合）：

- `PRIMARY KEY (id)`：主键；
- `uk_batch_id(batch_id)`：防止重复保存。

首期不建 `idx_memory_status(memory_id, status)` 与 `idx_status_modify(status, modify_time)`。当前恢复入口只按 `batch_id` 精确查询，两列索引没有实际读取路径；等二期页面待办列表、过期扫描或清理任务落地时，再随查询语句一并补索引，避免过早承担索引维护成本。

### 4.2 MySQL 增量 SQL

文件：`agentforge-service/sql/increment/1.0.x/20261115000000_v1.0.x.sql`

```sql
-- 2026.11.15：新增 Agent 中断冻结快照表，支撑 AgentInterruptStore 持久化与批次 CAS
USE `agentforge`;

CREATE TABLE IF NOT EXISTS `ai_agent_interrupt_snapshot` (
    `id`                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `batch_id`            VARCHAR(80) NOT NULL COMMENT 'Framework 中断批次ID',
    `memory_id`           VARCHAR(128) NOT NULL COMMENT '会话ID，对应 AgentRequest.memoryId',
    `source_execution_id` VARCHAR(64)  NOT NULL COMMENT '产生中断的 Agent 执行ID',
    `step`                INT         NOT NULL COMMENT '产生中断时的 ReAct 步数',
    `status`              VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '批次状态：PENDING/RESUMING/COMPLETED',
    `schema_version`      INT         NOT NULL DEFAULT 1 COMMENT '快照JSON结构版本',
    `snapshot_json`       LONGTEXT    NOT NULL COMMENT '冻结的工具调用、中断项与拒绝项JSON',
    `version`             BIGINT      NOT NULL DEFAULT 0 COMMENT '状态推进次数，用于审计与后续乐观锁',
    `claimed_at`          DATETIME    DEFAULT NULL COMMENT '批次被抢占为 RESUMING 的时间',
    `resolved_at`         DATETIME    DEFAULT NULL COMMENT '批次完成为 COMPLETED 的时间',
    `create_time`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_batch_id` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent中断冻结快照表';
```

### 4.3 SQLite 增量 SQL

文件：`agentforge-service/sql/sqllite/1.0.x/20261115000000_v1.0.x.sql`

```sql
-- 2026.11.15：新增 Agent 中断冻结快照表（SQLite）
CREATE TABLE IF NOT EXISTS ai_agent_interrupt_snapshot (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    batch_id            TEXT    NOT NULL,
    memory_id           TEXT    NOT NULL,
    source_execution_id TEXT    NOT NULL,
    step                INTEGER NOT NULL,
    status              TEXT    NOT NULL DEFAULT 'PENDING',
    schema_version      INTEGER NOT NULL DEFAULT 1,
    snapshot_json       TEXT    NOT NULL,
    version             INTEGER NOT NULL DEFAULT 0,
    claimed_at          DATETIME DEFAULT NULL,
    resolved_at         DATETIME DEFAULT NULL,
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (batch_id)
);
```

### 4.4 SQL 发布顺序

1. 先执行增量 SQL，再发布 Service；
2. MySQL 走 `bin/local/upgrate-sql.sh`，SQLite 走 `bin/local/upgrate-sqlite.sh`；
3. `sql/local/**` 与 `docker/data/sql-applied/**` 是执行归档，不手工新增；
4. 回滚只需下线 Store Bean，表可保留；保留数据不会影响 `InMemoryAgentInterruptStore` 回退。

---

## 五、序列化契约

### 5.1 总体规则

`snapshot_json` 只保存下列内容：

```json
{
  "schemaVersion": 1,
  "toolRequests": [
    {
      "id": "call_07",
      "name": "ask_user",
      "arguments": "{\"questions\":[...]}"
    }
  ],
  "interrupts": [
    {
      "interruptId": "int_xxx",
      "type": "ASK_USER",
      "toolRequest": {
        "id": "call_07",
        "name": "ask_user",
        "arguments": "{\"questions\":[...]}"
      },
      "payload": {
        "toolCallId": "call_07",
        "toolName": "ask_user",
        "questions": [
          {
            "id": "env",
            "title": "请选择部署环境",
            "description": null,
            "type": "SINGLE_CHOICE",
            "required": true,
            "options": [
              {"id": "test", "label": "测试", "description": null},
              {"id": "prod", "label": "生产", "description": null}
            ],
            "minSelections": null,
            "maxSelections": null,
            "maxLength": null,
            "placeholder": null
          }
        ],
        "reason": "发布前需要确认",
        "submitLabel": "继续发布"
      }
    }
  ],
  "denials": [
    {
      "toolRequest": {
        "id": "call_08",
        "name": "shell",
        "arguments": "{\"command\":\"rm -rf demo\"}"
      },
      "reason": "tool call denied by permission policy: shell"
    }
  ]
}
```

约束：

1. `schemaVersion` 同时落列和 JSON，二者必须一致；不一致按损坏数据处理；
2. `toolRequests` 必须保持模型生成的原始顺序；
3. `interrupts` 必须保持 `preflight` 生成顺序；
4. `arguments` 原样保存字符串，不解析、不重排、不脱敏；
5. `PERMISSION_APPROVAL` 的 `payload` 固定为 `null`；
6. `ASK_USER` 的 `payload` 必须是非空对象，且 `payload.toolCallId` 与 `toolRequest.id` 一致；
7. 每个 `interrupt.toolRequest` 必须能在 `toolRequests` 中找到 `equals` 项；找不到视为快照损坏；
8. 每个 `denial.toolRequest` 同样必须能在 `toolRequests` 中找到；
9. 未知 `type`、未知问题类型、未知状态、未知 `schemaVersion` 一律抛出 `IllegalStateException`，禁止降级恢复。

### 5.2 为什么 payload 要原样保存

如果恢复时用当前版本的 `AskUserSupport.parse(toolRequest)` 重建 `AskUserRequest`，一旦解析规则升级（例如默认值、别名、校验规则变化），用户看到一个版本的问题，Framework 却按另一个版本校验答案，违反“中断对象不可漂移”。

因此：

- 优先方案：持久化 payload，按持久化内容重建；
- 兼容方案：仅当历史数据缺少 payload 时才允许回退到 `AskUserSupport.parse`，并在 Codec 注释和日志中明确标记 `legacy payload rebuilt`。

### 5.3 Core 最小改动

`AskQuestion` 当前只有无参构造器，字段为包级私有，Service 无法在包外按持久化内容重建。建议新增公开构造器：

```java
public AskQuestion(
        String id,
        String title,
        String description,
        AskUserAnswerType type,
        Boolean required,
        List<AskUserOption> options,
        Integer minSelections,
        Integer maxSelections,
        Integer maxLength,
        String placeholder) {
    this.id = id;
    this.title = title;
    this.description = description;
    this.type = type;
    this.required = required;
    this.options = options;
    this.minSelections = minSelections;
    this.maxSelections = maxSelections;
    this.maxLength = maxLength;
    this.placeholder = placeholder;
}
```

保留现有无参构造器给 Tool Schema 反射使用。该改动不涉及 SPI、不涉及状态机、不改变解析与校验行为。

`AskUserOption` 已有公开构造器，无需改动。

---

## 六、Java 组件实现

### 6.1 文件清单

```text
agentforge-service/agentforge-service-backend/agentforge-service-web/src/main/java/
  cloud/changlu/agentforge/service/modules/agent/interrupt/store/mysql/
    AgentInterruptSnapshotPojo.java
    AgentInterruptSnapshotMapper.java
    AgentInterruptSnapshotCodec.java
    MysqlAgentInterruptStore.java
  cloud/changlu/agentforge/service/modules/agent/config/
    InterruptStoreConfiguration.java

agentforge-service/agentforge-service-backend/agentforge-service-web/src/main/resources/
  mapper/agent/AgentInterruptSnapshotMapper.xml
  sqlliteMapper/agent/AgentInterruptSnapshotMapper.xml
```

### 6.2 Pojo

```java
public class AgentInterruptSnapshotPojo {
    private Long id;
    private String batchId;
    private String memoryId;
    private String sourceExecutionId;
    private Integer step;
    private String status;
    private Integer schemaVersion;
    private String snapshotJson;
    private Long version;
    private Date claimedAt;
    private Date resolvedAt;
    private Date createTime;
    private Date modifyTime;

    // getter/setter 省略
}
```

### 6.3 Mapper 接口

```java
@Mapper
public interface AgentInterruptSnapshotMapper {
    int insert(AgentInterruptSnapshotPojo pojo);

    AgentInterruptSnapshotPojo selectByBatchId(@Param("batchId") String batchId);

    int claim(@Param("batchId") String batchId, @Param("now") Date now);

    int complete(@Param("batchId") String batchId, @Param("now") Date now);
}
```

### 6.4 Mapper XML

MySQL 与 SQLite 内容一致，分别放在两个目录：

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="cloud.changlu.agentforge.service.modules.agent.interrupt.store.mysql.AgentInterruptSnapshotMapper">

    <resultMap id="SnapshotResult"
               type="cloud.changlu.agentforge.service.modules.agent.interrupt.store.mysql.AgentInterruptSnapshotPojo">
        <id property="id" column="id"/>
        <result property="batchId" column="batch_id"/>
        <result property="memoryId" column="memory_id"/>
        <result property="sourceExecutionId" column="source_execution_id"/>
        <result property="step" column="step"/>
        <result property="status" column="status"/>
        <result property="schemaVersion" column="schema_version"/>
        <result property="snapshotJson" column="snapshot_json"/>
        <result property="version" column="version"/>
        <result property="claimedAt" column="claimed_at"/>
        <result property="resolvedAt" column="resolved_at"/>
        <result property="createTime" column="create_time"/>
        <result property="modifyTime" column="modify_time"/>
    </resultMap>

    <sql id="columns">
        id, batch_id, memory_id, source_execution_id, step, status, schema_version,
        snapshot_json, version, claimed_at, resolved_at, create_time, modify_time
    </sql>

    <insert id="insert" useGeneratedKeys="true" keyProperty="id" keyColumn="id">
        insert into ai_agent_interrupt_snapshot
            (batch_id, memory_id, source_execution_id, step, status, schema_version,
             snapshot_json, version, create_time, modify_time)
        values
            (#{batchId}, #{memoryId}, #{sourceExecutionId}, #{step}, #{status}, #{schemaVersion},
             #{snapshotJson}, #{version}, #{createTime}, #{modifyTime})
    </insert>

    <select id="selectByBatchId" resultMap="SnapshotResult">
        select <include refid="columns"/>
        from ai_agent_interrupt_snapshot
        where batch_id = #{batchId}
    </select>

    <update id="claim">
        update ai_agent_interrupt_snapshot
        set status = 'RESUMING',
            version = version + 1,
            claimed_at = #{now},
            modify_time = #{now}
        where batch_id = #{batchId}
          and status = 'PENDING'
    </update>

    <update id="complete">
        update ai_agent_interrupt_snapshot
        set status = 'COMPLETED',
            version = version + 1,
            resolved_at = #{now},
            modify_time = #{now}
        where batch_id = #{batchId}
          and status &lt;&gt; 'COMPLETED'
    </update>
</mapper>
```

### 6.5 Codec

Codec 负责 Java 对象与 `snapshot_json` 的互转，是持久化正确性的核心。

```java
public final class AgentInterruptSnapshotCodec {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public AgentInterruptSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy();
    }

    public int schemaVersion() {
        return CURRENT_SCHEMA_VERSION;
    }

    public String memoryId(Object memoryId) {
        if (memoryId == null) {
            throw new IllegalArgumentException("interrupt snapshot memoryId must not be null");
        }
        if (!(memoryId instanceof String)) {
            throw new IllegalArgumentException(
                    "interrupt snapshot memoryId must be String, actual: "
                            + memoryId.getClass().getName());
        }
        return (String) memoryId;
    }

    public String encode(AgentInterruptSnapshot snapshot) {
        StoredSnapshot stored = new StoredSnapshot();
        stored.setSchemaVersion(CURRENT_SCHEMA_VERSION);
        stored.setToolRequests(toStoredRequests(snapshot.getToolRequests()));
        stored.setInterrupts(toStoredInterrupts(snapshot.getInterrupts()));
        stored.setDenials(toStoredDenials(snapshot.getDenials()));
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "interrupt snapshot encode failed: " + snapshot.getBatchId(), e);
        }
    }

    public AgentInterruptSnapshot decode(AgentInterruptSnapshotPojo pojo) {
        try {
            StoredSnapshot stored =
                    objectMapper.readValue(pojo.getSnapshotJson(), StoredSnapshot.class);
            if (stored.getSchemaVersion() == null
                    || stored.getSchemaVersion() != pojo.getSchemaVersion()
                    || stored.getSchemaVersion() != CURRENT_SCHEMA_VERSION) {
                throw new IllegalStateException(
                        "unsupported interrupt snapshot schemaVersion: "
                                + stored.getSchemaVersion());
            }
            List<ToolExecutionRequest> toolRequests =
                    toToolRequests(require(stored.getToolRequests(), "toolRequests"),
                            pojo.getBatchId());
            List<InterruptRequest> interrupts =
                    toInterrupts(require(stored.getInterrupts(), "interrupts"),
                            toolRequests, pojo.getBatchId());
            List<PermissionDenial> denials =
                    toDenials(stored.getDenials(), toolRequests, pojo.getBatchId());
            return new AgentInterruptSnapshot(
                    pojo.getBatchId(),
                    pojo.getMemoryId(),
                    pojo.getSourceExecutionId(),
                    pojo.getStep(),
                    status(pojo.getStatus(), pojo.getBatchId()),
                    toolRequests,
                    interrupts,
                    denials);
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "interrupt snapshot decode failed: " + pojo.getBatchId(), e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "interrupt snapshot decode failed: " + pojo.getBatchId(), e);
        }
    }

    public InterruptBatchStatus status(String value, String batchId) {
        try {
            return InterruptBatchStatus.valueOf(value);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "unknown interrupt batch status '" + value + "' for batch: " + batchId, e);
        }
    }

    // toStoredXxx / toXxx / require / 校验逻辑省略
}
```

Codec 实现要点：

1. `objectMapper.copy()`，避免影响全局 ObjectMapper 配置；
2. 读写 DTO 与 Core 领域对象分离，DTO 只负责 JSON 形状；
3. 反序列化后先做结构性校验，再构造 `AgentInterruptSnapshot`；
4. 所有错误统一包装为 `IllegalStateException`，保留 `batchId` 便于定位；
5. `PERMISSION_APPROVAL` 的 payload 必须为 `null`；若历史数据写入非空 payload，忽略还是失败？首期选择失败，避免未知语义；
6. `ASK_USER` payload 重建 `AskUserRequest`，问题 `type` 使用 `AskUserAnswerType.valueOf`，未知值失败；
7. `denials` 允许为空列表，但禁止 `null`；缺失按损坏处理。

### 6.6 Store 实现

```java
public final class MysqlAgentInterruptStore implements AgentInterruptStore {
    private final AgentInterruptSnapshotMapper mapper;
    private final AgentInterruptSnapshotCodec codec;

    public MysqlAgentInterruptStore(
            AgentInterruptSnapshotMapper mapper, AgentInterruptSnapshotCodec codec) {
        this.mapper = mapper;
        this.codec = codec;
    }

    @Override
    public void save(AgentInterruptSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("interrupt snapshot must not be null");
        }
        String batchId = snapshot.getBatchId();
        Date now = new Date();
        AgentInterruptSnapshotPojo pojo = new AgentInterruptSnapshotPojo();
        pojo.setBatchId(batchId);
        pojo.setMemoryId(codec.memoryId(snapshot.getMemoryId()));
        pojo.setSourceExecutionId(snapshot.getSourceExecutionId());
        pojo.setStep(snapshot.getStep());
        pojo.setStatus(snapshot.getStatus().name());
        pojo.setSchemaVersion(codec.schemaVersion());
        pojo.setSnapshotJson(codec.encode(snapshot));
        pojo.setVersion(0L);
        pojo.setCreateTime(now);
        pojo.setModifyTime(now);
        try {
            mapper.insert(pojo);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("interrupt batch already exists: " + batchId, e);
        }
    }

    @Override
    public AgentInterruptSnapshot get(String batchId) {
        AgentInterruptSnapshotPojo pojo = mapper.selectByBatchId(batchId);
        if (pojo == null) {
            throw new IllegalArgumentException("interrupt batch not found: " + batchId);
        }
        return codec.decode(pojo);
    }

    @Override
    public void claim(String batchId) {
        if (mapper.claim(batchId, new Date()) == 1) {
            return;
        }
        AgentInterruptSnapshotPojo current = mapper.selectByBatchId(batchId);
        if (current == null) {
            throw new IllegalArgumentException("interrupt batch not found: " + batchId);
        }
        InterruptBatchStatus status = codec.status(current.getStatus(), batchId);
        if (status == InterruptBatchStatus.COMPLETED) {
            throw new IllegalStateException("interrupt batch already completed: " + batchId);
        }
        if (status == InterruptBatchStatus.RESUMING) {
            throw new IllegalStateException("interrupt batch is being resumed: " + batchId);
        }
        throw new IllegalStateException(
                "interrupt batch claim conflict, status=" + status + ", batch=" + batchId);
    }

    @Override
    public void complete(String batchId) {
        mapper.complete(batchId, new Date());
    }
}
```

说明：

- `DataIntegrityViolationException` 覆盖 MySQL 与 SQLite 的唯一键冲突翻译；比只捕获 `DuplicateKeyException` 更稳；
- `claim` 的 0 行结果再做一次状态查询，是为了给出与内存实现一致的异常文案；
- `complete` 与内存实现一致：批次存在就收口；不存在静默忽略；已经是 `COMPLETED` 时不重复递增版本；
- 所有方法都是单条 SQL，不需要额外事务；Spring/MyBatis 会保证单语句原子性。

---

## 七、注入 PermissionInterruptModule

### 7.1 Store Bean

新建 `InterruptStoreConfiguration`：

```java
@Configuration
public class InterruptStoreConfiguration {

    @Bean
    public AgentInterruptStore agentInterruptStore(
            AgentInterruptSnapshotMapper mapper, ObjectMapper objectMapper) {
        return new MysqlAgentInterruptStore(
                mapper, new AgentInterruptSnapshotCodec(objectMapper));
    }
}
```

放在 `modules/agent/config` 下，与 `UserMessageAppendConfiguration`、`TodoPlanConfiguration` 保持一致。

### 7.2 ReActAgentFactory 修改

构造器增加一个参数：

```java
private final AgentInterruptStore agentInterruptStore;

public ReActAgentFactory(
        // ... 现有参数
        AgentInterruptStore agentInterruptStore) {
    // ... 现有赋值
    this.agentInterruptStore = agentInterruptStore;
}
```

模块装配改为：

```java
if (standardAgentConfig.isAskUserEnabled()) {
    builder.permissionInterruptModule(
            PermissionInterruptModule.builder()
                    .activePermissionMode(PermissionMode.DEFAULT)
                    .interruptStore(agentInterruptStore)
                    .enableAskUser()
                    .build());
}
```

注入后的行为：

1. 所有 Agent Runtime 共用同一个 Store Bean；
2. `preflight` 保存快照到数据库，而不是模块私有内存；
3. 恢复时 `resolve` 从数据库读取快照，`claim` 走数据库 CAS；
4. 未开启 AskUser 的 Agent 仍不创建模块，不产生任何快照行；
5. 未来新增权限规则装配路径时，必须复用同一个 `agentInterruptStore` Bean，禁止在 Service 内重新 `new MysqlAgentInterruptStore` 或退回内存实现。

### 7.3 不要做的事

- 不要在每次 `createReActAgent` 时创建新的 Store 实例；Store 是无状态单例，Mapper 由 MyBatis 管理；
- 不要把 `AgentInterruptSnapshotMapper` 直接注入 `PermissionInterruptModule`，Core 不依赖 Service；
- 不要为不同 Agent 使用不同的表或不同 schema，`batchId` 已全局唯一；
- 不要让 `getInterruptStore()` 暴露给 Controller，恢复入口仍然只能走 `resumeInterruptStream`。

---

## 八、状态机、并发与异常

### 8.1 状态流转

```text
preflight
  -> INSERT status=PENDING, version=0
  -> 发布 interrupt_requested
  -> invocation SUSPENDED

resume
  -> Service Registry claim（PENDING -> RESUMING）
  -> Framework resolve: get + validate + store.claim
       UPDATE ... WHERE status='PENDING'
       成功 -> status=RESUMING, version+1, claimed_at=now
       失败 -> 抛出 already completed / being resumed
  -> apply 冻结批次，写入 Tool Result
  -> store.complete: status=COMPLETED, version+1, resolved_at=now
  -> onAfterInterruptResume -> Service Registry COMPLETED
```

### 8.2 并发恢复

| 场景 | 结果 |
| --- | --- |
| 两个线程同时 `claim` | 只有一个 `UPDATE` 返回 1；另一个查出 `RESUMING` 并失败 |
| 已完成后再恢复 | `UPDATE` 返回 0；查出 `COMPLETED` 并失败 |
| 同一请求重复点击 | Service Registry 先拦截；即使穿透到 Framework，数据库 CAS 仍拦截 |
| `complete` 重复调用 | `status <> 'COMPLETED'` 条件保证只有第一次生效 |
| `get` 与 `claim` 之间被其他节点抢占 | `get` 成功但 `claim` 失败，调用方按恢复失败处理 |

### 8.3 异常映射

| Framework 异常 | Service 事件 | 页面处理 |
| --- | --- | --- |
| `interrupt batch not found` | `INTERRUPT_BATCH_NOT_FOUND` | 刷新历史，不可重试 |
| `already completed` | `BATCH_ALREADY_CONSUMED` | 刷新事件，不可重复提交 |
| `is being resumed` | `BATCH_RESUMING` | 等待 `interrupt_resuming/resumed` |
| `decode failed` / `unknown status` | `INTERRUPT_BATCH_EXPIRED` | 标记批次损坏，重新发起任务 |
| 其他恢复失败 | `INTERRUPT_RESUME_FAILED` | 默认不可重试，保留错误码 |

注意：Service 目前用 `IllegalArgumentException/IllegalStateException`；上线前建议在 `AgentInterruptApplicationService` 中按异常消息前缀映射错误码，避免把数据库异常直接暴露给前端。

### 8.4 孤儿 `PENDING` 行

用户在等待 HITL 时点击停止，Service 会把 Registry 置为 `CANCELLED`，但 Framework 没有任何“取消快照”的入口，因此数据库里会留下 `PENDING` 行。

首期处理原则：

1. 不允许删除或改写快照来伪造业务状态；
2. 孤儿行不会被恢复，因为恢复入口先经过 Service Registry，Registry 已取消或已丢失；
3. 保留行作为审计记录；后续增加清理任务，按 `status='PENDING' AND modify_time < now - retention` 归档或删除（清理任务上线时同步补 `idx_status_modify`）；
4. 二期若实现跨重启恢复，必须扩展 SPI 增加 `cancel/expire`，并在 Service 取消时同步调用；在那之前不要在 `InterruptBatchStatus` 中新增状态。

---

## 九、与 Service RuntimeRegistry 的协作

### 9.1 双 CAS 顺序

```text
页面提交 resume
  -> AgentInterruptApplicationService
       1. Registry.requireOwned(batchId, userId)
       2. Registry.claim(batchId)                    // Service 准入
       3. 提交后台恢复任务
  -> agent.resumeInterruptStream(command)
       4. Store.get(batchId)                         // 读取冻结快照
       5. 校验 responses
       6. Store.claim(batchId)                       // Framework 消费
       7. applyInterruptBatch
       8. Store.complete(batchId)
```

强制规则：

- Service 未抢占成功时，不得调用 Framework 恢复；
- Framework 抢占失败时，Service 必须调用 `interruptRegistry.failed(batchId)` 并发出 `interrupt_resume_failed`；
- 不允许先 Framework 抢占、后 Service 抢占，否则会出现“快照已消费但页面仍显示可提交”。

### 9.2 多节点

数据库 CAS 能防止重复消费，但不能让“没有 Runtime 的节点”完成恢复。首期仍需：

- 粘性路由：恢复请求转发到持有该 Agent Runtime 的节点；
- 或者按 `memoryId/sessionId` 固定节点；
- 节点重启后把 Service Registry 中的批次标记为 `EXPIRED`，不要尝试跨重启恢复。

MySQL Store 在多节点下的价值是：即使两个节点误收到同一批次，也只有一个能完成 `claim`；另一个会得到明确的“已消费/正在恢复”。

### 9.3 重启恢复的里程碑

| 阶段 | Store | Service Registry | 能否跨重启恢复 |
| --- | --- | --- | --- |
| 现在 | InMemory | InMemory | 否 |
| 本方案 | MySQL | InMemory | 否，但快照与状态可审计 |
| 二期 | MySQL | MySQL `ai_agent_interrupt_batch` + Runtime 重建 | 视 Runtime/ChatMemory 重建能力而定 |

文档中“跨进程持久化恢复”仍然属于二期，本方案只是把 Framework 快照这一层先持久化。

---

## 十、增量 SQL 文件清单

| 数据库 | 文件 | 说明 |
| --- | --- | --- |
| MySQL | `agentforge-service/sql/increment/1.0.x/20261115000000_v1.0.x.sql` | 手工新增，升级脚本待执行目录 |
| SQLite | `agentforge-service/sql/sqllite/1.0.x/20261115000000_v1.0.x.sql` | 手工新增，升级脚本待执行目录 |

不要手工把同名文件放进：

- `agentforge-service/sql/local/1.0.x/`（MySQL 执行归档）；
- `agentforge-service/sql/local/sqlite/1.0.x/`（SQLite 执行归档）；
- `agentforge-service/docker/data/sql-applied/`（容器运行时归档）。

这些目录由升级脚本在成功执行后写入，手工放入会导致幂等判断失效。

---

## 十一、测试与验收

### 11.1 Codec 单测

1. `PERMISSION_APPROVAL` 快照 round-trip 后，`toolRequests` 顺序与参数完全一致；
2. `ASK_USER` 快照 round-trip 后，问题 `id/title/type/options/required/limit` 完全一致；
3. `denials` round-trip 后，拒绝原因一致；
4. 未知 `schemaVersion` 抛异常；
5. 未知 `InterruptType` 抛异常；
6. 未知问题类型抛异常；
7. `interrupt.toolRequest` 不在 `toolRequests` 中时抛异常；
8. `memoryId` 非 `String` 时 `save` 抛异常。

### 11.2 Store 单测（可用 H2/SQLite 或 Testcontainers MySQL）

1. `save` 后 `get` 能还原；
2. 重复 `save` 抛“already exists”；
3. `get` 不存在抛“not found”；
4. `claim` 后状态为 `RESUMING`，`version=1`，`claimed_at` 非空；
5. 两次 `claim` 第二次抛“being resumed”；
6. `complete` 后状态为 `COMPLETED`，`version=2`，`resolved_at` 非空；
7. `complete` 后再 `claim` 抛“already completed”；
8. `complete` 不存在时静默成功；
9. 两个线程并发 `claim`，只有一个成功。

### 11.3 Service 集成测试

1. 开启 AskUser 的 Agent 产生 `ask_user` 调用后，`ai_agent_interrupt_snapshot` 出现一行 `PENDING`；
2. 同一进程重建 `PermissionInterruptModule`（模拟 Runtime 刷新），使用同一 Store Bean 仍能恢复；
3. 恢复成功后数据库状态为 `COMPLETED`；
4. 恢复失败时 Service 发出 `interrupt_resume_failed`，Registry 为 `FAILED`；
5. 等待态停止后，Registry 为 `CANCELLED`，数据库行仍为 `PENDING`，且不能通过恢复接口消费；
6. 两个节点/两个线程同时提交同一 `batchId`，只有一个恢复成功。

### 11.4 回归边界

- 未开启 AskUser 的 Agent 不产生任何快照行；
- `DONT_ASK` 模式下 `ask_user` 仍走 DENY，不产生快照；
- `PermissionInterruptModuleTest`、`PermissionDeniedFlowTest` 不依赖数据库，继续使用 `InMemoryAgentInterruptStore`；
- 流式与非流式恢复共用同一 Store 行为。

---

## 十二、风险与后续

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 快照包含原始工具参数 | 敏感命令、路径、令牌可能落库 | 限制表访问权限、开启磁盘加密、禁止日志打印 `snapshot_json`、设置保留期 |
| 表持续增长 | 审计数据膨胀 | 按 `status + modify_time` 定期归档/删除（届时补 `idx_status_modify`）；保留期由业务配置 |
| 大参数导致行过大 | 插入失败或复制延迟 | `LONGTEXT` 兜底；监控单行大小；必要时限制 Tool 参数长度 |
| 升级后 payload 结构变化 | 旧批次无法恢复 | `schemaVersion` 门禁；payload 原样保存；升级前回归旧数据读测试 |
| 孤儿 `PENDING` 行 | 审计噪音、后续跨重启误恢复 | 首期靠 Service Registry 拦截；二期增加 `cancel/expire` SPI 与清理任务 |
| 多节点非粘性路由 | 恢复请求打到无 Runtime 节点 | 首期粘性路由；二期持久化 Registry 与 Runtime 重建 |
| SQLite 单写并发 | 高并发恢复时写锁等待 | SQLite 仅用于本地开发；生产使用 MySQL |

后续演进建议：

1. 二期新增 `ai_agent_interrupt_batch`，保存 `messageId/sessionId/aid/userId/is_debug/request_json/response_json/error_*`；
2. 扩展 `AgentInterruptStore` SPI 增加 `cancel(batchId)` / `expire(batchId)` / `findByMemory(memoryId, status)`；
3. 页面待办列表直接查询 Service 批次表，不查询 Framework 快照表；
4. 增加快照保留任务和审计查询接口；
5. 如果需要跨节点恢复，先解决 Runtime 与 ChatMemory 的重建，再谈快照读取。

---

## 十三、实施清单

1. 新增 Core `AskQuestion` 公开全参构造器与单测；
2. 新增 MySQL/SQLite 增量 SQL 各一份；
3. 新增 `AgentInterruptSnapshotPojo`、`AgentInterruptSnapshotMapper`、两份 Mapper XML；
4. 新增 `AgentInterruptSnapshotCodec`，按第五章契约实现编解码与 fail-closed 校验；
5. 新增 `MysqlAgentInterruptStore`，对齐 `InMemoryAgentInterruptStore` 语义；
6. 新增 `InterruptStoreConfiguration` 暴露 `AgentInterruptStore` Bean；
7. 修改 `ReActAgentFactory` 构造器与模块装配，注入 `.interruptStore(agentInterruptStore)`；
8. 补充 Store/Codec 单测与 Service HITL 集成测试；
9. 更新 HITL 方案文档中“默认使用进程内 AgentInterruptStore”的描述，标记本方案为 P3 持久化 Store 的落地实现。