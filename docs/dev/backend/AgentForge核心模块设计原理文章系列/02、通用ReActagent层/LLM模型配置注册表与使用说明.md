# LLM 模型配置注册表与使用说明

## 1. 背景

ReAct Agent 需要知道当前模型的上下文上限、最大输出上限和多模态能力，以便：

- 为上下文压缩中间件设置正确的 Token 阈值；
- 向 Chat Model 传递合理的最大输出 Token 数；
- 在 Agent 配置页面切换模型时自动带出模型默认值；
- 判断模型是否支持文本、图片等输入。

AgentForge 使用下列两个类统一管理这些信息：

- `LlmModelConfig`：单个 LLM 的能力与 Token 上限配置实体；
- `LlmModelConfigRegistry`：内置模型和运行时模型的注册、查询与降级匹配入口。

源码位置：

- `agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/config/LlmModelConfig.java`
- `agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/config/LlmModelConfigRegistry.java`

## 2. LlmModelConfig 字段说明

| 字段 | 含义 | 单位/说明 |
| --- | --- | --- |
| `baseUrl` | 模型服务地址 | 用于区分不同供应商下的同名模型 |
| `modelName` | API 请求使用的模型名 | 例如 `deepseek-flash` |
| `maxInputKTokens` | 最大输入/上下文 Token | K，`128` 表示 `128000 tokens` |
| `maxOutputKTokens` | 最大输出 Token | K，`64` 表示 `64000 tokens` |
| `supportsText` | 是否支持文本输入 | 布尔值 |
| `supportsImage` | 是否支持图片输入 | 布尔值 |

Token Getter 的单位已在方法名中明确表达：

| 方法 | 返回示例 | 说明 |
| --- | ---: | --- |
| `getMaxInputKTokens()` | `128` | K 单位输入上限 |
| `getMaxOutputKTokens()` | `64` | K 单位输出上限 |
| `getMaxInputTokens()` | `128000` | 实际输入 Token 数 |
| `getMaxOutputTokens()` | `64000` | 实际输出 Token 数 |

`isSupportsMultimodal()` 当前等价于 `supportsImage`。未来增加音频、视频或文件能力时，可再将其扩展为综合判断。

## 3. 内置模型支持矩阵

> 下表与当前 `LlmModelConfigRegistry` 代码保持一致。输入和输出列的单位均为 K Token。“图片”为“是”时，`isSupportsMultimodal()` 也返回 `true`。

| 供应商/端点 | 模型名 | 输入 K | 输出 K | 文本 | 图片 |
| --- | --- | ---: | ---: | :---: | :---: |
| DeepSeek `https://api.deepseek.com` | `deepseek-flash` | 1024 | 384 | 是 | 是 |
| DeepSeek | `deepseek-v4.1-flash` | 1024 | 384 | 是 | 是 |
| DeepSeek | `deepseek-v4.1-flash-1017` | 1024 | 384 | 是 | 是 |
| DeepSeek | `deepseek-v4-flash` | 1024 | 384 | 是 | 是 |
| DeepSeek | `deepseek-v4-pro` | 1024 | 384 | 是 | 否 |
| TokenRhythm `https://tokenrhythm.studio/v1` | `deepseek-v4-flash` | 1024 | 384 | 是 | 否 |
| TokenRhythm | `deepseek-v4-pro` | 1024 | 384 | 是 | 否 |
| TokenRhythm | `deepseek-v4-flash-0731` | 1024 | 384 | 是 | 否 |
| TokenRhythm | `seed-2.1-pro` | 262 | 131 | 是 | 是 |
| TokenRhythm | `seed-2.1-turbo` | 262 | 131 | 是 | 是 |
| TokenRhythm | `glm-5` | 1024 | 128 | 是 | 否 |
| TokenRhythm | `glm-5.1` | 200 | 128 | 是 | 否 |
| TokenRhythm | `glm-5.2` | 1024 | 128 | 是 | 否 |
| TokenRhythm | `kimi-k2.5` | 256 | 64 | 是 | 是 |
| TokenRhythm | `kimi-k2.6` | 256 | 128 | 是 | 是 |
| TokenRhythm | `kimi-k2.7-code` | 256 | 128 | 是 | 是 |
| TokenRhythm | `mimo-v2.5-pro` | 256 | 256 | 是 | 否 |
| TokenRhythm | `minimax-m2.5` | 200 | 200 | 是 | 否 |
| TokenRhythm | `minimax-m2.7` | 200 | 192 | 是 | 否 |
| TokenRhythm | `qwen3.7-max` | 1024 | 131 | 是 | 否 |
| TokenRhythm | `qwen3.8-max` | 1024 | 131 | 是 | 是 |
| NVIDIA `https://integrate.api.nvidia.com/v1` | `z-ai/glm4.7` | 128 | 128 | 是 | 否 |
| 智谱 `https://open.bigmodel.cn/api/coding/paas/v4` | `glm-5.3-flash` | 1024 | 128 | 是 | 是 |
| 智谱 | `glm-5.3` | 1024 | 128 | 是 | 否 |
| 美团 LongCat `https://api.longcat.chat/openai/v1` | `LongCat-Flash-Omni-2603` | 128 | 8 | 是 | 是 |
| 美团 LongCat | `LongCat-Flash-Chat` | 256 | 128 | 是 | 否 |
| 美团 LongCat | `LongCat-Flash-Thinking` | 128 | 256 | 是 | 否 |
| 美团 LongCat | `LongCat-Flash-Thinking-2601` | 128 | 256 | 是 | 否 |
| 美团 LongCat | `LongCat-Flash-Lite` | 256 | 320 | 是 | 否 |
| ModelScope `https://api-inference.modelscope.cn/v1` | `deepseek-ai/DeepSeek-V4-Pro` | 1024 | 384 | 是 | 否 |
| 小米 MiMo `https://token-plan-cn.xiaomimimo.com/v1` | `mimo-v2.5-pro` | 1024 | 128 | 是 | 否 |
| 小米 MiMo | `mimo-v2-pro` | 1024 | 128 | 是 | 否 |
| 小米 MiMo | `mimo-v2.5` | 1024 | 128 | 是 | 否 |
| 小米 MiMo | `mimo-v2-omni` | 256 | 128 | 是 | 是 |
| 小米 MiMo | `mimo-v2-flash` | 256 | 64 | 是 | 否 |
| OpenAI `https://api.openai.com/v1` | `gpt-5.5` | 1024 | 128 | 是 | 是 |
| OpenAI | `gpt-5.4` | 1024 | 128 | 是 | 是 |
| OpenAI | `gpt-5.4-mini` | 400 | 128 | 是 | 是 |
| OpenAI | `gpt-5.4-nano` | 400 | 64 | 是 | 否 |
| 阿里云百炼 `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen3.7-plus` | 1024 | 64 | 是 | 是 |
| 阿里云百炼 | `qwen3.7-max` | 1024 | 64 | 是 | 是 |
| 阿里云百炼 | `qwen3.6-plus` | 1024 | 64 | 是 | 是 |
| 阿里云百炼 | `qwen3.6-max` | 1024 | 64 | 是 | 否 |
| 阿里云百炼 | `qwen3.6-flash` | 1024 | 64 | 是 | 是 |
| 阿里云百炼 | `qwen3.5-plus` | 1024 | 64 | 是 | 是 |
| 阿里云百炼 | `qwen3` | 1024 | 64 | 是 | 否 |

### 3.1 DeepSeek 官方参考

- 官方模型列表：<https://api-docs.deepseek.com/api/list-models/>
- 官方模型规格与定价：<https://api-docs.deepseek.com/quick_start/pricing/>
- V4.1 Flash 发布和兼容别名：<https://api-docs.deepseek.com/updates/>

DeepSeek 官方 API 当前推荐使用 `deepseek-flash`，实际版本为 DeepSeek-V4.1-Flash-1017。`deepseek-v4.1-flash` 和 `deepseek-v4.1-flash-1017` 是 AgentForge 为自定义网关和版本化模型名增加的能力匹配别名，不代表 DeepSeek 官方 API 一定接受该请求参数。

## 4. 核心接口设计

注册表对外只提供一个核心接口：

| 接口 | 职责 | 适用于 |
| --- | --- | --- |
| `LlmModelConfigRegistryApi` | 注册、查询、检查和枚举模型配置 | Agent 工厂、Options 服务、模型扩展 |

默认实例由静态门面提供：

```java
LlmModelConfigRegistryApi registry =
        LlmModelConfigRegistry.registry();
```

`LlmModelConfigRegistry` 保留默认静态实现，业务层通过 `LlmModelConfigRegistryApi` 使用，后续如果需要数据库或远程配置实现，可直接扩展该接口。

## 5. 配置查询与降级规则

```java
LlmModelConfigRegistryApi registry = LlmModelConfigRegistry.registry();
LlmModelConfig config = registry.getConfig(baseUrl, modelName);
```

注册表按以下顺序查询：

1. `baseUrl + modelName` 精确匹配；
2. URL 前缀兼容匹配，例如注册地址为 `/v1`，运行地址为 `/v1/chat`；
3. 忽略 URL，仅根据 `modelName` 降级匹配；
4. 仍未匹配时返回默认配置：`128K` 输入、`64K` 输出、仅支持文本。

查询结果始终非 `null`。

### 5.1 为什么应优先传 baseUrl

同一个模型名可能同时存在于多个供应商，而且能力与 Token 上限可能不同。例如 `qwen3.7-max` 同时存在于 TokenRhythm 和阿里云百炼配置中。

因此业务代码应尽量传入完整的 `baseUrl` 和 `modelName`，不要仅依赖模型名降级。

## 6. 基本用法

### 6.1 查询模型配置

```java
LlmModelConfigRegistryApi registry = LlmModelConfigRegistry.registry();
LlmModelConfig config =
        registry.getConfig("https://api.deepseek.com", "deepseek-flash");

int maxInputKTokens = config.getMaxInputKTokens();   // 1024
int maxOutputKTokens = config.getMaxOutputKTokens(); // 384
int maxInputTokens = config.getMaxInputTokens();     // 1024000
int maxOutputTokens = config.getMaxOutputTokens();   // 384000
boolean supportsText = config.isSupportsText();      // true
boolean supportsImage = config.isSupportsImage();    // true
```

### 6.2 只获取实际 Token 数

```java
int maxInputTokens =
        registry.getMaxInputTokens(baseUrl, modelName);

int maxOutputTokens =
        registry.getMaxOutputTokens(baseUrl, modelName);
```

### 6.3 判断是否存在配置

```java
boolean exact =
        registry.hasExactConfig(baseUrl, modelName);

boolean knownModel =
        registry.hasModelNameConfig(modelName);
```

`hasExactConfig` 适合判断当前供应商与模型组合是否已显式登记；`hasModelNameConfig` 只检查模型名是否存在于任意供应商。

## 7. 运行时注册自定义模型

业务接入新模型或私有网关时，可以不修改内置列表，直接在应用启动时注册：

```java
LlmModelConfigRegistryApi registry = LlmModelConfigRegistry.registry();
registry.register(
        LlmModelConfig.builder()
                .baseUrl("https://models.example.com/v1")
                .modelName("example-vision-model")
                .maxInputKTokens(256)
                .maxOutputKTokens(32)
                .supportsText(true)
                .supportsImage(true)
                .build());
```

注册行为：

- 相同 `baseUrl + modelName` 的新配置会覆盖旧配置；
- 注册后可立即通过 `getConfig` 查询；
- 内部使用 `ConcurrentHashMap`，支持并发查询和注册；
- 相同 `modelName` 的后注册配置会成为“仅模型名匹配”时的降级结果，因此生产查询应传入 `baseUrl`。

可以通过以下方法查看所有按端点注册的配置：

```java
List<LlmModelConfig> configs =
        registry.registeredConfigs();
```

返回的 List 为只读视图，不应尝试修改。

## 8. ReAct Agent 运行时的使用

`ReActAgentFactory` 会使用数据库中模型配置的 URL 和模型名查询能力：

```java
LlmModelConfigRegistryApi registry = LlmModelConfigRegistry.registry();
LlmModelConfig modelConfig =
        registry.getConfig(llmConfig.getUrl(), llmConfig.getModelName());
```

实际参数优先级为：

```text
Agent 显式配置的 K Token
    ↓ 未配置或小于等于 0
LlmModelConfigRegistry 匹配的模型默认值
    ↓ 未登记模型
全局默认值（128K 输入 / 64K 输出）
```

其中：

- `getMaxInputTokens()` 的结果传给 `CompactSummarizationMiddleware`，作为上下文压缩上限；
- `getMaxOutputTokens()` 的结果传给 `ChatModelProperties.maxTokens`，作为模型最大输出参数。

## 9. Agent 配置页面的 Options 联动

Agent 页面不再通过模型管理分页接口获取下拉数据，而是调用专用接口：

```http
GET /api/model-config/options/models?modelType=0
```

每个 option 由 `ModelSelectionOptionVo` 表示。Web 层不直接暴露 Agent Core 的 `LlmModelConfig`，而是转换成轻量的 `ModelCapabilityOptionVo`，只返回页面当前需要的两个 K Token 参数：

```json
{
  "id": 1,
  "name": "DeepSeek Flash",
  "modelName": "deepseek-flash",
  "modelType": 0,
  "modelConfig": {
    "maxInputKTokens": 1024,
    "maxOutputKTokens": 384
  }
}
```

页面联动规则：

1. 首次打开已有 Agent 时，优先展示 Agent 已保存的输入/输出 K Token；
2. Agent 未显式保存参数时，使用 option 中 `modelConfig` 的默认值；
3. 用户主动切换对话模型时，使用新模型的 `maxInputKTokens` 和 `maxOutputKTokens` 覆盖高级模型参数输入框；
4. 用户可以继续手动调整，保存后作为 Agent 显式配置。

## 10. 新增或更新内置模型的约定

1. 优先查阅模型厂商官方模型列表、定价页或 API 文档；
2. 使用 API 真正接受的 `modelName`，展示名不应代替 API ID；
3. 为每个供应商保留官方参考链接，至少包含模型列表和 Token 规格来源；
4. 确认 Token 单位后再写入：注册表使用 K Token，不是实际 Token 数；
5. 多模态能力必须以 API 是否接受该输入为准，不应只根据模型宣传名称判断；
6. 增加或修改模型后，同步更新本文的支持矩阵和 `LlmModelConfigRegistryTest`。

## 11. 注意事项

- 注册表描述的是 AgentForge 使用的能力上限，实际网关还可能对 Token 上限进行二次限制；
- 自定义 OpenAI 兼容网关可以使用与官方不同的模型名，建议显式注册完整的 `baseUrl + modelName`；
- 未知模型会使用 `128K/64K` 默认值，该值只是保证系统可运行的降级配置，不代表模型的官方上限；
- `registeredConfigs()` 的返回顺序不作为稳定 API 保证，展示时应在业务层排序。
