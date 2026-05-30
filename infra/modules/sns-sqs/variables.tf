variable "org" {
  description = "Organisation prefix used in resource names"
  type        = string
}

variable "environment" {
  description = "Deployment environment (e.g. dev)"
  type        = string
}

variable "topic_name" {
  description = "Logical SNS topic name appended to the name prefix"
  type        = string
  default     = "orders-events"
}

variable "queue_name" {
  description = "Logical SQS queue name appended to the name prefix"
  type        = string
  default     = "orders-processing"
}

variable "visibility_timeout_seconds" {
  description = "SQS visibility timeout"
  type        = number
  default     = 30
}

variable "max_receive_count" {
  description = "Number of receive attempts before message moves to DLQ"
  type        = number
  default     = 3
}
