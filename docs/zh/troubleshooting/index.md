# 故障排查

RecordPlatform 常见问题诊断与解决方案。

## 快速参考

| 症状 | 可能原因 | 解决方案 |
|------|----------|----------|
| 服务无法启动 | 依赖服务缺失 | [检查前置条件](../getting-started/prerequisites) |
| 401 未授权 | JWT 过期/无效 | [认证问题](#认证问题) |
| 文件上传失败 | 存储节点宕机 | [存储问题](#存储问题) |
| 区块链超时 | 节点不可达 | [区块链问题](#区块链问题) |

## 主题

### [常见问题](common-issues)
- 启动问题
- 认证错误
- 存储故障
- 区块链连接
- 性能问题

## 诊断命令

### 检查服务健康状态

```bash
# 后端服务健康检查（包含下游服务状态）
curl http://localhost:8000/record-platform/actuator/health
```

> **注意：** `platform-storage` 和 `platform-fisco` 是 Dubbo Provider，没有内嵌 HTTP 服务器，因此不直接暴露 `/actuator/health` 端点。它们的健康状态通过 backend 的 `/actuator/health` 响应间接反映（如 `s3Storage` 组件）。也可通过 OTel Collector 的 Prometheus 端点 `localhost:8889` 监控。

### 查看日志

```bash
# 查看最新错误日志（默认 LOG_PATH 为各服务工作目录下的 ./log）
tail -f log/backend/error.log

# 搜索特定错误
grep -r "Exception" log/
```

> 通过 `LOG_PATH` 环境变量可自定义日志目录（如 `LOG_PATH=/var/log/recordplatform`）。

### 检查依赖服务

```bash
# Nacos 状态
curl http://localhost:8848/nacos/v1/console/health/liveness

# MySQL 连接
mysql -h <mysql-host> -u <mysql-user> -p -e "SELECT 1"

# Redis 连接
redis-cli -h <redis-host> ping

# RabbitMQ 状态
rabbitmqctl status
```

## 获取帮助

如果无法解决问题：

1. 查阅[常见问题](common-issues)指南
2. 检查应用日志获取详细错误信息
3. 验证所有[前置条件](../getting-started/prerequisites)服务正常运行
4. 检查[配置](../getting-started/configuration)值是否正确

