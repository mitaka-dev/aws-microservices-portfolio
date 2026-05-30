locals {
  name_prefix = "${var.org}-${var.environment}"
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

resource "aws_cognito_user_pool" "this" {
  name = "${local.name_prefix}-users"

  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  password_policy {
    minimum_length                   = 8
    require_lowercase                = true
    require_uppercase                = true
    require_numbers                  = true
    require_symbols                  = false
    temporary_password_validity_days = 7
  }

  mfa_configuration = "OPTIONAL"

  software_token_mfa_configuration {
    enabled = true
  }

  tags = { Name = "${local.name_prefix}-users" }
}

resource "aws_cognito_user_pool_client" "app" {
  name         = "${local.name_prefix}-app-client"
  user_pool_id = aws_cognito_user_pool.this.id

  generate_secret = false

  explicit_auth_flows = [
    "ALLOW_USER_PASSWORD_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
    "ALLOW_USER_SRP_AUTH",
  ]
}

# Hosted UI domain — globally unique, so include account ID to avoid collisions
resource "aws_cognito_user_pool_domain" "this" {
  domain       = "${local.name_prefix}-${data.aws_caller_identity.current.account_id}"
  user_pool_id = aws_cognito_user_pool.this.id
}
