# Configuration

This guide covers environment variables and configuration options for RecordPlatform.

## Environment Variables

Copy the example file and customize:

```bash
cp .env.example .env
vim .env
```

### Core Configuration

| Category | Variable | Description | Default |
|----------|----------|-------------|---------|
| **Database** | `DB_HOST` | MySQL host | localhost |
| | `DB_PORT` | MySQL port | 3306 |
| | `DB_NAME` | Database name | RecordPlatform |
| | `DB_USERNAME` | Database user | root |
| | `DB_PASSWORD` | Database password | - |
| **Redis** | `REDIS_HOST` | Redis host | localhost |
| | `REDIS_PORT` | Redis port | 6379 |
| | `REDIS_PASSWORD` | Redis password | - |
| **Nacos** | `NACOS_HOST` | Nacos server | localhost |
| | `NACOS_USERNAME` | Nacos username | nacos |
| | `NACOS_PASSWORD` | Nacos password | nacos |
| **RabbitMQ** | `RABBITMQ_ADDRESSES` | RabbitMQ addresses | localhost:5672 |
| | `RABBITMQ_USERNAME` | RabbitMQ user | guest |
| | `RABBITMQ_PASSWORD` | RabbitMQ password | guest |

### Security Configuration

| Variable | Description | Requirement |
|----------|-------------|-------------|
| `JWT_KEY` | JWT signing key + ID encryption derivation | Min 32 characters, high entropy |

> **Note**: `ID_SECURITY_KEY` is deprecated since v2.0. ID encryption keys are now derived from `JWT_KEY`.

### Storage Configuration

S3-compatible storage is configured via Nacos. Basic environment variables:

| Variable | Description |
|----------|-------------|
| `S3_ENDPOINT` | S3 endpoint URL |
| `S3_ACCESS_KEY` | Access key |
| `S3_SECRET_KEY` | Secret key |

Fault domain configuration is managed through Nacos and supports runtime refresh.

### Blockchain Configuration

| Variable | Description | Example |
|----------|-------------|---------|
| `BLOCKCHAIN_ACTIVE` | Active chain type | `local-fisco`, `bsn-fisco`, `bsn-besu` |
| `FISCO_PEER_ADDRESS` | FISCO node address | `127.0.0.1:20200` |
| `FISCO_STORAGE_CONTRACT` | Storage contract address | `0x...` |
| `FISCO_SHARING_CONTRACT` | Sharing contract address | `0x...` |

### SSL Configuration (Production)

| Variable | Description |
|----------|-------------|
| `SERVER_SSL_KEY_STORE` | Keystore path |
| `SERVER_SSL_KEY_STORE_PASSWORD` | Keystore password |
| `SECURITY_REQUIRE_SSL` | Force HTTPS (true/false) |
| `SECURITY_HTTP_REDIRECT_PORT` | HTTP redirect port |

### Service Port Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | Backend REST API port | 8000 |
| `DUBBO_FISCO_PORT` | FISCO Dubbo service port | 8091 |
| `DUBBO_STORAGE_PORT` | Storage Dubbo service port | 8092 |
| `DUBBO_HOST` | Service registration IP (for Docker) | - |
| `QOS_BACKEND_PORT` | Backend QoS management port | 22330 |
| `QOS_FISCO_PORT` | FISCO QoS management port | 22331 |
| `QOS_STORAGE_PORT` | Storage QoS management port | 22332 |

> **Note**: `DUBBO_HOST` is critical in Docker environments to ensure services register with accessible IPs rather than Docker bridge network IPs.

### Logging Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `LOG_LEVEL` | Application log level | INFO |
| `LOG_PATH` | Log file output directory | /var/log/record-platform |

### CORS Configuration

| Variable | Description | Example |
|----------|-------------|---------|
| `CORS_ALLOWED_ORIGINS` | Allowed frontend origins (comma-separated) | `http://localhost:3000,http://localhost:5173` |

### API Documentation Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `KNIFE4J_USERNAME` | Knife4j/Swagger UI username | admin |
| `KNIFE4J_PASSWORD` | Knife4j/Swagger UI password | - |

### APM Configuration (Optional)

SkyWalking integration for distributed tracing:

| Variable | Description | Default |
|----------|-------------|---------|
| `SW_AGENT_COLLECTOR_BACKEND_SERVICES` | SkyWalking OAP collector | localhost:11800 |
| `SW_AGENT_NAME` | Service name in SkyWalking | record-platform |
| `SW_JDBC_TRACE_SQL_PARAMETERS` | Trace SQL parameters | true |

## Profile Configuration

Available profiles: `local`, `dev`, `prod`

```bash
# Run with specific profile
java -jar app.jar --spring.profiles.active=prod
```

### Profile Differences

| Feature | local | dev | prod |
|---------|-------|-----|------|
| Swagger UI | Enabled | Enabled | Disabled |
| Druid Monitor | Enabled | Enabled | Disabled |
| Debug Logging | Enabled | Partial | Disabled |
| SSL Required | No | No | Yes |

## Nacos Configuration

Dynamic configurations are managed in Nacos. Template: `docs/nacos-config-template.yaml`

### Key Nacos Configs

```yaml
# Storage nodes with fault domains
storage:
  # Required: List of active domains
  active-domains:
    - domain-a
    - domain-b

  # Optional: External endpoint (v3.2.0+)
  # Used for generating presigned URLs, solves cross-network (e.g., VPN) access issues
  # Format: http://host:port (no trailing slash)
  external-endpoint: http://10.1.0.2:9000

  # Optional: Standby domain (for failover)
  standby-domain: standby

  # Replication configuration (v3.1.0+)
  replication:
    factor: 2                     # Replica count, default = active domain count
    quorum: auto                  # Quorum strategy: auto|majority|all|number

  # Degraded write configuration (v3.1.0+)
  degraded-write:
    enabled: true                 # Allow degraded writes
    min-replicas: 1               # Minimum replicas in degraded mode
    track-for-sync: true          # Track degraded writes for later sync

  virtualNodesPerNode: 150

  # Optional: Domain detailed configuration
  domains:
    - name: domain-a
      minNodes: 1
      acceptsWrites: true
    - name: domain-b
      minNodes: 1
      acceptsWrites: true
    - name: standby
      minNodes: 0
      acceptsWrites: false

  nodes:
    - name: node-a1
      endpoint: http://minio-a:9000
      faultDomain: domain-a
      weight: 100
    - name: node-b1
      endpoint: http://minio-b:9000
      faultDomain: domain-b
      weight: 100

  # Replica consistency repair configuration
  consistency:
    repair:
      enabled: true               # Enable scheduled repair
      cron: "0 */15 * * * ?"      # Every 15 minutes
      batch-size: 100
      lock-timeout-seconds: 600

  # Data rebalancing configuration
  rebalance:
    enabled: true                 # Enable automatic rebalancing
    rate-limit-per-second: 10     # Max objects copied per second
    cleanup-source: false         # Delete source data after rebalance
```

> **Note**: `active-domains` is required and validated at startup. For single-domain development mode, configure only one domain.

## Frontend Configuration

Frontend environment variables (`platform-frontend/.env`):

| Variable | Description |
|----------|-------------|
| `PUBLIC_API_BASE_URL` | Backend API URL |
| `PUBLIC_ENV` | Environment name |
| `PUBLIC_TENANT_ID` | Default tenant ID |

