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
# Backend health
curl http://localhost:8000/record-platform/actuator/health

# Storage health
curl http://localhost:8092/actuator/health

# FISCO health
curl http://localhost:8091/actuator/health
```

### Check Logs

```bash
# View recent errors
tail -f /var/log/recordplatform/backend/error.log

# Search for specific error
grep -r "Exception" /var/log/recordplatform/
```

### Check Dependencies

```bash
# Nacos status
curl http://localhost:8848/nacos/v1/console/health/liveness

# MySQL connection
mysql -h $DB_HOST -u $DB_USERNAME -p$DB_PASSWORD -e "SELECT 1"

# Redis connection
redis-cli -h $REDIS_HOST ping

# RabbitMQ status
rabbitmqctl status
```

## Getting Help

If you can't resolve an issue:

1. Check the [Common Issues](common-issues) guide
2. Review application logs for detailed error messages
3. Verify all [prerequisites](../getting-started/prerequisites) are running
4. Check [configuration](../getting-started/configuration) for correct values

