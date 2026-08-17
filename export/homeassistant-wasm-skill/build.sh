#!/usr/bin/env bash

set -euo pipefail

bundle_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
builder_home="$(cd && pwd -P)"
unit_separator=$'\x1f'
encoded_rustflags="--remap-path-prefix=${bundle_dir}=.${unit_separator}--remap-path-prefix=${builder_home}=."
target_wasm="${bundle_dir}/target/wasm32-wasip1/release/homeassistant-wasm-skill.wasm"
bundle_wasm="${bundle_dir}/module.wasm"

cd "${bundle_dir}"
CARGO_ENCODED_RUSTFLAGS="${encoded_rustflags}" cargo build --release --target wasm32-wasip1
cp "${target_wasm}" "${bundle_wasm}"

if strings "${bundle_wasm}" | grep -F -e "${bundle_dir}" -e "${builder_home}/.cargo" -e "${builder_home}/.rustup" >/dev/null; then
  printf 'error: module.wasm contains local build paths\n' >&2
  exit 1
fi

printf 'built %s without local build paths\n' "${bundle_wasm}"
