# AgentForge Documentation

AgentForge 的 Docusaurus 文档站，包含中英文内容、v1.0 / Next 版本切换，以及 GitHub Pages 自动部署配置。

## 本地开发

开发和编辑文档时，推荐直接启动开发服务器：

```bash
cd website
npm ci
npm start
```

默认地址为 `http://localhost:3000/AgentForge/`。开发服务器会直接读取最新源码，并在文档或配置修改后自动刷新页面。

英文开发模式可运行：

```bash
npm run start:en
```

## 生产构建

需要按生产模式预览时，必须先构建，再启动静态站点服务：

```bash
npm run build
npm run serve
```

> `npm run serve` 只负责预览 `website/build` 中上一次构建生成的静态文件，不会重新编译最新源码。修改文档后如果直接运行 `npm run serve`，可能仍然看到旧页面；请先执行 `npm run build`。

也可以使用下面的组合命令完成构建并启动预览：

```bash
npm run preview
```

`npm run preview` 等价于先执行 `npm run build`，再执行 `docusaurus serve`。访问 `http://localhost:3000/AgentForge/`，可以通过导航栏语言菜单切换中文与 English。

### 命令选择

| 场景 | 命令 | 是否读取最新源码 |
| --- | --- | --- |
| 日常编辑、热更新预览 | `npm start` | 是 |
| 英文文档开发预览 | `npm run start:en` | 是 |
| 生成生产静态文件 | `npm run build` | 是，输出到 `website/build` |
| 预览已有生产构建 | `npm run serve` | 否，只读取 `website/build` |
| 构建并立即预览 | `npm run preview` | 是 |

## 文档结构

- `docs/`：当前开发版（Next）的中文文档；
- `i18n/en/`：英文界面与文档；
- `versioned_docs/version-1.0/`：v1.0 中文快照；
- `versioned_sidebars/`：各版本侧边栏；
- `versions.json`：已发布版本列表。

发布新版本时，在 `website/` 中运行：

```bash
npm run docs:version -- 1.1
```

推送到 `main` 后，根目录 `.github/workflows/docs-pages.yml` 会构建双语站点并发布到 GitHub Pages。
