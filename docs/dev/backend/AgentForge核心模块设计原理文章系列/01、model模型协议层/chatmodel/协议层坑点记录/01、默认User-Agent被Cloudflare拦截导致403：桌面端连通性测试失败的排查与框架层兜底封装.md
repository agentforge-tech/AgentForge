# 01、默认 User-Agent 被 Cloudflare 拦截导致 403：桌面端连通性测试失败的排查与框架层兜底封装

> 记录一次典型的“参数完全一样，curl 能通、Java 桌面端 403”的协议层问题，以及最终在 AgentForge
> model 协议层内置 User-Agent 兜底的设计与实现。
>
> 涉及模块：`agentforge-model-api` / `agentforge-model-core` / `agentforge-model-registry` /
> `AgentForge-Desktop`

---

## 一、结论先行

**401 是没通过鉴权，403 是没进业务层。** 本次报错的 403 与 API Key、模型名、Base URL 全部无关，
真正原因是：

> AgentForge 的 HTTP 传输层没有设置 `User-Agent`，JDK 8 的 `HttpURLConnection` 自动补上了
> `User-Agent: Java/1.8.0_412`；目标中转站前面挂着 Cloudflare 的 **Browser Integrity Check**，
> 该规则把 `Java/1.x` 这类非浏览器签名直接判定为爬虫，返回 **HTTP 403 + `error code: 1010`**，
> 请求在 Cloudflare 边缘就被拦掉，根本没有到达 new-api 业务网关。

修复方式：**在 transport 层统一内置产品化 User-Agent，并允许请求级 / 配置级覆盖**，一次修复覆盖
OpenAI / Anthropic、同步 / 流式全部调用路径。

---

## 二、问题现象

### 2.1 复现请求

命令行走原始 HTTP 完全正常：

```bash
curl https://deepseek5f.xyz/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-***" \
  -d '{"model":"deepseek-flash","messages":[{"role":"user","content":"你好"}],"stream":true}'
# 200，正常流式返回
```

但桌面端“模型管理 → 连通性测试”失败：

```
连通性测试未通过：模型连通性测试失败: OpenAI request failed with HTTP 403
```

桌面端保存的配置完全一致：

| 配置项 | 值 |
| --- | --- |
| 厂商 | OpenAI |
| 模型名称 | `deepseek-flash` |
| Base URL | `https://deepseek5f.xyz/v1` |
| API Key | `sk-***`（与 curl 完全相同） |

### 2.2 关键疑点

- Key 相同、URL 相同、模型相同、同一台机器，为什么 curl 通、Java 端 403？
- 403 走的是 `ModelConfigService.testConnectivity()` → `OpenAiChatModel` → `JdkHttpTransport` 全链路，
  错误信息由 `OpenAiChatModel` 抛出：

```java
// OpenAiChatModel#execute
throw new ModelException(
        "OpenAI request failed with HTTP " + response.statusCode(),
        response.statusCode(),
        response.body());   // ← 关键信息藏在这里，但上层只取了 getMessage()
```

---

## 三、排查过程与证据

### 3.1 先分清 401 / 403

对网关做无鉴权探测：

| 请求 | 响应 |
| --- | --- |
| `GET /v1/models`（不带 Key） | `401 Invalid token`，`server: cloudflare`，带 `x-new-api-version` |
| `POST /v1/chat/completions`（不带 Key） | `401 Invalid token` |

说明 upstream 是 **new-api（one-api 系）**，且 401 才是凭证错误的正常表现；403 一定发生在
鉴权之前，也就是 Cloudflare 或网关前置逻辑。

### 3.2 逐项排除“参数问题”

用同一个 Key 穷举请求形态，全部 200：

| 变量 | 结果 |
| --- | --- |
| `GET /v1/models` | 200 |
| Chat Completions：`stream=true` / `stream=false` | 200 |
| 强制 HTTP/1.1 / HTTP/2 | 200 |
| 携带 `Origin: http://127.0.0.1:1421` | 200 |
| UA 换成 `node` / `undici` / `axios` / `Electron` / 浏览器 UA | 200 |
| 错误路径 `/v1/v1/chat/completions` | 404（排除 URL 拼接问题） |

**结论：模型、Key、URL、路径拼接、Origin、HTTP 版本、常见 UA 都不是原因。**

### 3.3 用“Java 客户端特征”定位

既然桌面端是 Java，就直接对照 JDK 网络栈的特征继续穷举 UA：

| User-Agent | 结果 |
| --- | --- |
| `Java/1.8.0_412` | **403 `error code: 1010`** |
| `Java/1.8.0_402` | **403 `error code: 1010`** |
| `Apache-HttpClient/4.5.13 (Java/1.8.0_412)` | 200 |
| `Java-http-client/11.0.20`（JDK11 默认） | 200 |

命中。`error code: 1010` 是 Cloudflare 的标准错误码：

> The owner of this website has banned your access based on your browser's signature.

即 **Browser Integrity Check（浏览器完整性校验）**：它维护了一份“非浏览器客户端”签名黑名单，
`Java/1.x` 正在其中。注意它匹配的是**以 `Java/` 开头的签名**，而不是“包含 Java”。

### 3.4 用 JDK8 原生代码 100% 复现

按 `JdkHttpTransport` 的写法（`HttpURLConnection` + POST + 相同 header）写最小复现：

```java
HttpURLConnection c = (HttpURLConnection) new URL("https://deepseek5f.xyz/v1/chat/completions").openConnection();
c.setRequestMethod("POST");
c.setRequestProperty("Content-Type", "application/json");
c.setRequestProperty("Accept", "application/json");
// 不设置 User-Agent
c.setDoOutput(true);
c.setFixedLengthStreamingMode(payload.length);
// ... write body
int status = c.getResponseCode();
```

输出：

```
<JDK default UA>          ->  HTTP 403   error code: 1010
Java/1.8.0_412            ->  HTTP 403   error code: 1010
AgentForge-Desktop/0.1.0  ->  HTTP 401   （已过 Cloudflare，才轮到鉴权）
```

至此根因闭环：**JDK8 `HttpURLConnection` 的默认 UA 就是 `Java/1.8.0_412`，恰好撞上 Cloudflare
BIC 黑名单。**

### 3.5 完整 403 响应

```
HTTP/2 403
server: cloudflare
cf-ray: a5e0e652cf61e35b-NRT
content-type: text/plain; charset=UTF-8

error code: 1010
```

没有 `x-new-api-request-id`，进一步证明请求没有进入 new-api 业务层。

---

## 四、根因分析

### 4.1 三层事实

1. **JDK 层**：`java.net.HttpURLConnection` 在未显式设置 `User-Agent` 时，会自动写入
   `User-Agent: Java/<java.version>`（如 `Java/1.8.0_412`）。
2. **CDN 层**：Cloudflare Browser Integrity Check 将 `Java/1.x` 视为自动化客户端并直接 403，
   响应体固定为 `error code: 1010`，**发生在 WAF 鉴权之前**。
3. **框架层**：`JdkHttpTransport.openConnection()` 只透传调用方传入的 header，全仓库没有任何地方
   设置 `User-Agent`，于是第 1 条自动生效。

### 4.2 为什么“只有部分渠道挂”

- 直连 `api.openai.com`、`api.anthropic.com`：官方不启用该规则，默认 UA 不会报错。
- 套了 Cloudflare 的第三方中转站（new-api / one-api 部署几乎必然套 CF）：直接 403。
- 因此表现为“有些模型能测通、有些死活 403”，很容易被误判为“渠道不稳定 / Key 失效”。

### 4.3 为什么错误信息看不出原因

`ModelConfigService.message(e)` 只取 `e.getMessage()`，而 `ModelException` 的构造签名是
`(message, statusCode, body)`，真正的 `error code: 1010` 在 `body` 里被丢弃了。端上只能看到
`HTTP 403`，给排障造成很大干扰。

---

## 五、核心解决思路与设计方案

### 5.1 设计目标

1. **默认即正确**：任何 provider、同步/流式、任何调用方，只要用框架默认 transport，就绝不发送
   `Java/1.x` 签名。
2. **可覆盖**：业务方 / 单个模型可以显式指定 UA，用于网关白名单、QPS 归因、灰度统计等场景。
3. **优先级明确**：请求级 header > transport 构造参数 > 框架默认值，绝不允许“兜底值覆盖调用方”。
4. **修改面收敛**：只改 transport 一处即可覆盖全部 adapter，不在每个协议实现里重复塞 header。
5. **可观测**：UA 里带上产品名与版本，便于网关侧识别真实调用方。

### 5.2 方案对比

| 方案 | 做法 | 问题 |
| --- | --- | --- |
| A. 只改桌面端 | 在 `ModelConfigService` 里给每个模型加 header | 框架其他使用方（service、harness）仍然会挂；流式/非流式要重复改 |
| B. 每个 adapter 各加 | `OpenAiChatModel`、`OpenAiStreamingChatModel`、`Anthropic*` 各塞一个 header | 重复、易漏、无法约束自定义 transport |
| C. **transport 层统一兜底 + 配置覆盖**（采用） | `JdkHttpTransport` 内统一注入，缺失才补；开放构造参数与配置项 | 无 |

选择 **C**：User-Agent 是“HTTP 客户端身份”，属于传输层职责，天然应该由 transport 统一负责；
协议适配层只关心业务协议，不应该关心 UA。

### 5.3 分层设计

```
┌──────────────────────────────────────────────────────────────┐
│ 业务配置层  ChatModelProperties.userAgent / LlmConstant.USER_AGENT
│             （可选，默认不设置，交给 transport 兜底）            │
└───────────────────────────┬──────────────────────────────────┘
                            │ Properties
┌───────────────────────────▼──────────────────────────────────┐
│ Registry 层  OpenAiModel / AnthropicModel                     │
│              有配置 → builder.customHeader("User-Agent", ua)   │
└───────────────────────────┬──────────────────────────────────┘
                            │ HttpRequest.headers
┌───────────────────────────▼──────────────────────────────────┐
│ Transport 层 JdkHttpTransport                                  │
│              1. 请求已带 UA → 原样透传                          │
│              2. 未带 → 用构造参数 UA                            │
│              3. 构造参数为空 → HttpUserAgent.defaultUserAgent() │
└──────────────────────────────────────────────────────────────┘
```

**优先级（从高到低）**：

1. `HttpRequest.header("User-Agent", ...)`（单个请求显式指定）
2. `new JdkHttpTransport(userAgent)`（transport 实例级别）
3. `HttpUserAgent.defaultUserAgent()`（框架默认，`AgentForge/<version> (Java/...; OS)`）

### 5.4 为什么不在 UA 里伪装成浏览器

不采用 `Mozilla/5.0 ...` 伪装：

- 伪装浏览器会与真实 TLS/JA3、`sec-ch-ua`、HTTP/2 指纹不一致，反而触发更严格的风控
  （例如 CF 1010 的另一条规则就是“UA 与指纹不匹配”）。
- 中转站运营方通常允许明确的 SDK/产品 UA（便于统计与放行），但会封杀伪装。
- 产品化 UA 更利于问题定位与商务沟通。

---

## 六、实现细节

### 6.1 新增 `HttpUserAgent`（agentforge-model-api）

职责：集中生成默认 UA、判断请求是否已带 UA。放在 model-api，便于 registry / core / 业务方复用。

```java
public final class HttpUserAgent {

    public static final String HEADER = "User-Agent";

    private static final String PRODUCT = "AgentForge";
    private static final String FALLBACK_VERSION = "1.0";

    /** AgentForge/1.0 (Java/1.8.0_412; Mac OS X) */
    public static String defaultUserAgent() {
        return productUserAgent(PRODUCT, version());
    }

    /** 业务方可据此标记自己的应用：AgentForge-Desktop/0.1.0 (...) */
    public static String productUserAgent(String product, String version) {
        ...
    }

    /** 大小写不敏感判断 header map 中是否已有 User-Agent */
    public static boolean containsUserAgent(Map<String, String> headers) {
        ...
    }
}
```

要点：

- 版本号优先取 `Package#getImplementationVersion()`（打包后有效），运行期未打包时回退 `1.0`，
  避免出现 `AgentForge/null`。
- `java.version` / `os.name` 做 CRLF 清洗，防止 header 注入。

### 6.2 `JdkHttpTransport` 注入兜底（关键修复点）

```java
private final String userAgent;

public JdkHttpTransport() {
    this(HttpUserAgent.defaultUserAgent());
}

public JdkHttpTransport(String userAgent) {
    this.userAgent = userAgent == null || userAgent.trim().isEmpty()
            ? HttpUserAgent.defaultUserAgent()
            : userAgent.trim();
}

@Override
public HttpCall newCall(final HttpRequest request) {
    return new JdkHttpCall(request, userAgent);
}
```

`openConnection` 中：

```java
boolean hasUserAgent = false;
for (Map.Entry<String, String> header : request.headers().entrySet()) {
    if (HttpUserAgent.HEADER.equalsIgnoreCase(header.getKey())) {
        hasUserAgent = true;   // 调用方显式设置，尊重调用方
    }
    connection.setRequestProperty(header.getKey(), header.getValue());
}
if (!hasUserAgent) {
    connection.setRequestProperty(HttpUserAgent.HEADER, userAgent);
}
```

同步 `JdkHttpCall` 与流式 `stream()` 共用 `openConnection`，因此 **一处修改同时覆盖同步与流式**。

### 6.3 配置层可覆盖（agentforge-model-registry）

```java
// LlmConstant
String USER_AGENT = "userAgent";
```

```java
// ChatModelProperties
public Builder userAgent(String userAgent) { ... }
// toProperties() 中非空才写入，避免空值覆盖 transport 默认
```

`OpenAiModel` / `AnthropicModel` 在 `configureCommonParams` 中映射：

```java
String userAgent = props.getProperty(LlmConstant.USER_AGENT);
if (isNotEmpty(userAgent)) {
    builder.customHeader("User-Agent", userAgent.trim());
}
```

**注意**：只在显式配置时才注入 header，未配置时保持 `headers` 为空，把兜底权完全交给 transport，
避免 registry 层写死一个默认值、导致以后无法统一升级。

### 6.4 桌面端接入（AgentForge-Desktop）

```java
ChatModelProperties properties =
        ChatModelProperties.builder()
                .timeout(120000)
                .returnThinking(true)
                .userAgent(HttpUserAgent.productUserAgent("AgentForge-Desktop", "0.1.0"))
                .build();
```

这样网关侧日志能直接看到 `AgentForge-Desktop/0.1.0`，同时避免被 CF BIC 拦截。

---

## 七、测试与验证

### 7.1 单元测试

新增 `agentforge-model-core/src/test/.../http/JdkHttpTransportTest.java`，用 JDK 自带
`com.sun.net.httpserver.HttpServer` 起本地服务，断言真实发出的 header：

| 用例 | 断言 |
| --- | --- |
| 默认构造 | UA 等于 `HttpUserAgent.defaultUserAgent()`，且**不以 `Java/` 开头** |
| 请求显式 header | 以请求 header 为准 |
| 构造参数 | 使用构造参数 UA |
| 构造参数为空白 | 回退框架默认值 |
| 流式 `executeStreamingCancellable` | 同样携带配置的 UA |
| header 检测 | `containsUserAgent` 大小写不敏感 |

registry 层新增：

| 用例 | 断言 |
| --- | --- |
| `USER_AGENT` prop | `customHeaders["User-Agent"]` 正确映射 |
| 未配置 | `customHeaders` 为空（留给 transport 兜底） |

执行结果：

```
JdkHttpTransportTest        Tests run: 6,  Failures: 0
OpenAiModelTest             Tests run: 6,  Failures: 0
agentforge-model-registry   Tests run: 27, Failures: 0
BUILD SUCCESS
```

### 7.2 真实网关验证

| User-Agent | 结果 |
| --- | --- |
| `AgentForge/1.0 (Java/1.8.0_412; Mac OS X)` | 200 |
| `AgentForge-Desktop/0.1.0` | 200 |
| `AgentForge-Stream/1.0` | 200 |
| `Java/1.8.0_412` | 403 `error code: 1010` |

### 7.3 生效方式

```bash
# 框架侧安装到本地仓库（版本 1.0.1-SNAPSHOT）
mvn -pl agentforge-model/agentforge-model-api,agentforge-model/agentforge-model-core,agentforge-model/agentforge-model-registry \
    install -DskipTests

# 重启桌面端后端后重新执行“连通性测试”
```

---

## 八、同类坑点清单（协议层风险扫描）

本次问题暴露出的不是单点，而是一类“传输层默认行为 × 网关风控”的组合风险。建议长期关注：

| 风险点 | 说明 | 处理建议 |
| --- | --- | --- |
| **默认 User-Agent** | JDK `Java/1.x`、Python `python-requests`、Go `Go-http-client` 常被 WAF 403 | transport 层统一兜底（已修复） |
| **403 ≠ 401** | 403 通常代表被 CDN/WAF 拦截，不是凭证错误 | 排障先看 401/403、`server`、`cf-ray`、`error code` |
| **错误 body 被丢弃** | `ModelException` 已带 body，上层只取 message | 建议把 body 截断后拼入 message |
| **超时单位不一致** | `LlmConstant.TIMEOUT` 约定为秒，桌面端传 `120000` | 统一语义或增加单测（见遗留项） |
| **跟随重定向** | `HttpURLConnection` 默认跟随 302，POST 可能被降级为 GET 且丢失 Authorization | 需要时显式 `setInstanceFollowRedirects(false)` |
| **SSE 空行处理** | 流式按行读取时需保留事件边界 | provider 层已按协议解析，注意 `[DONE]` |

---

## 九、遗留问题与后续 TODO

1. **超时单位语义统一**
   - 现状：`ChatModelProperties.timeout` 默认 60（秒），`OpenAiModel` 内部 `* 1000` 转毫秒；
     桌面端 `ModelConfigService` 传入 `timeout(120000)`，实际得到 120000 秒（约 33 小时）的读超时。
   - 建议：统一为“秒”，桌面端改传 `120`；或在 `ChatModelProperties` 中明确暴露 `timeoutMillis`。

2. **错误信息透出**
   - 建议 `ModelConfigService.message(Throwable)` 对 `ModelException` 追加 `body`（截断 500 字符），
     这样“`error code: 1010` / `invalid_api_key` / `model_not_found`”能直接显示在 UI。

3. **自定义 Header 通用能力**
   - 目前仅开放了 `userAgent`。可考虑在 `LlmConstant` 增加 `CUSTOM_HEADERS`
     （如 `header.X-Foo=bar`），支持中转站要求的自定义鉴权头。

4. **可配置化默认 UA**
   - 可通过系统属性 `agentforge.http.userAgent` 覆盖全局默认值，便于企业内网按需设定。

---

## 十、复盘

- **最重要的认知**：`403` 先怀疑“被中间层拦截”，而不是“凭证错误”；`401` 才是凭证。
- **最有效的动作**：把桌面端的“Java 客户端特征”搬到 curl 里做对照实验，一次命中。
- **最合适的修复位置**：HTTP 身份属于传输层职责，放在 `JdkHttpTransport` 一次性兜底，
  比在 N 个协议适配器里重复补 header 更稳、更可维护。
- **最该补的能力**：把 upstream 的错误 body 透出到 UI，否则任何 4xx 都只能看到一句
  `HTTP xxx`，排障成本极高。
