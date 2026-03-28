# Troubleshooting

Diagnosing and resolving common issues in RecordPlatform.

## Quick Reference

| Symptom | Likely Cause | Solution |
|---------|--------------|----------|
| Service won't start | Missing dependency | [Check prerequisites](../getting-started/prerequisites) |
| 401 Unauthorized | JWT expired/invalid | [Auth issues](#authentication-issues) |
| File upload fails | Storage node down | [Storage issues](#storage-issues) |
| Blockchain timeout | Node unreachable | [Blockchain issues](#blockchain-issues) |

## Topics

### [Common Issues](common-issues)
- Startup problems
- Authentication errors
- Storage failures
- Blockchain connectivity
- Performance issues

## Diagnostic Commands

### Check Service Health

```bash
# Backend health (includes downstream service status)
curl http://localhost:8000/record-platform/actuator/health
```

> **Note:** `platform-storage` and `platform-fisco` are Dubbo providers without embedded HTTP servers, so they do not expose `/actuator/health` endpoints directly. Their health is reflected in the backend's `/actuator/health` response (e.g., the `s3Storage` component). You can also monitor them via the OTel Collector Prometheus endpoint at `localhost:8889`.

### Check Logs

```bash
# View recent errors (default LOG_PATH is ./log relative to each service's working directory)
tail -f log/backend/error.log

# Search for specific error
grep -r "Exception" log/
```

> Override `LOG_PATH` environment variable to change the log directory (e.g., `LOG_PATH=/var/log/recordplatform`).

### Check Dependencies

```bash
# Nacos status
curl http://localhost:8848/nacos/v1/console/health/liveness

# MySQL connection
mysql -h <mysql-host> -u <mysql-user> -p -e "SELECT 1"

# Redis connection
redis-cli -h <redis-host> ping

# RabbitMQ status
rabbitmqctl status
```

## Getting Help

If you can't resolve an issue:

1. Check the [Common Issues](common-issues) guide
2. Review application logs for detailed error messages
3. Verify all [prerequisites](../getting-started/prerequisites) are running
4. Check [configuration](../getting-started/configuration) for correct values

