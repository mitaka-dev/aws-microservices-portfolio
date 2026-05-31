output "topic_arn" {
  description = "SNS topic ARN"
  value       = aws_sns_topic.this.arn
}

output "queue_url" {
  description = "SQS queue URL"
  value       = aws_sqs_queue.this.url
}

output "queue_arn" {
  description = "SQS queue ARN"
  value       = aws_sqs_queue.this.arn
}

output "dlq_url" {
  description = "Dead-letter queue URL"
  value       = aws_sqs_queue.dlq.url
}

output "dlq_arn" {
  description = "Dead-letter queue ARN"
  value       = aws_sqs_queue.dlq.arn
}

output "queue_name" {
  description = "SQS queue name (used as CloudWatch dimension)"
  value       = aws_sqs_queue.this.name
}

output "dlq_name" {
  description = "Dead-letter queue name (used as CloudWatch dimension)"
  value       = aws_sqs_queue.dlq.name
}
