#!/usr/bin/env bash
set -euo pipefail
probe=test/agent/ci_exit_probe_test.clj
if [[ -e "$probe" ]]; then
  echo "Probe path already exists: $probe" >&2
  exit 2
fi
trap 'rm -f "$probe"' EXIT
cat > "$probe" <<'CLJ'
(ns agent.ci-exit-probe-test (:require [clojure.test :refer [deftest is]]))
(deftest deliberate-failure (is false "Expected CI exit-code probe failure"))
CLJ
set +e
clojure -M:test -e "(require 'agent.test-runner) (with-redefs [agent.test-runner/test-namespaces (constantly ['agent.ci-exit-probe-test])] (agent.test-runner/-main))"
status=$?
set -e
if [[ "$status" != 1 ]]; then
  echo "Expected test failure exit 1, got $status" >&2
  exit 1
fi
echo "Test failure correctly exits 1"
