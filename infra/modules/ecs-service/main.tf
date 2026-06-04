locals {
  name_prefix = "${var.org}-${var.environment}"

  otel_env_vars = var.enable_adot ? [
    { name = "JAVA_TOOL_OPTIONS", value = "-javaagent:/otel/javaagent.jar" },
    { name = "OTEL_EXPORTER_OTLP_ENDPOINT", value = "http://localhost:4317" },
    { name = "OTEL_TRACES_EXPORTER", value = "otlp" },
    { name = "OTEL_METRICS_EXPORTER", value = "none" },
    { name = "OTEL_LOGS_EXPORTER", value = "none" },
    { name = "OTEL_SERVICE_NAME", value = var.otel_service_name != "" ? var.otel_service_name : var.service_name },
  ] : []

  main_container = {
    name      = var.service_name
    image     = var.image_uri
    essential = true
    portMappings = [{
      containerPort = var.container_port
      protocol      = "tcp"
    }]
    environment = concat(
      [for k, v in var.env_vars : { name = k, value = v }],
      local.otel_env_vars
    )
    secrets = [for k, v in var.task_secrets : { name = k, valueFrom = v }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.this.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }
  }

  adot_container = {
    name      = "adot-collector"
    image     = "public.ecr.aws/aws-observability/aws-otel-collector:v0.43.1"
    essential = false
    environment = [
      { name = "AOT_CONFIG_CONTENT", value = file("${path.module}/adot-config.yaml") }
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.this.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "adot"
      }
    }
  }

  # jsonencode over a tuple literal avoids Terraform's list type-unification constraint
  # (main_container has 7 keys, adot_container has 5 — different object types)
  container_defs_json = var.enable_adot ? jsonencode([local.main_container, local.adot_container]) : jsonencode([local.main_container])
}

# ── Logs ──────────────────────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${local.name_prefix}/${var.service_name}"
  retention_in_days = 7

  tags = { Name = "${local.name_prefix}-${var.service_name}" }
}

# ── IAM ───────────────────────────────────────────────────────────────────────

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    actions = ["sts:AssumeRole"]
  }
}

resource "aws_iam_role" "execution" {
  name               = "${local.name_prefix}-${var.service_name}-exec"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json

  tags = { Name = "${local.name_prefix}-${var.service_name}-exec" }
}

resource "aws_iam_role_policy_attachment" "execution_basic" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  count = length(var.secret_arns_for_exec_role) > 0 ? 1 : 0

  name = "${local.name_prefix}-${var.service_name}-exec-secrets"
  role = aws_iam_role.execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = var.secret_arns_for_exec_role
    }]
  })
}

resource "aws_iam_role" "task" {
  name               = "${local.name_prefix}-${var.service_name}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json

  tags = { Name = "${local.name_prefix}-${var.service_name}-task" }
}

resource "aws_iam_role_policy" "task_cloudwatch" {
  name = "${local.name_prefix}-${var.service_name}-task-cw"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["cloudwatch:PutMetricData"]
      Resource = "*"
    }]
  })
}

resource "aws_iam_role_policy" "task_dynamodb" {
  count = length(var.dynamodb_table_arns) > 0 ? 1 : 0

  name = "${local.name_prefix}-${var.service_name}-task-ddb"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "dynamodb:GetItem",
        "dynamodb:PutItem",
        "dynamodb:UpdateItem",
        "dynamodb:DeleteItem",
        "dynamodb:Query",
        "dynamodb:Scan",
        "dynamodb:BatchWriteItem",
        "dynamodb:BatchGetItem",
        "dynamodb:TransactWriteItems",
      ]
      Resource = concat(
        var.dynamodb_table_arns,
        [for arn in var.dynamodb_table_arns : "${arn}/index/*"]
      )
    }]
  })
}

resource "aws_service_discovery_service" "this" {
  count        = var.enable_cloud_map ? 1 : 0
  name         = var.service_name
  namespace_id = var.cloud_map_namespace_id

  dns_config {
    namespace_id   = var.cloud_map_namespace_id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 10
    }
  }

  health_check_custom_config {
    failure_threshold = 1
  }
}

# ── Task definition ───────────────────────────────────────────────────────────

resource "aws_ecs_task_definition" "this" {
  family                   = "${local.name_prefix}-${var.service_name}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.cpu)
  memory                   = tostring(var.memory)
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = local.container_defs_json

  tags = { Name = "${local.name_prefix}-${var.service_name}" }
}

# ── ALB target group + listener rule ──────────────────────────────────────────

resource "aws_lb_target_group" "this" {
  count = var.enable_alb_listener ? 1 : 0

  name        = "${local.name_prefix}-${var.service_name}-tg"
  port        = var.container_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  deregistration_delay = 30

  health_check {
    path                = var.health_check_path
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = { Name = "${local.name_prefix}-${var.service_name}-tg" }
}

resource "aws_lb_listener_rule" "this" {
  count = var.enable_alb_listener ? 1 : 0

  listener_arn = var.alb_listener_arn
  priority     = var.listener_rule_priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[0].arn
  }

  condition {
    path_pattern {
      values = var.path_patterns
    }
  }
}

# ── Security group ────────────────────────────────────────────────────────────

resource "aws_security_group" "task" {
  name   = "${local.name_prefix}-${var.service_name}-sg"
  vpc_id = var.vpc_id

  tags = { Name = "${local.name_prefix}-${var.service_name}-sg" }
}

resource "aws_vpc_security_group_egress_rule" "task_all" {
  security_group_id = aws_security_group.task.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# ── ECS service ───────────────────────────────────────────────────────────────

resource "aws_ecs_service" "this" {
  name            = "${local.name_prefix}-${var.service_name}"
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.task.id]
    assign_public_ip = false
  }

  dynamic "load_balancer" {
    for_each = var.enable_alb_listener ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.this[0].arn
      container_name   = var.service_name
      container_port   = var.container_port
    }
  }

  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = var.enable_alb_listener ? var.health_check_grace_period_seconds : 0

  dynamic "service_registries" {
    for_each = var.enable_cloud_map ? [1] : []
    content {
      registry_arn = aws_service_discovery_service.this[0].arn
    }
  }

  depends_on = [aws_lb_listener_rule.this]

  # Autoscaling manages desired_count at runtime; ignore drift on subsequent applies
  lifecycle {
    ignore_changes = [desired_count]
  }

  tags = { Name = "${local.name_prefix}-${var.service_name}" }
}

resource "aws_iam_role_policy" "task_sqs" {
  count = length(var.sqs_queue_arns) > 0 ? 1 : 0
  name  = "${local.name_prefix}-${var.service_name}-task-sqs"
  role  = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes",
        "sqs:ChangeMessageVisibility",
      ]
      Resource = var.sqs_queue_arns
    }]
  })
}

resource "aws_iam_role_policy" "task_sns" {
  count = length(var.sns_topic_arns) > 0 ? 1 : 0
  name  = "${local.name_prefix}-${var.service_name}-task-sns"
  role  = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["sns:Publish"]
      Resource = var.sns_topic_arns
    }]
  })
}

resource "aws_iam_role_policy" "task_s3" {
  count = length(var.s3_bucket_arns) > 0 ? 1 : 0
  name  = "${local.name_prefix}-${var.service_name}-task-s3"
  role  = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:GetObject", "s3:PutObject"]
      Resource = [for arn in var.s3_bucket_arns : "${arn}/*"]
    }]
  })
}

resource "aws_iam_role_policy" "task_xray" {
  count = var.enable_adot ? 1 : 0
  name  = "${local.name_prefix}-${var.service_name}-task-xray"
  role  = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "xray:PutTraceSegments",
        "xray:PutTelemetryRecords",
        "xray:GetSamplingRules",
        "xray:GetSamplingTargets",
      ]
      Resource = "*"
    }]
  })
}
