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

自动化完成受指纹门禁保护的本地 FISCO BCOS 智能合约生命周期：catalog 校验 → chain/group 对账 → 编译 → ABI/BIN 比对 → 部署 → 链上身份核验 → 审计回执 → 原子激活。脚本不会覆盖仓库中已签入的 ABI/BIN；产物升级必须先经代码审查更新 catalog。

### 前置条件

- FISCO BCOS 控制台已安装（默认路径 `~/fisco/console`）
- FISCO BCOS 节点已启动且可达；`.env` 必须显式配置 `FISCO_PEER_ADDRESS`、`FISCO_CHAIN_ID` 和 `FISCO_GROUP_ID`
- 控制台包含支持 `-v 0.8.11` 的 `contract2java.sh`，且 `$HOME/.fisco/solc/0.8.11/keccak256/solc` 与 `$HOME/.fisco/solc/0.8.11/sm3/solc` 两套官方编译器可执行；系统已安装 `python3` 以及 `timeout`（macOS 可使用 `gtimeout`）
- `.env` 已存在且不是符号链接；正常部署仅在全部链上检查通过后替换它
- `CONTRACT_DEPLOYMENT_RECEIPT_DIR` 指向持久化、受限访问的非符号链接目录；默认使用仓库内已忽略的 `log/contract-deployments`

### 使用示例

```bash
# 完整部署流程（使用默认控制台路径）
./scripts/contract-deploy.sh

# 指定自定义控制台目录
./scripts/contract-deploy.sh --console-dir /opt/fisco/console

# Dry-run 模式：打印将执行的步骤，不做任何实际操作
./scripts/contract-deploy.sh --dry-run

# 指定 .env 文件写回目标
./scripts/contract-deploy.sh --env-file /etc/record-platform/.env

# 显式指定受版本控制的 artifact catalog
./scripts/contract-deploy.sh --catalog-file platform-fisco/src/main/resources/contract-registry/artifacts.json

# 将不含凭据的结构化部署回执写入外部审计目录
./scripts/contract-deploy.sh --receipt-dir /var/lib/record-platform/contract-deployments
```

### 选项

| 选项                  | 说明                                              |
| --------------------- | ------------------------------------------------- |
| `--console-dir DIR`   | FISCO BCOS 控制台目录（默认：`~/fisco/console`） |
| `--console-launcher FILE` | 显式选择交互入口，仅允许 Console 根目录内的 `start.sh` 或 legacy `console.sh` |
| `--env-file FILE`     | 地址与部署证据的原子激活文件（默认：项目根目录的 `.env`） |
| `--catalog-file FILE` | 受版本控制的 artifact catalog 路径                        |
| `--receipt-dir DIR`   | 结构化部署回执目录（默认：`CONTRACT_DEPLOYMENT_RECEIPT_DIR` 或 `log/contract-deployments`） |
| `--dry-run`           | 校验输入并打印操作步骤，不修改文件或链状态                |
| `-h`, `--help`        | 显示帮助信息                                      |

脚本会在任何 Console 命令前解析唯一的安全交互入口：官方 Console 3.7 布局中优先使用 `start.sh`；只有在 `start.sh` 不存在时，才允许把 `console.sh` 作为明确的旧版交互入口。覆盖值可来自 `--console-launcher` 或 `FISCO_CONSOLE_LAUNCHER`，两者必须一致。入口必须为 Console 根目录内的可执行普通文件，禁止符号链接、子目录或外部路径。

部署前脚本通过官方 Console `getGroupInfo` 读取唯一的 `chainID`/`groupID`/`smCryptoType`/VM 元组：VM 字段接受当前布尔字段 `isWasm` 或 legacy 布尔字段 `wasm`，两者同时出现时必须相等；类型错误、冲突、缺失或未知别名都失败关闭。chain/group 必须与显式配置完全一致，WASM 或无法确定的 crypto/VM 组合会失败关闭。每笔链写前都会再次核验该上下文；部署后在同一个 Console 会话中执行 `getGroupInfo` 和 `getTransactionReceipt`，只接受唯一结构化回执，并要求显式 `status=0`、回执内 `transactionHash`/`contractAddress` 与部署输出一致、`blockNumber` 为非负整数。最终发布字段全部来自该同一成功回执，不会从多段文本拼接。随后 `getCode` 必须与节点实际 ECC/SM 变体对应的完整签入 runtime bytecode 完全一致，`contractIdentity()` 还必须等于已验证 catalog 中唯一 `ACTIVE` 条目的名称和语义版本。`--skip-verify` 已被明确拒绝。Dry-run 不调用 Console、不生成时间、不创建回执，也不修改链或 `.env`。

该脚本只负责 `BLOCKCHAIN_ACTIVE=local-fisco`；配置为 `bsn-fisco` 或 `bsn-besu` 时会在 Console 查询前拒绝执行，BSN 部署必须使用对应网络的受审查发布流程。

当前签入的 `Storage`/`Sharing` ABI、ECC/SM creation bytecode 与 ECC/SM deployed runtime bytecode 由 FISCO solc `0.8.11+commit.6b4cc280` 生成。构建画像固定为 EVM London、optimizer disabled、metadata IPFS；脚本既校验 Console `-v 0.8.11` 生成的两套 creation 制品，也用 Console 缓存的 keccak256/sm3 官方编译器在不可预测的系统临时目录中独立重建 creation/runtime，退出时安全清理。Console 源码使用同目录私有临时文件原子替换，失败退出时也会受限清理；源码/制品路径不得为符号链接，每笔部署交易前会再次核验 catalog、staged source、ABI 与两套 creation；任何缺失、替换、变体回退或字节漂移都会在对应链写之前失败。

### 执行阶段

| 阶段 | 说明 |
| ---- | ---- |
| 1. Pre-flight  | 校验工具、catalog/源码/ABI/creation/runtime、控制台、激活文件、节点连通性，并用 `getGroupInfo` 严格对账 chain/group/crypto/VM |
| 2. Compile     | 用官方 `contract2java.sh` 生成 ABI 与 ECC/SM creation，并用固定 keccak256/sm3 solc 重建 creation/runtime |
| 3. Artifact Verification | canonical 比对 ABI，并按 decoded bytes 比对 ECC/SM creation/runtime bytecode |
| 4. Deploy      | 顺序部署双合约；每笔链写前重验 chain/group，随后从同一上下文中的唯一成功结构化回执取得并交叉核对 tx/address/block |
| 5. On-chain Verification | 对两个地址先执行 `getCode` 并匹配节点实际 crypto 变体的完整 runtime，再执行 `contractIdentity()` 核对 catalog 名称/版本，任一失败即停止 |
| 6. Audited Atomic Activation | 固定一次 UTC `effectiveAt`，先原子发布结构化回执，再一次性写入两个地址及两组完整部署证据 |

新部署回执 schema 为 `record-platform-contract-deployment-receipt.v2`；历史 `v1` 回执只作为旧审计记录保留，不能替代启动时 RPC 校验。`chainType` 使用 registry 的本地 FISCO 公共枚举值，每个合约额外记录 `receiptStatus=SUCCESS`；地址、交易哈希和区块号均来自同一笔成功回执。回执还记录 catalog SHA-256、chain/group、名称、版本和同一 `effectiveAt`，但不记录 RPC URL、证书、私钥或令牌。回执写入失败时 `.env` 保持不变；若回执成功但随后 `.env` 原子替换失败，回执仍只是“链上部署已验证、尚未激活”的审计证据，不应被解释为运行时已启用。

`platform-fisco` 启动时还会通过当前 active chain 客户端重新查询每个配置交易：FISCO 必须为显式 `status=0`，Besu 必须为显式 `status=1`；缺失状态、RPC 错误、未出块/不存在的回执、普通交易的空/零合约地址、tx/address/block 任一不一致、重复地址或重复部署交易都会阻止发布任何 `ACTIVE` registry。`FISCO_*_DEPLOYMENT_TX`、`FISCO_*_DEPLOYMENT_BLOCK` 和 `FISCO_*_DEPLOYMENT_EFFECTIVE_AT` 不再支持整组留空；旧环境必须先从当前链取得真实成功回执并完整配置，无法证明时应重新部署，禁止手填或复制其他交易证据。

### 部署后

```bash
# 重启 FISCO 服务使新地址生效
./scripts/start.sh restart fisco

# 验证合约地址已正确配置
./scripts/env-check.sh --service contracts
```

`env-check.sh` 只检查字段格式和完整性。最终验收必须实际重启 `platform-fisco` 并确认 registry 初始化成功，因为只有服务启动校验会读取活动链回执、runtime code 和 `contractIdentity()`。回滚时应把上一版已审查的 catalog、两个地址及其各自真实 tx/block/effectiveAt 一次恢复；若任一旧回执无法在当前 chain/group 上复核，则保持服务停止并重新部署，不得删除校验或伪造证据。
