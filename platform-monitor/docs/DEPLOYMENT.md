# Monitor System Deployment Guide

This guide provides comprehensive instructions for deploying the Monitor System in various environments including Docker Compose, Kubernetes, and production setups.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Quick Start with Docker Compose](#quick-start-with-docker-compose)
3. [Kubernetes Deployment](#kubernetes-deployment)
4. [Production Deployment](#production-deployment)
5. [Configuration](#configuration)
6. [Monitoring and Observability](#monitoring-and-observability)
7. [Troubleshooting](#troubleshooting)

## Prerequisites

### System Requirements

- **CPU**: Minimum 4 cores, Recommended 8+ cores
- **Memory**: Minimum 8GB RAM, Recommended 16GB+ RAM
- **Storage**: Minimum 50GB, Recommended 200GB+ SSD
- **Network**: Stable internet connection with ports 80, 443, 8080-8090 available

### Software Requirements

#### For Docker Compose Deployment
- Docker Engine 20.10+
- Docker Compose 2.0+
- Git

#### For Kubernetes Deployment
- Kubernetes cluster 1.24+
- kubectl configured to access your cluster
- Helm 3.8+ (optional, for monitoring stack)
- Docker for building images

#### For Production Deployment
- Load balancer (nginx, HAProxy, or cloud LB)
- SSL/TLS certificates
- Monitoring infrastructure (Prometheus, Grafana)
- Backup solution for databases

## Quick Start with Docker Compose

The fastest way to get the Monitor System running for development or testing.

### 1. Clone and Setup

```bash
git clone <repository-url>
cd platform-monitor

# Create required directories
mkdir -p logs data/mysql data/redis data/influxdb data/rabbitmq
```

### 2. Configure Environment

```bash
# Copy and edit environment file
cp .env.example .env

# Edit configuration (see Configuration section)
nano .env
```

### 3. Deploy Services

```bash
# Start all services
docker-compose up -d

# Check service status
docker-compose ps

# View logs
docker-compose logs -f
```

### 4. Verify Deployment

```bash
# Check service health
curl http://localhost:8080/actuator/health

# Access web dashboard
open http://localhost:3000
```

### 5. Initial Setup

1. **Access Grafana**: http://localhost:3000 (admin/admin123)
2. **Access InfluxDB**: http://localhost:8086 (admin/monitor123)
3. **Access RabbitMQ Management**: http://localhost:15672 (monitor/monitor123)

## Kubernetes Deployment

### 1. Prepare Kubernetes Environment

```bash
# Verify cluster access
kubectl cluster-info

# Create namespace
kubectl create namespace monitor-system

# Set default namespace (optional)
kubectl config set-context --current --namespace=monitor-system
```

### 2. Configure Secrets and ConfigMaps

```bash
cd platform-monitor/k8s

# Review and update configuration
nano configmap.yaml

# Update secrets (base64 encode your passwords)
echo -n "your-password" | base64
nano configmap.yaml  # Update the base64 encoded values
```

### 3. Deploy Infrastructure Services

```bash
# Deploy infrastructure components
kubectl apply -f namespace.yaml
kubectl apply -f configmap.yaml
kubectl apply -f rbac.yaml

# Deploy databases and message queue
kubectl apply -f infrastructure/mysql.yaml
kubectl apply -f infrastructure/redis.yaml
kubectl apply -f infrastructure/influxdb.yaml
kubectl apply -f infrastructure/rabbitmq.yaml

# Wait for infrastructure to be ready
kubectl wait --for=condition=ready pod -l app=mysql --timeout=300s
kubectl wait --for=condition=ready pod -l app=redis --timeout=300s
kubectl wait --for=condition=ready pod -l app=influxdb --timeout=300s
kubectl wait --for=condition=ready pod -l app=rabbitmq --timeout=300s
```

### 4. Build and Deploy Application Services

```bash
# Build Docker images (if not using pre-built images)
./build-images.sh

# Deploy application services
kubectl apply -f services/auth-service.yaml
kubectl apply -f services/data-service.yaml
kubectl apply -f services/notification-service.yaml
kubectl apply -f services/websocket-service.yaml
kubectl apply -f services/api-gateway.yaml
kubectl apply -f services/web-dashboard.yaml

# Wait for services to be ready
kubectl wait --for=condition=ready pod -l app=monitor-auth-service --timeout=300s
kubectl wait --for=condition=ready pod -l app=monitor-data-service --timeout=300s
kubectl wait --for=condition=ready pod -l app=monitor-notification-service --timeout=300s
kubectl wait --for=condition=ready pod -l app=monitor-websocket-service --timeout=300s
kubectl wait --for=condition=ready pod -l app=monitor-api-gateway --timeout=300s
kubectl wait --for=condition=ready pod -l app=monitor-web-dashboard --timeout=300s
```

### 5. Deploy Service Mesh (Optional)

If using Istio:

```bash
# Enable Istio injection
kubectl label namespace monitor-system istio-injection=enabled

# Deploy Istio configuration
kubectl apply -f istio/gateway.yaml
kubectl apply -f istio/security.yaml

# Restart pods to inject sidecars
kubectl rollout restart deployment -n monitor-system
```

### 6. Deploy Monitoring Stack

```bash
# Deploy Prometheus and Grafana
kubectl apply -f monitoring/prometheus.yaml
kubectl apply -f monitoring/grafana.yaml
kubectl apply -f monitoring/jaeger.yaml
kubectl apply -f monitoring/logging.yaml

# Apply service monitors
kubectl apply -f monitoring/service-monitors.yaml
kubectl apply -f monitoring/metrics-config.yaml
```

### 7. Automated Deployment Script

For convenience, use the provided deployment script:

```bash
# Full deployment
./k8s/deploy.sh

# Deploy specific components
./k8s/deploy.sh infrastructure
./k8s/deploy.sh services
./k8s/deploy.sh monitoring

# Clean up
./k8s/deploy.sh clean
```

## Production Deployment

### 1. Infrastructure Planning

#### High Availability Setup
- **Database**: MySQL cluster with read replicas
- **Cache**: Redis Cluster with sentinel
- **Message Queue**: RabbitMQ cluster
- **Time Series DB**: InfluxDB cluster
- **Load Balancer**: Multiple instances with health checks

#### Security Considerations
- **Network**: Private subnets, security groups, firewalls
- **Encryption**: TLS everywhere, encrypted storage
- **Authentication**: Strong passwords, certificate rotation
- **Monitoring**: Security event logging, intrusion detection

### 2. Database Setup

#### MySQL Configuration
```sql
-- Create database and user
CREATE DATABASE monitor_system CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'monitor'@'%' IDENTIFIED BY 'strong-password';
GRANT ALL PRIVILEGES ON monitor_system.* TO 'monitor'@'%';
FLUSH PRIVILEGES;

-- Import schema
SOURCE database.sql;
```

#### InfluxDB Setup
```bash
# Initialize InfluxDB
influx setup \
  --username admin \
  --password strong-password \
  --org monitor-org \
  --bucket monitor-metrics \
  --retention 30d \
  --force

# Create additional buckets for different retention periods
influx bucket create -n monitor-metrics-1h -r 7d -o monitor-org
influx bucket create -n monitor-metrics-1d -r 90d -o monitor-org
```

### 3. SSL/TLS Configuration

#### Generate Certificates
```bash
# Using Let's Encrypt
certbot certonly --standalone -d monitor.yourdomain.com

# Or use your own CA
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout monitor.key -out monitor.crt \
  -subj "/C=US/ST=State/L=City/O=Organization/CN=monitor.yourdomain.com"
```

#### Configure TLS in Kubernetes
```bash
# Create TLS secret
kubectl create secret tls monitor-tls-secret \
  --cert=monitor.crt \
  --key=monitor.key \
  -n monitor-system
```

### 4. Load Balancer Configuration

#### Nginx Configuration
```nginx
upstream monitor_backend {
    server monitor-api-gateway-1:8080 max_fails=3 fail_timeout=30s;
    server monitor-api-gateway-2:8080 max_fails=3 fail_timeout=30s;
}

server {
    listen 443 ssl http2;
    server_name monitor.yourdomain.com;
    
    ssl_certificate /etc/ssl/certs/monitor.crt;
    ssl_certificate_key /etc/ssl/private/monitor.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-RSA-AES256-GCM-SHA512:DHE-RSA-AES256-GCM-SHA512;
    
    location / {
        proxy_pass http://monitor_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # WebSocket support
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
    
    # Health check endpoint
    location /health {
        access_log off;
        return 200 "healthy\n";
        add_header Content-Type text/plain;
    }
}
```

### 5. Backup and Disaster Recovery

#### Database Backup
```bash
#!/bin/bash
# MySQL backup script
BACKUP_DIR="/backup/mysql"
DATE=$(date +%Y%m%d_%H%M%S)

mysqldump -h mysql-host -u monitor -p monitor_system > \
  $BACKUP_DIR/monitor_system_$DATE.sql

# Compress and upload to S3
gzip $BACKUP_DIR/monitor_system_$DATE.sql
aws s3 cp $BACKUP_DIR/monitor_system_$DATE.sql.gz \
  s3://your-backup-bucket/mysql/
```

#### InfluxDB Backup
```bash
#!/bin/bash
# InfluxDB backup script
BACKUP_DIR="/backup/influxdb"
DATE=$(date +%Y%m%d_%H%M%S)

influx backup $BACKUP_DIR/influxdb_$DATE \
  --host http://influxdb-host:8086 \
  --token your-token

# Upload to S3
tar -czf $BACKUP_DIR/influxdb_$DATE.tar.gz $BACKUP_DIR/influxdb_$DATE
aws s3 cp $BACKUP_DIR/influxdb_$DATE.tar.gz \
  s3://your-backup-bucket/influxdb/
```

## Configuration

### Environment Variables

#### Core Application Settings
```bash
# Database Configuration
MYSQL_HOST=mysql-service
MYSQL_PORT=3306
MYSQL_DATABASE=monitor_system
MYSQL_USERNAME=monitor
MYSQL_PASSWORD=strong-password

# Redis Configuration
REDIS_HOST=redis-service
REDIS_PORT=6379
REDIS_PASSWORD=redis-password

# InfluxDB Configuration
INFLUXDB_URL=http://influxdb-service:8086
INFLUXDB_TOKEN=your-influxdb-token
INFLUXDB_ORG=monitor-org
INFLUXDB_BUCKET=monitor-metrics

# Security Configuration
JWT_SECRET=your-jwt-secret-key
CERTIFICATE_VALIDATION_ENABLED=true
CORS_ALLOWED_ORIGINS=https://monitor.yourdomain.com

# Performance Configuration
CACHE_TTL_SECONDS=300
QUERY_TIMEOUT_SECONDS=30
CONNECTION_POOL_MAX_SIZE=20
```

#### Service-Specific Settings
```bash
# API Gateway
GATEWAY_RATE_LIMIT_ENABLED=true
GATEWAY_CIRCUIT_BREAKER_ENABLED=true
GATEWAY_TIMEOUT_SECONDS=30

# WebSocket Service
WEBSOCKET_MAX_CONNECTIONS=1000
WEBSOCKET_HEARTBEAT_INTERVAL=30

# Data Service
DATA_RETENTION_DAYS=90
AGGREGATION_BATCH_SIZE=1000
METRICS_COLLECTION_INTERVAL=60

# Notification Service
NOTIFICATION_EMAIL_ENABLED=true
NOTIFICATION_SMS_ENABLED=false
ALERT_COOLDOWN_MINUTES=15
```

### Application Configuration Files

#### application-k8s.yml
```yaml
spring:
  profiles:
    active: k8s
  
  datasource:
    url: jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}?useSSL=true&requireSSL=true&verifyServerCertificate=false
    username: ${MYSQL_USERNAME}
    password: ${MYSQL_PASSWORD}
    hikari:
      maximum-pool-size: ${CONNECTION_POOL_MAX_SIZE:20}
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
  
  redis:
    host: ${REDIS_HOST}
    port: ${REDIS_PORT}
    password: ${REDIS_PASSWORD}
    lettuce:
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 5
  
  rabbitmq:
    host: ${RABBITMQ_HOST}
    port: ${RABBITMQ_PORT}
    username: ${RABBITMQ_USERNAME}
    password: ${RABBITMQ_PASSWORD}

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true

logging:
  level:
    cn.flying.monitor: INFO
    org.springframework.security: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level [%X{traceId},%X{spanId}] %logger{36} - %msg%n"
```

## Monitoring and Observability

### Metrics Collection

The system exposes metrics in Prometheus format at `/actuator/prometheus` endpoint.

#### Key Metrics to Monitor
- **Application Metrics**: Request rate, response time, error rate
- **JVM Metrics**: Memory usage, GC performance, thread count
- **Database Metrics**: Connection pool usage, query performance
- **Cache Metrics**: Hit rate, eviction rate, memory usage
- **Custom Metrics**: Client connections, certificate validations

### Health Checks

#### Kubernetes Health Probes
```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 120
  periodSeconds: 30
  timeoutSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
```

### Distributed Tracing

The system uses Spring Cloud Sleuth with Jaeger for distributed tracing.

#### Jaeger Configuration
```yaml
spring:
  sleuth:
    jaeger:
      http-sender:
        url: http://jaeger-collector:14268/api/traces
    sampler:
      probability: 0.1  # Sample 10% of traces in production
```

### Log Aggregation

#### Structured Logging
```yaml
logging:
  pattern:
    console: '{"timestamp":"%d{yyyy-MM-dd HH:mm:ss}","level":"%level","thread":"%thread","logger":"%logger{36}","traceId":"%X{traceId}","spanId":"%X{spanId}","message":"%msg"}%n'
```

#### Log Shipping to ELK Stack
```yaml
# Filebeat configuration
filebeat.inputs:
- type: container
  paths:
    - '/var/log/containers/*monitor*.log'
  processors:
  - add_kubernetes_metadata:
      host: ${NODE_NAME}
      matchers:
      - logs_path:
          logs_path: "/var/log/containers/"

output.elasticsearch:
  hosts: ["elasticsearch:9200"]
  index: "monitor-logs-%{+yyyy.MM.dd}"
```

## Troubleshooting

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for detailed troubleshooting guide.

### Quick Diagnostics

#### Check Service Status
```bash
# Docker Compose
docker-compose ps
docker-compose logs service-name

# Kubernetes
kubectl get pods -n monitor-system
kubectl logs -f deployment/monitor-api-gateway -n monitor-system
kubectl describe pod pod-name -n monitor-system
```

#### Common Issues
1. **Service won't start**: Check logs for configuration errors
2. **Database connection failed**: Verify credentials and network connectivity
3. **High memory usage**: Check for memory leaks, adjust JVM settings
4. **Slow queries**: Review database indexes, check InfluxDB performance

### Performance Tuning

#### JVM Tuning
```bash
JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxGCPauseMillis=200"
```

#### Database Optimization
```sql
-- MySQL optimization
SET GLOBAL innodb_buffer_pool_size = 2147483648;  -- 2GB
SET GLOBAL query_cache_size = 268435456;          -- 256MB
SET GLOBAL max_connections = 200;

-- Create indexes for better performance
CREATE INDEX idx_client_timestamp ON metrics_data(client_id, timestamp);
CREATE INDEX idx_metric_name ON metrics_data(metric_name);
```

## Security Considerations

### Network Security
- Use private networks for internal communication
- Implement proper firewall rules
- Enable TLS for all external communications
- Use service mesh for internal TLS

### Authentication and Authorization
- Rotate certificates regularly
- Use strong passwords and secrets
- Implement proper RBAC in Kubernetes
- Monitor authentication failures

### Data Protection
- Encrypt sensitive data at rest
- Use encrypted communication channels
- Implement proper backup encryption
- Regular security audits

## Scaling Guidelines

### Horizontal Scaling
- API Gateway: Scale based on request rate
- Data Service: Scale based on write throughput
- WebSocket Service: Scale based on concurrent connections
- Auth Service: Scale based on authentication requests

### Vertical Scaling
- Increase memory for data-intensive services
- Increase CPU for compute-intensive operations
- Adjust JVM heap sizes accordingly

### Database Scaling
- Use read replicas for MySQL
- Implement Redis clustering
- Use InfluxDB clustering for high write loads

## Maintenance

### Regular Tasks
- Monitor disk usage and clean old logs
- Update security patches
- Rotate certificates and secrets
- Review and optimize database performance
- Update monitoring dashboards

### Backup Verification
- Test backup restoration procedures
- Verify backup integrity
- Document recovery procedures
- Practice disaster recovery scenarios

---

For additional help, see:
- [Configuration Guide](CONFIGURATION.md)
- [Troubleshooting Guide](TROUBLESHOOTING.md)
- [API Documentation](API.md)
- [Certificate Management Guide](CERTIFICATE_MANAGEMENT.md)