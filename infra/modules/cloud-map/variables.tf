variable "org" {
  description = "Organisation prefix used in resource names"
  type        = string
}

variable "environment" {
  description = "Deployment environment (e.g. dev)"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID for the private DNS namespace"
  type        = string
}

variable "namespace" {
  description = "Private DNS namespace name (e.g. internal.local)"
  type        = string
  default     = "internal.local"
}
