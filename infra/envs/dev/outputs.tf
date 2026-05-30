output "vpc_id" {
  description = "VPC ID"
  value       = module.network.vpc_id
}

output "public_subnet_ids" {
  description = "Public subnet IDs"
  value       = module.network.public_subnet_ids
}

output "private_subnet_ids" {
  description = "Private subnet IDs"
  value       = module.network.private_subnet_ids
}

output "ecr_repository_urls" {
  description = "ECR repository URLs keyed by service name"
  value       = module.ecr.repository_urls
}

output "cognito_user_pool_id" {
  description = "Cognito User Pool ID"
  value       = module.cognito.user_pool_id
}

output "cognito_app_client_id" {
  description = "Cognito App Client ID"
  value       = module.cognito.app_client_id
}

output "cognito_issuer_uri" {
  description = "Cognito JWT issuer URI"
  value       = module.cognito.issuer_uri
}

output "api_gateway_endpoint" {
  description = "API Gateway invoke URL"
  value       = module.api_gateway.api_endpoint
}

output "alb_dns_name" {
  description = "Internal ALB DNS name"
  value       = module.alb.alb_dns_name
}

output "rds_address" {
  description = "RDS hostname"
  value       = module.rds_postgres.address
  sensitive   = true
}

output "ecs_cluster_arn" {
  description = "ECS cluster ARN"
  value       = module.ecs_cluster.cluster_arn
}

output "redis_primary_endpoint" {
  description = "ElastiCache Redis primary endpoint"
  value       = module.elasticache_redis.primary_endpoint
  sensitive   = true
}

output "dynamodb_catalog_table" {
  description = "DynamoDB catalog table name"
  value       = module.dynamodb_catalog.table_name
}

output "cloud_map_namespace_id" {
  description = "Cloud Map private DNS namespace ID"
  value       = module.cloud_map.namespace_id
}
