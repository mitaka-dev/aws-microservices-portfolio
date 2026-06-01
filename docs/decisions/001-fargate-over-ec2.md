# ADR 001 — ECS Fargate over EC2 Launch Type

## Context

The portfolio runs four containerised Spring Boot services. A compute platform was needed that supports container orchestration, integrates with the existing AWS tooling (ECS, ECR, IAM), and minimises operational overhead for a solo developer. The realistic options were:

- ECS with EC2 launch type (self-managed nodes)
- ECS Fargate (serverless container runtime)
- EKS (managed Kubernetes)

The project has modest traffic requirements (demo/portfolio scale) but should demonstrate real production patterns.

## Decision

Use **ECS Fargate** for all four services.

Fargate runs containers directly without provisioning or patching EC2 instances. Each task gets its own isolated microVM (Firecracker), billed per vCPU-second and GB-second. Task definitions specify CPU and memory; Fargate handles placement, bin-packing, and host maintenance invisibly.

## Consequences

**Positive:**
- No AMI patching, no capacity planning, no SSH access to nodes — operational surface is limited to task definitions and IAM roles.
- Per-task billing makes scale-to-zero meaningful: scheduled actions that set desired count to 0 at 22:00 UTC stop all compute charges.
- Integrates directly with ECS Service Connect, Cloud Map, Application Auto Scaling, and X-Ray without additional cluster configuration.
- Task-level IAM roles are first-class (no need for EC2 instance profiles shared across tenants).

**Negative:**
- Higher per-task cost than EC2 at sustained load (no bin-packing efficiency across tasks on shared nodes).
- Cold start is slower than EC2: ~107 seconds for a 256 CPU / 512 MB Spring Boot 4 task (no JVM pre-warming).
- No support for GPU workloads or privileged containers.

## Alternatives Considered

**EC2 launch type:** Cheaper at sustained load through bin-packing. Requires managing node groups, AMI updates, and capacity reservations. Appropriate when running dozens of services on a shared cluster; overkill for four services on a portfolio.

**EKS (managed Kubernetes):** Richer ecosystem (Argo, KEDA, Istio, Karpenter) and the industry-standard platform for large engineering organisations. Adds ~$0.10/hour for the control plane and significant operational complexity (node pools, RBAC, ingress controllers, DNS plugins). The right choice when you need advanced scheduling, multi-tenancy, or a service mesh — none of which this project requires.
