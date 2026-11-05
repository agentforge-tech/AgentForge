# Changelog

所有对本项目的重要更改都将记录在此文件中。

本日志格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
并且项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

发布记录同步：

- GitHub Releases：https://github.com/agentforge-tech/AgentForge/releases
- Gitee Releases：https://gitee.com/changluJava/agent-forge/releases

# release_1.0.x

## v1.0.0 - 2026.09.13-2026.11.02

状态：已发布 ✅

### Added

- [cc07b63] 项目初始化
- [1ccb482] 新增 LLM 架构文档与 ChatModel 协议层
- [8d28699] 新增 OpenAI / Anthropic Function Calling（阻塞 + 流式）
- [44b651f] 新增 Tool 工具层（ToolService / ToolExecutor）
- [c5397eb] 新增 agentforge-llm 单元测试
- [3ec3d0c] 新增 ReAct Agent 运行时与 LLM 工厂
- [02b50ce] 构建中英文文档站及 GitHub Pages 发布
- [dc8ba44] 新增 Studio 示例：后端基于 SSE 流式对话与 StudioStreamingMiddleware 中间件，拆分 session/think/tool/response/error 事件处理器；前端 Vue3 支持调试/优雅双渲染模式及 Markdown 展示
- [3a13cb0] 新增 Agent 工具层（local/http/mcp）与 Maven Central 发布链路，重组核心模块设计文档并补充 ChatModel 原理 01/02
- [2cb8d91] 升级 Agent 并发工具执行与中间件扩展，新增 Harness/OpenReach 集成，Studio 重构为 Service，并上线中英双语博客
- [a59bb55] UI 多页面独立构建：新增「我的模型」管理页 ModelsPage 与 Vite 配置工厂（chat/models 双入口、独立产物与端口、接入 sass），博客定制分类侧边栏与文章标题主题
- [a921401] 模型与 Agent 配置化：新增 ai_model_config / ai_agent_config 持久化与 MyBatis 增删改查、Agent 驾驶舱（列表/配置/调试）与单站点多页面构建，模型管理页接入真实 API；外部化 conf 配置与日志，修复文档站迁移后的 GitHub Pages 地址
- [7a6e715] 完善 Agent 发布与流式调试链路：新增消息窗口记忆及 Provider、按 aid 动态装载与发布状态管理、SSE 会话停止/清理和 OpenReach 工具接入；优化调试对话及模型配置交互，补充本地 SQL 升级脚本
- [e6f5705] Agent 持久化记忆与发布态对话：ChatMemory 抽象重构（ChatMemoryStore/ChatMessageCodec/InMemory+MySQL 存储、窗口记忆下沉 window 包）与 Provider 工厂化，新增 Agent 标准会话/message 核心表与增量 SQL、aid 生成工具与发布态基础信息接口；UI 新增发布页对话与消息内容组件、窗口记忆配置项及模型图标，调试与模型页交互优化；补充 2026 RoadMap 与博客侧边栏/布局主题
- [b3b9f3a] Agent 会话持久化与交互迭代：新增会话/消息领域模型、MyBatis 持久化、管理接口、生命周期中间件与 SQL 增量，完善调试及发布态会话回放/停止清理；优化 Markdown 流式渲染、工具调用参数、导航布局、模型与 Agent 配置页面及多页面构建；补充交互设计、接口方案与 2026 RoadMap 文档
- [0ae3cc6] Agent 版本管理与 MCP 插件迭代：新增 ai_agent_config_version / ai_agent_config_published 两张表与版本备注（提交时可填写、历史内可编辑）、发布态与调试态运行时配置解析、统一 AgentForgeException 与 Agent 不可用降级为正常 AI 回答；落地 MCP 插件管理（插件/Server CRUD、一键导入、快捷复制导出、工具探测、列表分页）并修复 McpTool 序列化；UI 改为 MCP 插件列表/详情两级页面、Agent 选择插件分页、版本历史与发布按钮交互、发布态会话 ID 地址栏同步；补充 2026 设计与 SQL 增量文档
- [2ee64e3] HTTP 插件全链路落地与工具思考参数统一：新增 HTTP 插件/方法/参数三层管理与 curl/JSON 一键导入导出、在线试运行，Agent 配置收敛为 pluginConfig 并支持 HTTP 方法/MCP Server 绑定，插件变更事务提交后统一失效运行时缓存并移除冗余 Agent-HTTP 引用表；抽离 ToolSpecificationThoughts 为本地/HTTP/MCP 工具统一注入 __thought，HTTP 执行器补充 Path 编码、Query 参数处理与敏感 Header 日志脱敏，MCP 工具支持思考参数；UI 新增 HTTP 插件列表/详情页、Agent HTTP 工具选择弹窗与侧栏入口，完善对话自动滚动；优化 README 与 RoadMap，补充 HTTP 插件方案、OpenReach 模板及产品使用手册
- [053d99c] Agent Trace 监控与前端 SPA 收敛：新增链路快照持久化与查询接口（会话列表支持调试态/发布态筛选、多轮耗时与 Token 汇总、时间展示）及调试专区历史会话加载回放，落地流程图/火焰图与耗时统计、节点详情参数 JSON 格式化与逐项复制；统一 Token 缓存命中口径（OpenAI prompt_tokens_details.cached_tokens 与 DeepSeek prompt_cache_hit_tokens、Anthropic cache_read/cache_creation_input_tokens），快照补充 cached_tokens/debug 字段与增量 SQL，并在 OpenAI/Anthropic 协议文档补齐缓存命中字段与 Token 计算步骤；前端由多页入口收敛为 vue-router 单页与统一 admin 样式，新增图片上传组件、本地文件上传访问地址配置与相关测试
- [edf56a4] SQLite 默认运行与一体化 Docker 部署：新增 SQLite 数据源、类型处理器、双套 MyBatis Mapper 与增量 SQL，支持后端托管 SPA 静态资源及外置上传配置；落地后端/前端/SQLite 一体化镜像、构建部署备份迁移升级与离线分发脚本，新增基于 Raw GitHub 的 standalone 一键安装和自动备份回滚升级能力；补充快速部署、运维设计及中英文 README
- [3746f1b] SQLite 增量迁移归档与部署文档完善：已执行 SQL 统一归档到 sql/local/sqlite/<版本>（Docker 默认 data/sql-applied）并以此作为幂等判断依据，新增 AGENTFORGE_SQLITE_APPLIED_DIR 环境变量并同步升级脚本、Dockerfile、Compose 编排与 standalone 环境模板；补充 SQLite 部署设计、快速部署/运维命令及中英文 README，更新 RoadMap
- [49c8ff4] Roadmap 双平台同步脚本与维护指南：新增 sync_roadmap.py 将本地相对链接渲染为 GitHub/Gitee Issue 绝对链接并支持双平台同步，补充 Gitee Issue、仓库 About 与 CLI 登录维护说明；修正核心 Agent 调试发布完成状态及架构图层命名
- [32d1583] 官网文档体系与首页迭代：文档拆分为 SDK/Service/RoadMap 三套侧边栏，新增 Model/Agent/消息事件/Middleware/Tool 核心组件文档及 Service 介绍与快速部署，并同步英文 i18n 与 version-1.0 版本快照；首页改版新增技术栈分层、模块列表、Quick Start 代码示例与 Service 工作台区块，能力卡片支持跳转；导航栏与页脚补充 SDK/Service/RoadMap 入口，博客侧边栏与布局样式重构，favicon 与品牌 logo 更新

### Changed

- [5cb7f49] 更新测试
- [d4db9a9] 重构 Tool 工具层至 agentforge.llm.tool 子包
- [4621d2d] 统一bom包维护
- [9c22ec3] 集成 Spotless 代码格式化校验
- [8090d2f] 补充 README logo
- [032fbe6] 新增 GitHub Pages 部署工作流（gh-pages）
- [ba5d757] 模块重构：llm→model、ai-agent→agent-core，统一包名并同步文档
- [887a8c6] 模块重构：拆分 model-api/model-core，统一异常为 ModelException 并同步文档
- [988dfac] 统一包命名空间为 io.github.agentforge（groupId 与包名一致），重写 README 结构并同步模块划分/BOM 规范文档
- [459e4fe] 统一包命名空间为 cloud.changlu.agentforge（自有域名 changlu.cloud 反向命名），groupId 与包名保持一致
- [0a4355e] README 拆分中英双语：README.md 改为英文默认，新增 README.zh-CN.md 中文版与语言切换
- [ed3340a] 前端管理台体验迭代：统一 Element UI 按需注册与基础样式，重构模型、MCP 插件、Agent 列表及配置页的导航、表单、弹窗、分页和响应式布局；优化发布态与调试态对话交互，修复中文输入法回车误发送，新增 Markdown 最终渲染完成事件并在正文完成后展示操作区，精简发布页欢迎语、发布时间与快捷键提示；调整侧栏宽度及 Agent 列表命名，补充 Markdown 流式渲染时序设计文档
- [c244d3e] 优化对话「回到底部」交互：将滚动按钮样式抽离为独立 chat-scroll-button.css 并在发布页、发布态多页面及 Agent 调试页统一引入；按钮默认收敛为磨砂圆形箭头，悬停/聚焦向右展开显示文案，流式生成中增加呼吸圆点与箭头动效提示新内容；补充移动端定位与 prefers-reduced-motion 降级，替换原 styles.css 中的基础按钮样式
- [24560f5] README 快速开始体验优化：将文档入口下移至快速开始之后并同步目录顺序，快速开始前置 Maven 依赖引入并保留 agentforge-agent-core 示例与常用模块说明，源码构建拆为独立小节；路线图移除阶段明细表格，统一指向 Issue #1
- [cec1eea] 补充插件工具统一 Function Call 标准设计：统一 Local/HTTP/MCP 的 __thought 注入入口与执行层隔离、幂等增强及向后兼容方案
- [8122fb7] 发布与文档体验优化：bin/local/README 固定 Maven settings 路径为 bin/local/settings.xml 并说明命令在仓库根目录执行，将本地发布凭据文件加入 .gitignore 避免 Central Token 入库；README 中英文版补充文档站入口并移除目录，精简首屏结构
- [5000190] 统一服务命名为 agentforge-service：调整 Compose 服务、项目与默认容器名，同步安装迁移升级脚本及快速部署运维命令，使镜像拉取进度和容器操作名称保持一致
- [ab49374] Agent 中间件目录收敛与链路追踪文档补充：将 AgentTraceMiddleware、AgentTraceCollector 及 trace domain 从独立 trace 包迁入 extend/middlewares/trace，统一内置中间件扩展目录并同步 Service 侧 AgentForgeTraceMiddleware、MysqlAgentTraceCollector 与快照 import；新增 Logging/AgentTrace/标准 Agent 中间件实现详解与 FunctionCall 设计文档，补齐 trace 协议路径、内置实现引用及中间件接入点说明
- [64ede21] 会话记忆窗口扩展包收敛至 extend/memory：将 MessageWindowChatMemory、MessageWindowChatMemoryProvider 及对应测试从 agent.memory.window 迁入 agent.extend.memory.window，统一内置扩展目录结构；同步 agentforge-service-web 的 WindowChatMemoryProviderFactoryExtension 与 7 个 core 测试 import，并更新 ChatMemory 设计文档目录树
- [3fbfb76] 完善Agent核心设计文档与RoadMap：补充会话记忆、事件协议、Agent装配、链路追踪、版本发布及交互设计
- [4d084cc] README 中英文同步：开头补充 Roadmap 地址并重构核心特性介绍，移除正文冗余路线图小节
- [4111d63] README 中英文架构与快速开始重构：Architecture 改为 Roadmap 两层设计（通用 ReAct/Harness 框架层 + AgentForge Service 业务层），在 agentforge-agent-core 下补充 LLM 到 Real Action 的逐层能力说明并移除 Module Capabilities 表；Quick Start 拆分为框架使用方式与 AgentForge Service 快捷本地一键启动两部分；同步 RoadMap 勾选核心 Agent 调试与发布对话设计与实现
- [1eda650] README 启动效果配图本地化：将 AgentForge Service 一键启动示意图从外链迁移至 assets/agentforge-service-ui.png，并在中英文 README 第二部分同步展示启动平台界面
- [a7080de] Service 默认部署端口由 18080 调整为 17080：同步 Compose 编排、一键安装与部署脚本的 WEB_PORT/AGENTFORGE_FILE_VISIT_PORT 默认值，并更新中英文 README、QUICK_DEPLOY、SQLite 一体化部署设计文档及官网 Service 介绍/快速部署（含 version-1.0 与 i18n 快照）

### Fixed

- [504afea] 修复 CI：Spotless 3.10.3 / google-java-format 1.35 需 JVM 21+，改为仅在 JDK 21 下激活 format-check profile，CI 矩阵新增 JDK 21，JDK 8/17 仅编译与测试
- [d31d439] 修复一键安装环境模板 404：将受 .gitignore 影响的 .env.example 更名为可跟踪的 env.example，并同步 Raw GitHub 下载路径，确保 standalone 安装器完整获取运行配置
- [c8c131c] 完善一键安装反馈与可恢复性：Compose 迁移改用非交互 TTY 模式修复 curl 管道安装中断，支持保留环境及配置后重复安装，并在启动成功后输出访问地址、目录、日志和升级命令
- [02b494c] 修复 curl 管道安装迁移后提前结束：关闭 docker compose run 标准输入交互，避免迁移容器消费安装脚本后续内容，确保继续启动服务并输出访问及运维提示
- [1d5e120] 修复生产环境 Element UI 初始化异常：移除 Vue 与 Element UI 强制 vendor 分包，交由 Rollup 按模块依赖图拆包，消除 chunk 循环依赖导致的 Cannot access before initialization
- [646d598] 修复 Docker 前端模型图标 404：新增 /model-logos/** 静态资源映射
