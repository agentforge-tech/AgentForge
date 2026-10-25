---
title: 快速开始
sidebar_label: 快速开始
description: 在 Maven 项目中引入 agentforge-agent-core 并完成第一次 AgentForge 调用
---
# 快速开始

## 环境要求

- JDK 8 或 JDK 17
- Maven 3.8+
- Git

## 引入 Agent Core

Agent 应用的核心入口是 `agentforge-agent-core`。在 Maven 项目的 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`agentforge-agent-core` 会传递引入 `agentforge-model-api` 和 `agentforge-model-core`，无需重复声明。

:::info 当前版本说明
在正式版本发布到 Maven Central 前，请先将仓库中的 `1.0.0-SNAPSHOT` 构件安装到本地 Maven 仓库。
:::

## 安装 SNAPSHOT 到本地

```bash
git clone https://github.com/agentforge-tech/AgentForge.git
cd AgentForge
mvn -B -ntp clean install -DskipTests
```

如果你已经拥有本地 AgentForge 源码，也可以只构建 Agent Core 及其依赖模块：

```bash
mvn -B -ntp install \
  -pl agentforge-framework/agentforge-agent-core \
  -am \
  -DskipTests
```

- `-pl`：指定构建 `agentforge-agent-core`；
- `-am`：同时构建它依赖的 AgentForge 模块；
- `-DskipTests`：首次安装时跳过测试，加快构建。

## 选择模型 Provider

Agent Core 不绑定模型厂商。如果使用 OpenAI 或 OpenAI-compatible 接口，再添加：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-openai</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

使用 Anthropic 时，将 `artifactId` 替换为 `agentforge-model-anthropic`。

## 创建模型

```java
ChatModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .build();

String answer = model.chat("Hello AgentForge");
System.out.println(answer);
```

:::tip
请通过环境变量管理 API Key，不要将密钥提交到仓库。
:::

## 下一步

引入 `agentforge-agent-core` 后，你可以继续组装 `ReActAgent`、`ToolService`、Memory 和 Middleware。模型 Provider 只负责对接底层 LLM，Agent 层业务代码无需依赖具体厂商协议。
