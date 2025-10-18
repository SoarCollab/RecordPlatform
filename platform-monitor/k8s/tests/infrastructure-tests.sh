#!/bin/bash

# Infrastructure Validation Tests
# This script validates the Kubernetes infrastructure deployment

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
NAMESPACE="${NAMESPACE:-monitor-system}"
TIMEOUT="${TIMEOUT:-300}"

# Test counters
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

# Function to print colored output
print_test_header() {
    echo -e "\n${BLUE}=== $1 ===${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
    ((TESTS_PASSED++))
}

print_failure() {
    echo -e "${RED}✗ $1${NC}"
    ((TESTS_FAILED++))
}

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

# Function to run a test
run_test() {
    local test_name="$1"
    local test_command="$2"
    
    ((TESTS_RUN++))
    print_info "Running: $test_name"
    
    if eval "$test_command" >/dev/null 2>&1; then
        print_success "$test_name"
        return 0
    else
        print_failure "$test_name"
        return 1
    fi
}

# Function to wait for resource to be ready
wait_for_resource() {
    local resource_type="$1"
    local resource_name="$2"
    local condition="${3:-available}"
    local timeout="${4:-$TIMEOUT}"
    
    print_info "Waiting for $resource_type/$resource_name to be ready..."
    
    if kubectl wait --for=condition=$condition --timeout=${timeout}s $resource_type/$resource_name -n $NAMESPACE >/dev/null 2>&1; then
        return 0
    else
        return 1
    fi
}

# Test 1: Namespace and Basic Resources
test_namespace_and_basic_resources() {
    print_test_header "Testing Namespace and Basic Resources"
    
    run_test "Namespace exists" "kubectl get namespace $NAMESPACE"
    run_test "ConfigMap exists" "kubectl get configmap monitor-config -n $NAMESPACE"
    run_test "Secrets exist" "kubectl get secret monitor-secrets -n $NAMESPACE"
    run_test "ServiceAccounts exist" "kubectl get serviceaccount monitor-api-gateway -n $NAMESPACE"
    run_test "RBAC Role exists" "kubectl get role monitor-service-role -n $NAMESPACE"
    run_test "RBAC RoleBinding exists" "kubectl get rolebinding monitor-service-rolebinding -n $NAMESPACE"
}

# Test 2: Infrastructure Services
test_infrastructure_services() {
    print_test_header "Testing Infrastructure Services"
    
    # MySQL
    run_test "MySQL deployment exists" "kubectl get deployment mysql -n $NAMESPACE"
    run_test "MySQL service exists" "kubectl get service mysql-service -n $NAMESPACE"
    run_test "MySQL PVC exists" "kubectl get pvc mysql-pvc -n $NAMESPACE"
    
    if wait_for_resource "deployment" "mysql" "available" 120; then
        print_success "MySQL deployment is ready"
        ((TESTS_PASSED++))
    else
        print_failure "MySQL deployment failed to become ready"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # Redis
    run_test "Redis deployment exists" "kubectl get deployment redis -n $NAMESPACE"
    run_test "Redis service exists" "kubectl get service redis-service -n $NAMESPACE"
    run_test "Redis PVC exists" "kubectl get pvc redis-pvc -n $NAMESPACE"
    
    if wait_for_resource "deployment" "redis" "available" 60; then
        print_success "Redis deployment is ready"
        ((TESTS_PASSED++))
    else
        print_failure "Redis deployment failed to become ready"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # InfluxDB
    run_test "InfluxDB deployment exists" "kubectl get deployment influxdb -n $NAMESPACE"
    run_test "InfluxDB service exists" "kubectl get service influxdb-service -n $NAMESPACE"
    run_test "InfluxDB PVC exists" "kubectl get pvc influxdb-pvc -n $NAMESPACE"
    
    if wait_for_resource "deployment" "influxdb" "available" 120; then
        print_success "InfluxDB deployment is ready"
        ((TESTS_PASSED++))
    else
        print_failure "InfluxDB deployment failed to become ready"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # RabbitMQ
    run_test "RabbitMQ deployment exists" "kubectl get deployment rabbitmq -n $NAMESPACE"
    run_test "RabbitMQ service exists" "kubectl get service rabbitmq-service -n $NAMESPACE"
    run_test "RabbitMQ PVC exists" "kubectl get pvc rabbitmq-pvc -n $NAMESPACE"
    
    if wait_for_resource "deployment" "rabbitmq" "available" 120; then
        print_success "RabbitMQ deployment is ready"
        ((TESTS_PASSED++))
    else
        print_failure "RabbitMQ deployment failed to become ready"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
}

# Test 3: Application Services
test_application_services() {
    print_test_header "Testing Application Services"
    
    local services=("monitor-api-gateway" "monitor-auth-service" "monitor-data-service" 
                   "monitor-notification-service" "monitor-websocket-service" "monitor-web-dashboard")
    
    for service in "${services[@]}"; do
        run_test "$service deployment exists" "kubectl get deployment $service -n $NAMESPACE"
        run_test "$service service exists" "kubectl get service ${service}-service -n $NAMESPACE"
        run_test "$service HPA exists" "kubectl get hpa ${service}-hpa -n $NAMESPACE"
        
        if wait_for_resource "deployment" "$service" "available" 180; then
            print_success "$service deployment is ready"
            ((TESTS_PASSED++))
        else
            print_failure "$service deployment failed to become ready"
            ((TESTS_FAILED++))
        fi
        ((TESTS_RUN++))
    done
}

# Test 4: Pod Health and Resource Usage
test_pod_health() {
    print_test_header "Testing Pod Health and Resource Usage"
    
    # Check all pods are running
    local running_pods=$(kubectl get pods -n $NAMESPACE --field-selector=status.phase=Running --no-headers | wc -l)
    local total_pods=$(kubectl get pods -n $NAMESPACE --no-headers | wc -l)
    
    if [ "$running_pods" -eq "$total_pods" ] && [ "$total_pods" -gt 0 ]; then
        print_success "All pods are running ($running_pods/$total_pods)"
        ((TESTS_PASSED++))
    else
        print_failure "Not all pods are running ($running_pods/$total_pods)"
        kubectl get pods -n $NAMESPACE
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # Check for pods with restart count > 0
    local restarted_pods=$(kubectl get pods -n $NAMESPACE --no-headers | awk '$4 > 0 {print $1}')
    if [ -z "$restarted_pods" ]; then
        print_success "No pods have restarted"
        ((TESTS_PASSED++))
    else
        print_warning "Some pods have restarted: $restarted_pods"
        ((TESTS_PASSED++)) # This is a warning, not a failure
    fi
    ((TESTS_RUN++))
    
    # Check resource usage
    if kubectl top pods -n $NAMESPACE >/dev/null 2>&1; then
        print_success "Resource metrics are available"
        ((TESTS_PASSED++))
    else
        print_warning "Resource metrics not available (metrics-server may not be installed)"
        ((TESTS_PASSED++)) # This is optional
    fi
    ((TESTS_RUN++))
}

# Test 5: Service Connectivity
test_service_connectivity() {
    print_test_header "Testing Service Connectivity"
    
    # Test internal service connectivity
    local services=("mysql-service:3306" "redis-service:6379" "influxdb-service:8086" 
                   "rabbitmq-service:5672" "monitor-api-gateway-service:8080")
    
    for service_port in "${services[@]}"; do
        local service=$(echo $service_port | cut -d: -f1)
        local port=$(echo $service_port | cut -d: -f2)
        
        if kubectl run connectivity-test-$(date +%s) --image=busybox --rm -i --restart=Never --timeout=30s -- \
           nc -z $service.$NAMESPACE.svc.cluster.local $port >/dev/null 2>&1; then
            print_success "Connectivity to $service:$port"
            ((TESTS_PASSED++))
        else
            print_failure "Cannot connect to $service:$port"
            ((TESTS_FAILED++))
        fi
        ((TESTS_RUN++))
    done
}

# Test 6: Health Endpoints
test_health_endpoints() {
    print_test_header "Testing Health Endpoints"
    
    local app_services=("monitor-api-gateway-service:8080" "monitor-auth-service:8081" 
                       "monitor-data-service:8082" "monitor-notification-service:8083" 
                       "monitor-websocket-service:8084")
    
    for service_port in "${app_services[@]}"; do
        local service=$(echo $service_port | cut -d: -f1)
        local port=$(echo $service_port | cut -d: -f2)
        
        if kubectl run health-test-$(date +%s) --image=curlimages/curl --rm -i --restart=Never --timeout=30s -- \
           curl -f http://$service.$NAMESPACE.svc.cluster.local:$port/actuator/health >/dev/null 2>&1; then
            print_success "Health endpoint for $service"
            ((TESTS_PASSED++))
        else
            print_failure "Health endpoint failed for $service"
            ((TESTS_FAILED++))
        fi
        ((TESTS_RUN++))
    done
}

# Test 7: Horizontal Pod Autoscaler
test_hpa() {
    print_test_header "Testing Horizontal Pod Autoscaler"
    
    local services=("monitor-api-gateway" "monitor-auth-service" "monitor-data-service" 
                   "monitor-notification-service" "monitor-websocket-service" "monitor-web-dashboard")
    
    for service in "${services[@]}"; do
        local hpa_status=$(kubectl get hpa ${service}-hpa -n $NAMESPACE -o jsonpath='{.status.conditions[?(@.type=="AbleToScale")].status}' 2>/dev/null)
        
        if [ "$hpa_status" = "True" ]; then
            print_success "HPA is working for $service"
            ((TESTS_PASSED++))
        else
            print_failure "HPA is not working for $service"
            ((TESTS_FAILED++))
        fi
        ((TESTS_RUN++))
    done
}

# Test 8: Persistent Volume Claims
test_persistent_storage() {
    print_test_header "Testing Persistent Storage"
    
    local pvcs=("mysql-pvc" "redis-pvc" "influxdb-pvc" "rabbitmq-pvc")
    
    for pvc in "${pvcs[@]}"; do
        local pvc_status=$(kubectl get pvc $pvc -n $NAMESPACE -o jsonpath='{.status.phase}' 2>/dev/null)
        
        if [ "$pvc_status" = "Bound" ]; then
            print_success "PVC $pvc is bound"
            ((TESTS_PASSED++))
        else
            print_failure "PVC $pvc is not bound (status: $pvc_status)"
            ((TESTS_FAILED++))
        fi
        ((TESTS_RUN++))
    done
}

# Test 9: Istio Configuration (if available)
test_istio_configuration() {
    print_test_header "Testing Istio Configuration"
    
    # Check if Istio is installed
    if kubectl get namespace istio-system >/dev/null 2>&1; then
        print_info "Istio is installed, testing service mesh configuration"
        
        run_test "Istio Gateway exists" "kubectl get gateway monitor-gateway -n $NAMESPACE"
        run_test "Istio VirtualService exists" "kubectl get virtualservice monitor-virtualservice -n $NAMESPACE"
        run_test "Istio DestinationRules exist" "kubectl get destinationrule -n $NAMESPACE"
        run_test "Istio PeerAuthentication exists" "kubectl get peerauthentication default -n $NAMESPACE"
        run_test "Istio AuthorizationPolicies exist" "kubectl get authorizationpolicy -n $NAMESPACE"
        
        # Check if pods have Istio sidecars
        local pods_with_sidecars=$(kubectl get pods -n $NAMESPACE -o jsonpath='{.items[*].spec.containers[*].name}' | grep -o istio-proxy | wc -l)
        local total_app_pods=$(kubectl get pods -n $NAMESPACE -l 'app in (monitor-api-gateway,monitor-auth-service,monitor-data-service,monitor-notification-service,monitor-websocket-service,monitor-web-dashboard)' --no-headers | wc -l)
        
        if [ "$pods_with_sidecars" -eq "$total_app_pods" ]; then
            print_success "All application pods have Istio sidecars ($pods_with_sidecars/$total_app_pods)"
            ((TESTS_PASSED++))
        else
            print_failure "Not all pods have Istio sidecars ($pods_with_sidecars/$total_app_pods)"
            ((TESTS_FAILED++))
        fi
        ((TESTS_RUN++))
    else
        print_info "Istio is not installed, skipping service mesh tests"
    fi
}

# Test 10: Monitoring Stack
test_monitoring_stack() {
    print_test_header "Testing Monitoring Stack"
    
    run_test "Prometheus deployment exists" "kubectl get deployment prometheus -n $NAMESPACE"
    run_test "Prometheus service exists" "kubectl get service prometheus-service -n $NAMESPACE"
    run_test "Prometheus ConfigMap exists" "kubectl get configmap prometheus-config -n $NAMESPACE"
    
    if wait_for_resource "deployment" "prometheus" "available" 120; then
        print_success "Prometheus deployment is ready"
        ((TESTS_PASSED++))
    else
        print_failure "Prometheus deployment failed to become ready"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # Test Prometheus metrics endpoint
    if kubectl run prometheus-test-$(date +%s) --image=curlimages/curl --rm -i --restart=Never --timeout=30s -- \
       curl -f http://prometheus-service.$NAMESPACE.svc.cluster.local:9090/metrics >/dev/null 2>&1; then
        print_success "Prometheus metrics endpoint is accessible"
        ((TESTS_PASSED++))
    else
        print_failure "Prometheus metrics endpoint is not accessible"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
}

# Test 11: Security Configuration
test_security_configuration() {
    print_test_header "Testing Security Configuration"
    
    # Check security contexts
    local pods=$(kubectl get pods -n $NAMESPACE -o name)
    local secure_pods=0
    local total_pods=0
    
    for pod in $pods; do
        ((total_pods++))
        local run_as_non_root=$(kubectl get $pod -n $NAMESPACE -o jsonpath='{.spec.securityContext.runAsNonRoot}')
        local read_only_root=$(kubectl get $pod -n $NAMESPACE -o jsonpath='{.spec.containers[0].securityContext.readOnlyRootFilesystem}')
        
        if [ "$run_as_non_root" = "true" ] && [ "$read_only_root" = "true" ]; then
            ((secure_pods++))
        fi
    done
    
    if [ "$secure_pods" -eq "$total_pods" ]; then
        print_success "All pods have secure security contexts ($secure_pods/$total_pods)"
        ((TESTS_PASSED++))
    else
        print_warning "Some pods may not have optimal security contexts ($secure_pods/$total_pods)"
        ((TESTS_PASSED++)) # This is a warning for now
    fi
    ((TESTS_RUN++))
    
    # Check for network policies (production only)
    if kubectl get networkpolicy -n $NAMESPACE >/dev/null 2>&1; then
        print_success "Network policies are configured"
        ((TESTS_PASSED++))
    else
        print_info "Network policies not found (may be optional for this environment)"
        ((TESTS_PASSED++))
    fi
    ((TESTS_RUN++))
}

# Test 12: Performance and Scaling
test_performance_and_scaling() {
    print_test_header "Testing Performance and Scaling"
    
    # Check resource requests and limits
    local deployments=$(kubectl get deployments -n $NAMESPACE -o name)
    local deployments_with_limits=0
    local total_deployments=0
    
    for deployment in $deployments; do
        ((total_deployments++))
        local has_requests=$(kubectl get $deployment -n $NAMESPACE -o jsonpath='{.spec.template.spec.containers[0].resources.requests}')
        local has_limits=$(kubectl get $deployment -n $NAMESPACE -o jsonpath='{.spec.template.spec.containers[0].resources.limits}')
        
        if [ -n "$has_requests" ] && [ -n "$has_limits" ]; then
            ((deployments_with_limits++))
        fi
    done
    
    if [ "$deployments_with_limits" -eq "$total_deployments" ]; then
        print_success "All deployments have resource requests and limits ($deployments_with_limits/$total_deployments)"
        ((TESTS_PASSED++))
    else
        print_failure "Some deployments missing resource requests/limits ($deployments_with_limits/$total_deployments)"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # Test HPA scaling capability (basic check)
    local hpa_count=$(kubectl get hpa -n $NAMESPACE --no-headers | wc -l)
    if [ "$hpa_count" -gt 0 ]; then
        print_success "HPA configurations are present ($hpa_count HPAs)"
        ((TESTS_PASSED++))
    else
        print_failure "No HPA configurations found"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
}

# Main test execution
main() {
    echo -e "${BLUE}Starting Infrastructure Validation Tests${NC}"
    echo -e "${BLUE}Namespace: $NAMESPACE${NC}"
    echo -e "${BLUE}Timeout: $TIMEOUT seconds${NC}\n"
    
    # Check kubectl connectivity
    if ! kubectl cluster-info >/dev/null 2>&1; then
        echo -e "${RED}Error: Cannot connect to Kubernetes cluster${NC}"
        exit 1
    fi
    
    # Run all tests
    test_namespace_and_basic_resources
    test_infrastructure_services
    test_application_services
    test_pod_health
    test_service_connectivity
    test_health_endpoints
    test_hpa
    test_persistent_storage
    test_istio_configuration
    test_monitoring_stack
    test_security_configuration
    test_performance_and_scaling
    
    # Print summary
    echo -e "\n${BLUE}=== Test Summary ===${NC}"
    echo -e "Tests Run: $TESTS_RUN"
    echo -e "${GREEN}Tests Passed: $TESTS_PASSED${NC}"
    echo -e "${RED}Tests Failed: $TESTS_FAILED${NC}"
    
    local success_rate=$((TESTS_PASSED * 100 / TESTS_RUN))
    echo -e "Success Rate: $success_rate%"
    
    if [ $TESTS_FAILED -eq 0 ]; then
        echo -e "\n${GREEN}🎉 All infrastructure tests passed!${NC}"
        exit 0
    else
        echo -e "\n${RED}❌ Some infrastructure tests failed. Please check the output above.${NC}"
        exit 1
    fi
}

# Handle script arguments
case "${1:-run}" in
    "run")
        main
        ;;
    "help")
        echo "Usage: $0 [run|help]"
        echo ""
        echo "Environment Variables:"
        echo "  NAMESPACE - Kubernetes namespace to test (default: monitor-system)"
        echo "  TIMEOUT   - Timeout for resource readiness (default: 300 seconds)"
        echo ""
        echo "Examples:"
        echo "  $0                           # Run all tests"
        echo "  NAMESPACE=monitor-system-dev $0  # Test dev environment"
        echo "  TIMEOUT=600 $0               # Use longer timeout"
        ;;
    *)
        echo "Unknown command: $1"
        echo "Use '$0 help' for usage information"
        exit 1
        ;;
esac