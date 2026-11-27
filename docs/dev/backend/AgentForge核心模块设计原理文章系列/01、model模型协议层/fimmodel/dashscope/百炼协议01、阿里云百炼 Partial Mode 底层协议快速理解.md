# 百炼协议01、阿里云百炼（DashScope）Partial Mode 底层协议快速理解

> 更新日期：2026-11-25  
> 适用场景：理解 `agentforge-model-dashscope` 所对接的百炼 Partial Mode wire protocol  
> 维护者：changlu  
> 核对方式：本文所有接口结论均来自当日可访问的官方文档，链接见文末

一句话结论：**百炼没有 OpenAI 风格的 `/completions` + `suffix` FIM 接口**，它提供的是
**Partial Mode（前缀续写）**——把前缀放进最后一条 `role=assistant` 消息并设置 `partial: true`，
端点仍是 `/chat/completions`。

---

## 一、背景与问题引入

### 1.1、为什么不能用 DeepSeek 那套直接对接

DeepSeek 的 FIM 是 OpenAI Completions 形状：

```http
POST https://api.deepseek.com/beta/completions
{ "model": "deepseek-flash", "prompt": "<前缀>", "suffix": "<后缀>" }
```

换成百炼时，同样的请求会撞两堵墙：

1. 百炼的 OpenAI 兼容模式**只提供 `/chat/completions`**，没有 `/completions`；
2. 即使强行发到 `/chat/completions`，**它也没有 `suffix` 字段**。

也就是说：**同一个 `OpenAiFimModel` 无法对接百炼**，必须单独适配。

### 1.2、百炼的等价能力叫什么

官方文档里叫 **「前缀续写」Partial Mode**，定位写得很明确：

> 在代码补全、文本续写等场景中，需要模型从已有的文本片段（前缀）开始继续生成。
> Partial Mode 可提供精确控制能力，确保模型输出的内容紧密衔接提供的前缀。

<br/>

## 二、协议本体

### 2.1、端点

```http
POST {BASE_URL}/chat/completions
```

`BASE_URL`（OpenAI SDK 用，**不含** `/chat/completions`）：

| 地域 | BASE_URL |
|---|---|
| 华北2（北京） | `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` |
| 新加坡 | `https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1` |
| 美国（弗吉尼亚） | `https://{WorkspaceId}.us-east-1.maas.aliyuncs.com/compatible-mode/v1` |
| 日本（东京） | `https://{WorkspaceId}.ap-northeast-1.maas.aliyuncs.com/compatible-mode/v1` |
| 德国（法兰克福） | `https://{WorkspaceId}.eu-central-1.maas.aliyuncs.com/compatible-mode/v1` |
| 中国香港 | `https://{WorkspaceId}.cn-hongkong.maas.aliyuncs.com/compatible-mode/v1` |

> 旧域名 `https://dashscope.aliyuncs.com/compatible-mode/v1`（北京）、
> `https://dashscope-intl.aliyuncs.com`（新加坡）**仍可正常使用**，官方建议迁移到业务空间专属域名。

**API Key 与地域绑定**：用北京 Key 调弗吉尼亚 endpoint 会返回 `HTTP 401 invalid_api_key`，
这不是 Key 失效，而是地域不匹配。

### 2.2、触发条件（最容易写错的地方）

Partial Mode 不是靠某个"模式参数"，而是靠**消息数组的最后一条**：

```json
{
  "model": "qwen3-coder-flash",
  "messages": [
    { "role": "user", "content": "请补全这个函数，勿添加其它内容" },
    { "role": "assistant", "content": "<前缀>", "partial": true }
  ]
}
```

三条硬性要求：

1. **最后一条**必须是 `role: "assistant"`；
2. 该消息 `content` 就是**前缀正文**；
3. 该消息必须带 `"partial": true`。

**只要少任何一个条件，请求依然会 200 成功**，但会退化成"普通对话生成"——模型可能把前缀复述一遍、
或给出解释、或加上 Markdown 围栏。这是静默失效，不会报错，所以接入层必须自己保证形状正确。

### 2.3、请求字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `model` | ✅ | 模型名，见第四章支持清单 |
| `messages` | ✅ | 普通 chat 消息数组，最后一条为 partial assistant |
| `max_tokens` | ❌ | 补全上限（只约束**新增**部分） |
| `temperature` | ❌ | 代码补全建议 `0` |
| `top_p` | ❌ | 与 temperature 二选一调 |
| `stop` | ❌ | 行内补全强烈建议设置 |
| `stream` | ❌ | `true` 时 SSE |
| `stream_options.include_usage` | ❌ | 需配合 `stream: true` |

### 2.4、响应

**非流式**（注意与 Completions 的差别）：

```json
{
  "id": "chatcmpl-d103b1cf-...",
  "object": "chat.completion",
  "model": "qwen3-coder-flash",
  "choices": [
    {
      "index": 0,
      "logprobs": null,
      "finish_reason": "stop",
      "message": { "role": "assistant", "content": "    return a + b;" }
    }
  ],
  "usage": {
    "prompt_tokens": 48,
    "completion_tokens": 19,
    "total_tokens": 67,
    "prompt_tokens_details": { "cache_type": "implicit", "cached_tokens": 0 }
  }
}
```

| 维度 | OpenAI Completions（DeepSeek FIM） | 百炼 Partial Mode |
|---|---|---|
| 文本位置 | `choices[0].text` | `choices[0].message.content` |
| 对象类型 | `text_completion` | `chat.completion` |
| 流式增量 | `choices[0].text` | `choices[0].delta.content` |
| 缓存字段 | `prompt_cache_hit_tokens` | `prompt_tokens_details.cached_tokens` |
| 前/后文 | `prompt` + `suffix` | 前缀进 assistant，**无 suffix** |

**流式**：标准 SSE，`data: {...}` 逐块，末块 `finish_reason` 非空，最后 `data: [DONE]`。

```text
data: {"choices":[{"delta":{"role":"assistant","content":""},"finish_reason":null}],"usage":null}
data: {"choices":[{"delta":{"content":"    return"},"finish_reason":null}],"usage":null}
data: {"choices":[{"delta":{"content":" a + b;"},"finish_reason":null}],"usage":null}
data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{...}}
data: [DONE]
```

<br/>

## 三、核心限制：只有前缀，没有后缀

这是本文最需要强调的一点。

### 3.1、Partial Mode ≠ 严格意义的 FIM

FIM 建模的是 $P(\text{Middle} \mid \text{Prefix}, \text{Suffix})$，而 Partial Mode 只有：

$$P(\text{Continuation} \mid \text{Prefix})$$

后果很具体——回到我们那个 Java 例子：

```java
public User getUser(Long id) {
    User user = userMapper.selectById(id);
    // ← 光标
    return user;
}
```

- **DeepSeek 原生 FIM**：知道后面已有 `return user;`，于是只补空值校验；
- **百炼 Partial Mode**：看不到后文，很可能把 `return user;` 再写一遍。

这不是百炼做得不好，而是协议里根本没有承载 suffix 的位置。

### 3.2、工程上的两种补偿

| 做法 | 效果 | 代价 |
|---|---|---|
| 把 suffix 塞进 `user` 提示词（"光标之后已存在以下代码，勿重复生成"） | 有改善，但仍非原生 FIM | 提示词变长、延迟上升、指令遵循不稳定 |
| 纯前缀续写，不告知后文 | 最忠实于协议 | 重复生成概率最高 |

当前实现默认**开启提示词弱约束**（`suffixHintEnabled = true`），可关掉。这个开关只是"提示词补偿"，
**不改变模型没有 FIM 能力这一事实**。

### 3.3、什么时候该用、什么时候不该用

| 场景 | 建议 |
|---|---|
| 行内实时代码补全（Cursor / Copilot 式） | ⚠️ 优先用 DeepSeek FIM 或本地 Qwen FIM 权重 |
| 离线批量补全、代码审查辅助 | ✅ 可用，延迟不敏感 |
| 数据不能出境、已统一在百炼体系 | ✅ 可用，接受无 suffix 的降级 |
| 需要精确"中间填充" | ❌ 不适合 |

<br/>

## 四、支持的模型

官方 Partial Mode 支持清单（文档原文整理）：

### 4.1、文本生成模型

| 类别 | 支持模型 |
|---|---|
| Qwen4-Max | `qwen4-max` |
| 千问 Max（非思考模式） | Qwen3.8-Max、Qwen3.7-Max、Qwen3.6-Max、Qwen3-Max、Qwen-Max 系列 |
| 千问 Plus（非思考模式） | Qwen3.7-Plus、Qwen3.6-Plus、Qwen3.5-Plus、Qwen-Plus 系列 |
| 千问 Flash（非思考模式） | Qwen3.7-Flash、Qwen3.6-Flash、Qwen3.5-Flash、Qwen-Flash 系列 |
| **千问 Coder** | **Qwen3-Coder 系列、Qwen2.5-Coder 系列、Qwen-Coder 系列** |
| 千问 Turbo（非思考模式） | Qwen-Turbo 系列 |
| Qwen3.6 / 3.5 / 3 / 2.5 开源系列 | 非思考模式 |
| 千问 Math | Qwen-Math、Qwen2.5-Math 系列 |
| DeepSeek（硅基流动部署） | `siliconflow/deepseek-v3.1-terminus`（非思考模式）、`siliconflow/deepseek-v3-0324` |
| DeepSeek（快手万擎部署） | `vanchin/deepseek-v3.2-think` |
| Ling（蚂蚁数科部署） | `antdigital/Ling-3.1-flash` |

### 4.2、多模态模型（代码补全用不到，列出供完整参考）

千问 VL（Qwen3-VL-Plus / Flash、Qwen-VL-Max / Plus、Qwen3-VL 开源系列）、
Kimi（`kimi/kimi-k3.1-pro`、`kimi/kimi-k3`、`kimi/kimi-k2.6`、`kimi/kimi-k2.5` 等）。

### 4.3、关于 `deepseek-v4.1-flash`

**它不在 Partial Mode 支持清单内。** 这一点很关键：

- 百炼的模型列表里确实有 `deepseek-v4.1-flash`（可正常 Chat）；
- 但 Partial Mode 的 DeepSeek 支持项只有上面那三个三方直供版本；
- 因此用它做前缀续写，**可能不生效（退化成普通生成）或直接报错**，需实测确认。

**结论：百炼侧做补全，应选 `qwen3-coder-flash` 或 Qwen2.5-Coder 系列。**

<br/>

## 五、与 OpenAI / DeepSeek 协议的关系

```text
OpenAI legacy Completions  ——  prompt + suffix，text_completion
        │
        ├── DeepSeek FIM（Beta）    —— 同形状，换 base 为 /beta
        │
        └── 百炼 Partial Mode      —— ✗ 不适用（无 /completions、无 suffix）
                                    改用 chat/completions + assistant.partial=true
```

所以 AgentForge 的模块划分是：

| Provider | 协议形态 | 端点 |
|---|---|---|
| `OpenAiFimModel` | `NATIVE_SUFFIX` | `{base}/completions` |
| `DashScopeFimModel` | `PREFIX_PARTIAL`（chat 特例） | `{base}/chat/completions` |

<br/>

## 六、常见错误与排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 200 但模型把前缀复述一遍 | 少了 `partial: true`，或最后一条不是 assistant | 检查消息数组形状 |
| 200 但输出带解释/Markdown | 退化成问答模式 | 同上；并加 `stop: ["```"]` |
| 404 | BASE_URL 拼错（多写/少写 `/chat/completions`） | SDK 用 base，HTTP 用完整 endpoint |
| 401 `invalid_api_key` | Key 与地域不匹配 | 用同地域 Key |
| 400 提示模型不支持 | 模型不在 Partial Mode 清单 | 换 `qwen3-coder-flash` 等 |
| 补全重复了后文代码 | 无原生 suffix 的固有限制 | 开启 `suffixHintEnabled`，或改用 DeepSeek FIM |

---

## 参考资料来源

> 均于 2026-11-25 实际访问核对。

[1] 阿里云百炼. *前缀续写（Partial Mode）* — **本文协议核心依据**，含触发条件、支持模型清单、OpenAI 兼容与 DashScope 两种调用示例。  
https://help.aliyun.com/zh/model-studio/partial-mode

[2] 阿里云百炼. *OpenAI 兼容 - Chat* — BASE_URL 各地域清单、`/chat/completions` 端点、地域与 Key 绑定规则。  
https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope

[3] 阿里云百炼. *OpenAI 兼容 Chat Completions 参数说明* — 请求字段、`stream_options`、响应示例。  
https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions

[4] 阿里云百炼. *代码能力（Qwen-Coder）* — Coder 系列能力与调用示例。  
https://help.aliyun.com/zh/model-studio/qwen-coder

[5] 阿里云百炼. *模型列表* — 引用其中 `deepseek-v4.1-flash` 存在于模型列表，但**未**出现在 Partial Mode 支持清单（见 [1]）。  
https://help.aliyun.com/zh/model-studio/getting-started/models

[6] 阿里云百炼. *错误码* — `invalid_api_key` 等错误含义。  
https://help.aliyun.com/zh/model-studio/error-code

[7] OpenAI. *Create completion* — 对照用：`suffix` 参数与 `text_completion` 响应形状。  
https://platform.openai.com/docs/api-reference/completions/create

[8] DeepSeek. *FIM 补全（Beta）* — 对照用：原生 suffix 的完整 FIM 方案。  
https://api-docs.deepseek.com/zh-cn/guides/fim_completion

---

整理者：长路　创建时间：2026-11-25