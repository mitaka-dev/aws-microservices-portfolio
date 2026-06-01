# ADR 007 — Amazon Cognito over Self-Issued JWT

## Context

The API requires token-based authentication. Every request to a non-public endpoint must carry a valid JWT that the API Gateway can verify before forwarding traffic to the internal services. The choices were:

- Self-issued JWT: Spring Security generates and signs tokens with a locally managed RSA key pair, services expose a `/well-known/jwks.json` endpoint.
- Amazon Cognito User Pool: managed identity provider, issues OIDC-compliant JWTs.
- Third-party IdP: Auth0, Okta, Keycloak.

## Decision

Use **Amazon Cognito User Pool** as the identity provider. The HTTP API Gateway JWT authorizer is configured with the Cognito JWKS endpoint (`https://cognito-idp.{region}.amazonaws.com/{userPoolId}/.well-known/jwks.json`) and the expected issuer/audience claims.

## Consequences

**Positive:**
- No auth server to run. Cognito is a managed service; key rotation, token signing, and user storage are handled by AWS.
- The HTTP API Gateway JWT authorizer validates tokens at the edge without invoking a Lambda or hitting any service — zero latency added to the auth check from the application perspective.
- Cognito includes hosted UI, password policies, MFA (TOTP and SMS), account recovery, and email verification out of the box.
- IAM integration: Cognito identity pool can exchange a user pool token for temporary AWS credentials, enabling direct S3 access from mobile clients if needed in future.
- 50,000 MAU free tier — cost is zero for a portfolio.

**Negative:**
- Vendor lock-in: Cognito tokens use AWS-specific claims (`cognito:username`, `cognito:groups`) in addition to standard OIDC claims. Migrating to a different IdP requires updating claim mappings across all services.
- Cognito is not available in all AWS regions and has known quirks around advanced security features and custom domain setup.
- Local development requires either a running Cognito instance or a mock (LocalStack's Cognito support is partial). The local Spring profile skips JWT validation entirely for development convenience.

## Alternatives Considered

**Self-issued JWT:** Full control, no vendor dependency. Requires managing an RSA key pair, publishing a JWKS endpoint, and handling key rotation. Key compromise means revoking all outstanding tokens. Appropriate for teams that need fine-grained control over token claims or multi-tenant issuance logic; unnecessary complexity for this project.

**Auth0 / Okta:** Feature-rich (fine-grained authorization, machine-to-machine tokens, SCIM provisioning). Introduces an external vendor dependency outside AWS. Free tier is limited (~7,000 MAU for Auth0). The right choice for a SaaS product targeting enterprise customers; not justified for a portfolio.

**Keycloak:** Open-source, full OIDC/SAML server with an admin UI and strong enterprise features. Must be self-hosted on a server or container. Running Keycloak on ECS or EC2 would add ~$0.02/hour and a non-trivial ops surface — more than the auth problem it solves for this project.
