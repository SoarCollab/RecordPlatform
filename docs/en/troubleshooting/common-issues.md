# Common Issues

Solutions for frequently encountered problems.

## Startup Issues

### Service Fails to Start

**Symptom**: Application exits immediately after startup.

**Possible Causes**:

1. **Nacos not reachable**
   ```
   Error: Failed to connect to Nacos server
   ```
   **Solution**: Ensure Nacos is running on port 8848.
   ```bash
   curl http://localhost:8848/nacos/v1/console/health/liveness
   ```

2. **Database connection failed**
   ```
   Error: Communications link failure
   ```
   **Solution**: Verify MySQL is running and credentials are correct.
   ```bash
   mysql -h <mysql-host> -u <mysql-user> -p RecordPlatform
   ```

3. **Port already in use**
   ```
   Error: Address already in use: 8000
   ```
   **Solution**: Kill the existing process or use a different port.
   ```bash
   lsof -i :8000
   kill -9 <PID>
   ```

### Dubbo Service Registration Failed

**Symptom**: Provider services not visible in Nacos console.

**Solution**:
1. Check Nacos address in configuration
2. Verify network connectivity to Nacos
3. Check Dubbo protocol port is not blocked

## Authentication Issues

### 401 Unauthorized

**Symptom**: All API requests return 401.

**Possible Causes**:

1. **JWT expired**
   - Tokens expire after the configured TTL
   - **Solution**: Re-authenticate to get a new token

2. **Invalid JWT_KEY**
   - If `JWT_KEY` changed after token issuance
   - **Solution**: Clear client tokens and re-login

3. **Missing Authorization header**
   - Request lacks `Authorization: Bearer <token>`
   - **Solution**: Include the header in all authenticated requests

### 403 Forbidden

**Symptom**: User authenticated but access denied.

**Possible Causes**:

1. **Insufficient permissions**
   - User lacks required role/permission
   - **Solution**: Check user's role assignments

2. **Resource ownership**
   - Trying to access another user's resources
   - **Solution**: Verify resource ownership or admin privileges

## Storage Issues

### File Upload Fails

**Symptom**: Upload returns error or times out.

**Possible Causes**:

1. **S3 node offline**
   ```bash
   # Check node status
   curl http://localhost:8092/actuator/health
   ```
   **Solution**: Restart the offline node or wait for failover.

2. **Insufficient replicas**
   - Not enough healthy nodes for replication factor
   - **Solution**: Ensure at least `replicationFactor` nodes are online.

3. **Bucket not exists**
   ```
   Error: The specified bucket does not exist
   ```
   **Solution**: Create the bucket or check bucket name configuration.

### File Download Fails

**Symptom**: Download returns 404 or corrupted data.

**Possible Causes**:

1. **File not fully replicated**
   - Saga transaction incomplete
   - **Solution**: Check saga status in database.

2. **Shard corruption**
   - One or more shards are corrupted
   - **Solution**: Trigger consistency repair via admin endpoint.

3. **Node containing shard is offline**
   - **Solution**: Wait for node recovery or trigger rebalance.

### Saga Transaction Stuck

**Symptom**: Upload shows as pending indefinitely.

**Solution**:
1. Check `file_saga` table for status
2. Review saga step failures in `file_saga_step`
3. Trigger manual compensation if needed:
   ```sql
   UPDATE file_saga SET status = 'COMPENSATING'
   WHERE saga_id = '<id>' AND status = 'RUNNING';
   ```

## Blockchain Issues

### Connection Timeout

**Symptom**: Blockchain operations timeout after 30s.

**Possible Causes**:

1. **FISCO node unreachable**
   ```bash
   # Test connectivity
   telnet 127.0.0.1 20200
   ```
   **Solution**: Verify node is running and network allows connection.

2. **Certificate mismatch**
   - SDK certificates don't match node certificates
   - **Solution**: Regenerate and deploy matching certificates.

3. **Circuit breaker open**
   - Too many failures triggered circuit breaker
   - **Solution**: Wait for half-open state or restart service.

### Contract Call Failed

**Symptom**: Smart contract operations return errors.

**Possible Causes**:

1. **Contract not deployed**
   ```
   Error: Contract address is null
   ```
   **Solution**: Deploy contracts and update addresses in config.

2. **Insufficient gas**
   - Transaction exceeds gas limit
   - **Solution**: Increase gas limit in configuration.

3. **Permission denied**
   - Caller not authorized for the contract
   - **Solution**: Check contract ACL configuration.

## Performance Issues

### Slow API Response

**Symptom**: API requests take >5 seconds.

**Diagnostic Steps**:

1. **Check database queries**
   ```bash
   # Enable slow query log
   SET GLOBAL slow_query_log = 'ON';
   SET GLOBAL long_query_time = 1;
   ```

2. **Check connection pools**
   - Druid monitor: `/record-platform/druid/`
   - Look for connection wait times

3. **Check S3 node latency**
   - Review `s3_node_load_score` metrics
   - Consider adding more nodes

### High Memory Usage

**Symptom**: JVM heap usage >80%.

**Solutions**:

1. **Increase heap size**
   ```bash
   JAVA_OPTS="-Xms4g -Xmx8g"
   ```

2. **Check for memory leaks**
   - Enable heap dumps on OOM
   - Analyze with Eclipse MAT or VisualVM

3. **Reduce concurrent uploads**
   - Limit `multipart.max-file-size`
   - Reduce chunk buffer sizes

### Database Connection Pool Exhausted

**Symptom**: "Cannot acquire connection from pool" errors.

**Solutions**:

1. **Increase pool size**
   ```yaml
   spring:
     datasource:
       druid:
         max-active: 100
   ```

2. **Fix connection leaks**
   - Check for unclosed connections in code
   - Enable connection leak detection in Druid

3. **Optimize slow queries**
   - Add missing indexes
   - Review query execution plans

## Redis Issues

### Cache Miss Rate High

**Symptom**: Database queries for cached data.

**Solutions**:

1. **Check Redis connectivity**
   ```bash
   redis-cli -h <redis-host> ping
   ```

2. **Verify TTL settings**
   - Ensure cache isn't expiring too quickly

3. **Check memory eviction**
   - If Redis is evicting keys due to memory pressure
   - Increase `maxmemory` or reduce cached data

### Redis Connection Timeout

**Symptom**: Intermittent Redis timeouts.

**Solutions**:

1. **Check network latency**
   ```bash
   redis-cli -h <redis-host> --latency
   ```

2. **Increase connection timeout**
   ```yaml
   spring:
     redis:
       timeout: 5000
   ```

3. **Use connection pooling**
   - Configure Lettuce pool settings

## RabbitMQ Issues

### Messages Not Being Consumed

**Symptom**: Queue depth keeps growing.

**Solutions**:

1. **Check consumer is running**
   ```bash
   rabbitmqctl list_consumers
   ```

2. **Check for consumer errors**
   - Review application logs for listener exceptions

3. **Check queue bindings**
   ```bash
   rabbitmqctl list_bindings
   ```

### Dead Letter Queue Growing

**Symptom**: DLQ has many messages.

**Solutions**:

1. **Review message processing errors**
   - Check application logs for root cause

2. **Reprocess DLQ messages**
   - Move messages back to main queue after fixing issue

3. **Adjust retry settings**
   ```yaml
   spring:
     rabbitmq:
       listener:
         simple:
           retry:
             max-attempts: 5
   ```

