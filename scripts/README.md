# 部署与启动指南

RecordPlatform 服务管理脚本使用说明。

## 1. 目录结构

```text
/opt/record-platform/
├── bin/                        # 启动脚本
│   ├── start.sh               # 服务管理脚本
│   └── env.sh                 # 环境配置
├── jars/                       # JAR 包
├── agent/                      # SkyWalking Agent
├── log/                        # 日志目录
└── .env                        # 环境变量
```

## 2. 服务管理命令

```bash
./start.sh <命令> [服务...] [选项]
```

### 命令

| 命令      | 说明            |
| --------- | --------------- |
| `start`   | 启动服务 (默认) |
| `stop`    | 停止服务        |
| `restart` | 重启服务        |
| `status`  | 查看状态        |

### 服务

| 服务      | 说明            |
| --------- | --------------- |
| `all`     | 全部服务 (默认) |
| `storage` | 存储服务        |
| `fisco`   | 区块链服务      |
| `backend` | 后端服务        |

### 选项

| 选项            | 说明            |
| --------------- | --------------- |
| `--skywalking`  | 启用 SkyWalking |
| `--foreground`  | 前台运行（仅对单服务 start 有效） |
| `--profile=xxx` | Spring Profile  |
| `--profile xxx` | Spring Profile  |

## 3. 使用示例

```bash
# 启动全部服务（带 SkyWalking）
./start.sh start all --skywalking

# 停止全部服务
./start.sh stop

# 重启后端服务
./start.sh restart backend

# 查看服务状态
./start.sh status

# 前台调试单个服务
./start.sh start storage --foreground --profile=dev

# 启动多个服务
./start.sh start storage fisco --profile=dev
```

## 4. 环境变量

在 `.env` 文件中配置：

```bash
SERVER_PORT=8080
DUBBO_FISCO_PORT=8091
DUBBO_STORAGE_PORT=8092
NACOS_HOST=127.0.0.1
NACOS_PORT=8848
SW_AGENT_COLLECTOR_BACKEND_SERVICES=127.0.0.1:11800
```

## 5. 日志文件

日志保存在 `log/` 目录：

| 文件               | 服务       |
| ------------------ | ---------- |
| `*-storage-*.json` | 存储服务   |
| `*-fisco-*.json`   | 区块链服务 |
| `*-spring-*.json`  | 后端服务   |

## 6. 智能合约部署 (`contract-deploy.sh`)

自动化完成 FISCO BCOS 智能合约的完整生命周期：编译 → 部署 → ABI 同步 → 地址回写 → 验证。

### 前置条件

- FISCO BCOS 控制台已安装（默认路径 `~/fisco/console`）
- FISCO BCOS 节点已启动且可达（`FISCO_PEER_ADDRESS` 已在 `.env` 中配置）

### 使用示例

```bash
# 完整部署流程（使用默认控制台路径）
./scripts/contract-deploy.sh

# 指定自定义控制台目录
./scripts/contract-deploy.sh --console-dir /opt/fisco/console

# Dry-run 模式：打印将执行的步骤，不做任何实际操作
./scripts/contract-deploy.sh --dry-run

# 跳过部署后验证
./scripts/contract-deploy.sh --skip-verify

# 指定 .env 文件写回目标
./scripts/contract-deploy.sh --env-file /etc/record-platform/.env
```

### 选项

| 选项                  | 说明                                              |
| --------------------- | ------------------------------------------------- |
| `--console-dir DIR`   | FISCO BCOS 控制台目录（默认：`~/fisco/console`） |
| `--env-file FILE`     | 地址写回目标文件（默认：项目根目录的 `.env`）     |
| `--skip-verify`       | 跳过部署后的合约调用验证                          |
| `--dry-run`           | 仅打印操作步骤，不执行任何更改                    |
| `-h`, `--help`        | 显示帮助信息                                      |

### 执行阶段

| 阶段 | 说明 |
| ---- | ---- |
| 1. Pre-flight  | 检查控制台目录、`console.sh` 可执行、节点连通性、合约源文件 |
| 2. Compile     | 将 `Storage.sol` 和 `Sharing.sol` 复制至控制台，触发编译    |
| 3. Deploy      | 部署双合约并捕获链上地址                                    |
| 4. ABI Sync    | 将编译产物 `.abi` 覆盖至 `platform-fisco/src/main/resources/abi/` |
| 5. Write-back  | 更新 `.env` 中的 `FISCO_STORAGE_CONTRACT` 和 `FISCO_SHARING_CONTRACT` |
| 6. Verify      | 调用只读方法（`getUserFiles`、`getShareInfo`）确认合约响应正常 |

### 部署后

```bash
# 重启 FISCO 服务使新地址生效
./scripts/start.sh restart fisco

# 验证合约地址已正确配置
./scripts/env-check.sh --service contracts
```
