#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf '%s\n' \
    "Usage: scripts/iris-isolated-rebuild.sh [iris args...]" \
    "" \
    "Builds a fresh Iris jar, then runs scripts/iris-isolated.sh." \
    "" \
    "Options via env:" \
    "  IRIS_JAR=target/iris.jar" \
    "  IRIS_BUILD_CMD='clojure -T:uberjar uberjar'"
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
project_dir="$(cd "$script_dir/.." && pwd -P)"
jar_path="${IRIS_JAR:-target/iris.jar}"
build_cmd="${IRIS_BUILD_CMD:-clojure -T:uberjar uberjar}"

cd "$project_dir"
printf 'Building fresh Iris jar: %s\n' "$jar_path"
# shellcheck disable=SC2086
$build_cmd :jar "\"$jar_path\""

exec "$script_dir/iris-isolated.sh" "$@"
