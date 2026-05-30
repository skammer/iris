#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf '%s\n' \
    "Usage: scripts/iris-isolated.sh [iris args...]" \
    "" \
    "Runs Iris itself inside macOS Seatbelt or Linux Bubblewrap." \
    "Default reads: all host files. Default writes/deletes: current directory and ~/.config/iris only." \
    "" \
    "Build first if no jar exists:" \
    "  clojure -T:uberjar uberjar" \
    "" \
    "Options via env:" \
    "  IRIS_SANDBOX=seatbelt|bubblewrap" \
    "  IRIS_JAR=target/iris.jar" \
    "  JAVA_CMD=/path/to/java"
}

canonical_dir() {
  cd "$1" && pwd -P
}

canonical_file() {
  local dir
  dir="$(dirname "$1")"
  printf '%s/%s\n' "$(canonical_dir "$dir")" "$(basename "$1")"
}

resolve_file() {
  local path="$1"
  while [[ -L "$path" ]]; do
    local dir target
    dir="$(dirname "$path")"
    target="$(readlink "$path")"
    case "$target" in
      /*) path="$target" ;;
      *) path="$dir/$target" ;;
    esac
  done
  canonical_file "$path"
}

find_iris_jar() {
  if [[ -n "${IRIS_JAR:-}" ]]; then
    canonical_file "$IRIS_JAR"
    return
  fi

  local jar
  jar="target/iris.jar"
  if [[ ! -f "$jar" ]]; then
    jar="$(find target -maxdepth 1 -type f -name 'iris-*.jar' -print 2>/dev/null | sort | tail -n 1 || true)"
  fi
  if [[ -z "$jar" ]]; then
    printf '%s\n' "No Iris jar found under target/." >&2
    printf '%s\n' "Run: clojure -T:uberjar uberjar" >&2
    exit 1
  fi
  canonical_file "$jar"
}

find_java() {
  if [[ -n "${JAVA_CMD:-}" ]]; then
    printf '%s\n' "$JAVA_CMD"
  elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    printf '%s\n' "$JAVA_HOME/bin/java"
  elif [[ -x /opt/homebrew/opt/openjdk/bin/java ]]; then
    printf '%s\n' /opt/homebrew/opt/openjdk/bin/java
  elif [[ -x /usr/local/opt/openjdk/bin/java ]]; then
    printf '%s\n' /usr/local/opt/openjdk/bin/java
  elif [[ "$(uname -s)" == "Darwin" && -x /usr/libexec/java_home ]]; then
    local java_home
    java_home="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [[ -n "$java_home" && -x "$java_home/bin/java" ]]; then
      printf '%s\n' "$java_home/bin/java"
    else
      command -v java
    fi
  else
    command -v java
  fi
}

config_dir() {
  if [[ -n "${IRIS_CONFIG_DIR:-}" ]]; then
    printf '%s\n' "$IRIS_CONFIG_DIR"
  elif [[ -n "${XDG_CONFIG_HOME:-}" ]]; then
    printf '%s/iris\n' "$XDG_CONFIG_HOME"
  else
    printf '%s/.config/iris\n' "$HOME"
  fi
}

sb_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

project_dir="$(canonical_dir ".")"
cfg_dir="$(config_dir)"
mkdir -p "$cfg_dir/tmp"
cfg_dir="$(canonical_dir "$cfg_dir")"
jar_path="$(find_iris_jar)"
if [[ ! -f "$jar_path" ]]; then
  printf '%s\n' "Iris jar not found: $jar_path" >&2
  exit 1
fi
java_cmd="$(resolve_file "$(find_java)")"

sandbox="${IRIS_SANDBOX:-auto}"
if [[ "$sandbox" == "auto" ]]; then
  case "$(uname -s)" in
    Darwin) sandbox="seatbelt" ;;
    Linux) sandbox="bubblewrap" ;;
    *) printf '%s\n' "Unsupported OS. Set IRIS_SANDBOX=seatbelt or bubblewrap." >&2; exit 1 ;;
  esac
fi

case "$sandbox" in
  seatbelt)
    if ! command -v sandbox-exec >/dev/null 2>&1; then
      printf '%s\n' "sandbox-exec not found" >&2
      exit 1
    fi

    profile="$(mktemp "$cfg_dir/iris-seatbelt.XXXXXX")"
    trap 'rm -f "$profile"' EXIT
    cat >"$profile" <<EOF
(version 1)
(import "system.sb")
(allow default)
(deny file-write*)
(allow process*)
(allow signal)
(allow network*)
(allow file-write*
  (subpath "$(sb_escape "$project_dir")")
  (subpath "$(sb_escape "$cfg_dir")"))
EOF
    export IRIS_CONFIG_DIR="$cfg_dir"
    export HOME="$cfg_dir"
    export TMPDIR="$cfg_dir/tmp"
    exec sandbox-exec -f "$profile" "$java_cmd" "-Duser.home=$cfg_dir" "-Djava.io.tmpdir=$cfg_dir/tmp" -jar "$jar_path" "$@"
    ;;

  bubblewrap)
    if ! command -v bwrap >/dev/null 2>&1; then
      printf '%s\n' "bwrap not found" >&2
      exit 1
    fi
    if [[ -r /proc/sys/kernel/apparmor_restrict_unprivileged_userns ]] \
      && [[ "$(cat /proc/sys/kernel/apparmor_restrict_unprivileged_userns)" == "1" ]] \
      && [[ ! -u "$(command -v bwrap)" ]]; then
      printf '%s\n' "bubblewrap blocked: AppArmor restricts unprivileged user namespaces." >&2
      printf '%s\n' "Temporary test: sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0" >&2
      printf '%s\n' "Restore: sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=1" >&2
      exit 1
    fi

    exec bwrap \
      --die-with-parent \
      --new-session \
      --ro-bind / / \
      --proc /proc \
      --dev /dev \
      --bind "$project_dir" "$project_dir" \
      --bind "$cfg_dir" "$cfg_dir" \
      --setenv IRIS_CONFIG_DIR "$cfg_dir" \
      --setenv HOME "$cfg_dir" \
      --chdir "$project_dir" \
      -- "$java_cmd" -Duser.home="$cfg_dir" -Djava.io.tmpdir="$cfg_dir/tmp" -jar "$jar_path" "$@"
    ;;

  *)
    printf '%s\n' "Unknown IRIS_SANDBOX: $sandbox" >&2
    exit 1
    ;;
esac
