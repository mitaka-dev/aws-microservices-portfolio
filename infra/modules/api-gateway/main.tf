locals {
  name_prefix = "${var.org}-${var.environment}"
}

# Inline Lambda — temporary /health backend until Phase 3 adds ALB + VPC Link
data "archive_file" "health_lambda" {
  type        = "zip"
  output_path = "${path.module}/health_lambda.zip"

  source {
    content  = "def handler(e, c): return {\"statusCode\": 200, \"body\": '{\"status\":\"ok\"}', \"headers\": {\"Content-Type\": \"application/json\"}}"
    filename = "handler.py"
  }
}

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
    actions = ["sts:AssumeRole"]
  }
}

resource "aws_iam_role" "health_lambda" {
  name               = "${local.name_prefix}-health-lambda"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

resource "aws_iam_role_policy_attachment" "health_lambda_basic" {
  role       = aws_iam_role.health_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_lambda_function" "health" {
  function_name    = "${local.name_prefix}-health"
  role             = aws_iam_role.health_lambda.arn
  runtime          = "python3.12"
  handler          = "handler.handler"
  filename         = data.archive_file.health_lambda.output_path
  source_code_hash = data.archive_file.health_lambda.output_base64sha256

  tags = { Name = "${local.name_prefix}-health" }
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

resource "aws_apigatewayv2_integration" "health" {
  api_id             = aws_apigatewayv2_api.this.id
  integration_type   = "AWS_PROXY"
  integration_uri    = aws_lambda_function.health.invoke_arn
  integration_method = "POST"
}

resource "aws_apigatewayv2_route" "health" {
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "GET /health"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito.id
  target             = "integrations/${aws_apigatewayv2_integration.health.id}"
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = "$default"
  auto_deploy = true
}

resource "aws_lambda_permission" "apigw" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.health.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.this.execution_arn}/*/*"
}

# ── VPC Link → internal ALB (added in Phase 3) ────────────────────────────────

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
