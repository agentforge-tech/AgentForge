# CodeQwen1.5-7B 对接分析：Xinference + HAMi 部署实测报告

> 目标端点：`http://172.16.122.66:9997`
> 测试时间：2026-11-26
> 测试方式：全部为真实 HTTP 调用（非 mock），逐项取证
> 结论一句话：**协议走 OpenAI Completions（`/v1/completions`），FIM 特殊 Token 必须用"无竖线"形态 `<fim_prefix>`，而不是 Qwen 官方标准的 `<|fim_prefix|>`。**

---

## 一、结论速览

| 项目 | 实测结论 | 可信度 |
|---|---|---|
| 推理后端 | **Xinference**（非 vLLM），`server: uvicorn`，`owned_by: xinference` | ✅ 直接观测 |
| 部署方式 | HAMi 容器化（华为昇腾 NPU 切分），运维确认 | ✅ 运维确认 |
| 可用协议 | **OpenAI Completions**：`POST /v1/completions`，返回 `object: text_completion` | ✅ 实测 200 |
| Chat 协议 | **不可用**，报错 `is not for chat` | ✅ 实测报错 |
| FIM Token 形态 | **无竖线** `<fim_prefix>` / `<fim_suffix>` / `<fim_middle>` 各 = **1 token** | ✅ token 计数取证 |
| 带竖线形态 | `<\|fim_prefix\|>` = **约 8 token**，被当普通文本切词，FIM 失效 | ✅ token 计数取证 |
| 输出上限 | `max_tokens=8192` 可写满；运维确认服务端上限 **65536** | ✅ 实测 + 运维 |
| 上下文上限 | 实测 prompt 达 **40023 token** 正常；运维确认 **65536** | ✅ 实测 + 运维 |
| `/v1/models` 的 `context_length: 2048` | **不可信的声明值，实际远超** | ⚠️ 需注意 |
| 模型自动收尾 | **不会**自己吐 `<fim_pad>`，必须客户端给 stop | ✅ 实测 |
| 框架现成支持 | `OpenAiModel` 未实现 FIM，`FimTemplates.QWEN` 用的是**带竖线**形态 → **不能直接用** | ⚠️ 需补适配 |

---

## 二、部署形态判定

### 2.1 探测命令

```bash
curl -sS -i http://172.16.122.66:9997/v1/models
```

### 2.2 返回（节选）

```http
HTTP/1.1 200 OK
server: uvicorn
content-type: application/json
```

```json
{
  "object": "list",
  "data": [{
    "id": "CodeQwen1.5-7B",
    "owned_by": "xinference",
    "model_type": "LLM",
    "address": "10.0.5.8:36061",
    "model_family": "codeqwen1.5",
    "model_format": "pytorch",
    "quantization": "none",
    "context_length": 2048,
    "replica": 1,
    "accelerators": ["0"]
  }]
}
```

### 2.3 判读

- `owned_by: xinference` + `server: uvicorn` → 前面是 **Xinference 网关**，不是 vLLM。
  因此网上关于「vLLM `--enable-prefix-caching` / `--tokenizer-mode`」的调优建议**不适用**。
- `address: 10.0.5.8:36061` → 真实的模型 worker 在容器网段，`9997` 只是 Xinference 的控制端口。
- HAMi 的作用是**把一张昇腾 NPU 按显存切分给多个容器**，它只影响资源调度，**不改变对外 HTTP 协议**。
  也就是说：HAMi 不是协议层因素，协议完全由 Xinference 决定。
- `context_length: 2048` 是 Xinference 从模型 `config.json` 读到的声明值，**与实测严重不符**（见第五节）。

---

## 三、协议判定：为什么必须是 Completions

### 3.1 Chat 协议实测不可用

```bash
curl -sS http://172.16.122.66:9997/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"CodeQwen1.5-7B","messages":[{"role":"user","content":"hi"}],"max_tokens":8}'
```

返回：

```json
{"detail":"[address=10.0.5.8:36061, pid=256] Model model_format='pytorch'
model_size_in_billions=7 quantization='none' model_id=None model_hub='huggingface'
model_uri='/data/cache/CodeQwen1.5-7B' model_revision=None activated_size_in_billions=None
is not for chat."}
```

**结论：该部署的模型能力只有 `generate`（`model_ability: ["generate"]`），没有 `chat`。**

这带来一个重要的架构限制：

> 这个模型**只能用于 FIM / 代码补全**，**不能**作为 AgentForge 的 `ChatModel` 接入 Agent 对话。
> 如果业务上需要"边聊边补全"，必须再部署一份带 chat 能力的模型（或将此模型按 chat 模板重新 launch）。

### 3.2 Completions 协议特征

| 特征 | 实测值 | 说明 |
|---|---|---|
| 端点 | `POST /v1/completions` | 与 OpenAI Completions 同构 |
| 请求体 | `prompt` 字符串 | 无 `messages` |
| 响应 `object` | `text_completion` | 非 `chat.completion` |
| 正文位置 | `choices[0].text` | 非 `choices[0].message.content` |
| 流式 | `stream=true` 返回标准 SSE `data: {...}` | 实测可用，末尾有 `finish_reason: length/stop` |

流式实测片段：

```
data: {"id":"d3f95fb4-...","object":"text_completion","model":"CodeQwen1.5-7B",
"choices":[{"text":" return","index":0,"finish_reason":null}]}
...
data: {"id":"d3f95fb4-...","choices":[{"text":"","index":0,"finish_reason":"length"}]}
```

---

## 四、FIM 特殊 Token：本次实测最关键的坑

### 4.1 取证方法

不猜、不查文档，直接用 `prompt_tokens` 反推 tokenizer 行为——
**如果某个标记是真正的 special token，它只会占 1 个 token；否则会被切成多个普通 token。**

### 4.2 实测数据

| prompt | prompt_tokens | 判读 |
|---|---|---|
| `<fim_prefix><fim_suffix><fim_middle>`（无竖线，裸标记） | **3** | ✅ 每个 = 1 token，是真 special token |
| `<\|fim_prefix\|><\|fim_suffix\|><\|fim_middle\|>`（带竖线，裸标记） | **23** | ❌ 被切成普通文本，非 special token |
| `<fim_prefix>def add(a, b):\n    <fim_suffix>\n\nassert add(2, 3) == 5\n<fim_middle>` | **25** | 正常 FIM |
| 同上，但换成带竖线 | **47** | 比无竖线多 22 token，多出来的正是标记被拆碎的代价 |
| 纯文本基线 `def add(a, b):\n    ` | **9** | 对照基准 |

### 4.3 行为验证（决定性证据）

**① 无竖线 → FIM 正常工作**

```bash
curl -sS http://172.16.122.66:9997/v1/completions -H 'Content-Type: application/json' -d '{
  "model":"CodeQwen1.5-7B",
  "prompt":"<fim_prefix>def add(a, b):\n    <fim_suffix>\n\nassert add(2, 3) == 5\n<fim_middle>",
  "max_tokens":40,"temperature":0,"stream":false,"stop":["<fim_pad>","<|endoftext|>"]
}'
```

```json
{"choices":[{"text":" \"\"\"\n    Add two numbers together\n    \"\"\"\n    return a + b\n","finish_reason":"stop"}]}
```

**② 带竖线 → 立即空返回**

```json
{"choices":[{"text":"","finish_reason":"stop"}]}
```

原因：带竖线的 `<|fim_middle|>` 是普通文本，模型看到的是一段"已经写完了的代码 + 一段乱码"，
自然认为无需续写，直接给 EOS。

> ⚠️ **这是最隐蔽的一类故障**：HTTP 200、无报错、只是补全为空或质量崩坏。
> 如果只看"接口通不通"，完全发现不了。

### 4.4 与 AgentForge 现有模板的冲突

`agentforge-model-core` 里的 `FimTemplates.QWEN` 定义的是**带竖线**形态：

```java
// FimTemplates.java:27-41
public static final FimTemplate QWEN =
        TokenFimTemplate.builder()
                .name("qwen")
                .prefixToken("<|fim_prefix|>")   // ← 与本部署不匹配
                .suffixToken("<|fim_suffix|>")   // ← 与本部署不匹配
                .middleToken("<|fim_middle|>")   // ← 与本部署不匹配
                ...
```

**结论：直接 `new TemplateFimModel(raw, FimTemplates.QWEN)` 会踩上面 ② 的空返回坑。**
必须为本次部署单独定义一个无竖线模板（见第七节）。

原因推测：`/data/cache/CodeQwen1.5-7B` 这份权重的 `tokenizer_config.json` 里
`additional_special_tokens` 注册的是无竖线形态（部分社区镜像或微调权重会这么改），
与 HuggingFace 官方仓库的默认配置不一致。

---

## 五、Token 上限实测

### 5.1 输出上限（`max_tokens`）

用"强制长输出"的 prompt（列表填充，模型不会自然停止）逐级试探：

| `max_tokens` | 实际 `completion_tokens` | `finish_reason` | 耗时 |
|---|---|---|---|
| 512 | 512 | `length` | — |
| 2048 | 2048 | `length` | — |
| 4096 | 4096 | `length` | — |
| **8192** | **8192** | **`length`** | **142.9s** |
| 65536 | — | — | 运维确认的服务端上限 |

**实测吞吐：8192 ÷ 142.9s ≈ 57 tokens/s**（单副本、昇腾 NPU）。

> 注意：如果 prompt 让模型自然完结，模型会提前 `finish_reason: stop`，
> 例如 `max_tokens=8192` 时只生成 125 token 就停了 —— 这不代表上限是 125。

### 5.2 上下文上限（prompt 长度）

`/v1/models` 声明 `context_length: 2048`，**实则完全不可信**：

| 填充行数 | 实际 `prompt_tokens` | 结果 |
|---|---|---|
| 300 | 1,523 | ✅ 正常 |
| 600 | 3,023 | ✅ 正常 |
| 1,200 | 6,023 | ✅ 正常 |
| 2,000 | 10,023 | ✅ 正常 |
| 4,000 | 20,023 | ✅ 正常 |
| 6,000 | 30,023 | ✅ 正常 |
| **8,000** | **40,023** | ✅ **正常** |
| 65,536 | — | 运维确认的上限 |

**关键提醒**：`context_length: 2048` 是 Xinference 从权重 `config.json` 读到的值，
而这份权重的 config 显然没按实际底座（CodeQwen1.5 原生支持 64K）正确填写。
**不要拿这个字段做客户端截断判断**，否则会把 64K 的模型当 2K 用。

### 5.3 上下文超限风险

由于声明值（2048）与实际值（65536）差 32 倍，如果 Xinference 内部**真的**按 2048 做截断，
表现会是"长文件补全时前缀被悄悄砍掉"，且**不报错**。

建议运维侧确认两件事：

1. `launch` 时是否显式传了 `--context-length`，若沿用默认则会以 config 值为准；
2. 截断策略是 `head`（保留尾部）还是报错 —— 对 FIM 而言**必须保留 prefix 尾部**，
   否则模型拿不到紧邻光标的上文，补全质量会断崖式下降。

---

## 六、stop 策略：必须客户端兜底

### 6.1 模型不会自己收尾

不给 stop 时的实测输出：

```
' return a + b\n\ndef subtract(a, b):\n    return a - b\n\ndef multiply(a, b):\n
 return a * b\n\ndef divide(a, b):\n    return a / b\n\ndef square(a):\n    return a * a\n\ndef cube(a):\n    return a *'
```

**模型顺着往下写了 6 个函数**，且全程**没有**吐出 `<fim_pad>`。

### 6.2 推荐 stop 组合

```json
"stop": ["<fim_pad>", "<|endoftext|>", "\n\ndef ", "\n\nclass ", "\n\nif __name__", "\n\n#", "\n\n//", "\n\nfunc "]
```

两组作用不同，缺一不可：

| 组别 | 作用 | 缺失后果 |
|---|---|---|
| `<fim_pad>` / `<|endoftext|>` | Token 级收尾（若模型偶尔吐） | 影响不大，实测基本不吐 |
| `"\n\ndef "` / `"\n\nclass "` / `"\n\nfunc "` … | **结构级收尾，真正的主力** | 一次吐出整个文件的函数 |

> 结构级 stop 需要按语言定制。`"\n\n"` 开头是关键：
> 单个 `"\n"` 会把正常的换行也截断，导致补全只剩一行。

---

## 七、AgentForge 接入方案

### 7.1 为什么不能直接用现有 provider

| provider 枚举 | 能否直连本部署 | 阻断点 |
|---|---|---|
| `OPENAI`(1) | ❌ | `OpenAiModel` 未实现 `buildFimModel`，落到 `IModel` 默认实现直接抛 `UnsupportedOperationException` |
| `DEEPSEEK`(3) | ❌ | `DeepSeekModel.normalizeBetaBase()` 强制追加 `/beta` → 404 |
| `DASHSCOPE`(4) | ❌ | 走百炼 Partial Mode（`/chat/completions` + `parameters.partial`），本部署 chat 不可用 |
| `ANTHROPIC`(2) | ❌ | 无 FIM 能力 |

**但底层网络组件完全够用**：`OpenAiFimModel` 的请求形状（`prompt` + `stop` + `/completions`）
与 Xinference 100% 吻合，缺的只是"装配入口"和"正确的 Token 模板"。

### 7.2 方案 A：不改框架，手工组合（立即可用）

**第一步：定义本部署专用的无竖线模板**

```java
import cloud.changlu.agentforge.model.fim.FimTemplate;
import cloud.changlu.agentforge.model.fim.template.TokenFimTemplate;
import java.util.Arrays;

/** 本部署（Xinference + CodeQwen1.5-7B）专用：无竖线特殊 Token。 */
public final class LocalQwenFimTemplates {

    public static final FimTemplate QWEN_NO_PIPE =
            TokenFimTemplate.builder()
                    .name("qwen-no-pipe")
                    .prefixToken("<fim_prefix>")   // 注意：无竖线，实测 1 token
                    .suffixToken("<fim_suffix>")
                    .middleToken("<fim_middle>")
                    .extraStops(Arrays.asList(
                            "<fim_pad>",
                            "<|endoftext|>"))
                    .build();
}
```

**第二步：装配**

```java
import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.fim.template.TemplateFimModel;
import cloud.changlu.agentforge.model.openai.OpenAiFimModel;

// 1) 底层：只负责把 prompt 发到 /completions
FimModel raw = OpenAiFimModel.builder()
        .baseUrl("http://172.16.122.66:9997/v1")  // 类内部会自动拼 /completions
        .apiKey("EMPTY")                          // Xinference 默认不校验，传占位符即可
        .modelName("CodeQwen1.5-7B")
        .disableThinking(false)   // 关键：框架默认 true 会塞 thinking 字段（本服务虽忽略，但没必要发）
        .readTimeoutMillis(30_000) // 关键：默认仅 2s，本地 7B 冷启动必然超时
        .build();

// 2) 装饰：套上无竖线 FIM 模板
FimModel qwen = new TemplateFimModel(raw, LocalQwenFimTemplates.QWEN_NO_PIPE);

// 3) 调用
FimResponse resp = qwen.complete(
        FimRequest.builder()
                .prefix("def add(a, b):\n    ")
                .suffix("\n\nassert add(2, 3) == 5\n")
                .build());

System.out.println(resp.completion());
```

`TemplateFimModel` 会自动完成三件事：
把 prefix/suffix 拼成 `<fim_prefix>P<fim_suffix>S<fim_middle>`、
把 `suffix` 字段置空（避免重复下发）、把模板 stop 与调用方 stop 合并。

### 7.3 方案 B：给 `OpenAiModel` 补 FIM 能力（长期更顺）

在 `OpenAiModel` 中 override `buildFimModel` / `buildStreamingFimModel`，
按模型名或配置项选择模板：

```java
@Override
public FimModel buildFimModel(LlmBasicConfig cfg) {
    OpenAiFimModel.Builder b = OpenAiFimModel.builder()
            .baseUrl(cfg.getUrl())
            .modelName(cfg.getModelName())
            .apiKey(cfg.getApiKey())
            .disableThinking(false)
            .readTimeoutMillis(resolveTimeout(cfg));   // 从 props 读，缺省 30s

    FimTemplate tpl = resolveTemplate(cfg);            // 支持 props 覆盖
    return new TemplateFimModel(b.build(), tpl);
}
```

建议同时给 `LlmConstant` 增加一个 `fimTemplate` 配置键，
让"Token 形态"变成可配置项而不是硬编码 —— 因为这次已经证明：
**同一模型的不同权重，Token 形态可能不一样。**

### 7.4 方案 C：配置化（不动代码）

若暂不想改框架，可直接用 `LlmBasicConfig` + 反射拿不到 FIM，
最省事的仍是方案 A 的手工 `new TemplateFimModel(...)`。

---

## 八、能力抽样评测

以下均为 `temperature=0` 的真实返回，用于评估是否满足业务需要。

### 8.1 Python：函数体补全

输入：

```
<fim_prefix>def add(a, b):
    <fim_suffix>

assert add(2, 3) == 5
assert add(-1, 1) == 0
<fim_middle>
```

输出（`finish_reason: stop`）：

```python
 """
    Add two numbers together
    """
    return a + b
```

✅ 正确地"补完函数体然后停住"，docstring 与实现都合理。

### 8.2 Python：断点续写（有上文语境）

输入：

```
<fim_prefix>import os
import json

def load_config(path):
    if not os.path.exists(path):
        <fim_suffix>
    with open(path) as f:
        return json.load(f)
<fim_middle>
```

输出：

```python
 return {}
    else:
        return load_config_from_file(path)
```

✅ 准确理解了"if 分支缺 return，且要与下方的 else 对齐"。
⚠️ `load_config_from_file` 是**幻觉 API**（不存在此函数）—— 这是 7B 补全模型的通病，
需要靠上下文工程（注入 import / 符号表）缓解。

### 8.3 Java

输入：`public static int add(int a, int b) { <fim_suffix> }\n}`

输出：` return a + b;`

✅ 干净准确。

### 8.4 Go

输入：`func Sum(nums []int) int { <fim_suffix> }`

输出：

```go
 sum := 0
    for _, num := range nums {
        sum += num
    }
```

✅ 惯用写法正确。

### 8.5 SQL

输入：`SELECT u.name, COUNT(o.id) AS cnt FROM users u <fim_suffix> GROUP BY u.name;`

输出：

```sql
LEFT JOIN orders o ON u.id = o.user_id
WHERE u.name = 'J
```

✅ 正确补出 JOIN 与 WHERE。

### 8.6 多行块补全

输入：`def fetch_user(user_id):\n    <fim_suffix>\n\nif __name__ == "__main__":`

输出：完整生成 docstring（含 Parameters / Returns 段落）。

✅ 能生成结构化多行块，但**容易超出单行补全的预期长度** —— 这正是必须配结构级 stop 的原因。

### 8.7 评测小结

| 维度 | 评价 |
|---|---|
| 语法正确性 | 好，多语言均无语法错误 |
| 意图理解 | 好，能理解 if/else 缺口、JOIN 缺口这类结构语义 |
| 幻觉倾向 | 中等，会调用不存在的函数（7B 模型正常水平） |
| 单行补全精度 | 好，配好 stop 后不会"话痨" |
| 建议用途 | IDE 行内补全 / 短块补全；**不建议**做长函数生成 |

---

## 九、风险清单与待办

### 9.1 高风险

| # | 风险 | 证据 | 建议 |
|---|---|---|---|
| 1 | **Token 形态与官方不一致**，框架 `FimTemplates.QWEN` 会静默失效 | token 计数 3 vs 23 | 用第七节的无竖线模板；并让运维贴出 `tokenizer_config.json` 的 `additional_special_tokens` 存档 |
| 2 | **`context_length` 声明 2048 与实际 65536 不符** | 实测 40023 token 正常 | 请运维确认 launch 参数与截断策略，务必"保留 prefix 尾部" |
| 3 | **chat 不可用**，该模型无法作为 Agent 对话模型 | `is not for chat` | 架构上定位为"补全专用模型"，不要混入 Agent 对话链路 |

### 9.2 中风险

| # | 风险 | 说明 |
|---|---|---|
| 4 | 框架默认 `readTimeout=2s` | 本地 7B 冷启动必超，必须显式放大到 20~30s |
| 5 | 框架默认 `disableThinking=true` | 会塞 `thinking` 字段，本服务实测忽略（HTTP 200），但应关掉避免依赖上游宽容度 |
| 6 | 无 API Key 校验 | 内网无所谓，若暴露到跨网段需加网关鉴权 |
| 7 | 单副本（`replica: 1`） | 并发补全会排队，57 tok/s 的单流吞吐下，**建议 IDE 侧做防抖 + 取消**（框架已有 `FimCall.cancel()`） |

### 9.3 建议的下一步

1. **拉 `tokenizer_config.json` 存档** —— 把 Token 形态这个"隐式契约"变成显式记录，避免下次换权重再次踩坑；
2. **确认截断策略** —— 这是长文件补全质量的根基，比模型选型更影响体感；
3. **补 `OpenAiModel.buildFimModel`** —— 让"OpenAI 兼容 + FIM"真正成为可配置能力，符合 `LlmEnum` 注释里"配置 url 即可"的既有设计意图；
4. **同步修正文档** —— `docs/dev/.../统一FimModel协议层01...md` 中 P1.5 标注"模板层已完成（客户端未接）"，
   但该模板默认带竖线，需补充"Token 形态需按部署实测校准"的提醒。

---

## 十、附：一键复现脚本

```bash
BASE=http://172.16.122.66:9997/v1
MODEL=CodeQwen1.5-7B

# 1) 协议与模型清单
curl -sS $BASE/models | python3 -m json.tool

# 2) 判定 Token 形态（无竖线应为 3）
for P in '<fim_prefix><fim_suffix><fim_middle>' '<|fim_prefix|><|fim_suffix|><|fim_middle|>'; do
  printf '%-45s -> ' "$P"
  curl -sS $BASE/completions -H 'Content-Type: application/json' \
    -d "$(python3 -c "import json,sys;print(json.dumps({'model':'$MODEL','prompt':sys.argv[1],'max_tokens':1,'temperature':0,'stream':False}))" "$P")" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['usage']['prompt_tokens'],'tokens')"
done

# 3) FIM 补全
curl -sS $BASE/completions -H 'Content-Type: application/json' -d "{
  \"model\":\"$MODEL\",
  \"prompt\":\"<fim_prefix>def add(a, b):\\n    <fim_suffix>\\n\\nassert add(2, 3) == 5\\n<fim_middle>\",
  \"max_tokens\":64,\"temperature\":0,\"stream\":false,
  \"stop\":[\"<fim_pad>\",\"<|endoftext|>\",\"\\n\\ndef \",\"\\n\\nclass \"]
}" | python3 -m json.tool

# 4) 流式
curl -sS -N $BASE/completions -H 'Content-Type: application/json' -d "{
  \"model\":\"$MODEL\",
  \"prompt\":\"<fim_prefix>def add(a, b):\\n    <fim_suffix>\\n\\nassert add(2, 3) == 5\\n<fim_middle>\",
  \"max_tokens\":32,\"temperature\":0,\"stream\":true,
  \"stop\":[\"<fim_pad>\",\"<|endoftext|>\"]
}"
```

---

**文档状态**：所有结论均基于 2026-11-26 对 `172.16.122.66:9997` 的真实调用取证。
`65536` 上限来自运维口头确认，未做满额压力测试（单次 8192 已耗时 142.9s，满额预计约 19 分钟，不适合在线验证）。
