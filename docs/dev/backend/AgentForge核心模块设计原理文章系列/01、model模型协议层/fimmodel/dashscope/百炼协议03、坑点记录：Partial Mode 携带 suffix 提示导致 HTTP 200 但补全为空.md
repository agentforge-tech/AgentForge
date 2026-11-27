# 百炼协议03、坑点记录：Partial Mode 携带 suffix 提示导致「HTTP 200 但补全为空」

> 更新日期：2026-11-25  
> 影响模块：`agentforge-model-dashscope`（`dashscope.fim`）  
> 维护者：changlu  
> 严重程度：**高**（静默失效，不报错、不抛异常，只表现为"补全没有内容"）

---

## 一、现象

用百炼 Partial Mode 做代码补全时，跑真实端点验证出现：

```text
---------- 场景1 Agent：ReAct 工具调用循环（长上下文） ----------
输入规模：prefix 1092 字符 / 32 行 | suffix 192 字符 / 9 行
>>> 实时流式输出（首块到达即打印）>>>


java.lang.AssertionError: [场景1 ...] 应收到增量块
```

三个关键观察：

1. **HTTP 200**，没有任何错误状态码；
2. **`finish_reason = stop`**（不是 `length`，不是 `content_filter`）；
3. **`content` 是空字符串**，一个增量块都没有。

也就是说：**请求成功、链路通、模型正常结束，但什么都没生成。**

这类问题最麻烦的地方在于——它看起来像"流式实现坏了"，实际流式完全正常。

<br/>

## 二、排查路径（用真实 curl 反推）

> 方法论：先用最小程序确认传输层，再逐步收窄请求体，最后锁定变量。

### 2.1、第一步：确认传输层没问题

用 `JdkHttpTransport` 直接发流式请求，打印回调：

```text
OPEN status=200 ct=[text/event-stream;charset=utf-8]
LINE[237]: data: {"model":"qwen3-coder-flash",...
LINE[0]:
LINE[221]: data: {"model":"qwen3-coder-flash","choices":[{"delta":{"content":...
...
```

**结论**：传输层、SSE 解析、`data:` 前缀识别全部正常。排除网络与解析问题。

### 2.2、第二步：确认 Provider 没问题

绕过测试，直接调 `DashScopeStreamingFimModel`：

```text
FIRST: [       ]
PART: [ return]
PART: [ a]
PART: [ +]
PART: [ b;\n    }]
COMPLETE finish=LENGTH len=120
```

**结论**：Provider 的流式聚合也正常。排除实现 bug。

### 2.3、第三步：用测试里的**真实输入**复现

用反射取出测试中的场景 1 前缀/后缀，直接调用：

```text
prefix len=1092 suffix len=192
COMPLETE chunks=0 finish=STOP len=0
body=[]
```

**复现成功**，且 `chunks=0`、`len=0`。此时可以确定：**问题出在请求内容与模型的交互上**。

### 2.4、第四步：打印实际发出的请求体

拦截 `httpTransport`，dump 请求体，关键片段：

```json
{
  "model": "qwen3-coder-flash",
  "messages": [
    {
      "role": "user",
      "content": "请补全这段代码中间缺失的部分，只输出需要补全的代码...
                  光标之后已经存在下面这些代码，请勿重复生成它们：
                  ```\n\n        return response.aiMessage().text();\n ... ```"
    },
    { "role": "assistant", "content": "<1092 字符的前缀>", "partial": true }
  ],
  "max_tokens": 256,
  "temperature": 0.0,
  "stop": ["\n\n    public ", "\n\n    private ", "```"],
  "stream": true,
  "stream_options": { "include_usage": true }
}
```

### 2.5、第五步：变量隔离（决定性实验）

固定输入为**精确的场景 1 数据**，只改一个变量：

| 变体 | `finish_reason` | 内容长度 |
|---|---|---|
| ① 纯 partial（**不带** suffix 提示） | `stop` | **1134** |
| ② suffix 放 **system** | `stop` | **0** |
| ③ suffix 放 **user** | `stop` | **0** |

再补一组对照，排除 `stop` 与 `max_tokens` 的干扰：

| 变体 | 结果 |
|---|---|
| 不带 `stop` | 仍为 0 |
| `max_tokens` 提到 512 | 仍为 0 |
| 去掉 ``` 围栏 | 仍为 0 |

**结论：唯一变量就是"是否把 suffix 塞进提示词"。只要塞了，就返回空。**

验证指令措辞不是原因（均不带 suffix）：

| 指令 | `finish_reason` | 长度 |
|---|---|---|
| 现行中文指令 | `stop` | 1134 |
| 更短指令"补全代码：" | `stop` | 22 |
| 空指令 | `stop` | 1313 |
| 英文指令 | `stop` | 1303 |

<br/>

## 三、根因

**百炼 Partial Mode 对"已经提供了完整上下文"的输入会判定为无需补全，直接以 `finish_reason=stop` 返回空内容。**

具体机制（结合观察推断，官方未明确说明）：

1. Partial Mode 的语义是"从 `assistant.partial` 的 content 继续往下写"；
2. 当 `user`/`system` 里又额外描述了"后面已经存在什么"，模型收到的信号变成
   "这个代码块已经完整了，没有中间缺口"；
3. 于是它选择立即停止（`stop`），而不是生成内容。

**为什么之前的 curl 测试没发现**：早期只用了简短的前缀（几行），此时即使带 suffix 提示，
模型仍会补一些内容；只有**长前缀（1000+ 字符，看起来像完整方法/类）+ 长后缀**时，
"已经完整"的判断才被触发。这也解释了为什么这个问题在"简单示例"里测不出来、
一上真实业务代码就暴露。

<br/>

## 四、修复

### 4.1、代码修改

| 文件 | 改动 |
|---|---|
| `DashScopePartialWire.java` | suffix 提示从 `user` 消息移到 `system` 消息（教学上更合理），并**默认不启用** |
| `DashScopeFimModel.Builder` | `suffixHintEnabled` 默认值 `true` → **`false`** |
| `DashScopeStreamingFimModel.Builder` | 同上 |
| `DashScopeLiveVerificationTest` | 新增"空补全"专属断言，把这类静默失效单独暴露 |

### 4.2、关键断言（防止回归）

```java
// 单独把"空内容"拆出来断言：这类失效是 HTTP 200 + finish=stop，不报错也很难察觉。
// 如果只断言"收到块"，报错信息会误导成"流式没工作"，实际是模型没返回内容。
assertTrue(
        "["
                + scenario.name
                + "] 模型返回了空补全（HTTP 200，finish="
                + handler.response.finishReason()
                + "）。请检查 suffix 是否被放进 user 消息（应放 system）。",
        !handler.response.isEmpty());
```

### 4.3、修复后实测

全部 5 个场景（真实端点、qwen3-coder-flash）：

```text
========== 耗时汇总 ==========
场景                                       TTFT(ms)   完整(ms)   墙钟(ms)    块数    块间隔avg
----------------------------------------------------------------------------------------------
1号 Agent：ReAct 工具调用循环（长上...          661       2650       2654      56       32.3ms
2号 流式：SSE 增量聚合（长上下文）              287       2511       2510      67       33.5ms
3号 业务：事务 + 分页 + Stream 映射...          249       1768       1768      48       31.4ms
4号 并发：分布式锁 + 重试退避（长上...          309        507        506       8       23.7ms
5号 协议层：接口默认方法（长上下文）            233        655        655       16       26.1ms
----------------------------------------------------------------------------------------------
平均 TTFT = 347ms / 平均完整耗时 = 1618ms
总块数 = 195 / 总字符 = 3711
```

全部有输出，无一为空。

### 4.4、优化前后 curl 请求体对比（重点）

同一份输入（场景 1：prefix 1092 字符 / suffix 192 字符），只改消息结构：

| 对比项 | 优化前（❌ 空返回） | 优化后（✅ 正常） |
|---|---|---|
| 消息数量 | 2 条 | 2 条 |
| `user` 消息内容 | 指令 **+ 一大段 suffix 代码提示** | **只有指令** |
| `system` 消息 | 无 | 无（默认关闭） |
| `assistant` 消息 | 前缀 + `partial:true` | 前缀 + `partial:true` |
| `stop` | `["\n\n    public ", "\n\n    private ", "```"]` | 同 |
| `finish_reason` | `stop` | `stop` |
| **`content` 长度** | **0** | **1134** |
| `prompt_tokens` | 292 | **233** |
| `completion_tokens` | **0** | **209** |

> 两个额外收益：**prompt 更短（省 token）**、**有实际输出（不是白花钱）**。

#### 优化前的请求体（会返回空）

```json
{
  "model": "qwen3-coder-flash",
  "messages": [
    {
      "role": "user",
      "content": "请补全这段代码中间缺失的部分，只输出需要补全的代码，不要输出解释或 Markdown 代码块。\n\n光标之后已经存在下面这些代码，请勿重复生成它们：\n```\n        return response.aiMessage().text();\n    }\n\n    private void logIteration(int iteration, String toolName) {\n        System.out.println(\"[iter \" + iteration + \"] \" + toolName);\n    }\n}\n\n```"
    },
    {
      "role": "assistant",
      "content": "<PREFIX 1092 字符>",
      "partial": true
    }
  ],
  "max_tokens": 256,
  "temperature": 0,
  "stop": ["\n\n    public ", "\n\n    private ", "```"]
}
```

返回：

```json
{
  "choices": [{ "finish_reason": "stop", "message": { "role": "assistant", "content": "" } }],
  "usage": { "prompt_tokens": 292, "completion_tokens": 0, "total_tokens": 292 }
}
```

#### 优化后的请求体（框架当前默认发出）

```json
{
  "model": "qwen3-coder-flash",
  "messages": [
    {
      "role": "user",
      "content": "请补全这段代码中间缺失的部分，只输出需要补全的代码，不要输出解释或 Markdown 代码块。"
    },
    {
      "role": "assistant",
      "content": "<PREFIX 1092 字符>",
      "partial": true
    }
  ],
  "max_tokens": 256,
  "temperature": 0,
  "stop": ["\n\n    public ", "\n\n    private ", "```"]
}
```

返回：

```json
{
  "choices": [{ "finish_reason": "stop", "message": { "role": "assistant", "content": "<1134 字符的补全代码>" } }],
  "usage": { "prompt_tokens": 233, "completion_tokens": 209, "total_tokens": 442 }
}
```

#### 对应的 curl 命令

```bash
# 优化后（推荐）：纯前缀续写，不带 suffix 提示
curl -sS https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions \
  -H "Authorization: Bearer $DASHSCOPE_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen3-coder-flash",
    "messages": [
      {"role": "user", "content": "请补全这段代码中间缺失的部分，只输出需要补全的代码，不要输出解释或 Markdown 代码块。"},
      {"role": "assistant", "content": "    public String run(String userInput) {\n        ChatResponse response = chatModel.chat(request);\n        int iteration = 0;\n        ", "partial": true}
    ],
    "max_tokens": 256,
    "temperature": 0,
    "stop": ["\n\n    public ", "\n\n    private ", "```"]
  }'
```

> 复现方式：把上面 `assistant.content` 换成你真实的 1000+ 字符前缀，
> 然后在 `user.content` 末尾追加「光标之后已经存在下面这些代码」加你对应的后缀代码块，
> 就能观察到 `content` 变成空、`completion_tokens` 变成 0。

<br/>

## 五、更深一层的结论：suffix 提示是伪需求

修复不只是"换个位置"，而是承认一个事实：

> **在 Partial Mode 下，"把 suffix 告诉模型"这件事本身是伪需求。**

原因有两层：

1. **它不生效**。模型拿到 suffix 描述后，反而更容易判定"已经完整"，从而返回空（本次踩的坑）。
2. **它本质不等价于 FIM**。Partial Mode 没有 suffix 字段，提示词只是"用自然语言告诉模型后文"，
   模型并不会因此获得 $P(\text{Middle} \mid \text{Prefix}, \text{Suffix})$ 的能力。

实测也验证了这一点：即使不返回空（短前缀场景），模型**仍会重复生成后文代码**——
因为它的训练目标里没有"避免重写后文"这一项。

**所以结论很直接：百炼 Partial Mode 只能做"前缀续写"，做不了真正的中间填充。
需要 FIM 能力请用 DeepSeek FIM（`/completions` + 原生 `suffix`）或本地 Qwen FIM 权重。**

<br/>

## 六、经验教训

| 教训 | 说明 |
|---|---|
| **断言要分层** | "收到块"和"内容非空"是两件事。只断言前者，会把"模型没生成"误报成"流式坏了" |
| **HTTP 200 ≠ 成功** | 大模型服务常见"成功但空结果"，必须对 `content` 做非空校验 |
| **简单示例测不出真问题** | 本次坑只在 1000+ 字符真实前缀下触发，短示例完全正常 |
| **用真实 curl 反推** | 先最小复现传输层，再逐步收窄请求体，比盯着代码猜快得多 |
| **变量隔离要彻底** | 一次只改一个变量（stop / max_tokens / 围栏 / 消息位置），否则会得出错误结论 |
| **静默失效优先于体验优化** | "让模型别重复后文"这类优化，若可能引发空返回，应默认关闭 |

<br/>

## 七、排查速查表

遇到"百炼返回空内容"时按顺序查：

| 检查项 | 期望值 | 不对怎么办 |
|---|---|---|
| HTTP 状态 | 200 | 401 查 Key 与地域；404 查 base_url |
| `finish_reason` | `stop` / `length` | `content_filter` 查内容策略 |
| `choices[0].message.content` 长度 | > 0 | 为 0 见下一条 |
| 是否启用了 `suffixHintEnabled` | **应为 false** | 关掉后重试 |
| 最后一条消息 | `role=assistant` + `partial=true` | 调整消息顺序 |
| 前缀是否为"看起来完整"的代码 | 是则易触发空返回 | 截短前缀尾部或改用纯续写 |
| `max_tokens` | ≥ 64 | 过小会 `length`（不会为空，但会截断） |

<br/>

## 参考资料来源

[1] 阿里云百炼. *前缀续写（Partial Mode）* — 协议定义与调用示例（无 suffix 字段）。  
https://help.aliyun.com/zh/model-studio/partial-mode

[2] 阿里云百炼. *OpenAI 兼容 - Chat* — 端点与地域说明。  
https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope

[3] 阿里云百炼. *模型列表* — 模型可用性与 Partial Mode 支持范围对照。  
https://help.aliyun.com/zh/model-studio/getting-started/models

[4] 阿里云百炼. *错误码* — 用于排除 401/404 类问题。  
https://help.aliyun.com/zh/model-studio/error-code

[5] DeepSeek. *FIM 补全（Beta）* — 原生 `suffix` 方案（真正的 FIM 能力）。  
https://api-docs.deepseek.com/zh-cn/guides/fim_completion

[6] OpenAI. *Create completion* — `suffix` 参数语义对照。  
https://platform.openai.com/docs/api-reference/completions/create

---

整理者：长路　创建时间：2026-11-25