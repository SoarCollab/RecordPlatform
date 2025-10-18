#!/bin/bash

# Comprehensive Test Runner for Monitor System Deployment
# This script runs all deployment and infrastructure tests in the correct order

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Configuration
NAMESPACE="${NAMESPACE:-monitor-system}"
TIMEOUT="${TIMEOUT:-300}"
PARALLEL_TESTS="${PARALLEL_TESTS:-false}"
SKIP_CLEANUP="${SKIP_CLEANUP:-false}"
TEST_REPORT_DIR="${TEST_REPORT_DIR:-./test-reports}"

# Test suite configuration
INFRASTRUCTURE_TESTS="true"
DEPLOYMENT_TESTS="true"
SECURITY_TESTS="true"
PERFORMANCE_TESTS="${PERFORMANCE_TESTS:-false}"

# Test results
TOTAL_SUITES=0
PASSED_SUITES=0
FAILED_SUITES=0

# Function to print colored output
print_banner() {
    echo -e "${PURPLE}"
    echo "╔══════════════════════════════════════════════════════════════════════════════╗"
    echo "║                    Monitor System Deployment Test Suite                      ║"
    echo "╚══════════════════════════════════════════════════════════════════════════════╝"
    echo -e "${NC}"
}

print_section() {
    echo -e "\n${CYAN}▶ $1${NC}"
    echo -e "${CYAN}$(printf '%.0s─' {1..80})${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_failure() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

# Function to run a test suite
run_test_suite() {
    local suite_name="$1"
    local test_script="$2"
    local description="$3"
    
    ((TOTAL_SUITES++))
    
    print_section "$suite_name"
    print_info "$description"
    
    local start_time=$(date +%s)
    local log_file="$TEST_REPORT_DIR/${suite_name,,}-$(date +%Y%m%d-%H%M%S).log"
    
    # Create test report directory
    mkdir -p "$TEST_REPORT_DIR"
    
    # Run the test suite
    if bash "$test_script" 2>&1 | tee "$log_file"; then
        local end_time=$(date +%s)
        local duration=$((end_time - start_time))
        
        print_success "$suite_name completed successfully (${duration}s)"
        ((PASSED_SUITES++))
        
        # Extract test statistics from log
        local tests_run=$(grep "Tests Run:" "$log_file" | tail -1 | awk '{print $3}' || echo "N/A")
        local tests_passed=$(grep "Tests Passed:" "$log_file" | tail -1 | awk '{print $3}' || echo "N/A")
        local tests_failed=$(grep "Tests Failed:" "$log_file" | tail -1 | awk '{print $3}' || echo "N/A")
        
        echo "  Tests: $tests_run run, $tests_passed passed, $tests_failed failed"
        
        return 0
    else
        local end_time=$(date +%s)
        local duration=$((end_time - start_time))
        
        print_failure "$suite_name failed (${duration}s)"
        ((FAILED_SUITES++))
        
        echo "  Log file: $log_file"
        return 1
    fi
}

# Function to check prerequisites
check_prerequisites() {
    print_section "Checking Prerequisites"
    
    # Check kubectl
    if ! command -v kubectl >/dev/null 2>&1; then
        print_failure "kubectl is not installed or not in PATH"
        exit 1
    fi
    print_success "kubectl is available"
    
    # Check cluster connectivity
    if ! kubectl cluster-info >/dev/null 2>&1; then
        print_failure "Cannot connect to Kubernetes cluster"
        exit 1
    fi
    print_success "Kubernetes cluster is accessible"
    
    # Check namespace exists
    if ! kubectl get namespace "$NAMESPACE" >/dev/null 2>&1; then
        print_warning "Namespace '$NAMESPACE' does not exist"
        print_info "Some tests may fail or be skipped"
    else
        print_success "Target namespace '$NAMESPACE' exists"
    fi
    
    # Check for optional tools
    if command -v istioctl >/dev/null 2>&1; then
        print_success "istioctl is available (service mesh tests enabled)"
    else
        print_info "istioctl not found (some service mesh tests will be skipped)"
    fi
    
    # Check for metrics server (for HPA tests)
    if kubectl get apiservice v1beta1.metrics.k8s.io >/dev/null 2>&1; then
        print_success "Metrics server is available (HPA tests enabled)"
    else
        print_info "Metrics server not found (some HPA tests may be limited)"
    fi
    
    # Create test report directory
    mkdir -p "$TEST_REPORT_DIR"
    print_success "Test report directory created: $TEST_REPORT_DIR"
}

# Function to run infrastructure tests
run_infrastructure_tests() {
    if [ "$INFRASTRUCTURE_TESTS" = "true" ]; then
        run_test_suite "Infrastructure Tests" \
                      "./infrastructure-tests.sh" \
                      "Validates Kubernetes infrastructure deployment and basic functionality"
    else
        print_info "Infrastructure tests skipped (disabled)"
    fi
}

# Function to run deployment automation tests
run_deployment_tests() {
    if [ "$DEPLOYMENT_TESTS" = "true" ]; then
        run_test_suite "Deployment Automation Tests" \
                      "./deployment-automation-tests.sh" \
                      "Tests deployment automation, scaling, and rollback procedures"
    else
        print_info "Deployment tests skipped (disabled)"
    fi
}

# Function to run security and service mesh tests
run_security_tests() {
    if [ "$SECURITY_TESTS" = "true" ]; then
        run_test_suite "Service Mesh & Security Tests" \
                      "./service-mesh-security-tests.sh" \
                      "Validates service mesh configuration, mTLS, and security policies"
    else
        print_info "Security tests skipped (disabled)"
    fi
}

# Function to run performance tests
run_performance_tests() {
    if [ "$PERFORMANCE_TESTS" = "true" ]; then
        print_section "Performance Tests"
        print_info "Running performance validation tests..."
        
        # Basic performance validation
        local start_time=$(date +%s)
        
        # Check resource usage
        if kubectl top nodes >/dev/null 2>&1; then
            print_info "Node resource usage:"
            kubectl top nodes
            
            print_info "Pod resource usage in $NAMESPACE:"
            kubectl top pods -n "$NAMESPACE" 2>/dev/null || print_warning "Cannot get pod metrics"
        else
            print_warning "Cannot get resource metrics (metrics-server may not be available)"
        fi
        
        # Check HPA status
        local hpa_count=$(kubectl get hpa -n "$NAMESPACE" --no-headers 2>/dev/null | wc -l)
        if [ "$hpa_count" -gt 0 ]; then
            print_info "HPA status in $NAMESPACE:"
            kubectl get hpa -n "$NAMESPACE"
        else
            print_info "No HPA resources found in $NAMESPACE"
        fi
        
        # Basic load test (if enabled)
        if [ "${LOAD_TEST_ENABLED:-false}" = "true" ]; then
            print_info "Running basic load test..."
            # This would typically run JMeter or similar load testing tool
            # For now, we'll just simulate a basic connectivity test
            
            local api_gateway_pod=$(kubectl get pods -n "$NAMESPACE" -l app=monitor-api-gateway -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
            if [ -n "$api_gateway_pod" ]; then
                # Port forward and test
                kubectl port-forward -n "$NAMESPACE" "$api_gateway_pod" 8080:8080 &
                local pf_pid=$!
                sleep 5
                
                # Simple load test
                for i in {1..10}; do
                    curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health || true
                done
                
                kill $pf_pid 2>/dev/null || true
                print_success "Basic load test completed"
            else
                print_warning "No API Gateway pod found for load testing"
            fi
        fi
        
        local end_time=$(date +%s)
        local duration=$((end_time - start_time))
        
        print_success "Performance tests completed (${duration}s)"
        ((TOTAL_SUITES++))
        ((PASSED_SUITES++))
    else
        print_info "Performance tests skipped (disabled)"
    fi
}

# Function to generate test report
generate_test_report() {
    print_section "Generating Test Report"
    
    local report_file="$TEST_REPORT_DIR/test-summary-$(date +%Y%m%d-%H%M%S).md"
    
    cat > "$report_file" << EOF
# Monitor System Deployment Test Report

**Generated:** $(date)
**Namespace:** $NAMESPACE
**Test Environment:** $(kubectl config current-context)

## Test Summary

- **Total Test Suites:** $TOTAL_SUITES
- **Passed:** $PASSED_SUITES
- **Failed:** $FAILED_SUITES
- **Success Rate:** $(( PASSED_SUITES * 100 / TOTAL_SUITES ))%

## Test Configuration

- Infrastructure Tests: $INFRASTRUCTURE_TESTS
- Deployment Tests: $DEPLOYMENT_TESTS
- Security Tests: $SECURITY_TESTS
- Performance Tests: $PERFORMANCE_TESTS
- Parallel Execution: $PARALLEL_TESTS
- Timeout: ${TIMEOUT}s

## Environment Information

### Cluster Information
\`\`\`
$(kubectl cluster-info)
\`\`\`

### Node Information
\`\`\`
$(kubectl get nodes -o wide)
\`\`\`

### Namespace Resources
\`\`\`
$(kubectl get all -n "$NAMESPACE" 2>/dev/null || echo "Namespace not found or empty")
\`\`\`

## Test Logs

Individual test logs are available in the following files:

EOF

    # Add log file references
    for log_file in "$TEST_REPORT_DIR"/*.log; do
        if [ -f "$log_file" ]; then
            echo "- $(basename "$log_file")" >> "$report_file"
        fi
    done
    
    cat >> "$report_file" << EOF

## Recommendations

EOF

    # Add recommendations based on test results
    if [ $FAILED_SUITES -gt 0 ]; then
        cat >> "$report_file" << EOF
### Issues Found

$FAILED_SUITES test suite(s) failed. Please review the individual test logs for detailed error information.

### Next Steps

1. Review failed test logs for specific error messages
2. Check Kubernetes cluster resources and configuration
3. Verify all required dependencies are installed and configured
4. Re-run failed tests after addressing issues

EOF
    else
        cat >> "$report_file" << EOF
### All Tests Passed

All test suites completed successfully. The Monitor System deployment appears to be functioning correctly.

### Maintenance Recommendations

1. Regularly run these tests to ensure continued system health
2. Monitor resource usage and scaling behavior
3. Keep security configurations up to date
4. Review and update test scenarios as the system evolves

EOF
    fi
    
    print_success "Test report generated: $report_file"
}

# Function to cleanup test resources
cleanup_test_resources() {
    if [ "$SKIP_CLEANUP" = "false" ]; then
        print_section "Cleaning Up Test Resources"
        
        # Clean up any test namespaces that might have been created
        local test_namespaces=("monitor-system-test" "monitor-system-mesh-test")
        
        for ns in "${test_namespaces[@]}"; do
            if kubectl get namespace "$ns" >/dev/null 2>&1; then
                print_info "Cleaning up test namespace: $ns"
                kubectl delete namespace "$ns" --ignore-not-found=true
            fi
        done
        
        # Kill any remaining port-forward processes
        pkill -f "kubectl port-forward" 2>/dev/null || true
        
        print_success "Cleanup completed"
    else
        print_info "Cleanup skipped (SKIP_CLEANUP=true)"
    fi
}

# Function to run tests in parallel (experimental)
run_tests_parallel() {
    print_info "Running tests in parallel mode (experimental)"
    
    local pids=()
    
    # Start infrastructure tests
    if [ "$INFRASTRUCTURE_TESTS" = "true" ]; then
        (run_test_suite "Infrastructure Tests" "./infrastructure-tests.sh" "Infrastructure validation") &
        pids+=($!)
    fi
    
    # Start deployment tests (after a delay to avoid conflicts)
    if [ "$DEPLOYMENT_TESTS" = "true" ]; then
        sleep 30
        (run_test_suite "Deployment Tests" "./deployment-automation-tests.sh" "Deployment automation") &
        pids+=($!)
    fi
    
    # Start security tests (after infrastructure is likely done)
    if [ "$SECURITY_TESTS" = "true" ]; then
        sleep 60
        (run_test_suite "Security Tests" "./service-mesh-security-tests.sh" "Security validation") &
        pids+=($!)
    fi
    
    # Wait for all tests to complete
    local failed_tests=0
    for pid in "${pids[@]}"; do
        if ! wait $pid; then
            ((failed_tests++))
        fi
    done
    
    return $failed_tests
}

# Main execution function
main() {
    print_banner
    
    print_info "Monitor System Deployment Test Suite"
    print_info "Namespace: $NAMESPACE"
    print_info "Timeout: ${TIMEOUT}s"
    print_info "Parallel Tests: $PARALLEL_TESTS"
    print_info "Report Directory: $TEST_REPORT_DIR"
    echo
    
    # Check prerequisites
    check_prerequisites
    
    # Run tests
    if [ "$PARALLEL_TESTS" = "true" ]; then
        run_tests_parallel
    else
        # Run tests sequentially
        run_infrastructure_tests
        run_deployment_tests
        run_security_tests
        run_performance_tests
    fi
    
    # Generate report
    generate_test_report
    
    # Cleanup
    cleanup_test_resources
    
    # Print final summary
    print_section "Final Summary"
    echo -e "Total Test Suites: $TOTAL_SUITES"
    echo -e "${GREEN}Passed: $PASSED_SUITES${NC}"
    echo -e "${RED}Failed: $FAILED_SUITES${NC}"
    
    local success_rate=$(( PASSED_SUITES * 100 / TOTAL_SUITES ))
    echo -e "Success Rate: $success_rate%"
    
    if [ $FAILED_SUITES -eq 0 ]; then
        echo -e "\n${GREEN}🎉 All test suites completed successfully!${NC}"
        echo -e "${GREEN}The Monitor System deployment is validated and ready for use.${NC}"
        exit 0
    else
        echo -e "\n${RED}❌ $FAILED_SUITES test suite(s) failed.${NC}"
        echo -e "${RED}Please review the test reports and address any issues.${NC}"
        exit 1
    fi
}

# Handle script arguments
case "${1:-run}" in
    "run")
        main
        ;;
    "infrastructure")
        DEPLOYMENT_TESTS="false"
        SECURITY_TESTS="false"
        PERFORMANCE_TESTS="false"
        main
        ;;
    "deployment")
        INFRASTRUCTURE_TESTS="false"
        SECURITY_TESTS="false"
        PERFORMANCE_TESTS="false"
        main
        ;;
    "security")
        INFRASTRUCTURE_TESTS="false"
        DEPLOYMENT_TESTS="false"
        PERFORMANCE_TESTS="false"
        main
        ;;
    "performance")
        INFRASTRUCTURE_TESTS="false"
        DEPLOYMENT_TESTS="false"
        SECURITY_TESTS="false"
        PERFORMANCE_TESTS="true"
        main
        ;;
    "help")
        echo "Usage: $0 [run|infrastructure|deployment|security|performance|help]"
        echo ""
        echo "Commands:"
        echo "  run            - Run all test suites (default)"
        echo "  infrastructure - Run only infrastructure tests"
        echo "  deployment     - Run only deployment automation tests"
        echo "  security       - Run only security and service mesh tests"
        echo "  performance    - Run only performance tests"
        echo "  help           - Show this help message"
        echo ""
        echo "Environment Variables:"
        echo "  NAMESPACE          - Kubernetes namespace to test (default: monitor-system)"
        echo "  TIMEOUT            - Timeout for operations (default: 300 seconds)"
        echo "  PARALLEL_TESTS     - Run tests in parallel (default: false)"
        echo "  SKIP_CLEANUP       - Skip cleanup of test resources (default: false)"
        echo "  TEST_REPORT_DIR    - Directory for test reports (default: ./test-reports)"
        echo "  PERFORMANCE_TESTS  - Enable performance tests (default: false)"
        echo "  LOAD_TEST_ENABLED  - Enable load testing (default: false)"
        echo ""
        echo "Examples:"
        echo "  $0                                    # Run all tests"
        echo "  NAMESPACE=monitor-system-prod $0      # Test production namespace"
        echo "  PARALLEL_TESTS=true $0                # Run tests in parallel"
        echo "  PERFORMANCE_TESTS=true $0             # Include performance tests"
        echo "  $0 infrastructure                     # Run only infrastructure tests"
        ;;
    *)
        echo "Unknown command: $1"
        echo "Use '$0 help' for usage information"
        exit 1
        ;;
esac