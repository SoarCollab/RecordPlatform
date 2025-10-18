# Monitor System Deployment Guide

This document provides comprehensive instructions for deploying the Monitor System using the CI/CD pipeline and Kubernetes orchestration.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Environment Setup](#environment-setup)
3. [CI/CD Pipeline](#cicd-pipeline)
4. [Manual Deployment](#manual-deployment)
5. [Environment Configuration](#environment-configuration)
6. [Monitoring and Observability](#monitoring-and-observability)
7. [Security Configuration](#security-configuration)
8. [Troubleshooting](#troubleshooting)
9. [Rollback Procedures](#rollback-procedures)

## Prerequisites

### Infrastructure Requirements

- **Kubernetes Cluster**: Version 1.25+ with the following resources:
  - Development: 8 CPU cores, 16GB RAM, 200GB storage
  - Staging: 16 CPU cores, 32GB RAM, 500GB storage
  - Production: 32 CPU cores, 64GB RAM, 2TB storage

- **Container Registry**: GitHub Container Registry (GHCR) or equivalent
- **Service Mesh**: Istio 1.18+ (optional but recommended for production)
- **Storage Classes**: 
  - `standard` for development
  - `fast-ssd` for production workloads

### Required Tools

- `kubectl` v1.25+
- `docker` v20.10+
- `helm` v3.10+ (for Istio installation)
- `kustomize` v4.5+ (built into kubectl)

### Access Requirements

- Kubernetes cluster admin access
- Container registry push/pull permissions
- GitHub repository access with Actions enabled

## Environment Setup

### 1. Kubernetes Cluster Preparation

```bash
# Verify cluster access
kubectl cluster-info

# Create storage classes (if not exists)
kubectl apply -f - <<EOF
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: fast-ssd
provisioner: kubernetes.io/gce-pd
parameters:
  type: pd-ssd
  replication-type: regional-pd
allowVolumeExpansion: true
EOF
```

### 2. Istio Installation (Optional)

```bash
# Install Istio
curl -L https://istio.io/downloadIstio | sh -
cd istio-*
export PATH=$PWD/bin:$PATH

# Install Istio with default configuration
istioctl install --set values.defaultRevision=default

# Enable Istio injection for the namespace
kubectl label namespace monitor-system istio-injection=enabled
```

### 3. Secrets Configuration

Create the required secrets for each environment:

```bash
# Development environment secrets
kubectl create secret generic monitor-secrets-dev \
  --from-literal=MYSQL_PASSWORD=dev123 \
  --from-literal=REDIS_PASSWORD="" \
  --from-literal=INFLUXDB_TOKEN=dev-token-123 \
  --from-literal=RABBITMQ_PASSWORD=dev123 \
  --from-literal=JWT_SECRET=dev-jwt-secret-key \
  -n monitor-system-dev

# Production environment secrets (use strong passwords)
kubectl create secret generic monitor-secrets \
  --from-literal=MYSQL_PASSWORD=$(openssl rand -base64 32) \
  --from-literal=REDIS_PASSWORD=$(openssl rand -base64 32) \
  --from-literal=INFLUXDB_TOKEN=$(openssl rand -base64 32) \
  --from-literal=RABBITMQ_PASSWORD=$(openssl rand -base64 32) \
  --from-literal=JWT_SECRET=$(openssl rand -base64 64) \
  -n monitor-system
```

## CI/CD Pipeline

### Pipeline Overview

The CI/CD pipeline consists of the following stages:

1. **Security Scan**: OWASP dependency check, Trivy, CodeQL
2. **Build and Test**: Unit tests, integration tests, code coverage
3. **Build Images**: Multi-arch Docker images with vulnerability scanning
4. **Performance Test**: JMeter load testing (main branch only)
5. **Deploy**: Environment-specific deployments with smoke tests

### Pipeline Configuration

#### Required GitHub Secrets

```bash
# Kubernetes configurations (base64 encoded kubeconfig files)
KUBE_CONFIG_DEV
KUBE_CONFIG_STAGING  
KUBE_CONFIG_PROD

# Notification webhooks
SLACK_WEBHOOK
EMERGENCY_SLACK_WEBHOOK

# Container registry (automatically provided by GitHub)
GITHUB_TOKEN
```

#### Environment Variables

Set these in your GitHub repository settings:

- `DOCKER_REGISTRY`: Container registry URL (default: ghcr.io)
- `IMAGE_NAME_PREFIX`: Image name prefix (default: your-org/monitor)

### Triggering Deployments

#### Automatic Deployments

- **Development**: Triggered on push to `develop` branch
- **Staging**: Triggered on push to `main` branch
- **Production**: Manual approval required via GitHub Actions

#### Manual Deployments

```bash
# Trigger manual deployment via GitHub Actions
# Go to Actions tab -> CI/CD Pipeline -> Run workflow
# Select environment and options
```

## Manual Deployment

### Using the Deployment Script

```bash
# Clone the repository
git clone <repository-url>
cd platform-monitor/k8s

# Set environment variables
export DOCKER_REGISTRY=ghcr.io/your-org/monitor
export IMAGE_TAG=latest
export ENVIRONMENT=dev

# Full deployment
./deploy.sh deploy

# Deploy specific components
./deploy.sh infrastructure  # Infrastructure only
./deploy.sh services       # Application services only
./deploy.sh istio          # Istio configuration only
./deploy.sh monitoring     # Monitoring stack only
```

### Using Kustomize

```bash
# Development deployment
kubectl apply -k environments/dev/

# Production deployment
kubectl apply -k environments/prod/

# Verify deployment
kubectl get pods -n monitor-system
kubectl get services -n monitor-system
kubectl get ingress -n monitor-system
```

## Environment Configuration

### Development Environment

- **Namespace**: `monitor-system-dev`
- **Replicas**: 1 per service
- **Resources**: Minimal (256Mi RAM, 100m CPU per service)
- **Storage**: 10Gi per component
- **Logging**: DEBUG level
- **Monitoring**: Full tracing enabled

### Staging Environment

- **Namespace**: `monitor-system-staging`
- **Replicas**: 2 per service
- **Resources**: Medium (512Mi-1Gi RAM, 250m-500m CPU)
- **Storage**: 50Gi per component
- **Logging**: INFO level
- **Monitoring**: Sampling enabled (50%)

### Production Environment

- **Namespace**: `monitor-system`
- **Replicas**: 3-5 per service with HPA
- **Resources**: High (1-4Gi RAM, 500m-2000m CPU)
- **Storage**: 100-500Gi per component
- **Logging**: WARN level
- **Monitoring**: Sampling enabled (10%)
- **Security**: Enhanced security policies, network policies

## Monitoring and Observability

### Prometheus Metrics

Access Prometheus dashboard:

```bash
kubectl port-forward -n monitor-system svc/prometheus-service 9090:9090
# Open http://localhost:9090
```

### Application Metrics

Each service exposes metrics at `/actuator/prometheus`:

- **API Gateway**: Request rates, response times, circuit breaker status
- **Auth Service**: Authentication attempts, token generation rates
- **Data Service**: Data ingestion rates, database performance
- **Notification Service**: Alert processing times, notification delivery rates
- **WebSocket Service**: Connection counts, message throughput

### Health Checks

```bash
# Check service health
kubectl get pods -n monitor-system
kubectl describe pod <pod-name> -n monitor-system

# Application health endpoints
kubectl port-forward -n monitor-system svc/monitor-api-gateway-service 8080:8080
curl http://localhost:8080/actuator/health
```

### Log Aggregation

```bash
# View service logs
kubectl logs -f deployment/monitor-api-gateway -n monitor-system
kubectl logs -f deployment/monitor-data-service -n monitor-system

# View logs from all pods
kubectl logs -f -l app=monitor-data-service -n monitor-system
```

## Security Configuration

### TLS/SSL Configuration

```bash
# Create TLS secret for Istio Gateway
kubectl create secret tls monitor-tls-secret \
  --cert=path/to/tls.crt \
  --key=path/to/tls.key \
  -n istio-system
```

### Network Policies

Network policies are automatically applied in production to restrict traffic:

- Ingress: Only from Istio Gateway and within namespace
- Egress: DNS, within namespace, and HTTPS to external services

### RBAC Configuration

Service accounts and RBAC policies are automatically created:

- Minimal permissions per service
- No cluster-wide access
- Read-only access to ConfigMaps and Secrets

### Security Scanning

Automated security scanning includes:

- **OWASP Dependency Check**: Identifies vulnerable dependencies
- **Trivy**: Container image vulnerability scanning
- **CodeQL**: Static code analysis
- **Semgrep**: SAST security scanning

## Troubleshooting

### Common Issues

#### Pod Startup Issues

```bash
# Check pod status
kubectl get pods -n monitor-system

# Describe problematic pod
kubectl describe pod <pod-name> -n monitor-system

# Check logs
kubectl logs <pod-name> -n monitor-system --previous
```

#### Service Discovery Issues

```bash
# Check service endpoints
kubectl get endpoints -n monitor-system

# Test service connectivity
kubectl run test-pod --image=curlimages/curl --rm -i --restart=Never -- \
  curl -v http://monitor-api-gateway-service.monitor-system.svc.cluster.local:8080/actuator/health
```

#### Database Connection Issues

```bash
# Check database pod
kubectl logs deployment/mysql -n monitor-system

# Test database connectivity
kubectl run mysql-client --image=mysql:8.0 --rm -i --restart=Never -- \
  mysql -h mysql-service.monitor-system.svc.cluster.local -u monitor -p
```

#### Performance Issues

```bash
# Check resource usage
kubectl top pods -n monitor-system
kubectl top nodes

# Check HPA status
kubectl get hpa -n monitor-system
kubectl describe hpa monitor-data-service-hpa -n monitor-system
```

### Debug Commands

```bash
# Port forward for local debugging
kubectl port-forward -n monitor-system svc/monitor-api-gateway-service 8080:8080
kubectl port-forward -n monitor-system svc/prometheus-service 9090:9090

# Execute commands in pods
kubectl exec -it deployment/monitor-data-service -n monitor-system -- /bin/bash

# Check Istio configuration (if using service mesh)
istioctl proxy-config cluster <pod-name> -n monitor-system
istioctl analyze -n monitor-system
```

## Rollback Procedures

### Automatic Rollback

The CI/CD pipeline includes automatic rollback triggers:

- Failed health checks after deployment
- Critical error rates above threshold
- Manual trigger via GitHub Actions

### Manual Rollback

#### Using GitHub Actions

1. Go to Actions tab in GitHub
2. Select "Rollback Deployment" workflow
3. Choose environment and rollback target
4. Provide rollback reason
5. Execute workflow

#### Using kubectl

```bash
# Rollback to previous version
kubectl rollout undo deployment/monitor-api-gateway -n monitor-system
kubectl rollout undo deployment/monitor-data-service -n monitor-system

# Rollback to specific revision
kubectl rollout undo deployment/monitor-api-gateway --to-revision=2 -n monitor-system

# Check rollout status
kubectl rollout status deployment/monitor-api-gateway -n monitor-system
```

#### Emergency Rollback

For critical issues:

```bash
# Scale down problematic services immediately
kubectl scale deployment monitor-data-service --replicas=0 -n monitor-system

# Restore from backup configuration
kubectl apply -f backup/deployment-backup.yaml

# Verify system stability
kubectl get pods -n monitor-system
curl -f http://api-gateway-url/actuator/health
```

### Rollback Verification

After rollback:

1. **Health Checks**: Verify all services are healthy
2. **Functionality Tests**: Run smoke tests
3. **Performance Monitoring**: Check metrics and logs
4. **User Acceptance**: Verify user-facing functionality

### Post-Rollback Actions

1. **Root Cause Analysis**: Investigate the issue that caused rollback
2. **Fix Implementation**: Develop and test fixes
3. **Documentation**: Update runbooks and procedures
4. **Team Communication**: Notify stakeholders of resolution

## Best Practices

### Deployment Best Practices

1. **Blue-Green Deployments**: Use for zero-downtime deployments
2. **Canary Releases**: Gradual rollout for risk mitigation
3. **Health Checks**: Comprehensive readiness and liveness probes
4. **Resource Limits**: Always set resource requests and limits
5. **Security**: Apply security contexts and network policies

### Monitoring Best Practices

1. **Alerting**: Set up alerts for critical metrics
2. **Dashboards**: Create comprehensive monitoring dashboards
3. **Log Retention**: Configure appropriate log retention policies
4. **Backup**: Regular backups of persistent data
5. **Documentation**: Keep deployment documentation updated

### Security Best Practices

1. **Secrets Management**: Use Kubernetes secrets, never hardcode
2. **Image Scanning**: Scan all container images for vulnerabilities
3. **Network Policies**: Implement network segmentation
4. **RBAC**: Apply principle of least privilege
5. **Regular Updates**: Keep all components updated with security patches