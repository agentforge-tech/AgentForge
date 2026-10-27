# AgentForge Roadmap 维护指南

本目录用于维护 AgentForge 的开发路线图。

- Roadmap 源文件：[`2026年AgentForge的RoadMap.md`](./2026年AgentForge的RoadMap.md)
- GitHub Issue：[AgentForge Development Roadmap](https://github.com/agentforge-tech/AgentForge/issues/1)
- Issue 编号：`#1`
- GitHub 仓库：`agentforge-tech/AgentForge`

## 日常维护流程

1. 编辑 `docs/roadmap/2026年AgentForge的RoadMap.md`。
2. 检查 Markdown 内容和任务勾选状态。
3. 将文件内容同步到 GitHub Issue：

```bash
gh issue edit 1 \
  --repo agentforge-tech/AgentForge \
  --body-file 'docs/roadmap/2026年AgentForge的RoadMap.md'
```

4. 检查线上 Issue：

```bash
gh issue view 1 \
  --repo agentforge-tech/AgentForge \
  --web
```

## 常用命令

### 查看 Issue 信息

```bash
gh issue view 1 \
  --repo agentforge-tech/AgentForge
```

### 修改 Issue 标题

```bash
gh issue edit 1 \
  --repo agentforge-tech/AgentForge \
  --title 'AgentForge Development Roadmap'
```

### 置顶 Issue

```bash
gh issue pin 1 \
  --repo agentforge-tech/AgentForge
```

### 检查 GitHub CLI 登录状态

```bash
gh auth status
```

如果登录已失效，重新授权：

```bash
gh auth login -h github.com -p https -w
```

## 维护约定

- 以本地 Roadmap Markdown 文件为内容源，GitHub Issue 用于对外展示和讨论。
- 功能完成后，将对应任务从 `[ ]` 更新为 `[x]`。
- 新增规划时，尽量放入已有的功能分组；只在确有新方向时增加一级分组。
- 如果有对应的 Issue 或 Pull Request，在任务后附上链接，便于跟踪进度。
- 更新本地文件后，再执行同步命令，避免本地文档和线上 Issue 长期不一致。

