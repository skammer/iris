#!/usr/bin/env bash

set -euo pipefail

forbidden_pattern='agent\.tailscale|100\.81\.169\.122|/Users/example|/home/example|api\.neuraldeep\.ru|git\.anton\.tail\.staging\.cat|UTC'

if git grep -nE "${forbidden_pattern}" -- . ':!scripts/check-public-tree.sh'; then
  printf 'error: tracked files contain private release metadata\n' >&2
  exit 1
fi

if git grep -nF 'name: Test User' -- .; then
  printf 'error: tracked fixtures contain personal profile data\n' >&2
  exit 1
fi

printf 'tracked public-release metadata check passed\n'
