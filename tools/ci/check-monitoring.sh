#!/usr/bin/env bash
# Validate monitored expressions with a pinned real Prometheus engine, offline.
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd -- "$SCRIPT_DIR/../.." && pwd)
PROMETHEUS_IMAGE=prom/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0

# Mount only the repository read-only; no host socket, credentials, or service network.
run_promtool() {
    docker run --rm --network none --entrypoint /bin/promtool \
        --mount "type=bind,src=$PROJECT_DIR,dst=/workspace,readonly" \
        --workdir /workspace "$PROMETHEUS_IMAGE" "$@"
}

run_promtool check rules config/prometheus/recording-rules.yml config/prometheus/alerting-rules.yml
run_promtool test rules config/prometheus/tests/slo-rules.test.yml
