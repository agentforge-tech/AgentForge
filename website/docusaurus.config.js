// @ts-check
import {themes as prismThemes} from 'prism-react-renderer';
/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'AgentForge', tagline: 'Forge Intelligence into Action.', favicon: 'img/agentforge-logo.png', future: {v4: true},
  url: 'https://agentforge-tech.github.io', baseUrl: '/AgentForge/', organizationName: 'agentforge-tech', projectName: 'AgentForge', deploymentBranch: 'gh-pages', trailingSlash: false,
  onBrokenLinks: 'throw', markdown: {hooks: {onBrokenMarkdownLinks: 'throw'}},
  i18n: {defaultLocale: 'zh-Hans', locales: ['zh-Hans', 'en'], localeConfigs: {'zh-Hans': {label: '简体中文', htmlLang: 'zh-Hans'}, en: {label: 'English', htmlLang: 'en'}}},
  presets: [['classic', {docs: {sidebarPath: './sidebars.js', editUrl: 'https://github.com/agentforge-tech/AgentForge/tree/main/website/', showLastUpdateAuthor: true, showLastUpdateTime: true, versions: {current: {label: 'Next', path: 'next'}, '1.0': {label: 'v1.0', path: ''}}}, blog: {showReadingTime: true, routeBasePath: 'blog', blogTitle: 'AgentForge 博客', blogDescription: 'AgentForge 核心模块设计原理系列，从零构建 Agent 框架。', postsPerPage: 10, blogSidebarCount: 'ALL', blogSidebarTitle: 'AgentForge 专栏'}, theme: {customCss: './src/css/custom.css'}}]],
  themeConfig: {
    colorMode: {defaultMode: 'dark', disableSwitch: false, respectPrefersColorScheme: true},
    navbar: {title: 'AgentForge', logo: {alt: 'AgentForge logo', src: 'img/agentforge-logo.webp'}, items: [
      {to: '/', label: 'Home', position: 'left'}, {type: 'doc', docId: 'roadmap/roadmap', label: 'RoadMap', position: 'left'}, {type: 'doc', docId: 'about/introduction', label: 'SDK', position: 'left'}, {type: 'doc', docId: 'service/introduction', label: 'Service', position: 'left'}, {type: 'doc', docId: 'changelog', label: '更新日志', position: 'left'}, {to: '/blog', label: 'Blog', position: 'left'},
      {to: '/community', label: 'Community', position: 'right'}, {type: 'docsVersionDropdown', position: 'right'}, {type: 'localeDropdown', position: 'right'}, {href: 'https://github.com/agentforge-tech/AgentForge', position: 'right', className: 'header-github-link', 'aria-label': 'GitHub repository'}]},
    footer: {style: 'dark', links: [
      {title: 'SDK 文档', items: [{label: '2026 RoadMap', to: '/docs/roadmap/roadmap'}, {label: 'AgentForge 介绍', to: '/docs/about/introduction'}, {label: '快速开始', to: '/docs/quickstart/getting-started'}, {label: 'Model 模型协议层', to: '/docs/sdk/model'}, {label: 'Agent 智能体', to: '/docs/sdk/agent'}, {label: 'Tool 扩展设计', to: '/docs/sdk/tool'}]},
      {title: 'Service', items: [{label: 'Service 介绍', to: '/docs/service/introduction'}, {label: '快速部署', to: '/docs/service/quick-deploy'}]},
      {title: 'Community', items: [{label: 'GitHub', href: 'https://github.com/agentforge-tech/AgentForge'}, {label: 'Discussions', href: 'https://github.com/agentforge-tech/AgentForge/discussions'}, {label: 'Issues', href: 'https://github.com/agentforge-tech/AgentForge/issues'}]}], copyright: `Copyright © ${new Date().getFullYear()} AgentForge. Built with Docusaurus.`},
    prism: {theme: prismThemes.github, darkTheme: prismThemes.dracula, additionalLanguages: ['bash', 'java', 'json']},
  },
};
export default config;
