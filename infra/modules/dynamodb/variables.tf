variable "org" {
  description = "Organisation prefix used in resource names"
  type        = string
}

variable "environment" {
  description = "Deployment environment (e.g. dev)"
  type        = string
}

variable "table_name" {
  description = "Logical table name appended to the name prefix"
  type        = string
  default     = "catalog"
}

variable "hash_key" {
  description = "DynamoDB partition key attribute name"
  type        = string
  default     = "pk"
}

variable "sort_key" {
  description = "DynamoDB sort key attribute name"
  type        = string
  default     = "sk"
}
