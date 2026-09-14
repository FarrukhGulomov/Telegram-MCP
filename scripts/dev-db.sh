#!/usr/bin/env bash
# Starts PostgreSQL for local development, preferring Docker and falling back to a local
# PostgreSQL install when no Docker daemon is available.
set -euo pipefail

DB_NAME="${1:-tgmcp}"
DB_USER="${TGMCP_DB_USER:-tgmcp}"
DB_PASSWORD="${TGMCP_DB_PASSWORD:-tgmcp}"
DB_PORT="${TGMCP_DB_PORT:-5432}"

if docker info >/dev/null 2>&1; then
  echo "==> Using Docker"
  docker compose up -d db
  echo "==> Waiting for PostgreSQL"
  for _ in $(seq 1 40); do
    if docker compose exec -T db pg_isready -U "$DB_USER" >/dev/null 2>&1; then
      echo "PostgreSQL is ready on localhost:${DB_PORT}"
      exit 0
    fi
    sleep 1
  done
  echo "PostgreSQL did not become ready" >&2
  exit 1
fi

echo "==> No Docker daemon; falling back to a local PostgreSQL install"
PGBIN="$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1 || true)"
if [ -z "$PGBIN" ]; then
  echo "Neither Docker nor a local PostgreSQL install was found." >&2
  echo "Install Docker, or PostgreSQL 14+ (Debian/Ubuntu: apt-get install postgresql)." >&2
  exit 1
fi

DATA_DIR="${TGMCP_PGDATA:-/var/lib/pgtest/data}"
RUN_DIR="$(dirname "$DATA_DIR")/run"
sudo_if_needed() { if [ "$(id -u)" -eq 0 ]; then "$@"; else sudo "$@"; fi; }

if [ ! -f "$DATA_DIR/PG_VERSION" ]; then
  echo "==> Initialising cluster at $DATA_DIR"
  sudo_if_needed mkdir -p "$DATA_DIR" "$RUN_DIR"
  sudo_if_needed chown -R postgres:postgres "$(dirname "$DATA_DIR")"
  sudo_if_needed chmod 700 "$DATA_DIR"
  sudo_if_needed su postgres -c "$PGBIN/initdb -D $DATA_DIR -U postgres --auth=trust -E UTF8"
fi

if ! sudo_if_needed su postgres -c "$PGBIN/pg_ctl -D $DATA_DIR status" >/dev/null 2>&1; then
  echo "==> Starting PostgreSQL on port $DB_PORT"
  sudo_if_needed su postgres -c \
    "$PGBIN/pg_ctl -D $DATA_DIR -o '-p $DB_PORT -k $RUN_DIR -c listen_addresses=127.0.0.1' \
     -l $(dirname "$DATA_DIR")/pg.log start"
  sleep 2
fi

psql -h 127.0.0.1 -p "$DB_PORT" -U postgres -tAc \
  "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'" | grep -q 1 || \
  psql -h 127.0.0.1 -p "$DB_PORT" -U postgres \
    -c "CREATE USER $DB_USER WITH PASSWORD '$DB_PASSWORD' SUPERUSER"

for db in tgmcp tgmcp_test; do
  psql -h 127.0.0.1 -p "$DB_PORT" -U postgres -tAc \
    "SELECT 1 FROM pg_database WHERE datname='$db'" | grep -q 1 || \
    psql -h 127.0.0.1 -p "$DB_PORT" -U postgres -c "CREATE DATABASE $db OWNER $DB_USER"
done

echo "PostgreSQL is ready on localhost:${DB_PORT} (databases: tgmcp, tgmcp_test)"
