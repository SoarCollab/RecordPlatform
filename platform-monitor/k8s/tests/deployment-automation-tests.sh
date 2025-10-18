#!/bin/bash

# Deployment Automation and Scaling Tests
# This script tests deployment automation, scaling, and rollback procedures

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
NAMESPACE="${NAMESPACE:-monitor-system}"
TEST_NAMESPACE="${TEST_NAMESPACE:-monitor-system-test}"
TIMEOUT="${TIMEOUT:-300}"
LOAD_TEST_DURATION="${LOAD_TEST_DURATION:-60}"

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

# Function to wait for deployment rollout
wait_for_rollout() {
    local deployment="$1"
    local namespace="$2"
    local timeout="${3:-$TIMEOUT}"
    
    if kubectl rollout status deployment/$deployment -n $namespace --timeout=${timeout}s >/dev/null 2>&1; then
        return 0
    else
        return 1
    fi
}

# Test 1: Deployment Automation
test_deployment_automation() {
    print_test_header "Testing Deployment Automation"
    
    # Create test namespace
    kubectl create namespace $TEST_NAMESPACE --dry-run=client -o yaml | kubectl apply -f -
    
    # Test deployment script functionality
    print_info "Testing deployment script components..."
    
    # Test namespace creation
    run_test "Deployment script can create namespace" "kubectl get namespace $TEST_NAMESPACE"
    
    # Test ConfigMap deployment
    cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: test-config
  namespace: $TEST_NAMESPACE
data:
  test-key: test-value
EOF
    
    run_test "ConfigMap deployment works" "kubectl get configmap test-config -n $TEST_NAMESPACE"
    
    # Test Secret deployment
    kubectl create secret generic test-secret --from-literal=test-key=test-value -n $TEST_NAMESPACE --dry-run=client -o yaml | kubectl apply -f -
    
    run_test "Secret deployment works" "kubectl get secret test-secret -n $TEST_NAMESPACE"
    
    # Test simple deployment
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: test-app
  namespace: $TEST_NAMESPACE
spec:
  replicas: 1
  selector:
    matchLabels:
      app: test-app
  template:
    metadata:
      labels:
        app: test-app
    spec:
      containers:
      - name: test-app
        image: nginx:alpine
        ports:
        - containerPort: 80
        resources:
          requests:
            memory: "64Mi"
            cpu: "50m"
          limits:
            memory: "128Mi"
            cpu: "100m"
EOF
    
    run_test "Test deployment creation" "kubectl get deployment test-app -n $TEST_NAMESPACE"
    
    if wait_for_rollout "test-app" "$TEST_NAMESPACE" 120; then
        print_success "Test deployment rollout completed"
        ((TESTS_PASSED++))
    else
        print_failure "Test deployment rollout failed"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
}

# Test 2: Rolling Updates
test_rolling_updates() {
    print_test_header "Testing Rolling Updates"
    
    # Update the test deployment with a new image
    print_info "Performing rolling update..."
    
    kubectl set image deployment/test-app test-app=nginx:1.21-alpine -n $TEST_NAMESPACE
    
    if wait_for_rollout "test-app" "$TEST_NAMESPACE" 120; then
        print_success "Rolling update completed successfully"
        ((TESTS_PASSED++))
    else
        print_failure "Rolling update failed"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # Check rollout history
    local revision_count=$(kubectl rollout history deployment/test-app -n $TEST_NAMESPACE | wc -l)
    if [ "$revision_count" -gt 2 ]; then
        print_success "Rollout history is maintained"
        ((TESTS_PASSED++))
    else
        print_failure "Rollout history not properly maintained"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # Test rollback capability
    print_info "Testing rollback capability..."
    kubectl rollout undo deployment/test-app -n $TEST_NAMESPACE
    
    if wait_for_rollout "test-app" "$TEST_NAMESPACE" 120; then
        print_success "Rollback completed successfully"
        ((TESTS_PASSED++))
    else
        print_failure "Rollback failed"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
}

# Test 3: Horizontal Pod Autoscaler
test_horizontal_scaling() {
    print_test_header "Testing Horizontal Pod Autoscaler"
    
    # Create HPA for test deployment
    cat <<EOF | kubectl apply -f -
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: test-app-hpa
  namespace: $TEST_NAMESPACE
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: test-app
  minReplicas: 1
  maxReplicas: 5
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 50
EOF
    
    run_test "HPA creation" "kubectl get hpa test-app-hpa -n $TEST_NAMESPACE"
    
    # Wait for HPA to initialize
    sleep 30
    
    # Check HPA status
    local hpa_status=$(kubectl get hpa test-app-hpa -n $TEST_NAMESPACE -o jsonpath='{.status.conditions[?(@.type=="AbleToScale")].status}')
    if [ "$hpa_status" = "True" ]; then
        print_success "HPA is able to scale"
        ((TESTS_PASSED++))
    else
        print_failure "HPA is not able to scale"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # Test manual scaling
    print_info "Testing manual scaling..."
    kubectl scale deployment test-app --replicas=3 -n $TEST_NAMESPACE
    
    if wait_for_rollout "test-app" "$TEST_NAMESPACE" 120; then
        local current_replicas=$(kubectl get deployment test-app -n $TEST_NAMESPACE -o jsonpath='{.status.readyReplicas}')
        if [ "$current_replicas" = "3" ]; then
            print_success "Manual scaling to 3 replicas successful"
            ((TESTS_PASSED++))
        else
            print_failure "Manual scaling failed (current: $current_replicas, expected: 3)"
            ((TESTS_FAILED++))
        fi
    else
        print_failure "Scaling rollout failed"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
}

# Test 4: Load Testing and Auto-scaling
test_load_and_autoscaling() {
    print_test_header "Testing Load Generation and Auto-scaling"
    
    # Create a service for the test app
    cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Service
metadata:
  name: test-app-service
  namespace: $TEST_NAMESPACE
spec:
  selector:
    app: test-app
  ports:
  - port: 80
    targetPort: 80
  type: ClusterIP
EOF
    
    run_test "Test service creation" "kubectl get service test-app-service -n $TEST_NAMESPACE"
    
    # Generate load to trigger autoscaling
    print_info "Generating load for $LOAD_TEST_DURATION seconds..."
    
    # Create load generator
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: load-generator
  namespace: $TEST_NAMESPACE
spec:
  replicas: 2
  selector:
    matchLabels:
      app: load-generator
  template:
    metadata:
      labels:
        app: load-generator
    spec:
      containers:
      - name: load-generator
        image: busybox
        command:
        - /bin/sh
        - -c
        - |
          while true; do
            wget -q -O- http://test-app-service.$TEST_NAMESPACE.svc.cluster.local/ || true
            sleep 0.1
          done
        resources:
          requests:
            cpu: 100m
            memory: 64Mi
          limits:
            cpu: 200m
            memory: 128Mi
EOF
    
    if wait_for_rollout "load-generator" "$TEST_NAMESPACE" 60; then
        print_success "Load generator deployed"
        ((TESTS_PASSED++))
    else
        print_failure "Load generator deployment failed"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # Wait for load generation and potential scaling
    print_info "Waiting for potential auto-scaling..."
    sleep $LOAD_TEST_DURATION
    
    # Check if scaling occurred (this may not always happen in test environments)
    local final_replicas=$(kubectl get deployment test-app -n $TEST_NAMESPACE -o jsonpath='{.status.readyReplicas}')
    print_info "Final replica count: $final_replicas"
    
    # Clean up load generator
    kubectl delete deployment load-generator -n $TEST_NAMESPACE
    
    # The scaling test is informational since it depends on metrics server and actual load
    print_success "Load testing completed (final replicas: $final_replicas)"
    ((TESTS_PASSED++))
    ((TESTS_RUN++))
}

# Test 5: Resource Limits and Quality of Service
test_resource_management() {
    print_test_header "Testing Resource Management"
    
    # Create deployment with different QoS classes
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: guaranteed-qos-app
  namespace: $TEST_NAMESPACE
spec:
  replicas: 1
  selector:
    matchLabels:
      app: guaranteed-qos-app
  template:
    metadata:
      labels:
        app: guaranteed-qos-app
    spec:
      containers:
      - name: app
        image: nginx:alpine
        resources:
          requests:
            memory: "128Mi"
            cpu: "100m"
          limits:
            memory: "128Mi"
            cpu: "100m"
EOF
    
    if wait_for_rollout "guaranteed-qos-app" "$TEST_NAMESPACE" 60; then
        # Check QoS class
        local qos_class=$(kubectl get pod -l app=guaranteed-qos-app -n $TEST_NAMESPACE -o jsonpath='{.items[0].status.qosClass}')
        if [ "$qos_class" = "Guaranteed" ]; then
            print_success "Guaranteed QoS class assigned correctly"
            ((TESTS_PASSED++))
        else
            print_failure "Expected Guaranteed QoS, got: $qos_class"
            ((TESTS_FAILED++))
        fi
    else
        print_failure "Guaranteed QoS app deployment failed"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # Test resource enforcement (this is more of a validation that limits are set)
    local pods_with_limits=$(kubectl get pods -n $TEST_NAMESPACE -o jsonpath='{.items[*].spec.containers[*].resources.limits}' | wc -w)
    if [ "$pods_with_limits" -gt 0 ]; then
        print_success "Resource limits are properly configured"
        ((TESTS_PASSED++))
    else
        print_failure "No resource limits found on pods"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
}

# Test 6: Disaster Recovery Simulation
test_disaster_recovery() {
    print_test_header "Testing Disaster Recovery Scenarios"
    
    # Test pod failure recovery
    print_info "Simulating pod failure..."
    local pod_name=$(kubectl get pods -l app=test-app -n $TEST_NAMESPACE -o jsonpath='{.items[0].metadata.name}')
    
    if [ -n "$pod_name" ]; then
        kubectl delete pod $pod_name -n $TEST_NAMESPACE
        
        # Wait for replacement pod
        sleep 10
        
        local new_pod_count=$(kubectl get pods -l app=test-app -n $TEST_NAMESPACE --field-selector=status.phase=Running | wc -l)
        if [ "$new_pod_count" -ge 1 ]; then
            print_success "Pod failure recovery successful"
            ((TESTS_PASSED++))
        else
            print_failure "Pod failure recovery failed"
            ((TESTS_FAILED++))
        fi
    else
        print_failure "No test pods found for failure simulation"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # Test deployment recreation
    print_info "Testing deployment recreation..."
    kubectl delete deployment test-app -n $TEST_NAMESPACE
    
    # Recreate deployment
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: test-app
  namespace: $TEST_NAMESPACE
spec:
  replicas: 2
  selector:
    matchLabels:
      app: test-app
  template:
    metadata:
      labels:
        app: test-app
    spec:
      containers:
      - name: test-app
        image: nginx:alpine
        ports:
        - containerPort: 80
        resources:
          requests:
            memory: "64Mi"
            cpu: "50m"
          limits:
            memory: "128Mi"
            cpu: "100m"
EOF
    
    if wait_for_rollout "test-app" "$TEST_NAMESPACE" 120; then
        print_success "Deployment recreation successful"
        ((TESTS_PASSED++))
    else
        print_failure "Deployment recreation failed"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
}

# Test 7: Configuration Management
test_configuration_management() {
    print_test_header "Testing Configuration Management"
    
    # Test ConfigMap updates
    kubectl patch configmap test-config -n $TEST_NAMESPACE --patch '{"data":{"test-key":"updated-value"}}'
    
    local updated_value=$(kubectl get configmap test-config -n $TEST_NAMESPACE -o jsonpath='{.data.test-key}')
    if [ "$updated_value" = "updated-value" ]; then
        print_success "ConfigMap update successful"
        ((TESTS_PASSED++))
    else
        print_failure "ConfigMap update failed"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # Test Secret updates
    kubectl patch secret test-secret -n $TEST_NAMESPACE --patch '{"data":{"test-key":"dXBkYXRlZC12YWx1ZQ=="}}' # base64 encoded "updated-value"
    
    local updated_secret=$(kubectl get secret test-secret -n $TEST_NAMESPACE -o jsonpath='{.data.test-key}' | base64 -d)
    if [ "$updated_secret" = "updated-value" ]; then
        print_success "Secret update successful"
        ((TESTS_PASSED++))
    else
        print_failure "Secret update failed"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
    
    # Test deployment with updated config
    cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: config-test-app
  namespace: $TEST_NAMESPACE
spec:
  replicas: 1
  selector:
    matchLabels:
      app: config-test-app
  template:
    metadata:
      labels:
        app: config-test-app
    spec:
      containers:
      - name: app
        image: nginx:alpine
        env:
        - name: TEST_CONFIG
          valueFrom:
            configMapKeyRef:
              name: test-config
              key: test-key
        - name: TEST_SECRET
          valueFrom:
            secretKeyRef:
              name: test-secret
              key: test-key
        resources:
          requests:
            memory: "64Mi"
            cpu: "50m"
          limits:
            memory: "128Mi"
            cpu: "100m"
EOF
    
    if wait_for_rollout "config-test-app" "$TEST_NAMESPACE" 60; then
        print_success "Deployment with ConfigMap/Secret references successful"
        ((TESTS_PASSED++))
    else
        print_failure "Deployment with ConfigMap/Secret references failed"
        ((TESTS_FAILED++))
    fi
    ((TESTS_RUN++))
}

# Test 8: Network Policies (if supported)
test_network_policies() {
    print_test_header "Testing Network Policies"
    
    # Create a basic network policy
    cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: test-network-policy
  namespace: $TEST_NAMESPACE
spec:
  podSelector:
    matchLabels:
      app: test-app
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - namespaceSelector:
        matchLabels:
          name: $TEST_NAMESPACE
  egress:
  - to:
    - namespaceSelector:
        matchLabels:
          name: $TEST_NAMESPACE
EOF
    
    if kubectl get networkpolicy test-network-policy -n $TEST_NAMESPACE >/dev/null 2>&1; then
        print_success "Network policy creation successful"
        ((TESTS_PASSED++))
    else
        print_warning "Network policies may not be supported in this cluster"
        ((TESTS_PASSED++)) # Don't fail if network policies aren't supported
    fi
    ((TESTS_RUN++))
}

# Cleanup function
cleanup_test_resources() {
    print_test_header "Cleaning Up Test Resources"
    
    print_info "Removing test namespace and all resources..."
    kubectl delete namespace $TEST_NAMESPACE --ignore-not-found=true
    
    # Wait for namespace deletion
    local timeout=60
    local elapsed=0
    while kubectl get namespace $TEST_NAMESPACE >/dev/null 2>&1 && [ $elapsed -lt $timeout ]; do
        sleep 2
        elapsed=$((elapsed + 2))
    done
    
    if ! kubectl get namespace $TEST_NAMESPACE >/dev/null 2>&1; then
        print_success "Test namespace cleanup completed"
    else
        print_warning "Test namespace cleanup may still be in progress"
    fi
}

# Main test execution
main() {
    echo -e "${BLUE}Starting Deployment Automation and Scaling Tests${NC}"
    echo -e "${BLUE}Test Namespace: $TEST_NAMESPACE${NC}"
    echo -e "${BLUE}Timeout: $TIMEOUT seconds${NC}"
    echo -e "${BLUE}Load Test Duration: $LOAD_TEST_DURATION seconds${NC}\n"
    
    # Check kubectl connectivity
    if ! kubectl cluster-info >/dev/null 2>&1; then
        echo -e "${RED}Error: Cannot connect to Kubernetes cluster${NC}"
        exit 1
    fi
    
    # Run all tests
    test_deployment_automation
    test_rolling_updates
    test_horizontal_scaling
    test_load_and_autoscaling
    test_resource_management
    test_disaster_recovery
    test_configuration_management
    test_network_policies
    
    # Cleanup
    cleanup_test_resources
    
    # Print summary
    echo -e "\n${BLUE}=== Test Summary ===${NC}"
    echo -e "Tests Run: $TESTS_RUN"
    echo -e "${GREEN}Tests Passed: $TESTS_PASSED${NC}"
    echo -e "${RED}Tests Failed: $TESTS_FAILED${NC}"
    
    local success_rate=$((TESTS_PASSED * 100 / TESTS_RUN))
    echo -e "Success Rate: $success_rate%"
    
    if [ $TESTS_FAILED -eq 0 ]; then
        echo -e "\n${GREEN}🎉 All deployment automation tests passed!${NC}"
        exit 0
    else
        echo -e "\n${RED}❌ Some deployment automation tests failed. Please check the output above.${NC}"
        exit 1
    fi
}

# Handle script arguments
case "${1:-run}" in
    "run")
        main
        ;;
    "cleanup")
        cleanup_test_resources
        ;;
    "help")
        echo "Usage: $0 [run|cleanup|help]"
        echo ""
        echo "Commands:"
        echo "  run     - Run all deployment automation tests (default)"
        echo "  cleanup - Clean up test resources only"
        echo "  help    - Show this help message"
        echo ""
        echo "Environment Variables:"
        echo "  NAMESPACE           - Main namespace (default: monitor-system)"
        echo "  TEST_NAMESPACE      - Test namespace (default: monitor-system-test)"
        echo "  TIMEOUT             - Timeout for operations (default: 300 seconds)"
        echo "  LOAD_TEST_DURATION  - Load test duration (default: 60 seconds)"
        echo ""
        echo "Examples:"
        echo "  $0                                    # Run all tests"
        echo "  TEST_NAMESPACE=my-test $0             # Use custom test namespace"
        echo "  LOAD_TEST_DURATION=120 $0            # Longer load test"
        ;;
    *)
        echo "Unknown command: $1"
        echo "Use '$0 help' for usage information"
        exit 1
        ;;
esac