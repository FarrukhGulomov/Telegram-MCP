#!/usr/bin/env bash
# Prints a set of freshly generated keys for a .env file. Nothing is written to disk.
set -euo pipefail
echo "TGMCP_SESSION_ENCRYPTION_KEY=$(openssl rand -base64 32)"
echo "TGMCP_CURSOR_SIGNING_KEY=$(openssl rand -base64 32)"
echo "TGMCP_SESSION_KEY_ID=$(date +%Y%m)-1"
