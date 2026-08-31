#!/usr/bin/env bash
set -euo pipefail

# Inspect every public entrypoint without setup/VU/teardown or target credentials.
main() {
  local root engine version entry scenario scenarios
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  cd "$root"
  source tools/k6/runtime.env
  engine="${1:-docker}"
  local -a runner
  case "$engine" in
    docker)
      docker pull "$K6_TESTED_IMAGE"
      runner=(docker run --rm --network none --read-only --cap-drop ALL
        --security-opt no-new-privileges -v "$root:/workspace:ro" -w /workspace
        "$K6_TESTED_IMAGE")
      ;;
    local) runner=("${K6_BINARY:-k6}") ;;
    *) echo 'Usage: check-runtime.sh [docker|local]' >&2; return 2 ;;
  esac
  version="$("${runner[@]}" version)"
  if [[ "$version" != "k6 v${K6_TESTED_VERSION} "* ]]; then
    echo "Expected the tested k6 v${K6_TESTED_VERSION}; use the pinned container or matching binary" >&2
    return 1
  fi
  for entry in tools/k6/file-query.js tools/k6/chunk-upload.js tools/k6/direct-path.js \
    tools/k6/scenarios/core-mixed.js tools/k6/suites/ci-smoke.js \
    tools/k6/suites/local-smoke.js tools/k6/suites/local-load.js; do
    scenarios='all file-query chunk-upload core-mixed direct-path'
    if [[ "$entry" == tools/k6/suites/local-load.js ]]; then
      scenarios='all file-query chunk-upload direct-path'
    fi
    for scenario in $scenarios; do
      "${runner[@]}" inspect --execution-requirements \
        -e BASE_URL=http://127.0.0.1:1/api/v1 -e TENANT_ID=1 \
        -e USERNAME=synthetic-fixture -e PASSWORD=synthetic-fixture \
        -e RUN_ID=runtime-check -e K6_SCENARIO="$scenario" -e CI_INCLUDE_CHUNK=true \
        "$entry" >/dev/null
    done
    echo "Initialized: $entry ($scenarios)"
  done
  # This script has only local fixture assertions: no HTTP imports or application calls.
  "${runner[@]}" run --no-usage-report tools/k6/tests/fixture-runtime.js
}

main "$@"
