#!/bin/bash

# Monitor System Kubernetes Deployment Script
# This script deploys the complete monitor system to Kubernetes with proper ordering

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
NAMESPACE="monitor-system"
DOCKER_REGISTRY="${DOCKER_REGISTRY:-monitor}"
IMAGE_TAG="${IMAGE_TAG:-latest}"

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to wait for deployment to be ready
wait_for_deployment() {
    local deployment=$1
    local namespace=$2
    print_status "Waiting for deployment $deployment to be ready..."
    kubectl rollout status deployment/$deployment -n $namespace --timeout=300s
    if [ $? -eq 0 ]; then
        print_success "Deployment $deployment is ready"
    else
        print_error "Deployment $deployment failed to become ready"
        exit 1
    fi
}

# Function to wait for pods to be ready
wait_for_pods() {
    local label_selector=$1
    local namespace=$2
    local expected_count=$3
    
    print_status "Waiting for pods with selector $label_selector to be ready..."
    
    local timeout=300
    local interval=10
    local elapsed=0
    
    while [ $elapsed -lt $timeout ]; do
        local ready_count=$(kubectl get pods -n $namespace -l $label_selector --field-selector=status.phase=Running --no-headers 2>/dev/null | wc -l)
        
        if [ "$ready_count" -ge "$expected_count" ]; then
            print_success "All expected pods are ready ($ready_count/$expected_count)"
            return 0
        fi
        
        print_status "Waiting for pods... ($ready_count/$expected_count ready)"
        sleep $interval
        elapsed=$((elapsed + interval))
    done
    
    print_error "Timeout waiting for pods to be ready"
    kubectl get pods -n $namespace -l $label_selector
    return 1
}

# Function to check if Istio is installed
check_istio() {
    if kubectl get namespace istio-system >/dev/null 2>&1; then
        print_success "Istio is installed"
        return 0
    else
        print_warning "Istio is not installed. Service mesh features will not be available."
        return 1
    fi
}

# Function to build and push Docker images
build_and_push_images() {
    print_status "Building and pushing Docker images..."
    
    local services=("monitor-api-gateway" "monitor-auth-service" "monitor-data-service" 
                   "monitor-notification-service" "monitor-websocket-service" "monitor-web-dashboard")
    
    for service in "${services[@]}"; do
        print_status "Building $service..."
        
        if [ "$service" = "monitor-web-dashboard" ]; then
            docker build -t $DOCKER_REGISTRY/$service:$IMAGE_TAG ./monitor-web-dashboard/
        else
            docker build -t $DOCKER_REGISTRY/$service:$IMAGE_TAG ./$service/
        fi
        
        if [ $? -eq 0 ]; then
            print_success "Built $service successfully"
            
            # Push to registry if DOCKER_REGISTRY is not 'monitor' (local)
            if [ "$DOCKER_REGISTRY" != "monitor" ]; then
                print_status "Pushing $service to registry..."
                docker push $DOCKER_REGISTRY/$service:$IMAGE_TAG
                print_success "Pushed $service successfully"
            fi
        else
            print_error "Failed to build $service"
            exit 1
        fi
    done
}

# Function to deploy infrastructure components
deploy_infrastructure() {
    print_status "Deploying infrastructure components..."
    
    # Create namespace
    kubectl apply -f namespace.yaml
    
    # Deploy ConfigMaps and Secrets
    kubectl apply -f configmap.yaml
    
    # Deploy RBAC
    kubectl apply -f rbac.yaml
    
    # Deploy infrastructure services
    kubectl apply -f infrastructure/mysql.yaml
    kubectl apply -f infrastructure/redis.yaml
    kubectl apply -f infrastructure/influxdb.yaml
    kubectl apply -f infrastructure/rabbitmq.yaml
    
    # Wait for infrastructure to be ready
    wait_for_deployment "mysql" $NAMESPACE
    wait_for_deployment "redis" $NAMESPACE
    wait_for_deployment "influxdb" $NAMESPACE
    wait_for_deployment "rabbitmq" $NAMESPACE
    
    print_success "Infrastructure components deployed successfully"
}

# Function to deploy application services
deploy_services() {
    print_status "Deploying application services..."
    
    # Deploy service discovery configuration
    kubectl apply -f service-discovery.yaml
    
    # Deploy services in dependency order
    kubectl apply -f services/auth-service.yaml
    wait_for_deployment "monitor-auth-service" $NAMESPACE
    
    kubectl apply -f services/data-service.yaml
    wait_for_deployment "monitor-data-service" $NAMESPACE
    
    kubectl apply -f services/notification-service.yaml
    wait_for_deployment "monitor-notification-service" $NAMESPACE
    
    kubectl apply -f services/websocket-service.yaml
    wait_for_deployment "monitor-websocket-service" $NAMESPACE
    
    kubectl apply -f services/api-gateway.yaml
    wait_for_deployment "monitor-api-gateway" $NAMESPACE
    
    kubectl apply -f services/web-dashboard.yaml
    wait_for_deployment "monitor-web-dashboard" $NAMESPACE
    
    print_success "Application services deployed successfully"
}

# Function to deploy Istio configuration
deploy_istio() {
    if check_istio; then
        print_status "Deploying Istio configuration..."
        
        # Enable Istio injection for the namespace
        kubectl label namespace $NAMESPACE istio-injection=enabled --overwrite
        
        # Deploy Istio resources
        kubectl apply -f istio/gateway.yaml
        kubectl apply -f istio/security.yaml
        
        print_success "Istio configuration deployed successfully"
    else
        print_warning "Skipping Istio deployment"
    fi
}

# Function to deploy monitoring
deploy_monitoring() {
    print_status "Deploying monitoring components..."
    
    kubectl apply -f monitoring/prometheus.yaml
    wait_for_deployment "prometheus" $NAMESPACE
    
    kubectl apply -f monitoring/jaeger.yaml
    wait_for_deployment "jaeger" $NAMESPACE
    
    kubectl apply -f monitoring/logging.yaml
    wait_for_deployment "elasticsearch" $NAMESPACE
    
    kubectl apply -f monitoring/grafana.yaml
    wait_for_deployment "grafana" $NAMESPACE
    
    kubectl apply -f monitoring/metrics-config.yaml
    kubectl apply -f monitoring/service-monitors.yaml
    
    print_success "Monitoring components deployed successfully"
}

# Function to verify deployment
verify_deployment() {
    print_status "Verifying deployment..."
    
    # Check all pods are running
    kubectl get pods -n $NAMESPACE
    
    # Check services
    kubectl get services -n $NAMESPACE
    
    # Check HPA status
    kubectl get hpa -n $NAMESPACE
    
    # Get ingress information if Istio is available
    if check_istio; then
        kubectl get gateway -n $NAMESPACE
        kubectl get virtualservice -n $NAMESPACE
    fi
    
    print_success "Deployment verification completed"
}

# Function to show access information
show_access_info() {
    print_status "Access Information:"
    
    if check_istio; then
        echo "The application is accessible through Istio Gateway:"
        echo "  - Web Dashboard: https://monitor.local"
        echo "  - API Gateway: https://monitor.local/api"
        echo "  - WebSocket: wss://monitor.local/ws"
        echo ""
        echo "Make sure to:"
        echo "1. Configure DNS or add 'monitor.local' to your /etc/hosts file"
        echo "2. Install TLS certificates in the monitor-tls-secret"
    else
        echo "Without Istio, you can access services using port-forwarding:"
        echo "  kubectl port-forward -n $NAMESPACE svc/monitor-web-dashboard-service 8080:80"
        echo "  kubectl port-forward -n $NAMESPACE svc/monitor-api-gateway-service 8081:8080"
    fi
    
    echo ""
    echo "Monitoring Services:"
    echo "  Prometheus: kubectl port-forward -n $NAMESPACE svc/prometheus-service 9090:9090"
    echo "  Grafana: kubectl port-forward -n $NAMESPACE svc/grafana-service 3000:3000"
    echo "  Jaeger: kubectl port-forward -n $NAMESPACE svc/jaeger-service 16686:16686"
    echo "  Elasticsearch: kubectl port-forward -n $NAMESPACE svc/elasticsearch-service 9200:9200"
    echo ""
    echo "Default Credentials:"
    echo "  Grafana: admin/admin123"
}

# Main deployment function
main() {
    print_status "Starting Monitor System Kubernetes Deployment"
    print_status "Namespace: $NAMESPACE"
    print_status "Docker Registry: $DOCKER_REGISTRY"
    print_status "Image Tag: $IMAGE_TAG"
    
    # Check if kubectl is available
    if ! command -v kubectl &> /dev/null; then
        print_error "kubectl is not installed or not in PATH"
        exit 1
    fi
    
    # Check if Docker is available for building images
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed or not in PATH"
        exit 1
    fi
    
    # Check Kubernetes connection
    if ! kubectl cluster-info &> /dev/null; then
        print_error "Cannot connect to Kubernetes cluster"
        exit 1
    fi
    
    # Build and push images
    build_and_push_images
    
    # Deploy components in order
    deploy_infrastructure
    deploy_services
    deploy_istio
    deploy_monitoring
    
    # Verify deployment
    verify_deployment
    
    # Show access information
    show_access_info
    
    print_success "Monitor System deployment completed successfully!"
}

# Handle script arguments
case "${1:-deploy}" in
    "deploy")
        main
        ;;
    "infrastructure")
        deploy_infrastructure
        ;;
    "services")
        deploy_services
        ;;
    "istio")
        deploy_istio
        ;;
    "monitoring")
        deploy_monitoring
        ;;
    "verify")
        verify_deployment
        ;;
    "clean")
        print_status "Cleaning up Monitor System deployment..."
        kubectl delete namespace $NAMESPACE --ignore-not-found=true
        print_success "Cleanup completed"
        ;;
    *)
        echo "Usage: $0 {deploy|infrastructure|services|istio|monitoring|verify|clean}"
        echo ""
        echo "Commands:"
        echo "  deploy        - Full deployment (default)"
        echo "  infrastructure - Deploy only infrastructure components"
        echo "  services      - Deploy only application services"
        echo "  istio         - Deploy only Istio configuration"
        echo "  monitoring    - Deploy only monitoring components"
        echo "  verify        - Verify existing deployment"
        echo "  clean         - Remove all components"
        exit 1
        ;;
esac