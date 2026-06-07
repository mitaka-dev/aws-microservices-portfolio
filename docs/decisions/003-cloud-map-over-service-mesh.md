# ADR 003 — AWS Cloud Map over a Service Mesh

## Context

`order-service` needs to make synchronous gRPC calls to `catalog-service` (to decrement stock when an order is confirmed). Both services run as ECS Fargate tasks in private subnets. Task IPs are ephemeral and change on every deployment.

The options for service-to-service discovery were:

- Hardcoded internal ALB DNS name
- AWS Cloud Map with ECS DNS-based service discovery
- AWS App Mesh (Envoy sidecar proxy)
- Istio (requires EKS)

## Decision

Use **AWS Cloud Map** with a private DNS namespace (`internal.local`). ECS registers each task's IP automatically when it reaches a healthy state and deregisters it on shutdown. `order-service` resolves `catalog-service.internal.local` at call time.

## Consequences

**Positive:**
- Zero additional cost: Cloud Map DNS queries are free for ECS-registered services.
- No sidecar container overhead — Fargate tasks stay at 256 CPU / 512 MB with a single app container plus the ADOT observability sidecar.
- Works natively with gRPC: the gRPC channel connects directly to the catalog task IP on port 9090, bypassing the ALB entirely (no HTTP/1.1 upgrade required).
- ECS handles registration/deregistration as part of the service lifecycle — no extra health check logic.

**Negative:**
- No mTLS between services: traffic between `order-service` and `catalog-service` is unencrypted inside the VPC. Acceptable within a single VPC; insufficient for a zero-trust network.
- No circuit breaking at the mesh layer — resilience patterns (retries, timeouts) must be implemented in application code or via Resilience4j.
- No traffic splitting: canary deploys require an ALB-based strategy, not weighted routing at the service mesh layer.

## Alternatives Considered

**Hardcoded ALB DNS:** The internal ALB could route gRPC traffic with path-based rules. Requires the ALB to support HTTP/2 (it does, with ALPN), but adds an unnecessary layer of indirection and cost for service-to-service calls. Also means gRPC health checking and connection pooling go through ALB listener capacity.

**AWS App Mesh:** Adds an Envoy proxy sidecar to each task, providing mTLS, traffic shaping, retries, circuit breaking, and distributed tracing integration. Costs ~$0.0075/Envoy-hour per task (~$0.72/day across 4 services). The right choice when you need a zero-trust network or fine-grained traffic control; overkill for five services where the VPC boundary is the trust boundary.

**Istio on EKS:** The most feature-rich option. Requires migrating to EKS (see ADR 001). Adds significant operational complexity in exchange for the full Istio feature set. Not appropriate for this scope.
