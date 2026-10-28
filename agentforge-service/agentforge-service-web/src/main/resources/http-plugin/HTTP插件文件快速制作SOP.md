# AgentForge HTTP 插件文件快速制作 SOP

## 目标

将同一域名、共享认证方式的一组 HTTP 接口整理为一个可直接导入 AgentForge 的 JSON 插件文件。

## 第一步：确定插件边界

1. 只把具有相同协议、域名和公共路径前缀的接口放入同一插件。
2. 将共同前缀写入 `plugin.baseUrl`，例如 `https://api.example.com/v1`。
3. 每个工具的 `uri` 只保留 Base URL 之后的路径，例如 `/users/{id}`。
4. 共用请求头放入 `staticHeaders`；密钥使用占位符，导入后再配置真实值。

## 第二步：整理接口清单

对每个接口记录：工具名称、用途、HTTP 方法、相对路径、Query/Body/Path/Header 参数、参数类型、是否必填。

枚举值：

- `httpMethodType`：1=GET，2=POST，3=PUT，4=DELETE，5=PATCH
- `useTypeValue`：1=Query，2=Body，3=Path，4=Header
- `dataTypeValue`：1=String，2=Integer，3=Number，4=Boolean，5=Array，6=Object
- `status`、`required`：1=是，0=否

`methodName` 及参数的 `methodParamName` 建议使用符合程序命名的英文名称；`mappedName` 填写 HTTP 请求中的真实参数名。

## 第三步：按模板制作 JSON

```json
{
  "version": "1.0",
  "source": "manual",
  "plugin": {
    "name": "Example API",
    "alias": "example",
    "icon": "",
    "baseUrl": "https://api.example.com/v1",
    "staticHeaders": {
      "Authorization": "Bearer {{EXAMPLE_API_KEY}}",
      "Content-Type": "application/json"
    },
    "description": "Example 同域名接口集合",
    "status": 1,
    "methods": [
      {
        "methodName": "getUser",
        "displayName": "查询用户",
        "methodDescription": "根据用户 ID 查询用户详情",
        "httpMethodType": 1,
        "uri": "/users/{id}",
        "status": 1,
        "sortOrder": 1,
        "parameters": [
          {
            "methodParamName": "userId",
            "methodParamDescription": "用户 ID",
            "mappedName": "id",
            "useTypeValue": 3,
            "dataTypeValue": 1,
            "defaultValue": null,
            "required": 1,
            "sortOrder": 1
          }
        ]
      },
      {
        "methodName": "searchUsers",
        "displayName": "搜索用户",
        "methodDescription": "按关键词搜索用户",
        "httpMethodType": 1,
        "uri": "/users/search",
        "status": 1,
        "sortOrder": 2,
        "parameters": [
          {
            "methodParamName": "keyword",
            "methodParamDescription": "搜索关键词",
            "mappedName": "q",
            "useTypeValue": 1,
            "dataTypeValue": 1,
            "defaultValue": null,
            "required": 1,
            "sortOrder": 1
          }
        ]
      }
    ]
  }
}
```

## 第四步：导入前检查

- JSON 可被正常解析，不含注释、多余逗号。
- `plugin.name`、`plugin.alias`、`plugin.baseUrl` 已填写。
- 所有 `uri` 均以 `/` 开头，且不再包含 Base URL。
- `methodName` 在插件内唯一，能清楚表达工具用途。
- Path 参数与 URI 中的 `{name}` 一一对应。
- 认证信息没有以明文写入准备共享的文件。
- 排序值从 1 开始递增，插件和所需工具的 `status` 为 1。

## 第五步：导入与验证

1. 进入「HTTP 插件」页面，选择「快捷导入 > 导入插件文件」。
2. 选择 JSON 文件，核对预览中的插件名称、Base URL 和工具数量。
3. 导入后进入插件，为占位认证信息配置真实值。
4. 使用「试运行」逐个验证工具的参数映射和响应。
5. 确认无误后，在 Agent 配置中选择该 HTTP 插件及所需工具。

---

## 六、开始前必须准备的材料

制作人不需要阅读 AgentForge 源码，但需要至少拿到以下任意一种接口材料：

1. OpenAPI/Swagger 文档；
2. Postman Collection；
3. 能够成功调用的 CURL 命令；
4. 接口文档，包含 URL、HTTP 方法、请求头、入参和示例响应。

制作前建议建立一张接口清单：

| 序号 | 接口用途 | HTTP 方法 | 完整 URL | 认证方式 | 是否加入插件 |
| --- | --- | --- | --- | --- | --- |
| 1 | 查询用户 | GET | `https://api.example.com/v1/users/{id}` | Bearer Token | 是 |
| 2 | 搜索用户 | GET | `https://api.example.com/v1/users/search` | Bearer Token | 是 |
| 3 | 上传文件 | POST | `https://upload.example.com/files` | Bearer Token | 否，域名不同 |

### 何时需要拆成多个插件

出现以下任一情况时，建议拆分：

- 协议或域名不同；
- 公共路径前缀差异很大；
- 认证方式完全不同；
- 两组接口属于不同业务领域，不希望 Agent 同时看到；
- 某组接口需要单独的启停、授权或维护周期。

## 七、如何确定 Base URL 和 URI

Base URL 是同一插件中所有接口的稳定公共前缀。

例如三个接口：

- `https://api.example.com/v1/users/{id}`
- `https://api.example.com/v1/users/search`
- `https://api.example.com/v1/orders/{id}`

建议拆分为：

- `baseUrl`: `https://api.example.com/v1`
- `uri`: `/users/{id}`
- `uri`: `/users/search`
- `uri`: `/orders/{id}`

不要把查询参数写入 `uri`。例如完整请求是 `GET /users/search?q=tom&page=1`，则 `uri` 仍然是 `/users/search`，`q` 和 `page` 应定义为 Query 参数。

判断标准：

- `baseUrl + uri` 必须能还原出真实接口路径；
- `baseUrl` 末尾建议不带 `/`；
- `uri` 必须以 `/` 开头；
- `uri` 中可以包含 `{id}` 这样的 Path 占位符。

## 八、插件字段字典

### 顶层字段

| 字段 | 必填 | 示例 | 说明 |
| --- | --- | --- | --- |
| `version` | 建议 | `1.0` | 文件结构版本 |
| `source` | 建议 | `manual` | 文件来源，手工制作可填 `manual` |
| `plugin` | 是 | 对象 | 插件本体 |

### `plugin` 字段

| 字段 | 必填 | 示例 | 制作规则 |
| --- | --- | --- | --- |
| `name` | 是 | `Example API` | 面向用户的插件名称 |
| `alias` | 是 | `example` | 英文别名，建议仅用小写字母、数字、下划线 |
| `icon` | 否 | `https://.../logo.png` | 插件图标，没有时使用空字符串 |
| `baseUrl` | 是 | `https://api.example.com/v1` | 插件所有工具的公共 URL 前缀 |
| `staticHeaders` | 否 | `{"Authorization":"Bearer {{API_KEY}}"}` | 所有工具共用的固定请求头 |
| `description` | 建议 | `用户与订单查询接口` | 说清插件能力边界，帮助配置人选择 |
| `status` | 是 | `1` | `1` 启用，`0` 停用 |
| `methods` | 是 | 数组 | 插件中的接口工具列表 |

### `methods[]` 字段

| 字段 | 必填 | 示例 | 制作规则 |
| --- | --- | --- | --- |
| `methodName` | 是 | `getUser` | 插件内唯一的英文工具名，建议使用动词+名词 |
| `displayName` | 建议 | `查询用户` | 界面展示名称 |
| `methodDescription` | 是 | `根据用户 ID 查询详情` | 告诉 AI 何时使用工具，应说清用途和约束 |
| `httpMethodType` | 是 | `1` | HTTP 方法枚举 |
| `uri` | 是 | `/users/{id}` | 相对于 Base URL 的接口路径 |
| `status` | 是 | `1` | `1` 启用，`0` 停用 |
| `sortOrder` | 建议 | `1` | 工具显示顺序，从 1 递增 |
| `parameters` | 是 | 数组 | 无参接口使用空数组 `[]` |

### `parameters[]` 字段

| 字段 | 必填 | 示例 | 制作规则 |
| --- | --- | --- | --- |
| `methodParamName` | 是 | `userId` | AI 调用工具时看到的参数名 |
| `methodParamDescription` | 是 | `要查询的用户 ID` | 说清含义、格式、单位、可选值和限制 |
| `mappedName` | 是 | `id` | 真正发送给 HTTP 接口的参数名 |
| `useTypeValue` | 是 | `3` | 参数位置：Query/Body/Path/Header |
| `dataTypeValue` | 是 | `1` | 参数数据类型 |
| `defaultValue` | 否 | `10` | 未传值时使用的默认值，无默认值填 `null` |
| `required` | 是 | `1` | `1` 必填，`0` 可选 |
| `sortOrder` | 建议 | `1` | 参数顺序，从 1 递增 |

注意：制作新文件时不要填写数据库 `id`，由 AgentForge 导入时生成。

## 九、参数映射实战

### Query 参数

接口：`GET /users/search?q=tom&page=1`

```json
{
  "methodParamName": "keyword",
  "methodParamDescription": "用户姓名或邮箱关键词",
  "mappedName": "q",
  "useTypeValue": 1,
  "dataTypeValue": 1,
  "defaultValue": null,
  "required": 1,
  "sortOrder": 1
}
```

`methodParamName` 可以为了 AI 理解而命名为 `keyword`，但 `mappedName` 必须保留接口真实名称 `q`。

### Path 参数

接口：`GET /users/{id}`

```json
{
  "methodParamName": "userId",
  "methodParamDescription": "用户唯一 ID",
  "mappedName": "id",
  "useTypeValue": 3,
  "dataTypeValue": 1,
  "defaultValue": null,
  "required": 1,
  "sortOrder": 1
}
```

`mappedName` 必须与 URI 中 `{id}` 的名称完全一致，包括大小写。

### Body 参数

接口需要以下 JSON Body：

```json
{
  "name": "Tom",
  "age": 20,
  "enabled": true
}
```

可将 `name`、`age`、`enabled` 分别定义为 Body 参数，`useTypeValue` 都为 `2`，数据类型分别为 String、Integer、Boolean。

如果接口要求一个完整的嵌套对象，可使用 Object 类型：

```json
{
  "methodParamName": "profile",
  "methodParamDescription": "用户资料对象，包含 name 和 address",
  "mappedName": "profile",
  "useTypeValue": 2,
  "dataTypeValue": 6,
  "defaultValue": null,
  "required": 1,
  "sortOrder": 1
}
```

### Header 参数

只有每次调用可能不同的请求头才定义为 Header 参数，例如租户 ID。所有工具一致的认证头应放在 `staticHeaders`。

```json
{
  "methodParamName": "tenantId",
  "methodParamDescription": "租户 ID",
  "mappedName": "X-Tenant-Id",
  "useTypeValue": 4,
  "dataTypeValue": 1,
  "defaultValue": null,
  "required": 1,
  "sortOrder": 1
}
```

## 十、从 CURL 快速批量制作

如果已有一组可执行 CURL，可按以下步骤整理：

1. 在本地或测试环境逐个执行 CURL，先确认接口本身可用。
2. 收集所有 CURL 中的 URL，提取最大稳定公共前缀为 `baseUrl`。
3. 从每个 CURL 中提取 HTTP 方法、相对 URI、Query、Header 和 Body。
4. 将每条 CURL 转换为一个 `methods[]` 元素。
5. 把相同的 Header 提升到 `plugin.staticHeaders`，删除每个工具里的重复定义。
6. 把 Token、API Key、Cookie 等敏感值替换成 `{{VARIABLE_NAME}}` 占位符。
7. 将 CURL 中的示例值区分为「默认值」和「仅用于测试的样例值」；不要把一次性 ID 误写成默认值。
8. 为每个工具补齐清晰的 `methodDescription` 和参数描述。

### CURL 转换示例

原始 CURL：

```bash
curl --request GET \
  'https://api.example.com/v1/users/search?q=tom&page=1' \
  --header 'Authorization: Bearer secret-token' \
  --header 'X-Tenant-Id: tenant-a'
```

转换结果：

- `baseUrl`: `https://api.example.com/v1`
- `uri`: `/users/search`
- `httpMethodType`: `1`
- `Authorization`: 放到 `staticHeaders` 并替换为占位符
- `q`: Query/String/必填
- `page`: Query/Integer/可选，默认值可设为 `1`
- `X-Tenant-Id`: 如每次可变，定义为 Header 参数；如始终固定，放入 `staticHeaders`

## 十一、命名与描述规范

### 插件别名

插件别名应简短稳定，不要把环境、日期或个人名写进别名。

- 推荐：`github`、`openreach`、`crm_order`
- 不推荐：`test_api_20261028`、`xiaoming_plugin`

工具最终会以插件别名和方法名组合展示，因此两者都需要稳定、可读。

### 工具名称

建议采用「动词 + 业务对象」：

- `getUser`
- `searchOrders`
- `createTicket`
- `updateCustomer`
- `deleteDraft`

不要使用 `api1`、`doRequest`、`execute` 这类无法表达用途的名称。

### 描述写法

`methodDescription` 建议包含：

1. 工具做什么；
2. 什么情况下使用；
3. 不能做什么或有什么前置条件；
4. 关键参数和返回结果的意义。

示例：「根据用户 ID 查询用户基本资料。仅在已获得明确用户 ID 时使用；如果只有姓名或邮箱，请先使用搜索用户工具。」

## 十二、安全规范

- 不得在可分发的 JSON 中保存真实 Token、API Key、Cookie、密码或会话信息。
- 将敏感值替换为明确的占位符，例如 `{{OPENREACH_API_KEY}}`。
- 不要把真实密钥放在 `defaultValue`、描述或文件名中。
- 试运行时使用测试账号和最小权限凭据。
- 对新增、修改、删除类接口进行明确标识，避免 Agent 误调用高风险工具。
- 插件导出后，分享前再次搜索 `token`、`key`、`secret`、`cookie`、`authorization` 等关键字。

## 十三、导入前的机械校验

保存文件后，先使用 JSON 工具进行语法校验。如果本机已安装 `jq`，可执行：

```bash
jq empty your-http-plugin.json
```

命令无输出表示 JSON 语法正常。然后按顺序检查：

1. 顶层是 JSON 对象，且包含 `plugin`。
2. `plugin.methods` 是 JSON 数组。
3. 每个 `methodName` 在当前插件内唯一。
4. 每个参数的 `methodParamName` 在当前工具内唯一。
5. `httpMethodType`、`useTypeValue`、`dataTypeValue` 使用数字枚举，不是 `"GET"`、`"Query"` 等字符串。
6. `status`、`required`使用 `0` 或 `1`，不是 `true` 或 `false`。
7. 整数、小数、布尔值、数组和对象的数据类型选择正确。
8. 所有 Path 占位符都有对应参数，反之亦然。
9. 文件使用 UTF-8 编码并以 `.json` 为后缀。

## 十四、导入后验收流程

不要以「导入成功」作为制作完成的标准。必须完成以下验收：

1. **插件级检查**：名称、别名、Base URL、共享 Header、启用状态正确。
2. **工具数量检查**：界面展示的数量与清单一致。
3. **工具配置检查**：方法、URI、参数位置、类型、是否必填正确。
4. **成功用例**：使用有效参数试运行，确认返回结果正确。
5. **边界用例**：验证可选参数缺省、分页边界、空结果等情况。
6. **错误用例**：使用无效 ID 或缺少必填参数，确认错误信息可理解。
7. **Agent 调用验证**：把插件配置给测试 Agent，用自然语言验证 AI 能选中正确工具并填写正确参数。

建议使用下表记录验收结果：

| 工具 | 成功用例 | 边界用例 | 错误用例 | Agent 调用 | 结论 |
| --- | --- | --- | --- | --- | --- |
| `getUser` | 通过 | 通过 | 通过 | 通过 | 可发布 |

## 十五、常见问题排查

### 导入时提示 JSON 格式错误

检查文件是否含有注释、末尾多余逗号、中文引号或未转义的双引号，使用 `jq empty` 定位语法错误。

### 导入成功但请求 404

重点检查 `baseUrl + uri` 是否多了或少了路径段，以及前后斜杠是否正确。

### 请求返回 401 或 403

检查 `staticHeaders` 中的认证名称、前缀（如 `Bearer `）和真实凭据；确认凭据具有调用该接口的权限。

### Path 参数没有替换

检查 `uri` 中 `{id}` 与 Path 参数的 `mappedName` 是否完全相同，并确认 `useTypeValue` 为 `3`。

### Query 或 Body 参数名错误

检查 `mappedName`，Agent 使用的是 `methodParamName`，HTTP 服务实际收到的是 `mappedName`。

### AI 不会选择正确工具

改进 `methodName`、`displayName`、`methodDescription` 和参数描述。同一插件中不要放入大量用途重叠且描述模糊的工具。

### 数组或对象参数试运行失败

试运行时必须输入合法 JSON：数组如 `["a","b"]`，对象如 `{"name":"Tom"}`。

## 十六、交付标准

一个可交付的 HTTP 插件文件应同时满足：

- 文件命名清晰，建议为 `<plugin-alias>-http-plugin.json`；
- 不含真实密钥和个人隐私数据；
- 包含插件所需的全部工具，没有无关接口；
- 所有工具已完成试运行；
- 工具和参数描述能被不熟悉原始 API 的人理解；
- 已在测试 Agent 中验证自然语言调用；
- 附带插件用途、认证占位符说明、工具清单和验收结果。

## 十七、最短执行路径

熟悉本 SOP 后，可按以下最短流程操作：

1. 收集并验证同域名接口。
2. 确定 Base URL，为每个接口提取相对 URI。
3. 复制本文档的完整 JSON 模板。
4. 填写插件基本信息、共享 Header 和工具列表。
5. 为每个工具填写 Query、Body、Path、Header 参数。
6. 移除密钥，执行 JSON 语法和字段检查。
7. 通过「快捷导入 > 导入插件文件」导入。
8. 逐个试运行，再使用测试 Agent 完成端到端验收。
