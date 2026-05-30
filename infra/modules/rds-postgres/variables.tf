variable "org" {
  description = "Organisation prefix"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for DB subnet group"
  type        = list(string)
}

variable "db_name" {
  description = "Database name"
  type        = string
  default     = "userdb"
}

variable "db_username" {
  description = "Master DB username"
  type        = string
  default     = "dbadmin"
}

variable "instance_class" {
  description = "RDS instance class"
  type        = string
  default     = "db.t4g.micro"
}

variable "allocated_storage" {
  description = "Allocated storage in GiB"
  type        = number
  default     = 20
}

variable "postgres_version" {
  description = "PostgreSQL major version"
  type        = string
  default     = "16"
}
