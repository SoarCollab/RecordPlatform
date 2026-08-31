# 发布公有容器镜像

发布镜像必须支持匿名拉取，验证通过后才能创建 GitHub Release。将 GHCR 镜像包设为公有会公开**该包的全部历史版本**，而且无法改回私有。操作前必须审查历史内容；部署凭据、证书、`.env` 文件及私有配置不得进入镜像。

即使源仓库公开，GitHub 新建的容器镜像包默认仍为私有。可见性是 GitHub 的包设置，不是 Docker 构建标签或推送选项。管理员需要在 **Package settings → Danger Zone → Change visibility → Public** 中完成一次性转换。不要为此新增个人访问令牌或扩大组织权限。平台规则来源：[GitHub：包可见性](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility)、[GitHub：容器镜像仓库](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)。

## 拉取已发布镜像

五个镜像包的坐标如下：

| 组件 | 镜像 |
| --- | --- |
| 后端 | `ghcr.io/soarcollab/recordplatform-backend` |
| FISCO | `ghcr.io/soarcollab/recordplatform-fisco` |
| 存储 | `ghcr.io/soarcollab/recordplatform-storage` |
| 前端 | `ghcr.io/soarcollab/recordplatform-frontend` |
| 公开验证器 | `ghcr.io/soarcollab/recordplatform-verifier-web` |

镜像包公开后无需登录镜像仓库：

```bash
docker pull ghcr.io/soarcollab/recordplatform-backend:0.0.3
```

需要可复现部署时，应固定经过核实的 manifest 摘要，而不是使用可变别名。目前在 Ubuntu runner 上构建的是单平台 Linux 镜像，不是多平台发布。拉取镜像本身不会部署或启动服务。

## 发布后续版本

`.github/workflows/release.yml` 继续作为唯一的构建、打包和推送流程。小写语义版本 Git 标签（例如 `v0.0.3`）为该稳定版本生成 `0.0.3`、`0.0`、`sha-0c2cad2` 和 `latest` 镜像别名。后续版本会更新适用的可变别名；预发布版本仍遵循当前固定版本 Docker metadata action 的规则。

1. 只读 build job 构建现有模块，保存五个镜像归档及生成的 `*.tags` 文件，不持有镜像仓库写入令牌。
2. publish job 加载原始归档，使用原有 `GITHUB_TOKEN` 权限推送每个别名。
3. `tools/ci/verify_public_images.py` 在**拉取任何别名之前**，记录全部本地已推送镜像的 ID 和仓库 manifest 摘要，再逐个匿名执行 Docker pull，比对命令报告的 manifest 摘要及拉取后的本地镜像身份。
4. 全部验证成功后才创建 GitHub Release。缺失或空的标签文件/归档、包名错误、匿名访问被拒绝、镜像不存在、身份不明确、摘要变化和重试耗尽都将阻断发布。

验证使用全新的临时 `HOME` 和显式 `DOCKER_CONFIG`，配置中只有一个空的 `ghcr.io` auth 条目，用于禁止自动发现凭据助手。子进程环境仅包含 `PATH`、`HOME`、`DOCKER_CONFIG`、`LANG`、`LC_ALL`，不会继承 GitHub 令牌、Docker 认证配置、自定义认证请求头或 Docker context。只使用 CI 本地 daemon，不使用调用方选择的远程 daemon。每次拉取限时 180 秒，最多尝试三次，间隔五秒；工作流步骤最长 30 分钟。Docker 可以复用已从归档加载的层，因此该发布检查证明匿名拉取权限及镜像身份，不代表每个层都重新下载。

新增组件时，归档与标签文件应使用相同文件名主体，对应 `recordplatform-<主体>` 镜像包；已有 `verifier` 主体对应 `recordplatform-verifier-web`。新增标签文件会自动参与验证，现有五个文件仍为必需。变更支持的服务集合时，同步更新必需组件约束、回归测试及运维文档。

## 处理公开发布检查失败

新创建的包仍为私有时，先审查并通过 GitHub 支持的页面操作将整个包改为 Public。等待设置生效后，在同一次工作流运行中使用原始 `release-bundle`，**仅重跑失败的 publish job**。目前该 artifact 只保留一天。不要为了修改可见性而重跑构建：依赖或基础镜像的重新解析可能产生不同字节。

如果 artifact 已过期，应停止并恢复与已推送摘要一致、经过核实的原始产物，再决定恢复方案。不要移动已有 Git 标签、覆盖历史镜像字节、删除包版本、弱化校验或手工创建 Release 绕过检查。摘要不匹配时还要核对是否有其他版本更新了 `latest` 等共享别名，不能自动用旧版本覆盖新版本。

## 独立执行匿名拉取验收

**Verify Public Images** 工作流仅支持手动触发（`workflow_dispatch`），仓库权限只读，不登录镜像仓库，不执行构建、推送或 Release 创建。它使用已审查的 `tools/ci/fixtures/v0.0.3-public-images.json` 快照：在新 runner 上先匿名拉取五个不可变 manifest，再调用同一验证脚本，确认二十个历史别名仍指向这些镜像。

```bash
gh workflow run verify-public-images.yml --ref main
```

工作流进入默认分支且包公开设置完成后再执行。这是 **v0.0.3 的历史验收快照**，不是关于 `latest` 或 `0.0` 永远不变的承诺。后续版本移动这些别名后，该快照验证会按设计失败；以后手动验收需要另行审核新快照。日常新版本发布使用该次构建的真实镜像归档及标签文件，不使用此固定快照。手动工作流不作为普通 PR 的依赖，也不会向任何服务器部署。

不需要 Docker 或镜像仓库访问的本地回归检查：

```bash
python3 -m unittest discover -s tools/ci/tests -p 'test_public_images.py' -v
actionlint .github/workflows/release.yml .github/workflows/verify-public-images.yml
```

这些确定性测试不等于真实 Docker 验收；仅成功请求 manifest 的 HTTP 验证也不能证明 Docker pull 或完整镜像层下载，报告时需要明确区分。
