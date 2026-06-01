output "alarm_topic_arn" {
  description = "SNS topic ARN that all CloudWatch alarms publish to"
  value       = aws_sns_topic.alarms.arn
}

output "dashboard_name" {
  description = "CloudWatch dashboard name"
  value       = aws_cloudwatch_dashboard.portfolio.dashboard_name
}
