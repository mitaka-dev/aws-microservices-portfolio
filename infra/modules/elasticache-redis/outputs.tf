output "primary_endpoint" {
  description = "Redis primary endpoint hostname"
  value       = aws_elasticache_cluster.this.cache_nodes[0].address
}

output "port" {
  description = "Redis port"
  value       = 6379
}

output "sg_id" {
  description = "Redis security group ID"
  value       = aws_security_group.redis.id
}
