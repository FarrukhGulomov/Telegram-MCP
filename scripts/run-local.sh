#!/usr/bin/env bash
# Builds and runs the application against a local PostgreSQL, in fully synthetic demo mode.
set -euo pipefail
cd "$(dirname "$0")/.."

./scripts/dev-db.sh

export TGMCP_DB_URL="${TGMCP_DB_URL:-jdbc:postgresql://localhost:5432/tgmcp}"
export TGMCP_DB_USER="${TGMCP_DB_USER:-tgmcp}"
export TGMCP_DB_PASSWORD="${TGMCP_DB_PASSWORD:-tgmcp}"
export TGMCP_TELEGRAM_ADAPTER="${TGMCP_TELEGRAM_ADAPTER:-MOCK}"

echo "==> Building"
mvn -B -q -DskipTests package

echo "==> Starting on http://localhost:${TGMCP_PORT:-8080}"
exec java -jar target/telegram-consent-mcp-*.jar
