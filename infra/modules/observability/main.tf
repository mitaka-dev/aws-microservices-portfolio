locals {
  name_prefix = "${var.org}-${var.environment}"
}

# ── Alarm SNS topic + email subscription ──────────────────────────────────────

resource "aws_sns_topic" "alarms" {
  name = "${local.name_prefix}-alarms"

  tags = { Name = "${local.name_prefix}-alarms" }
}

resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

# ── CloudWatch Dashboard ───────────────────────────────────────────────────────

resource "aws_cloudwatch_dashboard" "portfolio" {
  dashboard_name = "${local.name_prefix}-portfolio"

  dashboard_body = jsonencode({
    widgets = [
      # Row 0 (y=0): ALB requests per minute per service
      {
        type   = "metric"
        x      = 0
        y      = 0
        width  = 24
        height = 6
        properties = {
          title  = "ALB Requests per Minute"
          view   = "timeSeries"
          stat   = "Sum"
          period = 60
          region = var.region
          metrics = [for svc in var.services : [
            "AWS/ApplicationELB", "RequestCount",
            "LoadBalancer", var.alb_arn_suffix,
            "TargetGroup", svc.target_group_arn_suffix,
            { label = svc.name }
          ]]
        }
      },
      # Row 1 (y=6): ALB p99 latency per service
      {
        type   = "metric"
        x      = 0
        y      = 6
        width  = 24
        height = 6
        properties = {
          title  = "ALB p99 Latency (seconds)"
          view   = "timeSeries"
          stat   = "p99"
          period = 60
          region = var.region
          metrics = [for svc in var.services : [
            "AWS/ApplicationELB", "TargetResponseTime",
            "LoadBalancer", var.alb_arn_suffix,
            "TargetGroup", svc.target_group_arn_suffix,
            { label = svc.name }
          ]]
        }
      },
      # Row 2 (y=12): ECS CPU utilization
      {
        type   = "metric"
        x      = 0
        y      = 12
        width  = 12
        height = 6
        properties = {
          title  = "ECS CPU Utilization (cores)"
          view   = "timeSeries"
          stat   = "Average"
          period = 60
          region = var.region
          metrics = [for svc in var.services : [
            "ECS/ContainerInsights", "CpuUtilized",
            "ClusterName", var.cluster_name,
            "ServiceName", svc.ecs_service_name,
            { label = svc.name }
          ]]
        }
      },
      # Row 2 (y=12): ECS memory utilization
      {
        type   = "metric"
        x      = 12
        y      = 12
        width  = 12
        height = 6
        properties = {
          title  = "ECS Memory Utilization (MB)"
          view   = "timeSeries"
          stat   = "Average"
          period = 60
          region = var.region
          metrics = [for svc in var.services : [
            "ECS/ContainerInsights", "MemoryUtilized",
            "ClusterName", var.cluster_name,
            "ServiceName", svc.ecs_service_name,
            { label = svc.name }
          ]]
        }
      },
      # Row 3 (y=18): RDS connections
      {
        type   = "metric"
        x      = 0
        y      = 18
        width  = 12
        height = 6
        properties = {
          title   = "RDS Connections"
          view    = "timeSeries"
          stat    = "Average"
          period  = 60
          region  = var.region
          metrics = [["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", var.rds_identifier]]
        }
      },
      # Row 3 (y=18): RDS CPU
      {
        type   = "metric"
        x      = 12
        y      = 18
        width  = 12
        height = 6
        properties = {
          title   = "RDS CPU (%)"
          view    = "timeSeries"
          stat    = "Average"
          period  = 60
          region  = var.region
          metrics = [["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", var.rds_identifier]]
        }
      },
      # Row 4 (y=24): SQS visible messages
      {
        type   = "metric"
        x      = 0
        y      = 24
        width  = 12
        height = 6
        properties = {
          title   = "SQS Messages Visible"
          view    = "timeSeries"
          stat    = "Maximum"
          period  = 60
          region  = var.region
          metrics = [["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", var.sqs_queue_name]]
        }
      },
      # Row 4 (y=24): SQS oldest message age
      {
        type   = "metric"
        x      = 12
        y      = 24
        width  = 12
        height = 6
        properties = {
          title   = "SQS Oldest Message Age (seconds)"
          view    = "timeSeries"
          stat    = "Maximum"
          period  = 60
          region  = var.region
          metrics = [["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", var.sqs_queue_name]]
        }
      },
      # Row 5 (y=30): DynamoDB consumed RCU
      {
        type   = "metric"
        x      = 0
        y      = 30
        width  = 12
        height = 6
        properties = {
          title   = "DynamoDB Consumed RCU"
          view    = "timeSeries"
          stat    = "Sum"
          period  = 60
          region  = var.region
          metrics = [["AWS/DynamoDB", "ConsumedReadCapacityUnits", "TableName", var.dynamodb_table_name]]
        }
      },
      # Row 5 (y=30): DynamoDB consumed WCU
      {
        type   = "metric"
        x      = 12
        y      = 30
        width  = 12
        height = 6
        properties = {
          title   = "DynamoDB Consumed WCU"
          view    = "timeSeries"
          stat    = "Sum"
          period  = 60
          region  = var.region
          metrics = [["AWS/DynamoDB", "ConsumedWriteCapacityUnits", "TableName", var.dynamodb_table_name]]
        }
      },
    ]
  })
}

# ── Alarms ─────────────────────────────────────────────────────────────────────

# 5xx errors per service (ALB HTTPCode_Target_5XX_Count > 10 in 2 consecutive 1-min periods)
resource "aws_cloudwatch_metric_alarm" "service_5xx" {
  for_each = { for svc in var.services : svc.name => svc }

  alarm_name          = "${local.name_prefix}-${each.key}-5xx"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "HTTPCode_Target_5XX_Count"
  namespace           = "AWS/ApplicationELB"
  period              = 60
  statistic           = "Sum"
  threshold           = 10
  alarm_description   = "5xx errors > 10/min for ${each.key}"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
  treat_missing_data  = "notBreaching"

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = each.value.target_group_arn_suffix
  }

  tags = { Name = "${local.name_prefix}-${each.key}-5xx" }
}

# SQS oldest message age > 60s
resource "aws_cloudwatch_metric_alarm" "sqs_age" {
  alarm_name          = "${local.name_prefix}-sqs-message-age"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateAgeOfOldestMessage"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 60
  alarm_description   = "SQS oldest message age > 60s — consumer may be stuck"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
  treat_missing_data  = "notBreaching"

  dimensions = {
    QueueName = var.sqs_queue_name
  }

  tags = { Name = "${local.name_prefix}-sqs-message-age" }
}

# DLQ has any messages
resource "aws_cloudwatch_metric_alarm" "dlq_depth" {
  alarm_name          = "${local.name_prefix}-dlq-depth"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 1
  alarm_description   = "DLQ has messages — processing failures detected"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
  treat_missing_data  = "notBreaching"

  dimensions = {
    QueueName = var.dlq_name
  }

  tags = { Name = "${local.name_prefix}-dlq-depth" }
}

# RDS CPU > 80%
resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${local.name_prefix}-rds-cpu"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "CPUUtilization"
  namespace           = "AWS/RDS"
  period              = 60
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "RDS CPU utilization > 80%"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
  treat_missing_data  = "notBreaching"

  dimensions = {
    DBInstanceIdentifier = var.rds_identifier
  }

  tags = { Name = "${local.name_prefix}-rds-cpu" }
}

# ECS running task count < 1 per service
resource "aws_cloudwatch_metric_alarm" "ecs_running" {
  for_each = { for svc in var.services : svc.name => svc }

  alarm_name          = "${local.name_prefix}-${each.key}-running"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 2
  metric_name         = "RunningTaskCount"
  namespace           = "ECS/ContainerInsights"
  period              = 60
  statistic           = "Average"
  threshold           = 1
  alarm_description   = "ECS ${each.key} has no running tasks"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
  treat_missing_data  = "breaching"

  dimensions = {
    ClusterName = var.cluster_name
    ServiceName = each.value.ecs_service_name
  }

  tags = { Name = "${local.name_prefix}-${each.key}-running" }
}
