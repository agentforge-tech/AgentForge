# 百炼协议02、AgentForge 适配百炼 Partial Mode 接入核心实践

> 更新日期：2026-11-25  
> 适用模块：`agentforge-model-dashscope`（`cloud.changlu.agentforge.model.dashscope.fim`）  
> 维护者：changlu

一句话结论：百炼的 FIM 等价能力是 **Partial Mode（仅前缀续写）**，因此单独成模块
`agentforge-model-dashscope`，内部按 `fim` 分包，与 `agentforge-model-openai` 的解耦方式一致。

> 协议本身（请求形状、支持模型、无 suffix 限制）见
> [百炼协议01、阿里云百炼 Partial Mode 底层协议快速理解.md](百炼协议01、阿里云百炼%20Partial%20Mode%20底层协议快速理解.md)

---

## 一、为什么单独开一个模块

| 考量 | 说明 |
|---|---|
| 协议不同 | 端点 `/chat/completions`、消息形状 `assistant.partial`，与 `OpenAiFimModel` 完全不同 |
| 能力不同 | 只有前缀续写，没有 suffix；若混在 openai 模块里，容易让人误以为能力等价 |
| 依赖独立 | 未来百炼 Chat 能力（`chat` 包）会引入自己的请求参数，不宜污染 openai 模块 |
| 可选依赖 | 不用百炼的项目不应被迫引入 |

模块结构（按 `fim` / `chat` 分包，`chat` 预留）：

```text
agentforge-model/agentforge-model-dashscope/
├── pom.xml
└── src/
    ├── main/java/cloud/changlu/agentforge/model/dashscope/
    │   ├── fim/                                   ← 本次实现
    │   │   ├── DashScopeFimModel.java             同步
    │   │   ├── DashScopeStreamingFimModel.java    流式 SSE
    │   │   └── DashScopePartialWire.java          wire 构造与解析（内部 helper）
    │   └── chat/                                  ← 预留：百炼 Chat 能力
    └── test/java/cloud/changlu/agentforge/model/dashscope/
        ├── fim/
        │   ├── DashScopeFimModelTest.java          协议断言（11 条）
        │   ├── DashScopeStreamingFimModelTest.java 流式聚合（6 条）
        │   └── DashScopeLiveVerificationTest.java  真实端点验证入口
        └── chat/                                  ← 预留
```

> 与 openai 模块的分包策略保持一致：**测试目录和主代码目录同名分包**，一眼能看出测的是哪块能力。

<br/>

## 二、协议映射（五步转换）

### 2.1、请求侧

| 步骤 | 输入（AgentForge） | 输出（百炼 wire） |
|---|---|---|
| 1 | `FimRequest.prefix` | `messages[last].content`，且 `role=assistant`、`partial=true` |
| 2 | `FimRequest.suffix` | **无对应字段**：折叠进 `messages[0]`（user）提示词作为弱约束 |
| 3 | `modelName`（实例配置） | `model` |
| 4 | `FimRequestParameters` | `max_tokens` / `temperature` / `top_p` / `stop` |
| 5 | `stream` | `stream` + `stream_options.include_usage` |

核心代码（`DashScopePartialWire`）：

```java
private static Map<String, Object> assistantPartialMessage(String prefix) {
    LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
    item.put("role", "assistant");
    item.put("content", prefix == null ? "" : prefix);
    item.put("partial", Boolean.TRUE);   // ← 少了这个就退化成普通问答
    return item;
}
```

### 2.2、响应侧

| 步骤 | 百炼 wire | AgentForge |
|---|---|---|
| 1 | `choices[0].message.content` | `FimResponse.completion` |
| 2 | `choices[0].finish_reason` | `FimFinishReason`（stop/length/content_filter/other） |
| 3 | `usage.prompt_tokens` 等 | `FimUsage` |
| 4 | `usage.prompt_tokens_details.cached_tokens` | `FimUsage.cachedInputTokens` |
| 5 | 流式 `choices[0].delta.content` | `FimChunk.delta` |

<br/>

## 三、快速接入

### 3.1、最简用法

```java
// 只需 Key
FimModel fim = DashScopeFimModel.bailian(System.getenv("DASHSCOPE_API_KEY"));

FimResponse response = fim.complete(
        FimRequest.builder()
                .prefix("    public int add(int a, int b) {\n")
                .suffix("\n    }\n")
                .build());

System.out.println(response.completion());
```

### 3.2、完整配置

```java
FimModel fim = DashScopeFimModel.builder()
        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")  // 建议换成业务空间专属域名
        .apiKey("sk-xxx")
        .modelName("qwen3-coder-flash")     // 必须在 Partial Mode 支持清单内
        .maxTokens(128)
        .temperature(0.0d)
        .stopSequences(Arrays.asList("\n\n    public ", "```"))
        .suffixHintEnabled(false)           // 默认关闭：开启会显著提高空返回概率（见坑点记录 03）
        .build();
```

### 3.3、流式

```java
StreamingFimModel fim = DashScopeStreamingFimModel.builder()
        .apiKey("sk-xxx")
        .modelName("qwen3-coder-flash")
        .build();

fim.complete(request, new StreamingFimResponseHandler() {
    @Override
    public void onFirstChunk(FimChunk chunk) {
        // TTFT 打点
    }

    @Override
    public void onPartialCompletion(FimChunk chunk) {
        // 追加到编辑器 Ghost Text
    }

    @Override
    public void onComplete(FimResponse response) {
        // 聚合结果 + usage
    }

    @Override
    public void onError(Throwable error) {
        // 静默降级
    }
});
```

### 3.4、通过工厂装配

```java
LlmBasicConfig config = LlmBasicConfig.builder()
        .provider(LlmEnum.DASHSCOPE.getCode())
        .url("https://dashscope.aliyuncs.com/compatible-mode/v1")
        .modelName("qwen3-coder-flash")
        .apiKey("sk-xxx")
        .prop(LlmConstant.MAX_TOKENS, "128")
        .prop(LlmConstant.STOP, "\\n\\n    public |```")
        .build();

FimModel fim = LlmFactory.buildFimModel(config);
StreamingFimModel streaming = LlmFactory.buildStreamingFimModel(config);
```

> `DashScopeModel` 在 `url` / `modelName` 缺省时会**自动回落**到百炼官方默认值
> （`dashscope.aliyuncs.com/compatible-mode/v1` + `qwen3-coder-flash`），避免漏配直接 404。

<br/>

## 四、支持模型与选型

**Partial Mode 支持的 Coder 系列**（本模块推荐使用范围）：

| 模型 | 定位 | 行内补全适配度 |
|---|---|---|
| `qwen3-coder-flash` | 最新 Coder，速度优先 | ⭐⭐⭐ 推荐（默认值） |
| Qwen3-Coder 系列（其他规格） | 能力更强、延迟更高 | ⭐⭐ 需压 TTFT |
| Qwen2.5-Coder 系列 | 体积小、延迟低 | ⭐⭐⭐ 私有化/低延迟场景 |
| Qwen-Coder 系列（早期） | 兼容老项目 | ⭐ |

**不要用的模型**：

| 模型 | 原因 |
|---|---|
| `deepseek-v4.1-flash` | 不在 Partial Mode 支持清单，可能退化成普通生成 |
| 思考类模型（默认开思考） | 延迟高，行内补全不可接受 |
| Qwen-Audio | 不支持 OpenAI 兼容协议 |

> 完整清单见协议篇第四章，来源为百炼官方 Partial Mode 文档。

<br/>

## 五、验证方式

### 5.1、真实端点（填三项配置即可）

`DashScopeLiveVerificationTest` 顶部：

```java
private static final String FIM_BASE_URL   = "https://dashscope.aliyuncs.com/compatible-mode/v1";
private static final String FIM_API_KEY    = "";                    // ← 填你的百炼 Key
private static final String FIM_MODEL_NAME = "qwen3-coder-flash";
```

或用系统属性（推荐，避免密钥进 Git）：

```bash
mvn -pl agentforge-model/agentforge-model-dashscope -am \
    -Dfim.baseUrl=https://dashscope.aliyuncs.com/compatible-mode/v1 \
    -Dfim.apiKey=sk-xxx \
    -Dfim.modelName=qwen3-coder-flash \
    -Dtest="cloud.changlu.agentforge.model.dashscope.fim.DashScopeLiveVerificationTest" test
```

输出含非流式与流式的 `latency` / `finish` / 补全文本。**注意**：若补全里重复了后文代码，
属预期内的能力上限（无原生 suffix），不是 bug。

**选择只跑某几组场景**

默认跑全部 5 组。只想验证其中几组时，两种方式：

```java
// 方式一：改代码
private static final int[] SELECTED_SCENARIOS = new int[] { 2, 4 };   // 只跑 2 号与 4 号
```

```bash
# 方式二：命令行（优先级更高），支持带方括号的写法
mvn -pl agentforge-model/agentforge-model-dashscope -am \
    -Dfim.baseUrl=https://dashscope.aliyuncs.com/compatible-mode/v1 \
    -Dfim.apiKey=sk-xxx \
    -Dfim.modelName=qwen3-coder-flash \
    -Dfim.scenarios='[2,4]' \
    -Dtest="cloud.changlu.agentforge.model.dashscope.fim.DashScopeLiveVerificationTest#shouldVerifyBailianFim" test
```

支持的写法：`2,4`、`[2,4]`、`2 4`、`1;3;5`、单值 `3`，留空表示全部。

**编号越界会立即失败**，而不是静默跳过：

```text
java.lang.IllegalArgumentException: 场景编号越界：9，合法范围是 1..5（可用场景：[1, 2, 3, 4, 5]）
```

这条是刻意设计的——否则很容易"以为验证过了，其实一个场景都没跑"。

当前 5 组场景编号与对应的考察点：

| 编号 | 场景 | 考察点 |
|---|---|---|
| 1 | Agent：ReAct 工具调用循环 | 是否"执行工具→回填结果消息→再调模型"，保持 while 语义 |
| 2 | 流式：SSE 增量聚合 | 是否处理 `data:` 前缀、`[DONE]` 终止与增量累加 |
| 3 | 业务：事务 + 分页 + Stream 映射 | 是否复用已声明 `wrapper`、保持 `@Transactional` 语义 |
| 4 | 并发：分布式锁 + 重试退避 | 是否在 `try {` 内补齐、不破坏 `finally { unlock }` |
| 5 | 协议层：接口默认方法 | 是否沿用已有构造方式并返回正确类型 |

**流式是「实时打印」**：每个增量块到达就立刻输出并 `flush`，不做任何缓冲。运行过程中能亲眼看到
代码一行行"长出来"，而不是等结束才一次性刷屏——这是判断"服务端是否真的在流式返回"最直接的方式，
只看最终聚合文本是看不出来的。

**运行时先打印模型与思考模式信息**（判断"慢是不是因为它在思考"）：

```text
========== 百炼 Partial Mode 流式验证 ==========
baseUrl        = https://dashscope.aliyuncs.com/compatible-mode/v1
model          = qwen3-coder-flash
模型类型       = 非思考模型（Coder 系列，面向补全/生成速度）
思考模式       = 未开启（请求体未传 thinking / enable_thinking / reasoning_effort）
请求实际参数   = thinkingMode=未开启（Partial Mode 请求体不含 thinking 字段）
场景数         = 5 / 共 5
已选           = [1, 2, 3, 4, 5]
```

判定依据是**模型命名 + 实际请求体**，不发起额外请求：

| 模型名特征 | 模型类型 | 思考模式 |
|---|---|---|
| `qwq` / `qvq` | 思考模型（天生输出思维链） | 强制开启，请求无法关闭 |
| 含 `-thinking` | 思考模型 | 由模型变体决定 |
| 含 `coder` | 非思考模型 | 未开启（请求体无 thinking 字段） |
| 其它 instruct | 非思考模型 | 未开启 |

> 这条很关键：**思考类模型不适合行内补全**——它会先把思维链输出一遍，TTFT 直接翻倍。

单场景输出示例（含输入长度明细）：

```text
---------- 场景1 1号 Agent：ReAct 工具调用循环（长上下文） ----------
输入规模：prefix 1092 字符 / 32 行 | suffix 192 字符 / 9 行
长度明细：prefix=1092 | suffix=192 | 指令=47 | suffix提示=关闭 | 合计=1331
[prefix 尾部] ......ChatResponse response = chatModel.chat(request);\n        int iteration = 0;\n
[suffix 头部] \n        return response.aiMessage().text();\n    }\n\n    private void logIter...

>>> 实时流式输出（首块到达即打印）>>>
[TTFT 588ms]
            while (iteration < MAX_ITERATIONS) {
            ...

<<< 流式结束 <<<
TTFT=588ms | 完整耗时=2332ms | 墙钟=2337ms | 块数=56 | 字符=1134 | finish=STOP | usage=in=233, out=209, cached=0
块间隔：avg=31.1ms / max=96.3ms | TTFT 占比=25%
```

**耗时汇总表**（跑完所有场景后输出，含输入规模与输出字符）：

```text
========== 耗时汇总 ==========
场景                                prefix  suffix   指令    TTFT    完整  块数   间隔avg  输出字符
---------------------------------------------------------------------------------------------------
1号 Agent：ReAct 工具调用循环...      1092     192     47   689ms  3322ms    56    47.0ms      1134
2号 流式：SSE 增量聚合（长上...        703     324     47   216ms  2401ms    67    32.9ms      1502
3号 业务：事务 + 分页 + Strea...       778     324     47   366ms  1830ms    48    30.1ms       888
4号 并发：分布式锁 + 重试退避...       727     183     47   286ms   527ms     8    30.9ms        97
5号 协议层：接口默认方法（长...        392     375     47   328ms   724ms    16    24.4ms       239
---------------------------------------------------------------------------------------------------
平均 TTFT = 377ms / 平均完整耗时 = 1760ms
最大 TTFT = 689ms / 最大完整耗时 = 3322ms
总块数 = 195 / 总字符 = 3860
```

> 列含义：`prefix` / `suffix` / `指令` 是**输入的字符数**（`指令` 固定 47，因为默认不带 suffix 提示）；
> `输出字符` 是补全实际返回的字符数。两者对照能看出"输入规模"与"产出规模"是否匹配。
>
> 注意场景 1 的 TTFT 波动较大（实测 588ms ~ 4089ms），属真实网络抖动；
> 判断稳定性应看多次运行，而不是单次数字。

指标含义：

| 指标 | 含义 | 怎么用 |
|---|---|---|
| **TTFT** | 首块到达耗时 | 行内补全的核心 SLA，目标 < 300ms |
| **完整耗时** | 首字节到流结束（`FimResponse.latencyMs`） | 判断单次补全总成本 |
| **墙钟** | 从发起到断言完成（含线程调度） | 与完整耗时对比可发现额外开销 |
| **块间隔 avg / max** | 相邻块到达间隔 | `max` 明显大于 `avg` 说明中途卡顿；只有 1–2 块说明没真流式 |

> 表格按**显示宽度**对齐（CJK 字符按 2 列计算），中英文混排不会错列。

### 5.2、协议断言（不联网）

```bash
mvn -pl agentforge-model/agentforge-model-dashscope -am \
    -Dtest="cloud.changlu.agentforge.model.dashscope.fim.*Test" \
    -Dsurefire.failIfNoSpecifiedTests=false test
```

覆盖：URL 必须是 `/chat/completions`、最后一条消息必须是 `assistant + partial=true`、
`suffix` 折叠行为（开关两种）、`message.content` 解析、usage 缓存字段、流式 `delta.content` 聚合、
坏块容错、完成后幂等、HTTP 401 透出。

### 5.3、curl 快查

```bash
curl -sS https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions \
  -H "Authorization: Bearer $DASHSCOPE_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen3-coder-flash",
    "messages": [
      {"role":"user","content":"请补全这个函数，勿添加其它内容"},
      {"role":"assistant","content":"public int add(int a, int b) {\n","partial":true}
    ],
    "max_tokens": 64,
    "temperature": 0
  }'
```

<br/>

## 六、边界与风险

| 风险 | 表现 | 缓解 |
|---|---|---|
| **无原生 suffix** | 补全重复后文代码 | 属能力上限；关键场景改用 DeepSeek FIM（见坑点记录 03） |
| `partial` 漏写 | 200 成功但退化成问答（静默失效） | 单测硬断言 `"partial":true` |
| 模型不在支持清单 | 400 或效果异常 | 默认值锁定 `qwen3-coder-flash` |
| Key 与地域不匹配 | 401 `invalid_api_key` | 用同地域 Key；错误信息会误导成"Key 失效" |
| 旧域名稳定性 | 偶发延迟抖动 | 迁到 `{WorkspaceId}.cn-beijing.maas.aliyuncs.com` |
| 思考模式默认开启 | 延迟暴涨 | 用非思考模式模型（清单里已标注） |
| suffix 提示导致**空返回** | HTTP 200 但 content 为空 | **默认关闭** `suffixHintEnabled`（见坑点记录 03） |

### 上线检查清单

- [ ] BASE_URL 与 Key 同地域
- [ ] 模型在 Partial Mode 支持清单内（优先 `qwen3-coder-flash`）
- [ ] 请求最后一条消息是 `assistant` 且带 `partial: true`
- [ ] 设置了 `stop`（至少包含 `\n\n    public ` 与 `` ``` ``）
- [ ] `max_tokens` 收紧到 64–256
- [ ] 超时收紧到 1–3s（默认 `HttpRequest` 是 60s，会拖死编辑器）
- [ ] 接受"重复后文"这一能力上限，或明确改用 DeepSeek FIM

<br/>

## 七、演进

```text
fimmodel/
├── 统一FimModel协议层01、…（FimModel 契约 + DeepSeek FIM）
└── dashscope/
    ├── 百炼协议01、…（Partial Mode 协议本身）
    ├── 百炼协议02、…（当前文章：AgentForge 适配实践）
    └── 百炼协议03、…（坑点记录：suffix 提示导致空返回）

后续可选：
- dashscope/chat/  → 百炼 Chat 能力（工具调用、结构化输出等）
- FIM 能力对比基准 → DeepSeek FIM vs 百炼 Partial Mode vs 本地 Qwen FIM
```

---

## 参考资料来源

[1] 阿里云百炼. *前缀续写（Partial Mode）* — 支持模型清单与调用示例（本文模型表来源）。  
https://help.aliyun.com/zh/model-studio/partial-mode

[2] 阿里云百炼. *OpenAI 兼容 - Chat* — BASE_URL、地域与 Key 绑定。  
https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope

[3] 阿里云百炼. *代码能力（Qwen-Coder）* — Coder 模型能力与调用示例。  
https://help.aliyun.com/zh/model-studio/qwen-coder

[4] 阿里云百炼. *模型列表* — `deepseek-v4.1-flash` 存在但不在 Partial Mode 清单。  
https://help.aliyun.com/zh/model-studio/getting-started/models

[5] 阿里云百炼. *错误码* — `invalid_api_key` 等。  
https://help.aliyun.com/zh/model-studio/error-code

[6] AgentForge 仓库内参照：
`agentforge-model-api/.../fim/*`（FimModel 契约）、
`agentforge-model-openai/.../OpenAiFimModel.java`（原生 suffix 对照实现）、
`agentforge-model-registry/.../DashScopeModel.java`（装配）、
`agentforge-model-dashscope/.../dashscope/fim/*`（本模块实现）。

---

整理者：长路　创建时间：2026-11-25