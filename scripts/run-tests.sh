#!/usr/bin/env bash
# Runs the full test suite against a real PostgreSQL (the suite needs tsvector search and
# row-value keyset pagination, so an in-memory database would not exercise the real thing).
set -euo pipefail
cd "$(dirname "$0")/.."

./scripts/dev-db.sh

export TGMCP_TEST_DB_URL="${TGMCP_TEST_DB_URL:-jdbc:postgresql://localhost:5432/tgmcp_test}"
export TGMCP_TEST_DB_USER="${TGMCP_TEST_DB_USER:-tgmcp}"
export TGMCP_TEST_DB_PASSWORD="${TGMCP_TEST_DB_PASSWORD:-tgmcp}"

exec mvn -B test
