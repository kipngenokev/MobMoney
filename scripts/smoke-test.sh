#!/usr/bin/env bash
# Quick end-to-end smoke test against a running transfer-service (default :8080).
# Demonstrates login, a transfer, and idempotent replay.
# Usage: API=http://localhost:8080 ./scripts/smoke-test.sh
set -euo pipefail
API="${API:-http://localhost:8080}"

echo "==> Logging in as alice"
TOKEN=$(curl -s -X POST "$API/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"Password123!"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "Login failed"; exit 1; }
echo "    got token (${#TOKEN} chars)"

KEY=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "key-$RANDOM$RANDOM")
BODY='{"sourceAccountNumber":"ACC-ALICE-001","destinationAccountNumber":"ACC-BOB-001","amount":"25.00","currency":"USD","narrative":"smoke"}'

echo "==> First transfer (Idempotency-Key: $KEY)"
R1=$(curl -s -X POST "$API/api/transfers" -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "$BODY")
echo "    $R1"

echo "==> Replay with the SAME key (must not move money again)"
R2=$(curl -s -X POST "$API/api/transfers" -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "$BODY")
echo "    $R2"

REF1=$(echo "$R1" | sed -n 's/.*"reference":"\([^"]*\)".*/\1/p')
REF2=$(echo "$R2" | sed -n 's/.*"reference":"\([^"]*\)".*/\1/p')
if [ "$REF1" = "$REF2" ] && [ -n "$REF1" ]; then
  echo "==> PASS: replay returned the same reference ($REF1)"
else
  echo "==> FAIL: references differ ('$REF1' vs '$REF2')"; exit 1
fi

echo "==> Alice's balance:"
curl -s "$API/api/accounts/ACC-ALICE-001" -H "Authorization: Bearer $TOKEN"
echo
