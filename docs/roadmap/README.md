# AgentForge Roadmap 维护指南

本目录用于维护 AgentForge 的开发路线图，并同步到 GitHub / Gitee 的 Issue。

- Roadmap 源文件：[`2026年AgentForge的RoadMap.md`](./2026年AgentForge的RoadMap.md)
- GitHub Issue：[AgentForge Development Roadmap](https://github.com/agentforge-tech/AgentForge/issues/1)（`#1`）
- Gitee Issue：[AgentForge Development Roadmap](https://gitee.com/changluJava/AgentForge/issues/IKS14I)（`IKS14I`）
- GitHub 仓库：`agentforge-tech/AgentForge`
- Gitee 仓库：`changluJava/agent-forge`

## 维护原则

- 以本地 `docs/roadmap/2026年AgentForge的RoadMap.md` 为唯一内容源，GitHub / Gitee Issue 只做展示和讨论。
- 本地文件使用相对链接；同步到 Issue 时由脚本转换为对应平台的绝对链接并做 URL 编码。
- 功能完成后，将对应任务从 `[ ]` 更新为 `[x]`。
- 新增规划时，尽量放入已有的功能分组；只在确有新方向时增加一级分组。
- 如果有对应的 Issue 或 Pull Request，在任务后附上链接，便于跟踪进度。
- 每次更新本地文件后，同步 GitHub 与 Gitee 两个 Issue，避免线上内容长期不一致。

## 日常维护流程

1. 编辑 `docs/roadmap/2026年AgentForge的RoadMap.md`。
2. 预览两个平台的渲染结果：

```bash
python3 docs/roadmap/sync_roadmap.py render github
python3 docs/roadmap/sync_roadmap.py render gitee
```

3. 同时同步 GitHub 与 Gitee Issue：

```bash
python3 docs/roadmap/sync_roadmap.py sync
```

4. 只同步单个平台：

```bash
python3 docs/roadmap/sync_roadmap.py sync github
python3 docs/roadmap/sync_roadmap.py sync gitee
```

5. 检查线上 Issue：

```bash
gh issue view 1 --repo agentforge-tech/AgentForge --web
gitee issue view IKS14I -R changluJava/agent-forge
```

## Gitee 同步补充

### 检查 Gitee CLI 登录状态

```bash
gitee auth status
```

如未登录：

```bash
gitee auth login
```

### 推送全部分支到 Gitee

```bash
git push gitee --all
```

如需一并推送标签：

```bash
git push gitee --tags
```

> 推送远程时按 `AGENTS.md` 约定同时推 `origin` 与 `gitee`。

### 同步仓库 About（描述与主页）

从 GitHub 读取最新描述与主页：

```bash
gh repo view agentforge-tech/AgentForge --json description,homepageUrl
```

写入 Gitee（该接口要求同时传 `name`）：

```bash
gitee api /repos/changluJava/agent-forge -X PATCH \
  -f name="AgentForge" \
  -f description="<GitHub description>" \
  -f homepage="https://agentforge-tech.github.io/AgentForge/"
```

验证：

```bash
gitee repo view changluJava/agent-forge --json=description,html_url
```

### 创建 / 查看 RoadMap Issue

首次创建（当前 Issue 已存在，无需重复执行）：

```bash
python3 docs/roadmap/sync_roadmap.py render gitee > /tmp/gitee-roadmap.md

gitee issue create -R changluJava/agent-forge \
  -t "AgentForge Development Roadmap" \
  -b "$(cat /tmp/gitee-roadmap.md)"
```

查看：

```bash
gitee issue view IKS14I -R changluJava/agent-forge
gitee issue list -R changluJava/agent-forge --json=number,title,state,html_url
```

### 常见问题

- Gitee 的 `repo` 子命令不提供编辑描述能力，使用 `gitee api ... -X PATCH`。
- `gitee api` 的 PATCH 不传 `name` 会返回 `HTTP 400: name is missing`。
- 本地 Roadmap 的相对链接不能直接在 Issue 中打开，必须使用 `sync_roadmap.py` 渲染后再同步。
- Gitee Issue 编号是字母数字（如 `IKS14I`），不是 GitHub 那种纯数字。

## 常用命令

### 查看 Issue 信息

```bash
gh issue view 1 --repo agentforge-tech/AgentForge
gitee issue view IKS14I -R changluJava/agent-forge
```

### 修改 Issue 标题

```bash
gh issue edit 1 --repo agentforge-tech/AgentForge --title 'AgentForge Development Roadmap'
gitee issue edit IKS14I -R changluJava/agent-forge -t 'AgentForge Development Roadmap'
```

### 置顶 Issue

```bash
gh issue pin 1 --repo agentforge-tech/AgentForge
```

Gitee 暂不支持通过 CLI 置顶，请在网页端操作。

### 检查 CLI 登录状态

```bash
gh auth status
gitee auth status
```

如果 GitHub 登录已失效，重新授权：

```bash
gh auth login -h github.com -p https -w
```
