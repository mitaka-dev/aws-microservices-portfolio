# ADR 002 — API Gateway (HTTP API) + Internal ALB Two-Tier Routing

## Context

Four ECS Fargate services run in private subnets and need to be reachable from the public internet with JWT authentication enforced centrally. Several routing architectures were considered:

- Single public ALB with Cognito authenticator action
- Single public ALB with auth delegated to each service
- API Gateway (HTTP API) → VPC Link → internal ALB → services
- API Gateway (REST API) → VPC Link → internal ALB

Each tier has a different responsibility, cost model, and capability set.

## Decision

Use a **two-tier architecture**: HTTP API Gateway with a Cognito JWT authorizer in the public tier, and an internal Application Load Balancer with path-based routing rules in the private tier.

The two tiers are bridged by a VPC Link, which tunnels traffic from the API Gateway into the private VPC without a NAT hop.

```
Internet → API GW (JWT auth) → VPC Link → Internal ALB → ECS services
```

## Consequences

**Positive:**
- JWT verification happens once at the edge (API Gateway) before any request reaches a service. Services receive pre-validated claims and do not need to re-verify tokens.
- HTTP API is ~70% cheaper than REST API (~$1.00 vs ~$3.50 per million requests) and has lower latency.
- The internal ALB handles health checks, connection draining, and path-based routing independently of the API Gateway — concerns are separated cleanly.
- Routing rules (`/users/*`, `/catalog/*`, `/orders/*`, `/files/*`) live in one place (ALB listener rules) and are not duplicated across services.

**Negative:**
- Two components to manage. Debugging routing failures requires checking both API Gateway logs and ALB access logs.
- The VPC Link adds a small latency hop (~1–2 ms) and has its own cost (~$0.01/hour).
- HTTP API Gateway does not support request transformation, caching, or usage plans — those require the more expensive REST API.

## Alternatives Considered

**Single public ALB with Cognito authenticator action:** Cognito's ALB authenticator only works with browser-based flows (redirects to Hosted UI). It cannot validate Bearer tokens sent by API clients (mobile apps, curl, service-to-service). Unusable for this pattern.

**Auth delegated to each service:** Each Spring Boot service validates the Cognito JWT itself (Spring Security OAuth2 resource server). Works, but duplicates configuration, makes it easy for a future service to accidentally skip auth, and means a JWKS rotation requires a restart of all services rather than a single config change at the edge.

**REST API Gateway:** Supports request/response transformation, caching, and usage plans. ~3.5× more expensive than HTTP API for the same throughput. The additional features are not needed here.
