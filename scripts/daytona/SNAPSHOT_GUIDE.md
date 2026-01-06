# Daytona Snapshot 快速上手指南

完整的 RecordPlatform 集成测试执行指南。

---

## 📋 目录

1. [前置条件](#前置条件)
2. [快速开始](#快速开始)
3. [方式一：CLI 工具](#方式一cli-工具)
4. [方式二：TypeScript SDK](#方式二typescript-sdk)
5. [方式三：Python SDK](#方式三python-sdk)
6. [方式四：MCP 工具](#方式四mcp-工具)
7. [故障排查](#故障排查)
8. [性能优化](#性能优化)

---

## 前置条件

### 1. 获取 Daytona API Key

访问 [Daytona Dashboard](https://app.daytona.io/dashboard/api-keys) 获取 API Key。

```bash
export DAYTONA_API_KEY="dtna_xxxxxxxxxxxxx"
export DAYTONA_TARGET="us"  # 或 "eu"
```

### 2. 安装工具（根据使用方式选择）

**CLI 方式**：
```bash
# macOS
brew install daytonaio/cli/daytona

# Windows
powershell -Command "irm https://get.daytona.io/windows | iex"

# Linux
curl -sf https://get.daytona.io | sh
```

**TypeScript SDK**：
```bash
npm install @daytonaio/sdk dotenv tsx
```

**Python SDK**：
```bash
pip install daytona python-dotenv
```

---

## 快速开始

### 第一步：创建 Snapshot（仅首次）

```bash
cd scripts/daytona

# 方式 1: 使用脚本
chmod +x create-snapshot.sh
./create-snapshot.sh

# 方式 2: 使用 SDK
npx tsx daytona-sdk-example.ts create-snapshot
# 或
python daytona-sdk-example.py create-snapshot
```

⏱️ **预计耗时**：15-20 分钟（仅第一次）

### 第二步：运行测试

```bash
# 方式 1: CLI
daytona sandbox create --snapshot recordplatform-test-env --ephemeral
daytona exec <sandbox-id> -- bash /workspace/run-tests-optimized.sh

# 方式 2: SDK
npx tsx daytona-sdk-example.ts run-tests
# 或
python daytona-sdk-example.py run-tests
```

⏱️ **预计耗时**：2-3 分钟（环境启动）+ 10-15 分钟（测试执行）

---

## 方式一：CLI 工具

### 创建 Snapshot

```bash
cd scripts/daytona

# 检查配置
cat Dockerfile.snapshot

# 执行创建（支持选项定制）
./create-snapshot.sh \
  --name recordplatform-test-env \
  --cpu 4 \
  --memory 8 \
  --disk 10 \
  --dockerfile Dockerfile.snapshot
```

**选项说明**：
- `--name`：Snapshot 名称
- `--cpu`：CPU 核心数
- `--memory`：内存大小（GB）
- `--disk`：磁盘大小（GB）
- `--dry-run`：预览命令但不执行

### 运行测试

```bash
# 创建 Sandbox
SANDBOX_ID=$(daytona sandbox create \
  --snapshot recordplatform-test-env \
  --ephemeral \
  --cpu 4 \
  --memory 8 \
  --format json | jq -r '.id')

echo "Sandbox ID: $SANDBOX_ID"

# 执行测试
daytona exec $SANDBOX_ID -- bash /workspace/run-tests-optimized.sh

# 查看实时日志
daytona logs $SANDBOX_ID --follow

# 下载覆盖率报告
daytona download $SANDBOX_ID \
  /workspace/project/platform-backend/backend-web/target/site/jacoco/jacoco.xml \
  ./jacoco.xml

# Sandbox 会自动清理（ephemeral 模式）
```

### 环境变量控制

```bash
# 仅运行后端测试
daytona exec $SANDBOX_ID -- \
  bash -c "SKIP_FRONTEND=true bash /workspace/run-tests-optimized.sh"

# 仅运行前端测试
daytona exec $SANDBOX_ID -- \
  bash -c "SKIP_BACKEND=true bash /workspace/run-tests-optimized.sh"

# 测试指定分支
daytona exec $SANDBOX_ID -- \
  bash -c "BRANCH=develop bash /workspace/run-tests-optimized.sh"
```

---

## 方式二：TypeScript SDK

### 安装依赖

```bash
cd scripts/daytona
npm init -y
npm install @daytonaio/sdk dotenv tsx
```

### 创建环境变量文件

```bash
cat > .env << EOF
DAYTONA_API_KEY=dtna_xxxxxxxxxxxxx
DAYTONA_TARGET=us
EOF
```

### 创建 Snapshot

```bash
npx tsx daytona-sdk-example.ts create-snapshot
```

### 运行测试

```bash
npx tsx daytona-sdk-example.ts run-tests
```

### 集成到 Node.js 项目

```typescript
import { Daytona } from '@daytonaio/sdk';

async function runCI() {
  const daytona = new Daytona({
    apiKey: process.env.DAYTONA_API_KEY,
  });

  const sandbox = await daytona.create({
    snapshot: 'recordplatform-test-env',
    ephemeral: true,
  });

  const result = await sandbox.process.executeCommand(
    'bash /workspace/run-tests-optimized.sh',
    '/workspace'
  );

  if (result.exitCode !== 0) {
    throw new Error('Tests failed');
  }

  console.log('✅ All tests passed');
}

runCI().catch(console.error);
```

---

## 方式三：Python SDK

### 安装依赖

```bash
cd scripts/daytona
pip install daytona python-dotenv
```

### 创建 Snapshot

```bash
python daytona-sdk-example.py create-snapshot
```

### 运行测试

```bash
python daytona-sdk-example.py run-tests
```

### 集成到 Python 项目

```python
import os
from daytona import Daytona, DaytonaConfig, CreateSandboxParams

def run_ci():
    config = DaytonaConfig(api_key=os.getenv("DAYTONA_API_KEY"))
    daytona = Daytona(config)

    params = CreateSandboxParams(
        snapshot="recordplatform-test-env",
        ephemeral=True
    )
    sandbox = daytona.create(params)

    result = sandbox.process.execute_command(
        "bash /workspace/run-tests-optimized.sh",
        "/workspace"
    )

    if result.exit_code != 0:
        raise Exception("Tests failed")

    print("✅ All tests passed")

if __name__ == "__main__":
    run_ci()
```

---

## 方式四：MCP 工具

在 Claude Code 中直接使用 Daytona MCP 工具：

### 步骤 1：创建 Sandbox

```typescript
mcp__daytona-mcp__create_sandbox({
  snapshot: "recordplatform-test-env",
  cpu: 4,
  memory: 8,
  disk: 10,
  ephemeral: true,
  autoStopInterval: 30
})
```

记录返回的 `sandbox_id`。

### 步骤 2：更新代码

```typescript
mcp__daytona-mcp__execute_command({
  id: "<sandbox_id>",
  command: "cd /workspace/project && git pull origin main"
})
```

### 步骤 3：运行测试

```typescript
mcp__daytona-mcp__execute_command({
  id: "<sandbox_id>",
  command: "bash /workspace/run-tests-optimized.sh"
})
```

### 步骤 4：下载覆盖率报告

```typescript
mcp__daytona-mcp__file_download({
  id: "<sandbox_id>",
  filePath: "/workspace/project/platform-backend/backend-web/target/site/jacoco/jacoco.xml"
})
```

### 步骤 5：访问 Web Terminal（可选）

```typescript
mcp__daytona-mcp__preview_link({
  id: "<sandbox_id>",
  port: 22222,
  description: "Web Terminal",
  checkServer: false
})
```

浏览器打开返回的 URL 即可进入交互式终端。

---

## 故障排查

### 问题 1：Snapshot 创建超时

**症状**：`create-snapshot.sh` 执行超过 30 分钟无响应

**原因**：
- 网络连接到 Maven Central 或 npm registry 缓慢
- 资源不足（CPU/内存）

**解决方案**：
```bash
# 增加资源配置
./create-snapshot.sh --cpu 8 --memory 16

# 使用镜像源（修改 Dockerfile.snapshot）
RUN echo 'registry=https://registry.npmmirror.com' > ~/.npmrc
```

### 问题 2：Docker daemon 未启动

**症状**：`docker info` 报错 `Cannot connect to the Docker daemon`

**原因**：未使用 DinD 镜像或 Docker daemon 启动失败

**解决方案**：
```bash
# 验证使用的镜像
daytona sandbox list --format json | jq -r '.[] | .snapshot'

# 确保是 docker:*-dind 系列
# 错误示例：ubuntu:22.04
# 正确示例：docker:28.3.3-dind
```

### 问题 3：Maven 依赖仍在下载

**症状**：测试运行时仍显示 "Downloading from central"

**原因**：Snapshot 未正确缓存依赖

**解决方案**：
```bash
# 验证 Snapshot 中的 Maven 缓存
daytona exec <sandbox_id> -- du -sh /root/.m2/repository

# 应显示 > 500MB
# 如果为空或很小，需要重新创建 Snapshot
```

### 问题 4：前端测试失败

**症状**：`pnpm test:coverage` 报错

**原因**：
- Node.js 版本不兼容
- `pnpm-lock.yaml` 不存在或损坏

**解决方案**：
```bash
# 检查 Node.js 版本
daytona exec <sandbox_id> -- node --version
# 期望：v20.x 或更高

# 检查 lock 文件
daytona exec <sandbox_id> -- \
  ls -lh /workspace/project/platform-frontend/pnpm-lock.yaml

# 重新生成 lock 文件（如果损坏）
cd platform-frontend
pnpm install
git add pnpm-lock.yaml
git commit -m "fix: regenerate pnpm-lock.yaml"
```

### 问题 5：Testcontainers 失败

**症状**：
```
Could not find a valid Docker environment
```

**原因**：Docker socket 权限或路径问题

**解决方案**：
```bash
# 验证 Docker socket
daytona exec <sandbox_id> -- ls -l /var/run/docker.sock

# 检查 Testcontainers 配置
daytona exec <sandbox_id> -- \
  cat /workspace/project/platform-backend/backend-web/src/test/resources/application-test.yml

# 确保包含：
# spring:
#   docker:
#     compose:
#       enabled: false
```

---

## 性能优化

### 优化 1：使用预热镜像

在 `Dockerfile.snapshot` 中添加更多预热镜像：

```dockerfile
RUN nohup dockerd & \
    timeout 120 sh -c 'until docker info; do sleep 2; done' && \
    docker pull mysql:8.0 && \
    docker pull redis:7-alpine && \
    docker pull rabbitmq:3.12-management-alpine && \
    docker pull testcontainers/ryuk:0.5.1 && \
    pkill dockerd
```

**效果**：节省 2-3 分钟镜像拉取时间

### 优化 2：并行测试

修改 `run-tests-optimized.sh`：

```bash
# 后台运行后端测试
mvn -f platform-backend/pom.xml verify -Pit &
BACKEND_PID=$!

# 后台运行前端测试
cd platform-frontend && pnpm test:coverage &
FRONTEND_PID=$!

# 等待两者完成
wait $BACKEND_PID
BACKEND_RESULT=$?

wait $FRONTEND_PID
FRONTEND_RESULT=$?
```

**效果**：节省 5-8 分钟（取决于测试重叠度）

### 优化 3：增量更新 Snapshot

定期重建 Snapshot 以包含最新依赖：

```bash
# 每周自动重建
crontab -e

# 添加定时任务
0 2 * * 0 cd /path/to/RecordPlatform/scripts/daytona && ./create-snapshot.sh
```

### 优化 4：本地缓存覆盖率报告

```bash
# 创建本地缓存目录
mkdir -p ~/.daytona-cache/coverage

# 下载后保存到本地
daytona download $SANDBOX_ID \
  /workspace/project/platform-backend/backend-web/target/site/jacoco/jacoco.xml \
  ~/.daytona-cache/coverage/jacoco-$(date +%Y%m%d).xml

# 趋势分析
ls -lht ~/.daytona-cache/coverage/
```

---

## 成本控制

### 策略 1：自动清理

始终使用 `ephemeral: true` 和 `autoStopInterval`：

```typescript
const sandbox = await daytona.create({
  snapshot: 'recordplatform-test-env',
  ephemeral: true,           // 停止后自动删除
  autoStopInterval: 5,       // 5 分钟无活动自动停止
});
```

### 策略 2：按需创建

仅在以下场景创建 Sandbox：
- PR 提交时（而非每次 commit）
- 手动触发 CI
- 定时夜间回归测试

### 策略 3：资源分级

根据测试类型调整资源：

| 测试类型 | CPU | 内存 | 预计成本/次 |
|---------|-----|------|------------|
| 单元测试 | 2 | 4GB | $0.02 |
| 集成测试 | 4 | 8GB | $0.05 |
| 完整套件 | 4 | 8GB | $0.08 |

---

## 下一步

1. **集成到 CI/CD**：参考主方案文档中的 GitHub Actions 示例
2. **监控覆盖率趋势**：使用 Codecov 或 SonarQube
3. **多环境测试**：为 dev/staging/prod 创建不同的 Snapshot

---

## 相关资源

- [Daytona 官方文档](https://www.daytona.io/docs)
- [Testcontainers 指南](https://www.testcontainers.org/)
- [项目测试基类](../../platform-backend/backend-web/src/test/java/cn/flying/test/BaseIntegrationTest.java)

---

**问题反馈**：如遇到任何问题，请查看 [故障排查](#故障排查) 章节或联系开发团队。
