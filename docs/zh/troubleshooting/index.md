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
# 后端服务健康检查
curl http://localhost:8000/record-platform/actuator/health

# 存储服务健康检查
curl http://localhost:8092/actuator/health

# FISCO 服务健康检查
curl http://localhost:8091/actuator/health
```

### 查看日志

```bash
# 查看最新错误日志
tail -f /var/log/recordplatform/backend/error.log

# 搜索特定错误
grep -r "Exception" /var/log/recordplatform/
```

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

