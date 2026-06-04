#!/usr/bin/env bash
# Quick AWS health check for the portfolio account.
# Shows costs, running resources, alarms, ECR images, and S3 buckets.
#
# Usage: ./scripts/aws-status.sh
set -euo pipefail

export AWS_PROFILE="${AWS_PROFILE:-aws-microservices-portfolio}"
REGION="${AWS_REGION:-eu-west-1}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

BOLD='\033[1m'
CYAN='\033[36m'
GREEN='\033[32m'
YELLOW='\033[33m'
RED='\033[31m'
RESET='\033[0m'

section() { echo -e "\n${CYAN}${BOLD}=== $1 ===${RESET}"; }
ok()      { echo -e "  ${GREEN}$1${RESET}"; }
warn()    { echo -e "  ${YELLOW}$1${RESET}"; }
bad()     { echo -e "  ${RED}$1${RESET}"; }
row()     { printf "  %-44s %s\n" "$1" "$2"; }

# ── Costs ────────────────────────────────────────────────────────────────────

MONTH_START=$(date +%Y-%m-01)
TODAY=$(date +%Y-%m-%d)
LAST_START=$(date -d "$(date +%Y-%m-01) -1 month" +%Y-%m-01 2>/dev/null \
  || python3 -c "from datetime import date; d=date.today().replace(day=1); print((d.replace(month=d.month-1) if d.month>1 else d.replace(year=d.year-1,month=12)).strftime('%Y-%m-01'))")
LAST_END=$MONTH_START

_print_costs() {
  local label="$1"
  python3 -c "
import json, sys
label = sys.argv[1]
rows = json.load(sys.stdin)
rows = [(s, float(a)) for s, a in rows if float(a) > 0.001]
rows.sort(key=lambda x: -x[1])
tax   = sum(a for s, a in rows if s == 'Tax')
total = sum(a for _, a in rows)
charges = total - tax
for s, a in rows:
    flag = '  <-- check this (not this project?)' if a > 3 else ''
    print(f'  {s:<46} \${a:>7.4f}{flag}')
print(f'  {\"─\"*56}')
print(f'  {\"Charges (excl. tax)\":<46} \${charges:>7.4f}')
print(f'  {\"Tax\":<46} \${tax:>7.4f}')
print(f'  {label:<46} \${total:>7.4f}')
print()
print(f'  Note: Cost Explorer always reports in USD. If your billing')
print(f'  currency is EUR, the card charge is USD converted at the')
print(f'  monthly rate — plus any credits applied on the invoice.')
" "$label"
}

TAG_FILTER='{"Tags":{"Key":"Project","Values":["aws-microservices-portfolio"],"MatchOptions":["EQUALS"]}}'

section "COSTS — current month ($(date +%Y-%m)) [this project]"
aws ce get-cost-and-usage \
  --time-period "Start=${MONTH_START},End=${TODAY}" \
  --granularity MONTHLY \
  --metrics UnblendedCost \
  --filter "$TAG_FILTER" \
  --group-by Type=DIMENSION,Key=SERVICE \
  --query 'ResultsByTime[0].Groups[].[Keys[0], Metrics.UnblendedCost.Amount]' \
  --output json | _print_costs "TOTAL (so far)"

section "COSTS — last month [this project]"
aws ce get-cost-and-usage \
  --time-period "Start=${LAST_START},End=${LAST_END}" \
  --granularity MONTHLY \
  --metrics UnblendedCost \
  --filter "$TAG_FILTER" \
  --group-by Type=DIMENSION,Key=SERVICE \
  --query 'ResultsByTime[0].Groups[].[Keys[0], Metrics.UnblendedCost.Amount]' \
  --output json | _print_costs "TOTAL"

section "COSTS — last month [whole account]"
aws ce get-cost-and-usage \
  --time-period "Start=${LAST_START},End=${LAST_END}" \
  --granularity MONTHLY \
  --metrics UnblendedCost \
  --group-by Type=DIMENSION,Key=SERVICE \
  --query 'ResultsByTime[0].Groups[].[Keys[0], Metrics.UnblendedCost.Amount]' \
  --output json | _print_costs "TOTAL"

# ── Running resources (the expensive ones) ───────────────────────────────────

section "RUNNING RESOURCES — $REGION"

# NAT Gateways
NAT=$(aws ec2 describe-nat-gateways --region "$REGION" \
  --filter Name=state,Values=available \
  --query 'NatGateways[].NatGatewayId' --output text)
if [ -n "$NAT" ]; then
  bad "NAT Gateway (billing): $NAT  (~\$1.08/day)"
else
  ok "NAT Gateway: none"
fi

# RDS
RDS=$(aws rds describe-db-instances --region "$REGION" \
  --query 'DBInstances[].[DBInstanceIdentifier,DBInstanceStatus]' --output text)
if [ -n "$RDS" ]; then
  bad "RDS instances (billing):"
  echo "$RDS" | while read -r id status; do bad "  $id  [$status]"; done
else
  ok "RDS: none"
fi

# ElastiCache
CACHE=$(aws elasticache describe-cache-clusters --region "$REGION" \
  --query 'CacheClusters[].[CacheClusterId,CacheClusterStatus]' --output text)
if [ -n "$CACHE" ]; then
  bad "ElastiCache clusters (billing):"
  echo "$CACHE" | while read -r id status; do bad "  $id  [$status]"; done
else
  ok "ElastiCache: none"
fi

# ALBs
ALBS=$(aws elbv2 describe-load-balancers --region "$REGION" \
  --query 'LoadBalancers[].[LoadBalancerName,State.Code]' --output text)
if [ -n "$ALBS" ]; then
  warn "Load Balancers (billing):"
  echo "$ALBS" | while read -r name state; do warn "  $name  [$state]"; done
else
  ok "Load Balancers: none"
fi

# ECS clusters + running task count
CLUSTERS=$(aws ecs list-clusters --region "$REGION" --query 'clusterArns[]' --output text)
if [ -n "$CLUSTERS" ]; then
  warn "ECS Clusters:"
  for arn in $CLUSTERS; do
    NAME=$(basename "$arn")
    TASKS=$(aws ecs list-tasks --region "$REGION" --cluster "$arn" \
      --desired-status RUNNING --query 'taskArns | length(@)' --output text)
    warn "  $NAME  ($TASKS running tasks)"
  done
else
  ok "ECS: no clusters"
fi

# EIP (charged when unattached)
UNATTACHED_EIP=$(aws ec2 describe-addresses --region "$REGION" \
  --query 'Addresses[?AssociationId==null].PublicIp' --output text)
if [ -n "$UNATTACHED_EIP" ]; then
  bad "Unattached EIPs (billing): $UNATTACHED_EIP"
else
  ok "EIPs: none unattached"
fi

# ── CloudWatch Alarms ─────────────────────────────────────────────────────────

section "CLOUDWATCH ALARMS — $REGION"

ALARMING=$(aws cloudwatch describe-alarms --region "$REGION" \
  --state-value ALARM \
  --query 'MetricAlarms[].AlarmName' --output text)
INSUFFICIENT=$(aws cloudwatch describe-alarms --region "$REGION" \
  --state-value INSUFFICIENT_DATA \
  --query 'MetricAlarms[].AlarmName' --output text)
OK_COUNT=$(aws cloudwatch describe-alarms --region "$REGION" \
  --state-value OK \
  --query 'MetricAlarms | length(@)' --output text)

if [ -n "$ALARMING" ]; then
  bad "ALARM:"
  for a in $ALARMING; do bad "  $a"; done
else
  ok "No alarms firing"
fi

if [ -n "$INSUFFICIENT" ]; then
  warn "INSUFFICIENT_DATA (infra likely torn down):"
  for a in $INSUFFICIENT; do warn "  $a"; done
fi

[ -n "$OK_COUNT" ] && ok "$OK_COUNT alarm(s) in OK state"

# ── ECR Images ───────────────────────────────────────────────────────────────

section "ECR REPOSITORIES — $REGION"

REPOS=$(aws ecr describe-repositories --region "$REGION" \
  --query 'repositories[].repositoryName' --output text)

if [ -z "$REPOS" ]; then
  ok "No repositories"
else
  printf "  %-44s %s\n" "REPOSITORY" "IMAGES"
  for repo in $REPOS; do
    COUNT=$(aws ecr list-images --region "$REGION" \
      --repository-name "$repo" \
      --query 'imageIds | length(@)' --output text)
    row "$repo" "$COUNT image(s)"
  done
fi

# ── S3 Buckets ───────────────────────────────────────────────────────────────

section "S3 BUCKETS"

aws s3 ls | while read -r date bucket; do
  SIZE=$(aws s3 ls "s3://$bucket" --recursive --human-readable --summarize 2>/dev/null \
    | grep 'Total Size' | awk '{print $3, $4}' || echo "0 B")
  row "$bucket" "$SIZE"
done

# ── GitHub Actions CI status ─────────────────────────────────────────────────

section "GITHUB ACTIONS — last run per workflow"

REMOTE_URL=$(git -C "$REPO_ROOT" remote get-url origin 2>/dev/null || echo "")
GH_REPO=$(echo "$REMOTE_URL" | sed -E 's|.*github\.com[:/]||; s|\.git$||')

if [ -z "$GH_REPO" ]; then
  warn "Could not detect GitHub repo from git remote"
else
  for workflow in ci.yml infra.yml load-test.yml; do
    DATA=$(curl -sf \
      -H "Accept: application/vnd.github+json" \
      "https://api.github.com/repos/$GH_REPO/actions/workflows/$workflow/runs?per_page=1" \
      2>/dev/null) || true
    if [ -z "$DATA" ]; then
      warn "$workflow: API unreachable"
      continue
    fi
    echo "$DATA" | python3 -c "
import json, sys
workflow = sys.argv[1]
data = json.load(sys.stdin)
runs = data.get('workflow_runs', [])
if not runs:
    print(f'  \033[33m{workflow}: no runs found\033[0m')
    sys.exit()
r = runs[0]
conclusion = r.get('conclusion') or r.get('status', 'unknown')
ts = r.get('created_at', '')[:16].replace('T', ' ')
title = (r.get('display_title') or r.get('head_commit', {}).get('message', ''))[:60]
line = f'{workflow}: {conclusion} | {ts} | {title}'
if conclusion == 'success':
    print(f'  \033[32m{line}\033[0m')
elif conclusion in ('failure', 'cancelled'):
    print(f'  \033[31m{line}\033[0m')
else:
    print(f'  \033[33m{line}\033[0m')
" "$workflow" || warn "$workflow: parse error"
  done
fi

# ── SQS queue depths ─────────────────────────────────────────────────────────

section "SQS QUEUES — $REGION"

QUEUE_URLS=$(aws sqs list-queues --region "$REGION" \
  --query 'QueueUrls[]' --output text 2>/dev/null || true)

if [ -z "$QUEUE_URLS" ]; then
  ok "No queues (infra is down)"
else
  printf "  %-48s %8s %8s\n" "QUEUE" "VISIBLE" "IN-FLIGHT"
  for url in $QUEUE_URLS; do
    NAME=$(basename "$url")
    ATTRS=$(aws sqs get-queue-attributes --region "$REGION" \
      --queue-url "$url" \
      --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
      --query 'Attributes' --output json 2>/dev/null) || { warn "  $NAME  (could not read attributes)"; continue; }
    VISIBLE=$(echo "$ATTRS" | python3 -c "import json,sys; print(json.load(sys.stdin).get('ApproximateNumberOfMessages','0'))")
    INFLIGHT=$(echo "$ATTRS" | python3 -c "import json,sys; print(json.load(sys.stdin).get('ApproximateNumberOfMessagesNotVisible','0'))")
    LINE=$(printf "  %-48s %8s %8s" "$NAME" "$VISIBLE" "$INFLIGHT")
    if [[ "$NAME" == *dlq* || "$NAME" == *dead* ]] && [ "$VISIBLE" -gt 0 ] 2>/dev/null; then
      bad "$LINE  <-- DLQ has messages!"
    elif [ "${VISIBLE:-0}" -gt 100 ] 2>/dev/null; then
      warn "$LINE  <-- backlog"
    else
      echo "$LINE"
    fi
  done
fi

# ── AWS Budgets ───────────────────────────────────────────────────────────────

section "AWS BUDGETS"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
BUDGETS=$(aws budgets describe-budgets --account-id "$ACCOUNT_ID" \
  --query 'Budgets[].[BudgetName, BudgetLimit.Amount, CalculatedSpend.ActualSpend.Amount]' \
  --output json 2>/dev/null || echo "[]")

COUNT=$(echo "$BUDGETS" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))")
if [ "$COUNT" -eq 0 ]; then
  warn "No budgets configured — consider adding one at: aws budgets create-budget"
else
  echo "$BUDGETS" | python3 -c "
import json, sys
for name, limit, spent in json.load(sys.stdin):
    limit = float(limit or 0)
    spent = float(spent or 0)
    pct = (spent / limit * 100) if limit else 0
    bar = int(pct / 5) * '█' + int((100 - pct) / 5) * '░'
    flag = '  <-- over budget!' if pct > 100 else ('  <-- near limit' if pct > 80 else '')
    print(f'  {name}: \${spent:.2f} / \${limit:.2f}  ({pct:.0f}%)  [{bar}]{flag}')
"
fi

# ── OpenTofu state ───────────────────────────────────────────────────────────

section "OPENTOFU STATE"

STATE_COUNT=$(tofu -chdir="$REPO_ROOT/infra/envs/dev" state list 2>/dev/null | wc -l | tr -d ' ')

if [ "${STATE_COUNT:-0}" -eq 0 ]; then
  ok "0 resources in state — stack is fully destroyed"
elif [ "$STATE_COUNT" -lt 10 ]; then
  warn "$STATE_COUNT resource(s) in state — partial destroy?"
else
  bad "$STATE_COUNT resource(s) in state — stack is UP (billing active)"
fi

echo ""
