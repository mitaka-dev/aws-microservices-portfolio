# API Authentication Guide

The public edge is an **API Gateway HTTP API** with a **Cognito JWT authorizer**.
Every route (except future public health/metrics endpoints) requires a valid Cognito ID token
in the `Authorization: Bearer <token>` header.

---

## Prerequisites

```bash
export AWS_PROFILE=aws-microservices-portfolio
# Infrastructure must be applied: cd infra/envs/dev && tofu apply
```

---

## 1. Get a token

```bash
./scripts/get-token.sh                          # uses test@example.com / TestPassword1!
./scripts/get-token.sh me@example.com MyPass1!  # custom email and password
```

The script creates the user (idempotent) and prints the ID token. Store it:

```bash
TOKEN=$(./scripts/get-token.sh 2>/dev/null | awk '/^eyJ/{print; exit}')
API=$(tofu -chdir=infra/envs/dev output -raw api_gateway_endpoint)
```

---

## 2. Call without a token — expect 401

```bash
curl -i "$API/health"
```

```
HTTP/2 401
content-type: application/json
{"message":"Unauthorized"}
```

API Gateway rejects the request before it reaches the backend — no Lambda invocation.

---

## 3. Call with a valid token — expect 200

```bash
curl -i -H "Authorization: Bearer $TOKEN" "$API/health"
```

```
HTTP/2 200
content-type: application/json
{"status":"ok"}
```

---

## 4. Token lifetime and refresh

Cognito ID tokens expire after **1 hour**. To get a fresh one, re-run `get-token.sh`.

In Phase 3 the Spring services will validate the same token via
`spring.security.oauth2.resourceserver.jwt.issuer-uri` — the issuer URI is already in the
tofu outputs:

```bash
tofu -chdir=infra/envs/dev output cognito_issuer_uri
# https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_ygB44lFai
```

---

## 5. Token contents

Decode the ID token (no verification — for inspection only):

```bash
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq .
```

Key claims:
| Claim | Value |
|---|---|
| `sub` | User UUID (stable identifier) |
| `email` | User's email address |
| `aud` | App client ID — must match the authorizer audience |
| `iss` | Cognito issuer URI |
| `exp` | Expiry (Unix timestamp, 1 hour from issue) |
| `token_use` | `id` |
