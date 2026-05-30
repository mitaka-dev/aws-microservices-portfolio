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
