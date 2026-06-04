locals {
  name_prefix = "${var.org}-${var.environment}"
}

resource "aws_apigatewayv2_api" "this" {
  name          = "${local.name_prefix}-api"
  protocol_type = "HTTP"

  tags = { Name = "${local.name_prefix}-api" }
}

resource "aws_apigatewayv2_authorizer" "cognito" {
  api_id           = aws_apigatewayv2_api.this.id
  authorizer_type  = "JWT"
  name             = "cognito-jwt"
  identity_sources = ["$request.header.Authorization"]

  jwt_configuration {
    issuer   = var.cognito_issuer_uri
    audience = var.cognito_audience
  }
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = "$default"
  auto_deploy = true

  default_route_settings {
    throttling_rate_limit  = 500
    throttling_burst_limit = 1000
  }
}

# ── VPC Link → internal ALB ───────────────────────────────────────────────────

resource "aws_security_group" "vpc_link" {
  name   = "${local.name_prefix}-vpc-link-sg"
  vpc_id = var.vpc_id

  tags = { Name = "${local.name_prefix}-vpc-link-sg" }
}

resource "aws_vpc_security_group_egress_rule" "vpc_link_all" {
  security_group_id = aws_security_group.vpc_link.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_apigatewayv2_vpc_link" "this" {
  name               = "${local.name_prefix}-vpc-link"
  security_group_ids = [aws_security_group.vpc_link.id]
  subnet_ids         = var.private_subnet_ids

  tags = { Name = "${local.name_prefix}-vpc-link" }
}

resource "aws_apigatewayv2_integration" "alb" {
  api_id                 = aws_apigatewayv2_api.this.id
  integration_type       = "HTTP_PROXY"
  connection_type        = "VPC_LINK"
  connection_id          = aws_apigatewayv2_vpc_link.this.id
  integration_uri        = var.alb_listener_arn
  integration_method     = "ANY"
  payload_format_version = "1.0"
}

resource "aws_apigatewayv2_route" "users" {
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "ANY /users"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito.id
  target             = "integrations/${aws_apigatewayv2_integration.alb.id}"
}

resource "aws_apigatewayv2_route" "users_proxy" {
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "ANY /users/{proxy+}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito.id
  target             = "integrations/${aws_apigatewayv2_integration.alb.id}"
}

resource "aws_apigatewayv2_route" "catalog" {
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "ANY /catalog"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito.id
  target             = "integrations/${aws_apigatewayv2_integration.alb.id}"
}

resource "aws_apigatewayv2_route" "catalog_proxy" {
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "ANY /catalog/{proxy+}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito.id
  target             = "integrations/${aws_apigatewayv2_integration.alb.id}"
}

resource "aws_apigatewayv2_route" "orders" {
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "ANY /orders"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito.id
  target             = "integrations/${aws_apigatewayv2_integration.alb.id}"
}

resource "aws_apigatewayv2_route" "orders_proxy" {
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "ANY /orders/{proxy+}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito.id
  target             = "integrations/${aws_apigatewayv2_integration.alb.id}"
}

resource "aws_apigatewayv2_route" "files" {
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "ANY /files"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito.id
  target             = "integrations/${aws_apigatewayv2_integration.alb.id}"
}

resource "aws_apigatewayv2_route" "files_proxy" {
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "ANY /files/{proxy+}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito.id
  target             = "integrations/${aws_apigatewayv2_integration.alb.id}"
}
