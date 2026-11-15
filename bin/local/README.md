# AgentForge 本地发布工具

`release.sh` 用于一键构建、GPG 签名并将 AgentForge 正式版构件推送到 Maven Central Portal。脚本只处理公开库模块，不发布 examples / service。

## 前置条件

1. GPG 私钥已生成，公钥已上传到公钥服务器。
2. Maven `settings.xml` 已配置 `<server id="central">` 的 Portal User Token。
3. `settings.xml` 统一放在 `bin/local/settings.xml`（已在 `.gitignore` 中忽略，不会提交到仓库）。
4. 正式版本号尚未在 Maven Central 发布。已发布的 GA 版本不可覆盖，重发会报 `Component with package url ... already exists`，必须递增版本号。

## 标准发布流程（三步）

以下命令均在仓库根目录执行，`settings.xml` 固定使用 `bin/local/settings.xml`。以当前下一版本 `1.0.1` 为例，后续发布替换为新版本号即可。

### 1. 签名 + 构建预检

```bash
bin/local/release.sh 1.0.1 --settings bin/local/settings.xml --dry-run
```

真实执行构建与 GPG 签名，但不上传。预检失败时先修复签名、测试或构建问题，不要直接进入正式上传。

### 2. 正式上传

```bash
bin/local/release.sh 1.0.1 --settings bin/local/settings.xml
```

脚本上传后，deployment 会停在 Central Portal 的待审核状态。

### 3. Portal 手动发布

```bash
open https://central.sonatype.com/publishing/deployments
```

确认构件完整后点击 **Publish**；发布生效后约 10–30 分钟同步到 `repo1.maven.org`。

## 其他用法

确认三步流程稳定后，可让 Central 上传后自动发布：

```bash
bin/local/release.sh 1.0.1 \
  --settings bin/local/settings.xml \
  --auto-publish
```

## 常见发布失败

| 报错 | 原因 | 处理 |
| --- | --- | --- |
| `Component with package url ... already exists` | 该版本坐标已发布到 Maven Central，GA 版本不可覆盖 | 检查 `repo1.maven.org` 对应目录，改用新版本号重新执行三步流程 |
| 新增模块（如 `agentforge-harness-agent`）缺失 | 已发布版本无法追加模块，BOM/Parent 也不可变 | 将新模块加入发布列表后，随下一个版本一起发布 |
| `Bad passphrase` / `损坏的密码` | GPG 口令不匹配 | 输入创建密钥时的 Passphrase，见下文说明 |

## 可选参数

| 参数 | 作用 |
| --- | --- |
| `--settings <file>` | 指定 Maven `settings.xml` |
| `--dry-run` | 真实构建与签名，不上传 |
| `--auto-publish` | 上传后由 Central 自动发布 |
| `--skip-tests` | 跳过测试，正式发布不建议使用 |
| `-h`, `--help` | 显示帮助 |

## 安全说明

- 脚本不包含 Central Token、GPG 私钥或 Passphrase。
- 未设置 `MAVEN_GPG_PASSPHRASE` 时，脚本会在终端中隐藏输入 GPG Passphrase。
- 脚本会自动探测 MacPorts / Homebrew 的 GPG 绝对路径，避免 GUI Terminal 的 `PATH` 缺失导致签名失败。
- Maven 启动前会先对临时文件执行签名与验签；口令或 GPG 配置不正确时会立即输出原始错误，不再等到 Maven 模块构建中途才失败。
- 不要将 `settings.xml`、私钥导出文件或口令提交到 Git。
- Maven Central 已发布版本不可覆盖，重试前必须确认版本号。

## `损坏的密码` / `Bad passphrase`

该报错表示输入的口令与当前 GPG 私钥不匹配。请输入**创建这把 GPG 密钥时设置的 Passphrase**，不是 Central Token、KEY_ID、密钥指纹或 macOS 登录密码。

如果只是输入错误，重新执行原命令即可。如果已忘记 GPG Passphrase，无法从私钥反向找回；需要生成新密钥、上传新公钥，再用新密钥发布。
