# Monitor System Troubleshooting Guide

This guide helps diagnose and resolve common issues with the Monitor System deployment and operation.

## Table of Contents

1. [Quick Diagnostics](#quick-diagnostics)
2. [Common Issues](#common-issues)
3. [Service-Specific Issues](#service-specific-issues)
4. [Performance Issues](#performance-issues)
5. [Security Issues](#security-issues)
6. [Database Issues](#database-issues)
7. [Network Issues](#network-issues)
8. [Monitoring and Debugging](#monitoring-and-debugging)

## Quick Diagnostics

### System Health Check

```bash
# Check overall system status
kubectl get pods -n monitor-system
kubectl get services -n monitor-system
kubectl get ingress -n monitor-system

# Check resource usage
kubectl top pods -n monitor-system
kubectl top nodes

# Check events
kubectl get events -n monitor-system --sort-by='.lastTimestamp'
```

### Service Health Endpoints

```bash
# Check individual service health
curl http://api-gateway:8080/actuator/health
curl http://auth-service:8081/actuator/health
curl http://data-service:8082/actuator/health
curl http://websocket-service:8084/actuator/health

# Check metrics
curl http://api-gateway:8080/actuator/metrics
curl http://api-gateway:8080/actuator/prometheus
```

### Log Analysis

```bash
# View recent logs
kubectl logs -f deployment/monitor-api-gateway -n monitor-system --tail=100
kubectl logs -f deployment/monitor-data-service -n monitor-system --tail=100

# Search for errors
kubectl logs deployment/monitor-api-gateway -n monitor-system | grep -i error
kubectl logs deployment/monitor-data-service -n monitor-system | grep -i exception
```

## Common Issues

### 1. Service Won't Start

#### Symptoms
- Pod stuck in `Pending`, `CrashLoopBackOff`, or `ImagePullBackOff` state
- Service not responding to health checks

#### Diagnosis
```bash
# Check pod status and events
kubectl describe pod <pod-name> -n monitor-system

# Check logs for startup errors
kubectl logs <pod-name> -n monitor-system

# Check resource constraints
kubectl describe node <node-name>
```

#### Common Causes and Solutions

**Configuration Issues**
```bash
# Check ConfigMap and Secrets
kubectl get configmap monitor-config -n monitor-system -o yaml
kubectl get secret monitor-secrets -n monitor-system -o yaml

# Validate environment variables
kubectl exec -it <pod-name> -n monitor-system -- env | grep -E "(MYSQL|REDIS|INFLUX)"
```

**Resource Constraints**
```yaml
# Increase resource limits
resources:
  requests:
    memory: "1Gi"
    cpu: "500m"
  limits:
    memory: "2Gi"
    cpu: "1000m"
```

**Image Issues**
```bash
# Check image availability
docker pull monitor/api-gateway:latest

# Update image pull policy
imagePullPolicy: Always
```

### 2. Database Connection Issues

#### Symptoms
- "Connection refused" errors
- "Access denied" errors
- Slow database queries

#### Diagnosis
```bash
# Test database connectivity
kubectl exec -it <pod-name> -n monitor-system -- nc -zv mysql-service 3306

# Check database logs
kubectl logs deployment/mysql -n monitor-system

# Test authentication
kubectl exec -it <pod-name> -n monitor-system -- mysql -h mysql-service -u monitor -p
```

#### Solutions

**Connection Pool Issues**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      validation-timeout: 5000
      leak-detection-threshold: 60000
```

**Database Performance**
```sql
-- Check for slow queries
SELECT * FROM information_schema.processlist WHERE time > 10;

-- Check table locks
SHOW OPEN TABLES WHERE In_use > 0;

-- Optimize tables
OPTIMIZE TABLE metrics_data;
ANALYZE TABLE metrics_data;
```

### 3. Redis Connection Issues

#### Symptoms
- Cache misses
- Session management failures
- "Connection reset" errors

#### Diagnosis
```bash
# Test Redis connectivity
kubectl exec -it <pod-name> -n monitor-system -- redis-cli -h redis-service ping

# Check Redis memory usage
kubectl exec -it redis-pod -n monitor-system -- redis-cli info memory

# Monitor Redis commands
kubectl exec -it redis-pod -n monitor-system -- redis-cli monitor
```

#### Solutions

**Memory Issues**
```bash
# Check Redis memory configuration
kubectl exec -it redis-pod -n monitor-system -- redis-cli config get maxmemory

# Set memory policy
kubectl exec -it redis-pod -n monitor-system -- redis-cli config set maxmemory-policy allkeys-lru
```

**Connection Pool Issues**
```yaml
spring:
  redis:
    lettuce:
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 5
        max-wait: -1ms
```

### 4. InfluxDB Issues

#### Symptoms
- Metrics not being stored
- Query timeouts
- High memory usage

#### Diagnosis
```bash
# Check InfluxDB status
curl http://influxdb-service:8086/ping

# Check bucket and organization
influx bucket list --host http://influxdb-service:8086 --token <token>

# Monitor write performance
influx query 'from(bucket:"monitor-metrics") |> range(start:-1h) |> count()' \
  --host http://influxdb-service:8086 --token <token>
```

#### Solutions

**Write Performance Issues**
```yaml
monitor:
  data:
    influxdb:
      write:
        batch-size: 1000
        flush-interval: 1000ms
        retry-interval: 5000ms
```

**Memory Issues**
```toml
# influxdb.conf
[data]
  cache-max-memory-size = "1g"
  cache-snapshot-memory-size = "25m"
```

### 5. WebSocket Connection Issues

#### Symptoms
- Real-time updates not working
- Connection drops frequently
- High connection count

#### Diagnosis
```bash
# Check WebSocket service logs
kubectl logs deployment/monitor-websocket-service -n monitor-system

# Test WebSocket connection
wscat -c ws://websocket-service:8084/ws

# Check connection count
curl http://websocket-service:8084/actuator/metrics/websocket.connections
```

#### Solutions

**Connection Limits**
```yaml
monitor:
  websocket:
    max-connections: 1000
    heartbeat-interval: 30
    connection-timeout: 60000
```

**Load Balancing**
```yaml
# Use session affinity for WebSocket
service:
  sessionAffinity: ClientIP
  sessionAffinityConfig:
    clientIP:
      timeoutSeconds: 10800
```

## Service-Specific Issues

### API Gateway Issues

#### Rate Limiting Problems
```bash
# Check rate limit configuration
kubectl logs deployment/monitor-api-gateway -n monitor-system | grep "rate.limit"

# Monitor rate limit metrics
curl http://api-gateway:8080/actuator/metrics/gateway.requests
```

**Solution:**
```yaml
spring:
  cloud:
    gateway:
      filter:
        request-rate-limiter:
          redis-rate-limiter:
            replenish-rate: 100
            burst-capacity: 200
```

#### Circuit Breaker Issues
```bash
# Check circuit breaker status
curl http://api-gateway:8080/actuator/circuitbreakers

# Monitor circuit breaker events
curl http://api-gateway:8080/actuator/circuitbreakerevents
```

### Auth Service Issues

#### Certificate Validation Failures
```bash
# Check certificate store
kubectl exec -it redis-pod -n monitor-system -- redis-cli keys "cert:*"

# Validate certificate format
openssl x509 -in certificate.pem -text -noout
```

**Solution:**
```java
// Enable detailed certificate logging
logging.level.cn.flying.monitor.security.certificate=DEBUG
```

#### JWT Token Issues
```bash
# Check JWT configuration
kubectl logs deployment/monitor-auth-service -n monitor-system | grep -i jwt

# Validate token
curl -H "Authorization: Bearer <token>" http://auth-service:8081/api/validate
```

### Data Service Issues

#### Query Performance Problems
```bash
# Check slow queries
kubectl logs deployment/monitor-data-service -n monitor-system | grep "slow.query"

# Monitor query metrics
curl http://data-service:8082/actuator/metrics/query.execution.time
```

**Solution:**
```yaml
monitor:
  data:
    query:
      timeout: 30s
      max-results: 10000
      cache-enabled: true
```

## Performance Issues

### High CPU Usage

#### Diagnosis
```bash
# Check CPU usage by pod
kubectl top pods -n monitor-system --sort-by=cpu

# Check JVM metrics
curl http://service:port/actuator/metrics/jvm.threads.live
curl http://service:port/actuator/metrics/jvm.gc.pause
```

#### Solutions

**JVM Tuning**
```bash
JAVA_OPTS="
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
  -XX:+UseStringDeduplication
  -XX:MaxRAMPercentage=75.0
"
```

**Thread Pool Optimization**
```yaml
server:
  tomcat:
    threads:
      max: 200
      min-spare: 10
```

### High Memory Usage

#### Diagnosis
```bash
# Check memory usage
kubectl top pods -n monitor-system --sort-by=memory

# Check JVM heap usage
curl http://service:port/actuator/metrics/jvm.memory.used
curl http://service:port/actuator/metrics/jvm.memory.max
```

#### Solutions

**Memory Limits**
```yaml
resources:
  limits:
    memory: "2Gi"
  requests:
    memory: "1Gi"
```

**Heap Dump Analysis**
```bash
# Generate heap dump
kubectl exec -it <pod-name> -n monitor-system -- jcmd 1 GC.run_finalization
kubectl exec -it <pod-name> -n monitor-system -- jcmd 1 VM.gc
```

### Database Performance Issues

#### Slow Queries
```sql
-- Enable slow query log
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 2;

-- Check slow queries
SELECT * FROM mysql.slow_log ORDER BY start_time DESC LIMIT 10;
```

#### Connection Pool Exhaustion
```bash
# Monitor connection pool
curl http://service:port/actuator/metrics/hikaricp.connections.active
curl http://service:port/actuator/metrics/hikaricp.connections.pending
```

## Security Issues

### Certificate Authentication Failures

#### Diagnosis
```bash
# Check certificate validation logs
kubectl logs deployment/monitor-auth-service -n monitor-system | grep -i certificate

# Validate certificate chain
openssl verify -CAfile ca.pem client.pem
```

#### Solutions

**Certificate Store Issues**
```bash
# Check Redis certificate storage
kubectl exec -it redis-pod -n monitor-system -- redis-cli hgetall "cert:client-id"

# Refresh certificate cache
curl -X POST http://auth-service:8081/api/admin/certificates/refresh
```

### JWT Token Issues

#### Token Expiration
```bash
# Check token expiration
echo "<jwt-token>" | base64 -d | jq '.exp'

# Monitor token validation failures
curl http://auth-service:8081/actuator/metrics/jwt.validation.failures
```

### CORS Issues

#### Diagnosis
```bash
# Check CORS configuration
kubectl logs deployment/monitor-api-gateway -n monitor-system | grep -i cors

# Test CORS headers
curl -H "Origin: http://localhost:3000" \
     -H "Access-Control-Request-Method: POST" \
     -X OPTIONS http://api-gateway:8080/api/data
```

## Network Issues

### Service Discovery Problems

#### Diagnosis
```bash
# Check service endpoints
kubectl get endpoints -n monitor-system

# Test service connectivity
kubectl exec -it <pod-name> -n monitor-system -- nslookup mysql-service
kubectl exec -it <pod-name> -n monitor-system -- nc -zv mysql-service 3306
```

### Load Balancer Issues

#### Diagnosis
```bash
# Check service status
kubectl get services -n monitor-system

# Check ingress status
kubectl get ingress -n monitor-system
kubectl describe ingress monitor-ingress -n monitor-system
```

### DNS Resolution Issues

#### Diagnosis
```bash
# Test DNS resolution
kubectl exec -it <pod-name> -n monitor-system -- nslookup kubernetes.default.svc.cluster.local

# Check CoreDNS logs
kubectl logs -n kube-system deployment/coredns
```

## Monitoring and Debugging

### Enable Debug Logging

```yaml
logging:
  level:
    cn.flying.monitor: DEBUG
    org.springframework.security: DEBUG
    org.springframework.web: DEBUG
    org.springframework.data: DEBUG
```

### JVM Debugging

```bash
# Enable JVM debugging
JAVA_OPTS="$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

# Port forward for debugging
kubectl port-forward <pod-name> 5005:5005 -n monitor-system
```

### Profiling

```bash
# Enable JFR profiling
JAVA_OPTS="$JAVA_OPTS -XX:+FlightRecorder -XX:StartFlightRecording=duration=60s,filename=profile.jfr"

# Copy profile file
kubectl cp <pod-name>:/app/profile.jfr ./profile.jfr -n monitor-system
```

### Metrics Collection

```bash
# Collect all metrics
curl http://service:port/actuator/prometheus > metrics.txt

# Monitor specific metrics
watch -n 5 'curl -s http://service:port/actuator/metrics/jvm.memory.used | jq ".measurements[0].value"'
```

## Emergency Procedures

### Service Recovery

```bash
# Restart failed service
kubectl rollout restart deployment/monitor-api-gateway -n monitor-system

# Scale down and up
kubectl scale deployment/monitor-data-service --replicas=0 -n monitor-system
kubectl scale deployment/monitor-data-service --replicas=3 -n monitor-system
```

### Database Recovery

```bash
# MySQL recovery
kubectl exec -it mysql-pod -n monitor-system -- mysql -u root -p -e "SHOW PROCESSLIST;"
kubectl exec -it mysql-pod -n monitor-system -- mysql -u root -p -e "KILL <process-id>;"

# InfluxDB recovery
kubectl exec -it influxdb-pod -n monitor-system -- influx backup /backup/
```

### Cache Recovery

```bash
# Clear Redis cache
kubectl exec -it redis-pod -n monitor-system -- redis-cli flushdb

# Restart Redis
kubectl rollout restart deployment/redis -n monitor-system
```

## Getting Help

### Log Collection

```bash
# Collect all logs
kubectl logs --all-containers=true --prefix=true -n monitor-system > monitor-logs.txt

# Collect system information
kubectl get all -n monitor-system -o yaml > monitor-resources.yaml
kubectl describe nodes > node-info.txt
```

### Support Information

When reporting issues, include:

1. **System Information**
   - Kubernetes version
   - Node specifications
   - Network configuration

2. **Application Logs**
   - Service logs with timestamps
   - Error messages and stack traces
   - Configuration files

3. **Metrics and Monitoring**
   - Resource usage graphs
   - Performance metrics
   - Health check results

4. **Steps to Reproduce**
   - Detailed reproduction steps
   - Expected vs actual behavior
   - Environment differences

---

For additional support:
- [Configuration Guide](CONFIGURATION.md)
- [Deployment Guide](DEPLOYMENT.md)
- [API Documentation](API.md)