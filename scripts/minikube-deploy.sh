#!/usr/bin/env bash
# Build the images straight into Minikube's Docker daemon and deploy the stack.
# Usage: ./scripts/minikube-deploy.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> Ensuring Minikube is running"
minikube status >/dev/null 2>&1 || minikube start

echo "==> Pointing docker at Minikube's daemon"
eval "$(minikube docker-env)"

echo "==> Building images (into Minikube)"
docker build -t mobmoney/transfer-service:latest      backend/transfer-service
docker build -t mobmoney/partner-bank-service:latest  backend/partner-bank-service
docker build -t mobmoney/frontend:latest              frontend

echo "==> Applying manifests"
kubectl apply -f k8s/

echo "==> Waiting for rollouts"
kubectl -n mobmoney rollout status deploy/mysql --timeout=180s
kubectl -n mobmoney rollout status deploy/partner-bank-service --timeout=180s
kubectl -n mobmoney rollout status deploy/transfer-service --timeout=180s
kubectl -n mobmoney rollout status deploy/frontend --timeout=120s

cat <<'EOF'

==> Deployed. Useful URLs (run each in a separate terminal):
  minikube service -n mobmoney frontend --url      # the web app
  minikube service -n mobmoney transfer-service --url
  minikube service -n mobmoney grafana --url        # admin / admin
  minikube service -n mobmoney prometheus --url

Tip: set the frontend's NEXT_PUBLIC_API_BASE_URL to the transfer-service URL
that `minikube service` prints, then redeploy the frontend.
EOF
