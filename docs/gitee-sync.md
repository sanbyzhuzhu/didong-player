# GitHub 更新后自动同步 Gitee

GitHub 是发布源，Gitee 是单向镜像。工作流为 `.github/workflows/sync-to-gitee.yml`，实现位于 `tools/sync_gitee.py`。

## 一次性配置（由仓库所有者操作）

1. 在 Gitee 账户中创建一个专用于本仓库同步的私人令牌，授予推送该仓库及管理发行版/附件所需权限；使用服务提供的最小可用权限和合适的有效期。不要使用账户登录密码。
2. 打开 GitHub 仓库 `sanbyzhuzhu/didong-player` 的 **Settings → Secrets and variables → Actions → Secrets**，新增 Repository secret：名称 `GITEE_TOKEN`，值为上面的专用令牌。
3. 在同一页面的 **Variables** 中新增 Repository variable：`GITEE_SYNC_ENABLED`，值为小写 `true`。这是启用开关；令牌尚未就绪时不要开启。
4. Gitee 登录用户名默认 `sanbyzhu`。若使用拥有该仓库写权限的其他同步账号，可在 Variables 中设置 `GITEE_USERNAME`，填写该账号的用户名。
5. 先到 GitHub 的 **Actions → Sync to Gitee → Run workflow**，`release_tag` 填 `v0.0.24`，手动运行一次，补同步现有版本并确认凭证、权限和附件下载都正常。

默认未启用。未设开关时任务会跳过；开关已开但没有令牌时脚本也会明确跳过。工作流和脚本不会读取电脑的 Git 凭据管理器，不会把已有登录密码复制进 GitHub Secrets，也不会替用户创建或上传私人令牌。令牌只由用户直接填写到 GitHub 的 Secret 页面，不要发到聊天、写入仓库或普通 Variables。

## 触发方式

- GitHub `main` 更新：同步当前最新主分支的源码，包括 `updates/android.json`。
- 推送 `v*` 版本标签：同步该标签；标签源码以 GitHub 对应版本为准。
- GitHub 发行版公开发布（`release.published`）：同步标签、发行版标题/说明、APK、源码 ZIP、Windows 网页版 ZIP、说明及校验文件等附件。
- 手动 Run workflow：`release_tag` 留空只同步源码；填写已公开的版本标签则补同步该发行版及附件。更新已有发行版的附件后也可用此方式重跑。

同一时间只运行一个同步任务。短时间连续触发多次时 GitHub 可能只保留最新待运行任务；如某个旧发行版没有同步，使用手动补同步指定标签即可。仓库主分支仍以每次运行时 GitHub 最新 `main` 为准。

## 保留历史与版本保护

Gitee 曾因 GitHub 历史包含隐藏邮箱提交而拒收直接镜像推送。因此这里按源码树同步：在 Gitee 现有 `main` 后新增机器人镜像提交，文件内容与 GitHub 一致，Gitee 原有提交保留，不强推，也不修改账号邮箱隐私设置。

GitHub 与 Gitee 的提交号可以不同，判断是否同步应比较源码树/文件内容。发行版标签也按对应 GitHub 标签的源码树创建；即使 `main` 已有后续修改，旧标签仍保持旧版本内容。若 Gitee 已存在同名标签且内容不一致，任务停止，不改写旧标签。

这是 GitHub → Gitee 单向同步。在 Gitee 直接编辑的文件会在下一次同步时更新为 GitHub 内容，旧提交仍保留；需要长期保留的修改请先合并到 GitHub。

## 附件校验和重试

- 只同步已公开的 GitHub 发行版；下载时不将 GitHub/Gitee 令牌转发到附件 CDN。
- 先验证 GitHub 下载附件的大小、API 提供的摘要及 `SHA256SUMS.txt`，全部一致才开始创建/补齐 Gitee 发行版。
- 已有同名 Gitee 附件会下载并核对 SHA256，一致就复用，不重复上传；内容不同则失败，不删除或覆盖现有附件。
- 上传中断可能留下已成功上传的附件。直接手动重跑同一标签会检查现有状态并补缺，不会盲目重复 POST。
- Gitee 创建发行版的接口没有草稿阶段。请在任务成功并完成公开下载校验后，再对外宣布 Gitee 附件已就绪。
- 当前单附件同步上限 256 MB，单发行版附件少于 30 个；超过上限会明确停止。
- 附件使用 ASCII 文件名，如 `release-notes.md`。打包脚本已采用此名称，避免 GitHub 自动改写纯中文文件名后导致 SHA256 清单无法对应。

## 常见失败

- `HTTP 401/403`：检查 Gitee 专用令牌是否有效、是否有目标仓库推送和发行版/附件权限。不要通过公开令牌或关闭证书验证来解决。
- `Gitee ... different source tree`：同名标签已指向不同内容，需人工核对；工作流不会强行覆盖。
- `Existing Gitee asset differs`：已有同名附件内容不同。按项目约定发布新版本，不覆盖历史版本。
- 网络/下载中断：在 Actions 中手动重跑该标签，或重试失败任务。
- 暂停自动同步：将 Repository variable `GITEE_SYNC_ENABLED` 改为 `false`。轮换令牌时由所有者更新 Secret。

工作流只给 GitHub 内置令牌 `contents: read` 权限，不修改 GitHub 源码、设置或 Secrets。没有授予任何从本机获取登录凭证的能力。

## 本地无凭证回归

```powershell
python tools/test_sync_gitee.py
python tools/validate_update_feed.py
```

测试仅使用本地临时 Git 仓库和模拟 HTTP 接口，覆盖保留历史、重复运行、旧版本标签、标签冲突、部分附件上传后的重跑、校验失败、凭证边界，以及未配置时不会访问 Git/网络。真实端到端同步须由所有者配置专用令牌并启用后验证。
