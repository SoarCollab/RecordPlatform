# 架构设计

RecordPlatform 技术架构文档。

## 目录

- [系统架构总览](system-overview) - 整体架构、组件和数据流
- [分布式存储](distributed-storage) - 故障域、一致性哈希、再平衡
- [区块链集成](blockchain-integration) - 智能合约、多链适配器
- [安全机制](security) - 认证、授权、加密

## 架构原则

### 1. 基于 Dubbo 的微服务

- 服务间通过 Dubbo Triple 协议通信（兼容 gRPC）
- 使用 Nacos 进行服务发现和配置管理
- 清晰分离：Provider（存储、区块链）和 Consumer（后端）

### 2. 分布式事务

- Saga 模式保证跨服务一致性
- Outbox 模式确保可靠的事件发布
- 自动补偿，支持指数退避重试

### 3. 高可用设计

- 故障域隔离（1~N 活跃域 + 可选 STANDBY 备用池）
- 一致性哈希实现数据分布
- 自动故障转移和再平衡

### 4. 安全优先

- JWT 认证（HMAC512）
- ID 混淆（AES-256-CTR + HMAC）
- RBAC 权限 + 资源所有权校验
- 多租户隔离

## 快速参考

| 组件 | 技术 | 用途 |
|------|------|------|
| 服务通信 | Dubbo Triple | 高性能 RPC |
| 服务发现 | Nacos | 注册与配置 |
| 分布式事务 | Saga + Outbox | 跨服务一致性 |
| 存储 | S3 兼容 | 对象存储 |
| 区块链 | FISCO BCOS | 不可篡改存证 |
| 缓存 | Caffeine + Redis | 多级缓存 |
| 弹性设计 | Resilience4j | 熔断、重试 |
