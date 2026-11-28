---
title: FIM 代码补全模型协议
sidebar_label: FIM 代码补全模型协议
description: 使用 AgentForge FimModel 对接 OpenAI-compatible 代码补全服务，并运行 agentforge-fim-demo
sidebar_position: 1
displayed_sidebar: sdkSidebar
---

# FIM 代码补全模型协议

FIM（Fill-in-the-Middle）面向 IDE 行内代码补全。调用方提供光标前的 `prefix` 和光标后的 `suffix`，模型只返回中间缺失的代码。

它与 ChatModel 是并列协议：ChatModel 处理 `messages` 和对话响应，FIM 处理 `prefix/suffix` 和代码补全响应；OpenAI-compatible FIM 通常请求 `{baseUrl}/completions`，而不是 `/chat/completions`。

## 1. 引入依赖

固定使用 OpenAI-compatible FIM 服务时，引入 OpenAI Provider：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-openai</artifactId>
    <version>1.0.2-SNAPSHOT</version>
</dependency>
```

需要按配置动态装配 Provider 时，再引入 `agentforge-model-registry`。

## 2. 最小调用

同步补全：

```java
import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.openai.fim.OpenAiFimModel;

FimModel model = OpenAiFimModel.builder()
        .baseUrl("http://127.0.0.1:9997/v1")
        .apiKey(System.getenv().getOrDefault("FIM_API_KEY", "EMPTY"))
        .modelName("CodeQwen1.5-7B")
        .temperature(0.0)
        .maxTokens(64)
        .build();

FimRequest request = FimRequest.builder()
        .prefix("public int add(int a, int b) {\n    ")
        .suffix("\n}")
        .language("java")
        .build();

System.out.println(model.complete(request).completion());
```

也可以使用便捷入口：

```java
FimResponse response = model.complete(prefix, suffix);
```

`prefix` 和 `suffix` 是 FIM 的核心语义；`filePath`、`language`、`context` 和 `documentVersion` 可用于后续的编辑器上下文与结果过期校验。

## 3. 流式补全

行内补全通常需要尽快展示首个字符，可使用 `StreamingFimModel`：

```java
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.fim.response.FimChunk;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.fim.response.StreamingFimResponseHandler;
import cloud.changlu.agentforge.model.openai.fim.OpenAiStreamingFimModel;

StreamingFimModel model = OpenAiStreamingFimModel.builder()
        .baseUrl("http://127.0.0.1:9997/v1")
        .apiKey(System.getenv().getOrDefault("FIM_API_KEY", "EMPTY"))
        .modelName("CodeQwen1.5-7B")
        .includeUsage(true)
        .build();

model.complete(request, new StreamingFimResponseHandler() {
    @Override
    public void onPartialCompletion(FimChunk chunk) {
        System.out.print(chunk.delta());
    }

    @Override
    public void onComplete(FimResponse response) {
        System.out.println("\n完成：" + response.completion());
    }

    @Override
    public void onError(Throwable error) {
        error.printStackTrace();
    }
});
```

编辑器在用户继续输入时，应保存 `CancellationHandle` 并取消已经过期的请求，避免旧补全覆盖最新内容。

## 4. 运行 FIM Demo

仓库提供了完整的 Spring Boot + 原生前端示例：`agentforge-examples/agentforge-fim-demo`。它展示了：

- 前端按光标位置切分 `prefix/suffix`；
- 通过 `LlmFactory` 装配同步或流式 `FimModel`；
- 使用 SSE 推送增量，并在页面渲染 Ghost Text；
- 展示 TTFT、总耗时、usage 和 FIM Token 探测结果。

准备一个本地 OpenAI-compatible FIM 服务（默认地址为 `http://127.0.0.1:9997/v1`）后，在仓库根目录执行：

```bash
cd agentforge-examples/agentforge-fim-demo
FIM_BASE_URL=http://127.0.0.1:9997/v1 \
FIM_MODEL_NAME=CodeQwen1.5-7B \
FIM_API_KEY=EMPTY \
FIM_TEMPLATE=qwen-no-pipe \
./start.sh
```

启动后打开 `http://localhost:8080`。`FIM_TEMPLATE` 必须与部署模型的 tokenizer 形态一致，CodeQwen 常见值包括：

- `qwen`：`<|fim_prefix|>` 形式；
- `qwen-no-pipe`：`<fim_prefix>` 形式；
- `native-suffix`：服务端原生支持 `suffix` 字段。

不访问真实模型的单元测试：

```bash
mvn test
```

真实端点验证需要显式开启：

```bash
mvn -Dfim.live=true \
  -Dfim.baseUrl=http://127.0.0.1:9997/v1 \
  -Dfim.modelName=CodeQwen1.5-7B \
  -Dfim.template=qwen-no-pipe \
  -Dtest=FimLiveVerificationTest test
```

更完整的部署实测、Xinference + HAMi 配置和 Token 探测记录位于 Demo 目录下的 `CodeQwen1.5-7B 对接分析：Xinference + HAMi 部署实测报告.md`。

## 5. 协议边界

| 场景 | 使用协议 | 典型端点 |
| --- | --- | --- |
| 多轮对话、工具调用 | `ChatModel` / `StreamingChatModel` | `/chat/completions` |
| 光标中间代码补全 | `FimModel` / `StreamingFimModel` | `/completions` |

业务层建议依赖 `FimModel` 或 `StreamingFimModel` 接口，不直接解析厂商 JSON。OpenAI-compatible 服务的请求构造、SSE 聚合、usage 和 `finish_reason` 映射由 `openai.fim` Provider 负责。
