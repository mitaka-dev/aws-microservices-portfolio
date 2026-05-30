variable "org" {
  description = "Organisation prefix used in resource names"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "cognito_issuer_uri" {
  description = "Cognito issuer URI used by the JWT authorizer"
  type        = string
}

variable "cognito_audience" {
  description = "List of Cognito app client IDs that are valid JWT audiences"
  type        = list(string)
}

variable "vpc_id" {
  description = "VPC ID for VPC Link security group"
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for VPC Link placement"
  type        = list(string)
}

variable "alb_listener_arn" {
  description = "Internal ALB listener ARN for /users/* integration"
  type        = string
}
