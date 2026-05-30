locals {
  name_prefix = "${var.org}-${var.environment}"
}

resource "aws_sqs_queue" "dlq" {
  name                      = "${local.name_prefix}-${var.queue_name}-dlq"
  message_retention_seconds = 1209600 # 14 days
}

resource "aws_sqs_queue" "this" {
  name                       = "${local.name_prefix}-${var.queue_name}"
  visibility_timeout_seconds = var.visibility_timeout_seconds
  message_retention_seconds  = 86400 # 1 day

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = var.max_receive_count
  })
}

resource "aws_sns_topic" "this" {
  name = "${local.name_prefix}-${var.topic_name}"
}

resource "aws_sns_topic_subscription" "sqs" {
  topic_arn            = aws_sns_topic.this.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.this.arn
  raw_message_delivery = true
}

resource "aws_sqs_queue_policy" "this" {
  queue_url = aws_sqs_queue.this.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "sns.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.this.arn
      Condition = { ArnEquals = { "aws:SourceArn" = aws_sns_topic.this.arn } }
    }]
  })
}
