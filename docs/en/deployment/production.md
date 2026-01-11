# Production Deployment

Best practices for deploying RecordPlatform in production.

## Pre-deployment Checklist

- [ ] All environment variables configured
- [ ] `JWT_KEY` has high entropy (≥32 chars)
- [ ] SSL certificates installed
- [ ] Database backups configured
- [ ] Monitoring and alerting set up
- [ ] Log aggregation configured

## Using Deployment Scripts

RecordPlatform provides ready-to-use scripts in the `scripts/` directory.

### Available Scripts

| Script | Purpose |
|--------|---------|
| `start.sh` | Unified service management (start/stop/restart/status) |

### Start All Services (Production)

```bash
./scripts/start.sh start all --skywalking --profile=prod
```

This starts services in the correct order with SkyWalking agent attached.

### Start Individual Service

```bash
./scripts/start.sh start <service> --skywalking --profile=prod

# Examples
./scripts/start.sh start storage --skywalking --profile=prod
./scripts/start.sh start fisco --skywalking --profile=prod
./scripts/start.sh start backend --skywalking --profile=prod
```

### Stop All Services

```bash
./scripts/start.sh stop all
```

## High Availability Setup

### Recommended Architecture

```mermaid
flowchart TB
    LB["Load Balancer<br/>(nginx/HAProxy)"]

    LB --> B1["backend-1:8000"]
    LB --> B2["backend-2:8000"]
    LB --> B3["backend-3:8000"]

    B1 --> Nacos["Nacos HA<br/>(3 nodes)"]
    B2 --> Nacos
    B3 --> Nacos
```

### Component Recommendations

| Component | Minimum HA | Recommended |
|-----------|------------|-------------|
| backend | 2 instances | 3+ instances |
| storage | 2 instances | 3+ instances |
| fisco | 1 instance | 2 instances |
| MySQL | 1 primary + 1 replica | 1 primary + 2 replicas + MHA |
| Redis | Sentinel (3 nodes) | Cluster (6 nodes) |
| S3 Storage | 2 nodes (A/B domains) | 3+ nodes (A/B/STANDBY) |
| Nacos | 3 nodes | 3 nodes |
| RabbitMQ | 3 nodes (cluster) | 3 nodes (mirrored) |

### Storage Fault Domains

For production, configure at least 3 storage nodes across 2 active domains + standby:

```yaml
storage:
  # Active domains (in priority order)
  active-domains:
    - A
    - B
  standby-domain: STANDBY

  # Replication strategy
  replication:
    factor: 2              # Number of replicas (default = active domain count)
    quorum: auto           # Quorum policy: auto|majority|all|number

  # Degraded write (allow partial writes when a domain fails)
  degraded-write:
    enabled: true
    min-replicas: 1        # Minimum replicas in degraded mode
    track-for-sync: true   # Track for later sync when domain recovers

  # Storage nodes
  nodes:
    - name: s3-a1
      endpoint: http://s3-node-a1:9000
      accessKey: ${S3_ACCESS_KEY}
      secretKey: ${S3_SECRET_KEY}
      faultDomain: A
      weight: 100
      enabled: true
    - name: s3-b1
      endpoint: http://s3-node-b1:9000
      accessKey: ${S3_ACCESS_KEY}
      secretKey: ${S3_SECRET_KEY}
      faultDomain: B
      weight: 100
      enabled: true
    - name: s3-standby
      endpoint: http://s3-node-standby:9000
      accessKey: ${S3_ACCESS_KEY}
      secretKey: ${S3_SECRET_KEY}
      faultDomain: STANDBY
      weight: 100
      enabled: true

  # Domain configuration (optional, uses defaults if omitted)
  domains:
    - name: A
      minNodes: 1          # Min healthy nodes before standby promotion
      replicaCount: 1
      acceptsWrites: true
    - name: B
      minNodes: 1
      replicaCount: 1
      acceptsWrites: true
    - name: STANDBY
      minNodes: 0
      replicaCount: 0
      acceptsWrites: false
```

> For complete configuration options, see [Nacos Config Template](../../nacos-config-template.yaml)

## SSL/TLS Configuration

### Generate Self-Signed Certificate (Testing)

```bash
keytool -genkeypair -alias recordplatform \
  -keyalg RSA -keysize 2048 \
  -storetype PKCS12 \
  -keystore keystore.p12 \
  -validity 365
```

### Configure SSL

```yaml
server:
  port: 443
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-password: ${SERVER_SSL_KEY_STORE_PASSWORD}
    key-store-type: PKCS12

security:
  require-ssl: true
  http-redirect-port: 80
```

## JVM Tuning

### Recommended JVM Options

```bash
JAVA_OPTS="-Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/recordplatform/ \
  -Djava.security.egd=file:/dev/./urandom"
```

### Service-Specific Recommendations

| Service | Heap Min | Heap Max | Notes |
|---------|----------|----------|-------|
| backend | 2GB | 4GB | Main workload |
| storage | 1GB | 2GB | I/O bound |
| fisco | 512MB | 1GB | Lightweight |

## Logging

### Log Locations

```
/var/log/recordplatform/
├── backend/
│   ├── app.log
│   └── error.log
├── storage/
│   └── app.log
└── fisco/
    └── app.log
```

### Log Rotation

Configure logrotate for production:

```
/var/log/recordplatform/*/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    create 0640 app app
}
```

## Backup Strategy

### Database Backups

```bash
# Daily full backup
mysqldump -h <mysql-host> -u <mysql-user> -p \
  --single-transaction --routines --triggers \
  RecordPlatform > backup_$(date +%Y%m%d).sql

# Compress
gzip backup_$(date +%Y%m%d).sql
```

### S3 Data Sync

Enable cross-region replication or periodic sync for S3 data.
