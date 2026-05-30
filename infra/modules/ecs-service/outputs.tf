output "service_name" {
  description = "ECS service name"
  value       = aws_ecs_service.this.name
}

output "task_sg_id" {
  description = "Task security group ID (add ingress rules externally)"
  value       = aws_security_group.task.id
}
