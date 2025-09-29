# API Gateway 部署指南

## 目录

- [部署架构](#部署架构)
- [环境准备](#环境准备)
- [部署方式](#部署方式)
- [生产环境部署](#生产环境部署)
- [高可用部署](#高可用部署)
- [监控与运维](#监控与运维)
- [故障处理](#故障处理)
- [备份与恢复](#备份与恢复)

## 部署架构

### 单机部署架构

```
┌─────────────────────────────────┐
│         Nginx (80/443)          │
└─────────────┬───────────────────┘
              │
┌─────────────▼───────────────────┐
│    Platform-Identity (8888)     │
└─────────────┬───────────────────┘
              │
      ┌───────┴────────┐
      │                │
┌─────▼─────┐  ┌──────▼──────┐
│   MySQL   │  │    Redis    │
└───────────┘  └─────────────┘
```

### 集群部署架构

```
                ┌──────────────────────┐
                │   Load Balancer      │
                │   (Nginx/HAProxy)    │
                └──────────┬───────────┘
                          │
        ┌─────────────────┼─────────────────┐
        │                 │                 │
┌───────▼──────┐  ┌───────▼──────┐  ┌──────▼───────┐
│  Identity-1  │  │  Identity-2  │  │  Identity-3  │
│    (8888)    │  │    (8888)    │  │    (8888)    │
└───────┬──────┘  └───────┬──────┘  └──────┬───────┘
        │                 │                 │
        └─────────────────┼─────────────────┘
                         │
          ┌──────────────┼──────────────┐
          │                             │
    ┌─────▼──────┐              ┌──────▼──────┐
    │   MySQL    │              │  Redis      │
    │  (Master)  │              │  Cluster    │
    └─────┬──────┘              └─────────────┘
          │
    ┌─────▼──────┐
    │   MySQL    │
    │  (Slave)   │
    └────────────┘
```

## 环境准备

### 1. 系统要求

**操作系统**:
- CentOS 7.9+ / Ubuntu 20.04+ / Debian 11+
- 推荐使用 CentOS 7.9 或 Ubuntu 20.04 LTS

**硬件配置**:
| 环境 | CPU | 内存 | 磁盘 | 网络 |
|-----|-----|------|------|------|
| 开发 | 2核 | 4GB | 20GB | 1Mbps |
| 测试 | 4核 | 8GB | 50GB | 10Mbps |
| 生产 | 8核 | 16GB | 200GB | 100Mbps |

### 2. 软件依赖

**JDK 21 安装**:
```bash
# Ubuntu/Debian
sudo apt update
sudo apt install openjdk-21-jdk

# CentOS/RHEL
sudo yum install java-21-openjdk java-21-openjdk-devel

# 验证安装
java -version
```

**MySQL 8.0 安装**:
```bash
# Ubuntu
sudo apt install mysql-server-8.0

# CentOS
sudo yum install mysql-server

# 初始化配置
sudo mysql_secure_installation

# 创建数据库和用户
mysql -u root -p
CREATE DATABASE platform_identity CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'platform'@'%' IDENTIFIED BY 'Strong_Password_123';
GRANT ALL PRIVILEGES ON platform_identity.* TO 'platform'@'%';
FLUSH PRIVILEGES;
```

**Redis 7.0 安装**:
```bash
# Ubuntu
sudo apt install redis-server

# CentOS
sudo yum install redis

# 配置Redis
sudo vim /etc/redis/redis.conf
# 修改以下配置
bind 0.0.0.0
requirepass your_redis_password
maxmemory 2gb
maxmemory-policy allkeys-lru

# 启动Redis
sudo systemctl start redis
sudo systemctl enable redis
```

### 3. 目录结构

```bash
# 创建部署目录
sudo mkdir -p /opt/platform-identity/{app,logs,config,backup}
sudo chown -R deployer:deployer /opt/platform-identity

# 目录说明
/opt/platform-identity/
├── app/        # 应用程序目录
├── logs/       # 日志目录
├── config/     # 配置文件目录
└── backup/     # 备份目录
```

## 部署方式

### 方式一：JAR包部署

#### 1. 构建JAR包

```bash
# 在项目根目录执行
mvn clean package -DskipTests -Pprod

# 生成的JAR包位置
# target/platform-identity-1.0.0.jar
```

#### 2. 上传并运行

```bash
# 上传JAR包
scp target/platform-identity-1.0.0.jar deployer@server:/opt/platform-identity/app/

# 上传配置文件
scp src/main/resources/application-prod.yml deployer@server:/opt/platform-identity/config/

# SSH登录服务器
ssh deployer@server

# 启动应用
cd /opt/platform-identity
nohup java -jar \
    -Xms2g -Xmx4g \
    -XX:+UseG1GC \
    -Dspring.profiles.active=prod \
    -Dspring.config.location=config/application-prod.yml \
    app/platform-identity-1.0.0.jar > logs/startup.log 2>&1 &

# 查看启动日志
tail -f logs/startup.log
```

#### 3. 创建systemd服务

```bash
# 创建服务文件
sudo vim /etc/systemd/system/platform-identity.service

[Unit]
Description=Platform Identity Service
After=network.target

[Service]
Type=simple
User=deployer
ExecStart=/usr/bin/java -jar \
    -Xms2g -Xmx4g \
    -XX:+UseG1GC \
    -Dspring.profiles.active=prod \
    -Dspring.config.location=/opt/platform-identity/config/application-prod.yml \
    /opt/platform-identity/app/platform-identity-1.0.0.jar
Restart=on-failure
RestartSec=10
StandardOutput=append:/opt/platform-identity/logs/app.log
StandardError=append:/opt/platform-identity/logs/error.log

[Install]
WantedBy=multi-user.target

# 重新加载systemd配置
sudo systemctl daemon-reload

# 启动服务
sudo systemctl start platform-identity

# 设置开机自启
sudo systemctl enable platform-identity

# 查看服务状态
sudo systemctl status platform-identity
```

### 方式二：Docker部署

#### 1. 构建Docker镜像

```dockerfile
# Dockerfile
FROM openjdk:17-jdk-slim

# 设置时区
RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

# 创建应用目录
WORKDIR /app

# 复制JAR包
COPY target/platform-identity-1.0.0.jar app.jar

# 复制配置文件
COPY src/main/resources/application-prod.yml config/application-prod.yml

# 暴露端口
EXPOSE 8888

# 启动命令
ENTRYPOINT ["java", "-jar", \
    "-Xms2g", "-Xmx4g", \
    "-XX:+UseG1GC", \
    "-Dspring.profiles.active=prod", \
    "-Dspring.config.location=config/application-prod.yml", \
    "app.jar"]
```

```bash
# 构建镜像
docker build -t platform-identity:1.0.0 .

# 运行容器
docker run -d \
    --name platform-identity \
    -p 8888:8888 \
    -v /opt/platform-identity/logs:/app/logs \
    -v /opt/platform-identity/config:/app/config \
    --restart=always \
    platform-identity:1.0.0

# 查看日志
docker logs -f platform-identity
```

#### 2. Docker Compose部署

```yaml
# docker-compose.yml
version: '3.8'

services:
  platform-identity:
    image: platform-identity:1.0.0
    container_name: platform-identity
    ports:
      - "8888:8888"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - JAVA_OPTS=-Xms2g -Xmx4g -XX:+UseG1GC
    volumes:
      - ./logs:/app/logs
      - ./config:/app/config
    networks:
      - platform-network
    depends_on:
      - mysql
      - redis
    restart: always

  mysql:
    image: mysql:8.0
    container_name: platform-mysql
    environment:
      MYSQL_ROOT_PASSWORD: root_password
      MYSQL_DATABASE: platform_identity
      MYSQL_USER: platform
      MYSQL_PASSWORD: platform_password
    volumes:
      - mysql_data:/var/lib/mysql
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql
    networks:
      - platform-network
    restart: always

  redis:
    image: redis:7-alpine
    container_name: platform-redis
    command: redis-server --requirepass redis_password
    volumes:
      - redis_data:/data
    networks:
      - platform-network
    restart: always

networks:
  platform-network:
    driver: bridge

volumes:
  mysql_data:
  redis_data:
```

```bash
# 启动所有服务
docker-compose up -d

# 查看服务状态
docker-compose ps

# 停止所有服务
docker-compose down
```

### 方式三：Kubernetes部署

#### 1. 创建配置文件

```yaml
# configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: platform-identity-config
  namespace: platform
data:
  application-prod.yml: |
    spring:
      datasource:
        url: jdbc:mysql://mysql-service:3306/platform_identity
        username: platform
        password: platform_password
      data:
        redis:
          host: redis-service
          port: 6379
          password: redis_password
```

#### 2. 创建部署文件

```yaml
# deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: platform-identity
  namespace: platform
spec:
  replicas: 3
  selector:
    matchLabels:
      app: platform-identity
  template:
    metadata:
      labels:
        app: platform-identity
    spec:
      containers:
      - name: platform-identity
        image: platform-identity:1.0.0
        ports:
        - containerPort: 8888
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: JAVA_OPTS
          value: "-Xms1g -Xmx2g -XX:+UseG1GC"
        volumeMounts:
        - name: config
          mountPath: /app/config
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /identity/actuator/health
            port: 8888
          initialDelaySeconds: 60
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /identity/actuator/health
            port: 8888
          initialDelaySeconds: 30
          periodSeconds: 10
      volumes:
      - name: config
        configMap:
          name: platform-identity-config
```

#### 3. 创建服务

```yaml
# service.yaml
apiVersion: v1
kind: Service
metadata:
  name: platform-identity-service
  namespace: platform
spec:
  selector:
    app: platform-identity
  ports:
  - protocol: TCP
    port: 8888
    targetPort: 8888
  type: LoadBalancer
```

#### 4. 部署到Kubernetes

```bash
# 创建命名空间
kubectl create namespace platform

# 应用配置
kubectl apply -f configmap.yaml
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml

# 查看部署状态
kubectl get pods -n platform
kubectl get svc -n platform

# 查看日志
kubectl logs -f deployment/platform-identity -n platform
```

## 生产环境部署

### 1. 前置准备

```bash
# 系统优化
# 修改文件句柄限制
sudo vim /etc/security/limits.conf
* soft nofile 65535
* hard nofile 65535
* soft nproc 32000
* hard nproc 32000

# 修改内核参数
sudo vim /etc/sysctl.conf
net.ipv4.tcp_max_syn_backlog = 65535
net.core.netdev_max_backlog = 32768
net.core.somaxconn = 32768
net.ipv4.tcp_timestamps = 0
net.ipv4.tcp_tw_reuse = 1

# 应用内核参数
sudo sysctl -p
```

### 2. JVM优化

```bash
# 生产环境JVM参数
java -jar \
    -server \
    -Xms4g -Xmx4g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+ParallelRefProcEnabled \
    -XX:+UnlockExperimentalVMOptions \
    -XX:+DisableExplicitGC \
    -XX:+AlwaysPreTouch \
    -XX:G1NewSizePercent=30 \
    -XX:G1MaxNewSizePercent=40 \
    -XX:G1HeapRegionSize=8M \
    -XX:G1ReservePercent=15 \
    -XX:G1HeapWastePercent=5 \
    -XX:G1MixedGCCountTarget=5 \
    -XX:InitiatingHeapOccupancyPercent=15 \
    -XX:G1MixedGCLiveThresholdPercent=85 \
    -XX:G1RSetUpdatingPauseTimePercent=5 \
    -XX:SurvivorRatio=32 \
    -XX:+PerfDisableSharedMem \
    -XX:MaxTenuringThreshold=1 \
    -Dspring.profiles.active=prod \
    platform-identity-1.0.0.jar
```

### 3. Nginx配置

```nginx
# /etc/nginx/conf.d/platform-identity.conf
upstream platform-identity {
    least_conn;
    server 127.0.0.1:8888 max_fails=3 fail_timeout=30s;
    server 127.0.0.1:8889 max_fails=3 fail_timeout=30s;
    server 127.0.0.1:8890 max_fails=3 fail_timeout=30s;
}

server {
    listen 80;
    server_name api.example.com;
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name api.example.com;

    ssl_certificate /etc/nginx/ssl/api.example.com.crt;
    ssl_certificate_key /etc/nginx/ssl/api.example.com.key;

    # SSL优化
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;

    # 日志
    access_log /var/log/nginx/platform-identity-access.log;
    error_log /var/log/nginx/platform-identity-error.log;

    # 压缩
    gzip on;
    gzip_types text/plain application/json application/xml;
    gzip_comp_level 6;

    location /identity/ {
        proxy_pass http://platform-identity;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # 超时设置
        proxy_connect_timeout 30s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;

        # 缓冲区设置
        proxy_buffering on;
        proxy_buffer_size 4k;
        proxy_buffers 8 4k;
        proxy_busy_buffers_size 8k;
    }

    # 健康检查
    location /health {
        proxy_pass http://platform-identity/identity/actuator/health;
        access_log off;
    }
}
```

## 高可用部署

### 1. 数据库高可用（MySQL主从）

```bash
# 主库配置
vim /etc/mysql/mysql.conf.d/mysqld.cnf
[mysqld]
server-id = 1
log_bin = /var/log/mysql/mysql-bin.log
binlog_format = ROW

# 从库配置
[mysqld]
server-id = 2
relay_log = /var/log/mysql/mysql-relay-bin.log
read_only = 1

# 配置主从同步
# 在主库执行
CREATE USER 'replication'@'%' IDENTIFIED BY 'repl_password';
GRANT REPLICATION SLAVE ON *.* TO 'replication'@'%';
SHOW MASTER STATUS;

# 在从库执行
CHANGE MASTER TO
    MASTER_HOST='master_ip',
    MASTER_USER='replication',
    MASTER_PASSWORD='repl_password',
    MASTER_LOG_FILE='mysql-bin.000001',
    MASTER_LOG_POS=154;

START SLAVE;
SHOW SLAVE STATUS\G
```

### 2. Redis高可用（Redis Sentinel）

```bash
# redis-sentinel.conf
port 26379
sentinel monitor mymaster 127.0.0.1 6379 2
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 10000
sentinel parallel-syncs mymaster 1
sentinel auth-pass mymaster redis_password

# 启动Sentinel
redis-sentinel /etc/redis/redis-sentinel.conf
```

### 3. 应用层高可用

```bash
# 使用Keepalived实现VIP
# keepalived.conf
global_defs {
    router_id LVS_MASTER
}

vrrp_instance VI_1 {
    state MASTER
    interface eth0
    virtual_router_id 51
    priority 100
    advert_int 1
    authentication {
        auth_type PASS
        auth_pass 1111
    }
    virtual_ipaddress {
        192.168.1.100/24
    }
}
```

## 监控与运维

### 1. 应用监控

```bash
# Prometheus配置
# prometheus.yml
scrape_configs:
  - job_name: 'platform-identity'
    metrics_path: '/identity/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8888', 'localhost:8889', 'localhost:8890']
```

### 2. 日志监控

```bash
# ELK Stack配置
# filebeat.yml
filebeat.inputs:
- type: log
  paths:
    - /opt/platform-identity/logs/*.log
  multiline.pattern: '^\d{4}-\d{2}-\d{2}'
  multiline.negate: true
  multiline.match: after

output.elasticsearch:
  hosts: ["localhost:9200"]
  index: "platform-identity-%{+yyyy.MM.dd}"
```

### 3. 健康检查

```bash
# 健康检查脚本
#!/bin/bash
# health_check.sh

HEALTH_URL="http://localhost:8888/identity/actuator/health"

response=$(curl -s -o /dev/null -w "%{http_code}" $HEALTH_URL)

if [ $response -eq 200 ]; then
    echo "Service is healthy"
    exit 0
else
    echo "Service is unhealthy"
    # 发送告警
    curl -X POST https://webhook.example.com/alert \
        -H 'Content-Type: application/json' \
        -d '{"message": "Platform Identity service is down!"}'
    exit 1
fi
```

### 4. 性能监控

```bash
# 使用JMX监控
java -jar \
    -Dcom.sun.management.jmxremote \
    -Dcom.sun.management.jmxremote.port=9999 \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.ssl=false \
    platform-identity-1.0.0.jar

# 使用jconsole连接
jconsole localhost:9999
```

## 故障处理

### 1. 应用无法启动

```bash
# 检查端口占用
netstat -tlnp | grep 8888

# 检查日志
tail -f /opt/platform-identity/logs/error.log

# 检查配置文件
cat /opt/platform-identity/config/application-prod.yml

# 检查数据库连接
mysql -h localhost -u platform -p -e "SELECT 1"

# 检查Redis连接
redis-cli -h localhost -a redis_password ping
```

### 2. 内存溢出

```bash
# 生成堆转储文件
jmap -dump:format=b,file=heap.hprof <pid>

# 分析堆转储
jhat -port 7000 heap.hprof

# 添加JVM参数自动生成堆转储
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/opt/platform-identity/logs/
```

### 3. CPU占用过高

```bash
# 找出占用CPU最高的线程
top -H -p <pid>

# 将线程ID转换为16进制
printf "%x\n" <thread_id>

# 打印线程栈
jstack <pid> | grep -A 20 <hex_thread_id>
```

## 备份与恢复

### 1. 数据库备份

```bash
# 创建备份脚本
#!/bin/bash
# backup_mysql.sh

DATE=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="/opt/platform-identity/backup"
DB_NAME="platform_identity"
DB_USER="platform"
DB_PASS="platform_password"

# 创建备份
mysqldump -u $DB_USER -p$DB_PASS --single-transaction \
    --routines --triggers --events \
    $DB_NAME > $BACKUP_DIR/mysql_$DATE.sql

# 压缩备份文件
gzip $BACKUP_DIR/mysql_$DATE.sql

# 删除7天前的备份
find $BACKUP_DIR -name "mysql_*.sql.gz" -mtime +7 -delete

# 添加到crontab
# 0 2 * * * /opt/platform-identity/scripts/backup_mysql.sh
```

### 2. Redis备份

```bash
# 配置RDB持久化
save 900 1
save 300 10
save 60 10000

# 配置AOF持久化
appendonly yes
appendfsync everysec

# 手动备份
redis-cli -a redis_password BGSAVE
cp /var/lib/redis/dump.rdb /opt/platform-identity/backup/redis_$(date +%Y%m%d).rdb
```

### 3. 恢复流程

```bash
# 恢复MySQL
gunzip < mysql_20251011.sql.gz | mysql -u platform -p platform_identity

# 恢复Redis
systemctl stop redis
cp redis_20251011.rdb /var/lib/redis/dump.rdb
systemctl start redis
```

## 性能调优

### 1. 数据库调优

```sql
-- 添加索引
CREATE INDEX idx_app_owner_status ON api_application(owner_id, app_status);
CREATE INDEX idx_key_app_status ON api_key(app_id, key_status);

-- 分析查询性能
EXPLAIN SELECT * FROM api_application WHERE owner_id = ? AND app_status = ?;

-- 优化配置
[mysqld]
innodb_buffer_pool_size = 4G
innodb_log_file_size = 256M
innodb_flush_method = O_DIRECT
innodb_file_per_table = 1
```

### 2. Redis调优

```bash
# redis.conf
maxmemory 4gb
maxmemory-policy allkeys-lru
tcp-keepalive 60
tcp-backlog 511
```

### 3. 应用调优

```yaml
# 连接池优化
spring:
  datasource:
    hikari:
      maximum-pool-size: 30
      minimum-idle: 10
      connection-timeout: 30000

# 线程池优化
server:
  tomcat:
    threads:
      max: 200
      min-spare: 10
    accept-count: 100
    max-connections: 10000
```

## 安全加固

### 1. 防火墙配置

```bash
# 只允许必要端口
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow from 192.168.1.0/24 to any port 8888
sudo ufw enable
```

### 2. SSL/TLS配置

```bash
# 生成证书
certbot certonly --standalone -d api.example.com

# 自动续期
0 0 1 * * certbot renew --quiet
```

### 3. 敏感信息加密

```yaml
# 使用环境变量
spring:
  datasource:
    password: ${DB_PASSWORD}
  redis:
    password: ${REDIS_PASSWORD}

api:
  key:
    aes-secret: ${AES_SECRET}
```

---

*文档版本: v2.0.0*  
*最后更新: 2025-10-15*  
*Java 版本: 21*  
*Spring Boot 版本: 3.2.11*  
*Sa-Token 版本: 1.44.0*  
*服务端口: 8888*