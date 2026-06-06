# Manual Setup Checklist

Steps that can't be automated via OpenTofu or Maven. Work through this list once all phases are code-complete and the infrastructure is deployed (`tofu apply`).

---

## 1. Grafana Cloud (Phase 10)

- [ ] Create a free Grafana Cloud account at grafana.com
- [ ] Run `tofu apply` so the IAM user exists, then create an access key:
  ```bash
  aws iam create-access-key --user-name portfolio-dev-grafana-cloud \
    --profile aws-microservices-portfolio \
    --query 'AccessKey.{ID:AccessKeyId,Secret:SecretAccessKey}'
  ```
- [ ] In Grafana Cloud → **Connections → Add data source → CloudWatch**
  - Region: `eu-west-1`
  - Auth: Access & secret key
  - Name the datasource exactly: `CloudWatch`
- [ ] Add second data source → **AWS X-Ray**
  - Same credentials
  - Name it exactly: `X-Ray`
- [ ] Import dashboard: **Dashboards → Import → Upload JSON file** → `docs/grafana/portfolio-dashboard.json`
  - Map `${DS_CLOUDWATCH}` → `CloudWatch`
  - Map `${DS_XRAY}` → `X-Ray`
- [ ] Screenshot the live dashboard → save as `docs/diagrams/grafana-dashboard.png`

---

## 2. Screenshots for README (requires infra running + load)

Run `./scripts/up.sh` and send some traffic with k6 before taking these.

- [ ] ECS console — tasks running, service health → `docs/diagrams/ecs-console.png`
- [ ] CloudWatch dashboard — live metrics across all services → `docs/diagrams/cloudwatch-dashboard.png`
- [ ] X-Ray service map — multi-service trace topology → `docs/diagrams/xray-service-map.png`
- [ ] Auto-scaling event — CloudWatch scaling activities during k6 load → `docs/diagrams/scaling-event.png`
- [ ] k6 output — terminal output of `order-flow.js` passing → `docs/diagrams/k6-output.png`
- [ ] Grafana dashboard — live panels with data → `docs/diagrams/grafana-dashboard.png`

---

## 3. GitHub + CV (Final Phase)

- [ ] Pin the repo on your GitHub profile
- [ ] Add the repo link to your CV
- [ ] Add the repo link to your LinkedIn (Featured section or About)
