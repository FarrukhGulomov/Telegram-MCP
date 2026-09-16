#!/usr/bin/env bash
# Prints a set of freshly generated keys for a .env file. Nothing is written to disk.
set -euo pipefail
echo "TGMCP_SESSION_ENCRYPTION_KEY=$(openssl rand -base64 32)"
echo "TGMCP_CURSOR_SIGNING_KEY=$(openssl rand -base64 32)"
echo "TGMCP_SESSION_KEY_ID=$(date +%Y%m)-1"
# Security-audit finding F18: a persistent OAuth JWT signing key. Base64-encoded PKCS8 RSA
# private key; without it, the authorization server falls back to a fresh ephemeral key on
# every restart.
echo "TGMCP_OAUTH_RSA_PRIVATE_KEY=$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 -w0)"
