output "address" {
  description = "RDS instance hostname (no port)"
  value       = aws_db_instance.this.address
}

output "port" {
  description = "RDS port"
  value       = aws_db_instance.this.port
}

output "db_name" {
  description = "Database name"
  value       = aws_db_instance.this.db_name
}

output "secret_arn" {
  description = "Secrets Manager secret ARN for master credentials"
  value       = aws_secretsmanager_secret.db_creds.arn
}

output "sg_id" {
  description = "RDS security group ID (add ingress rules externally)"
  value       = aws_security_group.rds.id
}

output "db_instance_identifier" {
  description = "RDS DB instance identifier (used as CloudWatch dimension)"
  value       = aws_db_instance.this.id
}
