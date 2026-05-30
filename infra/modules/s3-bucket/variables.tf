variable "org" {
  description = "Organisation prefix"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "bucket_suffix" {
  description = "Suffix appended to {org}-{env}- for the bucket name"
  type        = string
  default     = "files"
}
