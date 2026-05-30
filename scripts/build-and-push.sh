#!/usr/bin/env bash
set -euo pipefail

REGION="${AWS_REGION:-eu-west-1}"
ORG="${ORG:-portfolio}"
ENV="${ENV:-dev}"
ACCOUNT_ID=$(AWS_PROFILE=aws-microservices-portfolio aws sts get-caller-identity --query Account --output text)
GIT_SHA=$(git rev-parse --short HEAD)

SERVICES=(user-service catalog-service order-service file-service)

echo "==> Logging in to ECR (${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com)"
AWS_PROFILE=aws-microservices-portfolio aws ecr get-login-password --region "${REGION}" \
  | docker login --username AWS --password-stdin \
    "${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

echo "==> Building all services (parallel, skip tests)"
./mvnw -T 1C -DskipTests clean package

for SERVICE in "${SERVICES[@]}"; do
  ECR_URL="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${ORG}-${ENV}-${SERVICE}"

  # Skip services that don't have a Dockerfile yet
  if [[ ! -f "${SERVICE}/Dockerfile" ]]; then
    echo "==> Skipping ${SERVICE} (no Dockerfile)"
    continue
  fi

  echo "==> Building image: ${SERVICE}"
  docker build \
    -t "${ECR_URL}:${GIT_SHA}" \
    -t "${ECR_URL}:latest" \
    -f "${SERVICE}/Dockerfile" \
    .

  echo "==> Pushing ${SERVICE}:${GIT_SHA} and :latest"
  docker push "${ECR_URL}:${GIT_SHA}"
  docker push "${ECR_URL}:latest"
done

echo "==> Done. Images tagged with ${GIT_SHA}."
