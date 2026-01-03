# RecordPlatform

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green.svg)](https://spring.io/projects/spring-boot)

基于区块链和分布式存储的**企业级文件存证平台**。

[English](README.md)

---

## 核心特性

- **区块链存证** - 文件元数据上链至 FISCO BCOS，确保不可篡改和可追溯
- **分布式存储** - 多副本冗余，故障域隔离，一致性哈希，仲裁写入，降级写入支持，备用池自动提升，N-1 节点容错
- **分片上传** - 支持断点续传，AES-GCM/ChaCha20-Poly1305 可配置加密
- **文件分享** - 生成带访问次数和有效期限制的分享码
- **分享审计与溯源** - 记录访问、下载、保存操作，支持多级溯源链（A→B→C），完整分享访问日志
- **实时通知** - SSE 推送文件状态变更和消息通知，支持多设备同时在线
- **RBAC 权限** - 细粒度权限控制，资源所有权校验
- **多租户隔离** - 数据库、缓存、存储路径按租户隔离

## 快速开始

### 1. 前置依赖

确保以下服务已运行：

| 服务        | 端口  | 用途               |
| ----------- | ----- | ------------------ |
| Nacos       | 8848  | 服务发现与配置中心 |
| MySQL       | 3306  | 数据库             |
| Redis       | 6379  | 缓存与分布式锁     |
| RabbitMQ    | 5672  | 消息队列           |
| S3 兼容存储 | 9000  | 对象存储           |
| FISCO BCOS  | 20200 | 区块链节点         |

### 2. 构建

```bash
# 安装共享接口（必须首先执行）
mvn -f platform-api/pom.xml clean install

# 构建所有模块
mvn -f platform-backend/pom.xml clean package -DskipTests
mvn -f platform-fisco/pom.xml clean package -DskipTests
mvn -f platform-storage/pom.xml clean package -DskipTests
```

### 3. 启动

```bash
# 按顺序启动服务（Provider 先于 Consumer）
java -jar platform-storage/target/platform-storage-*.jar --spring.profiles.active=local
java -jar platform-fisco/target/platform-fisco-*.jar --spring.profiles.active=local
java -jar platform-backend/backend-web/target/backend-web-*.jar --spring.profiles.active=local
```

> 详细配置请参阅 [快速开始指南](docs/zh/getting-started/index.md)

## 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                          基础设施层                               │
│  Nacos    MySQL    RabbitMQ    Redis    S3 存储集群               │
└─────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              │         platform-api          │
              │      (共享 Dubbo 接口)         │
              └───────────────┬───────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         │                    │                    │
         ▼                    │                    ▼
┌─────────────────┐           │           ┌─────────────────┐
│ platform-fisco  │           │           │ platform-storage│
│ 区块链服务        │           │           │ 存储服务         │
│ (端口 8091)      │           │           │ (端口 8092)     │
└────────┬────────┘           │           └────────┬────────┘
         │         Dubbo RPC  │  Dubbo RPC         │
         └────────────────────┼────────────────────┘
                              ▼
                    ┌─────────────────┐
                    │ platform-backend│
                    │ REST API :8000  │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ FISCO BCOS 节点  │
                    └─────────────────┘
```

> 详细架构请参阅 [架构设计文档](docs/zh/architecture/index.md)

## 文档导航

| 主题                                         | 说明                                   |
| -------------------------------------------- | -------------------------------------- |
| [快速开始](docs/zh/getting-started/index.md) | 前置依赖、安装部署、配置说明           |
| [架构设计](docs/zh/architecture/index.md)    | 系统架构、分布式存储、区块链、安全机制 |
| [部署运维](docs/zh/deployment/index.md)      | Docker 部署、生产环境、监控告警        |
| [故障排查](docs/zh/troubleshooting/index.md) | 常见问题与解决方案                     |
| [API 参考](docs/zh/api/index.md)             | REST API 接口文档                      |

## 技术栈

| 分类     | 技术                              | 版本                     |
| -------- | --------------------------------- | ------------------------ |
| 后端     | Java, Spring Boot                 | 21, 3.2.11               |
| 微服务   | Apache Dubbo (Triple), Nacos      | 3.3.3                    |
| 区块链   | FISCO BCOS, Solidity              | 3.8.0, ^0.8.11           |
| 存储     | S3 兼容存储, MySQL, Redis         | AWS SDK 2.29, 8.0+, 6.0+ |
| 前端     | Svelte 5, SvelteKit, Tailwind CSS, Vite | 5.46, 2.49, 4.1, 6.0 |
| 弹性设计 | Resilience4j                      | 2.2.0                    |
| 监控     | Micrometer, Prometheus            | -                        |

## 项目结构

```
RecordPlatform/
├── platform-api/          # 共享 Dubbo 接口定义
├── platform-backend/      # 后端服务 (Dubbo Consumer, REST API)
│   ├── backend-web/       # 控制器、过滤器、安全配置
│   ├── backend-service/   # 业务逻辑、Saga、Outbox
│   ├── backend-dao/       # MyBatis Plus 映射、实体
│   ├── backend-api/       # 内部 API 接口
│   └── backend-common/    # 工具类、常量
├── platform-fisco/        # 区块链服务 (Dubbo Provider)
├── platform-storage/      # 存储服务 (Dubbo Provider)
│   ├── config/            # 节点与故障域配置
│   ├── core/              # 一致性哈希、故障域管理
│   ├── service/           # 存储、再平衡、备用池管理
│   ├── event/             # 拓扑变更事件
│   ├── health/            # 健康检查端点
│   └── tenant/            # 多租户支持
├── platform-frontend/     # Svelte 5 + SvelteKit 前端
├── scripts/               # 部署脚本
└── docs/                  # 文档 (en/zh)
```

## 参与贡献

1. Fork 本仓库
2. 创建功能分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'feat: add amazing feature'`)
4. 推送分支 (`git push origin feature/amazing-feature`)
5. 发起 Pull Request

## 许可证

本项目基于 Apache License 2.0 开源 - 详见 [LICENSE](LICENSE) 文件。
