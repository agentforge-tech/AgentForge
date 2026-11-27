/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  sdkSidebar: [
    {
      type: 'category',
      label: '开始使用',
      collapsed: false,
      items: [
        {type: 'doc', id: 'about/introduction', label: '认识 AgentForge'},
        {type: 'doc', id: 'quickstart/getting-started', label: '快速开始'},
      ],
    },
    {
      type: 'category',
      label: 'Harness实现',
      collapsed: false,
      items: [
        {type: 'doc', id: 'harness/quickstart', label: '快速构建HarnessAgent'},
        {type: 'doc', id: 'harness/runtime', label: '运行时设计实现'},
      ],
    },
    {
      type: 'category',
      label: '核心组件',
      collapsed: false,
      items: [
        'sdk/model',
        'sdk/agent',
        'sdk/permission-hitl',
        'sdk/message-event',
        'sdk/middleware',
        'sdk/tool',
        'sdk/memory',
      ],
    },
    {
      type: 'category',
      label: '会话记忆扩展',
      collapsed: false,
      items: [
        'sdk/memory/window',
      ],
    },
    {
      type: 'category',
      label: 'Agent Tool 扩展',
      collapsed: false,
      items: [
        {type: 'doc', id: 'sdk/tool/java-local', label: 'Java本地Tool 对接'},
        {type: 'doc', id: 'sdk/tool/http', label: 'HTTP 对接'},
        {type: 'doc', id: 'sdk/tool/mcp', label: 'MCP 对接'},
      ],
    },
    {
      type: 'category',
      label: 'Middleware 扩展',
      collapsed: false,
      items: [
        {type: 'doc', id: 'sdk/middleware/logging', label: '日志 Middleware'},
        {type: 'doc', id: 'sdk/middleware/trace', label: 'Trace Middleware'},
        {type: 'doc', id: 'sdk/middleware/append', label: '对话追加 Middleware'},
        {type: 'doc', id: 'sdk/middleware/compact', label: '上下文阈值压缩 Middleware'},
        {type: 'doc', id: 'sdk/middleware/todo', label: '任务计划 Todo Middleware'},
        {type: 'doc', id: 'sdk/middleware/read', label: '阅读资源 Middleware'},
      ],
    },
    {
      type: 'category',
      label: '权限审批扩展',
      collapsed: false,
      items: [
        {type: 'doc', id: 'sdk/permission-approval/in-memory-session', label: '内存级会话审批确认'},
      ],
    },
    {
      type: 'category',
      label: '模型扩展',
      collapsed: false,
      items: [
        {type: 'doc', id: 'sdk/fim-model', label: 'FIM 代码补全模型协议'},
      ],
    },
  ],
  serviceSidebar: [
    {
      type: 'category',
      label: 'AgentForge Service',
      collapsed: false,
      items: [
        {type: 'doc', id: 'service/introduction', label: 'Service 介绍'},
        {type: 'doc', id: 'service/quick-deploy', label: '快速部署'},
      ],
    },
    {
      type: 'category',
      label: 'Agent组件',
      collapsed: false,
      items: [
        {type: 'doc', id: 'service/agent-components/model', label: '创建模型'},
        {type: 'doc', id: 'service/agent-components/http-plugin', label: '创建 HTTP 插件'},
        {type: 'doc', id: 'service/agent-components/mcp-plugin', label: '创建 MCP 插件'},
      ],
    },
    {
      type: 'category',
      label: 'Agent驾驶舱',
      collapsed: false,
      items: [
        {type: 'doc', id: 'service/agent-cockpit/create-agent', label: '创建通用 Agent'},
        {type: 'doc', id: 'service/agent-cockpit/debug', label: 'Agent 调试'},
        {type: 'doc', id: 'service/agent-cockpit/publish', label: 'Agent 发布'},
      ],
    },
    {
      type: 'category',
      label: 'Agent基础设施',
      collapsed: false,
      items: [
        {type: 'doc', id: 'service/agent-infrastructure/trace', label: 'Agent Trace 监控'},
      ],
    },
  ],
  changelogSidebar: ['changelog'],
  roadmapSidebar: ['roadmap/roadmap'],
};

export default sidebars;
