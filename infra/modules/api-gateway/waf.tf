data "aws_region" "current" {}

resource "aws_wafv2_web_acl" "api_gateway" {
  name  = "${local.name_prefix}-api-waf"
  scope = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "AWSManagedRulesCommonRuleSet"
    priority = 1

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name_prefix}-common-rules"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "AWSManagedRulesKnownBadInputsRuleSet"
    priority = 2

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name_prefix}-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.name_prefix}-api-waf"
    sampled_requests_enabled   = true
  }

  tags = { Name = "${local.name_prefix}-api-waf" }
}

resource "aws_wafv2_web_acl_association" "api_gateway" {
  resource_arn = "arn:aws:apigateway:${data.aws_region.current.name}::/apis/${aws_apigatewayv2_api.this.id}/stages/${aws_apigatewayv2_stage.default.name}"
  web_acl_arn  = aws_wafv2_web_acl.api_gateway.arn
}
