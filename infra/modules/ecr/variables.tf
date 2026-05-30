variable "org" {
  description = "Organisation prefix used in resource names"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "service_names" {
  description = "List of service names for which to create ECR repositories"
  type        = list(string)
}

variable "image_tag_mutability" {
  description = "Tag mutability setting for repositories (MUTABLE or IMMUTABLE)"
  type        = string
  default     = "MUTABLE"
}

variable "lifecycle_keep_count" {
  description = "Number of images to retain per repository"
  type        = number
  default     = 10
}

variable "force_delete" {
  description = "Delete repository even if it contains images (needed for tofu destroy in dev)"
  type        = bool
  default     = true
}
