# OpenReach Web Search CURL 一键导入示例

## 1. 用途

本示例对应 AgentForge 内置 `OpenReachClient.search()` 调用的 OpenReach 网页搜索接口，可直接复制到「HTTP 插件 -> CURL 导入」中创建插件工具。

接口信息：

| 配置 | 值 |
| --- | --- |
| 请求方法 | `POST` |
| Base URL | `http://openreach.changlu.cloud` |
| URI | `/api/web/search` |
| Content-Type | `application/json` |
| 鉴权 | 无 |

## 2. 可直接导入的 CURL

```bash
curl --location 'http://openreach.changlu.cloud/api/web/search' --request POST --header 'Content-Type: application/json' --header 'Accept: application/json' --data-raw '{"query":"AgentForge HTTP plugin","limit":5,"region":"auto","provider":"auto","timeRange":"any"}'
```

> 如果部署环境使用自建 OpenReach，只需将命令中的 `http://openreach.changlu.cloud` 替换为 `OPENREACH_BASE_URL` 的实际值。

## 3. 导入时建议填写

| 页面字段 | 建议值 |
| --- | --- |
| 导入方式 | 新建插件 |
| 插件名称 | `OpenReach` |
| 插件描述 | `OpenReach 网页搜索插件` |

插件别名和工具名会根据插件名称与 CURL 自动生成。以上述内容导入后，工具名类似：

```text
openreach__executePOSTSearch
```

## 4. 参数说明

| 参数 | 类型 | 说明 | 建议默认值 |
| --- | --- | --- | --- |
| `query` | String | 搜索关键词，实际使用时建议设为必填 | 无 |
| `limit` | Integer | 最大返回结果数 | `5` |
| `region` | String | 区域路由：`auto/CN/US/JP/SG/GLOBAL` | `auto` |
| `provider` | String | 搜索提供方 | `auto` |
| `timeRange` | String | 时效范围：`any/day/week/month/year` | `any` |

CURL 导入会从 JSON Body 自动生成上述 Body 参数。导入完成后，建议进入工具编辑页：

1. 将 `query` 设为必填并清空示例默认值。
2. 为每个参数补充上表中的说明，便于模型正确生成调用参数。
3. 使用「试运行」确认 OpenReach 服务可访问，再在 Agent 编辑页中绑定该工具。

## 5. 试运行参数

```json
{
  "query": "AgentForge HTTP plugin",
  "limit": 5,
  "region": "auto",
  "provider": "auto",
  "timeRange": "any"
}
```

OpenReach 正常时会返回 JSON 格式的搜索候选结果，其中包含标题、URL 和内容摘要等信息。
