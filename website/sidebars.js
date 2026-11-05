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
      label: '核心组件',
      collapsed: false,
      items: [
        'sdk/model',
        'sdk/agent',
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
      ],
    },
  ],
  serviceSidebar: [
    {
      type: 'category',
      label: 'AgentForge Service',
      collapsed: false,
      items: ['service/introduction', 'service/quick-deploy'],
    },
  ],
  changelogSidebar: ['changelog'],
  roadmapSidebar: ['roadmap/roadmap'],
};

export default sidebars;
