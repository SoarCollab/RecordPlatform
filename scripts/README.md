# 部署与启动指南 (Deployment & Startup Guide)

本文档详细说明了 RecordPlatform 平台的部署结构、启动顺序及脚本使用方法，特别是集成 SkyWalking 链路追踪的生产环境部署方案。

## 1. 推荐部署目录结构

在生产或测试环境服务器上，建议采用以下标准目录结构，以便于维护和脚本运行：

```text
/opt/record-platform/  <-- 项目根目录
├── bin/                        # 存放本目录下的所有 shell 脚本
│   ├── start-all-skywalking.sh # [核心] 一键启动所有服务(带SkyWalking)
│   ├── start-with-skywalking.sh# 单独启动某个服务(带SkyWalking)
│   └── start.sh                # 不带 Agent 的普通启动脚本
├── jars/                       # 存放 Maven 构建后的 jar 包
│   ├── backend-web-x.x.x.jar
│   ├── platform-fisco-x.x.x.jar
│   └── platform-storage-x.x.x.jar
├── agent/                      # SkyWalking Agent (解压自官方发布包)
│   ├── skywalking-agent.jar
│   ├── config/
│   └── logs/
├── logs/                       # 业务日志目录 (需要在 logback配置中指定或 java -jar > 重定向)
└── .env                        # 环境变量配置文件 (数据库、中间件密码等)
```

## 2. 启动顺序说明

由于使用了 Dubbo RPC 框架，服务启动有严格的依赖顺序关系：

1. **基础服务层 (Providers)**:
    * **Storage Service** (`platform-storage`): 提供 MinIO 文件存储能力。
    * **FISCO Service** (`platform-fisco`): 提供区块链交互能力。
    * *注意*: 这两个服务必须最先启动，等待注册到 Nacos 成功。脚本中默认设置了 10秒 的启动间隔。

2. **业务网关层 (Consumer)**:
    * **Backend Web** (`backend-web`): 核心 API 服务，依赖上述两个服务。

## 3. 脚本使用说明

### 3.1 一键全量启动 (推荐)

使用 `start-all-skywalking.sh` 脚本可按正确顺序启动所有服务，并自动挂载 SkyWalking探针。

**用法**:

```bash
# 赋予执行权限
chmod +x start-all-skywalking.sh

# 启动 (默认使用 prod profile)
./start-all-skywalking.sh

# 指定环境启动 (如 dev, test)
./start-all-skywalking.sh dev
```

**依赖的环境变量**:
脚本头部可配置以下变量，也可在 `.env` 或系统环境变量中设置：

* `SW_AGENT_HOME`: SkyWalking Agent 目录，默认为 `/opt/skywalking/agent`。
* `SW_COLLECTOR`: SkyWalking OAP 服务地址，默认为 `127.0.0.1:11800`。

### 3.2 单服务独立启动

如果需要单独重启某个服务，可以使用 `start-with-skywalking.sh`。

**用法**:

```bash
# 语法: ./start-with-skywalking.sh <服务名> [profile]
# 服务名可选: storage, fisco, web

./start-with-skywalking.sh storage prod
```

### 3.3 普通启动 (无Agent)

开发调试时，如果不需要链路追踪，可以使用 `start.sh`。

**用法**:

```bash
./start.sh all      # 启动所有
./start.sh backend  # 仅启动 backend
```

## 4. 环境变量配置 (.env)

项目根目录下的 `.env` 文件是配置的核心。启动前请确保以下关键变量已正确设置：

* `SERVER_PORT`: Backend Web 端口
* `DUBBO_FISCO_PORT`: Fisco 服务端口
* `DUBBO_MINIO_PORT`: Storage 服务端口
* `NACOS_SERVER_ADDR`: Nacos 地址
* `SKYWALKING_OAP_ADDR`: (可选) 用于覆盖默认收集器地址

## 5. 验证与排查

启动后，可以通过以下方式验证：

1. **查看进程**:

    ```bash
    jps -l
    # 应看到三个对应的 jar 进程
    ```

2. **查看日志**:
    检查 `logs/` 目录下的业务日志，或 SkyWalking Agent 目录下的 `logs/` 查看探针加载情况。

3. **SkyWalking UI**:
    访问 SkyWalking UI，应能看到三个服务节点 (`record-platform-storage`, `record-platform-fisco`, `record-platform-web`) 及其拓扑图。
