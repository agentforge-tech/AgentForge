import React, {useMemo, useState} from 'react';
import Link from '@docusaurus/Link';
import {useLocation} from '@docusaurus/router';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {useWindowSize, NavbarSecondaryMenuFiller} from '@docusaurus/theme-common';
import {useVisibleBlogSidebarItems} from '@docusaurus/plugin-content-blog/client';
import clsx from 'clsx';

import styles from './styles.module.css';

const COPY = {
  'zh-Hans': {
    model: '一、模型协议层',
    chatModel: 'ChatModel（LLM 对话协议）',
    providers: '主流模型协议实现',
    react: '二、通用 ReAct Agent 层',
    functionCalling: 'Function Calling 工具扩展',
    harness: '三、Harness Agent 层',
    service: '四、Agent 应用服务层',
    extras: '番外篇：工程化与生态',
    planned: '规划中',
    other: '其他文章',
    aria: 'AgentForge 博客文章导航',
  },
  en: {
    model: 'I. Model Protocol Layer',
    chatModel: 'ChatModel (LLM Protocol)',
    providers: 'Model Provider Protocols',
    react: 'II. General ReAct Agent Layer',
    functionCalling: 'Function Calling Tool Extensions',
    harness: 'III. Harness Agent Layer',
    service: 'IV. Agent Application Service Layer',
    extras: 'Extras: Engineering & Ecosystem',
    planned: 'Planned',
    other: 'Other Posts',
    aria: 'AgentForge blog navigation',
  },
};

function byDate(items) {
  return [...items].sort((a, b) => new Date(a.date) - new Date(b.date));
}

function buildTree(items, copy) {
  const buckets = {
    unified: [],
    openai: [],
    anthropic: [],
    local: [],
    http: [],
    mcp: [],
    extras: [],
    other: [],
  };

  items.forEach((item) => {
    const title = item.title.toLowerCase();
    if (title.includes('openai')) buckets.openai.push(item);
    else if (title.includes('anthropic')) buckets.anthropic.push(item);
    else if (title.includes('chatmodel')) buckets.unified.push(item);
    else if (title.includes('mcp')) buckets.mcp.push(item);
    else if (title.includes('http')) buckets.http.push(item);
    else if (title.includes('local')) buckets.local.push(item);
    else if (title.includes('maven central')) buckets.extras.push(item);
    else buckets.other.push(item);
  });

  Object.keys(buckets).forEach((key) => {
    buckets[key] = byDate(buckets[key]);
  });

  return [
    {
      label: copy.model,
      children: [
        {label: copy.chatModel, items: buckets.unified},
        {
          label: copy.providers,
          children: [
            {label: 'OpenAI', items: buckets.openai},
            {label: 'Anthropic', items: buckets.anthropic},
          ],
        },
      ],
    },
    {
      label: copy.react,
      children: [
        {
          label: copy.functionCalling,
          children: [
            {label: 'Local Tool', items: buckets.local},
            {label: 'HTTP Tool', items: buckets.http},
            {label: 'MCP Tool', items: buckets.mcp},
          ],
        },
      ],
    },
    {label: copy.harness, planned: true},
    {label: copy.service, planned: true},
    {label: copy.extras, items: buckets.extras},
    ...(buckets.other.length ? [{label: copy.other, items: buckets.other}] : []),
  ];
}

function samePath(left, right) {
  const normalize = (value) => decodeURI(value).replace(/\/$/, '');
  return normalize(left) === normalize(right);
}

function hasActive(node, pathname) {
  if (node.items?.some((item) => samePath(item.permalink, pathname))) {
    return true;
  }
  return node.children?.some((child) => hasActive(child, pathname)) ?? false;
}

function ArticleList({items, pathname}) {
  if (!items?.length) {
    return null;
  }
  return items.map((item) => (
    <li className="menu__list-item" key={item.permalink}>
      <Link
        to={item.permalink}
        title={item.title}
        className={clsx('menu__link', {
          'menu__link--active': samePath(item.permalink, pathname),
        })}>
        {item.title}
      </Link>
    </li>
  ));
}

function Category({node, pathname, depth, defaultOpen}) {
  const active = hasActive(node, pathname);
  const [open, setOpen] = useState(defaultOpen || active);

  return (
    <li className={clsx('menu__list-item', {'menu__list-item--collapsed': !open})}>
      <div className="menu__list-item-collapsible">
        <button
          type="button"
          className="clean-btn menu__link menu__link--sublist"
          aria-expanded={open}
          onClick={() => setOpen(!open)}>
          {node.label}
        </button>
        <button
          type="button"
          className="clean-btn menu__caret"
          aria-label={open ? 'Collapse category' : 'Expand category'}
          onClick={() => setOpen(!open)}
        />
      </div>
      {open && (
        <ul className="menu__list">
          <ArticleList items={node.items} pathname={pathname} />
          {node.children?.map((child) => (
            <TreeNode
              key={child.label}
              node={child}
              pathname={pathname}
              depth={depth + 1}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

function TreeNode({node, pathname, depth = 0}) {
  if (node.planned) {
    return (
      <li className="menu__list-item">
        <span className="menu__link" aria-disabled="true">
          {node.label}
        </span>
      </li>
    );
  }

  if (node.children || node.items?.length) {
    return (
      <Category
        node={node}
        pathname={pathname}
        depth={depth}
        defaultOpen={depth === 0}
      />
    );
  }

  return null;
}

function SidebarTree({sidebar, mobile = false}) {
  const {pathname} = useLocation();
  const {i18n} = useDocusaurusContext();
  const copy = COPY[i18n.currentLocale] ?? COPY.en;
  const items = useVisibleBlogSidebarItems(sidebar.items);
  const tree = useMemo(() => buildTree(items, copy), [items, copy]);

  return (
    <nav
      className={clsx('theme-doc-sidebar-menu menu thin-scrollbar', styles.sidebar, {
        [styles.mobile]: mobile,
      })}
      aria-label={copy.aria}>
      <ul className="menu__list">
        {tree.map((node) => (
          <TreeNode key={node.label} node={node} pathname={pathname} />
        ))}
      </ul>
    </nav>
  );
}

function MobileSidebar(props) {
  return <SidebarTree sidebar={props.sidebar} mobile />;
}

export default function BlogSidebar({sidebar}) {
  const windowSize = useWindowSize();
  if (!sidebar?.items.length) {
    return null;
  }

  if (windowSize === 'mobile') {
    return <NavbarSecondaryMenuFiller component={MobileSidebar} props={{sidebar}} />;
  }

  return <SidebarTree sidebar={sidebar} />;
}
