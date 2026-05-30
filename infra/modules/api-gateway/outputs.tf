output "api_id" {
  description = "API Gateway HTTP API ID"
  value       = aws_apigatewayv2_api.this.id
}

output "api_endpoint" {
  description = "API Gateway invoke URL (stage endpoint)"
  value       = aws_apigatewayv2_stage.default.invoke_url
}

output "vpc_link_sg_id" {
  description = "VPC Link security group ID (add ALB ingress rule externally)"
  value       = aws_security_group.vpc_link.id
}
