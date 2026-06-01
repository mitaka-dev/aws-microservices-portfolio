output "service_name" {
  description = "ECS service name"
  value       = aws_ecs_service.this.name
}

output "task_sg_id" {
  description = "Task security group ID (add ingress rules externally)"
  value       = aws_security_group.task.id
}

output "target_group_arn_suffix" {
  description = "ALB target group ARN suffix for CloudWatch metrics (format: targetgroup/name/id)"
  value       = aws_lb_target_group.this.arn_suffix
}

output "log_group_name" {
  description = "CloudWatch log group name for this service"
  value       = aws_cloudwatch_log_group.this.name
}
