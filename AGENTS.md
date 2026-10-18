# AGENTS.md

## 推送规则（强制）

- 每次推送远程时，必须推送到所有 remote（当前为 `origin` 与 `gitee`）的对应分支，不能只推一个。
- 例如：`git push origin <branch>` 与 `git push gitee <branch>` 都要执行。
