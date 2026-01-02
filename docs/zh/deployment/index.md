# 部署运维

RecordPlatform 部署和运维指南。

## 目录

- [Docker Compose](docker-compose) - 容器化部署
- [生产环境](production) - 生产环境配置
- [监控告警](monitoring) - 指标、告警和健康检查

## 部署选项

| 选项 | 适用场景 | 复杂度 |
|------|----------|--------|
| 手动 JAR | 开发、测试 | 低 |
| Docker Compose | 单机生产 | 中 |
| Kubernetes | 多节点生产 | 高 |

## 快速参考

### 服务端口

| 服务 | 端口 | 协议 |
|------|------|------|
| platform-backend | 8000 | HTTP/REST |
| platform-fisco | 8091 | Dubbo Triple |
| platform-storage | 8092 | Dubbo Triple |
| platform-frontend | 5173 (开发) / 80 (生产) | HTTP |

### 启动顺序

```
1. 基础设施（Nacos, MySQL, Redis, RabbitMQ, S3, FISCO）
2. platform-storage（Dubbo Provider）
3. platform-fisco（Dubbo Provider）
4. platform-backend（Dubbo Consumer）
5. platform-frontend（可选，可单独部署）
```

### Profile

| Profile | 适用场景 | 特性 |
|---------|----------|------|
| `local` | 本地开发 | Debug 日志、Swagger 启用 |
| `dev` | 开发服务器 | 部分日志 |
| `prod` | 生产环境 | Swagger 禁用、SSL 必需 |

