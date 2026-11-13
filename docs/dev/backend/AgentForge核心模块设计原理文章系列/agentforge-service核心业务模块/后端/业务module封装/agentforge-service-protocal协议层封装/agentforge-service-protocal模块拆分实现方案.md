# agentforge-service-protocal 模块拆分实现方案

## 1. 背景与目标

当前 `agentforge-service/agentforge-service-web` 同时承担 Spring Boot 应用、业务实现和前后端流式事件协议定义。随着业务继续增长，协议对象与 Web 实现放在同一模块中，会使协议复用、依赖约束和独立测试变得困难。

本次计划将现有后端目录调整为聚合模块 `agentforge-service-backend`，其下拆分两个子模块：

- `agentforge-service-protocal`：协议层，承载 SSE 事件、协议 DTO、事件枚举和事件构造器。
- `agentforge-service-web`：Spring Boot 可执行应用和其他业务实现，依赖 `agentforge-service-protocal`。

> 命名按当前规划使用 `protocal`，Java 包名仍保持语义正确的 `protocol`。若尚未对外发布，建议实施前再确认是否将 Maven artifact 统一为 `agentforge-service-protocol`，避免长期保留拼写误差。

## 2. 拆分后的目录结构

```text
agentforge-service/
├── pom.xml
├── agentforge-service-ui/
└── agentforge-service-backend/
    ├── pom.xml                         # backend 聚合 POM
    ├── agentforge-service-protocal/
    │   ├── pom.xml
    │   └── src/
    │       ├── main/java/cloud/changlu/agentforge/service/protocol/
    │       └── test/java/cloud/changlu/agentforge/service/protocol/
    └── agentforge-service-web/
        ├── pom.xml
        └── src/                     # 原 web 模块除 protocol 外的内容
```

Maven 聚合关系调整为：

```text
agentforge-service
├── agentforge-service-ui
└── agentforge-service-backend (pom)
    ├── agentforge-service-protocal (jar)
    └── agentforge-service-web (Spring Boot executable jar)
```

依赖方向必须保持单向：

```text
agentforge-service-web  --->  agentforge-service-protocal
```

`agentforge-service-protocal` 不得依赖 `agentforge-service-web`，否则会形成 Maven 循环依赖。

## 3. Maven 调整方案

### 3.1 agentforge-service 聚合层

`agentforge-service/pom.xml` 中将原模块：

```xml
<module>agentforge-service-web</module>
```

替换为：

```xml
<module>agentforge-service-backend</module>
```

`agentforge-service-ui` 保持不变。

### 3.2 agentforge-service-backend 聚合层

新的 `agentforge-service-backend/pom.xml` 仅作为后端聚合与共享配置层：

```xml
<artifactId>agentforge-service-backend</artifactId>
<packaging>pom</packaging>

<modules>
    <module>agentforge-service-protocal</module>
    <module>agentforge-service-web</module>
</modules>
```

建议将当前 Web POM 里的 Spring Boot、MyBatis、MySQL 和 SQLite 版本属性继续留在 Web 模块，直到其他后端子模块确实需要共享再上移，避免协议层被动继承过重的技术栈。

### 3.3 agentforge-service-protocal

该模块产出普通 JAR，不配置 Spring Boot `repackage`。根据当前源码的实际 import，最小依赖为：

- `agentforge-agent-core`：`UserMessageAppendProcessor` 使用 `AppendedUserMessage`。
- `jackson-annotations`：`StreamEventResponse`、`UserMessageAppendProcessor` 使用 `@JsonInclude`。
- JUnit（test scope）：承载迁移后的协议层测试。

不应仅依赖 `spring-boot-starter-web` 的传递依赖来获得 Jackson 注解，协议模块应显式声明自己的编译契约。

### 3.4 agentforge-service-web

Web 模块的 `artifactId` 保持 `agentforge-service-web`，以减少发布脚本、JAR 文件名和运维习惯的额外变更。其 parent 改为 `agentforge-service-backend`，并新增：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-service-protocal</artifactId>
    <version>${revision}</version>
</dependency>
```

Spring Boot 插件只保留在 Web 模块，确保最终仍由 `AgentForgeServiceApplication` 启动。

## 4. 源码迁移范围

将以下整个目录迁入 `agentforge-service-protocal`：

```text
cloud/changlu/agentforge/service/protocol/
├── CompactResult.java
├── StreamEventResponse.java
├── ToolCallResult.java
├── enums/StreamEventEnums.java
└── processor/
    ├── IStreamSceneProcessor.java
    ├── AiErrorProcessor.java
    ├── AiResponseProcessor.java
    ├── AiSessionProcessor.java
    ├── append/UserMessageAppendProcessor.java
    ├── compact/AiCompactProcessor.java
    ├── retry/AiRetryProcessor.java
    ├── think/AiThinkProcessor.java
    └── tool/AiToolCallProcessor.java
```

Java package 不修改，仍为 `cloud.changlu.agentforge.service.protocol...`。因此 Web 模块现有的 import 原则上无需批量替换，变更仅发生在物理源码位置和 Maven 依赖上。

当前唯一直接位于 `protocol` 包下的测试 `AiRetryProcessorTest` 应同步迁入新模块，以保持测试与被测单元归属一致。

## 5. 循环依赖风险与解决方案

### 5.1 现状

`UserMessageAppendProcessor` 位于待迁移的 `protocol` 包，但它依赖 Web 业务包中的：

```text
cloud.changlu.agentforge.service.modules.agent.enums.UserMessageAppendStatus
```

同时 `UserMessageAppendStatus` 又依赖 `StreamEventEnums.TypeEnum`。如果原样迁移，会变成：

```text
web -> protocal -> web
```

Maven 不允许这种循环依赖。

### 5.2 推荐处理

协议层定义事件表达，业务层使用协议映射，而不是协议层识别 Web 业务枚举。建议做如下小范围解耦：

1. `UserMessageAppendProcessor` 的 `queued/effective/deferred/canceled` 方法直接传入对应的协议 `TypeEnum`、状态码和终态标记，或在协议模块内新建一个仅面向传输的追加事件状态枚举。
2. 删除 `UserMessageAppendProcessor -> UserMessageAppendStatus` 依赖。
3. Web 中的 `UserMessageAppendStatus` 仍用于持久化和业务状态流转；如需映射协议事件，映射逻辑放在 Web 模块。

这一方案可以满足“仅将原 `protocol` 包移入新模块，其他业务代码仍留在 Web”的边界要求。

## 6. 其他受影响项

目录加深后，以下工程入口需同步更新：

- 根目录 `README.md` 的模块树、Maven 构建命令和 JAR 路径。
- `agentforge-service/README.md` 的 `-pl` 路径和启动 JAR 路径。
- `agentforge-service/docker/Dockerfile` 的 Maven `-pl` 路径及 JAR `COPY` 源路径。
- CI/CD、本地脚本或 IDE 配置中对 `agentforge-service/agentforge-service-web` 的硬编码引用。

新的关键路径为：

```text
agentforge-service/agentforge-service-backend/agentforge-service-web
agentforge-service/agentforge-service-backend/agentforge-service-web/target/agentforge-service-web-*.jar
```

artifactId 没有改变，所以最终 JAR 基础文件名不变；只是仓库内的物理路径变更。

## 7. 建议实施顺序

1. 创建 `agentforge-service-backend` 聚合 POM 和两个子模块目录。
2. 先建立 `agentforge-service-protocal` POM，再迁移 `protocol` 主代码和对应测试。
3. 解除 `UserMessageAppendProcessor` 对 Web 业务枚举的反向依赖。
4. 将原 Web 模块的其余文件迁入新的 `agentforge-service-web`，并增加对协议模块的依赖。
5. 更新上层聚合 POM、Dockerfile、README 和相关脚本路径。
6. 先分别验证协议模块和 Web 模块，最后执行全聚合构建。

迁移文件时建议使用 `git mv`，尽量保留 Git 历史可读性。

## 8. 验证清单

### 8.1 构建验证

```bash
mvn -pl agentforge-service/agentforge-service-backend/agentforge-service-protocal -am test
mvn -pl agentforge-service/agentforge-service-backend/agentforge-service-web -am test
mvn -pl agentforge-service -am package -DskipTests
```

### 8.2 边界验证

- `agentforge-service-protocal` 中不存在 `cloud.changlu.agentforge.service.modules` import。
- `agentforge-service-protocal` 不引入 Spring Boot、MyBatis、数据库驱动或 Web 业务模块。
- `agentforge-service-web` 中不再保留重复的 `protocol` 源文件。
- Maven reactor 中只出现一个 `agentforge-service-web` artifact。
- Spring Boot 可执行 JAR 可正常启动，前端静态资源仍能被打包和访问。

### 8.3 协议回归验证

- SSE JSON 字段名、`event/type/title/content/isFinish/eventSeq` 字段值与拆分前一致。
- `StreamEventEnums` 事件 code 和 type code 不变。
- 普通回答、思考、工具调用、重试、压缩、追加消息、会话创建和异常事件均做序列化回归。
- 事件持久化、SSE 即时推送和二段式重连重放的输出一致。

## 9. 完成标准

当以下条件全部满足时，本次拆分完成：

1. 原 `agentforge-service-web` 物理目录已调整为 `agentforge-service-backend` 聚合目录。
2. backend 下存在 `agentforge-service-web` 和 `agentforge-service-protocal` 两个子模块。
3. 原 `cloud.changlu.agentforge.service.protocol` 包及其测试已归属协议模块。
4. 模块依赖为 `web -> protocal`，不存在反向或循环依赖。
5. Maven 测试和打包通过，Docker 构建路径已适配。
6. 对外 SSE 协议和 Spring Boot 启动方式保持兼容。
