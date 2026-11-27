# AgentForge FIM Demo

这是一个可独立启动的 Spring Boot + 静态前端演示工程。启动后访问 <http://localhost:8080>。

## 一键启动

在本目录执行：

```bash
./start.sh
```

脚本会先从当前 AgentForge 源码构建 demo 所需的 model 模块，避免本地 Maven 仓库中的 SNAPSHOT JAR 版本不一致，然后启动 Spring Boot。

## 模型配置

默认使用本地的 OpenAI-compatible FIM 服务。可用环境变量覆盖：

```bash
FIM_BASE_URL=http://127.0.0.1:9997/v1 \
FIM_MODEL_NAME=CodeQwen1.5-7B \
FIM_API_KEY=EMPTY \
FIM_TEMPLATE=qwen-no-pipe \
./start.sh
```

可选变量：`SERVER_PORT`、`FIM_BASE_URL`、`FIM_MODEL_NAME`、`FIM_API_KEY`、`FIM_TEMPLATE`。

默认渠道在 `application.yml` 的 `fim.channel` 下单独配置：

- `provider-code: 1`：OpenAI-compatible `/v1/completions`
- `base-url: http://127.0.0.1:9997/v1`
- `api-key: EMPTY`

默认请求使用 `CodeQwen1.5-7B`、`max_tokens=64`、`temperature=0`、`stream=false/true`。
`qwen-no-pipe` 模板会自动把 prefix/suffix 组装成 `<fim_prefix>...<fim_suffix>...<fim_middle>`，
并自动下发 `<|endoftext|>` 与 `<fim_*>` 停止符。

## 测试

普通测试不访问真实模型：

```bash
mvn test
```

显式运行真实端点验证集：

```bash
mvn -Dfim.live=true \
  -Dfim.baseUrl=http://127.0.0.1:9997/v1 \
  -Dfim.modelName=CodeQwen1.5-7B \
  -Dfim.template=qwen-no-pipe \
  -Dtest=FimLiveVerificationTest test
```
