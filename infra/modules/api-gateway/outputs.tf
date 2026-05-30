output "api_id" {
  description = "API Gateway HTTP API ID"
  value       = aws_apigatewayv2_api.this.id
}

output "api_endpoint" {
  description = "API Gateway invoke URL (stage endpoint)"
  value       = aws_apigatewayv2_stage.default.invoke_url
}
