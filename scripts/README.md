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
DUBBO_FISCO_PORT=20880
DUBBO_MINIO_PORT=20881
NACOS_SERVER_ADDR=127.0.0.1:8848
SW_COLLECTOR=127.0.0.1:11800
```

## 5. 日志文件

日志保存在 `log/` 目录：

| 文件               | 服务       |
| ------------------ | ---------- |
| `*-storage-*.json` | 存储服务   |
| `*-fisco-*.json`   | 区块链服务 |
| `*-spring-*.json`  | 后端服务   |
