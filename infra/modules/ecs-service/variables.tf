variable "org" {
  description = "Organisation prefix"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "service_name" {
  description = "Service name (e.g. user-service)"
  type        = string
}

variable "image_uri" {
  description = "Full ECR image URI including tag"
  type        = string
}

variable "container_port" {
  description = "Container port the application listens on"
  type        = number
  default     = 8080
}

variable "cpu" {
  description = "Task CPU units (256, 512, 1024, 2048, 4096)"
  type        = number
  default     = 256
}

variable "memory" {
  description = "Task memory in MiB"
  type        = number
  default     = 512
}

variable "desired_count" {
  description = "Desired number of running tasks"
  type        = number
  default     = 1
}

variable "cluster_arn" {
  description = "ECS cluster ARN"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for task ENIs"
  type        = list(string)
}

variable "alb_listener_arn" {
  description = "ALB HTTP listener ARN for target group attachment"
  type        = string
}

variable "path_patterns" {
  description = "ALB listener rule path patterns (e.g. [\"/users\", \"/users/*\"])"
  type        = list(string)
}

variable "listener_rule_priority" {
  description = "ALB listener rule priority (unique per listener)"
  type        = number
  default     = 100
}

variable "health_check_path" {
  description = "ALB target group health check path"
  type        = string
  default     = "/actuator/health"
}

variable "aws_region" {
  description = "AWS region for CloudWatch Logs"
  type        = string
}

variable "env_vars" {
  description = "Non-sensitive environment variables injected into the container"
  type        = map(string)
  default     = {}
}

variable "task_secrets" {
  description = "Sensitive env vars — map of env var name to Secrets Manager valueFrom (ARN with optional :key:: suffix)"
  type        = map(string)
  default     = {}
}

variable "secret_arns_for_exec_role" {
  description = "Base Secrets Manager ARNs the execution role needs secretsmanager:GetSecretValue on"
  type        = list(string)
  default     = []
}
