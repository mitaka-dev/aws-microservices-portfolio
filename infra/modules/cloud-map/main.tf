locals {
  name_prefix = "${var.org}-${var.environment}"
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name = var.namespace
  vpc  = var.vpc_id

  tags = { Name = "${local.name_prefix}-${var.namespace}" }
}
