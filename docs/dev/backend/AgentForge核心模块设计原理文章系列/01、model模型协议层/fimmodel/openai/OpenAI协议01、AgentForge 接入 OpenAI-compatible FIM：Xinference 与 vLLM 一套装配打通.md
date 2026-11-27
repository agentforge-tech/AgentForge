# OpenAI协议01、AgentForge 接入 OpenAI-compatible FIM：Xinference / vLLM / Ollama 一套装配打通

> 适用版本：release_1.x
> 适用模块：`agentforge-model-core`（模板层）、`agentforge-model-registry`（装配层）
> 包前缀：`cloud.changlu.agentforge.model`
> 维护者：长路
> 实测与核对时间：2026-11-26（所有协议结论均以当日对真实端点的调用结果为准）

系列定位：本文属于「AgentForge 核心模块设计原理文章系列 / 01、model 模型协议层 / fimmodel」，是该类别的第 02 篇。
前置阅读：[../统一FimModel协议层01、DeepSeek FIM 快速对接与落地实现：OpenAI Completions 协议复用 + FimModel 契约设计.md](../统一FimModel协议层01、DeepSeek%20FIM%20快速对接与落地实现：OpenAI%20Completions%20协议复用%20+%20FimModel%20契约设计.md)

本文只回答三个问题：

1. **为什么不该为本地 OpenAI-compatible 服务单独抽一套协议**（结论：线缆全同，抽协议是过度设计）；
2. **真正需要解决的是什么**（结论：特殊 Token 形态因部署而异，必须配置化）；
3. **怎么用**（装配代码、配置项、验证方法、踩坑清单）。

<br/>

## 目录

- [一、一次真实的对接触发](#一一次真实的对接触发)
- [二、先做判断：要不要单独抽协议](#二先做判断要不要单独抽协议)
  - [2.1、按三层判断，而不是按模型是否不同判断](#21按三层判断而不是按模型是否不同判断)
  - [2.2、对照 DashScope：当初为什么必须单独建类](#22对照-dashscope当初为什么必须单独建类)
  - [2.3、判定标准](#23判定标准)
- [三、真正的问题：Token 形态因部署而异](#三真正的问题token-形态因部署而异)
  - [3.1、实测取证：用 prompt_tokens 反推 tokenizer](#31实测取证用-prompt_tokens-反推-tokenizer)
  - [3.2、错配的表现：HTTP 200 但补全为空](#32错配的表现http-200-但补全为空)
  - [3.3、为什么会这样](#33为什么会这样)
- [四、实现方案](#四实现方案)
  - [4.1、装配入口：OpenAiModel 补齐 FIM](#41装配入口openaemodel-补齐-fim)
  - [4.2、模板层新增：无竖线 Qwen 与原生 suffix 兜底](#42模板层新增无竖线-qwen-与原生-suffix-兜底)
  - [4.3、流式对称：TemplateStreamingFimModel](#43流式对称templatestreamingfimmodel)
  - [4.4、配置项：fimTemplate](#44配置项fimtemplate)
- [五、快速接入](#五快速接入)
  - [5.1、最简用法](#51最简用法)
  - [5.2、完整配置](#52完整配置)
  - [5.3、流式](#53流式)
- [六、验证方式](#六验证方式)
  - [6.1、判定 Token 形态](#61判定-token-形态)
  - [6.2、协议断言（不联网）](#62协议断言不联网)
  - [6.3、真实端点验证](#63真实端点验证)
- [七、边界与风险](#七边界与风险)
- [八、实测数据：CodeQwen1.5-7B on Xinference + HAMi](#八实测数据codeqwen15-7b-on-xinference--hami)
- [参考来源](#参考来源)

---

## 一、一次真实的对接触发

初始需求很朴素：本地有一台 `Xinference` 部署的代码补全模型，想接进 AgentForge。

运维给的信息是「HAMi 容器化部署」，接口能通，curl 也能出结果：

```bash
curl http://172.16.122.66:9997/v1/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "CodeQwen1.5-7B",
    "prompt": "<fim_prefix>def add(a, b):\n    <fim_suffix>\n\nassert add(2, 3) == 5\n<fim_middle>",
    "max_tokens": 64,
    "temperature": 0,
    "stop": ["<|endoftext|>", "<fim_prefix>", "<fim_suffix>", "<fim_middle>", "<fim_pad>"]
  }'
```

返回：

```json
{"choices":[{"text":" \"\"\"\n    Add two numbers together\n    \"\"\"\n    return a + b\n","finish_reason":"stop"}]}
```

于是产生两个疑问：

1. 这个模型该用哪个 `provider` 对接？
2. 要不要为它**单独抽一个协议**？

第二个问题更重要——它的答案决定了框架层接下来是"加一个类"还是"改一套抽象"。

---

## 二、先做判断：要不要单独抽协议

### 2.1、按三层判断，而不是按模型是否不同判断

框架里 FIM 已经拆成了三个正交的层（见 01 篇 3.4 与 `FimFormat.java`）：

| 层 | 内容 | 典型差异 |
|---|---|---|
| **① 线缆层（wire）** | URL、请求体字段名、响应取哪个字段 | `prompt`+`suffix` vs `messages` vs `response` |
| **② 语义层（format）** | 原生 `suffix` 字段 vs 折叠进 prompt | `NATIVE_SUFFIX` vs `TOKEN_TEMPLATE` |
| **③ Token 层（template）** | 特殊 Token 的字面量 | `<\|fim_prefix\|>` vs `<fim_prefix>` vs `[PREFIX]` |

把本次真实端点逐层对照：

| 层 | AgentForge 现有 `OpenAiFimModel` | 本次 Xinference 部署 | 是否相同 |
|---|---|---|---|
| ① 线缆 | `POST {base}/completions`，`prompt` + `stop`，取 `choices[0].text` | 完全一致 | ✅ **相同** |
| ② 语义 | `TOKEN_TEMPLATE`（走 `TemplateFimModel` 装饰） | 同样需要客户端拼 Token | ✅ **相同** |
| ③ Token | 带竖线 `<\|fim_prefix\|>` | **无竖线** `<fim_prefix>` | ❌ **不同** |

结论很清楚：**只有第 ③ 层不同，而这一层本来就是可插拔的 `FimTemplate`。**

所以不需要新协议，需要的是"把模板变成可配置项"。

### 2.2、对照 DashScope：当初为什么必须单独建类

反例最能说明判据。`agentforge-model-dashscope` 之所以是独立模块，是因为它差在**第 ① 层**，而且差得很彻底：

```java
// DashScopePartialWire.java 类注释
// /chat/completions，没有 /completions，也没有 suffix 字段
// 通过 assistant 消息并设置 partial: true 表达前缀续写
```

具体差异：

| 维度 | OpenAI Completions | 百炼 Partial Mode |
|---|---|---|
| 端点 | `/completions` | `/chat/completions` |
| 输入结构 | `prompt` 字符串 | `messages` 数组 |
| 前缀表达 | `prompt` | `assistant` 消息 + `partial: true`（必须最后一条） |
| 后缀表达 | `suffix` 字段 | **没有**，只能塞 `system` 做弱约束 |
| 响应文本 | `choices[0].text` | `choices[0].message.content` |

这五条没有一条能复用 `OpenAiFimWire.buildPayload()`，所以必须独立成类。

还有一个实测踩过的坑写在其类注释里：**suffix 只能放 `system`，放 `user` 会返回空内容**——这种"协议自身的怪癖"是独立模块存在的价值。

### 2.3、判定标准

综合上面两组对照，判据可以写成一句：

> **线缆不同（端点/字段/响应路径）才建新协议；只有 Token 字面量不同，换模板即可。**

再补一条框架自身的设计意图作为佐证——`LlmEnum.java` 的注释原文就写了：

```java
// OpenAI Chat Completions 协议，同时兼容 OpenAI-compatible 服务（DashScope / Ollama / Xinference 等，配置对应
// url 即可）
```

**框架自己已经声明了 Xinference 应通过 `OPENAI` provider + url 配置接入。** 若为它单独开一个 provider 或 protocol 模块，等于推翻自己的设计。

---

## 三、真正的问题：Token 形态因部署而异

### 3.1、实测取证：用 prompt_tokens 反推 tokenizer

不看文档、不猜，直接用一次极小的请求反推：**真正的 special token 只占 1 个 token；否则会被切成多个普通 token。**

```bash
for P in '<fim_prefix><fim_suffix><fim_middle>' '<|fim_prefix|><|fim_suffix|><|fim_middle|>'; do
  printf '%-45s -> ' "$P"
  curl -sS http://172.16.122.66:9997/v1/completions -H 'Content-Type: application/json' \
    -d "$(python3 -c "import json,sys;print(json.dumps({'model':'CodeQwen1.5-7B','prompt':sys.argv[1],'max_tokens':1,'temperature':0,'stream':False}))" "$P")" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['usage']['prompt_tokens'],'tokens')"
done
```

实测结果：

| prompt | prompt_tokens | 判读 |
|---|---|---|
| `<fim_prefix><fim_suffix><fim_middle>` | **3** | ✅ 每个 = 1 token，是真 special token |
| `<\|fim_prefix\|><\|fim_suffix\|><\|fim_middle\|>` | **23** | ❌ 被当普通文本切碎 |

完整场景对比（同一段代码上下文）：

| 形态 | prompt_tokens | 差值 |
|---|---|---|
| 无竖线 | 25 | — |
| 带竖线 | 47 | +22，多出来的正是标记被拆碎的代价 |

### 3.2、错配的表现：HTTP 200 但补全为空

这是本文最需要记住的一条：**Token 形态错了不会报错。**

带竖线的请求（结构完整、字段正确）：

```bash
curl -sS http://172.16.122.66:9997/v1/completions -H 'Content-Type: application/json' -d '{
  "model":"CodeQwen1.5-7B",
  "prompt":"<|fim_prefix|>def add(a, b):\n    <|fim_suffix|>\n\nassert add(2, 3) == 5\n<|fim_middle|>",
  "max_tokens":16,"temperature":0,"stream":false
}'
```

返回：

```json
{"choices":[{"text":"","finish_reason":"stop"}]}
```

**HTTP 200、内容为空、`finish_reason: stop`。**

原因：带竖线的 `<|fim_middle|>` 被当普通文本，模型看到的是一段"已经写完的代码 + 一串乱码"，自然认为无需续写，直接给 EOS。

> 这类故障如果只用"接口通不通"来判断，**100% 会漏掉**。必须看 `prompt_tokens` 或补全内容是否为空。

### 3.3、为什么会这样

特殊 Token 是否生效，取决于部署时加载的 `tokenizer_config.json` 里的 `additional_special_tokens` 注册内容，**而不是模型名**。

因此同一模型名的不同权重可能注册不同形态：

- HuggingFace 官方 `Qwen/CodeQwen1.5-7B` → 注册带竖线；
- 某些社区镜像 / 微调权重 / 私有缓存目录 → 可能只注册无竖线。

本次遇到的 `/data/cache/CodeQwen1.5-7B` 属于后者。

⚠️ **无法靠改配置"修"回来**：往 tokenizer 里加新 Token 需要扩 embedding 并重新 probe，代价远高于在客户端换模板。所以配置化是正解。

---

## 四、实现方案

### 4.1、装配入口：OpenAiModel 补齐 FIM

修改前 `OpenAiModel` 只实现了 `buildChatModel` / `buildStreamChatModel`，FIM 会落到 `IModel` 的默认实现直接抛异常：

```java
// IModel.java（修改前）
default FimModel buildFimModel(LlmBasicConfig llmBasicConfig) {
    throw new UnsupportedOperationException(
            getClass().getSimpleName() + " does not support FIM completion");
}
```

补齐后（`OpenAiModel.java`）：

```java
@Override
public FimModel buildFimModel(LlmBasicConfig llmBasicConfig) {
    FimTemplate template = resolveFimTemplate(llmBasicConfig);
    OpenAiFimModel raw = buildRawFimModel(llmBasicConfig);
    if (template.format() == FimFormat.NATIVE_SUFFIX) {
        // 原生 suffix 形态不能套装饰器：否则 suffix 会被置空导致后文丢失
        return raw;
    }
    return new TemplateFimModel(raw, template);
}

@Override
public StreamingFimModel buildStreamingFimModel(LlmBasicConfig llmBasicConfig) {
    FimTemplate template = resolveFimTemplate(llmBasicConfig);
    OpenAiStreamingFimModel raw = buildRawStreamingFimModel(llmBasicConfig);
    if (template.format() == FimFormat.NATIVE_SUFFIX) {
        return raw;
    }
    return new TemplateStreamingFimModel(raw, template);
}
```

两个关键设计点：

| 设计 | 原因 |
|---|---|
| 按 `FimFormat` 分支决定是否套装饰器 | `TemplateFimModel` 会把 `suffix` 置空（因为已拼进 prompt）。对原生 suffix 服务这么做会**丢失后文** |
| 补全超时默认放宽到 10s/30s | `OpenAiFimModel` 内置默认是 2s（照 DeepSeek 调的）。本地 7B 冷启动常见 5~20s，沿用会大面积超时 |

超时与参数的解析做了同步/流式共用，避免两份实现漂移：

```java
private static final int DEFAULT_FIM_CONNECT_TIMEOUT_MILLIS = 10_000;
private static final int DEFAULT_FIM_READ_TIMEOUT_MILLIS = 30_000;
```

### 4.2、模板层新增：无竖线 Qwen 与原生 suffix 兜底

**（1）`FimTemplates.QWEN_NO_PIPE`**

```java
public static final FimTemplate QWEN_NO_PIPE =
        TokenFimTemplate.builder()
                .name("qwen-no-pipe")
                .prefixToken("<fim_prefix>")
                .suffixToken("<fim_suffix>")
                .middleToken("<fim_middle>")
                .extraStops(Arrays.asList("<fim_pad>", "<|endoftext|>"))
                .build();
```

注意 `extraStops` 也同步成无竖线——**Token 形态必须自洽**，混用带竖线的 stop 等于又埋一个坑。

**（2）`NativeSuffixFimTemplate`：未识别模型的安全兜底**

修改前 `forModel()` 未识别时默认返回带竖线的 `QWEN`：

```java
// 修改前
public static FimTemplate forModel(String modelName) {
    ...
    return QWEN;   // ← 对任何未识别模型都硬塞 Qwen Token
}
```

这在 Token 形态不统一的世界里是**危险默认值**：最坏情况不是"没能力"，而是"看起来能跑但结果是错的"。

改为：

```java
public static FimTemplate forModel(String modelName) {
    if (modelName == null) {
        return NativeSuffixFimTemplate.INSTANCE;
    }
    String lower = modelName.toLowerCase();
    if (lower.contains("codestral")) return CODESTRAL;
    if (lower.contains("codellama") || lower.contains("code-llama")) return CODE_LLAMA;
    if (lower.contains("starcoder") || lower.contains("stable-code")) return STARCODER2;
    if (lower.contains("qwen")) return QWEN;
    return NativeSuffixFimTemplate.INSTANCE;   // ← 安全兜底
}
```

`NativeSuffixFimTemplate` 的实现极简：

```java
@Override
public String buildPrompt(String prefix, String suffix) {
    return prefix == null ? "" : prefix;   // suffix 由请求体独立字段承载
}

@Override
public List<String> stopTokens() {
    return Collections.emptyList();
}
```

理由：直传 prefix + suffix 让服务端自行决定。最坏情况退化为"该模型没有 FIM 能力"（可观测），而不是"静默返回错误内容"。

**（3）`forName`：按名称精确解析**

供配置项使用，未知名称返回 `null` 由调用方决定回退或报错（而不是悄悄用某个默认模板）：

```java
public static FimTemplate forName(String name) {
    ...
    if ("native-suffix".equals(key) || "native".equals(key)) return NativeSuffixFimTemplate.INSTANCE;
    if ("qwen".equals(key)) return QWEN;
    if ("qwen-no-pipe".equals(key) || "qwen-nopipe".equals(key)) return QWEN_NO_PIPE;
    if ("qwen-spm".equals(key)) return QWEN_SPM;
    if ("starcoder2".equals(key)) return STARCODER2;
    if ("code-llama".equals(key) || "codellama".equals(key)) return CODE_LLAMA;
    if ("codestral".equals(key)) return CODESTRAL;
    return null;
}
```

### 4.3、流式对称：TemplateStreamingFimModel

P1.5 只做了非流式的 `TemplateFimModel`，**流式路径没有对应的装饰器**。这是个容易被忽略的缺口：流式请求如果没套模板，同样会拿到空补全。

新增 `TemplateStreamingFimModel`，并抽取 `TemplateFimRequests` 让两条路径共用变换逻辑：

```java
final class TemplateFimRequests {
    static FimRequest transform(FimRequest request, FimTemplate template) {
        return FimRequest.builder()
                .prefix(template.buildPrompt(request.prefix(), request.suffix()))
                .suffix("")
                .filePath(request.filePath())
                .language(request.language())
                .context(request.context())
                .documentVersion(request.documentVersion())
                .parameters(mergeStops(request, template))
                .build();
    }
}
```

流式装饰器：

```java
@Override
public void complete(FimRequest request, StreamingFimResponseHandler handler) {
    delegate.complete(TemplateFimRequests.transform(request, template), handler);
}

/** 取消必须透传，否则 IDE 场景下用户继续输入时会堆积无效请求。 */
@Override
public CancellationHandle completeCancellable(
        FimRequest request, StreamingFimResponseHandler handler) {
    return delegate.completeCancellable(TemplateFimRequests.transform(request, template), handler);
}
```

> 抽取 `TemplateFimRequests` 的动机与 `OpenAiFimWire` 一致：**同一份逻辑只写一次，避免同步/流式漂移**。

### 4.4、配置项：fimTemplate

新增 `LlmConstant.FIM_TEMPLATE`：

```java
/**
 * FIM 特殊 Token 模板名，不配置则按模型名推断，无法识别时兜底为 native-suffix。
 *
 * 可选值：native-suffix / qwen / qwen-no-pipe / qwen-spm / starcoder2 / code-llama / codestral
 */
String FIM_TEMPLATE = "fimTemplate";
```

解析优先级（`OpenAiModel.resolveFimTemplate`）：

```text
显式配置 fimTemplate  >  按模型名推断  >  native-suffix 兜底
```

**显式配置必须能覆盖推断**，因为：
- 推断只看模型名，看不到部署的 `tokenizer_config.json`；
- 一旦推断错，表现是静默降质，生产环境极难定位。

配置值拼错时**立刻抛异常**，而不是静默回退：

```java
throw new IllegalArgumentException(
        "Unknown fimTemplate: " + configured
                + ", supported: native-suffix / qwen / qwen-no-pipe / qwen-spm"
                + " / starcoder2 / code-llama / codestral");
```

---

## 五、快速接入

### 5.1、最简用法

```java
LlmBasicConfig config = LlmBasicConfig.builder()
        .provider(LlmEnum.OPENAI.getCode())       // 关键：复用 OPENAI provider
        .url("http://172.16.122.66:9997/v1")      // 注意：类内部自动拼 /completions
        .modelName("CodeQwen1.5-7B")
        .apiKey("EMPTY")                          // Xinference 默认不校验
        .build();

FimModel model = LlmFactory.buildFimModel(config);

FimResponse resp = model.complete(
        FimRequest.builder()
                .prefix("def add(a, b):\n    ")
                .suffix("\n\nassert add(2, 3) == 5\n")
                .build());

System.out.println(resp.completion());
```

此时模板按模型名推断出 `qwen`（带竖线）。**如果该部署是无竖线形态，必须显式配置。**

### 5.2、完整配置

```java
LlmBasicConfig config = LlmBasicConfig.builder()
        .provider(LlmEnum.OPENAI.getCode())
        .url("http://172.16.122.66:9997/v1")
        .modelName("CodeQwen1.5-7B")
        .apiKey("EMPTY")
        // 关键：显式指定 Token 形态，覆盖模型名推断
        .prop(LlmConstant.FIM_TEMPLATE, "qwen-no-pipe")
        .prop(LlmConstant.TEMPERATURE, "0")
        .prop(LlmConstant.MAX_TOKENS, "64")
        // 结构级 stop 才是主力：模型不会自己吐 <fim_pad>
        .prop(LlmConstant.STOP, "\\n\\ndef |\\n\\nclass |\\n\\n#")
        .prop(LlmConstant.TIMEOUT, "30")
        .build();
```

| 配置项 | 建议值 | 说明 |
|---|---|---|
| `fimTemplate` | `qwen-no-pipe` | **务必按实测选定**，见第六节判定方法 |
| `stop` | `\n\ndef \|\n\nclass \|` | 多个值用 `\|` 分隔；结构级 stop 是防"话痨"主力 |
| `timeout` | `30`（秒） | 本地 7B 冷启动常见 5~20s |
| `maxTokens` | `64~128` | 单行补全足够；过大会拖慢 |
| `temperature` | `0` | 补全不需要多样性 |
| `fimDisableThinking` | 不配 | OpenAI-compatible 服务没有 thinking 字段，默认不下发 |

### 5.3、流式

```java
StreamingFimModel model = LlmFactory.buildStreamingFimModel(config);

CancellationHandle handle = model.completeCancellable(
        FimRequest.builder().prefix("def add(a, b):\n    ").suffix("\n").build(),
        new StreamingFimResponseHandler() {
            @Override
            public void onPartialCompletion(FimChunk chunk) {
                System.out.print(chunk.delta());
            }

            @Override
            public void onComplete(FimResponse response) {
                System.out.println("\n--- " + response.latencyMs() + "ms");
            }

            @Override
            public void onError(Throwable error) {
                error.printStackTrace();
            }
        });

// 用户继续输入时掐断上一轮
handle.cancel();
```

流式实测帧格式（标准 SSE，`choices[0].text`，无 `delta` 包装）：

```text
data: {"id":"...","object":"text_completion","choices":[{"text":" return","index":0,"finish_reason":null}]}
data: {"id":"...","choices":[{"text":" a","index":0,"finish_reason":null}]}
...
data: {"id":"...","choices":[{"text":"","finish_reason":"length"}]}
```

---

## 六、验证方式

### 6.1、判定 Token 形态

**这是接入前的第一步，不能跳过。**

```bash
BASE=http://172.16.122.66:9997/v1
MODEL=CodeQwen1.5-7B

for P in '<fim_prefix><fim_suffix><fim_middle>' '<|fim_prefix|><|fim_suffix|><|fim_middle|>'; do
  printf '%-45s -> ' "$P"
  curl -sS $BASE/completions -H 'Content-Type: application/json' \
    -d "$(python3 -c "import json,sys;print(json.dumps({'model':'$MODEL','prompt':sys.argv[1],'max_tokens':1,'temperature':0,'stream':False}))" "$P")" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['usage']['prompt_tokens'],'tokens')"
done
```

| 结果 | 结论 | 配置 |
|---|---|---|
| 无竖线 = **3** | 无竖线形态 | `fimTemplate=qwen-no-pipe` |
| 带竖线 = **3** | 带竖线形态 | `fimTemplate=qwen` |
| 都不为 3，但某个约为 3 的倍数 | 该形态未被注册 | 试另一个；都不行则用 `native-suffix` |

**辅助判据**：直接发一次真实 FIM 请求，若返回 `text` 为空且 `finish_reason: stop`，基本可判定 Token 形态错了。

### 6.2、协议断言（不联网）

单测注入假 `HttpTransport`，断言请求体与响应解析。已覆盖的关键用例：

| 用例 | 断言内容 |
|---|---|
| `shouldFallBackToNativeSuffixForUnknownModel` | 未识别模型返回 `native-suffix`，不硬塞 Qwen Token |
| `shouldResolveTemplateByName` | `qwen-no-pipe` / `QWEN-SPM` / 带空白等写法均可解析，未知返回 `null` |
| `shouldBuildQwenNoPipePrompt` | 无竖线模板的 prompt 与 stop **形态自洽**（不含 `<\|fim_pad\|>`） |
| `shouldPassThroughWithNativeSuffixTemplate` | 原生模板原样返回 prefix、stop 为空 |
| `shouldApplyTemplateOnStreamingPath` | 流式路径同样应用模板变换 |
| `shouldPropagateCancelOnStreamingPath` | 流式的 `cancel()` 必须透传到底层 |
| `shouldLetExplicitTemplateOverrideInference` | 显式 `fimTemplate` 覆盖模型名推断 |
| `shouldNotWrapUnknownModelWithTokenTemplate` | 未识别模型不套装饰器（保住 suffix） |
| `shouldRejectUnknownTemplateName` | 模板名拼错立刻抛 `IllegalArgumentException` |

运行：

```bash
mvn -pl agentforge-model/agentforge-model-core,agentforge-model/agentforge-model-registry -am test
```

### 6.3、真实端点验证

```bash
curl -sS http://172.16.122.66:9997/v1/completions -H 'Content-Type: application/json' -d '{
  "model":"CodeQwen1.5-7B",
  "prompt":"<fim_prefix>def add(a, b):\n    <fim_suffix>\n\nassert add(2, 3) == 5\n<fim_middle>",
  "max_tokens":40,"temperature":0,"stream":false,
  "stop":["<fim_pad>","<|endoftext|>","\n\ndef ","\n\nclass "]
}'
```

期望（实测）：

```json
{"choices":[{"text":" \"\"\"\n    Add two numbers together\n    \"\"\"\n    return a + b\n","finish_reason":"stop"}]}
```

---

## 七、边界与风险

| # | 风险 | 说明 | 应对 |
|---|---|---|---|
| 1 | **Token 形态错配** | 静默返回空，不报错 | 上线前必跑 6.1 判定；把结果写进配置文档 |
| 2 | **`context_length` 声明不可信** | 实测某部署声明 2048，实际 40023 token 仍正常 | 不要拿该字段做客户端截断 |
| 3 | **模型不会自己收尾** | 不配 stop 会连写多个函数 | 必须配**结构级** stop（`\n\ndef ` 等） |
| 4 | **chat 不可用** | 某部署 `is not for chat`，`model_ability` 只有 `generate` | 该模型只能做补全，不可混入 Agent 对话链路 |
| 5 | **单副本并发** | `replica: 1` 时并发补全会排队 | IDE 侧必须防抖 + 取消（`completeCancellable`） |
| 6 | **无鉴权** | 内网可接受 | 跨网段暴露需加网关 |
| 7 | **推断优先于配置的反向风险** | 若显式配置写错却没校验，会静默错配 | 已实现：未知名称直接抛异常 |

### 明确的边界：为什么不为它抽协议

| 若这样做 | 后果 |
|---|---|
| 新增 `LlmEnum.XINFERENCE` + `XinferenceModel` | 只为一处 Token 差异复制整套 HTTP 逻辑，且下次换部署还要再改 |
| 新增 `agentforge-model-local` 协议模块 | 与 `LlmEnum` 注释声明的"配置 url 即可"直接冲突 |
| 把 Token 形态写死进 Provider | 换权重即失效，且失效方式不可观测 |

**正解是配置化**：协议稳定（Completions），模板可变（`fimTemplate`）。

---

## 八、实测数据：CodeQwen1.5-7B on Xinference + HAMi

以下均为 2026-11-26 对 `172.16.122.66:9997` 的真实调用结果。

### 8.1、部署与协议

| 项目 | 实测值 |
|---|---|
| 推理后端 | Xinference（`server: uvicorn`，`owned_by: xinference`），非 vLLM |
| 部署方式 | HAMi 容器化；HAMi 只做资源切分，不改变 HTTP 协议 |
| 可用协议 | `POST /v1/completions`，`object: text_completion` |
| Chat 协议 | 不可用，报 `is not for chat` |
| 模型能力 | `model_ability: ["generate"]` |

> HAMi 的作用是把 NPU 按显存切分给多个容器，属于**调度层**。协议完全由 Xinference 决定，所以网上关于 vLLM `--tokenizer-mode` 之类的建议在此不适用。

### 8.2、Token 上限

| 项 | 实测值 | 说明 |
|---|---|---|
| 输出 `max_tokens` | **8192 写满**（`finish_reason: length`，耗时 142.9s） | 约 57 tokens/s（单副本、昇腾 NPU） |
| 上下文 prompt | **40023 token 正常**（填充 8000 行） | `/v1/models` 声明 2048，**不可信** |
| 服务端上限 | 65536（运维确认） | 未做满额压力测试 |

⚠️ 注意区分：若 prompt 让模型自然完结，`finish_reason` 会提前变 `stop`（例如 `max_tokens=8192` 时只生成 125 token），**这不代表上限是 125**。

### 8.3、能力抽样（`temperature=0`）

| 场景 | 输入 | 输出 | 评价 |
|---|---|---|---|
| Python 函数体 | `def add(a, b):\n    ` | docstring + `return a + b` | ✅ 正确停下 |
| Python 断点续写 | `if not os.path.exists(path):` 分支 | `return {}\n    else:\n        return load_config_from_file(path)` | ✅ 理解 else 对齐；⚠️ `load_config_from_file` 是幻觉 API |
| Java | `public static int add(int a, int b) {` | ` return a + b;` | ✅ 干净 |
| Go | `func Sum(nums []int) int {` | `sum := 0` + range 循环 | ✅ 惯用写法 |
| SQL | `FROM users u` + `GROUP BY` | `LEFT JOIN orders o ON u.id = o.user_id` | ✅ 正确 |

**结论**：适合行内补全与短块补全；长函数生成不建议。幻觉 API 是 7B 模型的正常水平，需靠上下文工程缓解。

### 8.4、复现脚本

```bash
BASE=http://172.16.122.66:9997/v1
MODEL=CodeQwen1.5-7B

# 1) 协议与模型清单
curl -sS $BASE/models | python3 -m json.tool

# 2) 判定 Token 形态（见 6.1）
# 3) FIM 补全
curl -sS $BASE/completions -H 'Content-Type: application/json' -d "{
  \"model\":\"$MODEL\",
  \"prompt\":\"<fim_prefix>def add(a, b):\\n    <fim_suffix>\\n\\nassert add(2, 3) == 5\\n<fim_middle>\",
  \"max_tokens\":64,\"temperature\":0,\"stream\":false,
  \"stop\":[\"<fim_pad>\",\"<|endoftext|>\",\"\\n\\ndef \",\"\\n\\nclass \"]
}"

# 4) 流式
curl -sS -N $BASE/completions -H 'Content-Type: application/json' -d "{
  \"model\":\"$MODEL\",
  \"prompt\":\"<fim_prefix>def add(a, b):\\n    <fim_suffix>\\n\\nassert add(2, 3) == 5\\n<fim_middle>\",
  \"max_tokens\":32,\"temperature\":0,\"stream\":true,
  \"stop\":[\"<fim_pad>\",\"<|endoftext|>\"]
}"
```

---

## 参考来源

| 编号 | 来源 | 用途 |
|---|---|---|
| [1] | 本文实测：`http://172.16.122.66:9997`（2026-11-26） | 协议判定、Token 计数、上限、能力抽样 |
| [2] | `agentforge-model-core`：`FimTemplates.java`、`TokenFimTemplate.java`、`TemplateFimModel.java` | 模板层实现 |
| [3] | `agentforge-model-registry`：`OpenAiModel.java`、`IModel.java`、`LlmConstant.java` | 装配层实现 |
| [4] | `agentforge-model-dashscope`：`DashScopePartialWire.java` | 对照反例：为何线缆不同才需独立协议 |
| [5] | 01 篇 3.4 节 | `FimFormat` 的分层依据 |

---

**文档状态**：所有结论均基于当日真实调用取证。`65536` 上限来自运维口头确认，未做满额压测（单次 8192 已耗时 142.9s）。
