#!/usr/bin/env bash

set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

jar_path="${IRIS_DEPLOY_JAR:-target/iris.jar}"
remote_host="${IRIS_DEPLOY_HOST:-}"
remote_user="${IRIS_DEPLOY_USER:-}"
remote_dir="${IRIS_DEPLOY_DIR:-.local/bin}"
remote_jar_name="${IRIS_DEPLOY_JAR_NAME:-iris.jar}"
remote_bin_name="${IRIS_DEPLOY_BIN:-iris}"
remote_config_dir="${IRIS_DEPLOY_CONFIG_DIR:-.config/iris}"
remote_port="${IRIS_DEPLOY_PORT:-22}"
ssh_opts="${IRIS_DEPLOY_SSH_OPTS:-}"

if [[ -z "${remote_host}" ]]; then
  printf 'IRIS_DEPLOY_HOST is required\n' >&2
  exit 2
fi

remote="${remote_host}"
if [[ -n "${remote_user}" ]]; then
  remote="${remote_user}@${remote_host}"
fi

cd "${repo_dir}"

printf 'Building JAR: %s\n' "${jar_path}"
clojure -T:uberjar uberjar :jar "\"${jar_path}\""

ssh_args=(-p "${remote_port}" -o ClearAllForwardings=yes)
scp_args=(-P "${remote_port}" -o ClearAllForwardings=yes)

if [[ -n "${ssh_opts}" ]]; then
  read -r -a extra_ssh_args <<< "${ssh_opts}"
  ssh_args+=("${extra_ssh_args[@]}")
  scp_args+=("${extra_ssh_args[@]}")
fi

printf -v remote_dir_quoted '%q' "${remote_dir}"
mkdir_command="mkdir -p ${remote_dir_quoted}"

printf 'Creating remote dir: %s:%s\n' "${remote}" "${remote_dir}"
ssh "${ssh_args[@]}" "${remote}" "${mkdir_command}"
printf 'Uploading JAR: %s -> %s:%s/%s\n' "${jar_path}" "${remote}" "${remote_dir}" "${remote_jar_name}"
scp "${scp_args[@]}" "${jar_path}" "${remote}:${remote_dir}/${remote_jar_name}"

remote_skills_dir="${remote_config_dir%/}/skills"
printf -v remote_skills_dir_quoted '%q' "${remote_skills_dir}"
if [[ -d "skills" ]]; then
  printf 'Uploading bundled skills: skills/ -> %s:%s/\n' "${remote}" "${remote_skills_dir}"
  ssh "${ssh_args[@]}" "${remote}" "mkdir -p ${remote_skills_dir_quoted}"
  scp "${scp_args[@]}" -r skills/. "${remote}:${remote_skills_dir}/"
fi

remote_docs_dir="${remote_config_dir%/}/docs"
printf -v remote_docs_dir_quoted '%q' "${remote_docs_dir}"
if [[ -d "docs" ]]; then
  printf 'Uploading operator docs: docs/ -> %s:%s/\n' "${remote}" "${remote_docs_dir}"
  ssh "${ssh_args[@]}" "${remote}" "mkdir -p ${remote_docs_dir_quoted}"
  scp "${scp_args[@]}" -r docs/. "${remote}:${remote_docs_dir}/"
fi

if [[ -f "obsidian/guides/cron-jobs.md" ]]; then
  printf 'Uploading cron guide: obsidian/guides/cron-jobs.md -> %s:%s/cron-jobs.md\n' "${remote}" "${remote_docs_dir}"
  scp "${scp_args[@]}" obsidian/guides/cron-jobs.md "${remote}:${remote_docs_dir}/cron-jobs.md"
fi

printf 'Installing launcher: %s:%s/%s\n' "${remote}" "${remote_dir}" "${remote_bin_name}"
ssh "${ssh_args[@]}" "${remote}" "bash -s" -- "${remote_dir}" "${remote_jar_name}" "${remote_bin_name}" <<'REMOTE_SCRIPT'
set -euo pipefail

remote_dir="$1"
remote_jar_name="$2"
remote_bin_name="$3"
jar_path="${remote_dir%/}/${remote_jar_name}"
bin_path="${remote_dir%/}/${remote_bin_name}"

{
  printf '#!/usr/bin/env bash\n'
  printf 'set -euo pipefail\n'
  printf 'script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"\n'
  printf 'exec java -jar "${script_dir}/%s" "$@"\n' "${remote_jar_name}"
} > "${bin_path}"

chmod 0644 "${jar_path}"
chmod 0755 "${bin_path}"
REMOTE_SCRIPT

printf 'Uploaded %s to %s:%s/%s\n' "${jar_path}" "${remote}" "${remote_dir}" "${remote_jar_name}"
printf 'Run remotely: %s/%s \"prompt text\"\n' "${remote_dir}" "${remote_bin_name}"
