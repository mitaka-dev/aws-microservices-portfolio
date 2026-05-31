variable "org" {
  description = "Organisation prefix"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "region" {
  description = "AWS region"
  type        = string
}

variable "alarm_email" {
  description = "Email address to receive CloudWatch alarm notifications"
  type        = string
}

variable "cluster_name" {
  description = "ECS cluster name (used for Container Insights metric dimensions)"
  type        = string
}

variable "rds_identifier" {
  description = "RDS DB instance identifier (used as CloudWatch dimension)"
  type        = string
}

variable "sqs_queue_name" {
  description = "SQS processing queue name (used as CloudWatch dimension)"
  type        = string
}

variable "dlq_name" {
  description = "SQS dead-letter queue name (used as CloudWatch dimension)"
  type        = string
}

variable "dynamodb_table_name" {
  description = "DynamoDB table name (used as CloudWatch dimension)"
  type        = string
}

variable "alb_arn_suffix" {
  description = "ALB ARN suffix for CloudWatch metric dimensions (format: app/name/id)"
  type        = string
}

variable "services" {
  description = "Per-service data for dashboard and alarms"
  type = list(object({
    name                    = string
    ecs_service_name        = string
    target_group_arn_suffix = string
  }))
}
