#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Reuse the validation suite's disposable PostgreSQL, HTTP cases, and fixture cleanup.
VERIFY_PROBLEMS=1 RUN_MAVEN="${RUN_MAVEN:-0}" exec "$root/verify-medflow-validation.sh"
