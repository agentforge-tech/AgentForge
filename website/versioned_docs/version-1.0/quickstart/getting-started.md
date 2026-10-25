---
title: 快速开始
---
# 快速开始

## 环境要求

- JDK 8 或 JDK 17
- Maven 3.8+
- Git

## 引入 Agent Core

在你的 Maven 项目 `pom.xml` 中引入 AgentForge 的 Agent 核心模块：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`agentforge-agent-core` 会传递引入 `agentforge-model-api` 和 `agentforge-model-core`，无需重复声明。

## 安装本地 SNAPSHOT

```bash
git clone https://github.com/agentforge-tech/AgentForge.git
cd AgentForge
mvn -B -ntp install \
  -pl agentforge-framework/agentforge-agent-core \
  -am \
  -DskipTests
```

`-pl` 指定 Agent Core 模块，`-am` 会自动构建它依赖的 AgentForge 模块。构建完成后，你的应用即可从本地 Maven 仓库解析 `agentforge-agent-core`。

## 选择模型 Provider

Agent Core 不绑定模型厂商。使用 OpenAI 或 OpenAI-compatible 接口时，另行引入：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-openai</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

使用 Anthropic 时，将 `artifactId` 替换为 `agentforge-model-anthropic`。
