# Monitor System Configuration Guide

This guide provides detailed information about configuring the Monitor System for different environments and use cases.

## Table of Contents

1. [Configuration Overview](#configuration-overview)
2. [Environment Variables](#environment-variables)
3. [Application Properties](#application-properties)
4. [Database Configuration](#database-configuration)
5. [Security Configuration](#security-configuration)
6. [Performance Tuning](#performance-tuning)
7. [Monitoring Configuration](#monitoring-configuration)
8. [Environment-Specific Settings](#environment-specific-settings)

## Configuration Overview

The Monitor System uses a hierarchical configuration approach:

1. **Default Configuration**: Built-in defaults in `application.yml`
2. **Environment Configuration**: Environment-specific files (`application-{profile}.yml`)
3. **External Configuration**: Environment variables and ConfigMaps
4. **Runtime Configuration**: Dynamic configuration through admin APIs

### Configuration Precedence

1. Environment variables (highest priority)
2. External configuration files
3. Profile-specific application properties
4. Default application properties (lowest priority)

## Environment Variables

### Core Database Settings

```bash
# MySQL Configuration
MYSQL_HOST=localhost                    # MySQL server hostname
MYSQL_PORT=3306                        # MySQL server port
MYSQL_DATABASE=monitor_system          # Database name
MYSQL_USERNAME=monitor                 # Database username
MYSQL_PASSWORD=monitor123              # Database password
MYSQL_SSL_MODE=REQUIRED               # SSL mode (DISABLED, PREFERRED, REQUIRED)

# Connection Pool Settings
MYSQL_HIKARI_MAXIMUM_POOL_SIZE=20     # Maximum connections in pool
MYSQL_HIKARI_MINIMUM_IDLE=5           # Minimum idle connections
MYSQL_HIKARI_CONNECTION_TIMEOUT=30000  # Connection timeout (ms)
MYSQL_HIKARI_IDLE_TIMEOUT=600000      # Idle timeout (ms)
MYSQL_HIKARI_MAX_LIFETIME=1800000     # Maximum connection lifetime (ms)
```

### Redis Configuration

```bash
# Redis Server Settings
REDIS_HOST=localhost                   # Redis server hostname
REDIS_PORT=6379                       # Redis server port
REDIS_PASSWORD=                       # Redis password (empty if no auth)
REDIS_DATABASE=0                      # Redis database number
REDIS_TIMEOUT=2000                    # Connection timeout (ms)

# Redis Pool Settings
REDIS_LETTUCE_POOL_MAX_ACTIVE=20      # Maximum active connections
REDIS_LETTUCE_POOL_MAX_IDLE=10        # Maximum idle connections
REDIS_LETTUCE_POOL_MIN_IDLE=5         # Minimum idle connections
REDIS_LETTUCE_POOL_MAX_WAIT=-1        # Maximum wait time for connection

# Redis Cluster Settings (if using cluster)
REDIS_CLUSTER_NODES=redis1:6379,redis2:6379,redis3:6379
REDIS_CLUSTER_MAX_REDIRECTS=3
```

### InfluxDB Configuration

```bash
# InfluxDB Server Settings
INFLUXDB_URL=http://localhost:8086     # InfluxDB server URL
INFLUXDB_TOKEN=your-token-here         # Authentication token
INFLUXDB_ORG=monitor-org              # Organization name
INFLUXDB_BUCKET=monitor-metrics       # Default bucket name

# InfluxDB Client Settings
INFLUXDB_CONNECTION_TIMEOUT=10000     # Connection timeout (ms)
INFLUXDB_READ_TIMEOUT=30000          # Read timeout (ms)
INFLUXDB_WRITE_TIMEOUT=10000         # Write timeout (ms)
INFLUXDB_BATCH_SIZE=1000             # Batch size for writes

# Data Retention Settings
INFLUXDB_RETENTION_POLICY_1H=7d       # 1-hour data retention
INFLUXDB_RETENTION_POLICY_1D=90d      # 1-day data retention
INFLUXDB_RETENTION_POLICY_RAW=24h     # Raw data retention
```

### Message Queue Configuration

```bash
# RabbitMQ Settings
RABBITMQ_HOST=localhost               # RabbitMQ server hostname
RABBITMQ_PORT=5672                   # RabbitMQ server port
RABBITMQ_USERNAME=monitor            # RabbitMQ username
RABBITMQ_PASSWORD=monitor123         # RabbitMQ password
RABBITMQ_VIRTUAL_HOST=/              # Virtual host

# Connection Settings
RABBITMQ_CONNECTION_TIMEOUT=60000    # Connection timeout (ms)
RABBITMQ_REQUESTED_HEARTBEAT=60      # Heartbeat interval (seconds)
RABBITMQ_PUBLISHER_CONFIRMS=true     # Enable publisher confirms
RABBITMQ_PUBLISHER_RETURNS=true      # Enable publisher returns

# Queue Settings
RABBITMQ_QUEUE_DURABLE=true          # Make queues durable
RABBITMQ_QUEUE_AUTO_DELETE=false     # Auto-delete queues
RABBITMQ_PREFETCH_COUNT=10           # Consumer prefetch count
```

### Security Configuration

```bash
# JWT Settings
JWT_SECRET=your-jwt-secret-key        # JWT signing secret
JWT_EXPIRATION=3600                  # Token expiration (seconds)
JWT_REFRESH_EXPIRATION=86400         # Refresh token expiration (seconds)
JWT_ISSUER=monitor-system            # JWT issuer

# Certificate Authentication
CERTIFICATE_VALIDATION_ENABLED=true  # Enable certificate validation
CERTIFICATE_STORE_TYPE=REDIS         # Certificate storage (REDIS, DATABASE)
CERTIFICATE_CACHE_TTL=3600          # Certificate cache TTL (seconds)
CERTIFICATE_CRL_CHECK_ENABLED=true   # Enable CRL checking

# CORS Settings
CORS_ALLOWED_ORIGINS=http://localhost:3000,https://monitor.local
CORS_ALLOWED_METHODS=GET,POST,PUT,DELETE,OPTIONS
CORS_ALLOWED_HEADERS=*
CORS_ALLOW_CREDENTIALS=true
CORS_MAX_AGE=3600

# Rate Limiting
RATE_LIMIT_ENABLED=true              # Enable rate limiting
RATE_LIMIT_REQUESTS_PER_MINUTE=100   # Requests per minute per IP
RATE_LIMIT_BURST_CAPACITY=20         # Burst capacity
```

### Application Performance Settings

```bash
# Server Configuration
SERVER_PORT=8080                     # Server port
SERVER_SERVLET_CONTEXT_PATH=/        # Context path
SERVER_COMPRESSION_ENABLED=true      # Enable response compression
SERVER_HTTP2_ENABLED=true           # Enable HTTP/2

# Tomcat Settings
SERVER_TOMCAT_MAX_THREADS=200        # Maximum worker threads
SERVER_TOMCAT_MIN_SPARE_THREADS=10   # Minimum spare threads
SERVER_TOMCAT_MAX_CONNECTIONS=8192   # Maximum connections
SERVER_TOMCAT_ACCEPT_COUNT=100       # Accept queue size

# JVM Settings
JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC
JVM_XMS=512m                        # Initial heap size
JVM_XMX=2g                          # Maximum heap size
JVM_METASPACE_SIZE=256m             # Metaspace size
```

### Monitoring and Observability

```bash
# Actuator Settings
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics,prometheus
MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always
MANAGEMENT_METRICS_EXPORT_PROMETHEUS_ENABLED=true

# Tracing Settings
SPRING_SLEUTH_ENABLED=true           # Enable distributed tracing
SPRING_SLEUTH_SAMPLER_PROBABILITY=0.1 # Sampling rate (10%)
SPRING_SLEUTH_ZIPKIN_BASE_URL=http://jaeger:14268
SPRING_SLEUTH_JAEGER_HTTP_SENDER_URL=http://jaeger:14268/api/traces

# Logging Settings
LOGGING_LEVEL_ROOT=INFO              # Root logging level
LOGGING_LEVEL_MONITOR=DEBUG          # Monitor package logging level
LOGGING_PATTERN_CONSOLE=%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level [%X{traceId},%X{spanId}] %logger{36} - %msg%n
```

## Application Properties

### Core Application Configuration

```yaml
# application.yml
spring:
  application:
    name: monitor-system
  
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:local}
  
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/${MYSQL_DATABASE:monitor_system}?useSSL=${MYSQL_SSL_ENABLED:false}&allowPublicKeyRetrieval=true&serverTimezone=UTC
    username: ${MYSQL_USERNAME:monitor}
    password: ${MYSQL_PASSWORD:monitor123}
    driver-class-name: com.mysql.cj.jdbc.Driver
    
    hikari:
      maximum-pool-size: ${MYSQL_HIKARI_MAXIMUM_POOL_SIZE:20}
      minimum-idle: ${MYSQL_HIKARI_MINIMUM_IDLE:5}
      connection-timeout: ${MYSQL_HIKARI_CONNECTION_TIMEOUT:30000}
      idle-timeout: ${MYSQL_HIKARI_IDLE_TIMEOUT:600000}
      max-lifetime: ${MYSQL_HIKARI_MAX_LIFETIME:1800000}
      leak-detection-threshold: 60000
      pool-name: MonitorHikariCP
  
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
    database: ${REDIS_DATABASE:0}
    timeout: ${REDIS_TIMEOUT:2000ms}
    
    lettuce:
      pool:
        max-active: ${REDIS_LETTUCE_POOL_MAX_ACTIVE:20}
        max-idle: ${REDIS_LETTUCE_POOL_MAX_IDLE:10}
        min-idle: ${REDIS_LETTUCE_POOL_MIN_IDLE:5}
        max-wait: ${REDIS_LETTUCE_POOL_MAX_WAIT:-1ms}
  
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:monitor}
    password: ${RABBITMQ_PASSWORD:monitor123}
    virtual-host: ${RABBITMQ_VIRTUAL_HOST:/}
    
    connection-timeout: ${RABBITMQ_CONNECTION_TIMEOUT:60000ms}
    requested-heartbeat: ${RABBITMQ_REQUESTED_HEARTBEAT:60s}
    
    publisher-confirms: ${RABBITMQ_PUBLISHER_CONFIRMS:true}
    publisher-returns: ${RABBITMQ_PUBLISHER_RETURNS:true}
    
    listener:
      simple:
        prefetch: ${RABBITMQ_PREFETCH_COUNT:10}
        retry:
          enabled: true
          initial-interval: 1000ms
          max-attempts: 3
          multiplier: 2.0

server:
  port: ${SERVER_PORT:8080}
  servlet:
    context-path: ${SERVER_SERVLET_CONTEXT_PATH:/}
  
  compression:
    enabled: ${SERVER_COMPRESSION_ENABLED:true}
    mime-types: text/html,text/xml,text/plain,text/css,text/javascript,application/javascript,application/json
    min-response-size: 1024
  
  http2:
    enabled: ${SERVER_HTTP2_ENABLED:true}
  
  tomcat:
    threads:
      max: ${SERVER_TOMCAT_MAX_THREADS:200}
      min-spare: ${SERVER_TOMCAT_MIN_SPARE_THREADS:10}
    max-connections: ${SERVER_TOMCAT_MAX_CONNECTIONS:8192}
    accept-count: ${SERVER_TOMCAT_ACCEPT_COUNT:100}
    connection-timeout: 20000ms
```

### Service-Specific Configuration

#### API Gateway Configuration

```yaml
# application-gateway.yml
spring:
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: lb://monitor-auth-service
          predicates:
            - Path=/api/auth/**
          filters:
            - StripPrefix=2
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: ${RATE_LIMIT_REPLENISH_RATE:10}
                redis-rate-limiter.burstCapacity: ${RATE_LIMIT_BURST_CAPACITY:20}
        
        - id: data-service
          uri: lb://monitor-data-service
          predicates:
            - Path=/api/data/**
          filters:
            - StripPrefix=2
            - name: CircuitBreaker
              args:
                name: data-service-cb
                fallbackUri: forward:/fallback/data
      
      default-filters:
        - DedupeResponseHeader=Access-Control-Allow-Credentials Access-Control-Allow-Origin
        - name: Retry
          args:
            retries: 3
            methods: GET,POST
            backoff:
              firstBackoff: 50ms
              maxBackoff: 500ms

# Circuit Breaker Configuration
resilience4j:
  circuitbreaker:
    instances:
      data-service-cb:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        sliding-window-size: 10
        minimum-number-of-calls: 5
```

#### Data Service Configuration

```yaml
# application-data.yml
monitor:
  data:
    influxdb:
      url: ${INFLUXDB_URL:http://localhost:8086}
      token: ${INFLUXDB_TOKEN:}
      org: ${INFLUXDB_ORG:monitor-org}
      bucket: ${INFLUXDB_BUCKET:monitor-metrics}
      
      client:
        connection-timeout: ${INFLUXDB_CONNECTION_TIMEOUT:10000ms}
        read-timeout: ${INFLUXDB_READ_TIMEOUT:30000ms}
        write-timeout: ${INFLUXDB_WRITE_TIMEOUT:10000ms}
      
      write:
        batch-size: ${INFLUXDB_BATCH_SIZE:1000}
        flush-interval: ${INFLUXDB_FLUSH_INTERVAL:1000ms}
        retry-interval: ${INFLUXDB_RETRY_INTERVAL:5000ms}
    
    retention:
      raw-data: ${DATA_RETENTION_RAW:24h}
      hourly-data: ${DATA_RETENTION_HOURLY:7d}
      daily-data: ${DATA_RETENTION_DAILY:90d}
    
    aggregation:
      enabled: ${DATA_AGGREGATION_ENABLED:true}
      batch-size: ${DATA_AGGREGATION_BATCH_SIZE:1000}
      interval: ${DATA_AGGREGATION_INTERVAL:60s}
    
    cache:
      ttl: ${CACHE_TTL_SECONDS:300}
      max-size: ${CACHE_MAX_SIZE:10000}
      refresh-ahead-time: ${CACHE_REFRESH_AHEAD_TIME:60s}
```

## Database Configuration

### MySQL Optimization

```sql
-- Performance tuning for MySQL
SET GLOBAL innodb_buffer_pool_size = 2147483648;  -- 2GB
SET GLOBAL innodb_log_file_size = 268435456;      -- 256MB
SET GLOBAL innodb_flush_log_at_trx_commit = 2;
SET GLOBAL query_cache_size = 268435456;          -- 256MB
SET GLOBAL query_cache_type = 1;
SET GLOBAL max_connections = 200;
SET GLOBAL thread_cache_size = 16;
SET GLOBAL table_open_cache = 2000;

-- Indexes for better performance
CREATE INDEX idx_client_timestamp ON metrics_data(client_id, timestamp);
CREATE INDEX idx_metric_name ON metrics_data(metric_name);
CREATE INDEX idx_alert_status ON alerts(status, created_at);
CREATE INDEX idx_user_session ON user_sessions(user_id, expires_at);
```

### InfluxDB Configuration

```toml
# influxdb.conf
[http]
  enabled = true
  bind-address = ":8086"
  max-concurrent-requests = 0
  max-enqueued-requests = 0
  enqueued-request-timeout = "30s"

[data]
  dir = "/var/lib/influxdb2/engine"
  wal-dir = "/var/lib/influxdb2/wal"
  
  # Cache settings
  cache-max-memory-size = "1g"
  cache-snapshot-memory-size = "25m"
  cache-snapshot-write-cold-duration = "10m"
  
  # Compaction settings
  compact-full-write-cold-duration = "4h"
  max-concurrent-compactions = 0
  
  # Series settings
  max-series-per-database = 1000000
  max-values-per-tag = 100000

[retention]
  enabled = true
  check-interval = "30m"

[shard-precreation]
  enabled = true
  check-interval = "10m"
  advance-period = "30m"
```

### Redis Configuration

```conf
# redis.conf
# Memory management
maxmemory 2gb
maxmemory-policy allkeys-lru

# Persistence
save 900 1
save 300 10
save 60 10000

# Network
tcp-keepalive 300
timeout 0

# Clients
maxclients 10000

# Performance
tcp-backlog 511
databases 16

# Security
requirepass your-redis-password
rename-command FLUSHDB ""
rename-command FLUSHALL ""
rename-command DEBUG ""
```

## Security Configuration

### SSL/TLS Configuration

```yaml
# SSL configuration for production
server:
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-password: ${SSL_KEYSTORE_PASSWORD}
    key-store-type: PKCS12
    key-alias: monitor
    
    # TLS settings
    protocol: TLS
    enabled-protocols: TLSv1.2,TLSv1.3
    ciphers: ECDHE-RSA-AES256-GCM-SHA512:DHE-RSA-AES256-GCM-SHA512:ECDHE-RSA-AES256-SHA384

# Certificate authentication
monitor:
  security:
    certificate:
      enabled: ${CERTIFICATE_VALIDATION_ENABLED:true}
      store-type: ${CERTIFICATE_STORE_TYPE:REDIS}
      cache-ttl: ${CERTIFICATE_CACHE_TTL:3600}
      crl-check-enabled: ${CERTIFICATE_CRL_CHECK_ENABLED:true}
      
      validation:
        check-expiry: true
        check-revocation: true
        require-client-cert: true
        trusted-ca-certs: classpath:ca-certificates/
    
    jwt:
      secret: ${JWT_SECRET}
      expiration: ${JWT_EXPIRATION:3600}
      refresh-expiration: ${JWT_REFRESH_EXPIRATION:86400}
      issuer: ${JWT_ISSUER:monitor-system}
      
    cors:
      allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}
      allowed-methods: ${CORS_ALLOWED_METHODS:GET,POST,PUT,DELETE,OPTIONS}
      allowed-headers: ${CORS_ALLOWED_HEADERS:*}
      allow-credentials: ${CORS_ALLOW_CREDENTIALS:true}
      max-age: ${CORS_MAX_AGE:3600}
```

### Authentication Configuration

```yaml
# Spring Security configuration
spring:
  security:
    oauth2:
      client:
        registration:
          monitor:
            client-id: ${OAUTH2_CLIENT_ID}
            client-secret: ${OAUTH2_CLIENT_SECRET}
            scope: read,write
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
        
        provider:
          monitor:
            authorization-uri: ${OAUTH2_AUTHORIZATION_URI}
            token-uri: ${OAUTH2_TOKEN_URI}
            user-info-uri: ${OAUTH2_USER_INFO_URI}
            jwk-set-uri: ${OAUTH2_JWK_SET_URI}

# Method security
@EnableGlobalMethodSecurity(prePostEnabled = true, securedEnabled = true)
```

## Performance Tuning

### JVM Tuning

```bash
# Production JVM settings
JAVA_OPTS="
  -XX:+UseContainerSupport
  -XX:MaxRAMPercentage=75.0
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
  -XX:+UseStringDeduplication
  -XX:+OptimizeStringConcat
  -XX:+UseCompressedOops
  -XX:+UseCompressedClassPointers
  -Djava.security.egd=file:/dev/./urandom
  -XX:+UnlockExperimentalVMOptions
  -XX:+UseCGroupMemoryLimitForHeap
  -XX:+PrintGCDetails
  -XX:+PrintGCTimeStamps
  -Xloggc:/app/logs/gc.log
  -XX:+UseGCLogFileRotation
  -XX:NumberOfGCLogFiles=5
  -XX:GCLogFileSize=10M
"
```

### Connection Pool Tuning

```yaml
# HikariCP optimization
spring:
  datasource:
    hikari:
      # Pool sizing
      maximum-pool-size: 20
      minimum-idle: 5
      
      # Connection management
      connection-timeout: 30000      # 30 seconds
      idle-timeout: 600000          # 10 minutes
      max-lifetime: 1800000         # 30 minutes
      
      # Performance
      leak-detection-threshold: 60000  # 1 minute
      validation-timeout: 5000         # 5 seconds
      
      # Connection properties
      connection-test-query: SELECT 1
      connection-init-sql: SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO'
```

### Cache Configuration

```yaml
# Redis cache configuration
spring:
  cache:
    type: redis
    redis:
      time-to-live: ${CACHE_TTL_SECONDS:300}s
      cache-null-values: false
      use-key-prefix: true
      key-prefix: "monitor:cache:"

# Custom cache configuration
monitor:
  cache:
    metrics:
      ttl: 300s
      max-size: 10000
    
    certificates:
      ttl: 3600s
      max-size: 1000
    
    queries:
      ttl: 60s
      max-size: 5000
```

## Environment-Specific Settings

### Development Environment

```yaml
# application-dev.yml
spring:
  profiles:
    active: dev
  
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver
    username: sa
    password: 
  
  h2:
    console:
      enabled: true
  
  jpa:
    show-sql: true
    hibernate:
      ddl-auto: create-drop

logging:
  level:
    cn.flying.monitor: DEBUG
    org.springframework.security: DEBUG
    org.springframework.web: DEBUG

monitor:
  security:
    certificate:
      enabled: false
    
    cors:
      allowed-origins: "*"
```

### Testing Environment

```yaml
# application-test.yml
spring:
  profiles:
    active: test
  
  datasource:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driver-class-name: org.h2.Driver
  
  jpa:
    hibernate:
      ddl-auto: create-drop
  
  redis:
    host: localhost
    port: 6370  # Different port for testing

# Disable external dependencies for testing
monitor:
  influxdb:
    enabled: false
  
  rabbitmq:
    enabled: false
  
  security:
    certificate:
      enabled: false
```

### Production Environment

```yaml
# application-prod.yml
spring:
  profiles:
    active: prod
  
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false

logging:
  level:
    root: WARN
    cn.flying.monitor: INFO
  
  file:
    name: /app/logs/monitor.log
  
  logback:
    rollingpolicy:
      max-file-size: 100MB
      max-history: 30

monitor:
  security:
    certificate:
      enabled: true
      crl-check-enabled: true
  
  performance:
    cache-enabled: true
    async-enabled: true
    batch-processing: true
```

## Configuration Validation

### Health Checks

```yaml
# Custom health indicators
management:
  health:
    custom:
      enabled: true
    
    influxdb:
      enabled: true
    
    redis:
      enabled: true
    
    rabbitmq:
      enabled: true

# Custom health check configuration
monitor:
  health:
    database:
      timeout: 5s
      query: SELECT 1
    
    influxdb:
      timeout: 10s
      ping-query: true
    
    redis:
      timeout: 2s
      ping-command: true
```

### Configuration Properties Validation

```java
@ConfigurationProperties(prefix = "monitor")
@Validated
public class MonitorProperties {
    
    @NotNull
    @Valid
    private Security security = new Security();
    
    @NotNull
    @Valid
    private Performance performance = new Performance();
    
    public static class Security {
        @NotBlank
        private String jwtSecret;
        
        @Min(300)
        @Max(86400)
        private int jwtExpiration = 3600;
        
        // getters and setters
    }
    
    public static class Performance {
        @Min(1)
        @Max(1000)
        private int cacheSize = 100;
        
        @Min(60)
        @Max(3600)
        private int cacheTtl = 300;
        
        // getters and setters
    }
}
```

---

For more information, see:
- [Deployment Guide](DEPLOYMENT.md)
- [Troubleshooting Guide](TROUBLESHOOTING.md)
- [Security Guide](SECURITY.md)